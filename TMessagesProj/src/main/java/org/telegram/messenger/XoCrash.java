/*
 * T65/T66 — fork-owned crash reporter ("black-box flight recorder").
 *
 * WHY: a subset of Android 15 devices crash right after the splash/login
 * transition while Android 13 is fine. The device fleet is unreachable by
 * adb, so the ONLY way to see the real stack is a reporter that is
 * guaranteed to deliver. T65's first version had two fatal blind spots:
 *   1) the :crash report process re-ran ApplicationLoader.onCreate and
 *      therefore ALSO died at the native-library fail-fast — the report
 *      screen could never render (the user literally saw it never appear);
 *   2) a native (signal) crash or any crash in the ContentProvider phase
 *      left no Java stack at all, so nothing was persisted.
 *
 * T66 design (four independent delivery channels, two processes):
 *   M1  crash handler persists the report AND synchronously POSTs it to
 *       the backend (bounded ~4 s) before the system teardown runs;
 *   M2  every report is archived under files/crash_reports/ and re-uploaded
 *       (with .sent markers) on any later launch from EITHER process;
 *   M3  the :crash report screen auto-POSTs the report it displays;
 *   M4  a launch ping (device name, app version, pending-crash flag) is
 *       POSTed at every app entry — even if the crash itself never made it,
 *       the very next entry reports it.
 * Persistence also mirrors the live report to external app storage so it
 * survives a data clear and is reachable by the user with a file manager.
 *
 * Hard constraints (so the reporter can never become the bug):
 *   - zero dependency on Telegram singletons; only the nullable
 *     ApplicationLoader.applicationContext, read defensively;
 *   - every statement inside try/catch(Throwable); bounded network
 *     timeouts; MIN_PRIORITY daemon threads for anything asynchronous;
 *   - the handler chain always reaches the DEFAULT handler so the system
 *     crash dialog and process teardown behave exactly as before;
 *   - payloads capped (24 KiB report / 28 KiB upload) and the endpoint
 *     re-throttles server-side.
 */
package org.telegram.messenger;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class XoCrash {

    private static final String LIVE_FILE = "last_crash.txt";
    private static final String OFFERED_FILE = "last_crash_offered.txt";
    private static final String EXTERNAL_FILE = "hermes_last_crash.txt";
    private static final String ARCHIVE_DIR = "crash_reports";
    private static final String SENT_SUFFIX = ".sent";

    private static final int MAX_REPORT_BYTES = 24 * 1024;
    private static final int MAX_UPLOAD_BYTES = 28 * 1024;
    private static final int MAX_RESENDS_PER_RUN = 3;
    private static final int MAX_ARCHIVE_FILES = 10;
    private static final long ARCHIVE_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000;

    private static final String ENDPOINT =
            "https://xorbit.ir/tele/api/v1/logs/client_log.php";

    private static volatile boolean installed;
    private static volatile boolean pingSentThisProcess;
    private static volatile String cachedDeviceId;

    private XoCrash() {
    }

    // ------------------------------------------------------------------
    // Installation
    // ------------------------------------------------------------------

    /** Installs the fork crash handler. Idempotent; safe from any process. */
    public static void install() {
        if (installed) {
            return;
        }
        try {
            final Thread.UncaughtExceptionHandler previous =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                // M1a: persist FIRST (file survives even if the upload fails)
                try {
                    persist(throwable);
                } catch (Throwable ignore) {
                }
                // M1b: synchronous best-effort upload from the dying thread —
                // reaches the backend even if the app never launches again.
                try {
                    uploadNow(readFileText(liveFile()), "crash", 4000);
                } catch (Throwable ignore) {
                }
                if (previous != null) {
                    try {
                        previous.uncaughtException(thread, throwable);
                    } catch (Throwable ignore) {
                        // never break the system teardown path
                    }
                }
            });
            installed = true;
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------
    // Offer lifecycle (consume-after-render: a report can never be
    // destroyed by a launch that fails to show the report screen)
    // ------------------------------------------------------------------

    /**
     * Returns the live report text if it was never offered to the user,
     * without modifying anything. Used by ApplicationLoader.onCreate and by
     * LaunchActivity's foreground fallback.
     */
    public static String peekUnofferedReport() {
        try {
            File live = liveFile();
            if (live == null || !live.exists()) {
                return null;
            }
            String text = readFileText(live);
            if (text == null || text.length() == 0) {
                return null;
            }
            File offered = offeredFile();
            if (offered != null && offered.exists()) {
                String prev = readFileText(offered);
                if (text.equals(prev)) {
                    return null; // this exact report was already offered
                }
            }
            return text;
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** Marks a report as shown (called by the report screen after render). */
    public static void markOffered(String text) {
        try {
            File offered = offeredFile();
            if (offered != null && text != null) {
                writeFile(offered, text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignore) {
        }
    }

    /** Archives and clears the live/offered pair (report screen close). */
    public static void clearAll() {
        try {
            File f = liveFile();
            if (f != null && f.exists()) {
                f.delete();
            }
            File o = offeredFile();
            if (o != null && o.exists()) {
                o.delete();
            }
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private static void persist(Throwable throwable) {
        String text = buildReport(throwable);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_REPORT_BYTES) {
            byte[] trimmed = new byte[MAX_REPORT_BYTES];
            System.arraycopy(bytes, 0, trimmed, 0, MAX_REPORT_BYTES);
            bytes = trimmed;
        }
        File live = liveFile();
        if (live != null) {
            writeFile(live, bytes);
        }
        // archive for the M2 resend channel (timestamped, per-run unique)
        try {
            File dir = archiveDir();
            if (dir != null) {
                long ts = System.currentTimeMillis();
                File dst = new File(dir, "crash-" + ts + "-"
                        + Integer.toHexString((int) (ts & 0xFFFF)) + ".txt");
                writeFile(dst, bytes);
            }
        } catch (Throwable ignore) {
        }
        // mirror to external app storage (survives "clear data" of the
        // internal files dir and is user-reachable via a file manager)
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx != null) {
                File ext = ctx.getExternalFilesDir(null);
                if (ext != null) {
                    writeFile(new File(ext, EXTERNAL_FILE), bytes);
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private static String buildReport(Throwable throwable) {
        StringWriter sw = new StringWriter(2048);
        PrintWriter pw = new PrintWriter(sw);
        pw.println("Hermes crash report");
        pw.println("time: " + System.currentTimeMillis());
        pw.println("model: " + Build.MANUFACTURER + " " + Build.MODEL
                + " (" + Build.DEVICE + ")");
        pw.println("android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        pw.println("fingerprint: " + Build.FINGERPRINT);
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx != null) {
                android.content.pm.PackageInfo pi = ctx.getPackageManager()
                        .getPackageInfo(ctx.getPackageName(), 0);
                pw.println("version: " + pi.versionName + " (" + pi.versionCode + ")");
            }
        } catch (Throwable ignore) {
        }
        pw.println("process: " + currentProcessName());
        pw.println("device-id: " + deviceId());
        pw.println("thread: " + Thread.currentThread().getName());
        pw.println();
        throwable.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    // ------------------------------------------------------------------
    // Delivery channels
    // ------------------------------------------------------------------

    /**
     * POSTs the text to the backend log receiver. Pure java.net, no app
     * dependencies, all failures swallowed (returns false). kind is
     * "crash" or "ping".
     */
    public static boolean uploadNow(String text, String kind, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            if (text == null || text.length() == 0) {
                return false;
            }
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            if (body.length > MAX_UPLOAD_BYTES) {
                byte[] trimmed = new byte[MAX_UPLOAD_BYTES];
                System.arraycopy(body, 0, trimmed, 0, MAX_UPLOAD_BYTES);
                body = trimmed;
            }
            String url = ENDPOINT + "?kind=" + URLEncoder.encode(kind, "UTF-8")
                    + "&devid=" + URLEncoder.encode(deviceId(), "UTF-8");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(body.length);
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            conn.setRequestProperty("X-Hermes-Kind", kind);
            conn.setRequestProperty("User-Agent", "Hermes-Diag/1");
            OutputStream os = conn.getOutputStream();
            try {
                os.write(body);
                os.flush();
            } finally {
                try {
                    os.close();
                } catch (Throwable ignore) {
                }
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in != null) {
                try {
                    byte[] sink = new byte[512];
                    while (in.read(sink) >= 0) {
                        // drain to allow connection reuse
                    }
                } catch (Throwable ignore) {
                }
                try {
                    in.close();
                } catch (Throwable ignore) {
                }
            }
            return code >= 200 && code < 300;
        } catch (Throwable ignore) {
            return false;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /**
     * M2: re-upload archived reports that never reached the backend.
     * Bounded per run; marks successes with a .sent sidecar.
     */
    public static void resendUnsentReports() {
        try {
            File dir = archiveDir();
            if (dir == null || !dir.isDirectory()) {
                return;
            }
            sweepArchive(dir);
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            int budget = MAX_RESENDS_PER_RUN;
            for (File file : files) {
                if (budget <= 0) {
                    return;
                }
                String name = file.getName();
                if (!name.endsWith(".txt")) {
                    continue;
                }
                File sent = new File(dir, name + SENT_SUFFIX);
                if (sent.exists()) {
                    continue;
                }
                String text = readFileText(file);
                if (text == null || text.length() == 0) {
                    continue;
                }
                budget--;
                if (uploadNow(text, "crash", 6000)) {
                    writeFile(sent, new byte[0]);
                }
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * M4: launch ping — device identity + app version + whether an
     * unreported crash is pending. Fires once per process from the main
     * process launch path; the backend relays it to the owner account.
     */
    public static void sendLaunchPing() {
        if (pingSentThisProcess) {
            return;
        }
        pingSentThisProcess = true;
        try {
            StringBuilder sb = new StringBuilder(512);
            sb.append("Hermes launch ping\n");
            sb.append("time: ").append(System.currentTimeMillis()).append('\n');
            sb.append("model: ").append(Build.MANUFACTURER).append(' ')
                    .append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n");
            sb.append("android: ").append(Build.VERSION.RELEASE)
                    .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
            try {
                Context ctx = ApplicationLoader.applicationContext;
                if (ctx != null) {
                    android.content.pm.PackageInfo pi = ctx.getPackageManager()
                            .getPackageInfo(ctx.getPackageName(), 0);
                    sb.append("version: ").append(pi.versionName)
                            .append(" (").append(pi.versionCode).append(")\n");
                }
            } catch (Throwable ignore) {
            }
            sb.append("process: ").append(currentProcessName()).append('\n');
            sb.append("device-id: ").append(deviceId()).append('\n');
            boolean pending = peekUnofferedReport() != null;
            sb.append("pending-crash: ").append(pending ? "YES" : "no").append('\n');
            uploadNow(sb.toString(), "ping", 5000);
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------
    // Helpers (pure java/android, no Telegram singletons)
    // ------------------------------------------------------------------

    private static void sweepArchive(File dir) {
        try {
            long now = System.currentTimeMillis();
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            int txtCount = 0;
            for (File file : files) {
                String name = file.getName();
                if (file.lastModified() < now - ARCHIVE_MAX_AGE_MS) {
                    file.delete();
                    new File(dir, name + SENT_SUFFIX).delete();
                    continue;
                }
                if (name.endsWith(".txt")) {
                    txtCount++;
                }
            }
            // hard cap: oldest .txt beyond MAX_ARCHIVE_FILES are removed
            int excess = txtCount - MAX_ARCHIVE_FILES;
            if (excess > 0) {
                for (File file : files) {
                    if (excess <= 0) {
                        break;
                    }
                    if (file.getName().endsWith(".txt")) {
                        new File(dir, file.getName() + SENT_SUFFIX).delete();
                        if (file.delete()) {
                            excess--;
                        }
                    }
                }
            }
        } catch (Throwable ignore) {
        }
    }

    public static String currentProcessName() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                String name = Application.getProcessName();
                if (name != null && name.length() > 0) {
                    return name;
                }
            }
        } catch (Throwable ignore) {
        }
        try (FileInputStream in = new FileInputStream("/proc/self/cmdline")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            String s = new String(out.toByteArray(), "UTF-8").trim();
            int zero = s.indexOf('\0');
            return zero >= 0 ? s.substring(0, zero) : s;
        } catch (Throwable ignore) {
            return "unknown";
        }
    }

    private static String deviceId() {
        String cached = cachedDeviceId;
        if (cached != null) {
            return cached;
        }
        String id = "unknown";
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx != null) {
                String raw = Settings.Secure.getString(
                        ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
                if (raw != null && raw.length() > 0) {
                    id = raw;
                }
            }
        } catch (Throwable ignore) {
        }
        cachedDeviceId = id;
        return id;
    }

    private static File archiveDir() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) {
                return null;
            }
            File dir = new File(ctx.getFilesDir(), ARCHIVE_DIR);
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                return null;
            }
            return dir;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static File liveFile() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) {
                return null;
            }
            return new File(ctx.getFilesDir(), LIVE_FILE);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static File offeredFile() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) {
                return null;
            }
            return new File(ctx.getFilesDir(), OFFERED_FILE);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static void writeFile(File file, byte[] bytes) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        } catch (IOException ignore) {
        }
    }

    private static String readFileText(File file) {
        try {
            if (file == null || !file.exists()) {
                return null;
            }
            long len = file.length();
            if (len > MAX_REPORT_BYTES) {
                len = MAX_REPORT_BYTES;
            }
            byte[] buf = new byte[(int) len];
            try (FileInputStream in = new FileInputStream(file)) {
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n < 0) {
                        break;
                    }
                    off += n;
                }
            }
            return new String(buf, StandardCharsets.UTF_8);
        } catch (Throwable ignore) {
            return null;
        }
    }
}

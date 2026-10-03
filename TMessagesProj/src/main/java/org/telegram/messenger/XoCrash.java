/*
 * T65 — fork-owned crash reporter.
 *
 * WHY: a subset of Android 15 devices crash right after the login page
 * renders. Static audit of the whole startup surface (manifest, FGS types,
 * receiver export flags, signing schemes, PendingIntent mutability, startup
 * call graph) found nothing that enforces on API 35 for a targetSdk 34 app,
 * and the container has no way to run an API 35 emulator. So the app now
 * carries its own black-box flight recorder: any uncaught exception is
 * persisted with a device fingerprint, and the NEXT launch surfaces it in a
 * SEPARATE PROCESS (":crash") that survives the crash-looping main process —
 * the user can share the exact stack trace through any messenger.
 *
 * Design constraints:
 *  - install() must never throw and must never slow startup (file I/O only
 *    inside the crash handler itself, which runs once, at death).
 *  - The handler chain always reaches the DEFAULT handler, so the system
 *    "app has stopped" dialog and process teardown behave exactly as before.
 *  - Report files are capped (24 KiB) so a runaway stack cannot fill storage.
 */
package org.telegram.messenger;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

public final class XoCrash {

    private static final String REPORT_FILE = "last_crash.txt";
    private static final String REPORT_SENT_FILE = "last_crash_reported.txt";
    private static final int MAX_REPORT_BYTES = 24 * 1024;

    private XoCrash() {
    }

    /** Installs the fork crash handler. Safe to call from any process. */
    public static void install() {
        try {
            final Thread.UncaughtExceptionHandler previous =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                try {
                    persist(throwable);
                } catch (Throwable ignore) {
                }
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
            });
        } catch (Throwable ignore) {
        }
    }

    /**
     * Returns the pending crash report text (or null) and marks it consumed.
     * Called once per process start from ApplicationLoader.onCreate.
     */
    public static String consumePendingReport() {
        try {
            File file = reportFile();
            if (file == null || !file.exists()) {
                return null;
            }
            byte[] raw = readCapped(file);
            String text = new String(raw, StandardCharsets.UTF_8);
            File sent = sentFile();
            try (FileOutputStream out = new FileOutputStream(sent)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            // keep last_crash.txt readable while the report screen is open;
            // it is archived to last_crash_reported.txt and the live marker
            // is removed so the NEXT process start does not re-offer it.
            File done = new File(file.getParentFile(), REPORT_FILE + ".offered");
            try (FileOutputStream out = new FileOutputStream(done)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (!file.delete()) {
                // fall through: content already archived; offer-gate is the
                // .offered marker below.
            }
            return text;
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** Live (not yet offered) report text, or null. Used by the report screen. */
    public static String readLiveReport() {
        try {
            File file = reportFile();
            if (file == null || !file.exists()) {
                return null;
            }
            return new String(readCapped(file), StandardCharsets.UTF_8);
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * True when a report exists and was already offered to the user in a
     * previous process start (used to avoid nagging on every launch).
     */
    public static boolean hasOfferedReport() {
        try {
            File done = new File(sentFile().getParentFile(), REPORT_FILE + ".offered");
            return done.exists();
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** Archives and clears all report artifacts (called by the report screen). */
    public static void clearAll() {
        try {
            File f = reportFile();
            if (f != null && f.exists()) {
                f.delete();
            }
            File done = new File(sentFile().getParentFile(), REPORT_FILE + ".offered");
            if (done.exists()) {
                done.delete();
            }
        } catch (Throwable ignore) {
        }
    }

    private static void persist(Throwable throwable) {
        File file = reportFile();
        if (file == null) {
            return;
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("Hermes crash report");
        pw.println("time: " + System.currentTimeMillis());
        pw.println("model: " + Build.MANUFACTURER + " " + Build.MODEL
                + " (" + Build.DEVICE + ")");
        pw.println("android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        pw.println("fingerprint: " + Build.FINGERPRINT);
        try {
            android.content.Context ctx = ApplicationLoader.applicationContext;
            if (ctx != null) {
                android.content.pm.PackageInfo pi = ctx.getPackageManager()
                        .getPackageInfo(ctx.getPackageName(), 0);
                pw.println("version: " + pi.versionName + " (" + pi.versionCode + ")");
            }
        } catch (Throwable ignore) {
        }
        pw.println("thread: " + Thread.currentThread().getName());
        pw.println();
        throwable.printStackTrace(pw);
        pw.flush();
        byte[] bytes = sw.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_REPORT_BYTES) {
            byte[] trimmed = new byte[MAX_REPORT_BYTES];
            System.arraycopy(bytes, 0, trimmed, 0, MAX_REPORT_BYTES);
            bytes = trimmed;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        } catch (IOException ignore) {
        }
    }

    private static File reportFile() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) {
                return null;
            }
            return new File(ctx.getFilesDir(), REPORT_FILE);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static File sentFile() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) {
                return null;
            }
            return new File(ctx.getFilesDir(), REPORT_SENT_FILE);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static byte[] readCapped(File file) throws IOException {
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
        return buf;
    }
}

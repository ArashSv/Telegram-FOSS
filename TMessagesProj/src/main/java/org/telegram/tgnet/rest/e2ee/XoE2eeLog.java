package org.telegram.tgnet.rest.e2ee;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.XoCrash;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * T74 — structured E2EE diagnostics log ("لاگ برای همه چیز", user request).
 *
 * <p>Purpose: the user hit a total 1:1-E2EE outage (lock-emoji rows + send
 * errors) that was invisible — no error surfaced, no safety number, nothing.
 * This channel makes every protocol decision VISIBLE after the fact, on the
 * server, without adb.
 *
 * <p>WHAT is recorded (ids, counters, reason codes, state transitions):
 * register/re-register (+ server otk_remaining), bundle fetches (+ otk
 * remaining), session build/rebuild, encrypt ok (+wire type) / fail (+reason),
 * decrypt ok (+wire type) / fail (+reason class), trust: pin/flag/heal/sweep,
 * sent-inner note/hit/miss, otk refill, store wipe/corruption.
 *
 * <p>WHAT is NEVER recorded (enforced by API shape — no parameter exists for
 * it): message content, inner JSON, key material, file keys, phone numbers.
 * Detail strings are hard-capped at 180 chars; entries are line-flat ASCII+UTF.
 *
 * <p>Transport: mirrors the T66 pipeline — batches are POSTed UNAUTHENTICATED
 * to logs/client_log.php (kind=e2ee, falling back to kind=ping; the v2.11.1
 * host currently stalls unknown kinds — the breaker learns within one batch),
 * which stores android_log-*.log files server-side AND relays a copy into the
 * operator's own Saved Messages. Local mirror: files/e2ee_logs/xoe2ee-<acct>.log
 * (256 KB cap, one rotation) so the latest history survives process death.
 *
 * <p>Lifecycle: {@link #startUploader()} is called from ApplicationLoader
 * (main process only). The E2EE classes only ever call {@link #event} —
 * zero network, zero scheduling — so JVM tests stay offline and fast.
 */
public final class XoE2eeLog {

    public static final String KIND_E2EE = "e2ee";
    public static final String KIND_PING = "ping";

    private static final int RING_MAX = 600;
    private static final int DETAIL_MAX = 180;
    private static final long MIN_UPLOAD_INTERVAL_MS = 8 * 60 * 1000L; // <= ~7 relayed/hour
    private static final long FLUSH_PERIOD_MS = 4 * 60 * 1000L;
    private static final int FLUSH_EVENT_THRESHOLD = 40;
    private static final int FILE_MAX_BYTES = 256 * 1024;
    private static final int BATCH_MAX_BYTES = 30 * 1024;

    private static final Object lock = new Object();
    private static final ArrayDeque<String> ring = new ArrayDeque<>();
    private static final ArrayDeque<String> unsent = new ArrayDeque<>(); // lines not yet uploaded
    private static long lastUploadAt;
    private static int eventsSinceUpload;
    /** Circuit breaker: unknown-kind stalls learn to skip straight to ping. */
    private static int e2eeKindFailures;
    private static boolean uploaderStarted;

    private XoE2eeLog() {
    }

    // ------------------------------------------------------------------ API (called from the E2EE classes)

    /**
     * Records one protocol event. All arguments are identifiers/codes, never
     * content. Peer and detail are optional (pass 0 / null).
     *
     * @param account app account number
     * @param event   short tag, e.g. "register.ok", "encrypt.fail", "trust.flagCleared"
     * @param peer    peer user id (0 = n/a)
     * @param detail  id/counter/reason string, hard-capped, no content
     */
    public static void event(int account, String event, long peer, String detail) {
        if (event == null) {
            return;
        }
        String line = System.currentTimeMillis()
                + "|" + account
                + "|v" + BuildVars.BUILD_VERSION_STRING
                + "|" + event
                + "|" + peer
                + "|" + cap(detail);
        synchronized (lock) {
            ring.addLast(line);
            while (ring.size() > RING_MAX) {
                ring.removeFirst();
            }
            unsent.addLast(line);
            while (unsent.size() > RING_MAX) {
                unsent.removeFirst();
            }
            eventsSinceUpload++;
        }
        appendToFile(account, line);
        // never log message content — the line is ids/codes only by construction
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("XoE2eeLog " + line);
        }
        maybeFlushThreshold();
    }

    /** Convenience overload without detail. */
    public static void event(int account, String event, long peer) {
        event(account, event, peer, null);
    }

    // ------------------------------------------------------------------ uploader (app process only)

    /** Starts the periodic flush. Called once from ApplicationLoader (main process). */
    public static void startUploader() {
        synchronized (lock) {
            if (uploaderStarted) {
                return;
            }
            uploaderStarted = true;
        }
        try {
            java.util.concurrent.ScheduledExecutorService pool =
                    java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "XoE2eeLogUploader");
                        t.setDaemon(true);
                        t.setPriority(Thread.MIN_PRIORITY);
                        return t;
                    });
            pool.scheduleWithFixedDelay(XoE2eeLog::flushSafe, FLUSH_PERIOD_MS, FLUSH_PERIOD_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            FileLog.e("XoE2eeLog: uploader start failed", t);
        }
    }

    /** Flush trigger used by the event counter (fire-and-forget, never blocks callers). */
    private static void maybeFlushThreshold() {
        int count;
        synchronized (lock) {
            count = eventsSinceUpload;
        }
        long since = System.currentTimeMillis() - lastUploadAt;
        if (count >= FLUSH_EVENT_THRESHOLD && since > MIN_UPLOAD_INTERVAL_MS) {
            flushSafe();
        }
    }

    private static void flushSafe() {
        try {
            flush();
        } catch (Throwable t) {
            FileLog.e("XoE2eeLog: flush failed", t);
        }
    }

    /** Builds and POSTs one batch of unsent lines. Runs on the uploader thread or a test thread. */
    static void flush() {
        java.util.List<String> batchLines = new java.util.ArrayList<>();
        String batch;
        synchronized (lock) {
            if (unsent.isEmpty()) {
                return;
            }
            long since = System.currentTimeMillis() - lastUploadAt;
            if (since < MIN_UPLOAD_INTERVAL_MS && eventsSinceUpload < FLUSH_EVENT_THRESHOLD * 3) {
                return; // throttle: the relay (Saved Messages) is capped server-side anyway
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Hermes e2ee diag v").append(BuildVars.BUILD_VERSION_STRING).append('\n');
            int bytes = sb.length();
            Iterator<String> it = unsent.iterator();
            while (it.hasNext()) {
                String line = it.next();
                int len = line.length() + 1;
                if (bytes + len > BATCH_MAX_BYTES) {
                    break;
                }
                sb.append(line).append('\n');
                bytes += len;
                batchLines.add(line);
            }
            batch = sb.toString();
        }
        if (batchLines.isEmpty()) {
            return;
        }
        String kind = KIND_E2EE;
        if (e2eeKindFailures >= 2) {
            kind = KIND_PING; // breaker open: this host stalls unknown kinds
        }
        boolean ok = XoCrash.uploadNow(batch, kind, 15000);
        if (!ok && KIND_E2EE.equals(kind)) {
            e2eeKindFailures++;
            // one clean retry on the fallback kind for THIS batch
            ok = XoCrash.uploadNow(batch, KIND_PING, 15000);
        } else if (ok && KIND_E2EE.equals(kind)) {
            e2eeKindFailures = 0;
        }
        if (ok) {
            synchronized (lock) {
                // remove exactly the lines shipped in this batch (first
                // occurrence each, in order — duplicates are rare but legal)
                for (String line : batchLines) {
                    unsent.removeFirstOccurrence(line);
                }
                eventsSinceUpload = 0;
                lastUploadAt = System.currentTimeMillis();
            }
        }
        // on total failure: lines stay queued; the next tick retries (bounded by ring cap)
    }

    // ------------------------------------------------------------------ local file mirror

    private static void appendToFile(int account, String line) {
        OutputStreamWriter w = null;
        try {
            File dir = dirFor(account);
            if (dir == null) {
                return;
            }
            File f = new File(dir, "xoe2ee-" + account + ".log");
            if (f.length() > FILE_MAX_BYTES) {
                // rotate: current -> .1 (replacing the old .1), then continue appending
                File rotated = new File(dir, "xoe2ee-" + account + ".1");
                //noinspection ResultOfMethodCallIgnored
                rotated.delete();
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(rotated);
            }
            w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8);
            w.write(line);
            w.write('\n');
        } catch (Throwable ignore) {
            // diagnostics must never break the protocol path
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static File dirFor(int account) {
        try {
            android.content.Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null || ctx.getFilesDir() == null) {
                return null;
            }
            File dir = new File(ctx.getFilesDir(), "e2ee_logs");
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                return null;
            }
            return dir;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String cap(String detail) {
        if (detail == null) {
            return "-";
        }
        String flat = detail.replace('\n', ' ').replace('\r', ' ').replace('|', '/');
        return flat.length() <= DETAIL_MAX ? flat : flat.substring(0, DETAIL_MAX);
    }

    // ------------------------------------------------------------------ test seams

    static void resetForTests() {
        synchronized (lock) {
            ring.clear();
            unsent.clear();
            eventsSinceUpload = 0;
            lastUploadAt = 0;
            e2eeKindFailures = 0;
            uploaderStarted = false;
        }
    }
}

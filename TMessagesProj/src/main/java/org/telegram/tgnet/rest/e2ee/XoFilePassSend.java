package org.telegram.tgnet.rest.e2ee;

import android.util.SparseArray;

/**
 * T82 — carries the user-typed file رمز from the send dialog into the send
 * pipeline. The dialog arms it for (account, dialogId); the prepare path
 * peeks it into the message's params ("xoPass") which ride into
 * {@code noteE2eeUploadIntent} → the pw intent → the XOSF2 envelope. Cleared
 * when the send invocation completes. A stale arm is harmless by
 * construction: it is read ONLY when the user explicitly chose the
 * encrypted-file send again — and that choice always re-arms.
 */
public final class XoFilePassSend {

    /**
     * The arm self-expires: the prepare pipeline stamps the رمز into each
     * item's params synchronously for documents and on a short-lived worker
     * thread for videos — a fixed 60s TTL covers every real stamping window
     * WITHOUT a racy eager clear (which could drop the رمز mid-batch and
     * silently downgrade a video send to the broken legacy path). RAM-only
     * exposure is bounded to the TTL.
     */
    private static final long ARM_TTL_MS = 60_000L;

    private static final SparseArray<java.util.HashMap<Long, String>> armed = new SparseArray<>();
    private static final SparseArray<java.util.HashMap<Long, Long>> armedAt = new SparseArray<>();

    private XoFilePassSend() {
    }

    /** The dialog confirmed a رمز for this chat's next encrypted send. */
    public static void arm(int account, long dialogId, String password) {
        if (password == null || password.length() == 0 || dialogId == 0) {
            return;
        }
        synchronized (XoFilePassSend.class) {
            java.util.HashMap<Long, String> map = armed.get(account);
            if (map == null) {
                map = new java.util.HashMap<>();
                armed.put(account, map);
            }
            map.put(dialogId, password);
            java.util.HashMap<Long, Long> ts = armedAt.get(account);
            if (ts == null) {
                ts = new java.util.HashMap<>();
                armedAt.put(account, ts);
            }
            ts.put(dialogId, System.currentTimeMillis());
        }
    }

    /** Non-destructive read for the prepare path (batch sends share one arm). */
    public static String peek(int account, long dialogId) {
        synchronized (XoFilePassSend.class) {
            java.util.HashMap<Long, String> map = armed.get(account);
            if (map == null) {
                return null;
            }
            java.util.HashMap<Long, Long> ts = armedAt.get(account);
            Long at = ts == null ? null : ts.get(dialogId);
            if (at == null || System.currentTimeMillis() - at > ARM_TTL_MS) {
                map.remove(dialogId);
                if (ts != null) {
                    ts.remove(dialogId);
                }
                return null;
            }
            return map.get(dialogId);
        }
    }

    /** Explicit clear after a fully synchronous send path (defensive). */
    public static void clear(int account, long dialogId) {
        synchronized (XoFilePassSend.class) {
            java.util.HashMap<Long, String> map = armed.get(account);
            if (map != null) {
                map.remove(dialogId);
            }
            java.util.HashMap<Long, Long> ts = armedAt.get(account);
            if (ts != null) {
                ts.remove(dialogId);
            }
        }
    }
}

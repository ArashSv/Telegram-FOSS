package org.telegram.tgnet.rest;

import android.os.SystemClock;

/**
 * T49: the server-corrected clock for REST accounts.
 *
 * Presence lives and dies by clock domains: the backend stamps
 * {@code last_seen_at} with the HOST clock and the status wire shapes carry
 * server-epoch seconds ({@code expires}, {@code was_online}), while every
 * legacy render site compares them against
 * {@link org.telegram.tgnet.ConnectionsManager#getCurrentTime()} — which, in
 * the MTProto world, was server-corrected by the handshake. This fork has no
 * handshake, so the correction source is the poll we already receive:
 * {@code /sync/index.php} answers {@code server_time} on every tick.
 *
 * The delta is anchored on {@link SystemClock#elapsedRealtime()} (monotonic,
 * immune to mid-session wall-clock jumps): current server time = the last
 * observed server time + elapsed since that observation. A delta beyond
 * ±24 h is treated as garbage and ignored (trust the device instead); before
 * the first healthy poll the clock simply reports the device wall clock —
 * exactly what the fork did before this class existed, so behavior can only
 * improve, never regress.
 *
 * Thread-safety: one tiny synchronized section per read; called from UI
 * (status formatting) and the poller scheduler alike.
 */
public final class XoClock {

    private static final Object LOCK = new Object();
    private static final long MAX_TRUSTED_DELTA_SECONDS = 24 * 3600;

    private static long serverAtSyncSeconds;
    private static long elapsedAtSyncMs;
    private static long deviceAtSyncSeconds;

    private XoClock() {
    }

    /** Feed from a sync page's {@code server_time}; no-op for absent/zero values. */
    public static void onServerTime(long serverTimeSeconds) {
        if (serverTimeSeconds <= 0) {
            return;
        }
        long deviceNow = System.currentTimeMillis() / 1000;
        long delta = serverTimeSeconds - deviceNow;
        if (Math.abs(delta) > MAX_TRUSTED_DELTA_SECONDS) {
            return;
        }
        synchronized (LOCK) {
            serverAtSyncSeconds = serverTimeSeconds;
            elapsedAtSyncMs = SystemClock.elapsedRealtime();
            deviceAtSyncSeconds = deviceNow;
        }
    }

    /**
     * Server-corrected unix seconds. Before any sync observation (or when the
     * only observed delta was rejected as garbage) this is the device wall
     * clock — the pre-T49 behavior.
     */
    public static int currentTimeSeconds() {
        synchronized (LOCK) {
            if (serverAtSyncSeconds == 0) {
                return (int) (System.currentTimeMillis() / 1000);
            }
            long elapsedSinceSyncMs = SystemClock.elapsedRealtime() - elapsedAtSyncMs;
            return (int) (deviceAtSyncSeconds + (serverAtSyncSeconds - deviceAtSyncSeconds)
                    + elapsedSinceSyncMs / 1000);
        }
    }
}

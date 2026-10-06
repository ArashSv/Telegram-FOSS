package org.telegram.tgnet.rest.e2ee;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * T75 — "chat creation request" semantics for E2EE 1:1 chats (user
 * requirement: a chat must be creatable — and messageable — even when the
 * peer has no keys yet: not logged in, fresh install, old build).
 *
 * <p>Contract: the private chat row is ALREADY created server-side before
 * the encryption gate runs (requireChatId → chats/create-private), so the
 * peer sees the chat on their next sync. What cannot happen yet is the
 * ENCRYPTED send. Instead of failing the message (the pre-T75 behavior),
 * the send error branches call {@link #maybeDefer}: the row STAYS in the
 * sending state (clock icon — standard Telegram pending semantics, no error
 * bulletin, no plaintext fallback), and the dialog joins the watch set.
 *
 * <p>The probe loop asks the backend {@code GET e2ee/keys/exists.php} (a
 * pure SELECT — never bundle.php, which CONSUMES one-time prekeys) whether
 * each watched peer has registered keys. On the first "yes" the dialog's
 * pending rows (mid&lt;0 AND send_state=SENDING, persisted by the tree) are
 * re-dispatched through {@link SendMessagesHelper#retrySendMessage} — the
 * full pipeline, exactly as if the user had tapped retry. Single-flight per
 * dialog, bounded probe cadence, watch set persisted across process death.
 *
 * <p>Conflict safety ("بدون تداخل"): the flush re-enters the canonical send
 * path, so ordering, key binding (same location → same upload key) and
 * server-side dedupe keep their usual guarantees; a flush that hits a
 * still-keyless peer simply re-defers. Identity-rotation failures are NEVER
 * deferred (only the "no keys yet" code qualifies).
 */
public final class XoPendingKeys {

    /** The ONLY error text that qualifies for deferral. */
    public static final String DEFERRABLE_CODE = "E2EE_NO_PEER_KEYS";

    private static final long PROBE_INTERVAL_MS = 45_000;
    private static final long PROBE_INTERVAL_BACKGROUND_MS = 3 * 60_000;
    private static final long MIN_FLUSH_GAP_MS = 60_000;

    private static final XoPendingKeys[] instances = new XoPendingKeys[UserConfig.MAX_ACCOUNT_COUNT];

    public static XoPendingKeys getInstance(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            account = 0;
        }
        XoPendingKeys mgr;
        synchronized (XoPendingKeys.class) {
            mgr = instances[account];
            if (mgr == null) {
                mgr = new XoPendingKeys(account);
                instances[account] = mgr;
            }
        }
        return mgr;
    }

    private final int account;
    private final Object lock = new Object();
    private final Set<Long> watchedPeers = new HashSet<>();
    private final Set<Long> flushing = new HashSet<>();
    private final SharedPreferences prefs;
    private boolean scheduled;
    private boolean started;

    private XoPendingKeys(int account) {
        this.account = account;
        this.prefs = ApplicationLoader.applicationContext.getSharedPreferences(
                "xopendingkeys_" + account, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ defer seam (called from send error branches)

    /**
     * Deferral gate for the send error branches. Handles EXACTLY the
     * "peer has no keys yet" code and nothing else.
     *
     * @return true when the rows were deferred — the caller must NOT mark
     *         them as send-error, NOT show an error bulletin, and simply
     *         return (the rows keep their clock icon and the probe loop
     *         re-dispatches them when the peer's keys appear)
     */
    public boolean maybeDefer(String errorText, List<TLRPC.Message> rows, boolean scheduled_) {
        if (!deferrable(errorText, rows, UserConfig.getInstance(account).clientUserId)) {
            return false;
        }
        for (int a = 0; a < rows.size(); a++) {
            watch(rows.get(a).dialog_id);
        }
        XoE2eeLog.event(account, "pending.defer", rows.get(0).dialog_id,
                "rows=" + rows.size() + " code=" + errorText);
        startProbing();
        return true;
    }

    /**
     * Pure decision core (JVM-testable): only the exact "no keys yet" code,
     * only non-empty rows, only PRIVATE non-self dialogs (groups stay
     * plaintext — there is nothing to wait for; self-chats are never E2EE).
     */
    public static boolean deferrable(String errorText, List<TLRPC.Message> rows, long selfId) {
        if (errorText == null || !DEFERRABLE_CODE.equals(errorText) || rows == null || rows.isEmpty()) {
            return false;
        }
        for (int a = 0; a < rows.size(); a++) {
            TLRPC.Message row = rows.get(a);
            long dialogId = row.dialog_id;
            if (dialogId <= 0 || dialogId == selfId) {
                return false; // group / self rows never defer
            }
        }
        return true;
    }

    /** Convenience overload for the single-row send callbacks. */
    public boolean maybeDefer(String errorText, TLRPC.Message row, boolean scheduled_) {
        if (row == null) {
            return false;
        }
        ArrayList<TLRPC.Message> list = new ArrayList<>(1);
        list.add(row);
        return maybeDefer(errorText, list, scheduled_);
    }

    /**
     * Overload for the group callbacks (albums carry MessageObject lists).
     * T76: NOT a generic erasure-compatible overload of the Message variant
     * (same erasure = compile error); a distinct name keeps both lists
     * first-class without boxing through a Map.
     */
    public boolean maybeDeferObjects(String errorText, List<MessageObject> rows, boolean scheduled_) {
        if (errorText == null || !DEFERRABLE_CODE.equals(errorText) || rows == null || rows.isEmpty()) {
            return false;
        }
        ArrayList<TLRPC.Message> owners = new ArrayList<>(rows.size());
        for (int a = 0; a < rows.size(); a++) {
            owners.add(rows.get(a).messageOwner);
        }
        return maybeDefer(errorText, owners, scheduled_);
    }

    // ------------------------------------------------------------------ watch set

    private void watch(long dialogId) {
        synchronized (lock) {
            watchedPeers.add(dialogId);
            persistLocked();
        }
    }

    public boolean isWatched(long dialogId) {
        synchronized (lock) {
            return watchedPeers.contains(dialogId);
        }
    }

    public int watchedCount() {
        synchronized (lock) {
            return watchedPeers.size();
        }
    }

    /** Test seam: forget everything (JVM suite). */
    public void resetForTests() {
        synchronized (lock) {
            watchedPeers.clear();
            flushing.clear();
            flushTimestamps.clear();
            started = false;
            scheduled = false;
        }
    }

    private void persistLocked() {
        try {
            JSONArray arr = new JSONArray();
            for (Long did : watchedPeers) {
                arr.put(did);
            }
            prefs.edit().putString("dialogs", arr.toString()).apply();
        } catch (Exception e) {
            FileLog.e("XoPendingKeys: persist failed", e);
        }
    }

    private void restoreLocked() {
        if (started) {
            return;
        }
        started = true;
        try {
            String raw = prefs.getString("dialogs", null);
            if (raw != null) {
                JSONArray arr = new JSONArray(raw);
                for (int a = 0; a < arr.length(); a++) {
                    watchedPeers.add(arr.optLong(a, 0));
                }
            }
        } catch (Exception e) {
            FileLog.e("XoPendingKeys: restore failed", e);
        }
    }

    // ------------------------------------------------------------------ probe loop

    /** Starts (or resumes) the periodic probe — safe to call repeatedly. */
    public void startProbing() {
        synchronized (lock) {
            restoreLocked();
            if (scheduled || watchedPeers.isEmpty()) {
                return;
            }
            scheduled = true;
        }
        AndroidUtilities.runOnUIThread(probeRunnable, firstDelay());
    }

    private long firstDelay() {
        return ApplicationLoader.isScreenOn && !ApplicationLoader.mainInterfacePaused
                ? 5_000 : PROBE_INTERVAL_MS;
    }

    private final Runnable probeRunnable = new Runnable() {
        @Override
        public void run() {
            List<Long> due;
            boolean screenOn;
            synchronized (lock) {
                due = new ArrayList<>(watchedPeers);
            }
            if (due.isEmpty()) {
                synchronized (lock) {
                    scheduled = false;
                }
                return;
            }
            screenOn = ApplicationLoader.isScreenOn && !ApplicationLoader.mainInterfacePaused;
            for (int a = 0; a < due.size(); a++) {
                long dialogId = due.get(a);
                if (!flushing.contains(dialogId)) {
                    probeAndMaybeFlush(dialogId);
                }
            }
            synchronized (lock) {
                if (watchedPeers.isEmpty()) {
                    scheduled = false;
                    return; // nothing left to watch — loop ends
                }
            }
            AndroidUtilities.runOnUIThread(this, screenOn ? PROBE_INTERVAL_MS : PROBE_INTERVAL_BACKGROUND_MS);
        }
    };

    private void probeAndMaybeFlush(final long dialogId) {
        ExistsProbe override = probeOverride;
        if (override != null) {
            final boolean registered = override.registered(dialogId);
            if (registered) {
                AndroidUtilities.runOnUIThread(() -> flushDialog(dialogId));
            }
            return;
        }
        org.telegram.tgnet.rest.RestGateway gateway = org.telegram.tgnet.rest.RestGateway.getInstance(account);
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            boolean registered;
            try {
                JSONObject resp = gateway.e2eeGet("e2ee/keys/exists.php?user_id=" + dialogId);
                registered = resp != null && resp.optBoolean("ok", false) && resp.optBoolean("registered", false);
            } catch (Throwable t) {
                return; // transport hiccup — the next tick retries
            }
            if (!registered) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> flushDialog(dialogId));
        });
    }

    /** Single-flight re-dispatch of one dialog's pending rows. */
    private void flushDialog(long dialogId) {
        synchronized (lock) {
            if (flushing.contains(dialogId)) {
                return;
            }
            flushing.add(dialogId);
        }
        try {
            Long lastFlush = flushTimestamps.get(dialogId);
            long now = System.currentTimeMillis();
            if (lastFlush != null && now - lastFlush < MIN_FLUSH_GAP_MS) {
                return; // bounded retry pressure (rescheduled below via watch)
            }
            flushTimestamps.put(dialogId, now);
            MessagesStorage storage = org.telegram.messenger.MessagesStorage.getInstance(account);
            storage.getUnsentMessagesForDialog(dialogId, 100, (messages, scheduledMessages) -> {
                ArrayList<TLRPC.Message> pending = new ArrayList<>();
                pending.addAll(messages);
                pending.addAll(scheduledMessages);
                if (pending.isEmpty()) {
                    synchronized (lock) {
                        watchedPeers.remove(dialogId); // nothing pending anymore
                        persistLocked();
                    }
                    return;
                }
                for (int a = 0; a < pending.size(); a++) {
                    TLRPC.Message row = pending.get(a);
                    MessageObject obj = new MessageObject(account, row, false, true);
                    obj.messageOwner.send_state = MessageObject.MESSAGE_SEND_STATE_SENDING;
                    try {
                        SendMessagesHelper.getInstance(account).retrySendMessage(obj, true);
                    } catch (Throwable t) {
                        FileLog.e("XoPendingKeys: retry failed for dialog " + dialogId, t);
                    }
                }
                XoE2eeLog.event(account, "pending.flush", dialogId, "rows=" + pending.size());
            });
            // the watch entry stays until the rows leave pending state; the
            // next tick re-checks and drops the dialog when nothing remains
        } catch (Throwable t) {
            FileLog.e("XoPendingKeys: flush failed for dialog " + dialogId, t);
        } finally {
            synchronized (lock) {
                flushing.remove(dialogId);
            }
        }
    }

    private final HashMap<Long, Long> flushTimestamps = new HashMap<>();

    /** Called by the send path when a message that used to be deferred lands
     *  (the normal send-success cleanup already removed the row; if the
     *  dialog has no pending rows left, the watch entry is dropped). */
    public void onSendSettled(long dialogId) {
        org.telegram.messenger.MessagesStorage storage = org.telegram.messenger.MessagesStorage.getInstance(account);
        storage.getUnsentMessagesForDialog(dialogId, 1, (messages, scheduledMessages) -> {
            if (messages.isEmpty() && scheduledMessages.isEmpty()) {
                synchronized (lock) {
                    watchedPeers.remove(dialogId);
                    persistLocked();
                }
            }
        });
    }

    /** Test seam: inject the probe answer instead of hitting the gateway. */
    public interface ExistsProbe {
        boolean registered(long dialogId);
    }

    private volatile ExistsProbe probeOverride;

    public void setProbeOverrideForTests(ExistsProbe probe) {
        this.probeOverride = probe;
    }
}

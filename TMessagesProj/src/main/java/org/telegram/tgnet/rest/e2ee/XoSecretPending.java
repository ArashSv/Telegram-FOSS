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
 * T78 — deferral for secret-chat sends when the PEER has no public key yet
 * (not installed / old build). Same UX contract the user required in T75:
 * the chat IS created server-side (the peer sees it on next sync), the
 * pending rows keep their clock icon, and a probe loop re-dispatches them
 * the moment the peer's key appears in the registry.
 *
 * <p>Adapted from the T75 XoPendingKeys: watched ids are now SECRET DIALOG
 * ids (negative, chat space); the probe asks the pure-SELECT
 * {@code GET secret/keys.php?user_id=} — no consumption, no races.
 */
public final class XoSecretPending {

    /** The ONLY error text that qualifies for deferral. */
    public static final String DEFERRABLE_CODE = XoSecret.REASON_NO_PEER_KEY;

    private static final long PROBE_INTERVAL_MS = 45_000;
    private static final long PROBE_INTERVAL_BACKGROUND_MS = 3 * 60_000;
    private static final long MIN_FLUSH_GAP_MS = 60_000;

    private static final XoSecretPending[] instances = new XoSecretPending[UserConfig.MAX_ACCOUNT_COUNT];

    public static XoSecretPending getInstance(int account) {
        if (account < 0 || account >= instances.length) {
            account = 0;
        }
        XoSecretPending mgr;
        synchronized (XoSecretPending.class) {
            mgr = instances[account];
            if (mgr == null) {
                mgr = new XoSecretPending(account);
                instances[account] = mgr;
            }
        }
        return mgr;
    }

    private final int account;
    private final Object lock = new Object();
    private final Set<Long> watchedDialogs = new HashSet<>();
    private final Set<Long> flushing = new HashSet<>();
    private final HashMap<Long, Long> flushTimestamps = new HashMap<>();
    private final SharedPreferences prefs;
    private boolean scheduled;
    private boolean started;

    private XoSecretPending(int account) {
        this.account = account;
        this.prefs = ApplicationLoader.applicationContext.getSharedPreferences(
                "xosecretpending_" + account, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ defer seam

    /**
     * Deferral gate for the send error branches — EXACTLY the "peer has no
     * key yet" code and nothing else.
     *
     * @return true when the rows were deferred (caller must NOT mark them
     * as send-error and NOT show an error bulletin)
     */
    public boolean maybeDefer(String errorText, List<TLRPC.Message> rows, boolean scheduled_) {
        if (!deferrable(errorText, rows)) {
            return false;
        }
        for (int a = 0; a < rows.size(); a++) {
            TLRPC.Message row = rows.get(a);
            // T78 fix: deferral is a SECRET-chat mechanic keyed on the NEGATIVE
            // chat-space dialog id. The retired T80 isSecretPeer() check here
            // expected a positive user id, so keyless-peer sends became error
            // rows instead of deferred clock icons (the deferrable() core and
            // its unit test always required negative ids).
            if (!org.telegram.tgnet.rest.e2ee.XoSecret.isSecretDialog(account, row.dialog_id)) {
                return false;
            }
            watch(row.dialog_id);
        }
        XoE2eeLog.event(account, "secret.pending.defer", rows.get(0).dialog_id,
                "rows=" + rows.size());
        startProbing();
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

    /** Overload for the album callbacks (MessageObject lists). */
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

    /**
     * Pure decision core (JVM-testable): the exact "no key yet" code, and
     * every row must belong to a SECRET dialog (negative chat-space id).
     */
    public static boolean deferrable(String errorText, List<TLRPC.Message> rows) {
        if (errorText == null || !DEFERRABLE_CODE.equals(errorText) || rows == null || rows.isEmpty()) {
            return false;
        }
        for (int a = 0; a < rows.size(); a++) {
            if (rows.get(a).dialog_id >= 0) {
                return false; // cloud dialogs never defer
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ watch set

    private void watch(long dialogId) {
        synchronized (lock) {
            watchedDialogs.add(dialogId);
            persistLocked();
        }
    }

    public boolean isWatched(long dialogId) {
        synchronized (lock) {
            return watchedDialogs.contains(dialogId);
        }
    }

    public int watchedCount() {
        synchronized (lock) {
            return watchedDialogs.size();
        }
    }

    public void resetForTests() {
        synchronized (lock) {
            watchedDialogs.clear();
            flushing.clear();
            flushTimestamps.clear();
            started = false;
            scheduled = false;
        }
    }

    private void persistLocked() {
        try {
            JSONArray arr = new JSONArray();
            for (Long did : watchedDialogs) {
                arr.put(did);
            }
            prefs.edit().putString("dialogs", arr.toString()).apply();
        } catch (Exception e) {
            FileLog.e("XoSecretPending: persist failed", e);
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
                    watchedDialogs.add(arr.optLong(a, 0));
                }
            }
        } catch (Exception e) {
            FileLog.e("XoSecretPending: restore failed", e);
        }
    }

    // ------------------------------------------------------------------ probe loop

    public void startProbing() {
        synchronized (lock) {
            restoreLocked();
            if (scheduled || watchedDialogs.isEmpty()) {
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
                due = new ArrayList<>(watchedDialogs);
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
                if (watchedDialogs.isEmpty()) {
                    scheduled = false;
                    return;
                }
            }
            AndroidUtilities.runOnUIThread(this, screenOn ? PROBE_INTERVAL_MS : PROBE_INTERVAL_BACKGROUND_MS);
        }
    };

    private void probeAndMaybeFlush(final long dialogId) {
        KeyProbe override = probeOverride;
        if (override != null) {
            if (override.registered(dialogId)) {
                AndroidUtilities.runOnUIThread(() -> flushDialog(dialogId));
            }
            return;
        }
        final long peerUserId = XoSecret.secretPeerUser(account, -dialogId);
        if (peerUserId <= 0) {
            return; // index not warm yet — next tick
        }
        org.telegram.tgnet.rest.RestGateway gateway = org.telegram.tgnet.rest.RestGateway.getInstance(account);
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            try {
                JSONObject resp = gateway.e2eeGet("secret/keys.php?user_id=" + peerUserId);
                boolean registered = resp != null && resp.optBoolean("ok", false)
                        && resp.optBoolean("registered", false);
                if (registered) {
                    // warm the cache so the flush's encrypt finds the key immediately
                    String pk = resp.optString("pk", null);
                    if (pk != null && !pk.isEmpty()) {
                        XoSecretStore.getInstance(account).putPeerKey(peerUserId, pk);
                    }
                    AndroidUtilities.runOnUIThread(() -> flushDialog(dialogId));
                }
            } catch (Throwable t) {
                // transport hiccup — the next tick retries
            }
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
                return;
            }
            flushTimestamps.put(dialogId, now);
            MessagesStorage storage = org.telegram.messenger.MessagesStorage.getInstance(account);
            storage.getUnsentMessagesForDialog(dialogId, 100, (messages, scheduledMessages) -> {
                ArrayList<TLRPC.Message> pending = new ArrayList<>();
                pending.addAll(messages);
                pending.addAll(scheduledMessages);
                if (pending.isEmpty()) {
                    synchronized (lock) {
                        watchedDialogs.remove(dialogId);
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
                        FileLog.e("XoSecretPending: retry failed for dialog " + dialogId, t);
                    }
                }
                XoE2eeLog.event(account, "secret.pending.flush", dialogId, "rows=" + pending.size());
            });
        } catch (Throwable t) {
            FileLog.e("XoSecretPending: flush failed for dialog " + dialogId, t);
        } finally {
            synchronized (lock) {
                flushing.remove(dialogId);
            }
        }
    }

    /** Called by the send path when a formerly-deferred message lands. */
    public void onSendSettled(long dialogId) {
        org.telegram.messenger.MessagesStorage storage = org.telegram.messenger.MessagesStorage.getInstance(account);
        storage.getUnsentMessagesForDialog(dialogId, 1, (messages, scheduledMessages) -> {
            if (messages.isEmpty() && scheduledMessages.isEmpty()) {
                synchronized (lock) {
                    watchedDialogs.remove(dialogId);
                    persistLocked();
                }
            }
        });
    }

    /** Test seam: inject the probe answer instead of hitting the gateway. */
    public interface KeyProbe {
        boolean registered(long dialogId);
    }

    private volatile KeyProbe probeOverride;

    public void setProbeOverrideForTests(KeyProbe probe) {
        this.probeOverride = probe;
    }
}

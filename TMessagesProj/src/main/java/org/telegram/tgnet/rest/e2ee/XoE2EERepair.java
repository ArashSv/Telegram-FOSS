package org.telegram.tgnet.rest.e2ee;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.rest.RestChatIndex;
import org.telegram.tgnet.rest.RestGateway;
import org.telegram.tgnet.rest.TlJsonMapper;
import org.telegram.tgnet.rest.UserHydration;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * T77 — locked-row repair sweep.
 *
 * <p>WHY THIS EXISTS: before T77, a row whose decrypt failed ONCE was stored
 * with a literal "🔒" and the original ciphertext was DISCARDED. Any
 * transient failure (session not yet built, peer flagged mid-heal, own-echo
 * cache miss) therefore PERMANENTLY corrupted that chat — the reported
 * "messages arrive with a lock / chats are broken" symptom. The mapper now
 * registers failed rows in {@link XoE2EEStore#noteLockedEnvelope} WITH their
 * ciphertext; this sweep re-opens them once protocol state improves.
 *
 * <p>WHEN IT RUNS (all bounded, background): scheduled by
 * (a) {@code XoE2EE.ensureRegisteredAsync} (app start / login),
 * (b) every successful session build (X3DH) — a fresh session can unlock
 * rows locked under the previous missing/healed state,
 * (c) every false-positive flag heal.
 *
 * <p>HOW A REPAIRED ROW REACHES THE UI: the sweep re-fetches the affected
 * chat's recent history from the server (the same rows, unchanged) and
 * pushes each repaired row through {@code TL_updateEditMessage} →
 * {@code MessagesController.processUpdateArray} — the exact pipeline the
 * sync's edit path and T76's parked-replay use, so storage, in-memory
 * index, and open ChatActivity UI all refresh without bespoke update code.
 *
 * <p>BOUNDS: at most 3 chats and 60 rows per sweep, min 90s between sweeps,
 * single-flight. Rows that still fail stay registered for the next sweep.
 * Own rows (sender == self) can never be re-decrypted by design (the sender
 * cannot open their own ciphertext) — they are never registered.
 */
public final class XoE2EERepair {

    private static final int MAX_CHATS_PER_SWEEP = 3;
    private static final int HISTORY_LIMIT = 60;
    private static final long MIN_SWEEP_INTERVAL_MS = 90_000L;
    private static final long SCHEDULE_DELAY_MS = 8_000L;

    private static final XoE2EERepair[] instances = new XoE2EERepair[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object instancesLock = new Object();

    public static XoE2EERepair getInstance(int account) {
        synchronized (instancesLock) {
            XoE2EERepair inst = instances[account];
            if (inst == null) {
                inst = new XoE2EERepair(account);
                instances[account] = inst;
            }
            return inst;
        }
    }

    /** JVM-test seam: drop all singletons so the next getInstance is fresh. */
    static void resetForTests() {
        synchronized (instancesLock) {
            for (int a = 0; a < instances.length; a++) {
                instances[a] = null;
            }
        }
    }

    private final int account;
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private volatile long lastSweepAt;
    private final ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "XoE2EERepair");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private XoE2EERepair(int account) {
        this.account = account;
    }

    /** Requests a sweep (coalesced, throttled, never throws). */
    public void schedule() {
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        long since = now - lastSweepAt;
        long delay = Math.max(SCHEDULE_DELAY_MS, MIN_SWEEP_INTERVAL_MS - since);
        try {
            pool.schedule(this::runSweep, delay, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            scheduled.set(false);
            FileLog.e("XoE2EERepair: schedule failed", t);
        }
    }

    private void runSweep() {
        try {
            sweep();
        } catch (Throwable t) {
            FileLog.e("XoE2EERepair: sweep failed", t);
        } finally {
            lastSweepAt = android.os.SystemClock.elapsedRealtime();
            scheduled.set(false);
        }
    }

    private void sweep() {
        XoE2EEStore store = XoE2EEStore.getInstance(account);
        HashMap<Long, Long> locked = store.lockedEnvelopeDialogs();
        if (locked.isEmpty()) {
            return;
        }
        RestChatIndex index = RestChatIndex.getInstance(account);

        // group locked rows per DIALOG (the mapper only knows dialog ids);
        // each dialog resolves to its backend chat id right before the fetch
        Map<Long, ArrayList<Long>> byDialog = new LinkedHashMap<>();
        for (Long messageId : locked.keySet()) {
            Long dialogId = locked.get(messageId);
            if (dialogId == null || dialogId == 0) {
                continue;
            }
            ArrayList<Long> list = byDialog.get(dialogId);
            if (list == null) {
                if (byDialog.size() >= MAX_CHATS_PER_SWEEP) {
                    continue;
                }
                list = new ArrayList<>();
                byDialog.put(dialogId, list);
            }
            if (list.size() < HISTORY_LIMIT) {
                list.add(messageId);
            }
        }

        long selfId = UserConfig.getInstance(account).clientUserId;

        for (Map.Entry<Long, ArrayList<Long>> entry : byDialog.entrySet()) {
            long dialogId = entry.getKey();
            long chatId;
            long peerUserId;
            boolean isGroup = dialogId < 0;
            if (isGroup) {
                chatId = -dialogId;
                peerUserId = 0;
            } else {
                peerUserId = dialogId;
                chatId = index.privateChatIdFor(dialogId);
            }
            if (chatId == 0 || !index.isKnownChat(chatId)) {
                continue; // cold index — a later sweep or the normal history load covers it
            }
            HashSet<Long> wantIds = new HashSet<>(entry.getValue());
            JSONArray rows;
            try {
                rows = RestGateway.getInstance(account).history(chatId, 0, HISTORY_LIMIT);
            } catch (Throwable t) {
                FileLog.e("XoE2EERepair: history fetch failed for chat " + chatId, t);
                continue;
            }
            if (rows == null || rows.length() == 0) {
                continue;
            }

            ArrayList<TLRPC.Update> tlUpdates = new ArrayList<>();
            ArrayList<TLRPC.TL_message> parsed = new ArrayList<>();
            ArrayList<TLRPC.Chat> chatsArr = new ArrayList<>();
            ArrayList<Long> repaired = new ArrayList<>();

            for (int a = 0; a < rows.length(); a++) {
                JSONObject row = rows.optJSONObject(a);
                if (row == null) {
                    continue;
                }
                long rowId = row.optLong("id", 0);
                if (!wantIds.contains(rowId)) {
                    continue;
                }
                try {
                    TLRPC.TL_message message = TlJsonMapper.parseMessage(account, row, dialogId, isGroup, peerUserId, selfId);
                    // Still locked? (decrypt failed again — keep it registered
                    // for a later sweep; covers text AND media rows, whose
                    // locked marker is the same 🔒 text)
                    if ("🔒".equals(message.message)) {
                        continue;
                    }
                    index.rememberMessages(chatId, java.util.Collections.singletonList(message));
                    TLRPC.TL_updateEditMessage upd = new TLRPC.TL_updateEditMessage();
                    upd.message = message;
                    upd.pts = 0;
                    upd.pts_count = 0;
                    tlUpdates.add(upd);
                    parsed.add(message);
                    repaired.add(rowId);
                } catch (Exception e) {
                    FileLog.e("XoE2EERepair: row " + rowId + " parse failed", e);
                }
            }

            if (tlUpdates.isEmpty()) {
                continue;
            }
            ArrayList<TLRPC.User> usersArr = new ArrayList<>(UserHydration.fetchUncached(account, UserHydration.senderIds(parsed)));
            int now = (int) (System.currentTimeMillis() / 1000L);
            Utilities.stageQueue.postRunnable(() -> {
                try {
                    org.telegram.messenger.MessagesController.getInstance(account)
                            .processUpdateArray(tlUpdates, usersArr, chatsArr, false, now);
                } catch (Exception e) {
                    FileLog.e("XoE2EERepair: processUpdateArray failed", e);
                }
            });
            for (Long id : repaired) {
                store.dropLockedEnvelope(id);
            }
            XoE2eeLog.event(account, "repair.sweep", peerUserId, "rows=" + repaired.size() + " chat=" + chatId);
        }
    }
}

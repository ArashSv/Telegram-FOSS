package org.telegram.tgnet.rest;

import android.util.SparseArray;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * T6: short-polling update source (GET /sync/index.php, cursor contract
 * API.md §9) — the REST replacement for MTProto's socket push.
 *
 * <p>Design boundaries, chosen critically:
 * <ul>
 *   <li>The legacy pts/seq state stays pinned at zero (state/difference stubs
 *       in {@link RestDispatcher}); this poller is the ONLY updates source, so
 *       events are applied directly instead of being fed through the seq-gated
 *       queue machinery we deliberately disabled.</li>
 *   <li>Delivery goes through {@code MessagesController.processUpdateArray}
 *       on the stageQueue — the same canonical path getDifference answers
 *       take — so dialogs, unread counters, notifications and the open chat
 *       are updated by the app's own code, not by a parallel implementation.</li>
 *   <li>{@code message_new} from the local account is skipped: own sends are
 *       confirmed synchronously by the send path; the backend fan-out would
 *       only duplicate them.</li>
 *   <li>Cursor persistence lives in {@link RestAuthStore} (plain
 *       SharedPreferences, per account slot). On a user change in the same
 *       slot the cursor resets to 0 so the fresh login replays its own
 *       history (retention is 7 days, storage is wiped on logout).</li>
 *   <li>Failures back off exponentially (1.5s → 60s) and a session-invalid
 *       answer stops the loop; a later successful routed call re-arms it via
 *       {@link #ensureStarted()}.</li>
 * </ul>
 *
 * <p>Interval is the API-recommended 1.5 s active value — the Phase-2
 * acceptance target is end-to-end delivery under 2 s. Background throttling
 * (30 s / Doze) is a later hardening task, not wired now.
 */
public final class UpdatePoller {

    private static final long POLL_INTERVAL_MS = 1500;
    private static final long MAX_BACKOFF_MS = 60_000;
    private static final int POLL_LIMIT = 200;

    private static final UpdatePoller[] instances = new UpdatePoller[4];

    public static UpdatePoller getInstance(int account) {
        if (account < 0 || account >= instances.length) {
            account = 0;
        }
        UpdatePoller poller;
        synchronized (UpdatePoller.class) {
            poller = instances[account];
            if (poller == null) {
                poller = new UpdatePoller(account);
                instances[account] = poller;
            }
        }
        return poller;
    }

    private final int account;
    private final RestGateway gateway;
    private final RestAuthStore store;
    private final ScheduledExecutorService scheduler;

    private volatile boolean started;
    private long startedForUser;   // client user id the loop was armed for
    private int consecutiveFailures;

    private UpdatePoller(int account) {
        this.account = account;
        this.gateway = RestGateway.getInstance(account);
        this.store = RestAuthStore.getInstance(account);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "XoSyncPoller-" + account);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    /** Idempotent arming — called by {@link RestDispatcher} on routed-call success. */
    public void ensureStarted() {
        if (started) {
            return;
        }
        synchronized (this) {
            if (started) {
                return;
            }
            long selfId = UserConfig.getInstance(account).clientUserId;
            if (selfId == 0 || store.getTokens() == null) {
                return; // not logged in (yet); the next routed success re-arms
            }
            if (startedForUser != 0 && startedForUser != selfId) {
                // different user in the same account slot: replay own history
                store.setSyncCursor(0);
            }
            startedForUser = selfId;
            started = true;
            consecutiveFailures = 0;
            FileLog.d("UpdatePoller: started for account " + account + " user " + selfId);
            scheduler.execute(this::tick);
        }
    }

    /** Stops the loop; the index forget is left to the session owner. */
    public void stop(String why) {
        started = false;
        FileLog.d("UpdatePoller: stopped for account " + account + " (" + why + ")");
    }

    private void tick() {
        if (!started) {
            return;
        }
        long cursor = store.getSyncCursor();
        try {
            JSONObject page = gateway.sync(cursor, POLL_LIMIT);
            process(page);
            consecutiveFailures = 0;
            long newCursor = page.optLong("cursor", cursor);
            if (newCursor != cursor) {
                store.setSyncCursor(newCursor);
            }
        } catch (XoApiException e) {
            // T7b: stop only on proof the family is dead (genuine envelope 401 or
            // SESSION_INVALID). A garbled 401 page from an intermediary keeps the
            // loop alive under backoff — VPN path noise must not kill live updates.
            if (e.isSessionInvalid() || (e.httpStatus == 401 && e.isGenuineServerRejection())) {
                stop("session invalid: " + e.errorCode);
                return;
            }
            consecutiveFailures++;
            FileLog.e("UpdatePoller: sync api failure (" + consecutiveFailures + ")", e);
        } catch (Exception e) {
            consecutiveFailures++;
            FileLog.e("UpdatePoller: sync transport failure (" + consecutiveFailures + ")", e);
        }
        long delay = consecutiveFailures == 0 ? POLL_INTERVAL_MS
                : Math.min(POLL_INTERVAL_MS << Math.min(consecutiveFailures, 6), MAX_BACKOFF_MS);
        scheduler.schedule(this::tick, delay, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ event application

    private void process(JSONObject page) {
        JSONArray updates = page.optJSONArray("updates");
        if (updates == null || updates.length() == 0) {
            return;
        }
        ArrayList<TLRPC.Update> tlUpdates = new ArrayList<>();
        ArrayList<TLRPC.TL_message> parsedMessages = new ArrayList<>();
        ArrayList<TLRPC.Chat> chatsArr = new ArrayList<>();
        SparseArray<long[]> pendingReads = new SparseArray<>(); // position -> {readerId, maxId, chatId}

        for (int a = 0; a < updates.length(); a++) {
            JSONObject update = updates.optJSONObject(a);
            if (update == null) {
                continue;
            }
            String type = update.optString("type", "");
            switch (type) {
                case "message_new":
                    handleNewMessage(update.optJSONObject("message"), tlUpdates, parsedMessages, chatsArr);
                    break;
                case "message_delete":
                    handleDelete(update, tlUpdates);
                    break;
                case "message_edit":
                    // v1 client gap (edit propagation) — logged, not applied
                    FileLog.d("UpdatePoller: message_edit deferred (T7 hardening)");
                    break;
                case "read":
                    handleRead(update, pendingReads);
                    break;
                case "chat_new":
                    handleChatNew(update.optJSONObject("chat"), chatsArr);
                    break;
                default:
                    break;
            }
        }

        appendReadUpdates(tlUpdates, pendingReads);
        if (tlUpdates.isEmpty()) {
            return;
        }

        long selfId = UserConfig.getInstance(account).clientUserId;
        ArrayList<TLRPC.User> usersArr = resolveUsers(parsedMessages, selfId);
        Utilities.stageQueue.postRunnable(() -> {
            try {
                MessagesController.getInstance(account).processUpdateArray(tlUpdates, usersArr, chatsArr, false, nowSeconds());
            } catch (Exception e) {
                FileLog.e("UpdatePoller: processUpdateArray failed", e);
            }
        });
    }

    private void handleNewMessage(JSONObject msgJson, ArrayList<TLRPC.Update> tlUpdates,
                                  ArrayList<TLRPC.TL_message> parsedMessages, ArrayList<TLRPC.Chat> chatsArr) {
        if (msgJson == null) {
            return;
        }
        long selfId = UserConfig.getInstance(account).clientUserId;
        if (msgJson.optLong("sender_id", 0) == selfId) {
            return; // own send — the send path already applied it
        }
        long chatId = msgJson.optLong("chat_id", 0);
        RestChatIndex index = RestChatIndex.getInstance(account);
        if (!index.isKnownChat(chatId) && !rebuildChatIndex()) {
            FileLog.e("UpdatePoller: message for unknown chat " + chatId + " dropped (no chat list answer)");
            return;
        }
        boolean isGroup = index.isGroup(chatId);
        long peerUserId = 0;
        if (isGroup) {
            TLRPC.TL_chat chat = index.groupChat(chatId);
            if (chat == null) {
                FileLog.e("UpdatePoller: group message for uncached chat " + chatId + " dropped");
                return; // processUpdateArray would drop it anyway (chat not found)
            }
            chatsArr.add(chat);
        } else {
            peerUserId = index.userForPrivateChat(chatId);
            if (peerUserId == 0) {
                FileLog.e("UpdatePoller: private message for unknown peer (chat " + chatId + ") dropped");
                return;
            }
        }
        try {
            long dialogId = isGroup ? -chatId : peerUserId;
            TLRPC.TL_message message = TlJsonMapper.parseMessage(msgJson, dialogId, isGroup, peerUserId, selfId);
            index.rememberMessages(chatId, new ArrayList<>(java.util.Collections.singletonList(message)));
            TLRPC.TL_updateNewMessage update = new TLRPC.TL_updateNewMessage();
            update.message = message;
            update.pts = 0;
            update.pts_count = 0;
            tlUpdates.add(update);
            parsedMessages.add(message);
        } catch (Exception e) {
            FileLog.e("UpdatePoller: malformed message_new", e);
        }
    }

    private void handleRead(JSONObject update, SparseArray<long[]> pendingReads) {
        long readerId = update.optLong("user_id", 0);
        int maxId = (int) update.optLong("max_id", 0);
        long chatId = update.optLong("chat_id", 0);
        if (readerId == 0 || maxId <= 0) {
            return;
        }
        pendingReads.put(pendingReads.size(), new long[]{readerId, maxId, chatId});
    }

    private void handleDelete(JSONObject update, ArrayList<TLRPC.Update> tlUpdates) {
        JSONArray ids = update.optJSONArray("message_ids");
        if (ids == null || ids.length() == 0) {
            return;
        }
        TLRPC.TL_updateDeleteMessages delete = new TLRPC.TL_updateDeleteMessages();
        for (int a = 0; a < ids.length(); a++) {
            delete.messages.add((int) ids.optLong(a, 0));
        }
        delete.pts = 0;
        delete.pts_count = 0;
        tlUpdates.add(delete);
    }

    private void handleChatNew(JSONObject chatJson, ArrayList<TLRPC.Chat> chatsArr) {
        if (chatJson == null) {
            return;
        }
        JSONArray single = new JSONArray();
        single.put(chatJson);
        RestChatIndex.ScanResult scan = RestChatIndex.getInstance(account).scanChats(single);
        chatsArr.addAll(scan.chats);
        if (!scan.chats.isEmpty()) {
            // new group with no message yet: only a dialogs reload surfaces it
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    MessagesController.getInstance(account).loadDialogs(0, 0, 100, false);
                } catch (Exception e) {
                    FileLog.e("UpdatePoller: chat_new dialogs reload failed", e);
                }
            });
        }
    }

    /**
     * Reads become {@code TL_updateReadHistoryOutbox} (the peer read our
     * messages) or {@code ...Inbox} (this account read on another device).
     * Read updates carry no users, so they are appended after the user
     * hydration pass — positionally, to keep the batch ordering intact.
     */
    private void appendReadUpdates(ArrayList<TLRPC.Update> tlUpdates, SparseArray<long[]> pendingReads) {
        RestChatIndex index = RestChatIndex.getInstance(account);
        for (int a = 0; a < pendingReads.size(); a++) {
            long[] entry = pendingReads.valueAt(a);
            long readerId = entry[0];
            int maxId = (int) entry[1];
            long chatId = entry[2];
            boolean isGroup = index.isGroup(chatId);
            if (readerId == UserConfig.getInstance(account).clientUserId) {
                continue; // own read is applied locally by markDialogAsRead already
            }
            if (isGroup) {
                TLRPC.TL_updateReadHistoryOutbox read = new TLRPC.TL_updateReadHistoryOutbox();
                read.peer = new TLRPC.TL_peerChat();
                read.peer.chat_id = chatId;
                read.max_id = maxId;
                read.pts = 0;
                read.pts_count = 0;
                tlUpdates.add(read);
            } else if (readerId != 0) {
                TLRPC.TL_updateReadHistoryOutbox read = new TLRPC.TL_updateReadHistoryOutbox();
                read.peer = new TLRPC.TL_peerUser();
                read.peer.user_id = readerId;
                read.max_id = maxId;
                read.pts = 0;
                read.pts_count = 0;
                tlUpdates.add(read);
            }
        }
    }

    /**
     * Senders present in the memory cache pass through; the rest share one
     * /users/get.php call; anything still unknown degrades to a
     * {@code user<id>} placeholder so a message is never lost to hydration.
     */
    private ArrayList<TLRPC.User> resolveUsers(ArrayList<TLRPC.TL_message> messages, long selfId) {
        ArrayList<TLRPC.User> usersArr = new ArrayList<>();
        ArrayList<Long> missing = null;
        for (int a = 0; a < messages.size(); a++) {
            TLRPC.TL_message message = messages.get(a);
            if (!(message.from_id instanceof TLRPC.TL_peerUser)) {
                continue;
            }
            long senderId = ((TLRPC.TL_peerUser) message.from_id).user_id;
            if (hasCachedUser(senderId)) {
                continue;
            }
            if (missing == null) {
                missing = new ArrayList<>();
            }
            if (!missing.contains(senderId)) {
                missing.add(senderId);
            }
        }
        if (missing != null) {
            long[] ids = new long[missing.size()];
            for (int a = 0; a < ids.length; a++) {
                ids[a] = missing.get(a);
            }
            try {
                usersArr.addAll(TlJsonMapper.parseUsers(gateway.usersGet(ids)));
            } catch (Exception e) {
                FileLog.e("UpdatePoller: sender hydration failed", e);
            }
        }
        for (int a = 0; a < messages.size(); a++) {
            TLRPC.TL_message message = messages.get(a);
            if (!(message.from_id instanceof TLRPC.TL_peerUser)) {
                continue;
            }
            long senderId = ((TLRPC.TL_peerUser) message.from_id).user_id;
            if (!containsUser(usersArr, senderId) && !hasCachedUser(senderId)) {
                TLRPC.TL_user fallback = new TLRPC.TL_user();
                fallback.id = senderId;
                fallback.first_name = "user" + senderId;
                fallback.flags |= 2;
                fallback.status = new TLRPC.TL_userStatusEmpty();
                usersArr.add(fallback);
            }
        }
        return usersArr;
    }

    private boolean hasCachedUser(long userId) {
        try {
            return MessagesController.getInstance(account).getUser(userId) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean containsUser(ArrayList<TLRPC.User> users, long id) {
        for (int a = 0; a < users.size(); a++) {
            if (users.get(a).id == id) {
                return true;
            }
        }
        return false;
    }

    /** One /chats/list.php call to warm the index after an unknown chat id. */
    private boolean rebuildChatIndex() {
        try {
            RestChatIndex.getInstance(account).scanChats(gateway.chatsList());
            return true;
        } catch (Exception e) {
            FileLog.e("UpdatePoller: chat index rebuild failed", e);
            return false;
        }
    }

    private static int nowSeconds() {
        return (int) (System.currentTimeMillis() / 1000L);
    }
}

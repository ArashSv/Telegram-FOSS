package org.telegram.tgnet.rest;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
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
 * <p>Interval is adaptive (T13 hardening, replacing the fixed 1.5 s of the
 * first wiring): 1.5 s while the user is looking at the app
 * ({@code !mainInterfacePaused && isScreenOn}) — the Phase-2 acceptance
 * target is end-to-end delivery under 2 s foregrounded; 10 s once the UI is
 * paused or the screen is off. Same cursor contract, same backoff ladder in
 * both modes; the signal is the tree-canonical volatile pair that
 * NotificationsController/MediaController already read, so there is no new
 * lifecycle coupling. Failures back off exponentially (1.5s/10s → 60s) and a
 * session-invalid answer stops the loop; a later successful routed call
 * re-arms it via {@link #ensureStarted()}.
 */
public final class UpdatePoller {

    private static final long POLL_INTERVAL_MS = 1500;
    private static final long BACKGROUND_POLL_INTERVAL_MS = 10_000;
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
                // T38: re-arm the one-shot self-heal — the fresh session's
                // self user (or its degraded residue in UserConfig) must be
                // verified/repaired even though this process already healed
                // the PREVIOUS session's user.
                XoSelf.Once.reset(account);
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
            // Xo (T7c): the poll is the REST liveness heartbeat — keep the header truthful
            ConnectionsManager.getInstance(account).setXoConnectionState(ConnectionsManager.ConnectionStateConnected);
            long newCursor = page.optLong("cursor", cursor);
            if (newCursor != cursor) {
                store.setSyncCursor(newCursor);
            }
            // T34: upgrade-path self-heal — one attempt per process, on the
            // first healthy poll, repairs a self user degraded by the v1.6
            // partial-replacement bug (no re-login needed after updating).
            if (XoSelf.Once.firstTime(account)) {
                XoSelf.ensureFresh(account);
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
            // Xo (T7c): transport/unknown failure — header goes back to Connecting
            ConnectionsManager.getInstance(account).setXoConnectionState(ConnectionsManager.ConnectionStateConnecting);
            FileLog.e("UpdatePoller: sync transport failure (" + consecutiveFailures + ")", e);
        }
        long idleInterval = isBackground() ? BACKGROUND_POLL_INTERVAL_MS : POLL_INTERVAL_MS;
        long delay = consecutiveFailures == 0 ? idleInterval
                : Math.min(idleInterval << Math.min(consecutiveFailures, 6), MAX_BACKOFF_MS);
        scheduler.schedule(this::tick, delay, TimeUnit.MILLISECONDS);
    }

    /** Tree-canonical foreground signal: UI paused or screen off → background cadence. */
    private static boolean isBackground() {
        return ApplicationLoader.mainInterfacePaused || !ApplicationLoader.isScreenOn;
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
        // T13: a plain list of {readerId, maxId, chatId} rows — the old
        // SparseArray-as-list keyed by position added indirection, nothing else
        ArrayList<long[]> pendingReads = new ArrayList<>();

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
                    // T38/13: edits propagate to the OTHER devices here (the
                    // editing device applies its own response update). The
                    // sync endpoint materializes the full message json.
                    handleEdit(update.optJSONObject("message"), tlUpdates, parsedMessages, chatsArr);
                    break;
                case "read":
                    handleRead(update, pendingReads);
                    break;
                case "chat_new":
                    handleChatNew(update.optJSONObject("chat"), chatsArr);
                    break;
                case "chat_member":
                    // T40: group membership changed around me (backend v2.2 —
                    // ids-only payload; applies directly like user_updated
                    // because a membership event can arrive alone in a page
                    // and the tlUpdates batch early-return would swallow it).
                    handleChatMember(update);
                    break;
                case "user_updated":
                    // T32: profile (name/avatar) changes apply directly — see handleUserUpdated
                    handleUserUpdated(update.optJSONObject("user"));
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
        RestChatIndex index = RestChatIndex.getInstance(account);
        if (msgJson.optLong("sender_id", 0) == selfId) {
            // T38/15: Saved-Messages self-chat — the owner's OTHER devices
            // learn saved messages only through this push. The device that
            // SENT it already holds it (the index remembered it at send
            // time), so a session-local lookup dedups the double-apply.
            long chatId = msgJson.optLong("chat_id", 0);
            boolean selfChat = index.isKnownChat(chatId) && index.userForPrivateChat(chatId) == selfId;
            if (!selfChat) {
                return; // own send on a normal chat — the send path already applied it
            }
            ArrayList<Integer> probe = new ArrayList<>();
            probe.add((int) msgJson.optLong("id", 0));
            if (index.chatIdForAnyMessage(probe) == chatId) {
                return; // this device already applied its own send
            }
        }
        long chatId = msgJson.optLong("chat_id", 0);
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
            index.rememberMessages(chatId, java.util.Collections.singletonList(message));
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

    /**
     * T38/13: a remote edit — re-parse the fresh message exactly like a new
     * one (the json is the same MessageMapper::json shape) and emit
     * TL_updateEditMessage, which processUpdateArray applies over the cached
     * MessageObject (text, caption, edit_date → the "(edited)" marker).
     */
    private void handleEdit(JSONObject msgJson, ArrayList<TLRPC.Update> tlUpdates,
                            ArrayList<TLRPC.TL_message> parsedMessages, ArrayList<TLRPC.Chat> chatsArr) {
        if (msgJson == null) {
            return;
        }
        long selfId = UserConfig.getInstance(account).clientUserId;
        long chatId = msgJson.optLong("chat_id", 0);
        RestChatIndex index = RestChatIndex.getInstance(account);
        if (!index.isKnownChat(chatId)) {
            return; // the edit concerns a chat this device never opened; history will load it fresh
        }
        boolean isGroup = index.isGroup(chatId);
        long peerUserId = 0;
        if (isGroup) {
            TLRPC.TL_chat chat = index.groupChat(chatId);
            if (chat == null) {
                return;
            }
            chatsArr.add(chat);
        } else {
            peerUserId = index.userForPrivateChat(chatId);
            if (peerUserId == 0) {
                return;
            }
        }
        try {
            long dialogId = isGroup ? -chatId : peerUserId;
            TLRPC.TL_message message = TlJsonMapper.parseMessage(msgJson, dialogId, isGroup, peerUserId, selfId);
            index.rememberMessages(chatId, java.util.Collections.singletonList(message));
            TLRPC.TL_updateEditMessage update = new TLRPC.TL_updateEditMessage();
            update.message = message;
            update.pts = 0;
            update.pts_count = 0;
            tlUpdates.add(update);
            parsedMessages.add(message);
        } catch (Exception e) {
            FileLog.e("UpdatePoller: malformed message_edit", e);
        }
    }

    private void handleRead(JSONObject update, ArrayList<long[]> pendingReads) {
        long readerId = update.optLong("user_id", 0);
        int maxId = (int) update.optLong("max_id", 0);
        long chatId = update.optLong("chat_id", 0);
        if (readerId == 0 || maxId <= 0) {
            return;
        }
        pendingReads.add(new long[]{readerId, maxId, chatId});
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
     * T40: group membership changed around me (backend v2.2 chat_member —
     * ids only: {chat_id, event: kick|leave|promote|demote|deleted,
     * user_id, actor_id}).
     *
     * Self-directed kick/leave and whole-group deletion remove the dialog
     * through the CANONICAL local deleteDialog path (storage + memory +
     * notifications all happen pre-send; the follow-up delete-history call
     * is tolerated by the relaxed v2.2 chat-existence guard even though this
     * session is already out of the chat). Everything else — someone else
     * joined/left/got promoted/kicked — refreshes the authoritative
     * membership snapshot via loadFullChat (the same re-pull the add flow
     * uses); a stale 403 (e.g. I left moments earlier myself) is swallowed
     * by loadFullChat's error path.
     */
    private void handleChatMember(JSONObject update) {
        if (update == null) {
            return;
        }
        final long chatId = update.optLong("chat_id", 0);
        final String event = update.optString("event", "");
        final long userId = update.optLong("user_id", 0);
        if (chatId <= 0 || event.length() == 0) {
            return;
        }
        long selfId = UserConfig.getInstance(account).clientUserId;
        boolean selfRemoved = "deleted".equals(event)
                || (("kick".equals(event) || "leave".equals(event)) && userId == selfId);
        if (selfRemoved) {
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    MessagesController.getInstance(account).deleteDialog(-chatId, 0, false);
                } catch (Exception e) {
                    FileLog.e("UpdatePoller: chat_member self-removal failed", e);
                }
            });
        } else {
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    MessagesController.getInstance(account).loadFullChat(chatId, 0, true);
                } catch (Exception e) {
                    FileLog.e("UpdatePoller: chat_member full-chat refresh failed", e);
                }
            });
        }
    }

    /**
     * T32: a user's name/avatar changed. Applies DIRECTLY (putUser + interface
     * masks) instead of riding the processUpdateArray batch — user updates can
     * arrive alone in a page, and the tlUpdates-only early-return would then
     * swallow them. Own changes are skipped: the acting device already applied
     * them locally.
     */
    private void handleUserUpdated(JSONObject userJson) {
        if (userJson == null) {
            return;
        }
        try {
            long userId = userJson.getLong("id");
            if (userId == UserConfig.getInstance(account).clientUserId) {
                // T34: own user_updated (another device changed this account's
                // profile) merges through XoSelf instead of being dropped —
                // multi-device profile sync without ever degrading the self
                // user to a public-shaped replacement.
                XoSelf.mergeApply(account, userJson);
                return;
            }
            TLRPC.TL_user user = TlJsonMapper.parseUser(userJson, false);
            // T35: the user_updated payload is PROFILE truth from the subject's
            // own perspective — it cannot carry viewer-relative surfaces. If
            // the cached user is the viewer's CONTACT, the contact surfaces
            // (saved name, phone, contact flag) must survive the merge:
            // first_name stays the saved contact name (a profile rename by
            // them must not overwrite OUR name for them), phone/contact ride
            // from the cache. Non-contact users just take the profile json.
            TLRPC.User cached = MessagesController.getInstance(account).getUser(userId);
            if (cached != null && cached.contact) {
                user.first_name = cached.first_name;
                user.last_name = cached.last_name;
                user.phone = cached.phone;
                user.contact = true;
                user.flags |= 2048;
                if (user.phone != null && user.phone.length() > 0) {
                    user.flags |= 16;
                }
                if (user.first_name != null && user.first_name.length() > 0) {
                    user.flags |= 2;
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    MessagesController.getInstance(account).putUser(user, false);
                    // T33: persist too — without this the db row lagged behind
                    // memory until the next dialogs/history save, so a changed
                    // username/bio would revert after a process restart.
                    // NOTE: putUsersAndChats takes List<User> — generics are
                    // invariant, so the list is declared User, not TL_user.
                    ArrayList<TLRPC.User> single = new ArrayList<>();
                    single.add(user);
                    MessagesStorage.getInstance(account).putUsersAndChats(single, null, false, true);
                    NotificationCenter.getInstance(account).postNotificationName(
                            NotificationCenter.updateInterfaces,
                            MessagesController.UPDATE_MASK_AVATAR | MessagesController.UPDATE_MASK_NAME);
                } catch (Exception e) {
                    FileLog.e("UpdatePoller: user_updated putUser failed", e);
                }
            });
        } catch (Exception e) {
            FileLog.e("UpdatePoller: user_updated parse failed", e);
        }
    }

    /**
     * Reads become {@code TL_updateReadHistoryOutbox} (the peer read our
     * messages) or {@code ...Inbox} (this account read on another device).
     * Read updates carry no users, so they are appended after the user
     * hydration pass — positionally, to keep the batch ordering intact.
     */
    private void appendReadUpdates(ArrayList<TLRPC.Update> tlUpdates, ArrayList<long[]> pendingReads) {
        RestChatIndex index = RestChatIndex.getInstance(account);
        for (int a = 0; a < pendingReads.size(); a++) {
            long[] entry = pendingReads.get(a);
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
     * /users/get.php call ({@link UserHydration}, shared with the dispatcher);
     * anything still unknown degrades to a {@code user<id>} placeholder so a
     * message is never lost to hydration.
     */
    private ArrayList<TLRPC.User> resolveUsers(ArrayList<TLRPC.TL_message> messages, long selfId) {
        ArrayList<Long> senderIds = UserHydration.senderIds(messages);
        ArrayList<TLRPC.User> usersArr = new ArrayList<>(UserHydration.fetchUncached(account, senderIds));
        for (int a = 0; a < senderIds.size(); a++) {
            long senderId = senderIds.get(a);
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

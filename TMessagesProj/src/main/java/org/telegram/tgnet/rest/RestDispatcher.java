package org.telegram.tgnet.rest;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.rest.e2ee.XoE2EE; // T71: E2EE facade (subpackage needs the explicit import)
import org.telegram.tgnet.RequestDelegateTimestamp;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * T5: the default-deny dispatcher sitting at the single funnel all
 * {@code ConnectionsManager.sendRequest} overloads flow through
 * (ConnectionsManager.sendRequestInternal). This is the "replace the callee,
 * not the call sites" architecture: 751 call sites stay untouched, the policy
 * lives in one class.
 *
 * <p>Routing contract ({@link RestRouter}):
 * <ul>
 *   <li><b>allowlisted</b> — the TL object is served by {@link RestGateway}
 *       on a worker thread, mapped back to the exact TL response class the
 *       original call site casts to, and delivered on
 *       {@code Utilities.stageQueue} — the same thread native responses use,
 *       so call-site threading assumptions hold by construction;</li>
 *   <li><b>everything else</b> — rejected immediately with
 *       {@code TL_error{400, XO_NOT_ROUTED}}. Nothing leaks to MTProto, and
 *       callers keep their normal error paths instead of hanging.</li>
 * </ul>
 *
 * <p>Mapped response classes (verified against call sites, not assumed):
 * <ul>
 *   <li>getDialogs → {@link TLRPC.TL_messages_dialogs} (MessagesController:10881 casts);</li>
 *   <li>getHistory → {@link TLRPC.TL_messages_messages} (MessagesController:10196);</li>
 *   <li>sendMessage → {@link TLRPC.TL_updates} carrying one
 *       {@code TL_updateNewMessage} (SendMessagesHelper:6420 extracts it and
 *       assigns the server id — no random_id echo needed);</li>
 *   <li>readHistory → {@link TLRPC.TL_messages_affectedHistory} — the MTProto
 *       schema type for messages.readHistory. The sole call site
 *       (MessagesController:12920) type-tests with
 *       {@code instanceof TL_messages_affectedMessages} before casting, so a
 *       schema-faithful affectedHistory is simply ignored there — safe;</li>
 *   <li>deleteMessages → {@link TLRPC.TL_messages_affectedMessages} — the
 *       MTProto schema type for messages.deleteMessages, and the class the
 *       sole call site (MessagesController:8262) <b>hard-casts</b> to.
 *       T12 root cause: this route used to answer affectedHistory (the two
 *       methods were assumed interchangeable), the cast threw
 *       ClassCastException on the stageQueue, and because the request is
 *       persisted as a pending task (MessagesStorage magic 24) whose
 *       {@code removePendingTask} runs only inside that very callback, the
 *       task re-fired and re-crashed on every launch until app data was
 *       cleared. Contract: every routed response class MUST equal what the
 *       consuming call site casts — see RestRouter's contract table;</li>
 *   <li>users_getUsers → {@link TLRPC.Vector} of users (MessagesController:5953);</li>
 *   <li>updates_getState/getDifference → zeroed stubs: the sync cursor lives
 *       in {@link UpdatePoller}, so the legacy pts/seq machinery must stay at
 *       its baseline and must never trigger difference fetches. getState can
 *       NOT be denied with an error — loadCurrentState() retries on any
 *       non-401 error (MessagesController:14243), which would loop forever.</li>
 * </ul>
 */
public final class RestDispatcher {

    private static final ExecutorService IO_QUEUE = Executors.newFixedThreadPool(3, r -> {
        Thread thread = new Thread(r, "XoRestDispatch");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    /**
     * T8: separate pool for file traffic. A 50 MB upload is ~400 part requests
     * (~14 concurrent from the tree); sharing the 3 messaging threads would
     * stall text sends/history behind media. 4 dedicated threads keep both
     * lanes moving; keep-alive pooling lives below in XoHttp.
     */
    private static final ExecutorService FILE_IO_QUEUE = Executors.newFixedThreadPool(4, r -> {
        Thread thread = new Thread(r, "XoRestFiles");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private RestDispatcher() {
    }

    /**
     * Called from {@code sendRequestInternal} on the stageQueue, before any
     * MTProto serialization. Never blocks: routed work is handed to the IO
     * pool, denials are answered via a stageQueue post.
     *
     * @return true when the request is consumed here (routed OR denied) and
     * native sendRequest must not proceed
     */
    public static boolean tryHandle(int account, TLObject object, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp) {
        if (object == null) {
            return false;
        }
        int route = RestRouter.routeFor(object);
        if (route == RestRouter.ROUTE_NONE) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("RestDispatcher: deny " + object.getClass().getSimpleName());
            }
            if (onComplete != null || onCompleteTimestamp != null) {
                TLRPC.TL_error error = new TLRPC.TL_error();
                error.code = 400;
                error.text = "XO_NOT_ROUTED";
                deliver(null, error, onComplete, onCompleteTimestamp);
            }
            return true;
        }
        boolean isFileRoute = route == RestRouter.ROUTE_FILE_GET
                || route == RestRouter.ROUTE_FILE_PART
                || route == RestRouter.ROUTE_FILE_PART_BIG;
        ExecutorService queue = isFileRoute ? FILE_IO_QUEUE : IO_QUEUE;
        queue.execute(() -> runRoute(account, route, object, onComplete, onCompleteTimestamp));
        return true;
    }

    private static void runRoute(int account, int route, TLObject object, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp) {
        TLObject response = null;
        TLRPC.TL_error error = null;
        try {
            switch (route) {
                case RestRouter.ROUTE_DIALOGS:
                    response = handleDialogs(account, (TLRPC.TL_messages_getDialogs) object);
                    break;
                case RestRouter.ROUTE_HISTORY:
                    response = handleHistory(account, (TLRPC.TL_messages_getHistory) object);
                    break;
                case RestRouter.ROUTE_SEND:
                    response = handleSend(account, (TLRPC.TL_messages_sendMessage) object);
                    break;
                case RestRouter.ROUTE_READ:
                    response = handleRead(account, (TLRPC.TL_messages_readHistory) object);
                    break;
                case RestRouter.ROUTE_DELETE:
                    response = handleDelete(account, (TLRPC.TL_messages_deleteMessages) object);
                    break;
                case RestRouter.ROUTE_USERS_GET:
                    response = handleUsersGet(account, (TLRPC.TL_users_getUsers) object);
                    break;
                case RestRouter.ROUTE_STATE:
                    response = stubState();
                    break;
                case RestRouter.ROUTE_DIFFERENCE:
                    response = stubDifference();
                    break;
                case RestRouter.ROUTE_FILE_GET:
                    response = handleFileGet(account, (TLRPC.TL_upload_getFile) object);
                    break;
                case RestRouter.ROUTE_FILE_PART:
                    response = handleFilePart(account, (TLRPC.TL_upload_saveFilePart) object);
                    break;
                case RestRouter.ROUTE_FILE_PART_BIG:
                    response = handleFilePartBig(account, (TLRPC.TL_upload_saveBigFilePart) object);
                    break;
                case RestRouter.ROUTE_SEND_MEDIA:
                    response = handleSendMedia(account, (TLRPC.TL_messages_sendMedia) object);
                    break;
                case RestRouter.ROUTE_CREATE_CHAT:
                    response = handleCreateChat(account, (TLRPC.TL_messages_createChat) object);
                    break;
                case RestRouter.ROUTE_ADD_CHAT_USER:
                    response = handleAddChatUser(account, (TLRPC.TL_messages_addChatUser) object);
                    break;
                case RestRouter.ROUTE_EDIT_CHAT_TITLE:
                    response = handleEditChatTitle(account, (TLRPC.TL_messages_editChatTitle) object);
                    break;
                case RestRouter.ROUTE_EDIT_CHAT_PHOTO:
                    response = handleEditChatPhoto(account, (TLRPC.TL_messages_editChatPhoto) object);
                    break;
                case RestRouter.ROUTE_UPLOAD_PROFILE_PHOTO:
                    response = handleUploadProfilePhoto(account, (TLRPC.TL_photos_uploadProfilePhoto) object);
                    break;
                case RestRouter.ROUTE_DELETE_PHOTOS:
                    response = handleDeletePhotos(account, (TLRPC.TL_photos_deletePhotos) object);
                    break;
                case RestRouter.ROUTE_UPDATE_PROFILE:
                    response = handleUpdateProfile(account, (TLRPC.TL_account_updateProfile) object);
                    break;
                case RestRouter.ROUTE_CHECK_USERNAME:
                    response = handleCheckUsername(account, (TLRPC.TL_account_checkUsername) object);
                    break;
                case RestRouter.ROUTE_UPDATE_USERNAME:
                    response = handleUpdateUsername(account, (TLRPC.TL_account_updateUsername) object);
                    break;
                case RestRouter.ROUTE_RESOLVE_USERNAME:
                    response = handleResolveUsername(account, (TLRPC.TL_contacts_resolveUsername) object);
                    break;
                case RestRouter.ROUTE_GET_FULL_USER:
                    response = handleGetFullUser(account, (TLRPC.TL_users_getFullUser) object);
                    break;
                case RestRouter.ROUTE_GET_FULL_CHAT:
                    response = handleGetFullChat(account, (TLRPC.TL_messages_getFullChat) object);
                    break;
                case RestRouter.ROUTE_EDIT_CHAT_ABOUT:
                    response = handleEditChatAbout(account, (TLRPC.TL_messages_editChatAbout) object);
                    break;
                case RestRouter.ROUTE_CONTACTS_GET:
                    response = handleContactsGet(account, (TLRPC.TL_contacts_getContacts) object);
                    break;
                case RestRouter.ROUTE_CONTACTS_IMPORT:
                    response = handleContactsImport(account, (TLRPC.TL_contacts_importContacts) object);
                    break;
                case RestRouter.ROUTE_CONTACTS_ADD:
                    response = handleContactsAdd(account, (TLRPC.TL_contacts_addContact) object);
                    break;
                case RestRouter.ROUTE_CONTACTS_DELETE:
                    response = handleContactsDelete(account, (TLRPC.TL_contacts_deleteContacts) object);
                    break;
                case RestRouter.ROUTE_EDIT_MESSAGE:
                    response = handleEditMessage(account, (TLRPC.TL_messages_editMessage) object);
                    break;
                case RestRouter.ROUTE_EDIT_DATA:
                    response = handleEditData();
                    break;
                case RestRouter.ROUTE_DELETE_HISTORY:
                    response = handleDeleteHistory(account, (TLRPC.TL_messages_deleteHistory) object);
                    break;
                case RestRouter.ROUTE_DELETE_CHAT_USER:
                    response = handleDeleteChatUser(account, (TLRPC.TL_messages_deleteChatUser) object);
                    break;
                case RestRouter.ROUTE_EDIT_CHAT_ADMIN:
                    response = handleEditChatAdmin(account, (TLRPC.TL_messages_editChatAdmin) object);
                    break;
                case RestRouter.ROUTE_DELETE_CHAT:
                    response = handleDeleteChat(account, (TLRPC.TL_messages_deleteChat) object);
                    break;
                case RestRouter.ROUTE_GET_SAVED_GIFS:
                    response = handleGetSavedGifs(account, (TLRPC.TL_messages_getSavedGifs) object);
                    break;
                case RestRouter.ROUTE_SAVE_GIF:
                    response = handleSaveGif(account, (TLRPC.TL_messages_saveGif) object);
                    break;
                case RestRouter.ROUTE_UPDATE_PROFILE_PHOTO:
                    response = handleUpdateProfilePhoto(account, (TLRPC.TL_photos_updateProfilePhoto) object);
                    break;
                case RestRouter.ROUTE_FORWARD:
                    response = handleForward(account, (TLRPC.TL_messages_forwardMessages) object);
                    break;
                case RestRouter.ROUTE_UPLOAD_MEDIA:
                    response = handleUploadMedia(account, (TLRPC.TL_messages_uploadMedia) object);
                    break;
                case RestRouter.ROUTE_SEND_MULTI_MEDIA:
                    response = handleSendMultiMedia(account, (TLRPC.TL_messages_sendMultiMedia) object);
                    break;
                case RestRouter.ROUTE_TOGGLE_DIALOG_PIN:
                    response = handleToggleDialogPin(account, (TLRPC.TL_messages_toggleDialogPin) object);
                    break;
                case RestRouter.ROUTE_REORDER_PINNED:
                    response = handleReorderPinned(account, (TLRPC.TL_messages_reorderPinnedDialogs) object);
                    break;
                case RestRouter.ROUTE_GET_PRIVACY:
                    response = handleGetPrivacy(account, (TLRPC.TL_account_getPrivacy) object);
                    break;
                case RestRouter.ROUTE_SET_PRIVACY:
                    response = handleSetPrivacy(account, (TLRPC.TL_account_setPrivacy) object);
                    break;
                case RestRouter.ROUTE_GET_BLOCKED:
                    response = handleGetBlocked(account, (TLRPC.TL_contacts_getBlocked) object);
                    break;
                case RestRouter.ROUTE_BLOCK_PEER:
                    response = handleBlockPeer(account, (TLRPC.TL_contacts_block) object);
                    break;
                case RestRouter.ROUTE_UNBLOCK_PEER:
                    response = handleUnblockPeer(account, (TLRPC.TL_contacts_unblock) object);
                    break;
                default:
                    error = tlError(400, "XO_NOT_ROUTED");
                    break;
            }
        } catch (XoApiException e) {
            FileLog.e("RestDispatcher: route " + route + " api failure", e);
            error = toTlError(e);
        } catch (XoTransportException e) {
            // Xo (T7c): transport failure means the backend is unreachable — say so
            // in the header (REST-driven state) instead of MTProto's own guessing.
            FileLog.e("RestDispatcher: route " + route + " transport failure", e);
            ConnectionsManager.getInstance(account).setXoConnectionState(ConnectionsManager.ConnectionStateConnecting);
            error = tlError(-1, e.getMessage() != null ? e.getMessage() : "XO_TRANSPORT");
        } catch (Exception e) {
            FileLog.e("RestDispatcher: route " + route + " unexpected failure", e);
            error = tlError(-1, "XO_GATEWAY_ERROR");
        }
        if (error == null) {
            // first successful routed call == session is live -> short polling may arm
            UpdatePoller.getInstance(account).ensureStarted();
            // Xo (T7c): REST is alive — the header reflects the REST layer truth
            ConnectionsManager.getInstance(account).setXoConnectionState(ConnectionsManager.ConnectionStateConnected);
        }
        deliver(response, error, onComplete, onCompleteTimestamp);
    }

    // ------------------------------------------------------------------ handlers

    private static TLObject handleDialogs(int account, TLRPC.TL_messages_getDialogs req) {
        if (req.offset_id > 0) {
            // backend v1 serves one page (<=200 chats, newest first): any
            // pagination request from the UI means the end of the list
            return new TLRPC.TL_messages_dialogs();
        }
        healStaleMediaLocationsOnce(account);
        long selfId = UserConfig.getInstance(account).clientUserId;
        RestChatIndex index = RestChatIndex.getInstance(account);
        TLRPC.TL_messages_dialogs dialogs = TlJsonMapper.parseDialogs(
                account, RestGateway.getInstance(account).chatsList(), selfId, index);
        // last messages are deletable content — feed the revoke id map
        for (int a = 0; a < dialogs.messages.size(); a++) {
            TLRPC.TL_message message = (TLRPC.TL_message) dialogs.messages.get(a);
            if (message.peer_id instanceof TLRPC.TL_peerChat) {
                index.rememberMessages(message.peer_id.chat_id, java.util.Collections.singletonList(message));
            } else if (message.peer_id instanceof TLRPC.TL_peerUser) {
                long chatId = index.privateChatIdFor(((TLRPC.TL_peerUser) message.peer_id).user_id);
                if (chatId != 0) {
                    index.rememberMessages(chatId, java.util.Collections.singletonList(message));
                }
            }
        }
        return dialogs;
    }

    /**
     * One-time local-database re-anchor per account (v1.2 media contract fix).
     *
     * <p>Rationale: messages parsed BEFORE the {@link TlJsonMapper#VIRTUAL_DC}
     * correction were persisted into MessagesStorage with {@code dc_id == 0}
     * (the location classes do not even serialize it). The tree refuses such
     * media at download time — FileLoadOperation.start() fails them before any
     * network request — and processLoadedMessages only re-queries the server
     * when the local cache page is EMPTY, so a cached chat never self-heals by
     * reopening it. The REST layer is the sole source of truth for this app
     * (no local-only content exists), so the deterministic repair is the
     * tree's own supported mechanism: clear the local database once; every
     * dialog and message is then re-fetched from the backend and re-persisted
     * with the corrected synthetic locations.
     *
     * <p>Trigger point: the first getDialogs fetch after login/start (the
     * session bootstrap). The once-ever guard lives in a dedicated per-account
     * prefs file, versioned so future contract corrections can reuse the
     * pattern. clearLocalDatabase() is asynchronous (posts to the storage
     * queue) and leaves params/auth untouched; the fresh dialogs answer this
     * call already carries is repopulated right after.
     */
    private static void healStaleMediaLocationsOnce(int account) {
        android.content.SharedPreferences prefs = ApplicationLoader.applicationContext
                .getSharedPreferences("xoheal_" + account, android.content.Context.MODE_PRIVATE);
        if (prefs.getBoolean("heal_2026_09_dc_v1", false)) {
            return;
        }
        prefs.edit().putBoolean("heal_2026_09_dc_v1", true).apply();
        MessagesStorage.getInstance(account).clearLocalDatabase();
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("RestDispatcher: one-time local database re-anchor for media contract v1.2 (account " + account + ")");
        }
    }

    private static TLObject handleHistory(int account, TLRPC.TL_messages_getHistory req) {
        long selfId = UserConfig.getInstance(account).clientUserId;
        PeerRef peer = resolvePeer(account, req.peer);
        if (peer == null) {
            throw new XoApiException(400, "NOT_FOUND", "unaddressable peer for history");
        }
        long chatId = requireChatId(account, peer);
        JSONArray messagesJson = RestGateway.getInstance(account).history(chatId, req.offset_id, req.limit);
        ArrayList<TLRPC.TL_message> messages;
        try {
            messages = TlJsonMapper.parseHistory(account, messagesJson, peer.dialogId, peer.isGroup, peer.userId, selfId);
        } catch (org.json.JSONException e) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed history answer: " + e.getMessage());
        }
        RestChatIndex.getInstance(account).rememberMessages(chatId, messages);

        TLRPC.TL_messages_messages res = new TLRPC.TL_messages_messages();
        res.chats.addAll(peer.groupChats());
        // getHistory walks backward from the offset: Telegram returns
        // newest -> oldest, the backend returns ascending — reverse here
        for (int a = messages.size() - 1; a >= 0; a--) {
            res.messages.add(messages.get(a));
        }
        res.users.addAll(hydrateSenders(account, res.messages));
        res.users.addAll(peer.usersToInclude());
        res.count = res.messages.size();
        return res;
    }

    private static TLObject handleSend(int account, TLRPC.TL_messages_sendMessage req) {
        if (req.message == null || req.message.length() == 0) {
            throw new XoApiException(400, "VALIDATION_ERROR", "empty message");
        }
        long selfId = UserConfig.getInstance(account).clientUserId;
        PeerRef peer = resolvePeer(account, req.peer);
        if (peer == null) {
            throw new XoApiException(400, "PEER_ID_INVALID", "unaddressable peer for send");
        }
        long chatId = requireChatId(account, peer);
        // Reply extraction — the single TL->REST translator for sends. The
        // backend rejects reply_to_id < 1 with a 400 that fails the WHOLE send,
        // so local/negative ids are clamped away here (never reach the wire).
        int replyToId = 0;
        if (req.reply_to instanceof TLRPC.TL_inputReplyToMessage) {
            replyToId = ((TLRPC.TL_inputReplyToMessage) req.reply_to).reply_to_msg_id;
        }
        if (replyToId <= 0) {
            replyToId = 0;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("RestDispatcher: send chat=" + chatId + " len=" + req.message.length() + " reply_to=" + replyToId);
        }

        // T71: E2EE — private 1:1 chats NEVER send plaintext. The send FAILS
        // LOUDLY (never silently falls back) when the peer has no keys yet.
        String wireContent = req.message;
        if (!peer.isGroup && peer.userId > 0 && peer.userId != selfId) {
            try {
                wireContent = XoE2EE.getInstance(account).encryptText(peer.userId, req.message);
            } catch (org.telegram.tgnet.rest.e2ee.XoE2EE.E2eeUnavailableException e) {
                // the tree's typed error path renders this as a failed message
                throw new XoApiException(400, e.reasonCode, e.getMessage());
            }
        }

        JSONObject sent = RestGateway.getInstance(account).send(chatId, wireContent, replyToId);
        JSONObject msgJson = sent.optJSONObject("message");
        if (msgJson == null) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "send response lacks the message object");
        }
        TLRPC.TL_message message;
        try {
            message = TlJsonMapper.parseMessage(account, msgJson, peer.dialogId, peer.isGroup, peer.userId, selfId);
        } catch (org.json.JSONException e) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed sent message: " + e.getMessage());
        }
        RestChatIndex.getInstance(account).rememberMessages(chatId, java.util.Collections.singletonList(message));

        // SendMessagesHelper extracts TL_updateNewMessage, takes the server id
        // and processes the remainder via processUpdates — pts/seq stay 0 so
        // the legacy difference machinery sees a no-op
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        TLRPC.TL_updateNewMessage update = new TLRPC.TL_updateNewMessage();
        update.message = message;
        update.pts = 0;
        update.pts_count = 0;
        updates.updates.add(update);
        updates.date = nowSeconds();
        updates.seq = 0;
        updates.users.addAll(hydrateSenders(account, java.util.Collections.singletonList(message)));
        return updates;
    }

    private static TLObject handleRead(int account, TLRPC.TL_messages_readHistory req) {
        PeerRef peer = resolvePeer(account, req.peer);
        if (peer == null) {
            throw new XoApiException(400, "PEER_ID_INVALID", "unaddressable peer for read");
        }
        long chatId = requireChatId(account, peer);
        RestGateway.getInstance(account).read(chatId, req.max_id);
        // Schema class for messages.readHistory. The sole call site
        // (MessagesController:12920) guards its cast with
        // `instanceof TL_messages_affectedMessages`, so this answer is safely
        // ignored there — and pts MUST stay 0 anyway (pinned baseline).
        return new TLRPC.TL_messages_affectedHistory();
    }

    /**
     * messages.deleteMessages is consumed by MessagesController.deleteMessages
     * (the method's only sender), whose callback HARD-CASTS the response to
     * {@code TL_messages_affectedMessages} (MessagesController:8262) and only
     * then clears the persisted pending task. Answering any other class is a
     * stageQueue ClassCastException that re-fires on every launch — T12.
     * pts/pts_count stay 0: processNewDifferenceParams(-1, 0, -1, 0) is a
     * no-op against our pinned baseline (lastPts == 0), by construction.
     */
    private static TLObject handleDelete(int account, TLRPC.TL_messages_deleteMessages req) {
        if (req.id.isEmpty()) {
            return affectedMessages();
        }
        if (!req.revoke) {
            // delete-for-me only: local deletion already happened, and the v1
            // endpoint is delete-for-everyone — calling it would exceed intent
            return affectedMessages();
        }
        RestChatIndex index = RestChatIndex.getInstance(account);
        long chatId = index.chatIdForAnyMessage(req.id);
        if (chatId == 0) {
            FileLog.w("RestDispatcher: revoke for unseen message ids, nothing to delete server-side");
            return affectedMessages();
        }
        int[] ids = new int[req.id.size()];
        for (int a = 0; a < ids.length; a++) {
            ids[a] = req.id.get(a);
        }
        RestGateway.getInstance(account).delete(chatId, ids);
        return affectedMessages();
    }

    /** The delete-route answer: the schema class, with pts pinned at the baseline. */
    private static TLRPC.TL_messages_affectedMessages affectedMessages() {
        TLRPC.TL_messages_affectedMessages res = new TLRPC.TL_messages_affectedMessages();
        res.pts = 0;
        res.pts_count = 0;
        return res;
    }

    // ------------------------------------------------------------------ file routes (T8b/T8c)

    /**
     * TL_upload_saveFilePart — small-file path (part size 32..128 KB, NO total
     * part count in the request). Answer contract: {@code TL_boolTrue} — the
     * consumer (FileUploadOperation ~:573) treats every other outcome as
     * upload failure.
     */
    private static TLObject handleFilePart(int account, TLRPC.TL_upload_saveFilePart req) {
        return uploadPart(account, req.file_id, req.file_part, 1, req.bytes);
    }

    /** TL_upload_saveBigFilePart — big-file path (128 KB parts). {@code file_total_parts} is the best part-count estimate; it may be -1 while streaming (size unknown) — the backend then extends the declared count on out-of-range indices (v1.1b). */
    private static TLObject handleFilePartBig(int account, TLRPC.TL_upload_saveBigFilePart req) {
        int estimate = req.file_total_parts > 0 ? req.file_total_parts : 1;
        return uploadPart(account, req.file_id, req.file_part, estimate, req.bytes);
    }

    /**
     * Shared upload-part pipeline: copy the tree's NativeByteBuffer out (the
     * dispatcher OWNS the request object — sendRequestInternal skips
     * freeResources() when tryHandle consumed the request, so the buffer is
     * returned to the native pool HERE, once), lazily initialize the backend
     * file via {@link RestFileBridge}, and forward the raw part.
     */
    private static TLObject uploadPart(int account, long treeUploadId, int part, int chunksTotalEstimate, NativeByteBuffer bytes) {
        if (bytes == null || bytes.limit() == 0) {
            throw new XoApiException(400, "VALIDATION_ERROR", "empty part body");
        }
        int written = bytes.position() > 0 ? bytes.position() : bytes.limit();
        byte[] data = new byte[written];
        java.nio.ByteBuffer src = bytes.buffer.duplicate();
        src.position(0);
        src.limit(written);
        src.get(data);
        bytes.reuse(); // single free: the tree never serializes a routed request

        // T71: E2EE media — if this upload belongs to an encrypted 1:1 send,
        // EVERY part is AEAD-encrypted on the fly with the upload's single-use
        // file key. What reaches the server (and the size/sha attestations
        // below) is CIPHERTEXT; the plaintext totals for the message envelope
        // are tracked separately in the encrypted store.
        byte[] e2eeFileKey = org.telegram.tgnet.rest.e2ee.XoE2EEStore.getInstance(account).uploadKeyForTree(treeUploadId);
        if (e2eeFileKey != null) {
            try {
                org.telegram.tgnet.rest.e2ee.XoE2EEStore.getInstance(account)
                        .noteUploadPart(treeUploadId, part, written);
                data = org.telegram.tgnet.rest.e2ee.XoE2EEMedia.encryptChunk(e2eeFileKey, part, data);
            } catch (Exception e) {
                FileLog.e("RestDispatcher: e2ee part encryption failed", e);
                throw new XoApiException(500, "E2EE_ENCRYPT_FAILED", "could not encrypt upload part");
            }
        }

        long backendId = RestFileBridge.getInstance(account).ensureBackendFile(treeUploadId, chunksTotalEstimate);
        // T14: the host WAF rejects binary POST bodies under ~10 KB, which
        // kills short final tail parts and small thumbnails (upload fails
        // after the progress bar hits 100%). Pad sub-32 KB bodies to a
        // normal-looking 32 KB part and declare the real length via &len= —
        // the backend truncates before storing, so the blob stays exact.
        byte[] body = data;
        int realLen = 0;
        if (data.length < 32 * 1024) {
            body = new byte[32 * 1024];
            System.arraycopy(data, 0, body, 0, data.length);
            realLen = data.length;
        }
        // T28: the backend echoes the byte count it actually stored
        // (v1.2.2+). A mismatch means the request body was cut or mangled
        // in flight while the transport layer still returned a response —
        // field-verified (SIZE_MISMATCH finalize: expected 154623, actual
        // 153624). One idempotent re-send (REPLACE semantics); a second
        // mismatch fails the part LOUDLY instead of storing silence.
        // T29: the backend v1.3 also echoes the sha256 of the stored bytes —
        // the content-level twin of the size check, catching bodies that
        // arrived the right length but byte-mangled (WAF rewriting). The
        // check is skipped when the backend predates the field.
        String sentSha = sha256Hex(data);
        JSONObject storedEnvelope = RestGateway.getInstance(account).fileChunkAttested(backendId, part, body, realLen);
        long stored = storedEnvelope.optLong("size", -1);
        String storedSha = storedEnvelope.isNull("sha256") ? null : storedEnvelope.optString("sha256", null);
        boolean bad = (stored >= 0 && stored != data.length)
                || (storedSha != null && !storedSha.equalsIgnoreCase(sentSha));
        if (bad) {
            FileLog.w("RestDispatcher: part " + part + " mismatch (stored " + stored + " of " + data.length
                    + "B, sha " + (storedSha == null ? "n/a" : "differ") + "), re-sending");
            storedEnvelope = RestGateway.getInstance(account).fileChunkAttested(backendId, part, body, realLen);
            stored = storedEnvelope.optLong("size", -1);
            storedSha = storedEnvelope.isNull("sha256") ? null : storedEnvelope.optString("sha256", null);
            if (stored >= 0 && stored != data.length) {
                throw new XoApiException(502, "PART_SIZE_MISMATCH",
                        "server stored " + stored + " of " + data.length + " bytes for part " + part);
            }
            if (storedSha != null && !storedSha.equalsIgnoreCase(sentSha)) {
                throw new XoApiException(502, "PART_HASH_MISMATCH",
                        "stored bytes do not match the sent part " + part + " (sha)");
            }
        }
        // the declared finalize size counts what the server VERIFIED it holds
        RestFileBridge.getInstance(account).noteUploadBytes(treeUploadId, data.length);
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("RestDispatcher: part " + part + " (" + written + "B) treeId=" + treeUploadId + " -> file " + backendId);
        }
        return new TLRPC.TL_boolTrue();
    }

    /**
     * T48: blocking small-binary upload for the send path's thumb guarantee.
     * Fully replicates the {@link #uploadPart} wire contract (init → one
     * chunk with the sub-32 KB WAF pad → echo size/sha verify → noteUpload-
     * Bytes) for a fully buffered payload, WITHOUT the FileLoader delayed-
     * message machinery: the caller gets back a fresh tree upload id whose
     * parts are already on the backend, ready for the normal
     * {@code finalizeUpload(treeId, 1, mime, ...)} in handleSendMedia.
     *
     * <p>Why: video/gif thumbs ride an async detour (video upload completes →
     * thumb upload starts → only then does messages/send fire). Any hiccup in
     * that detour (thumb file missing from cache, delayed-message replay
     * after a process kill, upload-error path that force-sends) and the video
     * is sent WITHOUT its thumb_file_id — the receiver then shows a bare
     * dark bubble, because this host has no ffmpeg to synthesize video
     * thumbs server-side. The send path now attaches the thumb synchronously
     * as a last line of defense (see SendMessagesHelper.ensureRestThumb).
     *
     * @return the tree upload id (0 = upload failed; the caller sends
     *         without the thumb exactly like today)
     */
    public static long uploadSmallBlocking(int account, byte[] data) {
        if (data == null || data.length == 0) {
            return 0;
        }
        long treeUploadId = Utilities.random.nextLong();
        try {
            RestFileBridge bridge = RestFileBridge.getInstance(account);
            long backendId = bridge.ensureBackendFile(treeUploadId, 1);
            byte[] body = data;
            int realLen = 0;
            if (data.length < 32 * 1024) {
                body = new byte[32 * 1024];
                System.arraycopy(data, 0, body, 0, data.length);
                realLen = data.length;
            }
            String sentSha = sha256Hex(data);
            JSONObject storedEnvelope = RestGateway.getInstance(account).fileChunkAttested(backendId, 0, body, realLen);
            long stored = storedEnvelope.optLong("size", -1);
            String storedSha = storedEnvelope.isNull("sha256") ? null : storedEnvelope.optString("sha256", null);
            boolean bad = (stored >= 0 && stored != data.length)
                    || (storedSha != null && !storedSha.equalsIgnoreCase(sentSha));
            if (bad) {
                // one idempotent re-send, then give up (mirror of uploadPart)
                storedEnvelope = RestGateway.getInstance(account).fileChunkAttested(backendId, 0, body, realLen);
                stored = storedEnvelope.optLong("size", -1);
                storedSha = storedEnvelope.isNull("sha256") ? null : storedEnvelope.optString("sha256", null);
                if (stored >= 0 && stored != data.length) {
                    FileLog.w("RestDispatcher: small upload mismatch (stored " + stored + " of " + data.length + "B), giving up");
                    return 0;
                }
                if (storedSha != null && !storedSha.equalsIgnoreCase(sentSha)) {
                    FileLog.w("RestDispatcher: small upload sha mismatch, giving up");
                    return 0;
                }
            }
            bridge.noteUploadBytes(treeUploadId, data.length);
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("RestDispatcher: small upload " + data.length + "B treeId=" + treeUploadId + " -> file " + backendId);
            }
            return treeUploadId;
        } catch (Exception e) {
            FileLog.w("RestDispatcher: small blocking upload failed: " + e.getMessage());
            return 0;
        }
    }

    /** T29: lowercase hex sha256 of a byte range (part-level upload attestation). */
    private static String sha256Hex(byte[] data) {
        return Utilities.bytesToHex(Utilities.computeSHA256(data, 0, data.length));
    }

    /**
     * TL_upload_getFile — ranged download. Location → backend file id per the
     * synthetic-id contract ({@link RestFileBridge#resolveBackendFileId},
     * including thumb-letter resolution). Answer contract: {@code TL_upload_file}
     * with bytes positioned at 0 and limit == byte count (FileLoadOperation
     * ~:2542 instanceof-tests it and writes bytes.buffer straight into its file
     * channel; a short tail chunk closes the download).
     *
     * <p>T31: the byte delivery itself is delegated to {@link XoFileTransport}
     * — the single owner of media transfer. It attests the file (memory →
     * persistent store → one metadata cold-fetch), clamps the window at the
     * attested EOF, splits large asks into ≤256 KB sub-windows, and verifies
     * every sub-window on three axes (declared length, per-window sha256 echo,
     * Content-Length promise) with fresh-connection retries. A short or
     * mangled body can never again reach the tree's assembly — it retries, or
     * fails LOUDLY here.
     */
    private static TLObject handleFileGet(int account, TLRPC.TL_upload_getFile req) {
        RestFileBridge bridge = RestFileBridge.getInstance(account);
        long backendId = bridge.resolveBackendFileId(req.location);
        if (backendId <= 0) {
            throw new XoApiException(400, "FILE_ID_INVALID", "unsupported file location");
        }
        if (req.offset < 0) {
            throw new XoApiException(400, "INVALID_RANGE", "negative offset");
        }
        long limit = Math.max(1, Math.min(req.limit, 1024 * 1024)); // tree asks 32..512 KB
        long end = req.offset + limit - 1;
        byte[] data = XoFileTransport.getInstance(account).downloadRange(backendId, req.offset, end);
        if (data.length > limit) {
            // T28: a 206 never carries more than the requested range (the only
            // legit tail is SHORTER at EOF). A longer body means an intermediary
            // answered something else (e.g. a full-file 200 that slipped past
            // the transport guard) — writing it at this offset would corrupt
            // the assembly. Reject loudly; the tree retries/fails visibly.
            throw new XoApiException(502, "RANGE_OVERFLOW",
                    "response body " + data.length + "B exceeds the requested " + limit + "B");
        }
        TLRPC.TL_upload_file result = new TLRPC.TL_upload_file();
        result.type = new TLRPC.TL_storage_fileUnknown();
        result.mtime = (int) (System.currentTimeMillis() / 1000L);
        try {
            NativeByteBuffer buffer = new NativeByteBuffer(data.length);
            buffer.writeBytes(data, 0, data.length);
            buffer.buffer.position(0); // the consumer writes buffer.remaining() to its channel
            result.bytes = buffer;
        } catch (Exception e) {
            FileLog.e("RestDispatcher: cannot wrap download bytes", e);
            throw new XoApiException(500, "SERVER_ERROR", "buffer allocation failed");
        }
        return result;
    }

    /**
     * TL_messages_sendMedia — the media SEND. The tree uploads all parts
     * FIRST, then reveals media context here (mime/name/attributes). Pipeline:
     * finalize the backend file (idempotent; also carries the client-declared
     * video/audio context the server cannot detect) →
     * POST /messages/send.php {media_file_id} → same TL_updates contract as
     * text sends (SendMessagesHelper ~:6417 extracts TL_updateNewMessage).
     */
    private static TLObject handleSendMedia(int account, TLRPC.TL_messages_sendMedia req) {
        RestFileBridge bridge = RestFileBridge.getInstance(account);
        long treeUploadId;
        int parts = 0;
        String mime = null;
        String name = null;
        Integer width = null, height = null, duration = null;
        // T46: the document's animated attribute is the TL contract for a
        // Telegram-style GIF (muted looping MP4). The upstream tree attaches it
        // in prepareSendingVideo whenever videoEditedInfo.muted is set — the
        // flag must ride finalize or the backend stamps kind='video' and every
        // client rebuilds the message as a plain video.
        boolean asGif = false;
        long thumbTreeId = 0;

        if (req.media instanceof TLRPC.TL_inputMediaUploadedPhoto) {
            TLRPC.InputFile file = ((TLRPC.TL_inputMediaUploadedPhoto) req.media).file;
            treeUploadId = file.id;
            parts = file.parts;
            name = file.name;
        } else if (req.media instanceof TLRPC.TL_inputMediaUploadedDocument) {
            TLRPC.TL_inputMediaUploadedDocument input = (TLRPC.TL_inputMediaUploadedDocument) req.media;
            treeUploadId = input.file.id;
            parts = input.file.parts;
            name = input.file.name;
            mime = input.mime_type;
            // T14: the tree uploads video/document thumbnails as separate small
            // files (input.thumb) — previously ignored, they lingered as dead
            // 'uploading' rows and video messages had no preview. Finalize the
            // thumb (its parts went through this dispatcher) and link it.
            if (input.thumb instanceof TLRPC.TL_inputFile || input.thumb instanceof TLRPC.TL_inputFileBig) {
                thumbTreeId = input.thumb.id;
            }
            for (int a = 0; a < input.attributes.size(); a++) {
                TLRPC.DocumentAttribute attribute = input.attributes.get(a);
                if (attribute instanceof TLRPC.TL_documentAttributeFilename) {
                    name = ((TLRPC.TL_documentAttributeFilename) attribute).file_name;
                } else if (attribute instanceof TLRPC.TL_documentAttributeImageSize) {
                    width = ((TLRPC.TL_documentAttributeImageSize) attribute).w;
                    height = ((TLRPC.TL_documentAttributeImageSize) attribute).h;
                } else if (attribute instanceof TLRPC.TL_documentAttributeVideo) {
                    TLRPC.TL_documentAttributeVideo video = (TLRPC.TL_documentAttributeVideo) attribute;
                    duration = (int) Math.round(video.duration);
                    if (video.w > 0) {
                        width = video.w;
                    }
                    if (video.h > 0) {
                        height = video.h;
                    }
                } else if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                    // duration is a double field in this tree (serialize casts to int)
                    duration = (int) Math.round(((TLRPC.TL_documentAttributeAudio) attribute).duration);
                } else if (attribute instanceof TLRPC.TL_documentAttributeAnimated) {
                    // T46: muted video / gif document — carried to finalize as as_gif
                    asGif = true;
                }
            }
        } else if (req.media instanceof TLRPC.TL_inputMediaPhoto) {
            treeUploadId = mediaPhotoTreeId((TLRPC.TL_inputMediaPhoto) req.media);
        } else if (req.media instanceof TLRPC.TL_inputMediaDocument) {
            // reference/forward path: TL_inputMediaDocument.id is an InputDocument
            // (TL_inputDocument carries the backend file id planted by the mapper)
            TLRPC.InputDocument inputDoc = ((TLRPC.TL_inputMediaDocument) req.media).id;
            treeUploadId = inputDoc instanceof TLRPC.TL_inputDocument ? ((TLRPC.TL_inputDocument) inputDoc).id : 0;
        } else {
            throw new XoApiException(400, "MEDIA_INVALID", "unsupported input media for this backend");
        }
        if (treeUploadId == 0) {
            throw new XoApiException(400, "FILE_ID_INVALID", "upload carries no file id");
        }
        // Referenced media (forward path): the id IS already a backend file id
        // (planted by the receive mapper) — skip finalize, the file is ready.
        boolean alreadyBackend = req.media instanceof TLRPC.TL_inputMediaPhoto
                || req.media instanceof TLRPC.TL_inputMediaDocument;

        // T71: resolve the peer FIRST — the E2EE decision must shape the
        // finalize declaration (encrypted uploads land as opaque e2ee blobs,
        // no plaintext mime/name/dimensions ever reach the file row).
        long selfId = UserConfig.getInstance(account).clientUserId;
        PeerRef peer = resolvePeer(account, req.peer);
        if (peer == null) {
            throw new XoApiException(400, "PEER_ID_INVALID", "unaddressable peer for send-media");
        }
        boolean e2eeChat = !peer.isGroup && peer.userId > 0 && peer.userId != selfId;
        org.telegram.tgnet.rest.e2ee.XoE2EEStore e2eeStore = e2eeChat
                ? org.telegram.tgnet.rest.e2ee.XoE2EEStore.getInstance(account) : null;
        byte[] bodyFileKey = e2eeStore != null && !alreadyBackend ? e2eeStore.uploadKeyForTree(treeUploadId) : null;
        byte[] thumbFileKey = e2eeStore != null && thumbTreeId != 0 ? e2eeStore.uploadKeyForTree(thumbTreeId) : null;
        // An encrypted chat NEVER references plaintext media: either the
        // upload carries an E2EE key, or the item is a re-encrypted forward
        // envelope (alreadyBackend + envelope content). Otherwise — fail loudly.
        boolean e2ee = false;
        if (e2eeChat) {
            if (!alreadyBackend && bodyFileKey == null) {
                throw new XoApiException(400, "E2EE_MEDIA_KEY_MISSING",
                        "encrypted upload lost its key; cancel and resend the media");
            }
            e2ee = !alreadyBackend || org.telegram.tgnet.rest.e2ee.XoE2EEEnvelope.isEnvelope(req.message);
            if (!e2ee) {
                throw new XoApiException(400, "E2EE_PLAINTEXT_MEDIA_REFUSED",
                        "plaintext media cannot be referenced in an encrypted chat");
            }
        }

        long thumbBackendId = 0;
        if (!alreadyBackend) {
            if (e2ee) {
                // ciphertext metadata: the server stores an opaque blob
                mime = null;
                name = null;
                width = height = duration = null;
                asGif = false;
            }
            // finalize is the single metadata injection point; the tree's real
            // part count patches any streaming-extended estimate (v1.1b). T14:
            // declare the exact streamed byte total (corruption cross-check).
            long declaredBytes = bridge.uploadedBytesFor(treeUploadId);
            JSONObject finalizeEnvelope = e2ee
                    ? bridge.finalizeUploadE2ee(treeUploadId, Math.max(1, parts), declaredBytes)
                    : bridge.finalizeUpload(treeUploadId, Math.max(1, parts), mime, name, width, height, duration, declaredBytes, asGif);
            // T29: the finalize answer is the server's final {size, sha256}
            // attestation for the new file — feed the download-integrity index
            // so this client's own later downloads of it verify too.
            RestFileBridge.noteFileMetaFromJson(finalizeEnvelope == null ? null : finalizeEnvelope.optJSONObject("file"));
            if (thumbTreeId != 0) {
                try {
                    JSONObject thumbEnvelope = e2ee
                            ? bridge.finalizeUploadE2ee(thumbTreeId, 1, bridge.uploadedBytesFor(thumbTreeId))
                            : bridge.finalizeUpload(thumbTreeId, 1, "image/jpeg", null, null, null, null, bridge.uploadedBytesFor(thumbTreeId));
                    JSONObject thumbJson = thumbEnvelope != null ? thumbEnvelope.optJSONObject("file") : null;
                    thumbBackendId = thumbJson != null ? thumbJson.optLong("file_id", 0) : 0;
                    RestFileBridge.noteFileMetaFromJson(thumbJson);
                } catch (Exception e) {
                    // a thumb must never fail the send; fall back to the mapping
                    FileLog.w("RestDispatcher: thumb finalize failed, continuing without link");
                }
                if (thumbBackendId == 0) {
                    thumbBackendId = bridge.backendFileIdFor(thumbTreeId);
                }
            }
        }
        long mediaFileId = alreadyBackend ? treeUploadId : bridge.backendFileIdFor(treeUploadId);
        if (mediaFileId == 0) {
            throw new XoApiException(400, "FILE_ID_INVALID", "upload was never routed through this client");
        }

        long chatId = requireChatId(account, peer);
        int replyToId = 0;
        if (req.reply_to instanceof TLRPC.TL_inputReplyToMessage) {
            replyToId = ((TLRPC.TL_inputReplyToMessage) req.reply_to).reply_to_msg_id;
        }
        if (replyToId <= 0) {
            replyToId = 0;
        }
        String caption = req.message == null ? "" : req.message;
        String wireCaption = caption;
        if (e2ee) {
            org.telegram.tgnet.rest.e2ee.XoE2EE e2eeMgr = org.telegram.tgnet.rest.e2ee.XoE2EE.getInstance(account);
            if (!alreadyBackend) {
                // fresh encrypted upload — build the media envelope from the
                // upload manifest (real metadata lives ONLY inside the
                // encrypted payload; the server file row stays opaque)
                long[] totals = e2eeStore.uploadPlaintextTotals(treeUploadId);
                long plainLen = totals != null ? totals[0] : 0;
                int chunkSize = totals != null && totals[1] > 0 ? (int) totals[1] : 131072;
                org.json.JSONObject manifest = e2eeStore.uploadManifest(treeUploadId);
                String realMime = manifest != null && manifest.has("mi") ? manifest.optString("mi") : null;
                String realName = manifest != null && manifest.has("na") ? manifest.optString("na") : null;
                int realW = manifest != null ? manifest.optInt("w", 0) : 0;
                int realH = manifest != null ? manifest.optInt("h", 0) : 0;
                int realDur = manifest != null ? manifest.optInt("du", 0) : 0;
                byte[] thumbKey = thumbFileKey;
                String inner;
                try {
                    inner = org.telegram.tgnet.rest.e2ee.XoE2EEEnvelope.innerMedia(
                            bodyFileKey, plainLen, chunkSize, realMime, realName, realW, realH, realDur,
                            caption, thumbKey != null,
                            thumbKey, thumbBackendId);
                    wireCaption = e2eeMgr.encryptForPeer(peer.userId, inner);
                } catch (org.telegram.tgnet.rest.e2ee.XoE2EE.E2eeUnavailableException e) {
                    throw new XoApiException(400, e.reasonCode, e.getMessage());
                } catch (Exception e) {
                    FileLog.e("RestDispatcher: e2ee media envelope failed", e);
                    throw new XoApiException(400, "E2EE_ENCRYPT_FAILED", "could not encrypt the media message");
                }
                // register media keys so THIS device can decrypt its own later
                // downloads (the receiver gains the same keys via the envelope)
                try {
                    org.telegram.tgnet.rest.e2ee.XoE2EE.MediaMeta meta =
                            org.telegram.tgnet.rest.e2ee.XoE2EE.parseMediaMeta(new org.json.JSONObject(inner));
                    e2eeMgr.noteMediaKeys(mediaFileId, meta);
                    if (thumbBackendId > 0 && thumbKey != null) {
                        org.telegram.tgnet.rest.e2ee.XoE2EE.MediaMeta thumbMeta =
                                new org.telegram.tgnet.rest.e2ee.XoE2EE.MediaMeta();
                        thumbMeta.fileKey = thumbKey;
                        thumbMeta.chunkSize = chunkSize;
                        thumbMeta.plaintextLen = 0;
                        thumbMeta.thumbEncrypted = true;
                        e2eeMgr.noteMediaKeys(thumbBackendId, thumbMeta);
                    }
                } catch (Exception e) {
                    FileLog.e("RestDispatcher: media key registration failed", e);
                }
                e2eeStore.dropUploadIntent(treeUploadId);
                if (thumbTreeId != 0) {
                    e2eeStore.dropUploadIntent(thumbTreeId);
                }
            }
            // alreadyBackend: req.message IS the pre-built envelope (re-encrypted
            // forward) — passed through untouched by policy (E2EE_PLAINTEXT_MEDIA_REFUSED guard above)
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("RestDispatcher: send-media chat=" + chatId + " file=" + mediaFileId + " e2ee=" + e2ee);
        }

        JSONObject sent = RestGateway.getInstance(account).sendMedia(chatId, mediaFileId, wireCaption, replyToId, thumbBackendId);
        JSONObject msgJson = sent.optJSONObject("message");
        if (msgJson == null) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "send response lacks the message object");
        }
        TLRPC.TL_message message;
        try {
            message = TlJsonMapper.parseMessage(account, msgJson, peer.dialogId, peer.isGroup, peer.userId, selfId);
        } catch (org.json.JSONException e) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed sent media message: " + e.getMessage());
        }
        RestChatIndex.getInstance(account).rememberMessages(chatId, java.util.Collections.singletonList(message));

        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        TLRPC.TL_updateNewMessage update = new TLRPC.TL_updateNewMessage();
        update.message = message;
        update.pts = 0;
        update.pts_count = 0;
        updates.updates.add(update);
        updates.date = nowSeconds();
        updates.seq = 0;
        updates.users.addAll(hydrateSenders(account, java.util.Collections.singletonList(message)));
        return updates;
    }

    // ------------------------------------------------------------------ T56: albums + forward + pin (backend v2.7.0)

    /**
     * TL_messages_uploadMedia — the upstream ALBUM finalization step. For a
     * grouped send the tree uploads every item, then REPLACES each
     * TL_inputMediaUploaded* with a reference media via this request before
     * the group may dispatch (sendReadyToSendGroup refuses to fire while any
     * item still holds an Uploaded* media). Default-deny killed the album
     * right here: newInputMedia stayed null → DelayedMessage.markAsError()
     * on ALL items → "photos upload but no message ever appears".
     *
     * Answer contract (consumed by SendMessagesHelper.uploadMultiMedia's
     * callback): a MessageMedia whose photo.id / document.id IS the backend
     * file id — the consumer wraps it into TL_inputMediaPhoto/Document and
     * the later sendMultiMedia handler reads the id back as the file
     * reference. finalize runs here (identical contract to handleSendMedia),
     * so items arrive at send-multi with ready backend files.
     */
    private static TLObject handleUploadMedia(int account, TLRPC.TL_messages_uploadMedia req) {
        RestFileBridge bridge = RestFileBridge.getInstance(account);
        long treeUploadId;
        int parts = 0;
        String mime = null;
        String name = null;
        Integer width = null, height = null, duration = null;
        boolean asGif = false;
        long thumbTreeId = 0;

        if (req.media instanceof TLRPC.TL_inputMediaUploadedPhoto) {
            TLRPC.InputFile file = ((TLRPC.TL_inputMediaUploadedPhoto) req.media).file;
            treeUploadId = file.id;
            parts = file.parts;
            name = file.name;
        } else if (req.media instanceof TLRPC.TL_inputMediaUploadedDocument) {
            TLRPC.TL_inputMediaUploadedDocument input = (TLRPC.TL_inputMediaUploadedDocument) req.media;
            treeUploadId = input.file.id;
            parts = input.file.parts;
            name = input.file.name;
            mime = input.mime_type;
            if (input.thumb instanceof TLRPC.TL_inputFile || input.thumb instanceof TLRPC.TL_inputFileBig) {
                thumbTreeId = input.thumb.id;
            }
            for (int a = 0; a < input.attributes.size(); a++) {
                TLRPC.DocumentAttribute attribute = input.attributes.get(a);
                if (attribute instanceof TLRPC.TL_documentAttributeFilename) {
                    name = ((TLRPC.TL_documentAttributeFilename) attribute).file_name;
                } else if (attribute instanceof TLRPC.TL_documentAttributeImageSize) {
                    width = ((TLRPC.TL_documentAttributeImageSize) attribute).w;
                    height = ((TLRPC.TL_documentAttributeImageSize) attribute).h;
                } else if (attribute instanceof TLRPC.TL_documentAttributeVideo) {
                    TLRPC.TL_documentAttributeVideo video = (TLRPC.TL_documentAttributeVideo) attribute;
                    duration = (int) Math.round(video.duration);
                    if (video.w > 0) {
                        width = video.w;
                    }
                    if (video.h > 0) {
                        height = video.h;
                    }
                } else if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                    duration = (int) Math.round(((TLRPC.TL_documentAttributeAudio) attribute).duration);
                } else if (attribute instanceof TLRPC.TL_documentAttributeAnimated) {
                    asGif = true;
                }
            }
        } else {
            throw new XoApiException(400, "MEDIA_INVALID", "unsupported input media for uploadMedia");
        }
        if (treeUploadId == 0) {
            throw new XoApiException(400, "FILE_ID_INVALID", "upload carries no file id");
        }

        long declaredBytes = bridge.uploadedBytesFor(treeUploadId);
        // T71: albums — the uploadMedia step is chat-agnostic, so the E2EE
        // decision rides the upload intent (only 1:1-destined uploads carry
        // a key). Encrypted items finalize as opaque e2ee blobs; the real
        // metadata rides the per-item envelope built later in send-multi.
        boolean e2eeItem = org.telegram.tgnet.rest.e2ee.XoE2EEStore.getInstance(account)
                .uploadKeyForTree(treeUploadId) != null;
        if (e2eeItem) {
            mime = null;
            name = null;
            width = height = duration = null;
            asGif = false;
        }
        JSONObject finalizeEnvelope = e2eeItem
                ? bridge.finalizeUploadE2ee(treeUploadId, Math.max(1, parts), declaredBytes)
                : bridge.finalizeUpload(treeUploadId, Math.max(1, parts), mime, name, width, height, duration, declaredBytes, asGif);
        RestFileBridge.noteFileMetaFromJson(finalizeEnvelope == null ? null : finalizeEnvelope.optJSONObject("file"));
        if (finalizeEnvelope == null || finalizeEnvelope.optJSONObject("file") == null) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "finalize response lacks the file object");
        }
        JSONObject fileJson = finalizeEnvelope.optJSONObject("file");
        if (thumbTreeId != 0) {
            try {
                boolean e2eeThumb = org.telegram.tgnet.rest.e2ee.XoE2EEStore.getInstance(account)
                        .uploadKeyForTree(thumbTreeId) != null;
                JSONObject thumbEnvelope = e2eeThumb
                        ? bridge.finalizeUploadE2ee(thumbTreeId, 1, bridge.uploadedBytesFor(thumbTreeId))
                        : bridge.finalizeUpload(thumbTreeId, 1, "image/jpeg", null, null, null, null, bridge.uploadedBytesFor(thumbTreeId));
                RestFileBridge.noteFileMetaFromJson(thumbEnvelope == null ? null : thumbEnvelope.optJSONObject("file"));
            } catch (Exception e) {
                // a thumb must never fail the item; the server-side link falls
                // back to whatever the file row already carries
                // T56-fix: this FileLog has w(String) only — e(String,Throwable) is the 2-arg logger
                FileLog.e("RestDispatcher: uploadMedia thumb finalize failed, continuing", e);
            }
        }
        // parseMedia maps the finalize file json onto the exact MessageMedia
        // shapes the tree expects (photo/document objects with VIRTUAL_DC
        // locations, gif-aware) — the consumer only reads photo/document id.
        return TlJsonMapper.parseMedia(fileJson, (int) (System.currentTimeMillis() / 1000L));
    }

    /**
     * TL_messages_sendMultiMedia — the ALBUM dispatch. By the time the group
     * fires, uploadMedia replaced every item's media with a reference
     * (TL_inputMediaPhoto/Document whose id IS the backend file id), so all
     * items travel BY REFERENCE in one request. The backend endpoint treats
     * the whole request as ONE album (server-assigned shared group_id — the
     * Telegram server contract), inserts everything atomically and answers
     * in item order.
     *
     * Response contract (consumed by SendMessagesHelper's multi callback
     * ~:6099-6252): TL_updates carrying ONE TL_updateMessageID per
     * req.multi_media.random_id (order-paired) + ONE TL_updateNewMessage per
     * returned row. The parsed rows carry the server group_id, so the ack
     * re-keys the UI group and every receiver groups identically.
     */
    private static TLObject handleSendMultiMedia(int account, TLRPC.TL_messages_sendMultiMedia req) {
        long selfId = UserConfig.getInstance(account).clientUserId;
        PeerRef peer = resolvePeer(account, req.peer);
        if (peer == null) {
            throw new XoApiException(400, "PEER_ID_INVALID", "unaddressable peer for send-multi-media");
        }
        long chatId = requireChatId(account, peer);
        // T71: E2EE albums — per-item envelopes. The items are backend-file
        // references; the per-item upload keys are found through the bridge's
        // reverse index (treeUploadIdForBackend). A 1:1 album item WITHOUT a
        // key (already on the server as plaintext, e.g. a re-sent GIF) is
        // REFUSED — encrypted chats never reference plaintext media.
        boolean e2eeChat = !peer.isGroup && peer.userId > 0 && peer.userId != selfId;
        org.telegram.tgnet.rest.e2ee.XoE2EEStore e2eeStore = e2eeChat
                ? org.telegram.tgnet.rest.e2ee.XoE2EEStore.getInstance(account) : null;

        JSONArray items = new JSONArray();
        for (int a = 0; a < req.multi_media.size(); a++) {
            TLRPC.TL_inputSingleMedia single = req.multi_media.get(a);
            long mediaFileId;
            if (single.media instanceof TLRPC.TL_inputMediaPhoto) {
                mediaFileId = mediaPhotoTreeId((TLRPC.TL_inputMediaPhoto) single.media);
            } else if (single.media instanceof TLRPC.TL_inputMediaDocument) {
                TLRPC.InputDocument inputDoc = ((TLRPC.TL_inputMediaDocument) single.media).id;
                mediaFileId = inputDoc instanceof TLRPC.TL_inputDocument ? ((TLRPC.TL_inputDocument) inputDoc).id : 0;
            } else {
                throw new XoApiException(400, "MEDIA_INVALID", "sendMultiMedia item " + a + " was never finalized through uploadMedia");
            }
            if (mediaFileId == 0) {
                throw new XoApiException(400, "FILE_ID_INVALID", "sendMultiMedia item " + a + " carries no backend file id");
            }
            String wireContent = single.message == null ? "" : single.message;
            if (e2eeChat) {
                long treeUploadId = RestFileBridge.getInstance(account).treeUploadIdForBackend(mediaFileId);
                byte[] fileKey = e2eeStore.uploadKeyForTree(treeUploadId);
                if (fileKey == null) {
                    throw new XoApiException(400, "E2EE_PLAINTEXT_MEDIA_REFUSED",
                            "album item " + a + " carries no encryption key for an encrypted chat");
                }
                long[] totals = e2eeStore.uploadPlaintextTotals(treeUploadId);
                int chunkSize = totals != null && totals[1] > 0 ? (int) totals[1] : 131072;
                org.json.JSONObject manifest = e2eeStore.uploadManifest(treeUploadId);
                try {
                    String inner = org.telegram.tgnet.rest.e2ee.XoE2EEEnvelope.innerMedia(
                            fileKey,
                            totals != null ? totals[0] : 0,
                            chunkSize,
                            manifest != null && manifest.has("mi") ? manifest.optString("mi") : null,
                            manifest != null && manifest.has("na") ? manifest.optString("na") : null,
                            manifest != null ? manifest.optInt("w", 0) : 0,
                            manifest != null ? manifest.optInt("h", 0) : 0,
                            manifest != null ? manifest.optInt("du", 0) : 0,
                            wireContent, false, null, 0);
                    wireContent = org.telegram.tgnet.rest.e2ee.XoE2EE.getInstance(account)
                            .encryptForPeer(peer.userId, inner);
                    org.telegram.tgnet.rest.e2ee.XoE2EE.MediaMeta meta =
                            org.telegram.tgnet.rest.e2ee.XoE2EE.parseMediaMeta(new org.json.JSONObject(inner));
                    org.telegram.tgnet.rest.e2ee.XoE2EE.getInstance(account).noteMediaKeys(mediaFileId, meta);
                    e2eeStore.dropUploadIntent(treeUploadId);
                } catch (org.telegram.tgnet.rest.e2ee.XoE2EE.E2eeUnavailableException e) {
                    throw new XoApiException(400, e.reasonCode, e.getMessage());
                } catch (Exception e) {
                    FileLog.e("RestDispatcher: e2ee album envelope failed", e);
                    throw new XoApiException(400, "E2EE_ENCRYPT_FAILED", "could not encrypt album item " + a);
                }
            }
            JSONObject item = new JSONObject();
            try {
                item.put("media_file_id", mediaFileId);
                item.put("content", wireContent);
            } catch (org.json.JSONException e) {
                throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "item json build failed: " + e.getMessage());
            }
            items.put(item);
        }

        JSONObject sent = RestGateway.getInstance(account).sendMultiMedia(chatId, items);
        JSONArray messagesJson = sent.optJSONArray("messages");
        if (messagesJson == null || messagesJson.length() != req.multi_media.size()) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "send-multi response rows do not pair with the request items");
        }

        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        // T56-fix: rememberMessages takes List<TL_message> — collect the
        // parseMessage products under their concrete type, not TLRPC.Message
        ArrayList<TLRPC.TL_message> parsed = new ArrayList<>();
        for (int a = 0; a < messagesJson.length(); a++) {
            JSONObject msgJson = messagesJson.optJSONObject(a);
            if (msgJson == null) {
                throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "send-multi row " + a + " is not an object");
            }
            TLRPC.TL_message message;
            try {
                message = TlJsonMapper.parseMessage(account, msgJson, peer.dialogId, peer.isGroup, peer.userId, selfId);
            } catch (org.json.JSONException e) {
                throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed send-multi row: " + e.getMessage());
            }
            parsed.add(message);

            // random_id pairing: the tree matches its placeholder messages to
            // the server ids THROUGH these echoes (newIds.get(random_id)).
            TLRPC.TL_updateMessageID idUpdate = new TLRPC.TL_updateMessageID();
            idUpdate.id = message.id;
            idUpdate.random_id = req.multi_media.get(a).random_id;
            updates.updates.add(idUpdate);

            TLRPC.TL_updateNewMessage update = new TLRPC.TL_updateNewMessage();
            update.message = message;
            update.pts = 0;
            update.pts_count = 0;
            updates.updates.add(update);
        }
        RestChatIndex.getInstance(account).rememberMessages(chatId, parsed);
        updates.date = nowSeconds();
        updates.seq = 0;
        updates.users.addAll(hydrateSenders(account, parsed));
        return updates;
    }

    /**
     * TL_messages_forwardMessages — Telegram-style forwarding (single AND
     * multi: the tree batches up to 100 source ids per request, splitting on
     * source-dialog changes). The backend copies by reference, resolves the
     * forward origin (including forward-of-forward inheritance) and answers
     * {messages:[...]} in EXACT request order.
     *
     * Response contract (consumed by SendMessagesHelper's forward callback
     * ~:2311-2435): TL_updates with ONE TL_updateMessageID per
     * req.random_id (order-paired) + ONE TL_updateNewMessage per copy.
     * drop_author strips the header server-side ("forward without sender").
     */
    private static TLObject handleForward(int account, TLRPC.TL_messages_forwardMessages req) {
        long selfId = UserConfig.getInstance(account).clientUserId;
        PeerRef peer = resolvePeer(account, req.to_peer);
        if (peer == null) {
            throw new XoApiException(400, "PEER_ID_INVALID", "unaddressable forward target");
        }
        long toChatId = requireChatId(account, peer);
        if (req.id == null || req.id.isEmpty()) {
            throw new XoApiException(400, "VALIDATION_ERROR", "forward carries no message ids");
        }
        int[] ids = new int[req.id.size()];
        for (int a = 0; a < ids.length; a++) {
            ids[a] = req.id.get(a);
        }

        // T71: E2EE forwards — the server forward endpoint copies content rows
        // VERBATIM, which for an encrypted 1:1 target would either leak
        // plaintext rows or place unreadable ciphertext in the target chat.
        // ALL forwards INTO an encrypted 1:1 chat are therefore re-encrypted
        // client-side as fresh messages (text) / re-envelope'd references
        // (media whose bytes are already E2EE ciphertext). Plaintext media
        // sources are REFUSED (the hard no-plaintext guarantee).
        boolean targetPrivate = !peer.isGroup && peer.userId > 0 && peer.userId != selfId;
        if (targetPrivate) {
            return forwardEncryptedIntoPrivate(account, req, peer, toChatId, ids, selfId);
        }

        JSONObject sent = RestGateway.getInstance(account).forward(toChatId, ids, req.drop_author, req.drop_media_captions);
        JSONArray messagesJson = sent.optJSONArray("messages");
        if (messagesJson == null || messagesJson.length() != ids.length) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "forward response rows do not pair with the request ids");
        }

        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        // T56-fix: rememberMessages takes List<TL_message> — same concrete
        // collection type as the send-multi path above
        ArrayList<TLRPC.TL_message> parsed = new ArrayList<>();
        for (int a = 0; a < messagesJson.length(); a++) {
            JSONObject msgJson = messagesJson.optJSONObject(a);
            if (msgJson == null) {
                throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "forward row " + a + " is not an object");
            }
            TLRPC.TL_message message;
            try {
                message = TlJsonMapper.parseMessage(account, msgJson, peer.dialogId, peer.isGroup, peer.userId, selfId);
            } catch (org.json.JSONException e) {
                throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed forward row: " + e.getMessage());
            }
            parsed.add(message);

            TLRPC.TL_updateMessageID idUpdate = new TLRPC.TL_updateMessageID();
            idUpdate.id = message.id;
            idUpdate.random_id = req.random_id.get(a);
            updates.updates.add(idUpdate);

            TLRPC.TL_updateNewMessage update = new TLRPC.TL_updateNewMessage();
            update.message = message;
            update.pts = 0;
            update.pts_count = 0;
            updates.updates.add(update);
        }
        RestChatIndex.getInstance(account).rememberMessages(toChatId, parsed);
        updates.date = nowSeconds();
        updates.seq = 0;
        updates.users.addAll(hydrateSenders(account, parsed));
        return updates;
    }

    /**
     * T71: client-side re-encryption for forwards whose TARGET is an
     * encrypted 1:1 chat. Text sources are re-sent as fresh encrypted
     * messages; E2EE media sources are re-referenced with a freshly
     * encrypted envelope carrying the same file keys; plaintext media is
     * REFUSED (it would leak plaintext bytes into the encrypted chat's
     * server history). Sources the device has never seen fail loudly
     * instead of silently server-copying ciphertext.
     *
     * <p>Trade-off (documented in docs/E2EE.md): converted forwards do not
     * carry the "Forwarded from" header — messages/send.php is content-blind
     * by design and cannot accept forward provenance.
     */
    private static TLObject forwardEncryptedIntoPrivate(int account, TLRPC.TL_messages_forwardMessages req,
                                                        PeerRef target, long toChatId, int[] ids, long selfId) {
        RestChatIndex index = RestChatIndex.getInstance(account);
        org.telegram.tgnet.rest.e2ee.XoE2EE e2ee = org.telegram.tgnet.rest.e2ee.XoE2EE.getInstance(account);
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        ArrayList<TLRPC.TL_message> parsed = new ArrayList<>();
        for (int a = 0; a < ids.length; a++) {
            TLRPC.TL_message src = index.seenMessage(ids[a]);
            if (src == null) {
                throw new XoApiException(400, "E2EE_FORWARD_UNAVAILABLE",
                        "forwarding into an encrypted chat needs the source message on this device");
            }
            long randomId = req.random_id != null && a < req.random_id.size() ? req.random_id.get(a) : 0;

            // media reference (backend id) when the source carries media
            long mediaFileId = 0;
            if (src.media instanceof TLRPC.TL_messageMediaPhoto && src.media.photo != null) {
                mediaFileId = src.media.photo.id;
            } else if (src.media instanceof TLRPC.TL_messageMediaDocument
                    && src.media.document instanceof TLRPC.TL_document) {
                mediaFileId = src.media.document.id;
            }

            String wireContent;
            JSONObject sent;
            if (mediaFileId > 0) {
                if (!org.telegram.tgnet.rest.e2ee.XoE2EEEnvelope.isEnvelope(src.message)) {
                    throw new XoApiException(400, "E2EE_PLAINTEXT_MEDIA_REFUSED",
                            "plaintext media cannot be forwarded into an encrypted chat");
                }
                String inner = e2ee.decryptFromPeer(counterpartOf(account, src, selfId), src.message);
                if (inner == null) {
                    throw new XoApiException(400, "E2EE_FORWARD_UNAVAILABLE",
                            "source media could not be decrypted on this device");
                }
                try {
                    wireContent = e2ee.encryptForPeer(target.userId, inner);
                } catch (org.telegram.tgnet.rest.e2ee.XoE2EE.E2eeUnavailableException e) {
                    throw new XoApiException(400, e.reasonCode, e.getMessage());
                }
                sent = RestGateway.getInstance(account).sendMedia(toChatId, mediaFileId, wireContent, 0, 0);
            } else {
                String text = src.message == null ? "" : src.message;
                if (org.telegram.tgnet.rest.e2ee.XoE2EEEnvelope.isEnvelope(text)) {
                    String inner = e2ee.decryptFromPeer(counterpartOf(account, src, selfId), text);
                    if (inner == null) {
                        throw new XoApiException(400, "E2EE_FORWARD_UNAVAILABLE",
                                "source message could not be decrypted on this device");
                    }
                    try {
                        text = new org.json.JSONObject(inner).optString("x", "");
                    } catch (org.json.JSONException e) {
                        throw new XoApiException(400, "E2EE_FORWARD_UNAVAILABLE", "malformed source payload");
                    }
                }
                try {
                    wireContent = e2ee.encryptForPeer(target.userId,
                            org.telegram.tgnet.rest.e2ee.XoE2EEEnvelope.innerText(text));
                } catch (org.telegram.tgnet.rest.e2ee.XoE2EE.E2eeUnavailableException e) {
                    throw new XoApiException(400, e.reasonCode, e.getMessage());
                } catch (Exception e) {
                    FileLog.e("RestDispatcher: forward innerText failed", e);
                    throw new XoApiException(400, "E2EE_ENCRYPT_FAILED", "could not encrypt the forwarded message");
                }
                sent = RestGateway.getInstance(account).send(toChatId, wireContent, 0);
            }

            JSONObject msgJson = sent.optJSONObject("message");
            if (msgJson == null) {
                throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "forwarded send lost its message object");
            }
            TLRPC.TL_message message;
            try {
                message = TlJsonMapper.parseMessage(account, msgJson, target.dialogId, target.isGroup, target.userId, selfId);
            } catch (org.json.JSONException e) {
                throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed forwarded row");
            }
            parsed.add(message);
            if (randomId != 0) {
                TLRPC.TL_updateMessageID idUpdate = new TLRPC.TL_updateMessageID();
                idUpdate.id = message.id;
                idUpdate.random_id = randomId;
                updates.updates.add(idUpdate);
            }
            TLRPC.TL_updateNewMessage update = new TLRPC.TL_updateNewMessage();
            update.message = message;
            update.pts = 0;
            update.pts_count = 0;
            updates.updates.add(update);
        }
        RestChatIndex.getInstance(account).rememberMessages(toChatId, parsed);
        updates.date = nowSeconds();
        updates.seq = 0;
        updates.users.addAll(hydrateSenders(account, parsed));
        return updates;
    }

    /** The OTHER side of the conversation a message belongs to (session key owner). */
    private static long counterpartOf(int account, TLRPC.TL_message message, long selfId) {
        boolean out = message.out || (message.from_id != null && message.from_id.user_id == selfId);
        if (out) {
            return message.peer_id != null && message.peer_id.user_id != 0
                    ? message.peer_id.user_id : selfId;
        }
        return message.from_id != null ? message.from_id.user_id : selfId;
    }

    /**
     * TL_messages_toggleDialogPin — the pin/unpin sync (per-USER dialog
     * state). Sent by MessagesController.pinDialog whenever taskId != -1;
     * the answer only clears the pending task (TL_boolTrue), the local state
     * was applied before the request. Other devices converge through the
     * owner-only dialog_pin sync event.
     */
    private static TLObject handleToggleDialogPin(int account, TLRPC.TL_messages_toggleDialogPin req) {
        TLRPC.InputPeer inputPeer = req.peer instanceof TLRPC.TL_inputDialogPeer
                ? ((TLRPC.TL_inputDialogPeer) req.peer).peer
                : null;
        if (inputPeer == null) {
            throw new XoApiException(400, "PEER_ID_INVALID", "unaddressable dialog pin peer");
        }
        PeerRef peer = resolvePeer(account, inputPeer);
        if (peer == null) {
            throw new XoApiException(400, "PEER_ID_INVALID", "unresolvable dialog pin peer");
        }
        long chatId = requireChatId(account, peer);
        RestGateway.getInstance(account).toggleDialogPin(chatId, req.pinned);
        return new TLRPC.TL_boolTrue();
    }

    /**
     * TL_messages_reorderPinnedDialogs — the FULL pinned-set sync. Fired by
     * DialogsActivity after every preview-menu pin/unpin (and by drag
     * reorder): req.order lists the pinned dialogs topmost-first, so the
     * backend endpoint replaces the user's whole dialog_pins set with
     * exactly this order (rows not listed get unpinned). Empty order (no
     * pins at all) is answered locally — nothing to sync.
     */
    private static TLObject handleReorderPinned(int account, TLRPC.TL_messages_reorderPinnedDialogs req) {
        if (req.order == null || req.order.isEmpty()) {
            return new TLRPC.TL_boolTrue();
        }
        int[] chatIds = new int[req.order.size()];
        for (int a = 0; a < req.order.size(); a++) {
            TLRPC.InputDialogPeer dialogPeer = req.order.get(a);
            TLRPC.InputPeer inputPeer = dialogPeer instanceof TLRPC.TL_inputDialogPeer
                    ? ((TLRPC.TL_inputDialogPeer) dialogPeer).peer
                    : null;
            if (inputPeer == null) {
                throw new XoApiException(400, "PEER_ID_INVALID", "unaddressable dialog peer in pin order");
            }
            PeerRef peer = resolvePeer(account, inputPeer);
            if (peer == null) {
                throw new XoApiException(400, "PEER_ID_INVALID", "unresolvable dialog peer in pin order");
            }
            chatIds[a] = (int) requireChatId(account, peer);
        }
        RestGateway.getInstance(account).pinOrder(chatIds);
        return new TLRPC.TL_boolTrue();
    }

    /**
     * TL_inputMediaPhoto carries a {@code TL_photo} reference built from a
     * RECEIVED message — whose id the receive mapper planted as the backend
     * file id. Direct file-id passthrough (forwarding/reuse, no re-upload).
     */
    private static long mediaPhotoTreeId(TLRPC.TL_inputMediaPhoto media) {
        if (media.id instanceof TLRPC.TL_inputPhoto) {
            return ((TLRPC.TL_inputPhoto) media.id).id;
        }
        return 0;
    }

    private static TLObject handleUsersGet(int account, TLRPC.TL_users_getUsers req) {
        long selfId = UserConfig.getInstance(account).clientUserId;
        long[] ids = new long[req.id.size()];
        for (int a = 0; a < ids.length; a++) {
            TLRPC.InputUser input = req.id.get(a);
            if (input instanceof TLRPC.TL_inputUserSelf) {
                ids[a] = selfId;
            } else if (input instanceof TLRPC.TL_inputUser) {
                ids[a] = ((TLRPC.TL_inputUser) input).user_id;
            } else {
                ids[a] = 0; // exotic InputUser flavors: backend answers NOT_FOUND, vector just shrinks
            }
        }
        JSONArray usersJson = RestGateway.getInstance(account).usersGet(ids);
        TLRPC.Vector vector = new TLRPC.Vector();
        try {
            vector.objects.addAll(TlJsonMapper.parseUsers(usersJson));
        } catch (Exception e) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed users/get answer: " + e.getMessage());
        }
        return vector;
    }

    // ------------------------------------------------------------------ T32: groups + avatars + profile

    /**
     * TL_messages_createChat — group creation (GroupCreateFinalActivity →
     * MessagesController.createChat:13154). The consumer hard-checks
     * {@code instanceof TL_messages_invitedUsers} then processes its updates,
     * posts chatDidCreated with {@code chats.get(0).id} — so the updates MUST
     * carry the created chat mapped through parseGroupChat. Avatar upload
     * (inputPhoto) is applied by the SAME flow right after creation, via
     * changeChatAvatar → TL_messages_editChatPhoto (GroupCreateFinalActivity:921).
     */
    private static TLObject handleCreateChat(int account, TLRPC.TL_messages_createChat req) {
        long selfId = UserConfig.getInstance(account).clientUserId;
        long[] memberIds = new long[req.users.size()];
        for (int a = 0; a < memberIds.length; a++) {
            TLRPC.InputUser input = req.users.get(a);
            if (input instanceof TLRPC.TL_inputUserSelf) {
                memberIds[a] = selfId;
            } else if (input instanceof TLRPC.TL_inputUser) {
                memberIds[a] = ((TLRPC.TL_inputUser) input).user_id;
            } else {
                memberIds[a] = 0;
            }
        }
        JSONObject created = RestGateway.getInstance(account).createGroupChat(req.title, memberIds);
        JSONObject chatJson = created.optJSONObject("chat");
        if (chatJson == null) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "create chat response lacks the chat object");
        }
        TLRPC.TL_chat chat = mapGroupChat(chatJson);
        // keep the index warm so later sends address this chat without a rescan
        RestChatIndex.getInstance(account).putGroup(chat);
        return invitedUsers(updatesWithChats(chat));
    }

    /**
     * TL_messages_addChatUser — invite to a basic group (MessagesController
     * :13726). Same invitedUsers contract as createChat; the added user's own
     * client discovers the chat via chat_new on its next sync poll.
     */
    private static TLObject handleAddChatUser(int account, TLRPC.TL_messages_addChatUser req) {
        long userId = req.user_id instanceof TLRPC.TL_inputUser
                ? ((TLRPC.TL_inputUser) req.user_id).user_id
                : 0;
        if (userId <= 0) {
            throw new XoApiException(400, "USER_ID_INVALID", "unsupported input user");
        }
        JSONObject added = RestGateway.getInstance(account).addChatMember(req.chat_id, userId);
        JSONObject chatJson = added.optJSONObject("chat");
        if (chatJson == null) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "add-member response lacks the chat object");
        }
        // T36: backend v1.8.1 answers the post-insert authoritative membership
        // snapshot ({members, count} — members.php shape). We fabricate the
        // TL-native membership update (TL_updateChatParticipants) from it, so
        // processUpdates applies the PERSISTED state — chatFull participants in
        // storage + chatInfoDidLoad — instead of leaving the member list to
        // ChatUsersActivity's UI-local optimism (the disappearing-member root).
        JSONArray membersJson = added.optJSONArray("members");
        try {
            TLRPC.TL_updates updates = updatesWithChats(mapGroupChat(chatJson));
            if (membersJson != null) {
                updates.updates.add(wrapParticipants(chatParticipantsFromMembers(membersJson, req.chat_id)));
                for (int i = 0; i < membersJson.length(); i++) {
                    JSONObject userJson = membersJson.getJSONObject(i).optJSONObject("user");
                    if (userJson != null) {
                        updates.users.add(TlJsonMapper.parseUser(userJson, false));
                    }
                }
            }
            return invitedUsers(updates);
        } catch (XoApiException e) {
            throw e;
        } catch (Exception e) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed add-member answer: " + e.getMessage());
        }
    }

    /**
     * TL_messages_editChatTitle — group rename (ChatEditActivity →
     * changeChatTitle:14018). The consumer casts the response straight to
     * TL_updates and processUpdates applies the chats list — putChats replaces
     * the whole chat object (title included). Emits chat_new to every member
     * on the backend so their next poll re-fetches the chat.
     */
    private static TLObject handleEditChatTitle(int account, TLRPC.TL_messages_editChatTitle req) {
        JSONObject edited = RestGateway.getInstance(account).editChatTitle(req.chat_id, req.title);
        JSONObject chatJson = edited.optJSONObject("chat");
        if (chatJson == null) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "chats/edit response lacks the chat object");
        }
        return updatesWithChats(mapGroupChat(chatJson));
    }

    /**
     * TL_messages_editChatPhoto — group avatar (ChatEditActivity and
     * GroupCreateFinalActivity's post-creation apply). The uploaded crop
     * source went through this dispatcher's file routes but was never
     * finalized (avatars have no sendMedia), so finalize FIRST — the same
     * single-metadata-injection point handleSendMedia uses — then set-photo.
     * Response: TL_updates with the updated chat (photo included) so
     * processUpdates' putChats applies it. The backend also emits chat_new to
     * every member, so their dialogs reload with the new avatar.
     */
    private static TLObject handleEditChatPhoto(int account, TLRPC.TL_messages_editChatPhoto req) {
        // v1.8: TL_inputChatPhotoEmpty == "remove the group avatar" (the edit
        // screen's delete-photo action); anything else is an upload to finalize.
        if (req.photo instanceof TLRPC.TL_inputChatPhotoEmpty) {
            JSONObject removed = RestGateway.getInstance(account).clearChatPhoto(req.chat_id);
            JSONObject removedChatJson = removed.optJSONObject("chat");
            if (removedChatJson == null) {
                throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "chats/set-photo (remove) response lacks the chat object");
            }
            return updatesWithChats(mapGroupChat(removedChatJson));
        }
        long backendFileId = finalizeAvatarSource(account, req.photo);
        JSONObject applied = RestGateway.getInstance(account).setChatPhoto(req.chat_id, backendFileId);
        JSONObject chatJson = applied.optJSONObject("chat");
        if (chatJson == null) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "chats/set-photo response lacks the chat object");
        }
        return updatesWithChats(mapGroupChat(chatJson));
    }

    /**
     * TL_photos_uploadProfilePhoto — own avatar (ProfileActivity/ChatEditActivity
     * didUploadPhoto). Response contract is TL_photos_photo: ProfileActivity
     * picks the closest-to-150 and closest-to-800 sizes and builds its fresh
     * TL_userProfilePhoto from THEIR locations. Video/emoji avatars are not
     * supported by the v1.5 backend — rejected as a typed error the UI shows.
     */
    private static TLObject handleUploadProfilePhoto(int account, TLRPC.TL_photos_uploadProfilePhoto req) {
        if (req.video != null || req.video_emoji_markup != null) {
            throw new XoApiException(400, "AVATAR_VIDEO_UNSUPPORTED", "video avatars are not supported yet");
        }
        if (!(req.file instanceof TLRPC.TL_inputFile)) {
            throw new XoApiException(400, "FILE_ID_INVALID", "avatar upload carries no file");
        }
        long backendFileId = finalizeAvatarSource(account, req.file);
        JSONObject applied = RestGateway.getInstance(account).setUserPhoto(backendFileId);
        JSONObject userJson = applied.optJSONObject("user");
        // T34: self-context response — run it through the merge funnel so the
        // self user gains the new photo surfaces without losing phone/self.
        if (userJson != null) {
            XoSelf.mergeApply(account, userJson);
        }
        JSONObject photoJson = userJson != null ? userJson.optJSONObject("photo") : null;
        TLRPC.TL_photo photo = TlJsonMapper.avatarPhoto(photoJson, nowSeconds());
        if (photo == null) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "set-photo response lacks the avatar surfaces");
        }
        TLRPC.TL_photos_photo result = new TLRPC.TL_photos_photo();
        result.photo = photo;
        return result;
    }

    /**
     * TL_photos_deletePhotos — avatar removal (MessagesController:7794). The
     * response is ignored by the call site; the backend clears the current
     * avatar and purges its crops.
     */
    private static TLObject handleDeletePhotos(int account, TLRPC.TL_photos_deletePhotos req) {
        JSONObject answer = RestGateway.getInstance(account).deleteUserPhoto();
        // T34: the response carries the user json with an explicit photo:null
        // — the merge funnel applies the CLEAR through one canonical path.
        if (answer != null) {
            JSONObject userJson = answer.optJSONObject("user");
            if (userJson != null) {
                XoSelf.mergeApply(account, userJson);
            }
        }
        return new TLRPC.Vector();
    }

    /**
     * T42 — TL_messages_getSavedGifs: the GIFs tab's server sync
     * (MediaDataController.loadRecents ~:1919). The backend answers the full
     * collection (max 30 rows — always cheap); the client hash round-trip
     * never matches the server crc32, so NotModified is not attempted — the
     * full-list answer IS the refresh. Each file json parses through the
     * regular media mapper (kind='gif' -> TL_document with the animated
     * attribute), and the documents feed the existing recent-gifs machinery.
     * Contract: {@code TL_messages_savedGifs{gifs: [TL_document...]}}.
     */
    private static TLObject handleGetSavedGifs(int account, TLRPC.TL_messages_getSavedGifs req) {
        JSONObject answer = RestGateway.getInstance(account).getGifs();
        TLRPC.TL_messages_savedGifs result = new TLRPC.TL_messages_savedGifs();
        org.json.JSONArray arr = answer == null ? null : answer.optJSONArray("gifs");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject fileJson = arr.optJSONObject(i);
                if (fileJson == null) {
                    continue;
                }
                TLRPC.MessageMedia media = TlJsonMapper.parseMedia(fileJson, 0);
                if (media instanceof TLRPC.TL_messageMediaDocument
                        && ((TLRPC.TL_messageMediaDocument) media).document instanceof TLRPC.TL_document) {
                    result.gifs.add(((TLRPC.TL_messageMediaDocument) media).document);
                }
            }
        }
        return result;
    }

    /**
     * T42 — TL_messages_saveGif: "Save to GIFs" (MessagesController.saveGif
     * ~:8878, unsave=false) and the panel's remove (MediaDataController
     * .removeRecentGif ~:1043, unsave=true). Consumers only test error==null;
     * the authoritative collection rides their next gifs/list.php load. A
     * GIFS_LIMIT (collection full at 30) propagates as a typed TL_error.
     * Contract: {@code TL_boolTrue}.
     */
    private static TLObject handleSaveGif(int account, TLRPC.TL_messages_saveGif req) {
        if (!(req.id instanceof TLRPC.TL_inputDocument)) {
            throw new XoApiException(400, "FILE_ID_INVALID", "saveGif carries no document id");
        }
        long fileId = ((TLRPC.TL_inputDocument) req.id).id;
        RestGateway.getInstance(account).saveGif(fileId, req.unsave);
        return new TLRPC.TL_boolTrue();
    }

    // ------------------------------------------------------------------ T61: privacy rules + blocked users

    /**
     * T61 — TL_account_getPrivacy. The backend serves the WHOLE rule set in
     * one GET (privacy/get.php); this handler filters the requested key out
     * of it and answers the upstream contract {@code TL_account_privacyRules
     * {rules, users, chats}}. Rule order emitted: [AllowUsers?,
     * DisallowUsers?, base] — fillNextRows/formatRulesString interpret the
     * classes, not the order.
     *
     * <p>Keys the backend does not model (phone — global by product decision
     * since v2.8.0; calls/p2p/voice — no VoIP; forwards/birthday/added-by-
     * phone — not modeled) answer SUCCESS with an empty rules list: the
     * loader marks the slot loaded (no retry storm) and the UI shows its
     * neutral state instead of an error dialog. The privacy screen only
     * offers the four modeled keys.
     */
    private static TLObject handleGetPrivacy(int account, TLRPC.TL_account_getPrivacy req) {
        JSONObject answer = RestGateway.getInstance(account).privacyGet();
        TLRPC.TL_account_privacyRules result = new TLRPC.TL_account_privacyRules();
        result.users.addAll(parsePrivacyUsers(answer));
        String key = wireKeyFor(req.key);
        if (key == null) {
            return result; // unsupported key: loaded-and-empty, never an error
        }
        JSONObject rules = answer.optJSONObject("rules");
        JSONObject mine = rules == null ? null : rules.optJSONObject(key);
        if (mine != null) {
            result.rules.addAll(tlRulesFromWire(mine));
        }
        return result;
    }

    /**
     * T61 — TL_account_setPrivacy. Converts the upstream input rule list
     * ([AllowUsers/DisallowUsers/ChatParticipants…, base, AllowPremium?])
     * into the backend wire shape {key, base, allowed[], disallowed[]};
     * chat-participant and premium rules carry no product semantics here and
     * are ignored (the editors that could build them are user-only). Answers
     * the canonical {@code TL_account_privacyRules} so PrivacyControlActivity's
     * success path (putUsers + ContactsController.setPrivacyRules + the
     * privacyRulesUpdated cascade) runs unchanged.
     */
    private static TLObject handleSetPrivacy(int account, TLRPC.TL_account_setPrivacy req) {
        String key = wireKeyFor(req.key);
        if (key == null) {
            throw new XoApiException(400, "PRIVACY_KEY_UNSUPPORTED", "privacy key not modeled by this backend");
        }
        String base = null;
        ArrayList<Long> allowed = new ArrayList<>();
        ArrayList<Long> disallowed = new ArrayList<>();
        for (int a = 0, N = req.rules.size(); a < N; a++) {
            TLRPC.InputPrivacyRule rule = req.rules.get(a);
            if (rule instanceof TLRPC.TL_inputPrivacyValueAllowUsers) {
                for (int b = 0, M = ((TLRPC.TL_inputPrivacyValueAllowUsers) rule).users.size(); b < M; b++) {
                    long id = inputUserId(account, ((TLRPC.TL_inputPrivacyValueAllowUsers) rule).users.get(b));
                    if (id > 0) {
                        allowed.add(id);
                    }
                }
            } else if (rule instanceof TLRPC.TL_inputPrivacyValueDisallowUsers) {
                for (int b = 0, M = ((TLRPC.TL_inputPrivacyValueDisallowUsers) rule).users.size(); b < M; b++) {
                    long id = inputUserId(account, ((TLRPC.TL_inputPrivacyValueDisallowUsers) rule).users.get(b));
                    if (id > 0) {
                        disallowed.add(id);
                    }
                }
            } else if (rule instanceof TLRPC.TL_inputPrivacyValueAllowAll) {
                base = "everybody";
            } else if (rule instanceof TLRPC.TL_inputPrivacyValueDisallowAll) {
                base = "nobody";
            } else if (rule instanceof TLRPC.TL_inputPrivacyValueAllowContacts) {
                base = "contacts";
            }
            // TL_inputPrivacyValueAllowPremium / *ChatParticipants: not modeled — skip
        }
        if (base == null) {
            throw new XoApiException(400, "VALIDATION_ERROR", "setPrivacy carries no base rule");
        }
        org.json.JSONArray allowedJson = allowed.isEmpty() ? null : new org.json.JSONArray(allowed);
        org.json.JSONArray disallowedJson = disallowed.isEmpty() ? null : new org.json.JSONArray(disallowed);
        JSONObject answer = RestGateway.getInstance(account).privacySet(key, base, allowedJson, disallowedJson);
        TLRPC.TL_account_privacyRules result = new TLRPC.TL_account_privacyRules();
        result.users.addAll(parsePrivacyUsers(answer));
        JSONObject rules = answer.optJSONObject("rules");
        JSONObject mine = rules == null ? null : rules.optJSONObject(key);
        if (mine != null) {
            result.rules.addAll(tlRulesFromWire(mine));
        }
        return result;
    }

    /**
     * T61 — TL_contacts_getBlocked → blocked/list.php. Answer contract
     * {@code TL_contacts_blockedSlice{count, blocked[], users[]}} — exactly
     * what MessagesController.getBlockedPeers consumes (count = full list
     * size; page size &lt; limit marks the end reached).
     */
    private static TLObject handleGetBlocked(int account, TLRPC.TL_contacts_getBlocked req) {
        JSONObject answer = RestGateway.getInstance(account).blockedList(req.offset, req.limit);
        TLRPC.TL_contacts_blockedSlice result = new TLRPC.TL_contacts_blockedSlice();
        org.json.JSONArray arr = answer.optJSONArray("blocked");
        if (arr != null) {
            for (int a = 0; a < arr.length(); a++) {
                JSONObject item = arr.optJSONObject(a);
                if (item == null) {
                    continue;
                }
                TLRPC.TL_peerBlocked blocked = new TLRPC.TL_peerBlocked();
                TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
                peer.user_id = item.optLong("user_id", 0);
                blocked.peer_id = peer;
                blocked.date = item.optInt("date", nowSeconds());
                result.blocked.add(blocked);
            }
        }
        result.count = Math.max(answer.optInt("count", 0), result.blocked.size());
        result.users.addAll(parsePrivacyUsers(answer));
        return result;
    }

    /**
     * T61 — TL_contacts_block → blocked/add.php. The upstream consumer
     * (MessagesController.blockPeer) applies its local optimistic state
     * BEFORE the call and ignores the response body; a typed error here
     * (self-block) still surfaces as the generic failure path. Contract:
     * {@code TL_boolTrue}.
     */
    private static TLObject handleBlockPeer(int account, TLRPC.TL_contacts_block req) {
        long userId = inputPeerId(account, req.id);
        if (userId <= 0) {
            throw new XoApiException(400, "PEER_INVALID", "block carries no resolvable user peer");
        }
        if (userId == UserConfig.getInstance(account).clientUserId) {
            throw new XoApiException(400, "VALIDATION_ERROR", "cannot block yourself");
        }
        RestGateway.getInstance(account).blockedAdd(userId);
        return new TLRPC.TL_boolTrue();
    }

    /** T61 — TL_contacts_unblock → blocked/remove.php. Contract: {@code TL_boolTrue}. */
    private static TLObject handleUnblockPeer(int account, TLRPC.TL_contacts_unblock req) {
        long userId = inputPeerId(account, req.id);
        if (userId <= 0) {
            throw new XoApiException(400, "PEER_INVALID", "unblock carries no resolvable user peer");
        }
        RestGateway.getInstance(account).blockedRemove(userId);
        return new TLRPC.TL_boolTrue();
    }

    /** T61: InputPrivacyKey → backend wire key, or null when not modeled. */
    private static String wireKeyFor(TLRPC.InputPrivacyKey key) {
        if (key instanceof TLRPC.TL_inputPrivacyKeyStatusTimestamp) {
            return "status_timestamp";
        } else if (key instanceof TLRPC.TL_inputPrivacyKeyProfilePhoto) {
            return "photo";
        } else if (key instanceof TLRPC.TL_inputPrivacyKeyAbout) {
            return "about";
        } else if (key instanceof TLRPC.TL_inputPrivacyKeyChatInvite) {
            return "chat_invite";
        }
        return null;
    }

    /** T61: InputUser → user id (Self resolves to the current client user; Empty/unmodeled → -1). */
    private static long inputUserId(int account, TLRPC.InputUser user) {
        if (user instanceof TLRPC.TL_inputUser) {
            return ((TLRPC.TL_inputUser) user).user_id;
        } else if (user instanceof TLRPC.TL_inputUserSelf) {
            return UserConfig.getInstance(account).clientUserId;
        }
        return -1;
    }

    /** T61: InputPeer → user id (Self resolves to the current client user; Empty/unmodeled → -1). */
    private static long inputPeerId(int account, TLRPC.InputPeer peer) {
        if (peer instanceof TLRPC.TL_inputPeerUser) {
            return ((TLRPC.TL_inputPeerUser) peer).user_id;
        } else if (peer instanceof TLRPC.TL_inputPeerSelf) {
            return UserConfig.getInstance(account).clientUserId;
        }
        return -1;
    }

    /** T61: one wire rule {base, allowed[], disallowed[]} → the upstream
     * PrivacyRule list. Emitted order [AllowUsers?, DisallowUsers?, base] —
     * every consumer iterates and type-switches, order is irrelevant.
     * Package-visible: the UpdatePoller privacy event reuses it. */
    static ArrayList<TLRPC.PrivacyRule> tlRulesFromWire(JSONObject mine) {
        ArrayList<TLRPC.PrivacyRule> out = new ArrayList<>();
        org.json.JSONArray allowed = mine.optJSONArray("allowed");
        if (allowed != null && allowed.length() > 0) {
            TLRPC.TL_privacyValueAllowUsers rule = new TLRPC.TL_privacyValueAllowUsers();
            for (int a = 0; a < allowed.length(); a++) {
                rule.users.add(allowed.optLong(a));
            }
            out.add(rule);
        }
        org.json.JSONArray disallowed = mine.optJSONArray("disallowed");
        if (disallowed != null && disallowed.length() > 0) {
            TLRPC.TL_privacyValueDisallowUsers rule = new TLRPC.TL_privacyValueDisallowUsers();
            for (int a = 0; a < disallowed.length(); a++) {
                rule.users.add(disallowed.optLong(a));
            }
            out.add(rule);
        }
        String base = mine.optString("base", "everybody");
        if ("contacts".equals(base)) {
            out.add(new TLRPC.TL_privacyValueAllowContacts());
        } else if ("nobody".equals(base)) {
            out.add(new TLRPC.TL_privacyValueDisallowAll());
        } else {
            out.add(new TLRPC.TL_privacyValueAllowAll());
        }
        return out;
    }

    /** T61: the response's users[] (public json of exception users) → parsed
     * TL_user list. Package-visible: the UpdatePoller privacy event reuses it. */
    static ArrayList<TLRPC.User> parsePrivacyUsers(JSONObject answer) {
        ArrayList<TLRPC.User> users = new ArrayList<>();
        org.json.JSONArray arr = answer == null ? null : answer.optJSONArray("users");
        if (arr != null) {
            for (int a = 0; a < arr.length(); a++) {
                JSONObject userJson = arr.optJSONObject(a);
                if (userJson == null) {
                    continue;
                }
                try {
                    TLRPC.TL_user user = TlJsonMapper.parseUser(userJson, false);
                    if (user != null) {
                        users.add(user);
                    }
                } catch (Exception e) {
                    FileLog.e("RestDispatcher: privacy users parse failed", e);
                }
            }
        }
        return users;
    }

    /**
     * T42 — TL_photos_updateProfilePhoto. TWO shapes reach this route:
     *  - id = TL_inputPhotoEmpty  -> DELETE the current avatar (the
     *    ProfileActivity dialog / PhotoViewer row funnel through
     *    MessagesController.deleteUserPhoto(null) ~:7757). Backend
     *    users/delete-photo.php clears the avatar + purges the crops; the
     *    self json rides the merge funnel. The consumer clears the local
     *    photo when the answer's photo is not a TL_photo — so the answer
     *    carries photo=null deliberately.
     *  - id = a real TL_inputPhoto -> upstream "set as main". This backend
     *    keeps ONE avatar per user, so the requested photo IS the current
     *    one: answer with the current surfaces (a no-op success) instead of
     *    the destructive XO_NOT_ROUTED the UI used to swallow.
     * Contract: {@code TL_photos_photo} (hard-cast by the consumer).
     */
    private static TLObject handleUpdateProfilePhoto(int account, TLRPC.TL_photos_updateProfilePhoto req) {
        TLRPC.TL_photos_photo result = new TLRPC.TL_photos_photo();
        if (req.id instanceof TLRPC.TL_inputPhotoEmpty) {
            JSONObject answer = RestGateway.getInstance(account).deleteUserPhoto();
            if (answer != null) {
                JSONObject userJson = answer.optJSONObject("user");
                if (userJson != null) {
                    XoSelf.mergeApply(account, userJson);
                }
            }
            // result.photo stays null -> consumer plants TL_userProfilePhotoEmpty
            return result;
        }
        // set-as-main no-op: mirror the CURRENT avatar surfaces back.
        org.telegram.messenger.UserConfig userConfig = org.telegram.messenger.UserConfig.getInstance(account);
        TLRPC.User self = userConfig.getCurrentUser();
        if (self != null && self.photo != null && self.photo.photo_id != 0) {
            TLRPC.TL_photo photo = new TLRPC.TL_photo();
            photo.id = self.photo.photo_id;
            photo.dc_id = TlJsonMapper.VIRTUAL_DC;
            photo.date = nowSeconds();
            photo.flags |= 4;
            TLRPC.TL_photoSize small = new TLRPC.TL_photoSize();
            small.type = "a";
            small.location = self.photo.photo_small;
            small.w = 160;
            small.h = 160;
            TLRPC.TL_photoSize big = new TLRPC.TL_photoSize();
            big.type = "c";
            big.location = self.photo.photo_big;
            big.w = 640;
            big.h = 640;
            photo.sizes.add(small);
            photo.sizes.add(big);
            result.photo = photo;
        }
        return result;
    }

    /**
     * TL_account_updateProfile — name and/or bio (ChangeNameActivity:180 sends
     * name only; ChangeBioActivity / UserInfoActivity add the about flag 4).
     * The backend takes display_name OR bio (at least one). Returns the
     * refreshed TL_user (schema-faithful); ChangeBioActivity additionally
     * patches its local userFull.about from the edited text it already has.
     */
    private static TLObject handleUpdateProfile(int account, TLRPC.TL_account_updateProfile req) {
        boolean hasName = (req.flags & 1) != 0 || (req.flags & 2) != 0;
        boolean hasAbout = (req.flags & 4) != 0;
        if (!hasName && !hasAbout) {
            throw new XoApiException(400, "NAME_INVALID", "profile update carries no usable field");
        }
        String display = null;
        if (hasName) {
            String first = (req.flags & 1) != 0 ? req.first_name : null;
            String last = (req.flags & 2) != 0 ? req.last_name : null;
            display = ((first == null ? "" : first)
                    + (first != null && first.length() > 0 && last != null && last.length() > 0 ? " " : "")
                    + (last == null ? "" : last)).trim();
            if (display.length() == 0) {
                throw new XoApiException(400, "NAME_INVALID", "profile update carries no usable name");
            }
        }
        // about="" (flag set, empty text) is the canonical CLEAR — pass it through
        String bio = hasAbout ? (req.about == null ? "" : req.about) : null;
        JSONObject edited = RestGateway.getInstance(account).updateProfile(display, bio);
        JSONObject userJson = edited.optJSONObject("user");
        if (userJson == null) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "users/edit response lacks the user object");
        }
        // T34: self-context response → the merge funnel. The returned user is
        // whole (self flag + phone preserved), applied through the canonical
        // paths; the v1.6 public-shape replacement corrupted exactly here.
        TLRPC.TL_user editedUser = XoSelf.mergeApply(account, userJson);
        if (editedUser == null) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed edited user");
        }
        return editedUser;
    }

    // ------------------------------------------------------------------ T33: usernames + bio + deep links

    /** trim + drop one leading '@' (mirrors the backend's normalizeUsername). */
    private static String stripAt(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed;
    }

    /**
     * TL_account_checkUsername — the live check behind ChangeUsernameActivity's
     * 300 ms debounce. The upstream UI renders exactly three states:
     * TL_boolTrue = available (green), TL_error USERNAME_INVALID = red
     * ("too short" when length == 4), everything else = "in use" (red).
     * The backend encodes rule violations as reason codes; only invalid-shape
     * reasons surface as USERNAME_INVALID — occupied, RESERVED (T70: webroot
     * folder names like /hadifan — "already selected") and unknown codes fall
     * to TL_boolFalse so the UI shows the standard in-use state.
     */
    private static TLObject handleCheckUsername(int account, TLRPC.TL_account_checkUsername req) {
        String name = stripAt(req.username);
        if (name == null || name.length() == 0) {
            return new TLRPC.TL_boolFalse();
        }
        JSONObject answer = RestGateway.getInstance(account).usernameCheck(name);
        if (answer.optBoolean("available", false)) {
            return new TLRPC.TL_boolTrue();
        }
        String reason = answer.optString("reason", "USERNAME_OCCUPIED");
        if ("USERNAME_TOO_SHORT".equals(reason)
                || "USERNAME_TOO_LONG".equals(reason)
                || "USERNAME_INVALID".equals(reason)
                || "USERNAME_INVALID_START".equals(reason)) {
            throw new XoApiException(400, "USERNAME_INVALID", reason);
        }
        return new TLRPC.TL_boolFalse();
    }

    /**
     * TL_account_updateUsername — set (or clear, empty string). Response is
     * the refreshed TL_user; ChangeUsernameActivity applies the canonical
     * trio (putUsers + putUsersAndChats + saveConfig) and finishes.
     */
    private static TLObject handleUpdateUsername(int account, TLRPC.TL_account_updateUsername req) {
        String name = stripAt(req.username);
        JSONObject answer = RestGateway.getInstance(account).usernameSet(name == null ? "" : name);
        JSONObject userJson = answer.optJSONObject("user");
        if (userJson == null) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "username-set response lacks the user object");
        }
        // T34: self-context response → merge funnel (whole user out; the
        // caller's putUsers then re-applies the same coherent object).
        TLRPC.TL_user updated = XoSelf.mergeApply(account, userJson);
        if (updated == null) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed username-set user");
        }
        return updated;
    }

    /**
     * TL_contacts_resolveUsername — @username deep links
     * (ChatActivity mention taps → MessagesController.openByUserName →
     * UserNameResolver, which hard-casts the response to
     * TL_contacts_resolvedPeer, persists the user, and opens by peer id).
     */
    private static TLObject handleResolveUsername(int account, TLRPC.TL_contacts_resolveUsername req) {
        String name = stripAt(req.username);
        if (name == null || name.length() == 0) {
            throw new XoApiException(400, "USERNAME_INVALID", "empty username");
        }
        JSONObject answer = RestGateway.getInstance(account).resolveUsername(name);
        JSONObject userJson = answer.optJSONObject("user");
        if (userJson == null) {
            throw new XoApiException(400, "USERNAME_NOT_FOUND", "no user with this username");
        }
        try {
            TLRPC.TL_user user;
            // T34: resolving YOUR OWN handle must not wipe the self user with
            // its public shape — route through the merge funnel.
            if (userJson.optLong("id") == UserConfig.getInstance(account).clientUserId) {
                user = XoSelf.mergeApply(account, userJson);
                if (user == null) {
                    throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed resolved self user");
                }
            } else {
                user = TlJsonMapper.parseUser(userJson, false);
            }
            TLRPC.TL_contacts_resolvedPeer resolved = new TLRPC.TL_contacts_resolvedPeer();
            TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
            peer.user_id = user.id;
            resolved.peer = peer;
            resolved.users.add(user);
            return resolved;
        } catch (XoApiException e) {
            throw e;
        } catch (Exception e) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed resolved user: " + e.getMessage());
        }
    }

    /**
     * TL_users_getFullUser — ProfileActivity's full-user load. T34 rewrites
     * the id resolution: upstream getInputUser(self) returns
     * TL_inputUserSelf (NOT TL_inputUser), so every SELF full-user request
     * died USER_ID_INVALID here — getUserFull(self) never existed and the
     * edit-profile screen spun forever on its null guard.
     */
    private static TLObject handleGetFullUser(int account, TLRPC.TL_users_getFullUser req) {
        final long userId;
        if (req.id instanceof TLRPC.TL_inputUserSelf) {
            userId = UserConfig.getInstance(account).clientUserId;
        } else if (req.id instanceof TLRPC.TL_inputUser) {
            userId = ((TLRPC.TL_inputUser) req.id).user_id;
        } else {
            userId = 0;
        }
        if (userId <= 0) {
            throw new XoApiException(400, "USER_ID_INVALID", "unsupported input user");
        }
        JSONArray usersJson = RestGateway.getInstance(account).usersGet(new long[]{userId});
        if (usersJson == null || usersJson.length() == 0) {
            throw new XoApiException(400, "USER_ID_INVALID", "user not found");
        }
        try {
            JSONObject userJson = usersJson.getJSONObject(0);
            // Self context: the users vector flows into putUsers on the
            // response path (loadFullUser) — a public-shaped self there would
            // re-corrupt the self user. The merge funnel keeps it whole.
            TLRPC.TL_user user = userId == UserConfig.getInstance(account).clientUserId
                    ? XoSelf.mergeApply(account, userJson)
                    : TlJsonMapper.parseUser(userJson, false);
            if (user == null) {
                throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed full user");
            }
            TLRPC.TL_userFull full = new TLRPC.TL_userFull();
            // UserFull carries NO user_id: the consumer keys on full_user.id
            // (loadFullUser does getUser(res.full_user.id); updateUserInfo
            // persists under info.user.id or info.id). id IS the user id here.
            full.id = user.id;
            full.user = user; // loadFullUser re-reads it from cache anyway
            Object bioObj = userJson.opt("bio");
            if (bioObj instanceof String) {
                String about = (String) bioObj;
                if (about.length() > 0) {
                    full.flags |= 2;
                    full.about = about;
                }
            }
            TLRPC.TL_users_userFull container = new TLRPC.TL_users_userFull();
            container.full_user = full;
            container.users.add(user);
            return container;
        } catch (XoApiException e) {
            throw e;
        } catch (Exception e) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed full user: " + e.getMessage());
        }
    }

    /** chat json → TL_chat, with the mapper's malformed-answer contract. */
    private static TLRPC.TL_chat mapGroupChat(JSONObject chatJson) {
        try {
            return TlJsonMapper.parseGroupChat(chatJson);
        } catch (Exception e) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed chat json: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ T35: group full info + group about + contacts

    /**
     * TL_chatParticipants assembled from the backend membership snapshot
     * ({members:[{user, role, joined_at}]}) — shared by chats/members.php
     * (getFullChat) and the v1.8.1 add-member snapshot. Role classes map
     * Creator/Admin/Participant; version tracks the list size (join/leave).
     */
    private static TLRPC.TL_chatParticipants chatParticipantsFromMembers(JSONArray membersJson, long chatId) throws Exception {
        TLRPC.TL_chatParticipants participants = new TLRPC.TL_chatParticipants();
        participants.chat_id = chatId;
        long creatorId = 0;
        for (int i = 0; i < membersJson.length(); i++) {
            JSONObject member = membersJson.getJSONObject(i);
            if ("creator".equals(member.optString("role"))) {
                JSONObject c = member.optJSONObject("user");
                if (c != null) {
                    creatorId = c.optLong("id");
                    break;
                }
            }
        }
        for (int i = 0; i < membersJson.length(); i++) {
            JSONObject member = membersJson.getJSONObject(i);
            JSONObject userJson = member.optJSONObject("user");
            if (userJson == null) {
                continue;
            }
            TLRPC.TL_user user = TlJsonMapper.parseUser(userJson, false);
            String role = member.optString("role");
            long joined = member.optLong("joined_at", 0);
            TLRPC.ChatParticipant participant;
            if ("creator".equals(role)) {
                TLRPC.TL_chatParticipantCreator creator = new TLRPC.TL_chatParticipantCreator();
                creator.user_id = user.id;
                participant = creator;
            } else if ("admin".equals(role)) {
                TLRPC.TL_chatParticipantAdmin admin = new TLRPC.TL_chatParticipantAdmin();
                admin.user_id = user.id;
                admin.inviter_id = creatorId != 0 ? creatorId : user.id;
                admin.date = (int) joined;
                participant = admin;
            } else {
                TLRPC.TL_chatParticipant plain = new TLRPC.TL_chatParticipant();
                plain.user_id = user.id;
                plain.inviter_id = creatorId != 0 ? creatorId : user.id;
                plain.date = (int) joined;
                participant = plain;
            }
            participants.participants.add(participant);
        }
        participants.version = participants.participants.size(); // count changes on join/leave
        return participants;
    }

    /** TL_updateChatParticipants wrapper — the basic-group membership broadcast. */
    private static TLRPC.TL_updateChatParticipants wrapParticipants(TLRPC.TL_chatParticipants participants) {
        TLRPC.TL_updateChatParticipants update = new TLRPC.TL_updateChatParticipants();
        update.participants = participants;
        return update;
    }

    /**
     * TL_messages_getFullChat — the basic-group info surface (MessagesController
     * loadFullChat:6470, HARD cast to TL_messages_chatFull). The backend
     * members endpoint answers members (with roles) + the viewer's chat
     * payload in ONE call; we assemble the exact TL_chatFull contract:
     * participants (Creator/Admin/Participant classes from the backend role),
     * about (drives ProfileActivity's description row + ChatEditActivity's
     * bio field), chat_photo (flag 4), notify_settings (mandatory field).
     *
     * <p>This one route is what un-empties: ProfileActivity's inline member
     * list, ChatUsersActivity's members/administrators screens (they read the
     * cached chatFull locally for basic groups — no other network path
     * exists), and the description field in the group editor.</p>
     */
    private static TLObject handleGetFullChat(int account, TLRPC.TL_messages_getFullChat req) {
        if (req.chat_id <= 0) {
            throw new XoApiException(400, "CHAT_ID_INVALID", "invalid chat id");
        }
        JSONObject answer = RestGateway.getInstance(account).chatMembers(req.chat_id);
        JSONArray membersJson = answer.optJSONArray("members");
        JSONObject chatJson = answer.optJSONObject("chat");
        if (membersJson == null || chatJson == null) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "chats/members answer lacks members or chat");
        }
        try {
            TLRPC.TL_messages_chatFull result = new TLRPC.TL_messages_chatFull();
            TLRPC.TL_chatFull full = new TLRPC.TL_chatFull();
            full.id = req.chat_id;

            // about: explicit-null safe (the optString("null") trap — T33)
            Object aboutObj = chatJson.opt("about");
            if (aboutObj instanceof String && ((String) aboutObj).length() > 0) {
                full.about = (String) aboutObj;
            }

            TLRPC.TL_chatParticipants participants = chatParticipantsFromMembers(membersJson, req.chat_id);
            TLRPC.TL_chat chat = mapGroupChat(chatJson);
            full.participants = participants;
            full.participants_count = participants.participants.size();
            full.notify_settings = new TLRPC.TL_peerNotifySettings();
            // exported_invite stays unset (flag off, null) — no invite links in
            // this backend yet; a planted empty link would wake the link UI.

            JSONObject photoJson = chatJson.optJSONObject("photo");
            // ChatFull.chat_photo is typed Photo (MTProto chatFull carries a
            // full photo object, not the small TL_chatPhoto surface) — build
            // the same TL_photo the avatar upload route answers with.
            TLRPC.TL_photo chatPhoto = TlJsonMapper.avatarPhoto(photoJson, nowSeconds());
            if (chatPhoto != null) {
                full.chat_photo = chatPhoto;
                full.flags |= 4;
            }

            result.full_chat = full;
            result.chats.add(chat);
            for (int i = 0; i < membersJson.length(); i++) {
                JSONObject userJson = membersJson.getJSONObject(i).optJSONObject("user");
                if (userJson != null) {
                    result.users.add(TlJsonMapper.parseUser(userJson, false));
                }
            }
            return result;
        } catch (XoApiException e) {
            throw e;
        } catch (Exception e) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed chats/members answer: " + e.getMessage());
        }
    }

    /**
     * TL_messages_editChatAbout — group description save (ChatEditActivity →
     * MessagesController.updateChatAbout:13505). Consumer contract: response
     * must be TL_boolTrue, then the call site patches info.about and posts
     * chatInfoDidLoad itself. Empty string clears the about (backend contract).
     */
    private static TLObject handleEditChatAbout(int account, TLRPC.TL_messages_editChatAbout req) {
        long chatId = req.peer instanceof TLRPC.TL_inputPeerChat
                ? ((TLRPC.TL_inputPeerChat) req.peer).chat_id
                : 0;
        if (chatId <= 0) {
            throw new XoApiException(400, "PEER_ID_INVALID", "unsupported peer for editChatAbout");
        }
        RestGateway.getInstance(account).editChatAbout(chatId, req.about != null ? req.about : "");
        return new TLRPC.TL_boolTrue();
    }

    /**
     * TL_contacts_getContacts — the contacts list (ContactsController
     * loadContacts:1514, HARD cast to contacts_Contacts). The backend list
     * answers REGISTERED contacts only (Telegram getContacts semantics) with
     * viewer-ruled users (contact_name → first_name override + phone +
     * contact flag, mapped by TlJsonMapper). hash: we never answer
     * contactsNotModified — the client applies the full list every time,
     * which keeps it coherent with server-side deletions.
     */
    private static TLObject handleContactsGet(int account, TLRPC.TL_contacts_getContacts req) {
        JSONObject answer = RestGateway.getInstance(account).contactsGet();
        long selfId = UserConfig.getInstance(account).clientUserId;
        try {
            TLRPC.TL_contacts_contacts result = new TLRPC.TL_contacts_contacts();
            JSONArray arr = answer.optJSONArray("contacts");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject userJson = arr.getJSONObject(i).optJSONObject("user");
                    if (userJson == null) {
                        continue;
                    }
                    TLRPC.TL_user user = TlJsonMapper.parseUser(userJson, false);
                    if (user.id == selfId) {
                        // T38: the viewer's own user must never ride a contact
                        // vector — putUsers would wholesale-replace the self
                        // user (phone gone, self flag gone). The backend refuses
                        // to emit such rows since v2.0; this is defense in depth.
                        continue;
                    }
                    TLRPC.TL_contact contact = new TLRPC.TL_contact();
                    contact.user_id = user.id;
                    result.contacts.add(contact);
                    result.users.add(user);
                }
            }
            result.saved_count = answer.optInt("saved_count", 0);
            return result;
        } catch (Exception e) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed contacts/list answer: " + e.getMessage());
        }
    }

    /**
     * TL_contacts_importContacts — TWO distinct producers share this route:
     *
     *   1. The New-contact sheet (NewContactBottomSheet:666, ONE entry,
     *      client_id left 0) — a DELIBERATE add typed by the user. It goes to
     *      contacts/save.php (phone path): upsert + tombstone clear + resolve.
     *      This is what makes re-adding a deleted number possible: the device
     *      book sync must respect tombstones, a deliberate re-add clears them
     *      (the v1.9 design — contacts/save.php is the un-tombstone door).
     *   2. The device-phonebook batch sync (performSyncPhoneBook, up to 500
     *      entries, client_id = the device row id — never 0) → contacts/
     *      import.php: upsert everything except tombstoned numbers and the
     *      caller's own number; answer only the registered matches.
     *
     * T38 discriminator: client_id == 0 ⟺ the sheet (the only producer that
     * never sets it). Both consumers hard cast to TL_contacts_importedContacts.
     */
    private static TLObject handleContactsImport(int account, TLRPC.TL_contacts_importContacts req) {
        if (req.contacts.isEmpty()) {
            throw new XoApiException(400, "CONTACTS_EMPTY", "import carries no contacts");
        }
        long selfId = UserConfig.getInstance(account).clientUserId;
        try {
            // Partition the producers: deliberate single adds (the sheet —
            // client_id 0) go to contacts/save.php one by one (upsert +
            // tombstone clear + resolve); the device-phonebook entries go to
            // contacts/import.php in ONE batch (the book contract: upsert
            // everything except tombstones and the caller's own number).
            ArrayList<TLRPC.TL_inputPhoneContact> deliberate = new ArrayList<>();
            ArrayList<TLRPC.TL_inputPhoneContact> book = new ArrayList<>();
            for (int i = 0; i < req.contacts.size(); i++) {
                TLRPC.TL_inputPhoneContact input = req.contacts.get(i);
                if (input.client_id == 0) {
                    deliberate.add(input);
                } else {
                    book.add(input);
                }
            }

            JSONArray usersJson = new JSONArray();
            JSONArray importedJson = new JSONArray(); // {"user_id": n} entries

            if (!book.isEmpty()) {
                JSONObject[] entries = new JSONObject[book.size()];
                for (int i = 0; i < book.size(); i++) {
                    TLRPC.TL_inputPhoneContact input = book.get(i);
                    entries[i] = new JSONObject();
                    entries[i].put("phone", input.phone != null && input.phone.length() > 0 ? input.phone : ("+" + input.client_id));
                    String first = input.first_name != null ? input.first_name.trim() : "";
                    String last = input.last_name != null ? input.last_name.trim() : "";
                    String name = (first + (first.length() > 0 && last.length() > 0 ? " " : "") + last).trim();
                    entries[i].put("name", name.length() > 0 ? name : "Contact");
                }
                JSONObject answer = RestGateway.getInstance(account).contactsImport(entries);
                JSONArray batch = answer.optJSONArray("imported");
                if (batch != null) {
                    for (int k = 0; k < batch.length(); k++) {
                        importedJson.put(batch.getJSONObject(k));
                    }
                }
                appendNonSelfUsers(answer.optJSONArray("users"), selfId, usersJson);
            }

            for (int i = 0; i < deliberate.size(); i++) {
                TLRPC.TL_inputPhoneContact input = deliberate.get(i);
                String first = input.first_name != null ? input.first_name.trim() : "";
                String last = input.last_name != null ? input.last_name.trim() : "";
                String name = (first + (first.length() > 0 && last.length() > 0 ? " " : "") + last).trim();
                if (name.length() == 0) {
                    name = "Contact";
                }
                String phone = input.phone != null && input.phone.length() > 0 ? input.phone : ("+" + input.client_id);
                // DELIBERATE add: the save contract (upsert + un-tombstone +
                // resolve). save.php answers {contact, user?, saved_count}.
                JSONObject answer = RestGateway.getInstance(account).contactsSave(null, phone, name);
                JSONObject userJson = answer.optJSONObject("user");
                if (userJson != null && userJson.optLong("id", 0) != selfId) {
                    usersJson.put(userJson);
                    JSONObject importedEntry = new JSONObject();
                    importedEntry.put("user_id", userJson.optLong("id", 0));
                    importedJson.put(importedEntry);
                }
            }

            TLRPC.TL_contacts_importedContacts result = new TLRPC.TL_contacts_importedContacts();
            for (int i = 0; i < importedJson.length(); i++) {
                long backendUserId = importedJson.getJSONObject(i).optLong("user_id");
                if (backendUserId <= 0) {
                    continue;
                }
                TLRPC.TL_importedContact imported = new TLRPC.TL_importedContact();
                imported.user_id = backendUserId;
                // client_id: the tree keys phone-book entries by it; we
                // match positionally — the first N imported entries align
                // with the request order per the backend contract.
                imported.client_id = i < req.contacts.size() ? req.contacts.get(i).client_id : 0;
                result.imported.add(imported);
            }
            for (int i = 0; i < usersJson.length(); i++) {
                TLRPC.TL_user savedUser = TlJsonMapper.parseUser(usersJson.getJSONObject(i), false);
                // T39: a deliberate save that returned a resolved user IS a
                // contact relationship (the backend row was just upserted) —
                // the flag is authoritative even if the wire json lost it to a
                // type coercion. Without it the profile opens on the
                // "Add to Contacts" affordance and the list never binds the
                // contact locally (addContact gates on u.contact).
                if (!savedUser.contact) {
                    savedUser.contact = true;
                    savedUser.flags |= 2048;
                }
                result.users.add(savedUser);
            }
            return result;
        } catch (XoApiException e) {
            throw e;
        } catch (Exception e) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed contacts/import answer: " + e.getMessage());
        }
    }

    /** T38: append user jsons to {@code out}, never letting the SELF user ride the vector. */
    private static void appendNonSelfUsers(JSONArray usersJson, long selfId, JSONArray out) throws Exception {
        if (usersJson == null) {
            return;
        }
        for (int i = 0; i < usersJson.length(); i++) {
            JSONObject userJson = usersJson.getJSONObject(i);
            if (userJson.optLong("id", 0) == selfId) {
                continue;
            }
            out.put(userJson);
        }
    }

    /**
     * TL_messages_editMessage — text AND caption edits (T38/13; the route
     * gap was the whole "Can't edit this message" class: the client's edit
     * UI, the canEditMessage gate and SendMessagesHelper all work, the
     * request just had no route — default-deny answered XO_NOT_ROUTED).
     *
     * The consumer is SendMessagesHelper.editMessage: the response must be a
     * TL_updates — we fabricate ONE TL_updateEditMessage carrying the fresh
     * backend message so processUpdates updates cache + storage + UI in the
     * canonical path (and the "(edited)" label rides edit_date).
     *
     * Flag handling: 2048 (message) is the text/caption path — the ONLY
     * content our REST model edits (a media message's caption IS its content
     * column). 16384 (media) is accepted and IGNORED on purpose: upstream
     * re-wraps the EXISTING photo/document when editing a caption, so the
     * media payload is redundant here — the backend keeps the stored media.
     * Entities/no_webpage/schedule have no REST equivalent (mentions are
     * client-detected at parse time).
     */
    private static TLObject handleEditMessage(int account, TLRPC.TL_messages_editMessage req) {
        if ((req.flags & 2048) == 0 || req.message == null || req.message.length() == 0) {
            throw new XoApiException(400, "MESSAGE_EMPTY", "edit carries no new content");
        }
        PeerRef peer = resolvePeer(account, req.peer);
        if (peer == null) {
            throw new XoApiException(400, "PEER_ID_INVALID", "unaddressable peer for edit");
        }
        long chatId = requireChatId(account, peer);
        if (req.id <= 0) {
            throw new XoApiException(400, "MESSAGE_ID_INVALID", "invalid message id");
        }
        // T71: edits of 1:1 messages re-encrypt the new text (the stored
        // content column only ever holds ciphertext for E2EE chats).
        long selfIdEarly = UserConfig.getInstance(account).clientUserId;
        String wireContent = req.message;
        if (!peer.isGroup && peer.userId > 0 && peer.userId != selfIdEarly) {
            try {
                wireContent = XoE2EE.getInstance(account).encryptForPeer(peer.userId,
                        org.telegram.tgnet.rest.e2ee.XoE2EEEnvelope.innerEdit(req.message));
            } catch (org.telegram.tgnet.rest.e2ee.XoE2EE.E2eeUnavailableException e) {
                throw new XoApiException(400, e.reasonCode, e.getMessage());
            } catch (Exception e) {
                FileLog.e("RestDispatcher: e2ee edit failed", e);
                throw new XoApiException(400, "E2EE_ENCRYPT_FAILED", "could not encrypt the edit");
            }
        }
        JSONObject answer = RestGateway.getInstance(account).messagesEdit(chatId, req.id, wireContent);
        JSONObject msgJson = answer.optJSONObject("message");
        if (msgJson == null) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "edit response lacks the message object");
        }
        long selfId = UserConfig.getInstance(account).clientUserId;
        TLRPC.TL_message message;
        try {
            message = TlJsonMapper.parseMessage(account, msgJson, peer.dialogId, peer.isGroup, peer.userId, selfId);
        } catch (org.json.JSONException e) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed edited message: " + e.getMessage());
        }
        RestChatIndex.getInstance(account).rememberMessages(chatId, java.util.Collections.singletonList(message));

        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        TLRPC.TL_updateEditMessage edit = new TLRPC.TL_updateEditMessage();
        edit.message = message;
        edit.pts = 0;
        edit.pts_count = 0;
        updates.updates.add(edit);
        updates.date = nowSeconds();
        updates.seq = 0;
        updates.users.addAll(hydrateSenders(account, java.util.Collections.singletonList(message)));
        updates.chats.addAll(peer.groupChats());
        return updates;
    }

    /**
     * T39: TL_messages_getMessageEditData — the edit-ENTRY pre-check
     * (ChatActivity.startEditingMessageObject ~:30108 sends it right after
     * putting the message text into the input). The consumer's only branch on
     * the result is {@code response == null} → EditMessageError AlertDialog +
     * exit edit mode; a non-null answer simply lets edit mode stand. Edit
     * permission is already enforced locally by MessageObject.canEditMessage
     * (own message, editable media class, edit window), so the REST model
     * answers the schema class directly: caption = false (text editing — for
     * media messages the caption IS the content column in this product, and
     * the send path (ROUTE_EDIT_MESSAGE) treats it identically).
     */
    private static TLObject handleEditData() {
        return new TLRPC.TL_messages_messageEditData();
    }

    /**
     * T39: TL_messages_deleteHistory — real dialog deletion ("delete chat"
     * and "clear history" both funnel here through MessagesController
     * deleteDialog ~:8783). The consumer (deleteDialog callback ~:8795) HARD
     * casts to TL_messages_affectedHistory: offset must stay 0 (a positive
     * offset re-loops deleteDialog), pts/pts_count stay 0 (pinned baseline,
     * same rule as every other fabricated response). Backend contract:
     * POST /chats/delete-dialog.php upserts the caller's hidden_dialogs row
     * — just_clear=true keeps the (now empty) dialog listed, just_clear=false
     * removes it from chats/list until a newer message arrives; history
     * never replays past hidden_before. req.revoke ("delete for both") has
     * no REST backing in v1 — the peer keeps their copy (documented in
     * API.md §6); the local delete already ran before this request.
     */
    private static TLObject handleDeleteHistory(int account, TLRPC.TL_messages_deleteHistory req) {
        PeerRef peer = resolvePeer(account, req.peer);
        if (peer == null) {
            throw new XoApiException(400, "PEER_ID_INVALID", "unaddressable peer for delete-history");
        }
        long chatId = requireChatId(account, peer);
        RestGateway.getInstance(account).deleteDialog(chatId, req.just_clear);
        TLRPC.TL_messages_affectedHistory res = new TLRPC.TL_messages_affectedHistory();
        res.offset = 0;
        res.pts = 0;
        res.pts_count = 0;
        return res;
    }

    /**
     * T40: TL_messages_deleteChatUser — KICK another member / LEAVE a basic
     * group (MessagesController.deleteParticipantFromChat ~:13940/:14016; the
     * UI funnels are the ProfileActivity member long-press, ChatUsersActivity
     * kick and the needDeleteDialog leave flow). The consumers HARD cast the
     * response to TLRPC.Updates (~:13953/:14029) and processUpdates it.
     *
     * SELF (leave): the caller already deleted their own dialog locally
     * (deleteDialog runs BEFORE the send) and the backend deleted the
     * membership row — the answer is an EMPTY TL_updates, there is nothing to
     * apply; the leaver's OTHER devices converge via the chat_member sync
     * event.
     *
     * KICK: the backend v2.2 answers the authoritative post-kick membership
     * snapshot ({members, count, chat} — members.php shape); fabricate the
     * TL-native TL_updateChatParticipants from it (same contract as
     * handleAddChatUser) so processUpdates applies the PERSISTED state.
     * The kicked user themselves gets the chat_member {event: kick} push.
     */
    private static TLObject handleDeleteChatUser(int account, TLRPC.TL_messages_deleteChatUser req) {
        if (req.chat_id <= 0) {
            throw new XoApiException(400, "CHAT_ID_INVALID", "chat_id required");
        }
        long userId;
        if (req.user_id instanceof TLRPC.TL_inputUserSelf) {
            userId = UserConfig.getInstance(account).clientUserId;
        } else if (req.user_id instanceof TLRPC.TL_inputUser) {
            userId = ((TLRPC.TL_inputUser) req.user_id).user_id;
        } else {
            throw new XoApiException(400, "USER_ID_INVALID", "unsupported input user");
        }
        if (userId == UserConfig.getInstance(account).clientUserId) {
            RestGateway.getInstance(account).leaveChat(req.chat_id);
            return new TLRPC.TL_updates();
        }
        JSONObject kicked = RestGateway.getInstance(account).kickChatMember(req.chat_id, userId);
        try {
            JSONObject chatJson = kicked.optJSONObject("chat");
            if (chatJson == null) {
                throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "kick response lacks the chat object");
            }
            TLRPC.TL_updates updates = updatesWithChats(mapGroupChat(chatJson));
            JSONArray membersJson = kicked.optJSONArray("members");
            if (membersJson != null) {
                updates.updates.add(wrapParticipants(chatParticipantsFromMembers(membersJson, req.chat_id)));
                for (int i = 0; i < membersJson.length(); i++) {
                    JSONObject userJson = membersJson.getJSONObject(i).optJSONObject("user");
                    if (userJson != null) {
                        updates.users.add(TlJsonMapper.parseUser(userJson, false));
                    }
                }
            }
            return updates;
        } catch (XoApiException e) {
            throw e;
        } catch (Exception e) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed kick answer: " + e.getMessage());
        }
    }

    /**
     * T40: TL_messages_editChatAdmin — promote/demote in a basic group
     * (MessagesController.setUserAdminRole ~:7655; ChatRightsEditActivity
     * Save and ChatUsersActivity "Remove admin" both land here). The
     * consumer (~:7661) tests error == null ONLY — the schema response class
     * is Bool and is otherwise unused; the acting UI refreshes via
     * loadFullChat 1 s later, every OTHER member via chat_member
     * {event: promote|demote}. Promoting an EXISTING member is preceded by
     * TL_messages_addChatUser (the addUserToChat chain): the backend's
     * USER_ALREADY_PARTICIPANT answer is swallowed by the chain's
     * ignoreIfAlreadyExists branch, then this request goes out. Answer
     * TL_boolTrue.
     */
    private static TLObject handleEditChatAdmin(int account, TLRPC.TL_messages_editChatAdmin req) {
        if (req.chat_id <= 0) {
            throw new XoApiException(400, "CHAT_ID_INVALID", "chat_id required");
        }
        long userId;
        if (req.user_id instanceof TLRPC.TL_inputUserSelf) {
            userId = UserConfig.getInstance(account).clientUserId;
        } else if (req.user_id instanceof TLRPC.TL_inputUser) {
            userId = ((TLRPC.TL_inputUser) req.user_id).user_id;
        } else {
            throw new XoApiException(400, "USER_ID_INVALID", "unsupported input user");
        }
        RestGateway.getInstance(account).setChatAdmin(req.chat_id, userId, req.is_admin);
        return new TLRPC.TL_boolTrue();
    }

    /**
     * T40: TL_messages_deleteChat — delete a basic group FOR EVERYONE
     * (MessagesController.deleteParticipantFromChat forceDelete branch
     * ~:13934/:14009 — the creator's "delete and exit" with delete-for-all).
     * The consumers IGNORE the response entirely (empty callback body); the
     * local dialog deletion already ran (deleteDialog before the send) and
     * every ex-member converges via chat_member {event: deleted}. The
     * backend v2.2 hard-deletes the chats row, memberships, hidden dialogs
     * and messages (creator-only, enforced server-side). Answer TL_boolTrue.
     */
    private static TLObject handleDeleteChat(int account, TLRPC.TL_messages_deleteChat req) {
        if (req.chat_id <= 0) {
            throw new XoApiException(400, "CHAT_ID_INVALID", "chat_id required");
        }
        RestGateway.getInstance(account).deleteChat(req.chat_id);
        return new TLRPC.TL_boolTrue();
    }

    /**
     * TL_contacts_addContact — "Add to contacts" from a profile
     * (ContactAddActivity → ContactsController.addContact:2351, HARD cast to
     * TL_updates; the consumer reads res.users). The saved (viewer-ruled)
     * user rides back so the client's cache gains contact_name/phone/contact
     * immediately. Name: first_name (+ " " + last_name when present).
     */
    private static TLObject handleContactsAdd(int account, TLRPC.TL_contacts_addContact req) {
        long userId = req.id instanceof TLRPC.TL_inputUser
                ? ((TLRPC.TL_inputUser) req.id).user_id
                : 0;
        if (userId <= 0) {
            throw new XoApiException(400, "USER_ID_INVALID", "unsupported input user");
        }
        String first = req.first_name != null ? req.first_name.trim() : "";
        String last = req.last_name != null ? req.last_name.trim() : "";
        String name = (first + (first.length() > 0 && last.length() > 0 ? " " : "") + last).trim();
        if (name.length() == 0) {
            throw new XoApiException(400, "CONTACT_NAME_INVALID", "contact name is empty");
        }
        JSONObject answer = RestGateway.getInstance(account).contactsSave(userId, null, name);
        try {
            TLRPC.TL_updates updates = new TLRPC.TL_updates();
            updates.date = nowSeconds();
            JSONObject userJson = answer.optJSONObject("user");
            if (userJson != null) {
                TLRPC.TL_user savedUser = TlJsonMapper.parseUser(userJson, false);
                // T39: same rule as the import path — a 200 save response with
                // a resolved user means the contact row exists; the flag is
                // authoritative and drives addContact's local dict insert.
                if (!savedUser.contact) {
                    savedUser.contact = true;
                    savedUser.flags |= 2048;
                }
                updates.users.add(savedUser);
            }
            return updates;
        } catch (XoApiException e) {
            throw e;
        } catch (Exception e) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed contacts/save answer: " + e.getMessage());
        }
    }

    /**
     * TL_contacts_deleteContacts — contact removal (ProfileActivity
     * delete_contact → ContactsController.deleteContact:505, HARD cast to
     * TL_updates). T37: the consumer clears its contact lists locally, but
     * the CACHED USER object keeps the stale contact surfaces (the saved
     * name overwrote first_name, the phone rode from the viewer rule) —
     * those would survive every reopen because profile reads hit the cache.
     * So after the backend delete succeeds we re-fetch the authoritative
     * user jsons and ride them in updates.users: processUpdates putUser
     * REPLACES the contact-shaped cache entry with the public-shaped truth
     * (backend name, no phone, contact=false) — nothing stale remains.
     */
    private static TLObject handleContactsDelete(int account, TLRPC.TL_contacts_deleteContacts req) {
        long[] ids = new long[req.id.size()];
        for (int i = 0; i < req.id.size(); i++) {
            TLRPC.InputUser input = req.id.get(i);
            ids[i] = input instanceof TLRPC.TL_inputUser ? ((TLRPC.TL_inputUser) input).user_id : 0;
        }
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = nowSeconds();
        if (ids.length > 0) {
            RestGateway.getInstance(account).contactsDelete(ids);
            try {
                // usersGet answers the users ARRAY directly ({users:[…]} is
                // unwrapped by the gateway — see UserHydration.fetchUncached).
                JSONArray usersJson = RestGateway.getInstance(account).usersGet(ids);
                if (usersJson != null) {
                    for (int i = 0; i < usersJson.length(); i++) {
                        updates.users.add(TlJsonMapper.parseUser(usersJson.getJSONObject(i), false));
                    }
                }
            } catch (Exception e) {
                // The delete itself succeeded; a refresh failure only delays
                // the cache repair until the next authoritative read.
                FileLog.e("handleContactsDelete: post-delete user refresh failed", e);
            }
        }
        return updates;
    }


    /** TL_updates carrying only a chats list (processUpdates applies chats verbatim). */
    private static TLRPC.TL_updates updatesWithChats(TLRPC.TL_chat chat) {
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.chats.add(chat);
        updates.date = nowSeconds();
        updates.seq = 0;
        return updates;
    }

    /** TL_messages_invitedUsers wrapper — the createChat/addChatUser response class. */
    private static TLRPC.TL_messages_invitedUsers invitedUsers(TLRPC.TL_updates updates) {
        TLRPC.TL_messages_invitedUsers invited = new TLRPC.TL_messages_invitedUsers();
        invited.updates = updates;
        return invited;
    }

    /**
     * Shared finalize for avatar sources (own avatar + group avatar): the
     * crop source went through the file-part routes, so the bridge mapping
     * exists — finalize it exactly like sendMedia does, feed the attestation
     * index, and return the backend file id.
     */
    private static long finalizeAvatarSource(int account, Object input) {
        long treeUploadId;
        int parts;
        if (input instanceof TLRPC.TL_inputChatUploadedPhoto) {
            TLRPC.InputFile file = ((TLRPC.TL_inputChatUploadedPhoto) input).file;
            if (!(file instanceof TLRPC.TL_inputFile)) {
                throw new XoApiException(400, "MEDIA_INVALID", "unsupported chat photo input");
            }
            treeUploadId = file.id;
            parts = ((TLRPC.TL_inputFile) file).parts;
        } else if (input instanceof TLRPC.TL_inputFile) {
            treeUploadId = ((TLRPC.TL_inputFile) input).id;
            parts = ((TLRPC.TL_inputFile) input).parts;
        } else {
            throw new XoApiException(400, "MEDIA_INVALID", "avatar removal/emoji variants are not supported");
        }
        RestFileBridge bridge = RestFileBridge.getInstance(account);
        long declaredBytes = bridge.uploadedBytesFor(treeUploadId);
        JSONObject envelope = bridge.finalizeUpload(treeUploadId, Math.max(1, parts), "image/jpeg", null,
                null, null, null, declaredBytes);
        RestFileBridge.noteFileMetaFromJson(envelope == null ? null : envelope.optJSONObject("file"));
        long backendFileId = bridge.backendFileIdFor(treeUploadId);
        if (backendFileId == 0) {
            throw new XoApiException(400, "FILE_ID_INVALID", "avatar upload was never routed through this client");
        }
        return backendFileId;
    }

    private static TLObject stubState() {
        TLRPC.TL_updates_state state = new TLRPC.TL_updates_state();
        state.pts = 0;
        state.qts = 0;
        state.date = nowSeconds();
        state.seq = 0;
        state.unread_count = 0;
        return state;
    }

    private static TLObject stubDifference() {
        TLRPC.TL_updates_differenceEmpty difference = new TLRPC.TL_updates_differenceEmpty();
        difference.date = nowSeconds();
        difference.seq = 0;
        return difference;
    }

    // ------------------------------------------------------------------ peer resolution

    /** Telegram-space view of an InputPeer, translated through {@link RestChatIndex}. */
    private static final class PeerRef {
        final long dialogId;   // user id (private) or -chat_id (group)
        final boolean isGroup;
        final long userId;     // private peer user id, 0 for groups
        final int account;
        RestChatIndex.ScanResult createdScan; // set when the dispatcher created the chat

        PeerRef(int account, long dialogId, boolean isGroup, long userId) {
            this.account = account;
            this.dialogId = dialogId;
            this.isGroup = isGroup;
            this.userId = userId;
        }

        ArrayList<TLRPC.TL_user> usersToInclude() {
            return createdScan == null ? new ArrayList<>() : createdScan.users;
        }

        ArrayList<TLRPC.TL_chat> groupChats() {
            ArrayList<TLRPC.TL_chat> chats = new ArrayList<>();
            if (createdScan != null) {
                chats.addAll(createdScan.chats);
            } else if (isGroup) {
                TLRPC.TL_chat chat = RestChatIndex.getInstance(account).groupChat(-dialogId);
                if (chat != null) {
                    chats.add(chat);
                }
            }
            return chats;
        }
    }

    /**
     * Maps a TL InputPeer to the dialog id it addresses (user id / -chat id),
     * keeping the group Chat cache warm. Returns null for unsupported peers
     * (channels, empty). TL_inputPeerSelf IS supported since T38/15 — it
     * addresses the Saved Messages self-chat.
     */
    private static PeerRef resolvePeer(int account, TLRPC.InputPeer peer) {
        if (peer instanceof TLRPC.TL_inputPeerUser) {
            long userId = ((TLRPC.TL_inputPeerUser) peer).user_id;
            return new PeerRef(account, userId, false, userId);
        }
        if (peer instanceof TLRPC.TL_inputPeerSelf) {
            // T38/15: the Saved-MESSAGES self-chat — getInputPeer(clientUserId)
            // is TL_inputPeerSelf; the dialog id IS the own user id.
            long userId = UserConfig.getInstance(account).clientUserId;
            return new PeerRef(account, userId, false, userId);
        }
        if (peer instanceof TLRPC.TL_inputPeerChat) {
            long chatId = ((TLRPC.TL_inputPeerChat) peer).chat_id;
            return new PeerRef(account, -chatId, true, 0);
        }
        return null;
    }

    /**
     * Backend chat id for a resolved peer. Private peers resolve through the
     * index and — on a miss — a find-or-create /chats/create.php call
     * (deterministic pair_key, idempotent). Groups address themselves.
     */
    private static long requireChatId(int account, PeerRef peer) {
        RestChatIndex index = RestChatIndex.getInstance(account);
        if (peer.isGroup) {
            return -peer.dialogId;
        }
        long chatId = index.privateChatIdFor(peer.dialogId);
        if (chatId != 0) {
            return chatId;
        }
        JSONObject created = RestGateway.getInstance(account).createPrivateChat(peer.dialogId);
        JSONObject chatJson = created.optJSONObject("chat");
        if (chatJson == null) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "create chat response lacks the chat object");
        }
        try {
            JSONArray single = new JSONArray();
            single.put(chatJson);
            RestChatIndex.ScanResult scan = index.scanChats(single);
            chatId = index.privateChatIdFor(peer.dialogId);
            if (chatId == 0) {
                chatId = chatJson.getLong("id");
                index.putPrivate(peer.dialogId, chatId);
            }
            peer.createdScan = scan;
            return chatId;
        } catch (Exception e) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed created chat: " + e.getMessage());
        }
    }

    /**
     * Users vector for TL response containers: cached senders pass through,
     * the rest share ONE /users/get.php call ({@link UserHydration}) so group
     * history renders without N round trips. The history endpoint itself
     * carries no user objects.
     */
    private static ArrayList<TLRPC.TL_user> hydrateSenders(int account, List<? extends TLRPC.Message> messages) {
        ArrayList<Long> senderIds = UserHydration.senderIds(messages);
        ArrayList<TLRPC.TL_user> users = new ArrayList<>();
        MessagesController controller = MessagesController.getInstance(account);
        for (int a = 0; a < senderIds.size(); a++) {
            TLRPC.User cached = controller.getUser(senderIds.get(a));
            if (cached instanceof TLRPC.TL_user) {
                users.add((TLRPC.TL_user) cached);
            }
        }
        users.addAll(UserHydration.fetchUncached(account, senderIds));
        return users;
    }

    // ------------------------------------------------------------------ delivery

    private static void deliver(TLObject response, TLRPC.TL_error error, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp) {
        Utilities.stageQueue.postRunnable(() -> {
            if (onComplete != null) {
                onComplete.run(response, error);
            } else if (onCompleteTimestamp != null) {
                onCompleteTimestamp.run(response, error, nowSeconds());
            }
        });
    }

    /** Maps a gateway failure onto TL_error for the original call site's error chain. */
    private static TLRPC.TL_error toTlError(XoApiException e) {
        String text = e.errorCode;
        if ("INVALID_RANGE".equals(text)) {
            // backend 416 (beyond EOF) == MTProto's canonical answer the tree
            // already understands: FileLoadOperation finishes the download on
            // OFFSET_INVALID when the received bytes align (processRequestResult)
            text = "OFFSET_INVALID";
        }
        return tlError(e.httpStatus == 0 ? 400 : e.httpStatus, text);
    }

    private static TLRPC.TL_error tlError(int code, String text) {
        TLRPC.TL_error error = new TLRPC.TL_error();
        error.code = code;
        error.text = text == null ? "SERVER_ERROR" : text;
        return error;
    }

    private static int nowSeconds() {
        return (int) (System.currentTimeMillis() / 1000L);
    }
}

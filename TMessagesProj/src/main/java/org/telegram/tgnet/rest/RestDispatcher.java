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
                RestGateway.getInstance(account).chatsList(), selfId, index);
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
            messages = TlJsonMapper.parseHistory(messagesJson, peer.dialogId, peer.isGroup, peer.userId, selfId);
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

        JSONObject sent = RestGateway.getInstance(account).send(chatId, req.message, replyToId);
        JSONObject msgJson = sent.optJSONObject("message");
        if (msgJson == null) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "send response lacks the message object");
        }
        TLRPC.TL_message message;
        try {
            message = TlJsonMapper.parseMessage(msgJson, peer.dialogId, peer.isGroup, peer.userId, selfId);
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
        long stored = RestGateway.getInstance(account).fileChunk(backendId, part, body, realLen);
        if (stored >= 0 && stored != data.length) {
            FileLog.w("RestDispatcher: part " + part + " stored " + stored + " of " + data.length + "B, re-sending");
            stored = RestGateway.getInstance(account).fileChunk(backendId, part, body, realLen);
            if (stored >= 0 && stored != data.length) {
                throw new XoApiException(502, "PART_SIZE_MISMATCH",
                        "server stored " + stored + " of " + data.length + " bytes for part " + part);
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
     * TL_upload_getFile — ranged download. Location → backend file id per the
     * synthetic-id contract ({@link RestFileBridge#resolveBackendFileId},
     * including thumb-letter resolution). Answer contract: {@code TL_upload_file}
     * with bytes positioned at 0 and limit == byte count (FileLoadOperation
     * ~:2542 instanceof-tests it and writes bytes.buffer straight into its file
     * channel; a short tail chunk closes the download).
     */
    private static TLObject handleFileGet(int account, TLRPC.TL_upload_getFile req) {
        long backendId = RestFileBridge.getInstance(account).resolveBackendFileId(req.location);
        if (backendId <= 0) {
            throw new XoApiException(400, "FILE_ID_INVALID", "unsupported file location");
        }
        if (req.offset < 0) {
            throw new XoApiException(400, "INVALID_RANGE", "negative offset");
        }
        long limit = Math.max(1, Math.min(req.limit, 1024 * 1024)); // tree asks 32..512 KB
        long end = req.offset + limit - 1;
        byte[] data = RestGateway.getInstance(account).fileDownloadRange(backendId, req.offset, end);
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
        long thumbBackendId = 0;
        if (!alreadyBackend) {
            // finalize is the single metadata injection point; the tree's real
            // part count patches any streaming-extended estimate (v1.1b). T14:
            // declare the exact streamed byte total (corruption cross-check).
            long declaredBytes = bridge.uploadedBytesFor(treeUploadId);
            bridge.finalizeUpload(treeUploadId, Math.max(1, parts), mime, name, width, height, duration, declaredBytes);
            if (thumbTreeId != 0) {
                try {
                    JSONObject thumbEnvelope = bridge.finalizeUpload(thumbTreeId, 1, "image/jpeg", null, null, null, null, bridge.uploadedBytesFor(thumbTreeId));
                    JSONObject thumbJson = thumbEnvelope != null ? thumbEnvelope.optJSONObject("file") : null;
                    thumbBackendId = thumbJson != null ? thumbJson.optLong("file_id", 0) : 0;
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

        long selfId = UserConfig.getInstance(account).clientUserId;
        PeerRef peer = resolvePeer(account, req.peer);
        if (peer == null) {
            throw new XoApiException(400, "PEER_ID_INVALID", "unaddressable peer for send-media");
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
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("RestDispatcher: send-media chat=" + chatId + " file=" + mediaFileId + " caption=" + caption.length());
        }

        JSONObject sent = RestGateway.getInstance(account).sendMedia(chatId, mediaFileId, caption, replyToId, thumbBackendId);
        JSONObject msgJson = sent.optJSONObject("message");
        if (msgJson == null) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "send response lacks the message object");
        }
        TLRPC.TL_message message;
        try {
            message = TlJsonMapper.parseMessage(msgJson, peer.dialogId, peer.isGroup, peer.userId, selfId);
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
     * (channels, self chat, empty).
     */
    private static PeerRef resolvePeer(int account, TLRPC.InputPeer peer) {
        if (peer instanceof TLRPC.TL_inputPeerUser) {
            long userId = ((TLRPC.TL_inputPeerUser) peer).user_id;
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

package org.telegram.tgnet.rest;

import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
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
 *   <li>readHistory/deleteMessages → {@link TLRPC.TL_messages_affectedHistory}
 *       — deliberately NOT {@code TL_messages_affectedMessages}, because the
 *       callers branch on that class to apply pts params
 *       (MessagesController:12906); our pts is always 0, so the safest
 *       response is one the call sites ignore by type;</li>
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
        IO_QUEUE.execute(() -> runRoute(account, route, object, onComplete, onCompleteTimestamp));
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
        // Xo (T9): reply probe — the server round-trip is verified PASS
        // (scripts/t8_reply_test.py), so this visible probe tells us in one test
        // whether the funnel ever receives the reply, and with which id.
        // TEMPORARY — remove once the reply bug is root-caused.
        int probeReplyToId = 0;
        String probeReplyClass = null;
        if (req.reply_to instanceof TLRPC.TL_inputReplyToMessage) {
            probeReplyToId = ((TLRPC.TL_inputReplyToMessage) req.reply_to).reply_to_msg_id;
            probeReplyClass = "TL_inputReplyToMessage";
        } else if (req.reply_to != null) {
            probeReplyClass = req.reply_to.getClass().getSimpleName();
        }
        final int fProbeId = probeReplyToId;
        final String fProbeClass = probeReplyClass;
        AndroidUtilities.runOnUIThread(() -> {
            try {
                if (fProbeId > 0) {
                    Toast.makeText(ApplicationLoader.applicationContext, "Xo: reply to #" + fProbeId, Toast.LENGTH_SHORT).show();
                } else if (fProbeClass != null) {
                    Toast.makeText(ApplicationLoader.applicationContext, "Xo: reply unextracted (" + fProbeClass + ")", Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
        FileLog.d("RestDispatcher: send reply_to=" + probeReplyToId + " class=" + fProbeClass);
        // defensive: a local/negative reply id must never reach the server (the
        // backend rejects reply_to_id < 1 with a 400 that fails the WHOLE send)
        int replyToId = probeReplyToId > 0 ? probeReplyToId : 0;

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
        // affectedHistory (NOT affectedMessages) so the call sites that apply
        // pts params on TL_messages_affectedMessages ignore the response
        return new TLRPC.TL_messages_affectedHistory();
    }

    private static TLObject handleDelete(int account, TLRPC.TL_messages_deleteMessages req) {
        if (req.id.isEmpty()) {
            return new TLRPC.TL_messages_affectedHistory();
        }
        if (!req.revoke) {
            // delete-for-me only: local deletion already happened, and the v1
            // endpoint is delete-for-everyone — calling it would exceed intent
            return new TLRPC.TL_messages_affectedHistory();
        }
        RestChatIndex index = RestChatIndex.getInstance(account);
        long chatId = index.chatIdForAnyMessage(req.id);
        if (chatId == 0) {
            FileLog.w("RestDispatcher: revoke for unseen message ids, nothing to delete server-side");
            return new TLRPC.TL_messages_affectedHistory();
        }
        int[] ids = new int[req.id.size()];
        for (int a = 0; a < ids.length; a++) {
            ids[a] = req.id.get(a);
        }
        RestGateway.getInstance(account).delete(chatId, ids);
        return new TLRPC.TL_messages_affectedHistory();
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
     * One /users/get.php call per page for sender ids missing from the
     * memory cache — keeps group history renderable (the history endpoint
     * itself carries no user objects) without N round trips.
     */
    private static ArrayList<TLRPC.TL_user> hydrateSenders(int account, List<? extends TLRPC.Message> messages) {
        ArrayList<TLRPC.TL_user> users = new ArrayList<>();
        ArrayList<Long> missing = null;
        for (int a = 0; a < messages.size(); a++) {
            TLRPC.Message message = messages.get(a);
            long senderId = message.from_id instanceof TLRPC.TL_peerUser
                    ? ((TLRPC.TL_peerUser) message.from_id).user_id : 0;
            if (senderId == 0) {
                continue;
            }
            TLRPC.User cached = MessagesController.getInstance(account).getUser(senderId);
            if (cached instanceof TLRPC.TL_user) {
                users.add((TLRPC.TL_user) cached);
            } else {
                if (missing == null) {
                    missing = new ArrayList<>();
                }
                missing.add(senderId);
            }
        }
        if (missing != null && !missing.isEmpty()) {
            long[] ids = new long[missing.size()];
            for (int a = 0; a < ids.length; a++) {
                ids[a] = missing.get(a);
            }
            try {
                users.addAll(TlJsonMapper.parseUsers(RestGateway.getInstance(account).usersGet(ids)));
            } catch (Exception e) {
                FileLog.e("RestDispatcher: sender hydration failed, dialog may render raw ids", e);
            }
        }
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
        return tlError(e.httpStatus == 0 ? 400 : e.httpStatus, e.errorCode);
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

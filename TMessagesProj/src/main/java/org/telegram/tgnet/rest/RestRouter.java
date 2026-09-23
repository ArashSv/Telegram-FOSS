package org.telegram.tgnet.rest;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.HashMap;

/**
 * Default-deny route table (plan critical correction #1): every TL method is
 * DENIED unless it is explicitly allowlisted here. The table grows task by
 * task — T4 wired auth into LoginActivity, T5 serves messages/chats/users
 * behind the ConnectionsManager funnel, T6 serves the sync state stubs.
 * Routes are never enabled before the code that serves them exists.
 *
 * <p>Keys are the concrete request classes (TLObject carries no instance
 * constructor field in this tree — the constructor int is a static constant
 * per class), so the lookup is an exact-class map read, no reflection. The
 * constructor ints below were read from TLRPC.java in this tree (10.14.3)
 * and are kept as provenance: TL_auth_sendCode = 0xa677244f,
 * TL_auth_signUp = 0x80eee427, TL_auth_signIn = 0x8d52a951,
 * TL_messages_getDialogs = 0xa0f4cb4f, TL_messages_getHistory = 0x4423e6c5,
 * TL_messages_sendMessage = 0x983f9745, TL_messages_readHistory = 0xe306d3a1,
 * TL_messages_deleteMessages = 0xe58e95d2, TL_users_getUsers = 0xd91a548,
 * TL_updates_getState = 0xedd4882a, TL_updates_getDifference = 0x25939651.
 *
 * <p><b>Response-class contract</b> (T12 lesson — the single most important
 * invariant of this package): the tree consumes routed responses with
 * hard casts, and a wrong class is a ClassCastException on the stageQueue —
 * for deleteMessages it even became a launch crash loop, because the request
 * is persisted as a pending task that only that callback clears. Every route
 * below therefore names its consuming call site; a response class may only
 * change together with that call site:
 * <ul>
 *   <li>ROUTE_DIALOGS → TL_messages_dialogs; consumer MessagesController
 *       getDialogs callback (hard cast, ~:10881);</li>
 *   <li>ROUTE_HISTORY → TL_messages_messages; consumer MessagesController
 *       getHistory callback (hard cast, ~:10196);</li>
 *   <li>ROUTE_SEND → TL_updates with one TL_updateNewMessage; consumer
 *       SendMessagesHelper (~:6420, hard cast);</li>
 *   <li>ROUTE_READ → TL_messages_affectedHistory (schema type of
 *       messages.readHistory); consumer MessagesController.completeReadTask
 *       (~:12920, instanceof-guarded — ignores it);</li>
 *   <li>ROUTE_DELETE → TL_messages_affectedMessages (schema type of
 *       messages.deleteMessages); consumer MessagesController.deleteMessages
 *       callback (~:8262, HARD cast + pending-task clear);</li>
 *   <li>ROUTE_USERS_GET → Vector of TL_user; consumer MessagesController
 *       users_getUsers callback (hard cast);</li>
 *   <li>ROUTE_STATE / ROUTE_DIFFERENCE → zeroed stubs (pts machinery stays
 *       pinned; getState cannot be error-denied — loadCurrentState retries
 *       forever on non-401 errors).</li>
 *   <li>ROUTE_FILE_PART / ROUTE_FILE_PART_BIG → TL_boolTrue; consumer
 *       FileUploadOperation callback (~:573) tests
 *       {@code response instanceof TL_boolTrue} and treats EVERYTHING else
 *       (null response with error, or any other class) as upload failure —
 *       so the answer class is non-negotiable (T8b).</li>
 *   <li>ROUTE_FILE_GET → TL_upload_file{bytes: NativeByteBuffer}; consumer
 *       FileLoadOperation (~:2542) instanceof-tests TL_upload_file first,
 *       TL_upload_webFile second, and HARD-CASTS the remaining branch to
 *       TL_upload_cdnFile — answering anything else is a ClassCastException
 *       (T8b; bytes.buffer must be positioned at 0, limit = byte count).</li>
 *   <li>ROUTE_SEND_MEDIA → TL_updates with one TL_updateNewMessage; the
 *       consumer is the SAME unified send path as ROUTE_SEND
 *       (SendMessagesHelper ~:6417: {@code response instanceof Updates} →
 *       extract TL_updateNewMessage) (T8c).</li>
 * </ul>
 */
public final class RestRouter {

    /** Returned for every TL method not (yet) routed: the caller must reject it. */
    public static final int ROUTE_NONE = -1;

    public static final int ROUTE_AUTH_SEND_CODE = 1;
    public static final int ROUTE_AUTH_VERIFY = 2; // signIn and signUp share one REST endpoint
    public static final int ROUTE_DIALOGS = 3;     // TL_messages_getDialogs -> GET /chats/list.php
    public static final int ROUTE_HISTORY = 4;     // TL_messages_getHistory -> GET /messages/history.php
    public static final int ROUTE_SEND = 5;        // TL_messages_sendMessage -> POST /messages/send.php
    public static final int ROUTE_READ = 6;        // TL_messages_readHistory -> POST /messages/read.php
    public static final int ROUTE_DELETE = 7;      // TL_messages_deleteMessages (revoke only) -> POST /messages/delete.php
    public static final int ROUTE_USERS_GET = 8;   // TL_users_getUsers -> GET /users/get.php
    public static final int ROUTE_STATE = 9;       // TL_updates_getState -> stub (sync cursor lives in UpdatePoller)
    public static final int ROUTE_DIFFERENCE = 10; // TL_updates_getDifference -> stub (UpdatePoller is the updates source)
    public static final int ROUTE_FILE_GET = 11;        // TL_upload_getFile -> GET /files/download.php (Range)
    public static final int ROUTE_FILE_PART = 12;       // TL_upload_saveFilePart -> lazy init + POST /files/chunk.php
    public static final int ROUTE_FILE_PART_BIG = 13;   // TL_upload_saveBigFilePart -> same (parts > 1 MB files)
    public static final int ROUTE_SEND_MEDIA = 14;      // TL_messages_sendMedia -> finalize + POST /messages/send.php

    // constructor ints (TLRPC.java, this tree): TL_upload_getFile = 0xbe5335be,
    // TL_upload_saveFilePart = 0xb304a621, TL_upload_saveBigFilePart = 0xde7b673d,
    // TL_messages_sendMedia = 0x7852834e

    private static final HashMap<Class<?>, Integer> ROUTES = new HashMap<>();

    static {
        ROUTES.put(TLRPC.TL_auth_sendCode.class, ROUTE_AUTH_SEND_CODE);
        ROUTES.put(TLRPC.TL_auth_signIn.class, ROUTE_AUTH_VERIFY);
        ROUTES.put(TLRPC.TL_auth_signUp.class, ROUTE_AUTH_VERIFY);
        ROUTES.put(TLRPC.TL_messages_getDialogs.class, ROUTE_DIALOGS);
        ROUTES.put(TLRPC.TL_messages_getHistory.class, ROUTE_HISTORY);
        ROUTES.put(TLRPC.TL_messages_sendMessage.class, ROUTE_SEND);
        ROUTES.put(TLRPC.TL_messages_readHistory.class, ROUTE_READ);
        ROUTES.put(TLRPC.TL_messages_deleteMessages.class, ROUTE_DELETE);
        ROUTES.put(TLRPC.TL_users_getUsers.class, ROUTE_USERS_GET);
        ROUTES.put(TLRPC.TL_updates_getState.class, ROUTE_STATE);
        ROUTES.put(TLRPC.TL_updates_getDifference.class, ROUTE_DIFFERENCE);
        ROUTES.put(TLRPC.TL_upload_getFile.class, ROUTE_FILE_GET);
        ROUTES.put(TLRPC.TL_upload_saveFilePart.class, ROUTE_FILE_PART);
        ROUTES.put(TLRPC.TL_upload_saveBigFilePart.class, ROUTE_FILE_PART_BIG);
        ROUTES.put(TLRPC.TL_messages_sendMedia.class, ROUTE_SEND_MEDIA);
    }

    private RestRouter() {
    }

    /** @return {@link #ROUTE_NONE} (= deny) for anything not yet routed. */
    public static int routeFor(TLObject object) {
        Integer route = ROUTES.get(object.getClass());
        return route == null ? ROUTE_NONE : route;
    }
}

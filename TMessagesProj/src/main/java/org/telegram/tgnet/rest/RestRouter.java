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
 * TL_messages_sendMessage = 0x983f9745, TL_messages_readHistory = 0xe306d3a,
 * TL_messages_deleteMessages = 0xe58e95d2, TL_users_getUsers = 0xd91a548,
 * TL_updates_getState = 0xedd4882a, TL_updates_getDifference = 0x25939651.
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
    }

    private RestRouter() {
    }

    /** @return {@link #ROUTE_NONE} (= deny) for anything not yet routed. */
    public static int routeFor(TLObject object) {
        Integer route = ROUTES.get(object.getClass());
        return route == null ? ROUTE_NONE : route;
    }
}

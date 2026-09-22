package org.telegram.tgnet.rest;

import android.util.SparseIntArray;

/**
 * T3 skeleton of the default-deny route table (plan critical correction #1):
 * every TL method is DENIED unless it is explicitly allowlisted here. The table
 * grows task by task — T4 wires auth into LoginActivity, T5 adds
 * messages/chats/users routes behind the ConnectionsManager facade, T6 adds
 * sync. Routes are never enabled before the code that serves them exists.
 *
 * <p>Constructor ints were read from TLRPC.java in this tree (10.14.3):
 * TL_auth_sendCode = 0xa677244f, TL_auth_signUp = 0x80eee427,
 * TL_auth_signIn = 0x8d52a951.
 */
public final class RestRouter {

    /** Returned for every TL method not (yet) routed: the caller must reject it. */
    public static final int ROUTE_NONE = -1;

    public static final int ROUTE_AUTH_SEND_CODE = 1;
    public static final int ROUTE_AUTH_VERIFY = 2; // signIn and signUp share one REST endpoint

    private static final SparseIntArray ROUTES = new SparseIntArray();

    static {
        ROUTES.put(0xa677244f, ROUTE_AUTH_SEND_CODE); // TLRPC.TL_auth_sendCode
        ROUTES.put(0x8d52a951, ROUTE_AUTH_VERIFY);    // TLRPC.TL_auth_signIn
        ROUTES.put(0x80eee427, ROUTE_AUTH_VERIFY);    // TLRPC.TL_auth_signUp
    }

    private RestRouter() {
    }

    /** @return {@link #ROUTE_NONE} (= deny) for anything not yet routed. */
    public static int routeFor(int tlConstructor) {
        return ROUTES.get(tlConstructor, ROUTE_NONE);
    }
}

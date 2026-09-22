package org.telegram.tgnet.rest;

/**
 * T3: API-level failure carrying a backend envelope code (docs/API.md v1 §2)
 * or a client-side integrity code when the response itself is unusable.
 * Network-level failures throw {@link XoTransportException} instead.
 *
 * <p>Runtime (unchecked) by design: the caller is always doing network I/O on a
 * worker thread and must catch anyway; forcing {@code throws} on every call site
 * of a facade that will be reached through request dispatchers (T5) adds noise,
 * not safety. Lifecycle codes:
 * <ul>
 *   <li>{@link #TOKEN_EXPIRED} — access token expired; the gateway refreshes
 *       single-flight and retries once before surfacing this.</li>
 *   <li>{@link #SESSION_INVALID} — token family revoked/absent; the only
 *       recovery is a fresh login (LoginActivity, T4). The session-invalid
 *       listener has been notified before this is thrown.</li>
 * </ul>
 */
public class XoApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Backend envelope codes this client makes lifecycle decisions on. */
    public static final String TOKEN_EXPIRED = "TOKEN_EXPIRED";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";

    /** Client-side codes: the body was not a usable v1 envelope. */
    public static final String MALFORMED_RESPONSE = "MALFORMED_RESPONSE";
    public static final String SESSION_INVALID = "SESSION_INVALID";

    public final int httpStatus;
    public final String errorCode;

    public XoApiException(int httpStatus, String errorCode, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    /** @return true when the only recovery is a fresh login. */
    public boolean isSessionInvalid() {
        return SESSION_INVALID.equals(errorCode);
    }

    /**
     * T7b VPN hardening: true ONLY when the 401 carried a genuine backend
     * envelope lifecycle code (docs/API.md v1 §2). A garbled 401 page from an
     * intermediary (WAF / proxy / captive portal — typical on VPN paths)
     * parses as MALFORMED_RESPONSE with http 401 and must never be treated as
     * proof that the token family is dead.
     */
    public boolean isGenuineServerRejection() {
        return TOKEN_EXPIRED.equals(errorCode) || UNAUTHORIZED.equals(errorCode);
    }

    @Override
    public String toString() {
        return "XoApiException{http=" + httpStatus + ", code=" + errorCode + ", msg=" + getMessage() + "}";
    }
}

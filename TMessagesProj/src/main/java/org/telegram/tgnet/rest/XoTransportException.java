package org.telegram.tgnet.rest;

/**
 * T3: network-level failure (DNS, TLS, timeout, truncated body) — the request
 * never produced an HTTP response, so there is no envelope and no backend code.
 * Retriable at the caller's discretion; the gateway never auto-retries these
 * (retry policy belongs to the feature layer, not the transport).
 */
public class XoTransportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public XoTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}

package org.telegram.tgnet.rest;

/**
 * T3: network-level failure (DNS, TLS, timeout, truncated body) — the request
 * never produced an HTTP response, so there is no envelope and no backend code.
 * Retriable at the caller's discretion; the gateway never auto-retries these
 * (retry policy belongs to the feature layer, not the transport).
 *
 * <p>Two shapes exist by design: (a) a wrapped I/O failure (the two-arg form
 * carries the underlying cause) and (b) an IN-FLIGHT integrity violation with
 * no underlying cause — the transfer's own bytes betrayed the contract
 * (declared-length mismatch on a download range, corrupted framing): the
 * single-arg form covers those, mirroring the standard
 * {@code Exception(String)} / {@code Exception(String, Throwable)} pair.
 */
public class XoTransportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public XoTransportException(String message) {
        super(message);
    }

    public XoTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}

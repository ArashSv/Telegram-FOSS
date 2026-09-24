package org.telegram.tgnet.rest;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

/**
 * T31: the ONE owner of media byte transfer over the REST backend. The tree's
 * FileLoadOperation asks for exact ranges (TL_upload_getFile → the dispatcher);
 * this class answers them with BYTE-FAITHFUL, SELF-VERIFIED windows and nothing
 * else — a short/mangled body can never again reach the tree's assembly.
 *
 * <p>Why sub-windows: the host intermittently CUTS long HTTP responses
 * mid-body (live-probed three separate times: a 512 KB 206 short, a 3 MB 200
 * cut at ~360 KB, a 2.69 MB 200 delivered 2.62 MB — always with a healthy
 * status line). Large single requests are the fragile regime. Every window
 * here is ≤256 KB, on a FRESH connection, verified on THREE axes before it is
 * returned: (1) declared length — the window the caller asked for, (2) the
 * backend's per-window sha256 echo (X-Range-Sha256) when present, (3) the
 * transport Content-Length promise inside XoHttp. Any mismatch → one
 * idempotent re-request on a fresh connection → then a LOUD typed failure.
 *
 * <p>The caller additionally verifies the FINISHED file against the server's
 * whole-file {size, sha256} attestation (FileLoadOperation REST integrity
 * gate); attestations resolve through {@link RestFileBridge#attest} — memory,
 * persistent store, or a one-time metadata cold-fetch, never dependent on how
 * the message arrived or how old the process is.
 */
public final class XoFileTransport {

    /** Sub-window size — comfortably below every observed host truncation regime. */
    private static final int SUB_WINDOW = 256 * 1024;
    /** Attempts per sub-window: 1 + 2 idempotent retries on a fresh connection. */
    private static final int WINDOW_ATTEMPTS = 3;
    private static final int WINDOW_BACKOFF_MS = 250;

    private static final XoFileTransport[] instances = new XoFileTransport[org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT];

    public static XoFileTransport getInstance(int account) {
        if (account < 0 || account >= org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT) {
            account = 0;
        }
        XoFileTransport transport;
        synchronized (XoFileTransport.class) {
            transport = instances[account];
            if (transport == null) {
                transport = new XoFileTransport(account);
                instances[account] = transport;
            }
        }
        return transport;
    }

    private final int account;

    private XoFileTransport(int account) {
        this.account = account;
    }

    /**
     * Delivers EXACTLY {@code endInclusive - offsetInclusive + 1} bytes of the
     * backend file, assembled from verified sub-windows. Throws
     * {@link XoApiException}(INVALID_RANGE) past the attested EOF and
     * {@link XoTransportException} when a window cannot be delivered intact
     * after its retries — callers treat that as the transport failure it is.
     */
    public byte[] downloadRange(long backendFileId, long offsetInclusive, long endInclusive) {
        if (backendFileId <= 0) {
            throw new XoApiException(400, "FILE_ID_INVALID", "unsupported file id");
        }
        if (offsetInclusive < 0 || endInclusive < offsetInclusive) {
            throw new XoApiException(400, "INVALID_RANGE", "negative or inverted range");
        }
        // Attestation is unconditional: memory → persistent prefs → one
        // blocking metadata fetch. Declared-length enforcement then holds for
        // EVERY download, not only for messages parsed this process.
        long declaredSize = RestFileBridge.attestedSize(account, backendFileId);
        if (declaredSize > 0) {
            if (offsetInclusive >= declaredSize) {
                // upstream's own error name for past-EOF getFile asks — the tree's
                // OFFSET_INVALID handler expects exactly this text to close an
                // unknown-size download at the attested EOF (with the fork's
                // do-not-finish-short guard).
                throw new XoApiException(416, "OFFSET_INVALID",
                        "offset " + offsetInclusive + " beyond attested file size " + declaredSize);
            }
            if (endInclusive >= declaredSize) {
                endInclusive = declaredSize - 1;
            }
        }

        long total = endInclusive - offsetInclusive + 1;
        if (total > Integer.MAX_VALUE) {
            throw new XoApiException(400, "INVALID_RANGE", "range exceeds one request's contract");
        }
        byte[] out = new byte[(int) total];
        long windowStart = offsetInclusive;
        while (windowStart <= endInclusive) {
            long windowEnd = Math.min(windowStart + SUB_WINDOW - 1L, endInclusive);
            int windowLen = (int) (windowEnd - windowStart + 1);
            byte[] window = fetchWindow(backendFileId, windowStart, windowEnd, windowLen);
            System.arraycopy(window, 0, out, (int) (windowStart - offsetInclusive), windowLen);
            windowStart = windowEnd + 1;
        }
        return out;
    }

    private byte[] fetchWindow(long backendFileId, long start, long end, int len) {
        java.security.MessageDigest digest = null;
        XoTransportException last = null;
        for (int attempt = 1; attempt <= WINDOW_ATTEMPTS; attempt++) {
            try {
                XoHttp.BinaryResponse response = RestGateway.getInstance(account)
                        .binaryWindow(backendFileId, start, end);
                if (response.code == 200 || response.code == 206) {
                    if (response.data.length != len) {
                        throw new XoTransportException("window " + start + "-" + end
                                + " delivered " + response.data.length + "B, declared " + len + "B");
                    }
                    if (response.rangeSha256 != null) {
                        if (digest == null) {
                            digest = java.security.MessageDigest.getInstance("SHA-256");
                        }
                        digest.reset();
                        digest.update(response.data, 0, response.data.length);
                        String actual = Utilities.bytesToHex(digest.digest());
                        if (!response.rangeSha256.equalsIgnoreCase(actual)) {
                            throw new XoTransportException("window " + start + "-" + end
                                    + " content mangled (server sha mismatch)");
                        }
                    }
                    return response.data;
                }
                if (response.code == 416) {
                    throw new XoApiException(416, "INVALID_RANGE", "window beyond EOF");
                }
                throw new XoApiException(response.code, response.code == 404 ? "NOT_FOUND" : "SERVER_ERROR",
                        "download http " + response.code);
            } catch (XoApiException e) {
                throw e; // the server said no — resending cannot change its mind
            } catch (Exception e) {
                last = e instanceof XoTransportException
                        ? (XoTransportException) e
                        : new XoTransportException("window " + start + "-" + end + " failed: " + e.getMessage(), e);
                FileLog.w("XoFileTransport: window " + start + "-" + end + " attempt " + attempt
                        + " failed (" + e.getMessage() + "), fresh connection retry follows");
            }
            if (attempt < WINDOW_ATTEMPTS) {
                try {
                    Thread.sleep(WINDOW_BACKOFF_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new XoTransportException("window retry interrupted", ie);
                }
            }
        }
        throw last != null ? last : new XoTransportException("window failed without a cause");
    }
}

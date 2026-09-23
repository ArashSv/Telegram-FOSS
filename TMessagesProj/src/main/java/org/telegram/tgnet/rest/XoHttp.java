package org.telegram.tgnet.rest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * T3: minimal HTTPS transport over {@link HttpURLConnection}.
 *
 * <p>Transport decision (critical pass, recorded in worklog): the okhttp 3.x line
 * is EOL and okhttp 4.x would drag in kotlin-stdlib and require API 21 against the
 * afat flavor's minSdk 19. Every traffic pattern this client has on its roadmap
 * (JSON request/response, short-poll, 5 MB chunked uploads with
 * setFixedLengthStreamingMode, Range downloads) is fully serviceable on the
 * platform client, with zero gradle/proguard churn. The gateway wraps this class,
 * so a future transport swap stays a one-file change.
 *
 * <p>Details that matter:
 * <ul>
 *   <li>HTTP >= 400 is <b>not</b> an exception here — the error body is read and
 *       returned, because the backend envelope (docs/API.md v1) carries the real
 *       error code. Only network-level failures throw {@link IOException}.</li>
 *   <li>Never {@code disconnect()}: once the body is fully read, the connection
 *       returns to the platform keep-alive cache (idle eviction is automatic).
 *       Socket reuse across polls is exactly what the /sync short-poll (T6) wants.</li>
 *   <li>No explicit {@code Accept-Encoding}: HttpURLConnection on Android adds
 *       gzip transparently and decompresses it for us; setting the header by
 *       hand would disable that.</li>
 * </ul>
 *
 * <p>Threading: blocking I/O — worker threads only.
 */
final class XoHttp {

    private static final int CONNECT_TIMEOUT_MS = 10 * 1000;
    private static final int READ_TIMEOUT_MS = 20 * 1000;
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024; // envelope cap; biggest v1 page (500 sync updates) fits with room
    /** Cap for one ranged file download. The tree asks for 32..512 KB chunks
     *  (FileLoadOperation currentDownloadChunkSize); 2 MB leaves headroom without
     *  ever buffering a whole file in memory. */
    private static final int MAX_BINARY_RESPONSE_BYTES = 2 * 1024 * 1024;

    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    /** Raw-bytes answer of {@link #binaryRequest} — T8 file routes. */
    static final class BinaryResponse {
        final int code;
        final byte[] data;

        BinaryResponse(int code, byte[] data) {
            this.code = code;
            this.data = data;
        }
    }

    private XoHttp() {
    }

    /**
     * Blocking request. Returns the response for ANY http status (>= 400 included).
     *
     * @param jsonBody    JSON string, or null for a body-less request (GET)
     * @param bearerToken access token for {@code Authorization: Bearer}, or null
     * @throws IOException on network-level failures (DNS/TLS/timeout/truncation)
     */
    static Response request(String urlSpec, String method, String jsonBody, String bearerToken) throws IOException {
        return request(urlSpec, method, jsonBody != null ? jsonBody.getBytes("UTF-8") : null,
                "application/json; charset=utf-8", bearerToken);
    }

    /**
     * T8: raw-body variant — one implementation under both callers so the
     * envelope semantics stay identical. {@code contentType} selects the wire
     * format (JSON envelopes vs {@code application/octet-stream} file parts).
     * For a binary body the response is still a (JSON) envelope — the file
     * endpoints answer {"ok":true,...} for uploads too.
     */
    static Response request(String urlSpec, String method, byte[] payload,
                            String contentType, String bearerToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlSpec).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Accept", "application/json");
        if (bearerToken != null && bearerToken.length() > 0) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        boolean hasBody = payload != null;
        if (hasBody) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", contentType);
            conn.setFixedLengthStreamingMode(payload.length);
            conn.connect();
            OutputStream out = conn.getOutputStream();
            try {
                out.write(payload);
                out.flush();
            } finally {
                out.close();
            }
        } else {
            conn.connect();
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        return new Response(code, readAll(in, MAX_RESPONSE_BYTES));
    }

    /**
     * T8: ranged binary GET against the file download endpoint (HTTP Range /
     * 206 semantics; a whole-file 200 is equally accepted). The answer is raw
     * bytes — never an envelope — so errors travel through the http status and
     * the gateway maps them onto typed exceptions.
     *
     * @param rangeStart inclusive first byte, {@code rangeEnd} inclusive last
     *                   byte ({@code rangeStart > rangeEnd} means no Range
     *                   header = whole file)
     */
    static BinaryResponse binaryRequest(String urlSpec, String bearerToken, long rangeStart, long rangeEnd) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlSpec).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/octet-stream");
        if (bearerToken != null && bearerToken.length() > 0) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        if (rangeStart >= 0 && rangeStart <= rangeEnd) {
            conn.setRequestProperty("Range", "bytes=" + rangeStart + "-" + rangeEnd);
        }
        conn.connect();
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        return new BinaryResponse(code, readBytes(in));
    }

    private static byte[] readBytes(InputStream in) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > MAX_BINARY_RESPONSE_BYTES) {
                    throw new IOException("binary response exceeds " + MAX_BINARY_RESPONSE_BYTES + " bytes");
                }
            }
        } finally {
            try {
                in.close();
            } catch (Exception ignore) {
            }
        }
        return buffer.toByteArray();
    }

    private static String readAll(InputStream in, int maxBytes) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > maxBytes) {
                    throw new IOException("response exceeds " + maxBytes + " bytes");
                }
            }
        } finally {
            try {
                in.close();
            } catch (Exception ignore) {
            }
        }
        return new String(buffer.toByteArray(), "UTF-8");
    }
}

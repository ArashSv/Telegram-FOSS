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

    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
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
        HttpURLConnection conn = (HttpURLConnection) new URL(urlSpec).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Accept", "application/json");
        if (bearerToken != null && bearerToken.length() > 0) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        boolean hasBody = jsonBody != null;
        if (hasBody) {
            byte[] payload = jsonBody.getBytes("UTF-8");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
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
        return new Response(code, readAll(in));
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > MAX_RESPONSE_BYTES) {
                    throw new IOException("response exceeds " + MAX_RESPONSE_BYTES + " bytes");
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

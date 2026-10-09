package org.telegram.tgnet.rest.e2ee;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * T78 — the on-the-wire envelope for SECRET-chat messages ("XOSC1").
 *
 * <p>Transport is REUSED unchanged: a secret-chat message rides
 * {@code messages/send.php} / {@code messages/edit.php} with its
 * {@code content} string. The content is
 * <pre>
 *   XOSC1:&lt;base64( ver(1) ‖ ephPub(32) ‖ senderPub(32) ‖ nonce(12) ‖ GCM-ct )&gt;
 * </pre>
 * (the crypto framing lives in {@link XoSecretCrypto}).
 *
 * <p>The payload INSIDE is a compact UTF-8 JSON object — field names are
 * IDENTICAL to the previous XOE1 inner format so the media/thumb/GIF
 * machinery (MediaMeta, chunked AEAD, GIF 'an' bit, T76 sentinel) ports
 * over untouched:
 * <pre>
 *   text message:  {"t":"t","x":"سلام"}
 *   edit:          {"t":"e","x":"متن جدید"}
 *   media message: {"t":"m","fk":"b64(32B file key)","pl":12345,"cs":131072,
 *                   "mi":"video/mp4","na":"clip.mp4","w":1920,"h":1080,"du":42,
 *                   "cap":"کپشن","an":0|1,"th":1,"tk":"b64","tf":77}
 * </pre>
 */
public final class XoSecretEnvelope {

    public static final String PREFIX = "XOSC1:";

    /** Guava is on the classpath and works on the JVM test suite too. */
    private static final com.google.common.io.BaseEncoding B64 = com.google.common.io.BaseEncoding.base64();

    private XoSecretEnvelope() {
    }

    public static String b64Encode(byte[] data) {
        return B64.encode(data);
    }

    public static byte[] b64Decode(String s) {
        return B64.decode(s);
    }

    public static boolean isEnvelope(String content) {
        return content != null && content.startsWith(PREFIX) && content.length() > PREFIX.length() + 8;
    }

    /** Raw body bytes after the prefix, or null when malformed. */
    public static byte[] bodyOf(String envelope) {
        if (!isEnvelope(envelope)) {
            return null;
        }
        try {
            return b64Decode(envelope.substring(PREFIX.length()));
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ inner payload helpers

    public static String innerText(String text) throws Exception {
        JSONObject json = new JSONObject();
        json.put("t", "t");
        json.put("x", text);
        return json.toString();
    }

    public static String innerEdit(String text) throws Exception {
        JSONObject json = new JSONObject();
        json.put("t", "e");
        json.put("x", text);
        return json.toString();
    }

    /**
     * Media payload; all parameters may be null except kind, fk and cs.
     * The thumb (when encrypted) carries its OWN single-use key (tk) —
     * thumb ciphertext is chunk-0 format under that key.
     *
     * @param animated the Telegram-GIF flag (muted looping mp4) — ALWAYS
     *                 written (1 or 0, the T76 contract)
     */
    public static String innerMedia(byte[] fileKey, long plaintextLen, int chunkSize, String mime,
                                    String name, int width, int height, int duration,
                                    String caption, boolean thumbEncrypted,
                                    byte[] thumbKey, long thumbFileId, boolean animated) throws Exception {
        JSONObject json = new JSONObject();
        json.put("t", "m");
        json.put("fk", b64Encode(fileKey));
        json.put("pl", plaintextLen);
        json.put("cs", chunkSize);
        json.put("mi", mime == null ? "application/octet-stream" : mime);
        if (name != null) {
            json.put("na", name);
        }
        if (width > 0) {
            json.put("w", width);
        }
        if (height > 0) {
            json.put("h", height);
        }
        if (duration > 0) {
            json.put("du", duration);
        }
        if (caption != null && caption.length() > 0) {
            json.put("cap", caption);
        }
        json.put("an", animated ? 1 : 0);
        if (thumbEncrypted) {
            json.put("th", 1);
            if (thumbKey != null) {
                json.put("tk", b64Encode(thumbKey));
            }
            if (thumbFileId > 0) {
                json.put("tf", thumbFileId);
            }
        }
        return json.toString();
    }

    public static JSONObject parseInner(byte[] plaintext) throws Exception {
        return new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
    }

    public static JSONObject parseInner(String plaintext) throws Exception {
        return new JSONObject(plaintext);
    }
}

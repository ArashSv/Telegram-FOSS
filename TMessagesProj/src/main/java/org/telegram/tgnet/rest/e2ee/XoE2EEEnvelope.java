package org.telegram.tgnet.rest.e2ee;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * T71 — the on-the-wire envelope for E2EE 1:1 messages.
 *
 * <p>The server transport is REUSED unchanged: a private-chat message still
 * rides {@code messages/send.php} / {@code messages/edit.php} with its
 * {@code content} string. The ONLY difference is what that string contains:
 * for E2EE chats the dispatcher replaces the plaintext with
 * <pre>
 *   XOE1:&lt;base64( [1B wire-type] [libsignal CiphertextMessage] )&gt;
 * </pre>
 * where wire-type is the libsignal CiphertextMessage type (2 = WhisperMessage
 * = ratcheted normal message, 3 = PreKeySignalMessage = session bootstrap —
 * the X3DH header is INSIDE the prekey message, exactly as the protocol spec
 * designs it, so no extra "first message" plumbing is needed).
 *
 * <p>The payload INSIDE the Signal ciphertext is a compact UTF-8 JSON object:
 * <pre>
 *   text message:  {"t":"t","x":"سلام"}
 *   edit:          {"t":"e","x":"متن جدید"}
 *   media message: {"t":"m","fk":"b64(32B file key)","pl":12345,"cs":131072,
 *                   "mi":"video/mp4","na":"clip.mp4","w":1920,"h":1080,"du":42,
 *                   "cap":"کپشن","th":1}
 * </pre>
 * (th=1 → the linked thumb file is also encrypted under the same key.)
 *
 * <p>Everything privacy-sensitive about a media item (real name, real MIME,
 * dimensions, duration, caption, file key) lives ONLY inside this encrypted
 * payload — the server-side file row declares a bare "e2ee" kind and
 * application/octet-stream, so even file metadata is out of server reach.
 */
public final class XoE2EEEnvelope {

    /** v1 wire prefix. Any content starting with this IS an E2EE envelope. */
    public static final String PREFIX = "XOE1:";

    /** Guava is already on the classpath (gson/lottie era) and works on the JVM test suite too. */
    private static final com.google.common.io.BaseEncoding B64 = com.google.common.io.BaseEncoding.base64();

    private XoE2EEEnvelope() {
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

    /**
     * Wraps a libsignal ciphertext into the transport envelope string.
     *
     * @param wireType libsignal CiphertextMessage type byte (2 or 3)
     */
    public static String wrap(int wireType, byte[] ciphertextBody) {
        byte[] out = new byte[1 + ciphertextBody.length];
        out[0] = (byte) wireType;
        System.arraycopy(ciphertextBody, 0, out, 1, ciphertextBody.length);
        return PREFIX + b64Encode(out);
    }

    /** Unwraps the envelope; returns null when malformed. */
    public static Unwrapped unwrap(String envelope) {
        if (!isEnvelope(envelope)) {
            return null;
        }
        try {
            byte[] raw = b64Decode(envelope.substring(PREFIX.length()));
            if (raw.length < 2) {
                return null;
            }
            byte[] body = new byte[raw.length - 1];
            System.arraycopy(raw, 1, body, 0, body.length);
            return new Unwrapped(raw[0], body);
        } catch (Throwable t) {
            return null;
        }
    }

    public static final class Unwrapped {
        public final int wireType;
        public final byte[] body;

        Unwrapped(int wireType, byte[] body) {
            this.wireType = wireType;
            this.body = body;
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
     * @param animated T75 — explicit Telegram-GIF flag (muted looping mp4).
     *                 The receiver rebuilds the animated attribute from THIS
     *                 bit; the previous ".mp4 name" heuristic misclassified
     *                 real videos as GIFs.
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
        // T76: the animated bit is ALWAYS written (1 or 0). T75 tagged only
        // GIFs, so every non-GIF envelope shipped UNtagged — and the receiver's
        // legacy ".mp4 name" fallback then fired on nearly all real videos
        // (the "GIF type is broken" field report). A present "an" field is
        // authoritative on receive; its absence now genuinely means pre-T75.
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

    /** Pre-T75 shape (no animated flag) — kept for the test suite's existing callers. */
    public static String innerMedia(byte[] fileKey, long plaintextLen, int chunkSize, String mime,
                                    String name, int width, int height, int duration,
                                    String caption, boolean thumbEncrypted,
                                    byte[] thumbKey, long thumbFileId) throws Exception {
        return innerMedia(fileKey, plaintextLen, chunkSize, mime, name, width, height, duration,
                caption, thumbEncrypted, thumbKey, thumbFileId, false);
    }

    public static JSONObject parseInner(byte[] plaintext) throws Exception {
        return new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
    }
}

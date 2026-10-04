package org.telegram.tgnet.rest.e2ee;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * T71 — client-side media/file encryption for E2EE 1:1 chats.
 *
 * <p>Every media byte that travels to the server inside an E2EE 1:1 chat is
 * encrypted HERE, on the sending device, with a random 32-byte file key that
 * only ever reaches the recipient inside a Signal-Protocol-encrypted message
 * (never in plaintext, never to the server). The server therefore stores an
 * opaque blob it can never open — stealing the whole disk does not reveal a
 * single pixel (threat-model contract, docs/E2EE.md §media).
 *
 * <p>Format (upload-part aligned — the upload funnel already streams 128 KB
 * parts, so each part IS one AEAD chunk and no re-buffering is needed):
 * <pre>
 *   part i plaintext  --AES-256-GCM(key, nonce_i, aad_i)-->  part i ciphertext (+16B tag)
 *   nonce_i = HKDF-SHA256(fileKey, salt=0^32, info="XOEEM1n" || uint32(chunkIndex))[0..12]
 *   aad_i   = "XOEEM1|c" || uint32(chunkIndex)
 * </pre>
 * Nonces are DERIVED from the (single-use) file key instead of stored: a
 * two-gigabyte upload would otherwise carry ~2000 nonces inside the message
 * envelope. Because a file key is generated fresh per upload and never
 * reused, distinct chunks always encrypt under distinct nonces, and two
 * files never share a key.
 *
 * <p>Thumbnails ride the SAME key under a separate domain string so a thumb
 * can never be spliced in as a body chunk or vice versa:
 * <pre>
 *   thumb: AES-256-GCM(key, random 12B nonce, aad="XOEEM1|t") -> nonce || ct
 * </pre>
 *
 * <p>All primitives are the platform JCA defaults (AES/GCM, HMAC-SHA256).
 * Nothing here invents a cipher; the only hand-written glue is HKDF
 * (RFC 5869) for nonce/AAD derivation, which is test-vector-checked in the
 * JVM suite.
 */
public final class XoE2EEMedia {

    public static final int FILE_KEY_LEN = 32;
    public static final int GCM_TAG_BITS = 128;
    public static final int GCM_NONCE_LEN = 12;

    /** AAD prefixes (domain separation: body chunk vs thumbnail). */
    private static final byte[] AAD_CHUNK = {'X', 'O', 'E', 'E', 'M', '1', '|', 'c'};
    private static final byte[] AAD_THUMB = {'X', 'O', 'E', 'E', 'M', '1', '|', 't'};
    private static final byte[] HKDF_INFO_CHUNK = {'X', 'O', 'E', 'E', 'M', '1', 'n'};

    private XoE2EEMedia() {
    }

    /** Fresh single-use file key. */
    public static byte[] newFileKey() {
        byte[] key = new byte[FILE_KEY_LEN];
        new java.security.SecureRandom().nextBytes(key);
        return key;
    }

    // ------------------------------------------------------------------ chunks

    /** Encrypts one upload part (any length; the last part is short). */
    public static byte[] encryptChunk(byte[] fileKey, int chunkIndex, byte[] plaintext) throws Exception {
        return gcm(fileKey, deriveChunkNonce(fileKey, chunkIndex), aad(AAD_CHUNK, chunkIndex), plaintext, true);
    }

    /** Decrypts one upload part. Throws on ANY tamper/truncate/reorder. */
    public static byte[] decryptChunk(byte[] fileKey, int chunkIndex, byte[] ciphertext) throws Exception {
        return gcm(fileKey, deriveChunkNonce(fileKey, chunkIndex), aad(AAD_CHUNK, chunkIndex), ciphertext, false);
    }

    // ------------------------------------------------------------------ whole blobs (thumbs)

    /** Small-blob (thumbnail) encryption: [12B nonce][ct+tag] under the "t" domain. */
    public static byte[] encryptThumb(byte[] fileKey, byte[] plaintext) throws Exception {
        byte[] nonce = new byte[GCM_NONCE_LEN];
        new java.security.SecureRandom().nextBytes(nonce);
        byte[] ct = gcm(fileKey, nonce, AAD_THUMB, plaintext, true);
        byte[] out = new byte[GCM_NONCE_LEN + ct.length];
        System.arraycopy(nonce, 0, out, 0, nonce.length);
        System.arraycopy(ct, 0, out, nonce.length, ct.length);
        return out;
    }

    public static byte[] decryptThumb(byte[] fileKey, byte[] blob) throws Exception {
        if (blob == null || blob.length < GCM_NONCE_LEN + 16) {
            throw new Exception("thumb blob too short");
        }
        byte[] nonce = new byte[GCM_NONCE_LEN];
        System.arraycopy(blob, 0, nonce, 0, nonce.length);
        byte[] ct = new byte[blob.length - GCM_NONCE_LEN];
        System.arraycopy(blob, GCM_NONCE_LEN, ct, 0, ct.length);
        return gcm(fileKey, nonce, AAD_THUMB, ct, false);
    }

    // ------------------------------------------------------------------ internals

    private static byte[] gcm(byte[] key, byte[] nonce, byte[] aad, byte[] input, boolean encrypt) throws Exception {
        SecretKeySpec spec = new SecretKeySpec(key, "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, spec, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(input);
    }

    /** nonce_i = HKDF-SHA256(fileKey, salt=0^32, info="XOEEM1n"||uint32(i)) truncated to 12 bytes. */
    private static byte[] deriveChunkNonce(byte[] fileKey, int chunkIndex) throws Exception {
        byte[] info = new byte[HKDF_INFO_CHUNK.length + 4];
        System.arraycopy(HKDF_INFO_CHUNK, 0, info, 0, HKDF_INFO_CHUNK.length);
        info[HKDF_INFO_CHUNK.length] = (byte) (chunkIndex >>> 24);
        info[HKDF_INFO_CHUNK.length + 1] = (byte) (chunkIndex >>> 16);
        info[HKDF_INFO_CHUNK.length + 2] = (byte) (chunkIndex >>> 8);
        info[HKDF_INFO_CHUNK.length + 3] = (byte) chunkIndex;
        byte[] okm = hkdfSha256(new byte[32], fileKey, info, 16);
        byte[] nonce = new byte[GCM_NONCE_LEN];
        System.arraycopy(okm, 0, nonce, 0, GCM_NONCE_LEN);
        return nonce;
    }

    private static byte[] aad(byte[] prefix, int chunkIndex) {
        byte[] out = new byte[prefix.length + 4];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        out[prefix.length] = (byte) (chunkIndex >>> 24);
        out[prefix.length + 1] = (byte) (chunkIndex >>> 16);
        out[prefix.length + 2] = (byte) (chunkIndex >>> 8);
        out[prefix.length + 3] = (byte) chunkIndex;
        return out;
    }

    // ------------------------------------------------------------------ HKDF (RFC 5869)

    /**
     * Minimal HKDF-SHA256 — extract-then-expand, exactly RFC 5869. The only
     * reason this exists is that the platform has no HKDF before API 33 and
     * the fork targets minSdk 19; it is verified against the RFC test
     * vectors in the JVM suite (XoE2EEMediaCryptoTest) and used ONLY for
     * nonce derivation, never for key agreement (that is libsignal's job).
     */
    static byte[] hkdfSha256(byte[] salt, byte[] ikm, byte[] info, int outLen) throws Exception {
        if (outLen > 255 * 32) {
            throw new IllegalArgumentException("hkdf output too long");
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        // extract
        mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        // expand
        byte[] okm = new byte[outLen];
        byte[] t = new byte[0];
        int pos = 0, counter = 1;
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        while (pos < outLen) {
            mac.update(t);
            mac.update(info);
            mac.update((byte) counter);
            t = mac.doFinal();
            int n = Math.min(t.length, outLen - pos);
            System.arraycopy(t, 0, okm, pos, n);
            pos += n;
            counter++;
        }
        return okm;
    }
}

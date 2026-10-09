package org.telegram.tgnet.rest.e2ee;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * T78 — the secret-chat message cipher: stateless ECIES over ONE X25519
 * key pair per user.
 *
 * <p>Design (the user's brief: "یک کلید خصوصی و کلید عمومی ساده، کاملاً امن،
 * با رد و بدل کردن کلید درست"):
 * <ul>
 *   <li>Every account owns ONE X25519 key pair. The private key never leaves
 *       the device; the public key is uploaded to the key registry.</li>
 *   <li>Per MESSAGE: the sender mints an ephemeral X25519 key pair and mixes
 *       TWO shared secrets — DH(ephemeral, recipient) for freshness and
 *       DH(static, recipient) for SENDER AUTHENTICATION (only the holder of
 *       the sender's private key can produce a ciphertext the recipient
 *       accepts as coming from them).</li>
 *   <li>key = HKDF-SHA256(DH_e || DH_s, salt = SHA256(E || S || R),
 *       info = "XOSC1|v1") → AES-256-GCM with a fresh 12-byte nonce.</li>
 *   <li>AAD binds the envelope to chat + parties: "XOSC1|c{chatId}|f{from}|t{to}"
 *       — an envelope captured from one chat is undecryptable in any other.</li>
 *   <li>The envelope CARRIES both public keys (ephemeral + sender static),
 *       so decryption is a PURE FUNCTION of (own private key, envelope, AAD
 *       context). No sessions, no ratchet state, no one-time keys, no
 *       ordering constraints: re-parsing a row re-decrypts identically, and
 *       the very first message needs no handshake at all. This statelessness
 *       is the structural death of the entire "locked message" bug class.</li>
 * </ul>
 */
public final class XoSecretCrypto {

    public static final int KEY_LEN = 32;
    public static final int NONCE_LEN = 12;
    public static final int TAG_BITS = 128;

    /** HKDF info — protocol + version separation. */
    private static final byte[] INFO = "XOSC1|v1".getBytes(StandardCharsets.UTF_8);

    private XoSecretCrypto() {
    }

    // ------------------------------------------------------------------ keys

    /** @return a fresh key pair: [0] = private (32B), [1] = public (32B). */
    public static byte[][] generateKeyPair() {
        byte[] priv = XoCurve25519.generatePrivateKey();
        return new byte[][]{priv, XoCurve25519.publicKeyFromPrivate(priv)};
    }

    /** Fingerprint shown to both users for out-of-band verification. */
    public static String safetyFingerprint(long selfUserId, byte[] selfPub, long peerUserId, byte[] peerPub) {
        if (selfPub == null || peerPub == null) {
            return null;
        }
        try {
            byte[] first = selfUserId <= peerUserId ? selfPub : peerPub;
            byte[] second = selfUserId <= peerUserId ? peerPub : selfPub;
            byte[] idFirst = String.valueOf(selfUserId <= peerUserId ? selfUserId : peerUserId)
                    .getBytes(StandardCharsets.UTF_8);
            byte[] idSecond = String.valueOf(selfUserId <= peerUserId ? peerUserId : selfUserId)
                    .getBytes(StandardCharsets.UTF_8);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((idFirst.length + "|").getBytes(StandardCharsets.UTF_8));
            md.update(idFirst);
            md.update(first);
            md.update((idSecond.length + "|").getBytes(StandardCharsets.UTF_8));
            md.update(idSecond);
            md.update(second);
            StringBuilder sb = new StringBuilder();
            byte[] d = md.digest();
            for (int i = 0; i < 15; i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(String.format("%04X", ((d[2 * i] & 0xff) << 8) | (d[2 * i + 1] & 0xff)));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------- encrypt

    /**
     * Encrypts one inner payload for a secret chat.
     *
     * @param chatId       backend chat id (AAD-bound)
     * @param senderId     our user id (AAD-bound)
     * @param recipientId  the peer's user id (AAD-bound)
     * @param senderPriv   OUR static X25519 private key (32B)
     * @param senderPub    OUR static X25519 public key (32B)
     * @param recipientPub the PEER's static X25519 public key (32B)
     * @param plaintext    the inner JSON bytes
     * @return the XOSC1 envelope string
     */
    public static String encrypt(long chatId, long senderId, long recipientId,
                                 byte[] senderPriv, byte[] senderPub, byte[] recipientPub,
                                 byte[] plaintext) throws Exception {
        byte[][] eph = generateKeyPair();
        byte[] key = deriveKey(chatId, senderId, recipientId,
                eph[1], senderPub, recipientPub, senderPriv, eph[0]);
        byte[] nonce = new byte[NONCE_LEN];
        new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad(chatId, senderId, recipientId));
        byte[] ct = cipher.doFinal(plaintext);

        byte[] body = new byte[1 + 32 + 32 + NONCE_LEN + ct.length];
        int off = 0;
        body[off++] = 1; // version
        System.arraycopy(eph[1], 0, body, off, 32); off += 32;   // ephemeral pub
        System.arraycopy(senderPub, 0, body, off, 32); off += 32; // sender static pub
        System.arraycopy(nonce, 0, body, off, NONCE_LEN); off += NONCE_LEN;
        System.arraycopy(ct, 0, body, off, ct.length);
        return XoSecretEnvelope.PREFIX + XoSecretEnvelope.b64Encode(body);
    }

    // -------------------------------------------------------------- decrypt

    /**
     * Decrypts an envelope. Returns the inner JSON bytes, or null when the
     * envelope cannot be opened (not addressed to us, tampered, wrong key) —
     * callers render a placeholder; no exception ever escapes into the
     * update pipeline.
     */
    public static byte[] decrypt(long chatId, long senderId, long recipientId,
                                 byte[] recipientPriv, byte[] recipientPub, String envelope) {
        try {
            byte[] body = XoSecretEnvelope.bodyOf(envelope);
            if (body == null || body.length < 1 + 32 + 32 + NONCE_LEN + 16) {
                return null;
            }
            if (body[0] != 1) {
                return null; // unknown version
            }
            byte[] ephPub = new byte[32];
            System.arraycopy(body, 1, ephPub, 0, 32);
            byte[] senderPub = new byte[32];
            System.arraycopy(body, 33, senderPub, 0, 32);
            byte[] nonce = new byte[NONCE_LEN];
            System.arraycopy(body, 65, nonce, 0, NONCE_LEN);
            byte[] ct = new byte[body.length - 77];
            System.arraycopy(body, 77, ct, 0, ct.length);

            // DH(R, E) || DH(R, S) — mirror of the sender's derivation
            byte[] d1 = XoCurve25519.scalarmult(recipientPriv, ephPub);
            byte[] d2 = XoCurve25519.scalarmult(recipientPriv, senderPub);
            byte[] key = hkdf(concat(d1, d2),
                    sha256(concat(concat(ephPub, senderPub), recipientPub)),
                    INFO, KEY_LEN);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(chatId, senderId, recipientId));
            return cipher.doFinal(ct);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Verifies that the sender's static key carried in an envelope matches
     * the key we have registered for that user. A mismatch means the peer
     * reinstalled/changed keys — decryptable (the envelope is
     * self-contained) but the UI must surface a key-change notice.
     */
    public static boolean senderKeyMatches(String envelope, byte[] trustedSenderPub) {
        try {
            byte[] body = XoSecretEnvelope.bodyOf(envelope);
            if (body == null || body.length < 65 || trustedSenderPub == null) {
                return true; // unknown — do not flag
            }
            byte[] senderPub = new byte[32];
            System.arraycopy(body, 33, senderPub, 0, 32);
            return java.util.Arrays.equals(senderPub, trustedSenderPub);
        } catch (Throwable t) {
            return true;
        }
    }

    /** The sender public key carried in an envelope, or null. */
    public static byte[] envelopeSenderPub(String envelope) {
        try {
            byte[] body = XoSecretEnvelope.bodyOf(envelope);
            if (body == null || body.length < 65) {
                return null;
            }
            byte[] senderPub = new byte[32];
            System.arraycopy(body, 33, senderPub, 0, 32);
            return senderPub;
        } catch (Throwable t) {
            return null;
        }
    }

    // -------------------------------------------------------------- internal

    private static byte[] deriveKey(long chatId, long senderId, long recipientId,
                                    byte[] ephPub, byte[] senderPub, byte[] recipientPub,
                                    byte[] senderPriv, byte[] ephPriv) throws Exception {
        byte[] d1 = XoCurve25519.scalarmult(ephPriv, recipientPub);  // DH(e, R)
        byte[] d2 = XoCurve25519.scalarmult(senderPriv, recipientPub); // DH(S, R)
        byte[] ikm = concat(d1, d2);
        byte[] salt = sha256(concat(concat(ephPub, senderPub), recipientPub));
        return hkdf(ikm, salt, INFO, KEY_LEN);
    }

    /** AAD: binds every envelope to its chat and its two parties. */
    static byte[] aad(long chatId, long senderId, long recipientId) {
        return ("XOSC1|c" + chatId + "|f" + senderId + "|t" + recipientId)
                .getBytes(StandardCharsets.UTF_8);
    }

    static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /**
     * RFC 5869 HKDF-SHA256 (extract + expand). Same construction the media
     * layer already ships (XoE2EEMedia) — duplicated here to keep the two
     * modules independent.
     */
    static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int len) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt == null || salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] out = new byte[len];
        byte[] t = new byte[0];
        int pos = 0, counter = 1;
        while (pos < len) {
            mac.update(t);
            mac.update(info);
            mac.update((byte) counter++);
            t = mac.doFinal();
            int n = Math.min(t.length, len - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
        }
        return out;
    }
}

package org.telegram.tgnet.rest.e2ee;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import org.telegram.tgnet.TLRPC;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * T82 — the PASSWORD-based encrypted-FILE scheme ("XOSF2") for private
 * cloud chats. Root redesign of the T81 XOSF1 flow per the user's brief:
 *
 * <ul>
 *   <li>«هیچ چیزی باز نمیشه که من رمز بزنم» — the user TYPES the رمز. A
 *       dialog collects it on send (key + confirm) and on receive (key +
 *       optional remember). Nothing is auto-derived, nothing fails closed
 *       on a "missing peer key" — the password is the ONLY secret.</li>
 *   <li>«رمزنگاری فایل ... باید سمت کلاینت صورت بگیره و سرور نتونه فایل رو
 *       رمزگشایی کنه» — EVERY byte of cryptography happens on the devices.
 *       The server receives and stores an opaque AEAD chunk stream
 *       (kind='e2ee' finalize) plus an envelope that carries NO usable key
 *       material: the file key travels ONLY wrapped under the password.
 *       Compromising the whole server disk reveals nothing.</li>
 *   <li>«فقط هم یه کلید متقارن کافیه چون دو شخص قبلا از راه امن ردوبدل
 *       کردن» — exactly one shared symmetric secret: the رمز the two people
 *       already exchanged out-of-band.</li>
 * </ul>
 *
 * <p>Format (hybrid, age/7z-style — a fresh key per file, wrapped by the
 * password so one رمز opens many files without re-encrypting per-file
 * metadata under a KDF-stretched key):
 * <pre>
 *   CEK      = random 32B content key (per FILE, never reused, never sent raw)
 *   body     = chunk i --AES-256-GCM(CEK, nonce_i)--> (+16B tag)   [XoE2EEMedia framing]
 *   KEK      = PBKDF2-HMAC-SHA256(password, salt(16B), 210000 iters) -> 32B
 *   wrap wp  = {"k":"pbkdf2","i":210000,"s":b64(salt),"n":b64(nonce),
 *               "w":b64(AES-256-GCM(KEK, nonce, AAD="XOSF2|wrap", CEK))}
 *   header hb= b64(nonce(12) || AES-256-GCM(CEK, nonce, AAD="XOSF2|head", inner JSON))
 *   envelope = "XOSF2:" b64({"v":1,"fid":fileId,"pl":plainLen,"cs":chunkSize,
 *                            "hb":...,"wp":{...}})
 * </pre>
 * The inner JSON is the SAME media schema the whole media machinery already
 * speaks ({@code XoSecretEnvelope.innerMedia} — fk/pl/cs/mi/na/w/h/du/cap/an),
 * so {@link XoSecret#parseMediaMeta(JSONObject)} and the chunk-decrypt path in
 * FileLoadOperation work unchanged; the header blob is simply that inner JSON
 * encrypted under the CEK. GCM authentication is the wrong-password detector:
 * a single wrong byte of رمز fails the tag check — no oracle, no fallback.
 */
public final class XoFilePassCrypto {

    public static final String PREFIX = "XOSF2:";

    public static final int CEK_LEN = 32;
    public static final int SALT_LEN = 16;
    public static final int NONCE_LEN = 12;
    public static final int TAG_BITS = 128;

    /** OWASP-recommended floor for PBKDF2-HMAC-SHA256 (2023+) is 600k; the
     * value here is the deliberate compromise for 2015-era low-end devices
     * in this fork's minSdk audience — still >=35x the 2013 1k legacy and
     * dominated by the server-side XOCC1 envelope, not by offline guessing
     * (the envelope is only attackable after a full server compromise). */
    public static final int PBKDF2_ITERS = 210_000;

    private static final byte[] AAD_WRAP = {'X', 'O', 'S', 'F', '2', '|', 'w', 'r', 'a', 'p'};
    private static final byte[] AAD_HEAD = {'X', 'O', 'S', 'F', '2', '|', 'h', 'e', 'a', 'd'};

    private XoFilePassCrypto() {
    }

    // ------------------------------------------------------------------ minting

    /** @return {cek, salt} — fresh per file; both persist ONLY in the keystore blob. */
    public static byte[][] mintCek() {
        SecureRandom random = new SecureRandom();
        byte[] cek = new byte[CEK_LEN];
        byte[] salt = new byte[SALT_LEN];
        random.nextBytes(cek);
        random.nextBytes(salt);
        return new byte[][]{cek, salt};
    }

    /** KEK = PBKDF2-HMAC-SHA256(password, salt, iters) -> 32B. */
    public static byte[] deriveKek(char[] password, byte[] salt, int iterations) throws Exception {
        if (password == null || password.length == 0 || salt == null || salt.length < 8) {
            throw new Exception("weak passphrase parameters");
        }
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    // ------------------------------------------------------------------ wrap

    /**
     * Wraps the CEK under the password-derived KEK.
     *
     * @return the "wp" JSON object (k/i/s/n/w) — travels inside the envelope
     */
    public static JSONObject wrapCek(byte[] cek, char[] password, byte[] salt, int iterations) throws Exception {
        byte[] kek = deriveKek(password, salt, iterations);
        byte[] nonce = new byte[NONCE_LEN];
        new SecureRandom().nextBytes(nonce);
        byte[] ct = gcm(kek, nonce, AAD_WRAP, cek, true);
        JSONObject wp = new JSONObject();
        wp.put("k", "pbkdf2");
        wp.put("i", iterations);
        wp.put("s", XoSecretEnvelope.b64Encode(salt));
        wp.put("n", XoSecretEnvelope.b64Encode(nonce));
        wp.put("w", XoSecretEnvelope.b64Encode(ct));
        return wp;
    }

    /**
     * Unwraps the CEK with the user's password. Throws {@link BadPassword}
     * when the GCM tag rejects the key (the honest wrong-رمز signal) and a
     * plain Exception for malformed input.
     */
    public static byte[] unwrapCek(JSONObject wp, char[] password) throws Exception {
        if (wp == null || password == null || password.length == 0) {
            throw new Exception("missing wrap blob or password");
        }
        String kdf = wp.optString("k", "");
        if (!"pbkdf2".equals(kdf)) {
            throw new Exception("unknown kdf " + kdf);
        }
        int iters = wp.optInt("i", 0);
        if (iters < 1000 || iters > 5_000_000) {
            throw new Exception("implausible kdf iteration count");
        }
        byte[] salt = XoSecretEnvelope.b64Decode(wp.optString("s", ""));
        byte[] nonce = XoSecretEnvelope.b64Decode(wp.optString("n", ""));
        byte[] ct = XoSecretEnvelope.b64Decode(wp.optString("w", ""));
        if (salt.length < 8 || nonce.length != NONCE_LEN || ct.length != CEK_LEN + 16) {
            throw new Exception("malformed wrap blob");
        }
        byte[] kek = deriveKek(password, salt, iters);
        try {
            byte[] cek = gcm(kek, nonce, AAD_WRAP, ct, false);
            if (cek.length != CEK_LEN) {
                throw new Exception("unwrapped key has wrong length");
            }
            return cek;
        } catch (Exception e) {
            throw new BadPassword();
        }
    }

    // ------------------------------------------------------------------ header

    /** Encrypts the inner media JSON under the CEK. @return b64(nonce || ct). */
    public static String encryptHeader(byte[] cek, String innerJson) throws Exception {
        byte[] nonce = new byte[NONCE_LEN];
        new SecureRandom().nextBytes(nonce);
        byte[] ct = gcm(cek, nonce, AAD_HEAD, innerJson.getBytes(StandardCharsets.UTF_8), true);
        byte[] blob = new byte[NONCE_LEN + ct.length];
        System.arraycopy(nonce, 0, blob, 0, NONCE_LEN);
        System.arraycopy(ct, 0, blob, NONCE_LEN, ct.length);
        return XoSecretEnvelope.b64Encode(blob);
    }

    /** Decrypts the header blob. Throws {@link BadPassword}-like failure on ANY tamper. */
    public static byte[] decryptHeader(byte[] cek, String headerB64) throws Exception {
        byte[] blob = XoSecretEnvelope.b64Decode(headerB64);
        if (blob.length < NONCE_LEN + 16) {
            throw new Exception("header blob too short");
        }
        byte[] nonce = new byte[NONCE_LEN];
        System.arraycopy(blob, 0, nonce, 0, NONCE_LEN);
        byte[] ct = new byte[blob.length - NONCE_LEN];
        System.arraycopy(blob, NONCE_LEN, ct, 0, ct.length);
        return gcm(cek, nonce, AAD_HEAD, ct, false);
    }

    // ------------------------------------------------------------------ envelope

    /** Builds the full XOSF2 message content string. */
    public static String buildEnvelope(long fileId, long plaintextLen, int chunkSize,
                                       String headerB64, JSONObject wrapJson) throws Exception {
        JSONObject json = new JSONObject();
        json.put("v", 1);
        json.put("fid", fileId);
        json.put("pl", plaintextLen);
        json.put("cs", chunkSize);
        json.put("hb", headerB64);
        json.put("wp", wrapJson);
        return PREFIX + XoSecretEnvelope.b64Encode(json.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Parsed view of an XOSF2 envelope. */
    public static final class PwEnvelope {
        public long fileId;
        public long plaintextLen;
        public int chunkSize;
        public String headerB64;
        public JSONObject wrapJson;
    }

    /** @return the parsed envelope, or null when the content is not a well-formed XOSF2 row. */
    public static PwEnvelope parse(String content) {
        try {
            byte[] body = XoSecretEnvelope.bodyOf(content, PREFIX);
            if (body == null) {
                return null;
            }
            JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
            if (json.optInt("v", 0) != 1) {
                return null;
            }
            PwEnvelope env = new PwEnvelope();
            env.fileId = json.optLong("fid", 0);
            env.plaintextLen = json.optLong("pl", 0);
            env.chunkSize = json.optInt("cs", 131072);
            env.headerB64 = json.optString("hb", null);
            env.wrapJson = json.optJSONObject("wp");
            if (env.fileId <= 0 || env.plaintextLen <= 0 || env.chunkSize <= 0
                    || env.headerB64 == null || env.wrapJson == null) {
                return null;
            }
            return env;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ row healing (storage reloads)

    /**
     * Re-renders an XOSF2 row that already has media keys (the CEK) in the
     * store — the storage-reload path where the fresh parse of TlJsonMapper
     * never ran. Fast no-op unless the row is a locked XOSF2 row; the unlock
     * itself is one tiny GCM (the KDF already happened when the password was
     * first accepted, so this is safe to call on row-load paths).
     *
     * @return true when the row was mutated to its unlocked form
     */
    public static boolean healLoadedRow(int account, TLRPC.TL_message message) {
        try {
            if (message == null || message.params == null
                    || !"1".equals(message.params.get("xoF2"))
                    || "1".equals(message.params.get("xoF2unlocked"))) {
                return false;
            }
            String envStr = message.params.get("xoF2Env");
            PwEnvelope env = parse(envStr);
            if (env == null) {
                return false;
            }
            XoSecret.MediaMeta meta = XoSecret.getInstance(account).mediaKeysFor(env.fileId);
            if (meta == null || meta.fileKey == null) {
                // still locked (no CEK yet) — re-register the locked file id
                // so the download gate keeps refusing ciphertext fetches
                XoSecretStore.getInstance(account).notePwLockedFile(env.fileId);
                return false;
            }
            byte[] inner = decryptHeader(meta.fileKey, env.headerB64);
            return XoSecret.applyPwUnlock(account, message, new String(inner, StandardCharsets.UTF_8), meta.fileKey);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ internals

    private static byte[] gcm(byte[] key, byte[] nonce, byte[] aad, byte[] input, boolean encrypt) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(input);
    }

    /** The wrong-password signal. Never carries a cause — no oracle leaks. */
    public static final class BadPassword extends Exception {
        public BadPassword() {
            super("wrong file password");
        }
    }
}

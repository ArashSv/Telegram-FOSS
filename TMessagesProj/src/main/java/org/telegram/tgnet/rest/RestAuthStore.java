package org.telegram.tgnet.rest;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Phase 2 (T2): persistent storage for the REST auth state of each account.
 *
 * <p>Replaces the legacy MTProto auth-key persistence with the JWT pair defined
 * by the backend contract (mymessenger-backend docs/API.md v1):
 * <ul>
 *   <li>access_token — JWT, 7 days, sent as {@code Authorization: Bearer}</li>
 *   <li>refresh_token — opaque, 30 days, <b>rotated on every refresh</b>.
 *       Replaying an already-rotated token revokes the whole token family
 *       server-side; the client must then fall back to a fresh login.</li>
 *   <li>sync cursor — non-secret, persisted after every /sync poll
 *       (update_queue.id, strictly increasing).</li>
 * </ul>
 *
 * <p>Security: the token blob is encrypted with AES-256/GCM using a key that
 * never leaves Android Keystore (API 23+). On API 19–22 the blob is kept in
 * app-private storage, which is exactly the protection level the legacy
 * auth-key file had — never worse than before. A blob that fails to decrypt
 * (device restore / Keystore invalidation) is treated as "not logged in"
 * and wiped — never propagated as a crash.
 *
 * <p>Threading: all state access is guarded; {@link #beginRefresh} provides a
 * single-flight claim so the future RestGateway (T3) can guarantee that
 * exactly one thread calls /auth/refresh.php on a {@code TOKEN_EXPIRED}.
 */
public final class RestAuthStore {

    private static final String TAG = "RestAuthStore";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALIAS_PREFIX = "rest_auth_";
    private static final String BLOB_FILE_PREFIX = "rest_auth_";
    private static final String BLOB_FILE_SUFFIX = ".bin";
    private static final String PREFS_PREFIX = "rest_auth_prefs_";
    private static final String PREF_CURSOR = "sync_cursor";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;

    /** Immutable snapshot of the REST auth tokens of one account. */
    public static final class TokenSet {
        public final String accessToken;
        public final String refreshToken;
        public final long expiresAt; // unix seconds, client clock

        public TokenSet(String accessToken, String refreshToken, long expiresAt) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAt = expiresAt;
        }

        /** Conservative expiry check with a 60 s skew guard (client clock only; server_time comparisons happen at the gateway). */
        public boolean isAccessTokenProbablyExpired() {
            return System.currentTimeMillis() / 1000L >= expiresAt - 60L;
        }
    }

    private static final RestAuthStore[] instances = new RestAuthStore[UserConfig.MAX_ACCOUNT_COUNT];

    /** Per-account singleton, keyed like the rest of the app (0..MAX_ACCOUNT_COUNT-1). */
    public static RestAuthStore getInstance(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            FileLog.e(TAG, "invalid account " + account + ", clamping to 0");
            account = 0;
        }
        RestAuthStore store;
        synchronized (RestAuthStore.class) {
            store = instances[account];
            if (store == null) {
                store = new RestAuthStore(account);
                instances[account] = store;
            }
        }
        return store;
    }

    private final int account;
    private final Object lock = new Object();
    private boolean refreshInFlight;
    private String claimedRefreshToken; // identity-guard for the single-flight claim

    private RestAuthStore(int account) {
        this.account = account;
    }

    // ------------------------------------------------------------------ tokens

    /** Persists the token pair atomically. Failures are logged, never thrown. */
    public void saveTokens(String accessToken, String refreshToken, long expiresAtSeconds) {
        synchronized (lock) {
            try {
                JSONObject json = new JSONObject();
                json.put("access", accessToken);
                json.put("refresh", refreshToken);
                json.put("expires_at", expiresAtSeconds);
                byte[] plain = json.toString().getBytes("UTF-8");
                byte[] blob = encrypt(plain);
                atomicWrite(blobFile(), blob);
            } catch (Exception e) {
                FileLog.e(TAG, e);
            }
        }
    }

    /** @return the stored token pair, or null when not logged in / state unreadable. */
    public TokenSet getTokens() {
        synchronized (lock) {
            byte[] blob = readFile(blobFile());
            if (blob == null) {
                return null;
            }
            try {
                JSONObject json = new JSONObject(new String(decrypt(blob), "UTF-8"));
                String access = json.optString("access", null);
                String refresh = json.optString("refresh", null);
                if (access == null || refresh == null || access.length() == 0 || refresh.length() == 0) {
                    return null;
                }
                return new TokenSet(access, refresh, json.optLong("expires_at", 0L));
            } catch (Exception e) {
                // corrupted blob or Keystore invalidation after device restore:
                // fall back to logged-out state instead of crashing the app
                FileLog.e(TAG, e);
                wipe();
                return null;
            }
        }
    }

    public boolean hasTokens() {
        return getTokens() != null;
    }

    /** Clears tokens and sync cursor (logout / token-family revocation fallback). */
    public void clear() {
        synchronized (lock) {
            refreshInFlight = false;
            claimedRefreshToken = null;
            wipe();
        }
    }

    // --------------------------------------------------- single-flight refresh

    /**
     * Claims the single refresh slot. Returns true when the caller owns the
     * refresh and must call /auth/refresh.php; false when another thread is
     * already refreshing and should instead re-read the token afterwards.
     *
     * @param currentRefreshToken the exact string reference the caller read
     *                            from its TokenSet (identity-checked at end)
     */
    public boolean beginRefresh(String currentRefreshToken) {
        synchronized (lock) {
            if (refreshInFlight) {
                return false;
            }
            refreshInFlight = true;
            claimedRefreshToken = currentRefreshToken;
            return true;
        }
    }

    /**
     * Releases the refresh slot claimed by {@link #beginRefresh}. When
     * {@code newTokens} is non-null it is persisted; null means the refresh
     * failed (caller decides whether to retry or clear() on family revocation).
     */
    public void endRefresh(String currentRefreshToken, TokenSet newTokens) {
        synchronized (lock) {
            if (!Objects.equals(currentRefreshToken, claimedRefreshToken)) {
                // stale claimant (e.g. clear()/logout ran while the refresh was
                // in flight): release the slot but never persist old tokens
                FileLog.d(TAG, "endRefresh from stale claimant, ignoring new tokens");
                refreshInFlight = false;
                claimedRefreshToken = null;
                return;
            }
            refreshInFlight = false;
            claimedRefreshToken = null;
            if (newTokens != null) {
                saveTokens(newTokens.accessToken, newTokens.refreshToken, newTokens.expiresAt);
            }
        }
    }

    public boolean isRefreshInFlight() {
        synchronized (lock) {
            return refreshInFlight;
        }
    }

    // ------------------------------------------------------------------ cursor

    /** Sync cursor (update_queue.id) of this account; 0 before the first poll. */
    public long getSyncCursor() {
        return prefs().getLong(PREF_CURSOR, 0L);
    }

    public void setSyncCursor(long cursor) {
        prefs().edit().putLong(PREF_CURSOR, cursor).apply();
    }

    // ------------------------------------------------------------- persistence

    private byte[] encrypt(byte[] plain) throws Exception {
        if (Build.VERSION.SDK_INT >= 23) {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreKey());
            byte[] iv = cipher.getIV();
            byte[] cipherText = cipher.doFinal(plain);
            byte[] out = new byte[GCM_IV_LEN + cipherText.length];
            System.arraycopy(iv, 0, out, 0, Math.min(iv.length, GCM_IV_LEN));
            System.arraycopy(cipherText, 0, out, GCM_IV_LEN, cipherText.length);
            return out;
        }
        return plain; // API 19-22: app-private storage, see class javadoc
    }

    private byte[] decrypt(byte[] blob) throws Exception {
        if (Build.VERSION.SDK_INT >= 23) {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKeystoreKey(),
                    new GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_LEN));
            return cipher.doFinal(blob, GCM_IV_LEN, blob.length - GCM_IV_LEN);
        }
        return blob;
    }

    private SecretKey getOrCreateKeystoreKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS_PREFIX + account, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS_PREFIX + account,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private void wipe() {
        File file = blobFile();
        if (file.exists() && !file.delete()) {
            FileLog.e(TAG, "unable to delete " + file.getName());
        }
        prefs().edit().remove(PREF_CURSOR).apply();
    }

    private void atomicWrite(File target, byte[] data) throws Exception {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(data);
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(target)) {
            target.delete();
            if (!tmp.renameTo(target)) {
                throw new IllegalStateException("unable to persist auth blob");
            }
        }
    }

    private byte[] readFile(File file) {
        if (!file.exists()) {
            return null;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buf = new byte[(int) file.length()];
            int off = 0;
            while (off < buf.length) {
                int read = in.read(buf, off, buf.length - off);
                if (read < 0) {
                    break;
                }
                off += read;
            }
            return buf;
        } catch (Exception e) {
            FileLog.e(TAG, e);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    private File blobFile() {
        Context context = ApplicationLoader.applicationContext;
        return new File(context.getFilesDir(), BLOB_FILE_PREFIX + account + BLOB_FILE_SUFFIX);
    }

    private SharedPreferences prefs() {
        Context context = ApplicationLoader.applicationContext;
        return context.getSharedPreferences(PREFS_PREFIX + account, Context.MODE_PRIVATE);
    }
}

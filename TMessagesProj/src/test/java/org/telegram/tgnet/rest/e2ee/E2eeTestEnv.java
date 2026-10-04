package org.telegram.tgnet.rest.e2ee;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * T71 — JVM test environment for the E2EE suite.
 *
 * <p>Provides:
 * <ul>
 *   <li>a fake application context whose {@code getFilesDir()} is a fresh
 *       temp directory per test instance (so per-account blobs are isolated
 *       and disposable) and whose prefs are in-memory;</li>
 *   <li>{@link FakeServer}: a faithful simulation of the v2.11 backend key
 *       store — register/bundle/refill with CONSUMED one-time prekeys —
 *       plus a MALICIOUS mode (identity substitution, ciphertext tamper,
 *       signed-prekey forgery, replay) for the hostile-server tests.</li>
 * </ul>
 */
public final class E2eeTestEnv {

    private static boolean initialized;
    private static File currentFilesDir;
    private static final Set<String> WHITELISTED_PREFS = new HashSet<>();

    /** Resets everything: new temp files dir, fresh singletons. */
    public static synchronized void reset() throws Exception {
        BuildVars.DEBUG_VERSION = false;
        BuildVars.LOGS_ENABLED = false;
        currentFilesDir = Files.createTempDirectory("xo-e2ee-test").toFile();
        ApplicationLoader.applicationContext = new FakeAppContext();
        XoE2EE.resetForTests();
        initialized = true;
    }

    public static boolean initialized() {
        return initialized;
    }

    /** Context that only knows getFilesDir() + in-memory SharedPreferences. */
    public static final class FakeAppContext extends ContextWrapper {
        public FakeAppContext() {
            super((Context) null);
        }

        @Override
        public File getFilesDir() {
            return currentFilesDir;
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return FakeSharedPreferences.forName(name);
        }
    }

    /** Minimal in-memory SharedPreferences. */
    public static final class FakeSharedPreferences implements SharedPreferences {
        private static final Map<String, FakeSharedPreferences> NAMED = new HashMap<>();

        public static FakeSharedPreferences forName(String name) {
            synchronized (NAMED) {
                return NAMED.computeIfAbsent(name, k -> new FakeSharedPreferences());
            }
        }

        private final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(values);
        }

        @Override
        public String getString(String key, String defValue) {
            Object v = values.get(key);
            return v instanceof String ? (String) v : defValue;
        }

        @Override
        public String[] getStringSet(String key, String[] defValues) {
            return defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object v = values.get(key);
            return v instanceof Integer ? (Integer) v : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object v = values.get(key);
            return v instanceof Long ? (Long) v : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object v = values.get(key);
            return v instanceof Float ? (Float) v : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object v = values.get(key);
            return v instanceof Boolean ? (Boolean) v : defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new Editor() {
                @Override
                public Editor putString(String key, String value) {
                    if (value == null) values.remove(key); else values.put(key, value);
                    return this;
                }

                @Override
                public Editor putStringSet(String key, java.util.Set<String> value) {
                    return this;
                }

                @Override
                public Editor putInt(String key, int value) {
                    values.put(key, value);
                    return this;
                }

                @Override
                public Editor putLong(String key, long value) {
                    values.put(key, value);
                    return this;
                }

                @Override
                public Editor putFloat(String key, float value) {
                    values.put(key, value);
                    return this;
                }

                @Override
                public Editor putBoolean(String key, boolean value) {
                    values.put(key, value);
                    return this;
                }

                @Override
                public Editor remove(String key) {
                    values.remove(key);
                    return this;
                }

                @Override
                public Editor clear() {
                    values.clear();
                    return this;
                }

                @Override
                public boolean commit() {
                    return true;
                }

                @Override
                public void apply() {
                }
            };
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }
    }

    // ------------------------------------------------------------------ fake server

    /**
     * Simulates the v2.11 backend key store. One instance = one server;
     * per-account identities are attached explicitly (account -> userId).
     */
    public static final class FakeServer implements XoE2EEApi.Backend {

        /** One registered user's key material (public parts ONLY — like the server). */
        public static final class User {
            public byte[] identityKey;
            public int registrationId;
            public JSONObject signedPrekey;
            public final Map<Integer, byte[]> oneTimeKeys = new HashMap<>();
            public int nextOtkRow = 0; // insertion order surrogate
            public final Map<Integer, Integer> otkRowToKey = new HashMap<>();
        }

        public final Map<Long, User> users = new HashMap<>();
        /** Attack mode: when set, bundle() returns THIS key instead of the peer's real identity. */
        public boolean substituteIdentity = false;
        public byte[] substituteIdentityKey;
        /** Attack mode: serve a signed prekey with an INVALID signature. */
        public boolean forgeSignature = false;
        /** Attack mode: replay log for the replay test. */
        public final Map<String, JSONObject> servedBundles = new HashMap<>();
        public long lastBundleUserId = 0;

        public void registerUser(long userId, byte[] identity, int regId, JSONObject spk, JSONArray otks) {
            User u = users.computeIfAbsent(userId, k -> new User());
            u.identityKey = identity;
            u.registrationId = regId;
            u.signedPrekey = spk;
            u.oneTimeKeys.clear();
            u.otkRowToKey.clear();
            for (int i = 0; i < otks.length(); i++) {
                JSONObject k = otks.optJSONObject(i);
                u.oneTimeKeys.put(k.optInt("key_id"), decode(k.optString("pub")));
                u.otkRowToKey.put(u.nextOtkRow++, k.optInt("key_id"));
            }
        }

        @Override
        public JSONObject post(String path, JSONObject body) {
            throw new UnsupportedOperationException("use the account-bound backend");
        }

        /** register/refill from a specific (account-bound) user. */
        public JSONObject postFor(long userId, String path, JSONObject body) throws Exception {
            if (path.endsWith("register.php")) {
                byte[] identity = decode(body.getString("identity_key"));
                int regId = body.getInt("registration_id");
                JSONObject spk = body.getJSONObject("signed_prekey");
                User u = users.computeIfAbsent(userId, k -> new User());
                u.identityKey = identity;
                u.registrationId = regId;
                u.signedPrekey = spk;
                u.oneTimeKeys.clear();
                u.otkRowToKey.clear();
                JSONArray otks = body.getJSONArray("one_time_prekeys");
                for (int i = 0; i < otks.length(); i++) {
                    JSONObject k = otks.optJSONObject(i);
                    u.oneTimeKeys.put(k.optInt("key_id"), decode(k.optString("pub")));
                    u.otkRowToKey.put(u.nextOtkRow++, k.optInt("key_id"));
                }
                JSONObject resp = new JSONObject().put("ok", true).put("otk_remaining", u.oneTimeKeys.size());
                return resp;
            }
            if (path.endsWith("refill.php")) {
                User u = users.get(userId);
                JSONArray otks = body.getJSONArray("one_time_prekeys");
                for (int i = 0; i < otks.length(); i++) {
                    JSONObject k = otks.optJSONObject(i);
                    if (!u.oneTimeKeys.containsKey(k.optInt("key_id"))) {
                        u.oneTimeKeys.put(k.optInt("key_id"), decode(k.optString("pub")));
                        u.otkRowToKey.put(u.nextOtkRow++, k.optInt("key_id"));
                    }
                }
                return new JSONObject().put("ok", true).put("otk_remaining", u.oneTimeKeys.size());
            }
            throw new IllegalArgumentException("unknown path " + path);
        }

        public JSONObject getFor(long userId, String path) throws Exception {
            if (!path.contains("bundle.php")) {
                throw new IllegalArgumentException("unknown path " + path);
            }
            long target = Long.parseLong(path.replaceAll(".*user_id=([0-9]+).*", "$1"));
            lastBundleUserId = target;
            User u = users.get(target);
            if (u == null || u.identityKey == null) {
                return new JSONObject().put("ok", false);
            }
            byte[] identity = u.identityKey;
            if (substituteIdentity && substituteIdentityKey != null) {
                identity = substituteIdentityKey; // MITM ATTACK
            }
            JSONObject spk = new JSONObject(u.signedPrekey.toString());
            if (forgeSignature) {
                spk.put("sig", encode(new byte[64])); // forged garbage signature
            }
            JSONObject resp = new JSONObject()
                    .put("ok", true)
                    .put("user_id", target)
                    .put("identity_key", encode(identity))
                    .put("registration_id", u.registrationId)
                    .put("signed_prekey", spk);
            Integer otkRow = null;
            for (int row : u.otkRowToKey.keySet()) {
                if (otkRow == null || row < otkRow) {
                    otkRow = row; // lowest row = oldest = FIFO pop
                }
            }
            if (otkRow != null) {
                int keyId = u.otkRowToKey.remove(otkRow); // CONSUMED server-side
                byte[] pub = u.oneTimeKeys.get(keyId);
                resp.put("one_time_prekey", new JSONObject()
                        .put("key_id", keyId)
                        .put("pub", encode(pub)));
            } else {
                resp.put("one_time_prekey", JSONObject.NULL);
            }
            resp.put("otk_remaining", u.otkRowToKey.size());
            servedBundles.put("bundle:" + target + ":" + System.nanoTime(), resp);
            return resp;
        }

        private static byte[] decode(String b64) {
            return XoE2EEEnvelope.b64Decode(b64);
        }

        private static String encode(byte[] raw) {
            return XoE2EEEnvelope.b64Encode(raw);
        }
    }

    /** Account-bound backend bridging one test account to one fake user. */
    public static final class AccountBackend implements XoE2EEApi.Backend {
        private final FakeServer server;
        private final long userId;

        public AccountBackend(FakeServer server, long userId) {
            this.server = server;
            this.userId = userId;
        }

        @Override
        public JSONObject post(String path, JSONObject body) throws Exception {
            return server.postFor(userId, path, body);
        }

        @Override
        public JSONObject get(String path) throws Exception {
            return server.getFor(userId, path);
        }
    }

    /** Wires an account's E2EE api to the fake server as the given user. */
    public static void bindBackend(int account, FakeServer server, long userId) {
        XoE2EEApi.getInstance(account).setBackendForTests(new AccountBackend(server, userId));
    }
}

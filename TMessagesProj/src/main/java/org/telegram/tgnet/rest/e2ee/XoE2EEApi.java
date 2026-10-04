package org.telegram.tgnet.rest.e2ee;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * T71 — the E2EE REST seam (key transport). The server contract is "dumb
 * store": this class uploads PUBLIC key material only and fetches bundles;
 * it never holds, derives or persists any private key.
 *
 * <p>Backend (v2.11.0, docs/API.md §e2ee):
 * <pre>
 *   POST api/v1/e2ee/keys/register.php  {identity_key, registration_id, signed_prekey{key_id,pub,sig}, one_time_prekeys:[{key_id,pub}...]}
 *                                       -> {ok, otk_remaining}
 *   GET  api/v1/e2ee/keys/bundle.php?user_id=N
 *                                       -> {ok, user_id, identity_key, registration_id,
 *                                           signed_prekey{key_id,pub,sig},
 *                                           one_time_prekey{key_id,pub}|null, otk_remaining}
 *                                           (the one_time_prekey row is CONSUMED atomically)
 *   POST api/v1/e2ee/keys/refill.php    {one_time_prekeys:[...]} -> {ok, otk_remaining}
 * </pre>
 *
 * <p>The transport indirection ({@link Backend}) exists so the JVM test
 * suite can simulate the whole server — including a MALICIOUS one — without
 * a device.
 */
public final class XoE2EEApi {

    /** Transport seam: production = RestGateway; tests = fakes (incl. hostile server sims). */
    public interface Backend {
        JSONObject post(String path, JSONObject body) throws Exception;
        JSONObject get(String path) throws Exception;
    }

    private static final XoE2EEApi[] instances = new XoE2EEApi[4];

    public static XoE2EEApi getInstance(int account) {
        if (account < 0 || account >= instances.length) {
            account = 0;
        }
        XoE2EEApi api;
        synchronized (XoE2EEApi.class) {
            api = instances[account];
            if (api == null) {
                api = new XoE2EEApi(account, null);
                instances[account] = api;
            }
        }
        return api;
    }

    static void resetForTests() {
        synchronized (XoE2EEApi.class) {
            for (int a = 0; a < instances.length; a++) {
                instances[a] = null;
            }
        }
    }

    private final int account;
    private volatile Backend backendOverride;

    private XoE2EEApi(int account, Backend ignored) {
        this.account = account;
    }

    /** Test seam: replace the transport (call BEFORE first use of this account). */
    public void setBackendForTests(Backend backend) {
        this.backendOverride = backend;
    }

    private Backend backend() {
        Backend override = backendOverride;
        return override != null ? override : new RestGatewayBackend(account);
    }

    // ------------------------------------------------------------------ calls

    /**
     * Full (re-)registration of this account's public key set. Server-side
     * this REPLACES the previous identity + signed prekey and refills the
     * OTK pool (used on first login and on identity recovery).
     *
     * @return otk_remaining as reported by the server
     */
    public int register(org.whispersystems.libsignal.IdentityKeyPair identity,
                        int registrationId, JSONObject signedPreKey, JSONArray oneTimeKeys) throws Exception {
        JSONObject body = new JSONObject();
        body.put("identity_key", XoE2EEEnvelope.b64Encode(identity.getPublicKey().serialize()));
        body.put("registration_id", registrationId);
        body.put("signed_prekey", signedPreKey);
        body.put("one_time_prekeys", oneTimeKeys);
        JSONObject resp = backend().post("e2ee/keys/register.php", body);
        requireOk(resp);
        return resp.optInt("otk_remaining", oneTimeKeys.length());
    }

    /** Appends one-time prekeys to the server pool (does not touch identity). */
    public int refill(JSONArray oneTimeKeys) throws Exception {
        JSONObject body = new JSONObject();
        body.put("one_time_prekeys", oneTimeKeys);
        JSONObject resp = backend().post("e2ee/keys/refill.php", body);
        requireOk(resp);
        return resp.optInt("otk_remaining", -1);
    }

    /**
     * Fetches a peer's prekey bundle. NEVER throws on server-side "not
     * found" — returns a bundle with ok=false so the facade can translate
     * that into a clean "recipient needs a newer version" error.
     */
    public JSONObject bundle(long userId) {
        try {
            JSONObject resp = backend().get("e2ee/keys/bundle.php?user_id=" + userId);
            if (resp == null || !resp.optBoolean("ok", false)) {
                return new JSONObject().put("ok", false);
            }
            resp.put("ok", true);
            return resp;
        } catch (Exception e) {
            try {
                return new JSONObject().put("ok", false).put("transport", String.valueOf(e.getMessage()));
            } catch (Exception ignore) {
                return new JSONObject();
            }
        }
    }

    private static void requireOk(JSONObject resp) throws Exception {
        if (resp == null || !resp.optBoolean("ok", false)) {
            String code = resp == null ? "null" : String.valueOf(resp.optJSONObject("error"));
            throw new Exception("e2ee backend call failed: " + code);
        }
    }

    // ------------------------------------------------------------------ production backend

    /** Bridges to RestGateway (auth, refresh, envelope parsing are already there). */
    private static final class RestGatewayBackend implements Backend {
        private final int account;

        RestGatewayBackend(int account) {
            this.account = account;
        }

        @Override
        public JSONObject post(String path, JSONObject body) throws Exception {
            return org.telegram.tgnet.rest.RestGateway.getInstance(account).e2eePost(path, body.toString());
        }

        @Override
        public JSONObject get(String path) throws Exception {
            return org.telegram.tgnet.rest.RestGateway.getInstance(account).e2eeGet(path);
        }
    }
}

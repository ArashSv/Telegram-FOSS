package org.telegram.tgnet.rest.e2ee;

import org.json.JSONObject;

/**
 * T78 — REST seam for the secret-chat PUBLIC-key registry.
 *
 * <p>Backend contract (App\Controllers\SecretController, v2.12.0):
 * <pre>
 *   PUT /api/v1/secret/keys.php {pk: b64(32B)}
 *       -> {ok, changed, pk, updated_at}
 *   GET /api/v1/secret/keys.php?user_id=N
 *       -> {ok, user_id, registered: true, pk, updated_at}
 *        | {ok, user_id, registered: false}
 * </pre>
 * The {@link Backend} interface keeps the JVM test suite dependency-free
 * (fake servers instead of OkHttp).
 */
public final class XoSecretApi {

    /** Transport seam for tests. */
    public interface Backend {
        JSONObject post(String path, JSONObject body) throws Exception;

        JSONObject get(String path) throws Exception;
    }

    private static final XoSecretApi[] instances = new XoSecretApi[org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT];

    public static XoSecretApi getInstance(int account) {
        if (account < 0 || account >= instances.length) {
            account = 0;
        }
        XoSecretApi api;
        synchronized (XoSecretApi.class) {
            api = instances[account];
            if (api == null) {
                api = new XoSecretApi(account);
            }
        }
        return api;
    }

    /** Test seam. */
    public static void setBackendForTests(int account, Backend backend) {
        getInstance(account).backend = backend;
    }

    /** Test seam. */
    public static void resetForTests() {
        synchronized (XoSecretApi.class) {
            for (int a = 0; a < instances.length; a++) {
                instances[a] = null;
            }
        }
    }

    private final int account;
    private volatile Backend backend;

    private XoSecretApi(int account) {
        this.account = account;
    }

    private Backend backend() {
        Backend b = backend;
        if (b == null) {
            final org.telegram.tgnet.rest.RestGateway gateway = org.telegram.tgnet.rest.RestGateway.getInstance(account);
            b = new Backend() {
                @Override
                public JSONObject post(String path, JSONObject body) throws Exception {
                    return gateway.e2eePost(path, body.toString());
                }

                @Override
                public JSONObject get(String path) throws Exception {
                    return gateway.e2eeGet(path);
                }
            };
            backend = b;
        }
        return b;
    }

    /**
     * PUTs our public key. Returns the server answer ({ok, changed, pk,
     * updated_at}) or null on transport failure — callers retry lazily.
     */
    public JSONObject putKey(String pkB64) {
        try {
            JSONObject body = new JSONObject();
            body.put("pk", pkB64);
            return backend().post("secret/keys.php", body);
        } catch (Throwable t) {
            XoE2eeLog.event(account, "secret.putKey.fail", 0, String.valueOf(t.getClass().getSimpleName()));
            return null;
        }
    }

    /**
     * GETs a user's public key. Returns the server answer or null on
     * transport failure. A present-but-keyless user answers
     * {ok, registered:false} (NOT null).
     */
    public JSONObject getKey(long userId) {
        try {
            return backend().get("secret/keys.php?user_id=" + userId);
        } catch (Throwable t) {
            XoE2eeLog.event(account, "secret.getKey.fail", userId, String.valueOf(t.getClass().getSimpleName()));
            return null;
        }
    }
}

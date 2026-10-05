package org.telegram.tgnet.rest.e2ee;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T74 — LIVE harness: the REAL client E2EE classes against the REAL
 * production backend (xorbit.ir v2.11.x). This is the definitive proof that
 * 1:1 messages encrypt, relay as opaque XOE1 envelopes, and DECRYPT on the
 * other side — plus that the shipped recovery un-bricks a poisoned store —
 * all over the actual wire.
 *
 * <p>Runs ONLY when the env XO_LIVE_T74=1 (CI stays offline):
 * {@code XO_LIVE_T74=1 java -cp <test classpath> org.junit.runner.JUnitCore ...}
 */
public class XoE2EELiveHarnessTest {

    private static final String BASE = "https://xorbit.ir/tele/api/v1";
    private static final long ALICE_ID = 3001L; // account slot, not user id
    private static final long BOB_ID = 3002L;

    private static final class Account {
        String phone, password, token;
        long userId;
        long chatId;
    }

    private final Map<Integer, Account> accounts = new HashMap<>();

    // ------------------------------------------------------------------ REST plumbing

    private static JSONObject http(String method, String url, String token, JSONObject body) throws Exception {
        // the host intermittently drops connections (documented since T55) —
        // the python probes carry up to 8 tries; mirror that here.
        Exception last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                return httpOnce(method, url, token, body);
            } catch (java.io.IOException e) {
                last = e;
                Thread.sleep(1500L * (attempt + 1));
            }
        }
        if (last != null) {
            throw last;
        }
        throw new AssertionError("transport failed");
    }

    private static JSONObject httpOnce(String method, String url, String token, JSONObject body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(45000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("User-Agent", "T74LiveHarness/1");
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        byte[] payload = null;
        if (body != null) {
            payload = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(payload.length);
            conn.setRequestProperty("Content-Type", "application/json");
        }
        if (payload != null) {
            java.io.OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                bout.write(buf, 0, n);
            }
            in.close();
        }
        String text = new String(bout.toByteArray(), StandardCharsets.UTF_8);
        JSONObject json;
        try {
            json = new JSONObject(text);
        } catch (Exception e) {
            throw new AssertionError("non-JSON (" + code + "): " + text.substring(0, Math.min(200, text.length())));
        }
        if (code >= 400) {
            throw new AssertionError(method + " " + url + " -> " + code + " " + json);
        }
        return json.put("___http", code);
    }

    /** XoE2EEApi.Backend bound to ONE account's bearer token (the real wire). */
    private static final class LiveBackend implements XoE2EEApi.Backend {
        private final Account acc;

        LiveBackend(Account acc) {
            this.acc = acc;
        }

        @Override
        public JSONObject post(String path, JSONObject body) throws Exception {
            return http("POST", BASE + "/" + path, acc.token, body);
        }

        @Override
        public JSONObject get(String path) throws Exception {
            return http("GET", BASE + "/" + path, acc.token, null);
        }
    }

    private Account signup(int slot, String stamp, String suffix) throws Exception {
        Account a = new Account();
        a.phone = "+404" + stamp + suffix; // closed test namespace: +404 + exactly 5 digits
        a.password = "T74pw" + stamp + suffix;
        JSONObject resp = http("POST", BASE + "/auth/register.php", null,
                new JSONObject().put("phone", a.phone)
                        .put("password", a.password)
                        .put("hint", "t74"));
        a.token = resp.getString("access_token");
        a.userId = resp.getJSONObject("user").getLong("id");
        accounts.put(slot, a);
        XoE2EEApi.getInstance(slot).setBackendForTests(new LiveBackend(a));
        return a;
    }

    private long ensurePrivateChat(Account a, long peerId) throws Exception {
        JSONObject resp = http("POST", BASE + "/chats/create.php", a.token,
                new JSONObject().put("type", "private").put("peer_user_id", peerId));
        return resp.getJSONObject("chat").getLong("id");
    }

    private long sendEncrypted(Account a, long chatId, String wireContent) throws Exception {
        JSONObject resp = http("POST", BASE + "/messages/send.php", a.token,
                new JSONObject().put("chat_id", chatId).put("content", wireContent));
        return resp.getJSONObject("message").getLong("id");
    }

    private JSONObject fetchRow(Account reader, long chatId, long sinceId) throws Exception {
        JSONObject resp = http("GET", BASE + "/messages/history.php?chat_id=" + chatId
                + "&since_id=" + sinceId + "&limit=50", reader.token, null);
        JSONArray arr = resp.optJSONArray("messages");
        return arr == null || arr.length() == 0 ? null : arr.getJSONObject(arr.length() - 1);
    }

    // ------------------------------------------------------------------ the harness

    @Test
    public void liveRoundTripOnProduction() throws Exception {
        Assume.assumeTrue("live harness runs only with XO_LIVE_T74=1",
                "1".equals(System.getenv("XO_LIVE_T74")));
        E2eeTestEnv.reset(); // temp files dir + fresh singletons (works on the JVM)

        long ms = System.currentTimeMillis();
        String stamp = String.valueOf(1000 + (ms % 8000)); // 4 digits
        Account alice = signup(0, stamp, "1"); // +404 + 4+1 = exactly 5 digits
        Account bob = signup(1, stamp, "2");
        assertTrue(alice.userId > 0 && bob.userId > 0);

        // 1. REAL key registration for both accounts.
        assertTrue(XoE2EE.getInstance(0).ensureRegistered());
        assertTrue(XoE2EE.getInstance(1).ensureRegistered());

        // 2. v2.11.1 register-preserve semantics: after consuming one OTK, a
        //    re-register with the SAME identity must PRESERVE the pool.
        // consume one of Alice's OTKs by fetching HER bundle from Bob's side:
        LiveBackend bobWire = new LiveBackend(bob);
        JSONObject bundleOfAlice = bobWire.get("e2ee/keys/bundle.php?user_id=" + alice.userId);
        int remainingAfterOne = bundleOfAlice.optInt("otk_remaining", -1);
        assertTrue("bundle served (otk consumed)", remainingAfterOne > 0);
        // re-register Alice with the same identity + ONE fresh OTK (re-confirm;
        // the signed prekey is re-submitted verbatim from the server's copy).
        // v2.11.1 semantics: unchanged identity => pool PRESERVED (the fresh
        // OTK is added; the 99 remaining rows are NOT deleted). A v2.11.0
        // (delete-all) backend would collapse the pool to just this one key.
        org.whispersystems.libsignal.state.PreKeyRecord fresh =
                org.whispersystems.libsignal.util.KeyHelper.generatePreKeys(900000, 1).get(0);
        JSONArray oneOtk = new JSONArray().put(new JSONObject()
                .put("key_id", fresh.getId())
                .put("pub", XoE2EEEnvelope.b64Encode(fresh.getKeyPair().getPublicKey().serialize())));
        int remainingBeforeReregister = bundleOfAlice.optInt("otk_remaining", -1);
        XoE2EE aliceMgr = XoE2EE.getInstance(0);
        XoE2EEApi.getInstance(0).register(
                XoE2EEStore.getInstance(0).getIdentityKeyPairObj(),
                XoE2EEStore.getInstance(0).getLocalRegistrationId(),
                bundleOfAlice.getJSONObject("signed_prekey"),
                oneOtk);
        JSONObject bundleOfAlice2 = bobWire.get("e2ee/keys/bundle.php?user_id=" + alice.userId);
        assertTrue("v2.11.1: re-register with unchanged identity PRESERVES the OTK pool (remaining="
                        + bundleOfAlice2.optInt("otk_remaining", -1) + " before=" + remainingBeforeReregister + ")",
                bundleOfAlice2.optBoolean("ok", false)
                        && bundleOfAlice2.optJSONObject("one_time_prekey") != null
                        && bundleOfAlice2.optInt("otk_remaining", -1) >= remainingBeforeReregister);

        // 3. REAL X3DH + send + relay + receive + decrypt (Alice -> Bob).
        alice.chatId = ensurePrivateChat(alice, bob.userId);
        bob.chatId = ensurePrivateChat(bob, alice.userId);
        assertEquals("deterministic private pair", alice.chatId, bob.chatId);

        String text1 = "سلام زنده — تست واقعی T74";
        String envelope1 = aliceMgr.encryptText(bob.userId, text1);
        assertTrue(XoE2EEEnvelope.isEnvelope(envelope1));
        long id1 = sendEncrypted(alice, alice.chatId, envelope1);
        assertTrue(id1 > 0);

        JSONObject row1 = fetchRow(bob, bob.chatId, 0);
        assertNotNull(row1);
        assertEquals("envelope relays verbatim (opaque)", envelope1, row1.getString("content"));
        String inner1 = XoE2EE.getInstance(1).decryptFromPeer(alice.userId, row1.getString("content"));
        assertNotNull("Bob decrypts the real message", inner1);
        // the inner is JSON — org.json escapes non-ASCII (\u2014 etc.), so
        // compare through the parser exactly like the client's mapper does
        assertEquals(text1, new JSONObject(inner1).optString("x"));

        // 4. Reply (Bob -> Alice) on the ratcheted sessions.
        String text2 = "پاسخ باب — دریافت شد";
        String envelope2 = XoE2EE.getInstance(1).encryptText(alice.userId, text2);
        long id2 = sendEncrypted(bob, bob.chatId, envelope2);
        assertTrue(id2 > 0);
        JSONObject row2 = fetchRow(alice, alice.chatId, id1);
        assertNotNull(row2);
        String inner2 = aliceMgr.decryptFromPeer(bob.userId, row2.getString("content"));
        assertNotNull("Alice decrypts the reply", inner2);
        assertEquals(text2, new JSONObject(inner2).optString("x"));

        // 5. POISON-RECOVERY against the REAL server: plant the build-108
        //    self-pin, then one encrypt call must heal + still deliver.
        XoE2EEStore.getInstance(0).selfPinForTests(bob.userId);
        assertTrue(XoE2EEStore.getInstance(0).isSelfPin(bob.userId));
        String text3 = "ترمیم خودکار روی سرور واقعی";
        String envelope3 = aliceMgr.encryptText(bob.userId, text3); // heals inside
        sendEncrypted(alice, alice.chatId, envelope3);
        JSONObject row3 = fetchRow(bob, bob.chatId, id2);
        String inner3 = XoE2EE.getInstance(1).decryptFromPeer(alice.userId, row3.getString("content"));
        assertNotNull(inner3);
        assertEquals(text3, new JSONObject(inner3).optString("x"));
        assertFalseAfterHeal(bob.userId);

        // 6. Own-echo refusal (T74 regression) on the live store.
        assertNull(aliceMgr.decryptFromPeer(bob.userId, envelope1));
    }

    private void assertFalseAfterHeal(long peer) {
        assertTrue("flag must be cleared after the live heal",
                !XoE2EEStore.getInstance(0).isFlagged(peer));
    }
}

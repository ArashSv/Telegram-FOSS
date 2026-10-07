package org.telegram.tgnet.rest;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.rest.e2ee.E2eeTestEnv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T80 — the two chat modes (cloud vs secret) client contract.
 *
 * <p>Pinned here:
 * <ol>
 *   <li><b>The chat mode mirror</b> (RestChatIndex.chatModes): chats/list
 *       payloads feed it, the default is CLOUD (unknown chats must always
 *       work), secret parses, chat_mode updates apply, groups never carry a
 *       secret mode.</li>
 *   <li><b>The safe default</b>: an unknown peer/chat is CLOUD —
 *       isSecretPeer false, so sends never touch libsignal and deferral
 *       never hangs on a clock icon for cloud chats.</li>
 *   <li><b>Mode-independent rendering</b>: a plain (cloud) row parses to its
 *       exact plaintext without any E2EE involvement, and the decrypt hook
 *       stays content-triggered (an XOE1 row inside a now-cloud chat still
 *       tries the legacy session — history never becomes garbage).</li>
 * </ol>
 *
 * JVM scope note: android.util SparseArrays are stubbed by
 * returnDefaultValues, so the peer-id → chat-id leg (isSecretPeer's positive
 * path) is covered by the on-device wire suite, not here; everything
 * HashMap-backed (modes, types) is fully pinned.
 */
public class XoT80ChatModeTest {

    private static final long ALICE_ID = 1001L;
    private static final long BOB_ID = 2002L;

    private RestChatIndex index;

    @BeforeClass
    public static void setUpClass() throws Exception {
        E2eeTestEnv.reset();
    }

    @Before
    public void setUp() throws Exception {
        E2eeTestEnv.reset();
        index = RestChatIndex.getInstance(0);
    }

    private static JSONObject chatJson(long chatId, String type, String mode, long peerId) throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", chatId);
        json.put("type", type);
        if (mode != null) {
            json.put("mode", mode);
        }
        JSONObject peer = new JSONObject();
        peer.put("id", peerId);
        peer.put("first_name", "Bob");
        json.put("peer", peer);
        return json;
    }

    @Test
    public void chatsList_defaultModeIsCloud() throws Exception {
        org.json.JSONArray chats = new org.json.JSONArray().put(chatJson(42L, "private", null, BOB_ID));
        index.scanChats(chats);
        assertEquals("a chat payload without a mode field is CLOUD (server default)",
                "cloud", index.modeForChat(42L));
        assertFalse("unknown mode must never be secret", index.isSecretChat(42L));
    }

    @Test
    public void chatsList_secretModeParses() throws Exception {
        org.json.JSONArray chats = new org.json.JSONArray().put(chatJson(43L, "private", "secret", BOB_ID));
        index.scanChats(chats);
        assertEquals("secret", index.modeForChat(43L));
        assertTrue(index.isSecretChat(43L));
    }

    @Test
    public void unknownChat_isCloud_safeDefault() {
        assertEquals("unknown chats are cloud — the always-works default",
                "cloud", index.modeForChat(999L));
        assertFalse(index.isSecretChat(999L));
        assertFalse("unknown peers never encrypt", index.isSecretPeer(12345L));
        assertFalse(index.isSecretPeer(0L));
        assertFalse(index.isSecretPeer(-7L));
    }

    @Test
    public void chatModeUpdate_appliesServerFlip() throws Exception {
        org.json.JSONArray chats = new org.json.JSONArray().put(chatJson(44L, "private", "cloud", BOB_ID));
        index.scanChats(chats);
        assertFalse(index.isSecretChat(44L));

        index.setChatMode(44L, "secret");
        assertTrue("the chat_mode update must flip the mirror", index.isSecretChat(44L));

        index.setChatMode(44L, "cloud");
        assertFalse("flipping back restores cloud", index.isSecretChat(44L));
    }

    @Test
    public void chatModeUpdate_ignoresUnknownChats() {
        index.setChatMode(777L, "secret");
        assertFalse("an unknown chat id must not gain a mode behind the index's back",
                index.isSecretChat(777L));
    }

    @Test
    public void garbageMode_normalizesToCloud() throws Exception {
        org.json.JSONArray chats = new org.json.JSONArray().put(chatJson(45L, "private", "banana", BOB_ID));
        index.scanChats(chats);
        assertEquals("anything not 'secret' is cloud", "cloud", index.modeForChat(45L));
        index.setChatMode(45L, "also-banana");
        assertEquals("a garbage flip must not corrupt the mirror", "cloud", index.modeForChat(45L));
    }

    @Test
    public void groupChats_neverCarrySecretMode() throws Exception {
        JSONObject group = chatJson(50L, "group", "secret", BOB_ID);
        group.put("title", "grp");
        group.remove("peer");
        org.json.JSONArray chats = new org.json.JSONArray().put(group);
        index.scanChats(chats);
        assertTrue(index.isGroup(50L));
        assertFalse("groups have no secret mode", index.isSecretChat(50L));
        assertEquals("cloud", index.modeForChat(50L));
    }

    @Test
    public void putPrivate_registersMode() {
        index.putPrivate(BOB_ID, 60L, "secret");
        assertTrue(index.isSecretChat(60L));
        index.putPrivate(BOB_ID, 61L);
        assertEquals("the 2-arg overload defaults to cloud", "cloud", index.modeForChat(61L));
    }

    @Test
    public void clear_resetsModes() throws Exception {
        org.json.JSONArray chats = new org.json.JSONArray().put(chatJson(46L, "private", "secret", BOB_ID));
        index.scanChats(chats);
        assertTrue(index.isSecretChat(46L));
        index.clear();
        assertEquals("a session wipe must clear the mode mirror too", "cloud", index.modeForChat(46L));
    }

    // ------------------------------------------------------ rendering paths

    private static JSONObject messageJson(long id, long senderId, String content) throws Exception {
        JSONObject msg = new JSONObject();
        msg.put("id", id);
        msg.put("sender_id", senderId);
        msg.put("date", (int) (System.currentTimeMillis() / 1000L));
        msg.put("content", content == null ? JSONObject.NULL : content);
        return msg;
    }

    @Test
    public void cloudPlaintext_parsesVerbatim_noE2eeInvolved() throws Exception {
        String plain = "cloud hello — سلام";
        TLRPC.TL_message parsed = TlJsonMapper.parseMessage(0,
                messageJson(10L, BOB_ID, plain), ALICE_ID, false, BOB_ID, ALICE_ID);
        assertNotNull(parsed);
        assertEquals("a cloud row must render exactly its plaintext (mode-independent)",
                plain, parsed.message);
    }

    @Test
    public void legacyEnvelopeRow_insideCloudChat_stillContentTriggered() throws Exception {
        // A row born in the E2EE era now lives in a cloud-mode chat (T80
        // backfill). The decrypt hook is CONTENT-triggered: the envelope
        // prefix must still attempt the legacy session — never render the
        // raw ciphertext as text. The session here is unknown to this fresh
        // JVM store, so the honest outcome is the lock placeholder, not the
        // envelope blob and not garbage.
        String envelope = "XOE1:Q2lwaGVydGV4dA==";
        TLRPC.TL_message parsed = TlJsonMapper.parseMessage(0,
                messageJson(11L, BOB_ID, envelope), ALICE_ID, false, BOB_ID, ALICE_ID);
        assertNotNull(parsed);
        assertFalse("the raw envelope must never leak as visible text",
                envelope.equals(parsed.message));
        assertEquals("a failed legacy decrypt degrades to the lock placeholder",
                "🔒", parsed.message);
    }
}

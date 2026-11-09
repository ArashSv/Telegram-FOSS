package org.telegram.tgnet.rest.e2ee;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.rest.RestChatIndex;
import org.telegram.tgnet.rest.XoTestEnv;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T85 — regression pins for the three field-reported defects:
 *
 * <ol>
 *   <li>SECRET CHAT IS "A GROUP WITH TWO MEMBERS": the chats stay in the
 *       chat space (dialog id -chatId, XOSC1 data layer untouched) but the
 *       CHAT now renders through the Telegram secret-chat chrome. The data
 *       preconditions this test pins: the index MUST resolve
 *       isSecret/secretPeerUser for a scanned secret row (what
 *       ChatActivity.resolveRestSecretChat consumes), and the fabricated
 *       EncryptedChat sentinel must be recognizable.</li>
 *   <li>XOSF2 RECEIVER CANNOT RECOGNIZE THE ROW: the params HashMap never
 *       survived the MessagesStorage round-trip on RECEIVED rows (server
 *       id &gt; 0, out=false) — writeAttachPath only piggybacked params for
 *       local/unsent rows, so xoF2/xoF2Env vanished and the reloaded row
 *       rendered as an opaque plain document whose download fetched raw
 *       ciphertext. The piggyback codec is now (a) written whenever the
 *       row carries xoF2Env and (b) parsed for received media rows. These
 *       tests pin the codec's byte-format contract.</li>
 *   <li>Fresh-install blocking (REQUEST_INSTALL_PACKAGES removal) is a
 *       manifest change — enforced by the new CI gate, not testable on the
 *       JVM.</li>
 * </ol>
 */
public class XoT85RegressionTest {

    @BeforeClass
    public static void init() throws Exception {
        XoTestEnv.init();
        boolean needsFilesDir = false;
        try {
            needsFilesDir = org.telegram.messenger.ApplicationLoader.applicationContext == null
                    || org.telegram.messenger.ApplicationLoader.applicationContext.getFilesDir() == null;
        } catch (Throwable t) {
            needsFilesDir = true;
        }
        if (needsFilesDir) {
            File tmp = Files.createTempDirectory("xot85").toFile();
            org.telegram.messenger.ApplicationLoader.applicationContext =
                    new android.content.ContextWrapper(
                            org.telegram.messenger.ApplicationLoader.applicationContext) {
                        @Override
                        public File getFilesDir() {
                            return tmp;
                        }
                    };
        }
    }

    // ------------------------------------------------- 1. the params piggyback codec

    @Test
    public void piggyback_roundTrip_preservesParamsAndPath() {
        HashMap<String, String> params = new HashMap<>();
        params.put("xoF2", "1");
        params.put("xoF2Env", "XOSF2:eyJ2IjoxLCJmaWQiOjk5OTksInBsIjo0MDk2fQ==");
        String encoded = TLRPC.Message.encodeParamsPiggyback(params, " ");
        assertTrue("must carry the sentinel prefix", encoded.startsWith("||"));
        TLRPC.Message.Piggyback pb = TLRPC.Message.decodeParamsPiggyback(encoded, null);
        // T85b (CI run 175): the decoder TRIMS the trailing path — exactly like
        // the upstream inline code it replaced. The writer's blank " " is a
        // serialization convention; an UNTRIMMED " " would read as a REAL
        // attach path downstream (TextUtils.isEmpty(" ") is false). The params
        // are the payload; the path normalizes to "".
        assertEquals("", pb.attachPath);
        assertNotNull(pb.params);
        assertEquals("1", pb.params.get("xoF2"));
        assertEquals("XOSF2:eyJ2IjoxLCJmaWQiOjk5OTksInBsIjo0MDk2fQ==", pb.params.get("xoF2Env"));
    }

    @Test
    public void piggyback_legacyFormat_decodesIdentically() {
        // the EXACT string the pre-T85 writer produced for unsent rows — the
        // decoder must stay compatible with rows already in users' databases
        String legacy = "||fwd_peer|=|-100200300||ve|=|-1_ab|| ";
        TLRPC.Message.Piggyback pb = TLRPC.Message.decodeParamsPiggyback(legacy, null);
        assertEquals("", pb.attachPath); // trimmed — upstream-identical
        assertEquals("-100200300", pb.params.get("fwd_peer"));
        assertEquals("-1_ab", pb.params.get("ve"));
        // and the encoder reproduces the same shape for the same input
        HashMap<String, String> params = new HashMap<>();
        params.put("fwd_peer", "-100200300");
        params.put("ve", "-1_ab");
        String reEncoded = TLRPC.Message.encodeParamsPiggyback(params, " ");
        TLRPC.Message.Piggyback pb2 = TLRPC.Message.decodeParamsPiggyback(reEncoded, null);
        assertEquals(pb.params, pb2.params);
        assertEquals(pb.attachPath, pb2.attachPath);
    }

    @Test
    public void piggyback_nonPiggyback_passesThroughTrimmed() {
        // received media rows without params carry a single blank
        TLRPC.Message.Piggyback pb = TLRPC.Message.decodeParamsPiggyback(" ", null);
        assertEquals("", pb.attachPath);
        assertNull(pb.params);
        // a real (non-piggyback) attach path
        TLRPC.Message.Piggyback pb2 = TLRPC.Message.decodeParamsPiggyback(" /tmp/photo.jpg ", null);
        assertEquals("/tmp/photo.jpg", pb2.attachPath);
        assertNull(pb2.params);
    }

    @Test
    public void piggyback_valueContainingSeparator_splitsOnFirstOnly() {
        // defense in depth: a value that itself contains "|=|" survives (the
        // split uses limit 2, matching the T85 decoder everywhere)
        HashMap<String, String> params = new HashMap<>();
        params.put("k", "a|=|b");
        String encoded = TLRPC.Message.encodeParamsPiggyback(params, " ");
        TLRPC.Message.Piggyback pb = TLRPC.Message.decodeParamsPiggyback(encoded, null);
        assertEquals("a|=|b", pb.params.get("k"));
    }

    @Test
    public void piggyback_emptyParamsMap_isNoOp() {
        assertEquals(" ", TLRPC.Message.encodeParamsPiggyback(new HashMap<>(), " "));
        assertEquals("/x", TLRPC.Message.encodeParamsPiggyback(null, "/x"));
    }

    @Test
    public void piggyback_mergeIntoExistingParams() {
        HashMap<String, String> existing = new HashMap<>();
        existing.put("ve", "-1_ab");
        String encoded = TLRPC.Message.encodeParamsPiggyback(paramsOf("xoF2Env", "XOSF2:zz"), " ");
        TLRPC.Message.Piggyback pb = TLRPC.Message.decodeParamsPiggyback(encoded, existing);
        assertEquals("XOSF2:zz", pb.params.get("xoF2Env"));
        assertEquals("-1_ab", pb.params.get("ve")); // merge, not replace
        assertTrue(pb.params == existing); // merged IN PLACE
    }

    private static HashMap<String, String> paramsOf(String k, String v) {
        HashMap<String, String> m = new HashMap<>();
        m.put(k, v);
        return m;
    }

    // ------------------------------------------------- 2. the REST secret chat fabrication

    @Test
    public void restFabricatedSentinel_recognizesOnlyFabricatedChats() {
        TLRPC.TL_encryptedChat fabricated = new TLRPC.TL_encryptedChat();
        fabricated.id = 4242;
        fabricated.user_id = 10071;
        fabricated.key_fingerprint = XoSecret.REST_FABRICATED_FINGERPRINT;
        fabricated.layer = 151; // SecretChatHelper.CURRENT_SECRET_CHAT_LAYER
        assertTrue(XoSecret.isRestFabricatedEncryptedChat(fabricated));

        TLRPC.TL_encryptedChat real = new TLRPC.TL_encryptedChat();
        real.id = 4242;
        real.user_id = 10071;
        real.key_fingerprint = 123456789012345L; // a genuine DH fingerprint
        assertFalse("a genuine-DH chat is never fabricated", XoSecret.isRestFabricatedEncryptedChat(real));
        assertFalse("null is never fabricated", XoSecret.isRestFabricatedEncryptedChat(null));
    }

    @Test
    public void secretChatIndex_resolvesPeerAndSecretness_forChatActivity() throws Exception {
        // the exact payload shape chats/create.php + chats/list.php answer —
        // ChatActivity.resolveRestSecretChat consumes isSecret + secretPeerUser
        org.json.JSONArray arr = new org.json.JSONArray();
        JSONObject chat = new JSONObject();
        chat.put("id", 5150L);
        chat.put("type", "secret");
        chat.put("mode", "secret");
        JSONObject peer = new JSONObject();
        peer.put("id", 10071L);
        peer.put("first_name", "Peer");
        peer.put("username", "peer");
        chat.put("peer", peer);
        arr.put(chat);
        RestChatIndex index = RestChatIndex.getInstance(0);
        index.scanChats(arr);

        assertTrue(index.isSecret(5150L));
        assertEquals(10071L, index.secretPeerUser(5150L));
        assertTrue(XoSecret.isSecretDialog(0, -5150L));
        assertFalse(XoSecret.isSecretDialog(0, -7777L)); // unknown chat
        assertEquals(5150L, XoSecret.chatIdOfDialog(-5150L));
        assertNotNull(index.secretChat(5150L));
        assertTrue("the fabricated title must be the peer name (no lock emoji)",
                "Peer".equals(index.secretChat(5150L).title));
    }

    // ------------------------------------------------- 3. the XOSF2 reloaded-row heal

    @Test
    public void healLoadedRow_lockedRow_reArmsTheDownloadGate() throws Exception {
        // a storage-reloaded locked row: params restored by the T85 piggyback
        // (xoF2 + xoF2Env), no CEK in the store yet — the heal must re-arm
        // notePwLockedFile so the loader refuses the ciphertext, exactly like
        // the fresh-parse path did.
        org.telegram.tgnet.rest.e2ee.XoSecretStore store =
                org.telegram.tgnet.rest.e2ee.XoSecretStore.getInstance(0);
        long fileId = 860001L;
        store.clearPwLockedFile(fileId);
        assertFalse(store.isPwLockedFile(fileId));

        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = 501;
        message.out = false;
        message.params = new HashMap<>();
        message.params.put("xoF2", "1");
        message.params.put("xoF2Env", lockedEnvelopeFor(fileId));
        message.media = new TLRPC.TL_messageMediaDocument();
        TLRPC.TL_messageMediaDocument media = (TLRPC.TL_messageMediaDocument) message.media;
        media.document = new TLRPC.TL_document();
        TLRPC.TL_document doc = (TLRPC.TL_document) media.document;
        doc.id = fileId;
        doc.mime_type = "application/octet-stream";
        doc.size = 4096 + 16;

        boolean healed = XoFilePassCrypto.healLoadedRow(0, message);
        assertFalse("no CEK registered — the row stays locked", healed);
        assertTrue("the download gate must refuse the ciphertext again",
                store.isPwLockedFile(fileId));
        store.clearPwLockedFile(fileId);
    }

    @Test
    public void healLoadedRow_ignoresNonXosf2Rows() {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = 502;
        message.params = null; // a plain received row after reload
        assertFalse(XoFilePassCrypto.healLoadedRow(0, message));
        message.params = new HashMap<>();
        message.params.put("someOther", "param");
        assertFalse(XoFilePassCrypto.healLoadedRow(0, message));
    }

    /** Builds a real (well-formed) XOSF2 envelope for a random CEK — the
     *  parse must succeed so the fileId routing works. */
    private static String lockedEnvelopeFor(long fileId) throws Exception {
        byte[][] minted = XoFilePassCrypto.mintCek();
        byte[] cek = minted[0];
        byte[] salt = minted[1];
        org.json.JSONObject inner = new org.json.JSONObject();
        inner.put("t", "m");
        inner.put("fk", XoSecretEnvelope.b64Encode(cek));
        inner.put("pl", 4096);
        inner.put("cs", 131072);
        inner.put("mi", "image/png");
        inner.put("na", "pic.png");
        String header = XoFilePassCrypto.encryptHeader(cek, inner.toString());
        org.json.JSONObject wrap = XoFilePassCrypto.wrapCek(
                cek, "رمز-test-85".toCharArray(), salt, XoFilePassCrypto.PBKDF2_ITERS);
        return XoFilePassCrypto.buildEnvelope(fileId, 4096, 131072, header, wrap);
    }
}

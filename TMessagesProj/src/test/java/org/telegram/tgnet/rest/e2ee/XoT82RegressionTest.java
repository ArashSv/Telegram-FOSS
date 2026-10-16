package org.telegram.tgnet.rest.e2ee;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.rest.RestChatIndex;
import org.telegram.tgnet.rest.XoTestEnv;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T82 — the root redesign of the two field-reported surfaces:
 *
 * <ol>
 *   <li>XOSF2 password-based encrypted files: wrap/unwrap under the
 *       user-typed رمز (PBKDF2), honest wrong-password rejection, encrypted
 *       header round-trip + tamper rejection, envelope build/parse.</li>
 *   <li>Chunk framing interop: the CEK drives the SAME XoE2EEMedia chunk
 *       AEAD the download path replays from pl/cs — sender and receiver
 *       must reconstruct byte-identical plaintext.</li>
 *   <li>The pw upload intent: CEK+salt+رمز mint-once stability across
 *       refreshes (process-death resume), drop.</li>
 *   <li>The locked-file registry + remembered-رمز store.</li>
 *   <li>THE SECRET-CHAT NO-DOWNGRADE CONTRACT: a type="secret" chat payload
 *       NEVER lands in the group map — with a peer it registers as secret,
 *       without a peer it stays secret-unknown; and a previously poisoned
 *       (group) id heals and is reported for purge.</li>
 * </ol>
 */
public class XoT82RegressionTest {

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
            File tmp = Files.createTempDirectory("xot82").toFile();
            org.telegram.messenger.ApplicationLoader.applicationContext =
                    new android.content.ContextWrapper(
                            org.telegram.messenger.ApplicationLoader.applicationContext) {
                        @Override
                        public File getFilesDir() {
                            return tmp;
                        }
                    };
        }
        XoSecretStore.resetForTests();
    }

    // ------------------------------------------------- 1. wrap / unwrap

    @Test
    public void wrapCek_roundTripsWithTheRightPassword() throws Exception {
        byte[][] minted = XoFilePassCrypto.mintCek();
        byte[] cek = minted[0];
        byte[] salt = minted[1];
        String pass = "رمز محرمانه #42";
        JSONObject wp = XoFilePassCrypto.wrapCek(cek, pass.toCharArray(), salt, XoFilePassCrypto.PBKDF2_ITERS);
        assertEquals("pbkdf2", wp.getString("k"));
        assertEquals(XoFilePassCrypto.PBKDF2_ITERS, wp.getInt("i"));
        assertArrayEquals(salt, XoSecretEnvelope.b64Decode(wp.getString("s")));
        assertArrayEquals("the CEK must come back identical through the password",
                cek, XoFilePassCrypto.unwrapCek(wp, pass.toCharArray()));
    }

    @Test
    public void wrapCek_wrongPasswordIsRejectedHonestly() throws Exception {
        byte[][] minted = XoFilePassCrypto.mintCek();
        JSONObject wp = XoFilePassCrypto.wrapCek(minted[0], "right-رمز".toCharArray(), minted[1],
                XoFilePassCrypto.PBKDF2_ITERS);
        try {
            XoFilePassCrypto.unwrapCek(wp, "wrong-رمز".toCharArray());
            throw new AssertionError("a wrong password MUST fail the GCM tag");
        } catch (XoFilePassCrypto.BadPassword expected) {
            // the honest wrong-رمز signal
        }
        // one-character mutation of the right password is still wrong
        try {
            XoFilePassCrypto.unwrapCek(wp, "right-رمزX".toCharArray());
            throw new AssertionError("a mutated password MUST fail");
        } catch (XoFilePassCrypto.BadPassword expected) {
        }
    }

    @Test
    public void wrapCek_malformedBlobsAreRejected() {
        try {
            XoFilePassCrypto.unwrapCek(null, "x".toCharArray());
            throw new AssertionError();
        } catch (Exception expected) {
        }
        try {
            XoFilePassCrypto.unwrapCek(new JSONObject(), "x".toCharArray());
            throw new AssertionError();
        } catch (Exception expected) {
        }
        try {
            JSONObject alien = new JSONObject();
            alien.put("k", "unknown-kdf");
            XoFilePassCrypto.unwrapCek(alien, "x".toCharArray());
            throw new AssertionError();
        } catch (Exception expected) {
        }
    }

    // ------------------------------------------------- 2. encrypted header

    @Test
    public void headerBlob_roundTripsAndRejectsTamper() throws Exception {
        byte[] cek = XoFilePassCrypto.mintCek()[0];
        String inner = "{\"t\":\"m\",\"na\":\"secret.pdf\",\"mi\":\"application/pdf\",\"cap\":\"سلام\"}";
        String hb = XoFilePassCrypto.encryptHeader(cek, inner);
        assertArrayEquals(inner.getBytes(StandardCharsets.UTF_8),
                XoFilePassCrypto.decryptHeader(cek, hb));

        // any tampered byte fails the GCM tag
        byte[] blob = XoSecretEnvelope.b64Decode(hb);
        blob[blob.length - 1] ^= 0x01;
        try {
            XoFilePassCrypto.decryptHeader(cek, XoSecretEnvelope.b64Encode(blob));
            throw new AssertionError("tampered header MUST fail");
        } catch (Exception expected) {
        }
        // a different CEK cannot open it (wrong password -> wrong CEK)
        byte[] otherCek = XoFilePassCrypto.mintCek()[0];
        try {
            XoFilePassCrypto.decryptHeader(otherCek, hb);
            throw new AssertionError("foreign CEK MUST fail");
        } catch (Exception expected) {
        }
    }

    // ------------------------------------------------- 3. envelope

    @Test
    public void envelope_roundTripsThroughParseAndPrefixRouting() throws Exception {
        byte[][] minted = XoFilePassCrypto.mintCek();
        JSONObject wp = XoFilePassCrypto.wrapCek(minted[0], "p@ss".toCharArray(), minted[1],
                XoFilePassCrypto.PBKDF2_ITERS);
        String hb = XoFilePassCrypto.encryptHeader(minted[0], "{\"t\":\"m\"}");
        String envelope = XoFilePassCrypto.buildEnvelope(155L, 1234L, 131072, hb, wp);

        assertTrue(XoSecretEnvelope.isFileEnvelope2(envelope));
        assertTrue(XoSecretEnvelope.isAnyEnvelope(envelope));
        assertFalse("XOSF2 must NOT be confused with the XOSF1 family",
                XoSecretEnvelope.isFileEnvelope(envelope));
        assertFalse("XOSF2 must NOT be confused with the XOSC1 family",
                XoSecretEnvelope.isEnvelope(envelope));

        XoFilePassCrypto.PwEnvelope env = XoFilePassCrypto.parse(envelope);
        assertNotNull(env);
        assertEquals(155L, env.fileId);
        assertEquals(1234L, env.plaintextLen);
        assertEquals(131072, env.chunkSize);
        assertEquals(hb, env.headerB64);
        assertNotNull(env.wrapJson);

        assertNull("garbage is rejected", XoFilePassCrypto.parse("XOSF2:not-base64!!"));
        assertNull("wrong family is rejected", XoFilePassCrypto.parse("XOSC1:AAAA"));
        assertNull("missing fields are rejected", XoFilePassCrypto.parse("XOSF2:" +
                XoSecretEnvelope.b64Encode("{\"v\":1}".getBytes(StandardCharsets.UTF_8))));
    }

    // ------------------------------------------------- 4. chunk framing interop

    @Test
    public void chunkFraming_senderAndReceiverReconstructByteIdenticalPlaintext() throws Exception {
        byte[] cek = XoFilePassCrypto.mintCek()[0];
        int chunkSize = 131072;
        // a multi-chunk plaintext: 1.5 chunks + exact-boundary case
        for (long plainLen : new long[]{(long) chunkSize + chunkSize / 2, chunkSize, 1L}) {
            byte[] plain = new byte[(int) plainLen];
            new java.security.SecureRandom().nextBytes(plain);

            // SENDER: upload-part aligned encryption (RestDispatcher.uploadPart)
            java.io.ByteArrayOutputStream cipher = new java.io.ByteArrayOutputStream();
            long chunks = (plainLen + chunkSize - 1) / chunkSize;
            for (int i = 0; i < chunks; i++) {
                int from = (int) (i * chunkSize);
                int to = (int) Math.min(plainLen, from + (long) chunkSize);
                cipher.write(XoE2EEMedia.encryptChunk(cek, i, java.util.Arrays.copyOfRange(plain, from, to)));
            }
            // RECEIVER: deterministic split replay (FileLoadOperation)
            byte[] cipherBytes = cipher.toByteArray();
            java.io.ByteArrayOutputStream decrypted = new java.io.ByteArrayOutputStream();
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(cipherBytes));
            for (int i = 0; i < chunks; i++) {
                int expect = (int) (i == chunks - 1 ? plainLen - (chunks - 1) * chunkSize + 16 : chunkSize + 16);
                byte[] frame = new byte[expect];
                in.readFully(frame);
                decrypted.write(XoE2EEMedia.decryptChunk(cek, i, frame));
            }
            assertArrayEquals("plainLen=" + plainLen, plain, decrypted.toByteArray());
        }
    }

    // ------------------------------------------------- 5. the pw upload intent

    @Test
    public void pwIntent_mintOnceStableAcrossRefreshes() throws Exception {
        XoSecretStore store = XoSecretStore.getInstance(0);
        String location = "/docs/t82-pw.bin";
        TLRPC.TL_document document = new TLRPC.TL_document();
        document.mime_type = "application/zip";
        TLRPC.TL_documentAttributeFilename fn = new TLRPC.TL_documentAttributeFilename();
        fn.file_name = "archive.zip";
        document.attributes.add(fn);

        store.noteUploadIntentPw(location, 10071L, null, document, "رمز-1");
        store.bindTreeUploadId(location, 8181L);

        assertTrue("pw tree carries a concrete key", store.uploadIsPwIntent(8181L));
        assertFalse("pw intents never need ss derivation",
                store.uploadNeedsDerivedKey(8181L));
        byte[] cek1 = store.uploadKeyForTree(8181L);
        byte[] salt1 = store.uploadSaltForTree(8181L);
        assertNotNull(cek1);
        assertEquals(XoFilePassCrypto.CEK_LEN, cek1.length);
        assertNotNull(salt1);
        assertEquals(XoFilePassCrypto.SALT_LEN, salt1.length);
        assertEquals("رمز-1", store.uploadPassForTree(8181L));
        assertEquals(10071L, store.uploadPeerForTree(8181L));

        // process-death resume re-notes the SAME location: cek/salt/رمز survive
        TLRPC.TL_document doc2 = new TLRPC.TL_document();
        doc2.mime_type = "video/mp4";
        store.noteUploadIntentPw(location, 10071L, null, doc2, "رمz-CHANGED");
        assertArrayEquals("the minted CEK is immutable across refreshes",
                cek1, store.uploadKeyForTree(8181L));
        assertArrayEquals(salt1, store.uploadSaltForTree(8181L));
        assertEquals("the original typed رمز is kept for the envelope build",
                "رمز-1", store.uploadPassForTree(8181L));
        assertEquals("refresh updates the manifest meta", "video/mp4",
                store.uploadManifest(8181L).getString("mi"));
        assertEquals("pw", store.uploadManifest(8181L).getString("kf"));

        store.dropUploadIntent(8181L);
        assertNull(store.uploadKeyForTree(8181L));
        assertNull(store.uploadPassForTree(8181L));
        assertFalse(store.uploadIsPwIntent(8181L));
    }

    // ------------------------------------------------- 6. locked registry + remembered رمز

    @Test
    public void lockedFileRegistry_gatesAndReleases() {
        XoSecretStore store = XoSecretStore.getInstance(0);
        assertFalse(store.isPwLockedFile(9991L));
        store.notePwLockedFile(9991L);
        assertTrue(store.isPwLockedFile(9991L));
        store.clearPwLockedFile(9991L);
        assertFalse(store.isPwLockedFile(9991L));
    }

    @Test
    public void chatFilePass_roundTripsAndForgets() {
        XoSecretStore store = XoSecretStore.getInstance(0);
        store.noteChatFilePass(5005L, "shared-رمز");
        assertEquals("shared-رمز", store.chatFilePass(5005L));
        store.forgetChatFilePass(5005L);
        assertNull(store.chatFilePass(5005L));
    }

    // ------------------------------------------------- 7. secret chats never become groups

    private static JSONObject secretChatJson(long id, Long peerId) throws Exception {
        JSONObject chat = new JSONObject();
        chat.put("id", id);
        chat.put("type", "secret");
        chat.put("mode", "secret");
        if (peerId != null) {
            JSONObject peer = new JSONObject();
            peer.put("id", peerId);
            peer.put("first_name", "SecretPeer");
            peer.put("username", "secretpeer");
            chat.put("peer", peer);
        }
        return chat;
    }

    @Test
    public void scanChats_secretWithPeerRegistersAsSecret_neverAsGroup() throws Exception {
        RestChatIndex index = RestChatIndex.getInstance(0);
        org.json.JSONArray arr = new org.json.JSONArray();
        arr.put(secretChatJson(3301L, 10071L));
        RestChatIndex.ScanResult scan = index.scanChats(arr);

        assertTrue("the chat MUST be secret", index.isSecret(3301L));
        assertTrue("no purge needed for a clean registration", scan.healedSecretChatIds.isEmpty());
    }

    @Test
    public void scanChats_peerlessSecretStaysUnknown_neverDowngradesToGroup() throws Exception {
        RestChatIndex index = RestChatIndex.getInstance(0);
        org.json.JSONArray arr = new org.json.JSONArray();
        arr.put(secretChatJson(3302L, null));
        RestChatIndex.ScanResult scan = index.scanChats(arr);

        // THE regression: the old scanner fell into the group fallback and
        // OVERWROTE chatTypes with "group" — the exact field-reported bug.
        assertTrue("type stays secret even without a peer", index.isSecret(3302L));
        assertTrue("a peerless secret registers nothing to purge", scan.healedSecretChatIds.isEmpty());
    }

    @Test
    public void scanChats_poisonedGroupHealsToSecret_andIsReportedForPurge() throws Exception {
        RestChatIndex index = RestChatIndex.getInstance(0);
        // simulate the t78-era poisoning: the id first arrived as a group
        JSONObject poisoned = new JSONObject();
        poisoned.put("id", 3303L);
        poisoned.put("type", "group");
        poisoned.put("title", "chat3303");
        org.json.JSONArray g = new org.json.JSONArray();
        g.put(poisoned);
        index.scanChats(g);
        assertFalse("precondition: poisoned into group type", index.isSecret(3303L));

        // now the FIXED backend ships the same id as type=secret + peer
        org.json.JSONArray arr = new org.json.JSONArray();
        arr.put(secretChatJson(3303L, 10005L));
        RestChatIndex.ScanResult scan = index.scanChats(arr);

        assertTrue("healed to secret", index.isSecret(3303L));
        assertTrue("the caller must purge the poisoned cached rows",
                scan.healedSecretChatIds.contains(3303L));
    }
}

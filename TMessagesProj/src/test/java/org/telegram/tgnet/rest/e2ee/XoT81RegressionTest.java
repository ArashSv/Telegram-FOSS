package org.telegram.tgnet.rest.e2ee;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.rest.TlJsonMapper;
import org.telegram.tgnet.rest.XoFixtures;
import org.telegram.tgnet.rest.XoTestEnv;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T81 — the encrypted-file feature (XOSF1) and the last-seen root fixes:
 *
 * <ol>
 *   <li>THE one symmetric file key: static-static ECDH derives the SAME
 *       32 bytes on both sides; different pairs/parts never collide.</li>
 *   <li>The XOSF1 wire family: ECIES round-trip, AAD/family separation from
 *       XOSC1 (cross-family opens fail), prefix routing helpers.</li>
 *   <li>The ss media inner: NO fk transported, "kf":"ss" marker, meta parse
 *       with the derived key, strict fk parse rejects ss inners.</li>
 *   <li>Chunk AEAD under the derived key round-trips (the at-rest blob is
 *       genuinely symmetric ciphertext — the user's server-side clause).</li>
 *   <li>The keyless ss upload intent lifecycle (derive-once at first part).</li>
 *   <li>REGRESSION GUARD for the T79 intent-gate bug: a normal (non-xoEnc)
 *       intent NEVER carries the ss marker, and the last-seen parse fix
 *       (flag 64 + offline mapping) holds.</li>
 * </ol>
 */
public class XoT81RegressionTest {

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
            File tmp = Files.createTempDirectory("xot81").toFile();
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

    // ---------------------------------------------------------- 1. the file key

    @Test
    public void fileKey_isSymmetricAcrossBothSides() {
        byte[][] a = XoSecretCrypto.generateKeyPair(); // Alice
        byte[][] b = XoSecretCrypto.generateKeyPair(); // Bob
        long alice = 10005L, bob = 10071L;

        // each side derives from ITS private key + the PEER's public key
        byte[] fromAlice = XoSecretCrypto.deriveFileKey(alice, a[0], a[1], bob, b[1]);
        byte[] fromBob = XoSecretCrypto.deriveFileKey(bob, b[0], b[1], alice, a[1]);
        assertNotNull(fromAlice);
        assertNotNull(fromBob);
        assertEquals(32, fromAlice.length);
        assertArrayEquals("both parties MUST hold the identical symmetric key", fromAlice, fromBob);

        // a different pair derives a different key
        byte[][] c = XoSecretCrypto.generateKeyPair();
        byte[] other = XoSecretCrypto.deriveFileKey(alice, a[0], a[1], 10099L, c[1]);
        assertFalse("key is pair-scoped", Arrays.equals(fromAlice, other));

        // malformed inputs fail closed (null, never a guessed key)
        assertNull(XoSecretCrypto.deriveFileKey(alice, null, a[1], bob, b[1]));
        assertNull(XoSecretCrypto.deriveFileKey(alice, a[0], a[1], bob, new byte[3]));
    }

    @Test
    public void fileKey_derivationIsDeterministic() {
        byte[][] a = XoSecretCrypto.generateKeyPair();
        byte[][] b = XoSecretCrypto.generateKeyPair();
        byte[] k1 = XoSecretCrypto.deriveFileKey(7L, a[0], a[1], 9L, b[1]);
        byte[] k2 = XoSecretCrypto.deriveFileKey(7L, a[0], a[1], 9L, b[1]);
        assertArrayEquals(k1, k2);
    }

    // ---------------------------------------------------------- 2. XOSF1 wire family

    @Test
    public void xosf1Envelope_roundTripsThroughBothSides() throws Exception {
        byte[][] sender = XoSecretCrypto.generateKeyPair();
        byte[][] recipient = XoSecretCrypto.generateKeyPair();
        long selfSender = 10005L, selfRecipient = 10071L;
        // the AAD chat context = the CANONICAL PAIR LOW ID — the one number
        // both perspectives agree on (dialog ids are perspective-relative!)
        long pairCtx = Math.min(selfSender, selfRecipient);
        String inner = XoSecretEnvelope.innerMediaSs(12345, 131072, "video/mp4",
                "clip.mp4", 640, 360, 42, "کپشن", true,
                new byte[32], 777, false);

        String envelope = XoSecretCrypto.encryptWire(XoSecretCrypto.WIRE_FILE,
                pairCtx, selfSender, selfRecipient, sender[0], sender[1], recipient[1],
                inner.getBytes(StandardCharsets.UTF_8));
        assertTrue("the file family carries the XOSF1 prefix",
                envelope.startsWith(XoSecretEnvelope.PREFIX_FILE));
        assertTrue(XoSecretEnvelope.isFileEnvelope(envelope));
        assertFalse(XoSecretEnvelope.isEnvelope(envelope));
        assertTrue(XoSecretEnvelope.isAnyEnvelope(envelope));

        // the receiver's perspective: its dialog id is the SENDER's id, yet
        // the AAD ctx it binds must be the same canonical low id
        byte[] opened = XoSecretCrypto.decryptWire(XoSecretCrypto.WIRE_FILE,
                Math.min(selfRecipient, selfSender), selfSender, selfRecipient,
                recipient[0], recipient[1], envelope);
        assertNotNull(opened);
        assertEquals(inner, new String(opened, StandardCharsets.UTF_8));
    }

    @Test
    public void xosf1_andXosc1_familiesNeverCrossOpen() throws Exception {
        byte[][] sender = XoSecretCrypto.generateKeyPair();
        byte[][] recipient = XoSecretCrypto.generateKeyPair();
        byte[] secret = "cross-family payload".getBytes(StandardCharsets.UTF_8);
        long pairCtx = 10005L; // min(10005, 10071)

        String fileEnvelope = XoSecretCrypto.encryptWire(XoSecretCrypto.WIRE_FILE,
                pairCtx, 10005L, 10071L, sender[0], sender[1], recipient[1], secret);
        String secretEnvelope = XoSecretCrypto.encryptWire(XoSecretCrypto.WIRE_SECRET,
                555L, 10005L, 10071L, sender[0], sender[1], recipient[1], secret);

        // an XOSF1 body replayed as XOSC1 (same keys) must NOT open: the wire
        // tag lives in the HKDF info AND the AAD
        assertNull(XoSecretCrypto.decryptWire(XoSecretCrypto.WIRE_SECRET,
                555L, 10005L, 10071L, recipient[0], recipient[1], fileEnvelope));
        assertNull(XoSecretCrypto.decryptWire(XoSecretCrypto.WIRE_FILE,
                pairCtx, 10005L, 10071L, recipient[0], recipient[1], secretEnvelope));

        // a WRONG pair context must not open either (AAD binding)
        assertNull(XoSecretCrypto.decryptWire(XoSecretCrypto.WIRE_FILE,
                99999L, 10005L, 10071L, recipient[0], recipient[1], fileEnvelope));
    }

    @Test
    public void xosc1LegacyPath_unchanged() throws Exception {
        byte[][] sender = XoSecretCrypto.generateKeyPair();
        byte[][] recipient = XoSecretCrypto.generateKeyPair();
        String inner = XoSecretEnvelope.innerText("سلام");
        String envelope = XoSecretCrypto.encrypt(555L, 10005L, 10071L,
                sender[0], sender[1], recipient[1], inner.getBytes(StandardCharsets.UTF_8));
        assertTrue(envelope.startsWith(XoSecretEnvelope.PREFIX));
        byte[] opened = XoSecretCrypto.decrypt(555L, 10005L, 10071L,
                recipient[0], recipient[1], envelope);
        assertEquals(inner, new String(opened, StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------- 3. ss media inner

    @Test
    public void innerMediaSs_carriesNoKeyAndParsesWithTheDerivedKey() throws Exception {
        byte[] derived = XoSecretCrypto.deriveFileKey(1L, XoSecretCrypto.generateKeyPair()[0],
                XoSecretCrypto.generateKeyPair()[1], 2L, XoSecretCrypto.generateKeyPair()[1]);
        String inner = XoSecretEnvelope.innerMediaSs(2048, 131072, "image/jpeg",
                "photo.jpg", 1920, 1080, 0, "caption", true,
                new byte[32], 42, true);
        JSONObject json = new JSONObject(inner);

        assertEquals("ss", json.getString("kf"));
        assertFalse("the file key is NOT transported", json.has("fk"));
        assertEquals(2048, json.getLong("pl"));
        assertEquals("caption", json.getString("cap"));
        assertEquals(1, json.getInt("an"));
        assertEquals(1, json.getInt("th"));

        // ss parse succeeds ONLY with the derived key
        XoSecret.MediaMeta meta = XoSecret.parseMediaMeta(json, derived);
        assertNotNull(meta);
        assertArrayEquals(derived, meta.fileKey);
        assertEquals("image/jpeg", meta.mime);
        assertEquals("photo.jpg", meta.name);
        assertTrue(meta.animated && meta.animatedKnown);
        assertEquals(42, meta.thumbFileId);

        // wrong key length rejected
        assertNull(XoSecret.parseMediaMeta(json, new byte[16]));
        // and the STRICT fk parse refuses an ss inner (no fk field)
        assertNull(XoSecret.parseMediaMeta(json));
    }

    @Test
    public void innerMedia_fkMode_stillCarriesTheKey() throws Exception {
        byte[] key = XoSecretCrypto.generateKeyPair()[1];
        String inner = XoSecretEnvelope.innerMedia(key, 10, 131072, null, null,
                0, 0, 0, null, false, null, 0, false);
        JSONObject json = new JSONObject(inner);
        assertFalse("secret-chat media keeps the transported-key shape", json.has("kf"));
        assertNotNull(XoSecret.parseMediaMeta(json));
    }

    // ---------------------------------------------------------- 4. chunks under the derived key

    @Test
    public void derivedKeyEncryptsChunks_roundTripAllParts() throws Exception {
        byte[][] a = XoSecretCrypto.generateKeyPair();
        byte[][] b = XoSecretCrypto.generateKeyPair();
        byte[] fileKey = XoSecretCrypto.deriveFileKey(10005L, a[0], a[1], 10071L, b[1]);
        assertNotNull(fileKey);

        // three parts: full, odd tail, 1-byte tail — the exact shape the
        // uploader streams; the receiver replays the same split by index
        int[][] parts = {{0, 131072}, {1, 4096}, {2, 1}};
        for (int[] part : parts) {
            byte[] plain = new byte[part[1]];
            new java.util.Random(part[0]).nextBytes(plain);
            byte[] ct = XoE2EEMedia.encryptChunk(fileKey, part[0], plain);
            assertEquals("chunk AEAD = plain + GCM tag (the nonce is HKDF-derived, not prefixed)",
                    plain.length + 16, ct.length);
            byte[] back = XoE2EEMedia.decryptChunk(fileKey, part[0], ct);
            assertArrayEquals(plain, back);
        }

        // a different pair's key cannot open the chunks (pair binding)
        byte[][] c = XoSecretCrypto.generateKeyPair();
        byte[] strangerKey = XoSecretCrypto.deriveFileKey(10005L, a[0], a[1], 20071L, c[1]);
        byte[] ct = XoE2EEMedia.encryptChunk(fileKey, 0, "top secret".getBytes(StandardCharsets.UTF_8));
        try {
            XoE2EEMedia.decryptChunk(strangerKey, 0, ct);
            throw new AssertionError("a foreign pair key must not decrypt");
        } catch (Exception expected) {
            // fail-closed is the contract
        }
    }

    // ---------------------------------------------------------- 5. ss upload intent lifecycle

    @Test
    public void ssIntentLifecycle_deriveOnceThenStable() throws Exception {
        XoSecretStore store = XoSecretStore.getInstance(0);
        String location = "/docs/t81-ss.bin";
        TLRPC.TL_document document = new TLRPC.TL_document();
        document.mime_type = "application/pdf";
        TLRPC.TL_documentAttributeFilename fn = new TLRPC.TL_documentAttributeFilename();
        fn.file_name = "report.pdf";
        document.attributes.add(fn);

        // mint: KEYLESS ss intent (main-thread safe)
        store.noteUploadIntentSs(location, 10071L, null, document);
        store.bindTreeUploadId(location, 4242L);
        assertTrue("needs derivation before the first part",
                store.uploadNeedsDerivedKey(4242L));
        assertNull("no key may exist yet", store.uploadKeyForTree(4242L));
        assertEquals("intent peer is the user id", 10071L, store.uploadPeerForTree(4242L));

        // the dispatcher derives once (simulated) and notes it
        byte[] derived = XoSecretCrypto.generateKeyPair()[1];
        store.noteDerivedUploadKey(4242L, derived);
        assertFalse(store.uploadNeedsDerivedKey(4242L));
        assertArrayEquals(derived, store.uploadKeyForTree(4242L));

        // re-derivation attempts NEVER overwrite (single-use key stability)
        byte[] intruder = XoSecretCrypto.generateKeyPair()[1];
        store.noteDerivedUploadKey(4242L, intruder);
        assertArrayEquals("the derived key is immutable once noted",
                derived, store.uploadKeyForTree(4242L));

        // the manifest carries the metadata for the envelope
        JSONObject manifest = store.uploadManifest(4242L);
        assertNotNull(manifest);
        assertEquals("application/pdf", manifest.getString("mi"));
        assertEquals("report.pdf", manifest.getString("na"));
        assertEquals("ss", manifest.getString("kf"));

        // drop cleans up
        store.dropUploadIntent(4242L);
        assertNull(store.uploadKeyForTree(4242L));
        assertFalse(store.uploadNeedsDerivedKey(4242L));
    }

    @Test
    public void ssIntent_refreshKeepsTheDerivedKey() throws Exception {
        XoSecretStore store = XoSecretStore.getInstance(0);
        String location = "/docs/t81-refresh.bin";
        store.noteUploadIntentSs(location, 10071L, null, null);
        store.bindTreeUploadId(location, 4343L);
        byte[] derived = XoSecretCrypto.generateKeyPair()[1];
        store.noteDerivedUploadKey(4343L, derived);

        // a process-death resume re-notes the SAME location: the key must survive
        TLRPC.TL_document document = new TLRPC.TL_document();
        document.mime_type = "video/mp4";
        store.noteUploadIntentSs(location, 10071L, null, document);
        assertArrayEquals(derived, store.uploadKeyForTree(4343L));
        assertEquals("video/mp4", store.uploadManifest(4343L).getString("mi"));
        store.dropUploadIntent(4343L);
    }

    // ---------------------------------------------------------- 6. last-seen + regression guards

    @Test
    public void parseUser_alwaysCarriesStatusAndFlag64() throws Exception {
        // a REAL backend row (fixture): flag 64 must now ride EVERY parsed user
        TLRPC.TL_user user = TlJsonMapper.parseUser(XoFixtures.obj("fixture_user_public"), false);
        assertEquals("flag 64 = status-present (the last-seen persistence contract)",
                64, user.flags & 64);
        assertNotNull("status object always planted (no NPE into legacy paths)", user.status);
        assertTrue(user.status instanceof TLRPC.TL_userStatusEmpty); // fixture has status:null
    }

    @Test
    public void ssIntent_neverMintedWithoutXoEnc_markerContract() throws Exception {
        // the T79 regression guard: a plain (random-key) intent — the ONLY kind
        // secret dialogs mint — carries NO ss marker, so cloud finalization can
        // never mistake it for an encrypted-file send
        XoSecretStore store = XoSecretStore.getInstance(0);
        String location = "/docs/t81-secret.bin";
        store.noteUploadIntent(location, 10071L, null, null);
        store.bindTreeUploadId(location, 4444L);
        JSONObject manifest = store.uploadManifest(4444L);
        assertNotNull(manifest);
        assertFalse("plain intents have NO kf marker", manifest.has("kf"));
        assertTrue("plain intents DO carry a random fk",
                manifest.optString("fk", "").length() > 0);
        // and the key exists immediately (no derive step on the secret path)
        assertNotNull(store.uploadKeyForTree(4444L));
        store.dropUploadIntent(4444L);
    }
}

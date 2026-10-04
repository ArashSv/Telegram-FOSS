package org.telegram.tgnet.rest.e2ee;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T71 — the REAL Signal Protocol test: two accounts (Alice, Bob) register
 * against a simulated backend, then exchange messages through the actual
 * facade (X3DH bootstrap on first contact + Double Ratchet per message).
 * Everything below runs against libsignal 2.8.1 — no mocks inside the
 * protocol path, only the transport is simulated.
 */
public class XoE2EEProtocolTest {

    private static final long ALICE_ID = 1001L;
    private static final long BOB_ID = 2002L;

    private E2eeTestEnv.FakeServer server;

    @Before
    public void setUp() throws Exception {
        // fresh temp dir + fresh singletons PER TEST: no cross-test key state
        E2eeTestEnv.reset();
        server = new E2eeTestEnv.FakeServer();
        E2eeTestEnv.bindBackend(0, server, ALICE_ID); // account 0 = Alice
        E2eeTestEnv.bindBackend(1, server, BOB_ID);   // account 1 = Bob
    }

    @Test
    public void registrationUploadsPublicKeysOnly() throws Exception {
        XoE2EE.getInstance(0).ensureRegistered();
        E2eeTestEnv.FakeServer.User alice = server.users.get(ALICE_ID);
        assertNotNull("server holds Alice's identity", alice.identityKey);
        assertEquals("the full OTK batch is uploaded at registration", 100, alice.oneTimeKeys.size());

        // nothing secret on the wire: the store blob holds the private key,
        // the server only ever saw public encodings
        assertTrue("identity key length is a public encoding",
                alice.identityKey.length == 32 || alice.identityKey.length == 33);
    }

    @Test
    public void firstMessagePerformsX3dhAndDecrypts() throws Exception {
        XoE2EE alice = XoE2EE.getInstance(0);
        XoE2EE bob = XoE2EE.getInstance(1);
        alice.ensureRegistered();
        bob.ensureRegistered();

        String envelope = alice.encryptText(BOB_ID, "سلام باب");
        assertTrue(XoE2EEEnvelope.isEnvelope(envelope));
        assertFalse("plaintext must never leak into the wire string",
                envelope.contains("سلام"));

        String inner = bob.decryptFromPeer(ALICE_ID, envelope);
        assertNotNull("Bob decrypts the prekey (X3DH) message", inner);
        assertEquals("سلام باب", new JSONObject(inner).optString("x"));
    }

    @Test
    public void doubleRatchetAdvancesPerMessage() throws Exception {
        XoE2EE alice = XoE2EE.getInstance(0);
        XoE2EE bob = XoE2EE.getInstance(1);
        alice.ensureRegistered();
        bob.ensureRegistered();

        // bootstrap
        String e1 = alice.encryptText(BOB_ID, "one");
        assertNotNull(bob.decryptFromPeer(ALICE_ID, e1));

        // ratchet forward both directions
        String e2 = alice.encryptText(BOB_ID, "two");
        String e3 = alice.encryptText(BOB_ID, "three");
        assertEquals("two", new JSONObject(bob.decryptFromPeer(ALICE_ID, e2)).optString("x"));
        assertEquals("three", new JSONObject(bob.decryptFromPeer(ALICE_ID, e3)).optString("x"));

        // distinct ciphertexts for the same plaintext (fresh message keys)
        String e4a = alice.encryptText(BOB_ID, "same");
        String e4b = alice.encryptText(BOB_ID, "same");
        assertNotEquals("same plaintext never reuses a ciphertext", e4a, e4b);
        assertEquals("same", new JSONObject(bob.decryptFromPeer(ALICE_ID, e4a)).optString("x"));
        assertEquals("same", new JSONObject(bob.decryptFromPeer(ALICE_ID, e4b)).optString("x"));

        // Bob replies (DH ratchet: new chains, post-compromise security)
        String r1 = bob.encryptText(ALICE_ID, "reply");
        String inner = alice.decryptFromPeer(BOB_ID, r1);
        assertEquals("reply", new JSONObject(inner).optString("x"));

        // ...and Alice continues on the NEW receiving chain
        String e5 = alice.encryptText(BOB_ID, "after-ratchet");
        assertEquals("after-ratchet", new JSONObject(bob.decryptFromPeer(ALICE_ID, e5)).optString("x"));
    }

    @Test
    public void outOfOrderDeliveryRecoversViaSkippedKeys() throws Exception {
        XoE2EE alice = XoE2EE.getInstance(0);
        XoE2EE bob = XoE2EE.getInstance(1);
        alice.ensureRegistered();
        bob.ensureRegistered();

        String e1 = alice.encryptText(BOB_ID, "m1");
        assertNotNull(bob.decryptFromPeer(ALICE_ID, e1));

        String e2 = alice.encryptText(BOB_ID, "m2");
        String e3 = alice.encryptText(BOB_ID, "m3");

        // server reorder: 3 arrives before 2 — the skipped-key machinery
        // must transparently recover BOTH, in any arrival order
        assertEquals("m3", new JSONObject(bob.decryptFromPeer(ALICE_ID, e3)).optString("x"));
        assertEquals("m2", new JSONObject(bob.decryptFromPeer(ALICE_ID, e2)).optString("x"));
    }

    @Test
    public void mediaEnvelopeCarriesKeysAndMetadata() throws Exception {
        XoE2EE alice = XoE2EE.getInstance(0);
        XoE2EE bob = XoE2EE.getInstance(1);
        alice.ensureRegistered();
        bob.ensureRegistered();

        byte[] fileKey = XoE2EEMedia.newFileKey();
        String inner = XoE2EEEnvelope.innerMedia(fileKey, 9999, 131072,
                "video/mp4", "clip.mp4", 1920, 1080, 42, "کپشن", false, null, 0);
        String envelope = alice.encryptForPeer(BOB_ID, inner);

        String decrypted = bob.decryptFromPeer(ALICE_ID, envelope);
        assertNotNull(decrypted);
        XoE2EE.MediaMeta meta = XoE2EE.parseMediaMeta(new JSONObject(decrypted));
        assertNotNull(meta);
        org.junit.Assert.assertArrayEquals("the file key travels ONLY inside the envelope",
                fileKey, meta.fileKey);
        assertEquals(9999, meta.plaintextLen);
        assertEquals("video/mp4", meta.mime);
        assertEquals("clip.mp4", meta.name);
        assertEquals(1920, meta.width);
        assertEquals("کپشن", meta.caption);
    }

    @Test
    public void safetyNumberIsStableAndPairBound() throws Exception {
        XoE2EE alice = XoE2EE.getInstance(0);
        XoE2EE bob = XoE2EE.getInstance(1);
        alice.ensureRegistered();
        bob.ensureRegistered();

        String a1 = alice.safetyNumber(ALICE_ID, BOB_ID);
        String a2 = alice.safetyNumber(ALICE_ID, BOB_ID);
        String b1 = bob.safetyNumber(BOB_ID, ALICE_ID);
        assertNotNull(a1);
        assertEquals("same keys -> same number on repeat", a1, a2);
        assertEquals("both sides derive the SAME 60 digits", a1, b1);
        assertEquals("60 digits (2x30, Signal display convention)", 60, a1.length());
    }

    @Test
    public void multiAccountIsolationSeparateIdentitiesAndSessions() throws Exception {
        XoE2EE alice = XoE2EE.getInstance(0);
        XoE2EE bob = XoE2EE.getInstance(1);
        alice.ensureRegistered();
        bob.ensureRegistered();

        String aliceEnvelope = alice.encryptText(BOB_ID, "from alice");
        String bobEnvelope = bob.encryptText(ALICE_ID, "from bob");

        assertNotNull(bob.decryptFromPeer(ALICE_ID, aliceEnvelope));
        assertNotNull(alice.decryptFromPeer(BOB_ID, bobEnvelope));

        // session loss (logout / reinstall wipe) renders further ratchet
        // traffic undecryptable — the neutral placeholder path, never a crash
        String followUp = alice.encryptText(BOB_ID, "after");
        assertNotNull(bob.decryptFromPeer(ALICE_ID, followUp));
        XoE2EEStore.getInstance(1).wipe();
        assertNull("a wiped store cannot open messages from the old session",
                bob.decryptFromPeer(ALICE_ID, alice.encryptText(BOB_ID, "next")));
    }
}

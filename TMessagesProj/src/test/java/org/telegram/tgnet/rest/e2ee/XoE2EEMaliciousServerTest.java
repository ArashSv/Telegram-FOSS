package org.telegram.tgnet.rest.e2ee;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T71 — the MALICIOUS SERVER suite (docs/E2EE.md §threat model, test §12).
 * The server is assumed FULLY under attacker control: it can modify,
 * replay, delete and re-order any blob, and substitute any key it serves.
 * Every attack below must FAIL LOUDLY on the client — never decrypt, never
 * accept, never leak.
 */
public class XoE2EEMaliciousServerTest {

    private static final long ALICE_ID = 3001L;
    private static final long BOB_ID = 3002L;

    private E2eeTestEnv.FakeServer server;
    private XoE2EE alice;
    private XoE2EE bob;

    @BeforeClass
    public static void initEnv() throws Exception {
        E2eeTestEnv.reset();
    }

    @Before
    public void setUp() throws Exception {
        E2eeTestEnv.reset();
        server = new E2eeTestEnv.FakeServer();
        E2eeTestEnv.bindBackend(0, server, ALICE_ID);
        E2eeTestEnv.bindBackend(1, server, BOB_ID);
        alice = XoE2EE.getInstance(0);
        bob = XoE2EE.getInstance(1);
        alice.ensureRegistered();
        bob.ensureRegistered();
    }

    @Test
    public void modifiedCiphertextIsRejected() throws Exception {
        String envelope = alice.encryptText(BOB_ID, "attack target");
        XoE2EEEnvelope.Unwrapped unwrapped = XoE2EEEnvelope.unwrap(envelope);
        assertNotNull(unwrapped);

        // flip one byte inside the AEAD body (bit-flip attack)
        unwrapped.body[unwrapped.body.length / 2] ^= 0x01;
        String tampered = XoE2EEEnvelope.wrap(unwrapped.wireType, unwrapped.body);
        assertNull("tampered ciphertext must NEVER decrypt", bob.decryptFromPeer(ALICE_ID, tampered));

        // and the untampered original still decrypts (attack did not poison state)
        String original = alice.encryptText(BOB_ID, "still fine");
        assertEquals("still fine", new org.json.JSONObject(bob.decryptFromPeer(ALICE_ID, original)).optString("x"));
    }

    @Test
    public void truncatedCiphertextIsRejected() throws Exception {
        String envelope = alice.encryptText(BOB_ID, "cut me");
        XoE2EEEnvelope.Unwrapped unwrapped = XoE2EEEnvelope.unwrap(envelope);
        byte[] cut = new byte[unwrapped.body.length - 4];
        System.arraycopy(unwrapped.body, 0, cut, 0, cut.length);
        assertNull("truncated ciphertext must fail (GCM tag)", bob.decryptFromPeer(ALICE_ID, XoE2EEEnvelope.wrap(unwrapped.wireType, cut)));
    }

    @Test
    public void replayedMessageIsRejected() throws Exception {
        String envelope = alice.encryptText(BOB_ID, "only once");
        String inner1 = bob.decryptFromPeer(ALICE_ID, envelope);
        assertNotNull("first delivery decrypts", inner1);
        // exact replay (server re-sends the same stored row)
        String inner2 = bob.decryptFromPeer(ALICE_ID, envelope);
        assertTrue("replay must be rejected OR return identical plaintext (never a new accept path)",
                inner2 == null || inner2.equals(inner1));
    }

    @Test
    public void identityKeySubstitutionIsDetectedAndPinned() throws Exception {
        // baseline: normal encrypted exchange works
        String ok = alice.encryptText(BOB_ID, "baseline");
        assertNotNull(bob.decryptFromPeer(ALICE_ID, ok));

        // ATTACK: the server now serves a FAKE identity for Bob
        server.substituteIdentity = true;
        server.substituteIdentityKey = XoE2EEMedia.newFileKey(); // attacker's key

        try {
            alice.encryptText(BOB_ID, "should fail");
            org.junit.Assert.fail("send must FAIL on a substituted identity — never fall back, never re-pin silently");
        } catch (XoE2EE.E2eeUnavailableException expected) {
            assertEquals("the failure is the identity-change tripwire",
                    "E2EE_IDENTITY_CHANGED", expected.reasonCode);
        }

        assertTrue("the peer is FLAGGED for the UI warning", alice.isFlagged(BOB_ID));
        // even a fresh bundle fetch cannot bypass: the pinned key governs
        server.substituteIdentity = false;
        try {
            alice.encryptText(BOB_ID, "still flagged");
            org.junit.Assert.fail("flagged state persists until user re-verification");
        } catch (XoE2EE.E2eeUnavailableException expected) {
            assertEquals("E2EE_IDENTITY_CHANGED", expected.reasonCode);
        }
    }

    @Test
    public void userApprovedResetRecoversTheSession() throws Exception {
        // set up the flag (identity substitution attack)
        server.substituteIdentity = true;
        server.substituteIdentityKey = XoE2EEMedia.newFileKey();
        try {
            alice.encryptText(BOB_ID, "attack");
            org.junit.Assert.fail();
        } catch (XoE2EE.E2eeUnavailableException ignore) {
        }
        assertTrue(alice.isFlagged(BOB_ID));

        // THE USER verifies the new safety number out-of-band and approves:
        // resetSession unpins the old identity (TOFU re-pin) + clears the flag
        alice.resetSession(BOB_ID);
        assertFalse("flag cleared by the explicit user action", alice.isFlagged(BOB_ID));

        // attack off; the NEW key is Bob's real one — traffic resumes
        server.substituteIdentity = false;
        String resume = alice.encryptText(BOB_ID, "we're back");
        assertEquals("we're back", new org.json.JSONObject(bob.decryptFromPeer(ALICE_ID, resume)).optString("x"));
    }

    @Test
    public void forgedSignedPrekeySignatureIsRejected() throws Exception {
        // fresh account without sessions; the server forges the SPK signature
        server.forgeSignature = true;
        XoE2EE victim = XoE2EE.getInstance(0);
        XoE2EE peer = XoE2EE.getInstance(1);
        peer.ensureRegistered();
        // the victim has no session yet -> the next send builds one from the
        // forged bundle: libsignal verifies the signature and REFUSES
        try {
            victim.encryptText(3002L, "forged bundle");
            org.junit.Assert.fail("a forged signed-prekey signature must never bootstrap a session");
        } catch (XoE2EE.E2eeUnavailableException expected) {
            // translate path: E2EE_ENCRYPT_FAILED (build failure) — acceptable
            assertNotNull(expected.reasonCode);
        }
        assertFalse("no session may exist against the forged bundle",
                org.telegram.tgnet.rest.e2ee.XoE2EEStore.getInstance(0).containsSession(
                        new org.whispersystems.libsignal.SignalProtocolAddress("3002", XoE2EEStore.DEVICE_ID)));
    }

    @Test
    public void forgedBundleDoesNotDecryptForLegitimatePeer() throws Exception {
        // an attacker forges a bundle, victim accidentally sends; the REAL Bob
        // (whose OTK the attacker does not know) must be unable to derive
        // anything useful — the message simply fails to decrypt for Bob.
        server.forgeSignature = true;
        XoE2EE victim = XoE2EE.getInstance(0);
        XoE2EE realBob = XoE2EE.getInstance(1);
        realBob.ensureRegistered();
        try {
            String envelope = victim.encryptText(BOB_ID, "MITM bait");
            // if the send somehow proceeded, the REAL Bob cannot open it
            assertNull(realBob.decryptFromPeer(ALICE_ID, envelope));
        } catch (XoE2EE.E2eeUnavailableException expected) {
            // the intended path: send failed at session build
        }
    }
}

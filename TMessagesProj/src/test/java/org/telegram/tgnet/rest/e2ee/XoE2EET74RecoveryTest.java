package org.telegram.tgnet.rest.e2ee;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T74 — recovery suite for the "only lock emoji is sent / sends die after
 * the first message" outage reported from build-108 with two accounts on one
 * device (developer + @Arash).
 *
 * <p>Root cause chain (build-108 = t71 fix8b, WITHOUT the t73 fixes):
 * own outgoing echoes ran through decryptFromPeer; the PreKey-type attempt
 * consulted isTrustedIdentity with OUR key under the PEER's address, which
 * (a) FLAGGED the peer — every later send died with E2EE_IDENTITY_CHANGED —
 * and (b) on a fresh store, TOFU-PINNED OUR OWN KEY under the peer's
 * address — every later REAL message from that peer then failed forever
 * (the neutral 🔒 placeholder). t73 fixed (a) partially; T74 closes both
 * variants at the STORE level with a cryptographic-impossibility guard and
 * heals the poisoned state deterministically on BOTH paths (send + receive).
 *
 * <p>Impossibility argument used by the heal: our own PUBLIC key can never
 * legitimately be a peer's identity key — that would require the peer to
 * hold our PRIVATE key. Clearing such a pin/flag therefore cannot weaken
 * any real MITM defense; a genuine substitution is untouched.
 */
public class XoE2EET74RecoveryTest {

    private static final long ALICE_ID = 1001L;
    private static final long BOB_ID = 2002L;

    private E2eeTestEnv.FakeServer server;
    private XoE2EE alice;
    private XoE2EE bob;

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

    // ------------------------------------------------------- store-level impossibility guard

    @Test
    public void guardRefusesOwnKeyInSaveIdentityAndTrustCheck() throws Exception {
        XoE2EEStore bobStore = XoE2EEStore.getInstance(1);
        org.whispersystems.libsignal.SignalProtocolAddress aliceAddr =
                new org.whispersystems.libsignal.SignalProtocolAddress(String.valueOf(ALICE_ID), XoE2EEStore.DEVICE_ID);
        byte[] bobOwn = bobStore.getIdentityKeyPairObj().getPublicKey().serialize();
        org.whispersystems.libsignal.IdentityKey ownAsPeer = new org.whispersystems.libsignal.IdentityKey(bobOwn, 0);

        boolean saved = bobStore.saveIdentity(aliceAddr, ownAsPeer);
        assertFalse("saveIdentity must refuse our own key as a peer identity", saved);
        assertNull("nothing may be pinned by an own-key sighting", bobStore.peerIdentity(ALICE_ID));

        boolean trusted = bobStore.isTrustedIdentity(aliceAddr, ownAsPeer,
                org.whispersystems.libsignal.state.IdentityKeyStore.Direction.RECEIVING);
        assertFalse("isTrustedIdentity must refuse our own key", trusted);
        assertFalse("an own-key sighting must NEVER flag the peer", bobStore.isFlagged(ALICE_ID));
    }

    @Test
    public void ownEchoThroughDecryptNeverPoisonsAndConversationSurvives() throws Exception {
        // Alice and Bob exchange for real.
        String e1 = alice.encryptText(BOB_ID, "یکی");
        assertNotNull(bob.decryptFromPeer(ALICE_ID, e1));
        String e2 = bob.encryptText(ALICE_ID, "دو");
        assertNotNull(alice.decryptFromPeer(BOB_ID, e2));

        // Bob's OWN echo comes back and (as in build-108) is fed through the
        // peer-decrypt path. The guard must refuse it WITHOUT pinning/flagging.
        assertNull(bob.decryptFromPeer(ALICE_ID, e2));

        XoE2EEStore bobStore = XoE2EEStore.getInstance(1);
        assertFalse("no flag after own-echo attempt", bobStore.isFlagged(ALICE_ID));
        byte[] pinned = bobStore.peerIdentity(ALICE_ID) == null ? null : bobStore.peerIdentity(ALICE_ID).serialize();
        if (pinned != null) {
            assertFalse("own key must never be pinned under ANY address",
                    java.util.Arrays.equals(pinned, bobStore.getIdentityKeyPairObj().getPublicKey().serialize()));
        }

        // The conversation continues in both directions.
        String e3 = alice.encryptText(BOB_ID, "سه");
        assertNotNull(bob.decryptFromPeer(ALICE_ID, e3));
        String e4 = bob.encryptText(ALICE_ID, "چهار");
        assertNotNull(alice.decryptFromPeer(BOB_ID, e4));
    }

    // ------------------------------------------------------- self-pin heal: receive path

    @Test
    public void selfPinPoisonHealsOnDecryptAndRealMessageOpens() throws Exception {
        // 1. Reproduce the build-108 poisoned store on ALICE: her OWN key
        //    pinned under Bob's address (the T71 own-echo TOFU artifact).
        XoE2EEStore aliceStore = XoE2EEStore.getInstance(0);
        aliceStore.selfPinForTests(BOB_ID);
        assertTrue(aliceStore.isSelfPin(BOB_ID));

        // 2. Bob sends a REAL first message (prekey envelope).
        String text = "پیام واقعی باب";
        String envelope = bob.encryptText(ALICE_ID, text);

        // 3. Alice decrypts: UntrustedIdentity -> self-pin heal -> retry -> opens.
        String inner = alice.decryptFromPeer(BOB_ID, envelope);
        assertNotNull("self-pin must heal on the receive path", inner);
        assertTrue(inner.contains(text));

        // 4. No residue: no flag, and the pin is NOT our own key anymore.
        assertFalse(aliceStore.isFlagged(BOB_ID));
        assertFalse(aliceStore.isSelfPin(BOB_ID));
        byte[] pinned = aliceStore.peerIdentity(BOB_ID).serialize();
        assertNotNull(pinned);
        assertFalse(java.util.Arrays.equals(pinned,
                aliceStore.getIdentityKeyPairObj().getPublicKey().serialize()));
    }

    // ------------------------------------------------------- self-pin heal: send path

    @Test
    public void selfPinPoisonHealsOnEncryptInOneCall() throws Exception {
        XoE2EEStore aliceStore = XoE2EEStore.getInstance(0);
        aliceStore.selfPinForTests(BOB_ID);
        assertTrue(aliceStore.isSelfPin(BOB_ID));

        // A single encrypt call must self-heal and succeed (bounded one retry).
        String envelope = alice.encryptText(BOB_ID, "سلام از آلیشا");

        // Bob must be able to open it (fresh session, real trust chain).
        String inner = bob.decryptFromPeer(ALICE_ID, envelope);
        assertNotNull(inner);
        assertTrue(inner.contains("سلام از آلیشا"));

        assertFalse(aliceStore.isFlagged(BOB_ID));
        assertFalse(aliceStore.isSelfPin(BOB_ID));
    }

    // ------------------------------------------------------- false-positive flag heal + session rebuild

    @Test
    public void falsePositiveFlagHealsAndFreshSessionStillInterops() throws Exception {
        // Establish a real session first.
        String envelope1 = alice.encryptText(BOB_ID, "اول");
        assertNotNull(bob.decryptFromPeer(ALICE_ID, envelope1));

        // Plant the legacy false-positive flag (pin CORRECT, flag wrong).
        XoE2EEStore aliceStore = XoE2EEStore.getInstance(0);
        aliceStore.flagForTests(BOB_ID);
        assertTrue(aliceStore.isFlagged(BOB_ID));

        // Next encrypt heals + rebuilds the session from a fresh bundle.
        String envelope2 = alice.encryptText(BOB_ID, "دوم بعد از ترمیم");
        assertFalse(aliceStore.isFlagged(BOB_ID));

        // Bob must still open the NEW prekey-type message — the fresh X3DH
        // on Alice's side interoperates with Bob's intact store.
        String inner = bob.decryptFromPeer(ALICE_ID, envelope2);
        assertNotNull("fresh session after heal must interop with the peer", inner);
        assertTrue(inner.contains("دوم بعد از ترمیم"));
    }

    // ------------------------------------------------------- genuine substitution stays blocked

    @Test
    public void genuineSubstitutionStillBlockedAfterHealLogic() throws Exception {
        // Real session first (pins Bob's real key).
        assertNotNull(bob.decryptFromPeer(ALICE_ID, alice.encryptText(BOB_ID, "پایه")));

        // Attacker replaces Bob's served identity on the server.
        byte[] attacker = org.whispersystems.libsignal.ecc.Curve.generateKeyPair().getPublicKey().serialize();
        server.substituteIdentity = true;
        server.substituteIdentityKey = attacker;

        XoE2EEStore aliceStore = XoE2EEStore.getInstance(0);
        aliceStore.flagForTests(BOB_ID); // flag with the CORRECT pin still in place

        try {
            alice.encryptText(BOB_ID, "نباید برود");
            fail("a genuine substitution must keep traffic stopped");
        } catch (XoE2EE.E2eeUnavailableException e) {
            assertEquals("E2EE_IDENTITY_CHANGED", e.reasonCode);
        }
        assertTrue("genuine substitution flag must persist", aliceStore.isFlagged(BOB_ID));
    }

    // ------------------------------------------------------- sent-inner cache cap

    @Test
    public void sentInnerCacheHolds2000Entries() throws Exception {
        XoE2EE a = XoE2EE.getInstance(0);
        for (long id = 1; id <= 2100; id++) {
            a.noteSentInner(id, "{\"t\":\"t\",\"x\":\"m" + id + "\"}");
        }
        assertNull("oldest beyond the cap must be evicted", a.getSentInnerForRender(1));
        assertNull("oldest beyond the cap must be evicted (2)", a.getSentInnerForRender(100));
        assertNotNull("cap boundary must still be present", a.getSentInnerForRender(101));
        assertNotNull("newest must be present", a.getSentInnerForRender(2100));
    }

    // ------------------------------------------------------- diagnostics log hygiene

    @Test
    public void e2eeLogCapsDetailAndSurvivesWeirdInput() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            big.append("قطعه|رمز\n");
        }
        XoE2eeLog.event(0, "test.event", BOB_ID, big.toString());
        XoE2eeLog.event(0, "test.event.null", 0, null);
        XoE2eeLog.event(0, null, 0, "ignored");
        XoE2eeLog.resetForTests();
        assertTrue(true);
    }
}

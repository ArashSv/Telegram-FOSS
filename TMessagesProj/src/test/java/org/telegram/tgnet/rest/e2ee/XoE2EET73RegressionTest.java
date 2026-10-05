package org.telegram.tgnet.rest.e2ee;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T73 — regression suite for the "no messages can be sent" defect class.
 *
 * <p>Root cause (proven against the REAL backend + libsignal in T73's
 * intersection harness): the sender's OWN outgoing echo was fed through
 * decryptFromPeer. libsignal can never re-open the sender's ciphertext, and
 * a PreKey-type attempt consults isTrustedIdentity with OUR OWN key under
 * the PEER's address — XoE2EEStore then FLAGGED the peer and every
 * subsequent send died with E2EE_IDENTITY_CHANGED.
 *
 * <p>Fixes pinned here:
 * <ul>
 *   <li>own echoes render from the sent-inner cache (never self-decrypt);</li>
 *   <li>false-positive flags auto-heal when the server still serves the
 *       pinned identity; genuine substitutions stay blocked.</li>
 * </ul>
 */
public class XoE2EET73RegressionTest {

    private static final long ALICE_ID = 1001L;
    private static final long BOB_ID = 2002L;

    private E2eeTestEnv.FakeServer server;

    @Before
    public void setUp() throws Exception {
        E2eeTestEnv.reset();
        server = new E2eeTestEnv.FakeServer();
        E2eeTestEnv.bindBackend(0, server, ALICE_ID);
        E2eeTestEnv.bindBackend(1, server, BOB_ID);
    }

    // ------------------------------------------------------- own-echo rendering

    @Test
    public void ownEchoRendersFromSentInnerCache() throws Exception {
        XoE2EE alice = XoE2EE.getInstance(0);
        XoE2EE bob = XoE2EE.getInstance(1);
        alice.ensureRegistered();
        bob.ensureRegistered();

        String text = "سلام باب — پیام اول";
        String envelope = alice.encryptText(BOB_ID, text);

        // the sender records the plaintext inner under the server message id
        alice.noteSentInner(631L, XoE2EEEnvelope.innerText(text));

        // the own-echo render path reads the cache — no decrypt is attempted
        String rendered = alice.getSentInnerForRender(631L);
        assertNotNull("own echo inner comes from the cache", rendered);
        assertEquals(text, new JSONObject(rendered).optString("x"));

        // and the trust store is untouched by the render (no flag, no pin churn)
        assertFalseTrust(alice);
    }

    @Test
    public void selfDecryptPoisonsWhichIsWhyMapperUsesTheCacheAndHealRecovers() throws Exception {
        XoE2EE alice = XoE2EE.getInstance(0);
        XoE2EE bob = XoE2EE.getInstance(1);
        alice.ensureRegistered();
        bob.ensureRegistered();

        String envelope = alice.encryptText(BOB_ID, "documenting reality");

        // libsignal reality (the T71 root cause): a direct self-decrypt of the
        // own prekey echo consults isTrustedIdentity with OUR key under the
        // peer's address — returning null AND flagging the peer. The T73
        // mapper never does this (own rows render from the cache); the assert
        // below pins the poison so the heal path is exercised too.
        assertNull("own ciphertext must not decrypt on the sender",
                alice.decryptFromPeer(BOB_ID, envelope));
        assertTrue("the direct self-decrypt flags the peer (documented poison)",
                alice.isFlagged(BOB_ID));

        // T73 heal: the server still serves the PINNED identity, so the next
        // send clears the false-positive flag and resumes traffic
        String next = alice.encryptText(BOB_ID, "after heal");
        assertNotNull("the heal unblocks sending", next);
        assertTrue("flag cleared by the heal", !alice.isFlagged(BOB_ID));
    }

    @Test
    public void sentInnerCacheEvictsOldestBeyondCap() {
        XoE2EE alice = XoE2EE.getInstance(0);
        for (int i = 0; i < 300; i++) {
            alice.noteSentInner(10_000L + i, "{\"t\":\"t\",\"x\":\"m" + i + "\"}");
        }
        assertEquals("oldest entries evicted", null, alice.getSentInnerForRender(10_000L));
        assertEquals("newest entries retained", "m299",
                new JSONObject(alice.getSentInnerForRender(10_299L)).optString("x"));
    }

    // ------------------------------------------------------------ flag healing

    @Test
    public void falsePositiveFlagHealsWhenServerIdentityUnchanged() throws Exception {
        XoE2EE alice = XoE2EE.getInstance(0);
        XoE2EE bob = XoE2EE.getInstance(1);
        alice.ensureRegistered();
        bob.ensureRegistered();

        // session build + pin (TOFU)
        String first = alice.encryptText(BOB_ID, "one");
        assertNotNull(bob.decryptFromPeer(ALICE_ID, first));

        // simulate the T71 false positive: a DIFFERENT identity is served
        // once, the trust strike flags Bob; then the server is honest again
        org.whispersystems.libsignal.ecc.ECPublicKey attackerKey =
                org.whispersystems.libsignal.ecc.Curve.generateKeyPair().getPublicKey();
        server.substituteIdentity = true;
        server.substituteIdentityKey = attackerKey.serialize();
        alice.forceNewSessionForTests(BOB_ID); // force a fresh bundle fetch
        try {
            alice.encryptText(BOB_ID, "two");
            fail("the substituted bundle must not bootstrap a session");
        } catch (XoE2EE.E2eeUnavailableException e) {
            assertEquals("E2EE_IDENTITY_CHANGED", e.reasonCode);
        }
        assertTrue("the strike flagged the peer", alice.isFlagged(BOB_ID));

        // server honest again (exactly the false-positive signature: the
        // served identity is back to the PINNED key) — the next send heals
        server.substituteIdentity = false;
        String three = alice.encryptText(BOB_ID, "three");
        assertNotNull("the healed session encrypts again", three);
        assertTrue("flag cleared by the heal", !alice.isFlagged(BOB_ID));
        String inner = bob.decryptFromPeer(ALICE_ID, three);
        assertEquals("three", new JSONObject(inner).optString("x"));
    }

    @Test
    public void genuineIdentityChangeStaysFlagged() throws Exception {
        XoE2EE alice = XoE2EE.getInstance(0);
        XoE2EE bob = XoE2EE.getInstance(1);
        alice.ensureRegistered();
        bob.ensureRegistered();

        String first = alice.encryptText(BOB_ID, "one");
        assertNotNull(bob.decryptFromPeer(ALICE_ID, first));

        // a PERSISTENT substitution = a genuine identity change on the server
        org.whispersystems.libsignal.ecc.ECPublicKey attackerKey =
                org.whispersystems.libsignal.ecc.Curve.generateKeyPair().getPublicKey();
        server.substituteIdentity = true;
        server.substituteIdentityKey = attackerKey.serialize();
        alice.forceNewSessionForTests(BOB_ID);
        try {
            alice.encryptText(BOB_ID, "two");
            fail("substitution must be refused");
        } catch (XoE2EE.E2eeUnavailableException e) {
            assertEquals("E2EE_IDENTITY_CHANGED", e.reasonCode);
        }

        // the hostile answer persists — the heal must NOT clear the flag
        try {
            alice.encryptText(BOB_ID, "three");
            fail("a genuine substitution keeps traffic stopped");
        } catch (XoE2EE.E2eeUnavailableException e) {
            assertEquals("E2EE_IDENTITY_CHANGED", e.reasonCode);
        }
        assertTrue("flag stays for a genuine change", alice.isFlagged(BOB_ID));
    }

    private void assertFalseTrust(XoE2EE alice) {
        assertTrue("peer not flagged", !alice.isFlagged(BOB_ID));
    }
}

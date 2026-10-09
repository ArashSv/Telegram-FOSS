package org.telegram.tgnet.rest.e2ee;

import org.json.JSONObject;
import org.telegram.tgnet.rest.XoTestEnv;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T78 — the protocol-level regression suite: the exact field-report bug
 * classes the dual-mode architecture is designed to kill, expressed as
 * executable contracts.
 */
public class XoT78RegressionTest {

    private static final long CHAT = 501;      // backend secret chat id
    private static final long ALICE = 10005;
    private static final long BOB = 10071;
    private static final long SELF_ID = 10004;

    @BeforeClass
    public static void init() {
        XoTestEnv.init();
    }

    @Test
    public void fullConversationScenario_statelessAcrossReparses() throws Exception {
        byte[][] alice = XoSecretCrypto.generateKeyPair();
        byte[][] bob = XoSecretCrypto.generateKeyPair();

        // the entire conversation, interleaved, both directions
        String m1 = XoSecretCrypto.encrypt(CHAT, ALICE, BOB, alice[0], alice[1], bob[1],
                XoSecretEnvelope.innerText("اولین پیام بدون قفل").getBytes(StandardCharsets.UTF_8));
        String m2 = XoSecretCrypto.encrypt(CHAT, BOB, ALICE, bob[0], bob[1], alice[1],
                XoSecretEnvelope.innerText("جواب سریع").getBytes(StandardCharsets.UTF_8));
        String m3 = XoSecretCrypto.encrypt(CHAT, ALICE, BOB, alice[0], alice[1], bob[1],
                XoSecretEnvelope.innerMedia(XoE2EEMedia.newFileKey(), 2048, 96 * 1024,
                        "image/jpeg", "photo.jpg", 800, 600, 0, "یک عکس", false, null, 0, false)
                        .getBytes(StandardCharsets.UTF_8));

        // the receiver parses EVERY row twice (history + sync race, scroll-back)
        // m1/m3 are ALICE->BOB (Bob's keys open them); m2 is BOB->ALICE
        for (int pass = 0; pass < 2; pass++) {
            byte[] p1 = XoSecretCrypto.decrypt(CHAT, ALICE, BOB, bob[0], bob[1], m1);
            assertNotNull(p1);
            assertEquals("اولین پیام بدون قفل", XoSecretEnvelope.parseInner(p1).optString("x"));
            byte[] p2 = XoSecretCrypto.decrypt(CHAT, BOB, ALICE, alice[0], alice[1], m2);
            assertNotNull(p2);
            assertEquals("جواب سریع", XoSecretEnvelope.parseInner(p2).optString("x"));
        }
        assertNotNull(XoSecretCrypto.decrypt(CHAT, ALICE, BOB, bob[0], bob[1], m3));
    }

    @Test
    public void legacyXoe1IsNotRecognizedByTheNewScheme() {
        String legacy = "XOE1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        assertFalse(XoSecretEnvelope.isEnvelope(legacy));
        assertNull(XoSecretEnvelope.bodyOf(legacy));
        // and the new prefix is not the old one
        assertTrue(XoSecretEnvelope.PREFIX.startsWith("XOSC1:"));
    }

    @Test
    public void animatedBitIsAlwaysWritten_t76Contract() throws Exception {
        String gif = XoSecretEnvelope.innerMedia(XoE2EEMedia.newFileKey(), 100, 96 * 1024,
                "video/mp4", "gif.mp4", 0, 0, 0, null, false, null, 0, true);
        String video = XoSecretEnvelope.innerMedia(XoE2EEMedia.newFileKey(), 100, 96 * 1024,
                "video/mp4", "video.mp4", 0, 0, 0, null, false, null, 0, false);
        assertEquals(1, new JSONObject(gif).optInt("an", -1));
        assertEquals("a plain video MUST carry an:0 (never untagged)", 0, new JSONObject(video).optInt("an", -1));
    }

    @Test
    public void envelopeTransportSizeStaysWithinTheWireCap() throws Exception {
        // the backend MAX_CONTENT_LENGTH is 16384; the client cap is 16000.
        // A maximum-size inner (long text + media manifest) must fit.
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            big.append("متن طولانی برای تست اندازه پاکت رمزی ");
        }
        byte[][] alice = XoSecretCrypto.generateKeyPair();
        byte[][] bob = XoSecretCrypto.generateKeyPair();
        String env = XoSecretCrypto.encrypt(CHAT, ALICE, BOB, alice[0], alice[1], bob[1],
                XoSecretEnvelope.innerText(big.toString()).getBytes(StandardCharsets.UTF_8));
        assertTrue("envelope must stay under the 16000-char wire cap, was " + env.length(),
                env.length() <= 16000);
    }

    @Test
    public void selfContainedEnvelope_recipientKeyIsTheOnlySecretNeeded() throws Exception {
        // the recipient's own key pair + envelope is enough: no peer key
        // fetch, no session store, no replay state — the T75 "chat corrupted"
        // and "first message locked" classes are structurally impossible
        byte[][] alice = XoSecretCrypto.generateKeyPair();
        byte[][] bob = XoSecretCrypto.generateKeyPair();
        String env = XoSecretCrypto.encrypt(CHAT, ALICE, BOB, alice[0], alice[1], bob[1],
                XoSecretEnvelope.innerText("self-contained").getBytes(StandardCharsets.UTF_8));
        // decrypt WITHOUT ever contacting the key registry (only own keys)
        byte[] plain = XoSecretCrypto.decrypt(CHAT, ALICE, BOB,
                bob[0], bob[1], env);
        assertNotNull(plain);
        assertEquals("self-contained", XoSecretEnvelope.parseInner(plain).optString("x"));
    }

    @Test
    public void crossSecretChatEnvelopeIsUndecryptable_aadBinding() throws Exception {
        // the same pair has TWO secret chats (Telegram allows re-creating);
        // an envelope from chat A must not open inside chat B even though
        // both sides hold identical long-term keys
        byte[][] alice = XoSecretCrypto.generateKeyPair();
        byte[][] bob = XoSecretCrypto.generateKeyPair();
        String envChat1 = XoSecretCrypto.encrypt(501, ALICE, BOB, alice[0], alice[1], bob[1],
                "chat one".getBytes(StandardCharsets.UTF_8));
        assertNull(XoSecretCrypto.decrypt(502, ALICE, BOB, bob[0], bob[1], envChat1));
        assertNotNull(XoSecretCrypto.decrypt(501, ALICE, BOB, bob[0], bob[1], envChat1));
    }
}

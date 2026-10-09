package org.telegram.tgnet.rest.e2ee;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T78 — the stateless ECIES contract: two-party round trips, sender
 * authentication, AAD binding, tamper resistance, and the media manifest
 * riding INSIDE the envelope (file/image/GIF).
 */
public class XoSecretCryptoTest {

    private static final long CHAT = 4242;
    private static final long ALICE = 10001;
    private static final long BOB = 10002;

    private static byte[][] keyPair() {
        return XoSecretCrypto.generateKeyPair();
    }

    @Test
    public void textRoundTrip_bothDirections() throws Exception {
        byte[][] alice = keyPair();
        byte[][] bob = keyPair();

        // Alice -> Bob
        String env1 = XoSecretCrypto.encrypt(CHAT, ALICE, BOB, alice[0], alice[1], bob[1],
                XoSecretEnvelope.innerText("سلام دنیا").getBytes(StandardCharsets.UTF_8));
        byte[] plain1 = XoSecretCrypto.decrypt(CHAT, ALICE, BOB, bob[0], bob[1], env1);
        assertNotNull(plain1);
        assertEquals("سلام دنیا", XoSecretEnvelope.parseInner(plain1).getString("x"));

        // Bob -> Alice
        String env2 = XoSecretCrypto.encrypt(CHAT, BOB, ALICE, bob[0], bob[1], alice[1],
                XoSecretEnvelope.innerText("reply").getBytes(StandardCharsets.UTF_8));
        byte[] plain2 = XoSecretCrypto.decrypt(CHAT, BOB, ALICE, alice[0], alice[1], env2);
        assertNotNull(plain2);
        assertEquals("reply", XoSecretEnvelope.parseInner(plain2).getString("x"));
    }

    @Test
    public void decryptionIsDeterministic_statelessReparse() throws Exception {
        // THE regression that killed the previous protocol: re-parsing the
        // same row must yield the identical plaintext, forever (no session
        // state, no replay window). Ten parses, one answer.
        byte[][] alice = keyPair();
        byte[][] bob = keyPair();
        String env = XoSecretCrypto.encrypt(CHAT, ALICE, BOB, alice[0], alice[1], bob[1],
                XoSecretEnvelope.innerText("stateless").getBytes(StandardCharsets.UTF_8));
        byte[] first = XoSecretCrypto.decrypt(CHAT, ALICE, BOB, bob[0], bob[1], env);
        for (int i = 0; i < 10; i++) {
            assertArrayEquals(first, XoSecretCrypto.decrypt(CHAT, ALICE, BOB, bob[0], bob[1], env));
        }
    }

    @Test
    public void firstMessageNeedsNoHandshake() throws Exception {
        // THE original field report (10071 -> 10005 first message locked):
        // with a pure public-key scheme the very FIRST message decrypts.
        byte[][] alice = keyPair();
        byte[][] bob = keyPair();
        String env = XoSecretCrypto.encrypt(CHAT, ALICE, BOB, alice[0], alice[1], bob[1],
                XoSecretEnvelope.innerText("first!").getBytes(StandardCharsets.UTF_8));
        assertNotNull(XoSecretCrypto.decrypt(CHAT, ALICE, BOB, bob[0], bob[1], env));
    }

    @Test
    public void wrongRecipientCannotDecrypt() throws Exception {
        byte[][] alice = keyPair();
        byte[][] bob = keyPair();
        byte[][] eve = keyPair();
        String env = XoSecretCrypto.encrypt(CHAT, ALICE, BOB, alice[0], alice[1], bob[1],
                "secret".getBytes(StandardCharsets.UTF_8));
        assertNull(XoSecretCrypto.decrypt(CHAT, ALICE, BOB, eve[0], eve[1], env));
    }

    @Test
    public void aadBindsEnvelopeToItsChat() throws Exception {
        // an envelope captured from one chat is undecryptable in any other
        byte[][] alice = keyPair();
        byte[][] bob = keyPair();
        String env = XoSecretCrypto.encrypt(CHAT, ALICE, BOB, alice[0], alice[1], bob[1],
                "secret".getBytes(StandardCharsets.UTF_8));
        assertNull(XoSecretCrypto.decrypt(CHAT + 1, ALICE, BOB, bob[0], bob[1], env));
        // and to the party pair
        assertNull(XoSecretCrypto.decrypt(CHAT, 99999, BOB, bob[0], bob[1], env));
        assertNull(XoSecretCrypto.decrypt(CHAT, ALICE, 99999, bob[0], bob[1], env));
    }

    @Test
    public void tamperingFailsClosed() throws Exception {
        byte[][] alice = keyPair();
        byte[][] bob = keyPair();
        String env = XoSecretCrypto.encrypt(CHAT, ALICE, BOB, alice[0], alice[1], bob[1],
                "secret".getBytes(StandardCharsets.UTF_8));
        byte[] body = XoSecretEnvelope.bodyOf(env);
        body[body.length - 1] ^= 0x01; // flip one ciphertext byte
        String tampered = XoSecretEnvelope.PREFIX + XoSecretEnvelope.b64Encode(body);
        assertNull(XoSecretCrypto.decrypt(CHAT, ALICE, BOB, bob[0], bob[1], tampered));
        // and a flipped sender key in the header (key substitution attempt)
        byte[] body2 = XoSecretEnvelope.bodyOf(env);
        body2[33] ^= 0x01;
        String substituted = XoSecretEnvelope.PREFIX + XoSecretEnvelope.b64Encode(body2);
        assertNull(XoSecretCrypto.decrypt(CHAT, ALICE, BOB, bob[0], bob[1], substituted));
    }

    @Test
    public void senderKeyChangeIsDetectable() throws Exception {
        byte[][] aliceOld = keyPair();
        byte[][] aliceNew = keyPair();
        byte[][] bob = keyPair();
        String envOld = XoSecretCrypto.encrypt(CHAT, ALICE, BOB, aliceOld[0], aliceOld[1], bob[1],
                "v1".getBytes(StandardCharsets.UTF_8));
        String envNew = XoSecretCrypto.encrypt(CHAT, ALICE, BOB, aliceNew[0], aliceNew[1], bob[1],
                "v2".getBytes(StandardCharsets.UTF_8));
        // the trusted key = old: the NEW envelope still DECRYPTS (self-contained),
        // but the mismatch is flagged — Telegram semantics
        assertNotNull(XoSecretCrypto.decrypt(CHAT, ALICE, BOB, bob[0], bob[1], envNew));
        assertFalse(XoSecretCrypto.senderKeyMatches(envNew, aliceOld[1]));
        assertTrue(XoSecretCrypto.senderKeyMatches(envOld, aliceOld[1]));
        assertNotEquals(Arrays.hashCode(XoSecretCrypto.envelopeSenderPub(envOld)),
                Arrays.hashCode(XoSecretCrypto.envelopeSenderPub(envNew)));
    }

    @Test
    public void mediaManifestRidesInsideTheEnvelope() throws Exception {
        // file/image/GIF: the REAL metadata + media key live ONLY inside the
        // encrypted payload; the wire sees an opaque blob + an opaque string.
        byte[][] alice = keyPair();
        byte[][] bob = keyPair();
        byte[] mediaKey = XoE2EEMedia.newFileKey();
        String inner = XoSecretEnvelope.innerMedia(mediaKey, 300 * 1024 + 12345, 96 * 1024,
                "video/mp4", "anim.mp4", 854, 480, 42, "کپشن ویدیو", true,
                XoE2EEMedia.newFileKey(), 777, true);
        String env = XoSecretCrypto.encrypt(CHAT, ALICE, BOB, alice[0], alice[1], bob[1],
                inner.getBytes(StandardCharsets.UTF_8));
        byte[] plain = XoSecretCrypto.decrypt(CHAT, ALICE, BOB, bob[0], bob[1], env);
        assertNotNull(plain);
        XoSecret.MediaMeta meta = XoSecret.parseMediaMeta(XoSecretEnvelope.parseInner(plain));
        assertNotNull(meta);
        assertArrayEquals(mediaKey, meta.fileKey);
        assertEquals(300 * 1024 + 12345L, meta.plaintextLen);
        assertEquals("video/mp4", meta.mime);
        assertEquals("anim.mp4", meta.name);
        assertEquals(854, meta.width);
        assertEquals(480, meta.height);
        assertEquals(42, meta.duration);
        assertEquals("کپشن ویدیو", meta.caption);
        assertTrue(meta.thumbEncrypted);
        assertNotNull(meta.thumbKey);
        assertEquals(777L, meta.thumbFileId);
        assertTrue("GIF flag must survive the envelope", meta.animated);
        assertTrue(meta.animatedKnown);
    }

    @Test
    public void realVideoIsNeverAGif_t76Contract() throws Exception {
        byte[][] alice = keyPair();
        byte[][] bob = keyPair();
        String inner = XoSecretEnvelope.innerMedia(XoE2EEMedia.newFileKey(), 1024, 96 * 1024,
                "video/mp4", "real-video.mp4", 1920, 1080, 300, "", false, null, 0, false);
        String env = XoSecretCrypto.encrypt(CHAT, ALICE, BOB, alice[0], alice[1], bob[1],
                inner.getBytes(StandardCharsets.UTF_8));
        byte[] plain = XoSecretCrypto.decrypt(CHAT, ALICE, BOB, bob[0], bob[1], env);
        XoSecret.MediaMeta meta = XoSecret.parseMediaMeta(XoSecretEnvelope.parseInner(plain));
        assertNotNull(meta);
        assertFalse("a tagged non-animated video must NOT become a GIF", meta.animated);
        assertTrue(meta.animatedKnown);
    }

    @Test
    public void mediaChunkRoundTripThroughEnvelopeKeys() throws Exception {
        // full media path: encrypt bytes with the manifest key (the exact
        // wire format the upload funnel uses), decrypt with the key from
        // the envelope manifest
        byte[][] alice = keyPair();
        byte[][] bob = keyPair();
        byte[] mediaKey = XoE2EEMedia.newFileKey();
        byte[] blob = new byte[100_000];
        new java.security.SecureRandom().nextBytes(blob);
        byte[] cipher = XoE2EEMedia.encryptChunk(mediaKey, 0, blob);

        String inner = XoSecretEnvelope.innerMedia(mediaKey, blob.length, 96 * 1024,
                "application/octet-stream", "file.bin", 0, 0, 0, null, false, null, 0, false);
        String env = XoSecretCrypto.encrypt(CHAT, ALICE, BOB, alice[0], alice[1], bob[1],
                inner.getBytes(StandardCharsets.UTF_8));
        byte[] plain = XoSecretCrypto.decrypt(CHAT, ALICE, BOB, bob[0], bob[1], env);
        XoSecret.MediaMeta meta = XoSecret.parseMediaMeta(XoSecretEnvelope.parseInner(plain));
        byte[] restored = XoE2EEMedia.decryptChunk(meta.fileKey, 0, cipher);
        assertArrayEquals(blob, restored);
    }

    @Test
    public void fingerprintIsSymmetricAndStable() {
        byte[][] alice = keyPair();
        byte[][] bob = keyPair();
        String f1 = XoSecretCrypto.safetyFingerprint(ALICE, alice[1], BOB, bob[1]);
        String f2 = XoSecretCrypto.safetyFingerprint(BOB, bob[1], ALICE, alice[1]);
        assertNotNull(f1);
        assertEquals(f1, f2);
        byte[][] mallory = keyPair();
        assertNotEquals(f1, XoSecretCrypto.safetyFingerprint(ALICE, alice[1], BOB, mallory[1]));
    }
}

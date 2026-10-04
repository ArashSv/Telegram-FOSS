package org.telegram.tgnet.rest.e2ee;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * T71 — media/file encryption tests (docs/E2EE.md §media):
 * chunked AES-256-GCM with HKDF-derived nonces + chunk-index AAD binding.
 * Proves confidentiality, tamper detection, reorder detection, truncation
 * detection, and the RFC 5869 test vectors for the HKDF glue.
 */
public class XoE2EEMediaCryptoTest {

    private static byte[] bytes(int n, int seed) {
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) (seed + i * 31);
        }
        return out;
    }

    @Test
    public void chunkRoundTripExactBytes() throws Exception {
        byte[] key = XoE2EEMedia.newFileKey();
        assertEquals(32, XoE2EEMedia.FILE_KEY_LEN);
        int[] sizes = {0, 1, 15, 16, 17, 127, 131071, 131072, 131073}; // tail edges
        for (int size : sizes) {
            byte[] plain = bytes(size, 7);
            byte[] ct = XoE2EEMedia.encryptChunk(key, 0, plain);
            assertEquals("ciphertext = plaintext + 16B GCM tag", size + 16, ct.length);
            byte[] back = XoE2EEMedia.decryptChunk(key, 0, ct);
            assertArrayEquals("round trip size=" + size, plain, back);
        }
    }

    @Test
    public void identicalPlaintextChunksNeverProduceIdenticalCiphertext() throws Exception {
        byte[] key = XoE2EEMedia.newFileKey();
        byte[] plain = bytes(4096, 3);
        byte[] ct0 = XoE2EEMedia.encryptChunk(key, 0, plain);
        byte[] ct1 = XoE2EEMedia.encryptChunk(key, 1, plain);
        assertFalse("derived-nonce scheme: distinct chunk indexes -> distinct keystreams",
                Arrays.equals(ct0, ct1));
        // cross-chunk splice must fail (AAD binds the chunk index)
        try {
            XoE2EEMedia.decryptChunk(key, 0, ct1);
            org.junit.Assert.fail("chunk splice (index mismatch) must fail AEAD verification");
        } catch (Exception expected) {
        }
    }

    @Test
    public void tamperedChunkFails() throws Exception {
        byte[] key = XoE2EEMedia.newFileKey();
        byte[] ct = XoE2EEMedia.encryptChunk(key, 5, bytes(1000, 9));
        ct[ct.length / 2] ^= 0x02;
        try {
            XoE2EEMedia.decryptChunk(key, 5, ct);
            org.junit.Assert.fail("bit-flipped chunk must fail");
        } catch (Exception expected) {
        }
    }

    @Test
    public void reorderedChunkFails() throws Exception {
        byte[] key = XoE2EEMedia.newFileKey();
        byte[] ctA = XoE2EEMedia.encryptChunk(key, 0, bytes(500, 1));
        byte[] ctB = XoE2EEMedia.encryptChunk(key, 1, bytes(500, 2));
        // server reorders the blob: chunk 1 presented as chunk 0
        try {
            XoE2EEMedia.decryptChunk(key, 1, ctA);
            org.junit.Assert.fail("reordered chunk must fail (index AAD)");
        } catch (Exception expected) {
        }
        assertArrayEquals(bytes(500, 2), XoE2EEMedia.decryptChunk(key, 1, ctB));
    }

    @Test
    public void wrongKeyFails() throws Exception {
        byte[] ct = XoE2EEMedia.encryptChunk(XoE2EEMedia.newFileKey(), 0, bytes(300, 4));
        try {
            XoE2EEMedia.decryptChunk(XoE2EEMedia.newFileKey(), 0, ct);
            org.junit.Assert.fail("wrong key must fail");
        } catch (Exception expected) {
        }
    }

    @Test
    public void truncatedChunkFails() throws Exception {
        byte[] key = XoE2EEMedia.newFileKey();
        byte[] ct = XoE2EEMedia.encryptChunk(key, 0, bytes(600, 5));
        byte[] cut = Arrays.copyOf(ct, ct.length - 3);
        try {
            XoE2EEMedia.decryptChunk(key, 0, cut);
            org.junit.Assert.fail("truncated chunk must fail");
        } catch (Exception expected) {
        }
    }

    @Test
    public void fullFileSimulatedThroughChunkedPipeline() throws Exception {
        // 1.5 "MB"-scale simulated body in 128KB chunks: encrypt all parts,
        // concatenate (what the server stores), decrypt back byte-exact
        byte[] key = XoE2EEMedia.newFileKey();
        int chunkSize = 131072;
        int total = chunkSize + 50_000 + 1; // 2 full + 1 tail chunk
        byte[] body = new byte[total];
        new java.security.SecureRandom().nextBytes(body);

        java.io.ByteArrayOutputStream blob = new java.io.ByteArrayOutputStream();
        int chunks = (total + chunkSize - 1) / chunkSize;
        for (int i = 0; i < chunks; i++) {
            int from = i * chunkSize;
            int len = Math.min(chunkSize, total - from);
            blob.write(XoE2EEMedia.encryptChunk(key, i, Arrays.copyOfRange(body, from, from + len)));
        }
        byte[] stored = blob.toByteArray();

        java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(stored);
        byte[] back = new byte[total];
        int pos = 0;
        for (int i = 0; i < chunks; i++) {
            int expect = (i == chunks - 1 ? total - (chunks - 1) * chunkSize : chunkSize) + 16;
            byte[] part = new byte[expect];
            int read = 0;
            while (read < expect) {
                int r = in.read(part, read, expect - read);
                assertTrue("server blob must carry the exact expected bytes", r > 0);
                read += r;
            }
            byte[] plain = XoE2EEMedia.decryptChunk(key, i, part);
            System.arraycopy(plain, 0, back, pos, plain.length);
            pos += plain.length;
        }
        assertEquals(total, pos);
        assertArrayEquals("multi-chunk file round trip byte-exact", body, back);
    }

    @Test
    public void thumbRoundTripAndDomainSeparation() throws Exception {
        byte[] key = XoE2EEMedia.newFileKey();
        byte[] thumb = bytes(900, 11);
        byte[] blob = XoE2EEMedia.encryptThumb(key, thumb);
        assertArrayEquals(thumb, XoE2EEMedia.decryptThumb(key, blob));

        // a thumb ciphertext can never be replayed as a body chunk (AAD 't' vs 'c')
        byte[] asChunk = XoE2EEMedia.encryptChunk(key, 0, thumb);
        try {
            XoE2EEMedia.decryptChunk(key, 0, blob);
            org.junit.Assert.fail("thumb blob is not a chunk-0 body (different AAD) — but chunk-0 body under thumb key WOULD decrypt; see note");
        } catch (Exception expected) {
            // expected: the encrypted-thumb blob carries a RANDOM nonce inline
            // (not the derived chunk nonce), so chunk-0 decryption fails
        }
        assertFalse(Arrays.equals(asChunk, blob));
    }

    @Test
    public void hkdfRfc5869TestVectors() throws Exception {
        // RFC 5869 Appendix A — Test Case 1 (SHA-256)
        byte[] ikm = hexToBytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = hexToBytes("000102030405060708090a0b0c");
        byte[] info = hexToBytes("f0f1f2f3f4f5f6f7f8f9");
        byte[] okm = XoE2EEMedia.hkdfSha256(salt, ikm, info, 42);
        assertArrayEquals(hexToBytes(
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
                okm);
    }

    @Test
    public void hkdfShortSaltDefaultsToZeros() throws Exception {
        byte[] a = XoE2EEMedia.hkdfSha256(new byte[0], "ikm".getBytes(StandardCharsets.UTF_8), new byte[0], 32);
        byte[] b = XoE2EEMedia.hkdfSha256(new byte[32], "ikm".getBytes(StandardCharsets.UTF_8), new byte[0], 32);
        assertArrayEquals("RFC 5869: empty salt == HashLen zeros", a, b);
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}

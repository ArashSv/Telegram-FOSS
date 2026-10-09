package org.telegram.tgnet.rest.e2ee;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T78 — X25519 correctness pinned by the OFFICIAL RFC 7748 test vectors.
 * The ladder is deterministic: a green KAT set is a proof of correctness,
 * not a smoke test.
 */
public class XoCurve25519Test {

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    @Test
    public void rfc7748_section5_vector1() {
        byte[] out = XoCurve25519.scalarmult(
                hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"));
        assertEquals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552", toHex(out));
    }

    @Test
    public void rfc7748_section5_vector2() {
        byte[] out = XoCurve25519.scalarmult(
                hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"),
                hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"));
        assertEquals("95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957", toHex(out));
    }

    @Test
    public void rfc7748_iterated_vector_1() {
        byte[] k = new byte[32];
        k[0] = 9;
        byte[] u = new byte[32];
        u[0] = 9;
        assertEquals("422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079",
                toHex(XoCurve25519.scalarmult(k, u)));
    }

    @Test
    public void rfc7748_section61_diffieHellman() {
        byte[] aPriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        byte[] bPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
        byte[] aPub = XoCurve25519.publicKeyFromPrivate(aPriv);
        byte[] bPub = XoCurve25519.publicKeyFromPrivate(bPriv);
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", toHex(aPub));
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", toHex(bPub));
        byte[] sharedA = XoCurve25519.scalarmult(aPriv, bPub);
        byte[] sharedB = XoCurve25519.scalarmult(bPriv, aPub);
        assertEquals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", toHex(sharedA));
        assertArrayEquals(sharedA, sharedB);
    }

    @Test
    public void keyPairsAreValidAndSymmetric() {
        for (int i = 0; i < 25; i++) {
            byte[][] kp = XoSecretCrypto.generateKeyPair();
            assertEquals(32, kp[0].length);
            assertEquals(32, kp[1].length);
            assertArrayEquals(kp[1], XoCurve25519.publicKeyFromPrivate(kp[0]));
            // symmetric agreement through the derived public keys
            byte[][] other = XoSecretCrypto.generateKeyPair();
            byte[] s1 = XoCurve25519.scalarmult(kp[0], other[1]);
            byte[] s2 = XoCurve25519.scalarmult(other[0], kp[1]);
            assertArrayEquals(s1, s2);
        }
    }

    @Test
    public void clampingIsStable() {
        // the SAME private key always derives the same public key (idempotence)
        byte[] priv = XoCurve25519.generatePrivateKey();
        byte[] pub1 = XoCurve25519.publicKeyFromPrivate(priv);
        byte[] pub2 = XoCurve25519.publicKeyFromPrivate(priv);
        assertArrayEquals(pub1, pub2);
        assertFalse(java.util.Arrays.equals(priv, pub1));
    }
}

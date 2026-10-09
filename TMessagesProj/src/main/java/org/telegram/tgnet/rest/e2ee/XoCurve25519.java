package org.telegram.tgnet.rest.e2ee;

import java.math.BigInteger;

/**
 * T78 — pure-Java X25519 (RFC 7748) scalar multiplication.
 *
 * <p>Why hand-carried: the previous stack pulled the whole Signal protocol
 * library for its curve primitive. The new secret-chat scheme needs exactly
 * ONE primitive (X25519) plus the HKDF/AES-GCM already provided by
 * {@link XoE2EEMedia} and the JCA. A dependency-free ~300-line port replaces
 * the library with less code than the build.gradle diff that removed it —
 * and keeps the JVM unit suite dependency-free.
 *
 * <p>Implementation: the RFC 7748 reference Montgomery ladder (MSB-first,
 * 4-element projective state) over 16-limb radix-2^16 field arithmetic with
 * lazy carrying — each primitive (add/sub/mul/square/invert) is simple,
 * constant-time in the swaps, and the WHOLE construction is pinned by the
 * official RFC 7748 test vectors (iter-1, §5.2 vectors, §6.1 DH) in
 * XoSecretCryptoTest. One scalar mult per message encrypt/decrypt — far
 * below a millisecond on any supported device.
 */
public final class XoCurve25519 {

    public static final int KEY_LEN = 32;

    private static final BigInteger P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));

    private XoCurve25519() {
    }

    // ------------------------------------------------------------- field ops

    private static long[] gf() {
        return new long[16];
    }

    private static long[] gf(long v) {
        long[] r = new long[16];
        r[0] = v;
        return r;
    }

    private static long[] gfCopy(long[] v) {
        long[] r = new long[16];
        System.arraycopy(v, 0, r, 0, 16);
        return r;
    }

    /** Carries every limb into [0, 2^16) with the limb-15 wrap folded back *38. */
    private static void car25519(long[] o) {
        long c = 0;
        for (int i = 0; i < 16; i++) {
            o[i] += c;
            c = o[i] >> 16;
            o[i] -= c << 16;
        }
        // the top carry represents c * 2^256 == c * 38 (mod p)
        o[0] += c * 38;
        c = o[0] >> 16;
        o[0] -= c << 16;
        o[1] += c;
        // one extra step keeps limb 1 bounded after the fold
        c = o[1] >> 16;
        o[1] -= c << 16;
        o[2] += c;
    }

    private static void add(long[] o, long[] a, long[] b) {
        for (int i = 0; i < 16; i++) {
            o[i] = a[i] + b[i];
        }
        car25519(o);
    }

    private static void sub(long[] o, long[] a, long[] b) {
        for (int i = 0; i < 16; i++) {
            o[i] = a[i] - b[i];
        }
        car25519(o);
    }

    private static void mul(long[] o, long[] a, long[] b) {
        long[] t = new long[31];
        for (int i = 0; i < 16; i++) {
            long ai = a[i];
            if (ai == 0) {
                continue;
            }
            for (int j = 0; j < 16; j++) {
                t[i + j] += ai * b[j];
            }
        }
        for (int i = 0; i < 15; i++) {
            t[i] += 38 * t[i + 16];
        }
        System.arraycopy(t, 0, o, 0, 16);
        car25519(o);
        car25519(o);
    }

    private static void sqr(long[] o, long[] a) {
        mul(o, a, a);
    }

    /** Constant-time conditional swap (swap != 0 swaps a and b). */
    private static void cswap(int swap, long[] a, long[] b) {
        long mask = ~(swap - 1); // 0 when swap==0, all-ones when swap==1
        for (int i = 0; i < 16; i++) {
            long t = mask & (a[i] ^ b[i]);
            a[i] ^= t;
            b[i] ^= t;
        }
    }

    /** o = a^(p-2) — Fermat inversion via square-and-multiply (MSB-first). */
    private static void invert(long[] o, long[] a) {
        // exponent p-2 = 2^255 - 21; plain MSB-first square-and-multiply
        long[] result = gf(1);
        long[] base = gfCopy(a);
        BigInteger e = P.subtract(BigInteger.TWO);
        for (int bit = 254; bit >= 0; bit--) {
            sqr(result, result);
            if (e.testBit(bit)) {
                mul(result, result, base);
            }
        }
        System.arraycopy(result, 0, o, 0, 16);
    }

    // ------------------------------------------------------------ pack/unpack

    private static long[] unpack25519(byte[] in) {
        long[] o = new long[16];
        for (int i = 0; i < 16; i++) {
            o[i] = (in[2 * i] & 0xffL) | ((in[2 * i + 1] & 0xffL) << 8);
        }
        o[15] &= 0x7fff; // u-coordinates carry the top bit clear (RFC 7748)
        return o;
    }

    private static void pack25519(byte[] o, long[] n) {
        long[] t = gfCopy(n);
        car25519(t);
        car25519(t);
        // Canonical reduction mod p = 2^255 - 19. After the carries the value
        // is a correct residue but may still sit at value = true + p (the
        // ladder leaves residues in [0, 2^256)); probe with +19:
        //   t >= p  <=>  t + 19 >= 2^255  <=>  bit 255 of (t + 19) is set
        long cy = 19;
        for (int i = 0; i < 16; i++) {
            t[i] += cy;
            cy = t[i] >> 16;
            t[i] -= cy << 16;
        }
        if ((t[15] & 0x8000) != 0) {
            // t >= p: t + 19 - 2^255 == t - p (canonical, and tiny)
            t[15] &= 0x7fff;
        } else {
            // t < p: undo the probe
            long borrow = 19;
            for (int i = 0; i < 16 && borrow != 0; i++) {
                t[i] -= borrow;
                if (t[i] < 0) {
                    t[i] += 1L << 16;
                    borrow = 1;
                } else {
                    borrow = 0;
                }
            }
        }
        // serialize 16 small limbs into 32 little-endian bytes with full
        // carry propagation — overflow bits are NEVER truncated
        byte[] buf = new byte[32];
        for (int i = 0; i < 16; i++) {
            long v = t[i];
            int idx = 2 * i;
            while (v != 0 && idx < 32) {
                int sum = (buf[idx] & 0xff) + (int) (v & 0xff);
                buf[idx] = (byte) sum;
                v = (v >>> 8) + (sum >>> 8);
                idx++;
            }
        }
        System.arraycopy(buf, 0, o, 0, 32);
    }

    // ---------------------------------------------------------------- public API

    /**
     * RFC 7748 X25519: out = scalar · uCoord (Montgomery ladder, MSB-first).
     *
     * @param scalar  32-byte scalar (clamped internally, as the RFC mandates)
     * @param uCoord  32-byte u-coordinate
     * @return 32-byte u-coordinate of the result
     */
    public static byte[] scalarmult(byte[] scalar, byte[] uCoord) {
        if (scalar == null || scalar.length != KEY_LEN || uCoord == null || uCoord.length != KEY_LEN) {
            throw new IllegalArgumentException("X25519 inputs must be 32 bytes");
        }
        byte[] k = new byte[KEY_LEN];
        System.arraycopy(scalar, 0, k, 0, KEY_LEN);
        k[0] &= 248;
        k[31] &= 127;
        k[31] |= 64;

        long[] x1 = unpack25519(uCoord);
        long[] x2 = gf(1);
        long[] z2 = gf();
        long[] x3 = gfCopy(x1);
        long[] z3 = gf(1);
        int swap = 0;

        long[] a = gf(), aa = gf(), b = gf(), bb = gf(), e = gf();
        long[] c = gf(), d = gf(), da = gf(), cb = gf(), t0 = gf(), t1 = gf();

        for (int t = 254; t >= 0; t--) {
            int kt = (k[t >>> 3] & 0xff) >>> (t & 7) & 1;
            swap ^= kt;
            cswap(swap, x2, x3);
            cswap(swap, z2, z3);
            swap = kt;

            add(a, x2, z2);          // A  = x2 + z2
            sqr(aa, a);              // AA = A^2
            sub(b, x2, z2);          // B  = x2 - z2
            sqr(bb, b);              // BB = B^2
            sub(e, aa, bb);          // E  = AA - BB
            add(c, x3, z3);          // C  = x3 + z3
            sub(d, x3, z3);          // D  = x3 - z3
            mul(da, d, a);           // DA = D * A
            mul(cb, c, b);           // CB = C * B
            add(t0, da, cb);         // t0 = DA + CB
            sqr(x3, t0);             // x3 = (DA + CB)^2
            sub(t1, da, cb);         // t1 = DA - CB
            sqr(z3, t1);             // z3 = (DA - CB)^2
            mul(z3, z3, x1);         // z3 = x1 * (DA - CB)^2
            mul(x2, aa, bb);         // x2 = AA * BB
            mul(t0, a24(), e);       // t0 = 121665 * E
            add(t1, aa, t0);         // t1 = AA + 121665 * E
            mul(z2, e, t1);          // z2 = E * (AA + 121665 * E)
        }
        cswap(swap, x2, x3);
        cswap(swap, z2, z3);

        long[] inv = gf();
        invert(inv, z2);
        mul(x2, x2, inv);
        byte[] out = new byte[KEY_LEN];
        pack25519(out, x2);
        return out;
    }

    private static long[] A24_LIMBS = null;

    private static long[] a24() {
        if (A24_LIMBS == null) {
            A24_LIMBS = gf();
            A24_LIMBS[0] = 121665 & 0xffff;
            A24_LIMBS[1] = 121665 >>> 16;
        }
        return A24_LIMBS;
    }

    /** @return the X25519 PUBLIC key for a 32-byte private key (base point u=9). */
    public static byte[] publicKeyFromPrivate(byte[] privateKey) {
        byte[] base = new byte[KEY_LEN];
        base[0] = 9; // RFC 7748: u = 9, little-endian
        return scalarmult(privateKey, base);
    }

    /** @return a fresh random 32-byte private key (pre-clamped). */
    public static byte[] generatePrivateKey() {
        byte[] key = new byte[KEY_LEN];
        new java.security.SecureRandom().nextBytes(key);
        key[0] &= 248;
        key[31] &= 127;
        key[31] |= 64;
        return key;
    }
}

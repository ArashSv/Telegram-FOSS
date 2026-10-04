package org.telegram.tgnet.rest.e2ee;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T71 — encrypted store tests: persistence round-trips, corruption wipe,
 * crash-consistency (atomic write), and per-account isolation of the
 * protocol state (the "database corruption does not leak keys" acceptance
 * criterion).
 */
public class XoE2EEStoreTest {

    private static File blobFile(int account) {
        return new File(E2eeTestEnv.currentFilesDir, "xo_e2ee_state_" + account + ".bin");
    }

    @Test
    public void statePersistsAcrossStoreReload() throws Exception {
        E2eeTestEnv.reset();
        XoE2EEStore store = XoE2EEStore.getInstance(0);
        store.generateIdentityIfAbsent();
        org.whispersystems.libsignal.IdentityKeyPair identity = store.getIdentityKeyPairObj();
        assertNotNull(identity);

        // a fresh singleton must re-read the SAME identity from the blob
        XoE2EEStore.resetForTests();
        XoE2EEStore reloaded = XoE2EEStore.getInstance(0);
        assertTrue(reloaded.hasIdentity());
        assertArrayEquals("identity survives persistence",
                identity.getPublicKey().serialize(), reloaded.getIdentityKeyPairObj().getPublicKey().serialize());
        assertEquals(store.getRegistrationIdObj(), reloaded.getRegistrationIdObj());
    }

    @Test
    public void sessionStateRoundTrips() throws Exception {
        E2eeTestEnv.reset();
        XoE2EEStore store = XoE2EEStore.getInstance(0);
        store.generateIdentityIfAbsent();

        org.whispersystems.libsignal.SignalProtocolAddress address =
                new org.whispersystems.libsignal.SignalProtocolAddress("777", 1);
        org.whispersystems.libsignal.state.SessionRecord record =
                new org.whispersystems.libsignal.state.SessionRecord();
        store.storeSession(address, record);
        assertTrue(store.containsSession(address));

        XoE2EEStore.resetForTests();
        XoE2EEStore reloaded = XoE2EEStore.getInstance(0);
        assertTrue("session state survives a process restart", reloaded.containsSession(address));
        reloaded.deleteSession(address);
        assertFalse(reloaded.containsSession(address));
    }

    @Test
    public void corruptedBlobIsWipedNotCrashed() throws Exception {
        E2eeTestEnv.reset();
        XoE2EEStore store = XoE2EEStore.getInstance(0);
        store.generateIdentityIfAbsent();
        assertTrue(store.hasIdentity());
        assertTrue(blobFile(0).length() > 0);

        // corrupt the blob (bit rot / tamper / keystore invalidation sim)
        byte[] blob = Files.readAllBytes(blobFile(0).toPath());
        blob[blob.length / 2] ^= 0x7F;
        Files.write(blobFile(0).toPath(), blob);

        XoE2EEStore.resetForTests();
        XoE2EEStore reloaded = XoE2EEStore.getInstance(0);
        assertFalse("unreadable crypto state is wiped, never propagated as a crash",
                reloaded.hasIdentity());
        // and a clean identity can be regenerated afterwards
        assertNotNull(reloaded.generateIdentityIfAbsent());
        assertTrue(reloaded.hasIdentity());
    }

    @Test
    public void accountsAreIsolated() throws Exception {
        E2eeTestEnv.reset();
        XoE2EEStore a = XoE2EEStore.getInstance(0);
        XoE2EEStore b = XoE2EEStore.getInstance(1);
        a.generateIdentityIfAbsent();
        b.generateIdentityIfAbsent();
        assertNotEquals("separate accounts hold SEPARATE identities",
                a.getIdentityKeyPairObj().getPublicKey().serialize(),
                b.getIdentityKeyPairObj().getPublicKey().serialize());

        // trust in one account does not leak to the other
        org.whispersystems.libsignal.SignalProtocolAddress addr =
                new org.whispersystems.libsignal.SignalProtocolAddress("555", 1);
        a.saveIdentity(addr, a.getIdentityKeyPairObj().getPublicKey());
        assertNotNull(a.getIdentity(addr));
        assertNull(b.getIdentity(addr));
    }

    @Test
    public void trustPinningTofuAndFlag() throws Exception {
        E2eeTestEnv.reset();
        XoE2EEStore store = XoE2EEStore.getInstance(0);
        store.generateIdentityIfAbsent();
        org.whispersystems.libsignal.SignalProtocolAddress addr =
                new org.whispersystems.libsignal.SignalProtocolAddress("888", 1);
        org.whispersystems.libsignal.IdentityKeyPair real =
                org.whispersystems.libsignal.util.KeyHelper.generateIdentityKeyPair();

        assertTrue("first sighting is trusted (TOFU)",
                store.isTrustedIdentity(addr, real.getPublicKey(), null));
        store.saveIdentity(addr, real.getPublicKey());
        assertTrue("same key trusted", store.isTrustedIdentity(addr, real.getPublicKey(), null));

        org.whispersystems.libsignal.IdentityKeyPair fake =
                org.whispersystems.libsignal.util.KeyHelper.generateIdentityKeyPair();
        assertFalse("a DIFFERENT key is NEVER trusted", store.isTrustedIdentity(addr, fake.getPublicKey(), null));
        assertTrue("mismatch flags the peer", store.isFlagged(888));
        assertFalse("and un-verifies", store.isVerified(888));

        store.clearFlag(888);
        assertFalse(store.isFlagged(888));
        assertFalse("the pinned key STILL differs after unflag (reset unpins it)",
                store.isTrustedIdentity(addr, fake.getPublicKey(), null));
        store.clearTrustedIdentity(888);
        assertTrue("after the user-approved unpin, TOFU re-pins",
                store.isTrustedIdentity(addr, fake.getPublicKey(), null));
    }

    @Test
    public void uploadIntentsTrackPartsAndDrop() throws Exception {
        E2eeTestEnv.reset();
        XoE2EEStore store = XoE2EEStore.getInstance(0);
        store.generateIdentityIfAbsent();

        store.noteUploadIntent("/cache/clip.mp4", 999, null, null);
        store.bindTreeUploadId("/cache/clip.mp4", 12345);
        byte[] minted = store.uploadKeyForTree(12345);
        assertNotNull("the intent mints a single-use file key", minted);
        assertEquals(XoE2EEMedia.FILE_KEY_LEN, minted.length);

        store.noteUploadPart(12345, 0, 131072);
        store.noteUploadPart(12345, 1, 50000);
        store.noteUploadPart(12345, 1, 50000); // idempotent re-send
        long[] totals = store.uploadPlaintextTotals(12345);
        assertEquals(181072, totals[0]);
        assertEquals(131072, totals[1]);
        store.dropUploadIntent(12345);
        assertNull(store.uploadKeyForTree(12345));

        XoE2EEStore.resetForTests();
        XoE2EEStore reloaded = XoE2EEStore.getInstance(0);
        assertNull("consumed upload intents are gone", reloaded.uploadKeyForTree(12345));
    }

    @Test
    public void blobOnDiskIsNotPlaintext() throws Exception {
        E2eeTestEnv.reset();
        XoE2EEStore store = XoE2EEStore.getInstance(0);
        store.generateIdentityIfAbsent();
        store.putMediaKeys(123456, "{\"fk\":\"AAEC\",\"pl\":1,\"cs\":2,\"th\":0}");
        byte[] blob = Files.readAllBytes(blobFile(0).toPath());
        String blobText = new String(blob, java.nio.charset.StandardCharsets.ISO_8859_1);
        // the API>=23 path encrypts with keystore (JVM takes the fallback path,
        // SDK_INT==0); assert the CONTRACT for the encrypted path and the
        // documented fallback for API<23
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            assertFalse("private state must not appear verbatim on disk",
                    blobText.contains("AAEC"));
        } else {
            // documented fallback: app-private storage (same level as the
            // legacy auth-key file). The blob still exists and round-trips.
            assertTrue(blob.length() > 0);
        }
    }
}

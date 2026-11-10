package org.telegram.tgnet.rest.e2ee;

import org.junit.BeforeClass;
import org.junit.Test;
import org.telegram.tgnet.rest.XoTestEnv;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T86 — regression pins for the SECRET-CHAT MEDIA DOWNLOAD fix.
 *
 * <p>Field report: secret-chat videos/GIFs (and files) fail to download /
 * play. Root cause chain: media keys lived in a 400-entry LRU inside the
 * keystore-protected blob; once evicted (or after a blob wipe), the finish
 * decrypt gate's "keys missing == not an E2EE file" fall-through renamed
 * raw CIPHERTEXT into the cache as the real file — the size/sha integrity
 * gate could not catch it because the server attestation is over the
 * ciphertext — and the poisoned cache file then shadowed every retry.
 *
 * <p>The fix has three layers, each pinned here:
 * <ol>
 *   <li><b>MediaKeyJournal</b> — unbounded persistent key store; eviction
 *       from the hot LRU no longer loses keys;</li>
 *   <li><b>XoE2eeFileRegistry</b> — plaintext keystore-independent record of
 *       which file ids are encrypted; survives blob wipes so the finish
 *       gate can FAIL-CLOSED (never again pass ciphertext through);</li>
 *   <li><b>poisoned-cache heal</b> — a cached file for a known-e2ee id that
 *       was never verifiably decrypted is deleted and re-downloaded (the
 *       gate decision itself runs in FileLoadOperation, which is not JVM
 *       instantiable — the registry semantics it consumes are pinned).</li>
 * </ol>
 */
public class XoT86RegressionTest {

    private static File filesDir;

    @BeforeClass
    public static void init() throws Exception {
        XoTestEnv.init();
        boolean needsFilesDir = false;
        try {
            needsFilesDir = org.telegram.messenger.ApplicationLoader.applicationContext == null
                    || org.telegram.messenger.ApplicationLoader.applicationContext.getFilesDir() == null;
        } catch (Throwable t) {
            needsFilesDir = true;
        }
        if (needsFilesDir) {
            filesDir = Files.createTempDirectory("xot86").toFile();
            org.telegram.messenger.ApplicationLoader.applicationContext =
                    new android.content.ContextWrapper(
                            org.telegram.messenger.ApplicationLoader.applicationContext) {
                        @Override
                        public File getFilesDir() {
                            return filesDir;
                        }
                    };
        } else {
            filesDir = org.telegram.messenger.ApplicationLoader.applicationContext.getFilesDir();
        }
        XoSecretStore.resetForTests();
    }

    private static String metaJson(long plainLen, int chunkSize, long thumbFileId) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"fk\":\"").append(XoSecretEnvelope.b64Encode(new byte[32])).append("\"");
        sb.append(",\"pl\":").append(plainLen);
        sb.append(",\"cs\":").append(chunkSize);
        if (thumbFileId > 0) {
            sb.append(",\"tf\":").append(thumbFileId);
        }
        sb.append("}");
        return sb.toString();
    }

    // ------------------------------------------------------------- 1. the journal

    @Test
    public void keysSurviveBeyondTheOldLruCap() {
        XoSecretStore store = XoSecretStore.getInstance(0);
        // 450 media messages × 1 body key — the pre-T86 LRU (400) evicted
        // the first ~50; the journal must retain EVERY one of them
        for (int i = 1; i <= 450; i++) {
            store.putMediaKeys(10_000L + i, metaJson(4096 + i, 131072, 0));
        }
        XoSecretStore.resetForTests(); // fresh instance = full reload
        XoSecretStore reloaded = XoSecretStore.getInstance(0);
        assertNotNull("first key evicted by the old LRU must survive (journal)",
                reloaded.getMediaKeys(10_001L));
        assertNotNull("last key must survive", reloaded.getMediaKeys(10_450L));
        // byte-exact round-trip
        assertEquals(metaJson(4096 + 42, 131072, 0), reloaded.getMediaKeys(10_042L));
    }

    @Test
    public void journalPersistsAcrossRestartAndHandlesTornTail() throws Exception {
        XoSecretStore store = XoSecretStore.getInstance(0);
        store.putMediaKeys(21_001, metaJson(8192, 65536, 0));
        store.putMediaKeys(21_002, metaJson(16384, 65536, 0));

        // torn tail: a crash mid-append leaves partial bytes after the last
        // well-formed record — everything before it must still load
        File journal = new File(filesDir, "xo_media_keys_0.bin");
        assertTrue(journal.exists());
        try (FileOutputStream out = new FileOutputStream(journal, true)) {
            out.write(new byte[]{(byte) 0xAB, (byte) 0xCD, 0x05}); // garbage tail
        }

        XoSecretStore.resetForTests();
        XoSecretStore reloaded = XoSecretStore.getInstance(0);
        assertEquals(metaJson(8192, 65536, 0), reloaded.getMediaKeys(21_001));
        assertEquals(metaJson(16384, 65536, 0), reloaded.getMediaKeys(21_002));
    }

    @Test
    public void journalMigrationCarriesPreT86HotCache() {
        // simulate the pre-T86 world: keys only in the blob's LRU. Put 450,
        // drop the journal (the pre-T86 builds had none), restart: the blob's
        // retained entries must migrate into the new journal on first use.
        XoSecretStore store = XoSecretStore.getInstance(0);
        for (int i = 1; i <= 450; i++) {
            store.putMediaKeys(30_000L + i, metaJson(1024 + i, 131072, 0));
        }
        File journal = new File(filesDir, "xo_media_keys_0.bin");
        assertTrue(journal.delete()); // pretend the journal never existed
        XoSecretStore.resetForTests();

        XoSecretStore migrated = XoSecretStore.getInstance(0);
        assertNull("entries the pre-T86 LRU already evicted are unrecoverable (expected)",
                migrated.getMediaKeys(30_001L));
        // an id the old LRU RETAINED must be reachable after migration...
        assertNotNull(migrated.getMediaKeys(30_400L));
        // ...and now durably journaled: a later restart keeps it
        XoSecretStore.resetForTests();
        assertNotNull(XoSecretStore.getInstance(0).getMediaKeys(30_400L));
        assertTrue(journal.exists()); // migration recreated the journal
    }

    @Test
    public void journalKeyMismatchSelfResets() {
        // a wiped store re-mints the journal key; records written by the LOST
        // key must reset the journal (not poison it with undecryptable rows)
        XoSecretStore store = XoSecretStore.getInstance(0);
        store.putMediaKeys(41_001, metaJson(2048, 131072, 0));
        // force a new journal key: wipe the BLOB only (journal survives on
        // disk but its key is gone from the store's perspective)
        File blob = new File(filesDir, "xo_secret_state_0.bin");
        assertTrue(blob.delete());
        XoSecretStore.resetForTests();

        XoSecretStore fresh = XoSecretStore.getInstance(0);
        assertNull("lost-key records must not resolve", fresh.getMediaKeys(41_001));
        // the journal must still be USABLE for new keys after the reset
        fresh.putMediaKeys(41_002, metaJson(4096, 131072, 0));
        XoSecretStore.resetForTests();
        assertEquals(metaJson(4096, 131072, 0),
                XoSecretStore.getInstance(0).getMediaKeys(41_002));
    }

    @Test
    public void putMediaKeysNotesRegistryBodyAndThumbIds() {
        XoSecretStore store = XoSecretStore.getInstance(0);
        store.putMediaKeys(50_001, metaJson(4096, 131072, 50_002));
        XoE2eeFileRegistry registry = XoE2eeFileRegistry.getInstance(0);
        assertTrue("body id must be a known e2ee blob", registry.known(50_001));
        assertTrue("thumb id must be a known e2ee blob", registry.known(50_002));
    }

    // ----------------------------------------------------------- 2. the registry

    @Test
    public void registryRoundTripAndSemantics() {
        XoE2eeFileRegistry registry = XoE2eeFileRegistry.getInstance(0);
        registry.note(60_001);
        registry.note(60_002);
        registry.markDecrypted(60_002);
        assertTrue(registry.known(60_001));
        assertTrue(registry.known(60_002));
        assertFalse("known-but-never-decrypted = the poisoned-cache signature",
                registry.isDecrypted(60_001));
        assertTrue(registry.isDecrypted(60_002));
        assertFalse(registry.known(60_999));
        assertFalse(registry.isDecrypted(60_999));

        XoE2eeFileRegistry.resetForTests(); // fresh instance = file reload
        XoE2eeFileRegistry reloaded = XoE2eeFileRegistry.getInstance(0);
        assertTrue(reloaded.known(60_001));
        assertTrue(reloaded.isDecrypted(60_002));
    }

    @Test
    public void registryToleratesCorruptTail() throws Exception {
        XoE2eeFileRegistry registry = XoE2eeFileRegistry.getInstance(0);
        registry.note(61_001);
        registry.markDecrypted(61_002);
        File file = new File(filesDir, "xo_e2ee_files_0.bin");
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(new byte[]{(byte) 0x99, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7F});
        }
        XoE2eeFileRegistry.resetForTests();
        XoE2eeFileRegistry reloaded = XoE2eeFileRegistry.getInstance(0);
        assertTrue("facts before the torn tail must survive", reloaded.known(61_001));
        assertTrue(reloaded.isDecrypted(61_002));
        assertFalse("garbage bytes must not become a fact", reloaded.known(Long.MAX_VALUE));
    }

    // ------------------------------------------------- 3. wipe semantics

    @Test
    public void logoutWipeClearsJournalAndRegistry() {
        XoSecretStore store = XoSecretStore.getInstance(0);
        store.putMediaKeys(70_001, metaJson(4096, 131072, 70_002));
        XoE2eeFileRegistry registry = XoE2eeFileRegistry.getInstance(0);
        assertTrue(registry.known(70_001));

        store.wipe(); // full logout wipe
        assertNull(store.getMediaKeys(70_001));
        assertFalse("logout clears the registry too", registry.known(70_001));
        assertFalse(new File(filesDir, "xo_e2ee_files_0.bin").exists());
        assertFalse(new File(filesDir, "xo_media_keys_0.bin").exists());
    }

    @Test
    public void blobCorruptWipeKeepsRegistryFailClosed() {
        // THE pin of the fail-closed backstop: when the blob/keystore dies,
        // the media keys die with it — but the registry (plaintext,
        // keystore-independent) must survive so the download gate can refuse
        // ciphertext for every file recorded before the wipe.
        XoSecretStore store = XoSecretStore.getInstance(0);
        store.putMediaKeys(80_001, metaJson(4096, 131072, 0));
        XoE2eeFileRegistry registry = XoE2eeFileRegistry.getInstance(0);
        assertTrue(registry.known(80_001));

        File blob = new File(filesDir, "xo_secret_state_0.bin");
        assertTrue(blob.exists());
        // corrupt the blob beyond JSON repair
        try (FileOutputStream out = new FileOutputStream(blob)) {
            out.write(new byte[]{0x00, 0x01, 0x02, 0x03, 0x04});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        XoSecretStore.resetForTests();
        XoSecretStore reloaded = XoSecretStore.getInstance(0); // load → corrupt → blob wipe
        assertNull("keys died with the blob", reloaded.getMediaKeys(80_001));
        // a FRESH registry instance (the reset also reset the singleton) must
        // reload the fact from the registry FILE — proving the registry
        // survives the blob wipe and the gate can refuse this ciphertext
        XoE2eeFileRegistry freshRegistry = XoE2eeFileRegistry.getInstance(0);
        assertTrue("REGISTRY MUST SURVIVE the blob wipe — the gate refuses ciphertext for this id",
                freshRegistry.known(80_001));
    }

    // ------------------------------------------- 4. panel entitlement mirror

    @Test
    public void parseSelfUserMirrorsPanelEntitlement() throws Exception {
        // v2.12.1 (T86): the panel grants users.multi_account; the self
        // payload carries it and parseUser(self) must mirror it per user id
        org.json.JSONObject self = new org.json.JSONObject()
                .put("id", 90_001L)
                .put("display_name", "Fairy")
                .put("multi_account", true);
        org.telegram.tgnet.TLRPC.TL_user u = org.telegram.tgnet.rest.TlJsonMapper.parseUser(self, true);
        assertTrue(XoSpecialAccounts.hasPanelEntitlement(90_001));
        assertEquals("90 001 mirrored from the self payload", u.id, 90_001);

        // truthy numeric form
        org.telegram.tgnet.rest.TlJsonMapper.parseUser(new org.json.JSONObject()
                .put("id", 90_002L)
                .put("display_name", "x")
                .put("multi_account", 1), true);
        assertTrue(XoSpecialAccounts.hasPanelEntitlement(90_002));

        // an older backend that omits the key CLEARs the mirror (the
        // entitlement is not sticky beyond the payload)
        org.telegram.tgnet.rest.TlJsonMapper.parseUser(new org.json.JSONObject()
                .put("id", 90_002L)
                .put("display_name", "x"), true);
        assertFalse(XoSpecialAccounts.hasPanelEntitlement(90_002));

        // public (non-self) payloads NEVER touch the mirror
        org.telegram.tgnet.rest.TlJsonMapper.parseUser(new org.json.JSONObject()
                .put("id", 90_003L)
                .put("display_name", "x")
                .put("multi_account", true), false);
        assertFalse(XoSpecialAccounts.hasPanelEntitlement(90_003));
    }
}

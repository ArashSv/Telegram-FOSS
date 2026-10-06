package org.telegram.tgnet.rest.e2ee;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.rest.TlJsonMapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T77 — regression suite for the field report "پیام‌ها با قفل میان / بعضی
 * چت‌ها خراب شدن" (messages arrive with a lock, some chats are broken).
 *
 * <p>Root causes pinned here:
 * <ol>
 *   <li><b>The 🔒 placeholder was persisted AS the message text and the
 *       ciphertext was discarded</b> — any transient decrypt failure
 *       permanently corrupted the chat (nothing left to decrypt when the
 *       session state healed). T77 keeps the envelope in a bounded
 *       locked-rows registry and the repair sweep re-opens it through the
 *       normal edit pipeline once protocol state improves.</li>
 *   <li><b>Decrypt idempotency was path-scoped</b> — the T75 row-id memo
 *       only covered the mapper; a second decrypt of the same ciphertext
 *       through any other path hit libsignal replay protection and produced
 *       a 🔒 overwrite. T77 memoizes by CIPHERTEXT CONTENT inside
 *       decryptFromPeer, so every path is idempotent and restart-safe.</li>
 *   <li><b>Logout wiped the whole protocol state</b> (T71 wipeLocal) — the
 *       identity reset broke every peer session and the wiped render caches
 *       turned the whole re-fetched history into permanent 🔒 rows. T77
 *       keeps per-account protocol state across logout/login (Signal-like);
 *       the repair sweep covers whatever a previous process left locked.</li>
 * </ol>
 */
public class XoT77PipelineTest {

    private static final long ALICE_ID = 1001L;
    private static final long BOB_ID = 2002L;

    @BeforeClass
    public static void setUpClass() throws Exception {
        E2eeTestEnv.reset();
    }

    @Before
    public void setUp() throws Exception {
        E2eeTestEnv.reset();
        E2eeTestEnv.FakeServer server = new E2eeTestEnv.FakeServer();
        E2eeTestEnv.bindBackend(0, server, ALICE_ID);
        E2eeTestEnv.bindBackend(1, server, BOB_ID);
        XoE2EE.getInstance(0).ensureRegistered();
        XoE2EE.getInstance(1).ensureRegistered();
    }

    private org.json.JSONObject rowFor(long id, long senderId, String envelope) throws Exception {
        org.json.JSONObject row = new org.json.JSONObject();
        row.put("id", id);
        row.put("sender_id", senderId);
        row.put("content", envelope);
        row.put("date", (int) (System.currentTimeMillis() / 1000L));
        return row;
    }

    /** A decrypt is idempotent across call paths: direct + mapper + repeat. */
    @Test
    public void decryptIsIdempotentAcrossPaths() throws Exception {
        String inner = XoE2EEEnvelope.innerText("پیام اول بدون قفل");
        String envelope = XoE2EE.getInstance(0).encryptForPeer(BOB_ID, inner);

        XoE2EE bob = XoE2EE.getInstance(1);
        String first = bob.decryptFromPeer(ALICE_ID, envelope);
        assertEquals(inner, first);

        // direct repeat — was DuplicateMessageException → null (🔒) before T77
        String second = bob.decryptFromPeer(ALICE_ID, envelope);
        assertEquals(inner, second);

        // mapper re-parse of the same server row returns the same text
        org.json.JSONObject row = rowFor(7001, ALICE_ID, envelope);
        TLRPC.TL_message parsed = TlJsonMapper.parseMessage(1, row, ALICE_ID, false, ALICE_ID, BOB_ID);
        assertEquals("پیام اول بدون قفل", parsed.message);
    }

    /**
     * THE core corruption bug: a decrypt that fails ONCE (whisper arrives
     * before its session's prekey) must NOT destroy the ciphertext; once the
     * session heals, the same ciphertext decrypts and the registry drops it.
     */
    @Test
    public void failedDecrypt_preservesCiphertextAndRepairs() throws Exception {
        String inner1 = XoE2EEEnvelope.innerText("اول");
        String inner2 = XoE2EEEnvelope.innerText("دوم");
        // alice's first message is PreKey-type, the second is a Whisper on
        // the same (fresh) receiving chain
        String envelope1 = XoE2EE.getInstance(0).encryptForPeer(BOB_ID, inner1);
        String envelope2 = XoE2EE.getInstance(0).encryptForPeer(BOB_ID, inner2);

        XoE2EE bob = XoE2EE.getInstance(1);
        XoE2EEStore bobStore = XoE2EEStore.getInstance(1);

        // simulates the out-of-order field case: the whisper is parsed first
        assertNull(bob.decryptFromPeer(ALICE_ID, envelope2));
        bobStore.noteLockedEnvelope(8002, ALICE_ID, envelope2);
        assertEquals(envelope2, bobStore.lockedEnvelopeFor(8002));

        // the prekey arrives: session builds, decrypts fine
        assertEquals(inner1, bob.decryptFromPeer(ALICE_ID, envelope1));

        // repair: the same locked ciphertext now opens (skipped-message-key
        // mechanism) and the registry drops the row
        assertEquals(inner2, bob.decryptFromPeer(ALICE_ID, envelope2));
        bobStore.dropLockedEnvelope(8002);
        assertNull(bobStore.lockedEnvelopeFor(8002));
    }

    /** The mapper registers locked rows WITH the ciphertext and clears them on success. */
    @Test
    public void mapperRegistersAndHealsLockedRows() throws Exception {
        String inner1 = XoE2EEEnvelope.innerText("hello");
        String inner2 = XoE2EEEnvelope.innerText("world");
        String envelope1 = XoE2EE.getInstance(0).encryptForPeer(BOB_ID, inner1);
        String envelope2 = XoE2EE.getInstance(0).encryptForPeer(BOB_ID, inner2);

        XoE2EEStore bobStore = XoE2EEStore.getInstance(1);

        // row 2 (whisper) is parsed first — decrypt fails
        TLRPC.TL_message locked = TlJsonMapper.parseMessage(1, rowFor(8102, ALICE_ID, envelope2),
                ALICE_ID, false, ALICE_ID, BOB_ID);
        assertEquals("🔒", locked.message);
        assertEquals(envelope2, bobStore.lockedEnvelopeFor(8102));

        // a re-parse while still locked must NOT lose the registry entry
        TLRPC.TL_message stillLocked = TlJsonMapper.parseMessage(1, rowFor(8102, ALICE_ID, envelope2),
                ALICE_ID, false, ALICE_ID, BOB_ID);
        assertEquals("🔒", stillLocked.message);
        assertEquals(envelope2, bobStore.lockedEnvelopeFor(8102));

        // the prekey (row 1) heals the session; the locked row re-parses clean
        assertEquals("hello", TlJsonMapper.parseMessage(1, rowFor(8101, ALICE_ID, envelope1),
                ALICE_ID, false, ALICE_ID, BOB_ID).message);
        TLRPC.TL_message repaired = TlJsonMapper.parseMessage(1, rowFor(8102, ALICE_ID, envelope2),
                ALICE_ID, false, ALICE_ID, BOB_ID);
        assertEquals("world", repaired.message);
        assertNull(bobStore.lockedEnvelopeFor(8102));
    }

    /** The content-keyed memo (and the repair registry) survive process death. */
    @Test
    public void memoAndRegistrySurviveStoreReload() throws Exception {
        String inner = XoE2EEEnvelope.innerText("ماندگار");
        String envelope = XoE2EE.getInstance(0).encryptForPeer(BOB_ID, inner);
        XoE2EEStore bobStore = XoE2EEStore.getInstance(1);
        assertNull(XoE2EE.getInstance(1).decryptFromPeer(ALICE_ID, envelope));
        // (first decrypt succeeded above — memo written; simulate a failure row too)
        bobStore.noteLockedEnvelope(8203, ALICE_ID, "XOE1:c2ltdWxhdGVk");

        XoE2EEStore.resetForTests();
        XoE2EEStore reloaded = XoE2EEStore.getInstance(1);
        assertEquals(inner, reloaded.getCipherMemo(envelope));
        assertEquals("XOE1:c2ltdWxhdGVk", reloaded.lockedEnvelopeFor(8203));
    }

    /** A fresh store instance (no wipe) keeps sessions + render caches alive. */
    @Test
    public void protocolStateSurvivesRelogin() throws Exception {
        String inner = XoE2EEEnvelope.innerText("after relogin");
        String envelope = XoE2EE.getInstance(0).encryptForPeer(BOB_ID, inner);
        XoE2EE bob = XoE2EE.getInstance(1);
        String decrypted = bob.decryptFromPeer(ALICE_ID, envelope);
        assertEquals(inner, decrypted);

        // simulate the logout→login cycle WITHOUT the T71 wipe: the same
        // per-account store re-loads; the session still decrypts the next
        // message and the sent-inner cache still renders own rows.
        XoE2EEStore.resetForTests();
        XoE2EEStore.getInstance(1);
        String inner3 = XoE2EEEnvelope.innerText("second after relogin");
        String envelope3 = XoE2EE.getInstance(0).encryptForPeer(BOB_ID, inner3);
        assertEquals(inner3, XoE2EE.getInstance(1).decryptFromPeer(ALICE_ID, envelope3));

        XoE2EEStore.getInstance(1).noteSentInner(91001, inner);
        XoE2EEStore.resetForTests();
        assertEquals(inner, XoE2EE.getInstance(1).getSentInnerForRender(91001));
        assertFalse("🔒".equals(inner));
    }
}

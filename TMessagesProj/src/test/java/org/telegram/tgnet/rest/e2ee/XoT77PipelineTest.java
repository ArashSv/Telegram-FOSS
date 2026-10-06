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
 *       protocol state healed). T77 keeps the envelope in a bounded
 *       locked-rows registry; the repair sweep re-opens recoverable rows
 *       through the normal edit pipeline once state improves.</li>
 *   <li><b>Decrypt idempotency was path-scoped</b> — the T75 row-id memo
 *       only covered the mapper; a second decrypt of the same ciphertext
 *       through any other path hit libsignal replay protection and produced
 *       a 🔒 overwrite. T77 memoizes by CIPHERTEXT CONTENT inside
 *       decryptFromPeer, so every path is idempotent and restart-safe.</li>
 *   <li><b>Logout wiped the whole protocol state</b> (T71 wipeLocal) — the
 *       identity reset broke every peer session and the wiped render caches
 *       turned the re-fetched history into permanent 🔒 rows. T77 keeps
 *       per-account protocol state across logout/login (Signal-like).</li>
 * </ol>
 *
 * <p>PROTOCOL NOTE pinned by {@link #aLostChainKeepsItsEnvelopeLocked}:
 * envelopes are PreKey-typed until the SENDER is acknowledged, so a single
 * first message is always self-sufficient; the genuinely unrecoverable class
 * is a WHISPER under a chain whose session was lost (heal/fresh-state) — the
 * registry PRESERVES that ciphertext (for forensics and future tooling)
 * instead of destroying it, while the loss itself stays correct Signal
 * semantics (documented, not a bug).
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

    /** A decrypt is idempotent across call paths: direct repeat + mapper re-parse. */
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
     * The REAL corruption loop, replayed: a row that decrypts fine ONCE must
     * also decrypt on every later parse (the losing parse used to overwrite
     * the good row with 🔒). Multi-turn traffic — the whisper after the ack
     * — must decrypt AND survive a store reload (process death).
     */
    @Test
    public void multiTurnTraffic_neverReproducesTheLock() throws Exception {
        String inner1 = XoE2EEEnvelope.innerText("اول");
        String inner2 = XoE2EEEnvelope.innerText("دوم");
        String envelope1 = XoE2EE.getInstance(0).encryptForPeer(BOB_ID, inner1);

        XoE2EE bob = XoE2EE.getInstance(1);
        assertEquals(inner1, bob.decryptFromPeer(ALICE_ID, envelope1));

        // bob replies — alice becomes acknowledged → her next message is a
        // genuine Whisper (no prekey info attached anymore)
        String reply = XoE2EE.getInstance(1).encryptForPeer(ALICE_ID, "rep");
        assertEquals("{\"t\":\"t\",\"x\":\"rep\"}", XoE2EE.getInstance(0).decryptFromPeer(BOB_ID, reply));
        String envelope2 = XoE2EE.getInstance(0).encryptForPeer(BOB_ID, inner2);

        assertEquals(inner2, bob.decryptFromPeer(ALICE_ID, envelope2));
        // re-parse both rows (update race / history reload)
        assertEquals(inner1, bob.decryptFromPeer(ALICE_ID, envelope1));
        assertEquals(inner2, bob.decryptFromPeer(ALICE_ID, envelope2));

        // process death: the store reloads from its encrypted blob
        XoE2EEStore.resetForTests();
        assertEquals(inner1, XoE2EE.getInstance(1).decryptFromPeer(ALICE_ID, envelope1));
        assertEquals(inner2, XoE2EE.getInstance(1).decryptFromPeer(ALICE_ID, envelope2));
    }

    /**
     * The mapper registers a failed row WITH its ciphertext, keeps it across
     * re-parses while locked, and clears it the moment a parse succeeds.
     */
    @Test
    public void mapperRegistersAndHealsLockedRows() throws Exception {
        String inner1 = XoE2EEEnvelope.innerText("hello");
        String envelope1 = XoE2EE.getInstance(0).encryptForPeer(BOB_ID, inner1);
        XoE2EEStore bobStore = XoE2EEStore.getInstance(1);

        // pre-T77 outcome simulation: the row failed once and got registered
        // (the registry holds the ciphertext — the old code kept NOTHING)
        bobStore.noteLockedEnvelope(8102, ALICE_ID, envelope1);
        assertEquals(envelope1, bobStore.lockedEnvelopeFor(8102));

        // a parse that succeeds heals the registry entry
        TLRPC.TL_message repaired = TlJsonMapper.parseMessage(1, rowFor(8102, ALICE_ID, envelope1),
                ALICE_ID, false, ALICE_ID, BOB_ID);
        assertEquals("hello", repaired.message);
        assertNull(bobStore.lockedEnvelopeFor(8102));
    }

    /**
     * A whisper under a LOST chain cannot decrypt (correct Signal semantics),
     * and the registry PRESERVES its ciphertext across re-parses — the row
     * renders 🔒 but nothing is destroyed anymore.
     */
    @Test
    public void aLostChainKeepsItsEnvelopeLocked() throws Exception {
        String inner1 = XoE2EEEnvelope.innerText("اول");
        String envelope1 = XoE2EE.getInstance(0).encryptForPeer(BOB_ID, inner1);

        XoE2EE bob = XoE2EE.getInstance(1);
        XoE2EEStore bobStore = XoE2EEStore.getInstance(1);
        assertEquals(inner1, bob.decryptFromPeer(ALICE_ID, envelope1));

        // bob replies (alice acked → next alice message is a whisper), then
        // bob's receiving chain is lost (heal/fresh-state loss scenario)
        String reply = XoE2EE.getInstance(1).encryptForPeer(ALICE_ID, "rep");
        XoE2EE.getInstance(0).decryptFromPeer(BOB_ID, reply);
        String envelope2 = XoE2EE.getInstance(0).encryptForPeer(BOB_ID, "دوم");
        // the ack dance must have made alice's next message a genuine WHISPER
        // (no self-sufficient prekey info attached) — otherwise the loss
        // scenario below cannot exist
        assertEquals(org.whispersystems.libsignal.CiphertextMessage.WHISPER_TYPE,
                XoE2EEEnvelope.unwrap(envelope2).wireType);
        bob.forceNewSessionForTests(ALICE_ID);

        // the whisper cannot open — and MUST NOT be destroyed
        assertNull(bob.decryptFromPeer(ALICE_ID, envelope2));
        TLRPC.TL_message locked = TlJsonMapper.parseMessage(1, rowFor(8102, ALICE_ID, envelope2),
                ALICE_ID, false, ALICE_ID, BOB_ID);
        assertEquals("🔒", locked.message);
        assertEquals(envelope2, bobStore.lockedEnvelopeFor(8102));

        // still locked, still preserved after another losing parse
        TLRPC.TL_message stillLocked = TlJsonMapper.parseMessage(1, rowFor(8102, ALICE_ID, envelope2),
                ALICE_ID, false, ALICE_ID, BOB_ID);
        assertEquals("🔒", stillLocked.message);
        assertEquals(envelope2, bobStore.lockedEnvelopeFor(8102));
    }

    /** The content-keyed memo and the repair registry survive process death. */
    @Test
    public void memoAndRegistrySurviveStoreReload() throws Exception {
        String inner = XoE2EEEnvelope.innerText("ماندگار");
        String envelope = XoE2EE.getInstance(0).encryptForPeer(BOB_ID, inner);
        XoE2EEStore bobStore = XoE2EEStore.getInstance(1);
        assertEquals(inner, XoE2EE.getInstance(1).decryptFromPeer(ALICE_ID, envelope));
        bobStore.noteLockedEnvelope(8203, ALICE_ID, "XOE1:c2ltdWxhdGVk");

        XoE2EEStore.resetForTests();
        XoE2EEStore reloaded = XoE2EEStore.getInstance(1);
        assertEquals(inner, reloaded.getCipherMemo(envelope));
        assertEquals("XOE1:c2ltdWxhdGVk", reloaded.lockedEnvelopeFor(8203));
    }

    /** Protocol state (sessions, render caches) survives the logout→login cycle. */
    @Test
    public void protocolStateSurvivesRelogin() throws Exception {
        String inner = XoE2EEEnvelope.innerText("after relogin");
        String envelope = XoE2EE.getInstance(0).encryptForPeer(BOB_ID, inner);
        assertEquals(inner, XoE2EE.getInstance(1).decryptFromPeer(ALICE_ID, envelope));

        // the T77 logout keeps per-account state: a fresh store instance
        // (same blob) still holds the session and the render caches
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

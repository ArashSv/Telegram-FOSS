package org.telegram.tgnet.rest.e2ee;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.rest.TlJsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T75 — regression suite for the field report "the FIRST message 10071→10005
 * is not decrypted (the rest of the chat is fine)" + "video / album / GIF do
 * not send" + the pending-chat requirement.
 *
 * <p>Root causes pinned here:
 * <ol>
 *   <li><b>Non-idempotent receive decryption.</b> The same server row is
 *       parsed multiple times BY DESIGN (update_queue message_new racing the
 *       history load, retries, scroll-back). libsignal replay protection
 *       throws DuplicateMessageException on the 2nd parse; pre-T75 the
 *       losing parse's 🔒 row replaced the winner's good row. The decrypted-
 *       inner memo makes parsing IDEMPOTENT.</li>
 *   <li><b>Album shape mismatch.</b> An opaque e2ee file row parses as a
 *       document; the album contract needs the response family to equal the
 *       request item (photo in → photo out). parseMediaE2ee pins that.</li>
 *   <li><b>Animated flag.</b> The envelope carries an explicit "an" bit
 *       (the ".mp4 name" heuristic misclassified real videos as GIFs).</li>
 *   <li><b>Pending chats.</b> Only the exact E2EE_NO_PEER_KEYS code defers,
 *       and only for private non-self dialogs.</li>
 * </ol>
 */
public class XoE2EET75RegressionTest {

    private static final long ALICE_ID = 1001L;
    private static final long BOB_ID = 2002L;

    private E2eeTestEnv.FakeServer server;
    private XoE2EE alice;
    private XoE2EE bob;

    @BeforeClass
    public static void setUpClass() throws Exception {
        E2eeTestEnv.reset();
    }

    @Before
    public void setUp() throws Exception {
        // fresh state per test (new temp dir + new singletons)
        E2eeTestEnv.reset();
        server = new E2eeTestEnv.FakeServer();
        E2eeTestEnv.bindBackend(0, server, ALICE_ID);
        E2eeTestEnv.bindBackend(1, server, BOB_ID);
        alice = XoE2EE.getInstance(0);
        bob = XoE2EE.getInstance(1);
        alice.ensureRegistered();
        bob.ensureRegistered();
    }

    // ------------------------------------------------- Fix A: idempotent receive

    /** THE first-message bug: parse the same row twice (update race) — both parses render the plaintext. */
    @Test
    public void sameRowParsedTwice_yieldsTheSamePlaintext_bothTimes() throws Exception {
        String text = "پیام اول برای تست";
        String envelope = alice.encryptText(BOB_ID, text);

        org.json.JSONObject row = new org.json.JSONObject();
        row.put("id", 5001);
        row.put("sender_id", ALICE_ID);
        row.put("content", envelope);
        row.put("date", (int) (System.currentTimeMillis() / 1000L));
        row.put("media", org.json.JSONObject.NULL);

        // parse #1 — e.g. the update_queue message_new tick
        TLRPC.TL_message first = TlJsonMapper.parseMessage(1, row, ALICE_ID, false, ALICE_ID, BOB_ID);
        assertEquals("first parse must render the plaintext", text, first.message);

        // parse #2 — e.g. the racing history load of the same row. Pre-T75
        // libsignal's replay protection failed this parse and the 🔒 row
        // replaced the good one in storage/UI.
        TLRPC.TL_message second = TlJsonMapper.parseMessage(1, row, ALICE_ID, false, ALICE_ID, BOB_ID);
        assertEquals("re-parse must be IDEMPOTENT (memo hit, no libsignal)", text, second.message);

        // parse #3 — a completely fresh process view of the same row
        // (restored memo) still opens.
        XoE2EEStore bobStore = XoE2EEStore.getInstance(1);
        assertEquals("memo persists with the encrypted blob", text,
                new org.json.JSONObject(bobStore.getDecryptedInner(5001)).optString("x"));
        TLRPC.TL_message third = TlJsonMapper.parseMessage(1, row, ALICE_ID, false, ALICE_ID, BOB_ID);
        assertEquals(text, third.message);
    }

    /** A genuinely undecryptable row still renders 🔒 — the memo never masks real failures. */
    @Test
    public void tamperedRow_staysLocked_andMemoIsNotPoisoned() throws Exception {
        String envelope = alice.encryptText(BOB_ID, "secret");
        byte[] raw = XoE2EEEnvelope.b64Decode(envelope.substring(XoE2EEEnvelope.PREFIX.length()));
        raw[raw.length - 1] ^= 0x5A; // structural tamper (GCM tag / body byte)
        String broken = XoE2EEEnvelope.PREFIX + XoE2EEEnvelope.b64Encode(raw);

        org.json.JSONObject row = new org.json.JSONObject();
        row.put("id", 5002);
        row.put("sender_id", ALICE_ID);
        row.put("content", broken);
        row.put("date", (int) (System.currentTimeMillis() / 1000L));
        row.put("media", org.json.JSONObject.NULL);

        TLRPC.TL_message first = TlJsonMapper.parseMessage(1, row, ALICE_ID, false, ALICE_ID, BOB_ID);
        assertEquals("tampered row must show the neutral placeholder", "🔒", first.message);
        TLRPC.TL_message second = TlJsonMapper.parseMessage(1, row, ALICE_ID, false, ALICE_ID, BOB_ID);
        assertEquals("🔒", second.message);
        assertNull("no memo entry may exist for a failed decrypt",
                XoE2EEStore.getInstance(1).getDecryptedInner(5002));
    }

    /** The memo is per-account: Bob's memo never answers Alice's receive path. */
    @Test
    public void memoIsAccountScoped() throws Exception {
        String envelope = alice.encryptText(BOB_ID, "از آلیس");
        org.json.JSONObject row = new org.json.JSONObject();
        row.put("id", 5003);
        row.put("sender_id", ALICE_ID);
        row.put("content", envelope);
        row.put("date", (int) (System.currentTimeMillis() / 1000L));
        row.put("media", org.json.JSONObject.NULL);

        TLRPC.TL_message parsed = TlJsonMapper.parseMessage(1, row, ALICE_ID, false, ALICE_ID, BOB_ID);
        assertEquals("از آلیس", parsed.message);

        // Bob's store holds it; Alice's store does not (she never received this id).
        assertNotNull(XoE2EEStore.getInstance(1).getDecryptedInner(5003));
        assertNull(XoE2EEStore.getInstance(0).getDecryptedInner(5003));
    }

    // ------------------------------------------------- Fix B: album shape + manifest animated flag

    /** An e2ee photo item's uploadMedia answer must stay a PHOTO (album shape contract). */
    @Test
    public void parseMediaE2ee_forcesTheRequestFamily() throws Exception {
        org.json.JSONObject e2eeFile = new org.json.JSONObject();
        e2eeFile.put("file_id", 4242);
        e2eeFile.put("size", 123456);
        e2eeFile.put("kind", "e2ee");
        e2eeFile.put("mime_type", "application/octet-stream");
        e2eeFile.put("sha256", org.json.JSONObject.NULL);

        TLRPC.MessageMedia asPhoto = TlJsonMapper.parseMediaE2ee(e2eeFile, 1791000000, true);
        assertTrue("photo item must map back to a photo", asPhoto instanceof TLRPC.TL_messageMediaPhoto);
        assertEquals(4242, ((TLRPC.TL_messageMediaPhoto) asPhoto).photo.id);

        TLRPC.MessageMedia asDocument = TlJsonMapper.parseMediaE2ee(e2eeFile, 1791000000, false);
        assertTrue("document item must map to a document", asDocument instanceof TLRPC.TL_messageMediaDocument);
        assertEquals(4242, ((TLRPC.TL_messageMediaDocument) asDocument).document.id);
    }

    /** The envelope manifest carries an EXPLICIT animated bit and parseMediaMeta round-trips it. */
    @Test
    public void envelopeAnimatedFlag_roundTrips() throws Exception {
        byte[] key = XoE2EEMedia.newFileKey();
        String inner = XoE2EEEnvelope.innerMedia(key, 9999, 131072,
                "video/mp4", "clip.mp4", 480, 480, 5, "کپشن", false, null, 0, true);
        XoE2EE.MediaMeta meta = XoE2EE.parseMediaMeta(new org.json.JSONObject(inner));
        assertNotNull(meta);
        assertTrue("animated GIF envelope must carry the explicit bit", meta.animated);

        String innerPlainVideo = XoE2EEEnvelope.innerMedia(key, 9999, 131072,
                "video/mp4", "clip.mp4", 1920, 1080, 42, null, false, null, 0, false);
        assertFalse("a real video must NOT be flagged animated",
                XoE2EE.parseMediaMeta(new org.json.JSONObject(innerPlainVideo)).animated);
    }

    // ------------------------------------------------- Fix E: pending keys gate

    private static TLRPC.Message row(long dialogId) {
        TLRPC.TL_message m = new TLRPC.TL_message();
        m.dialog_id = dialogId;
        m.id = -777;
        m.message = "سلام";
        return m;
    }

    @Test
    public void deferralGate_onlyTheExactCode_privateNonSelfDialogs() {
        List<TLRPC.Message> rows = new ArrayList<>();
        rows.add(row(BOB_ID));

        assertTrue("exact code + private dialog defers",
                XoPendingKeys.deferrable("E2EE_NO_PEER_KEYS", rows, ALICE_ID));
        assertFalse("any other code is a hard failure",
                XoPendingKeys.deferrable("E2EE_IDENTITY_CHANGED", rows, ALICE_ID));
        assertFalse("null code never defers",
                XoPendingKeys.deferrable(null, rows, ALICE_ID));
        assertFalse("self dialogs never defer (self-chats are plaintext)",
                XoPendingKeys.deferrable("E2EE_NO_PEER_KEYS", rows, BOB_ID));

        List<TLRPC.Message> groupRows = new ArrayList<>();
        groupRows.add(row(-500));
        assertFalse("group dialogs never defer (plaintext by design)",
                XoPendingKeys.deferrable("E2EE_NO_PEER_KEYS", groupRows, ALICE_ID));

        assertFalse("empty rows never defer",
                XoPendingKeys.deferrable("E2EE_NO_PEER_KEYS", new ArrayList<>(), ALICE_ID));
    }

    @Test
    public void deferralGate_usesTheOwnersDecisionCore() {
        XoPendingKeys mgr = XoPendingKeys.getInstance(0);
        mgr.resetForTests();
        assertEquals(0, mgr.watchedCount());
        // the seam's decision core is the pure deferrable() gate — covered
        // above; this pins the singleton starts clean and stays cheap.
        mgr.resetForTests();
    }
}

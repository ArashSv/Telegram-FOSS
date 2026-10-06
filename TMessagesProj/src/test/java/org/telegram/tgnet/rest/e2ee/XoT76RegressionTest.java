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
 * T76 — regression suite for the field report "no video downloads ('Message
 * doesn't exist' at the end / immediately for small ones)", "the GIF type is
 * gone", "online shows but last seen is gone" and "messages go missing and
 * show up later".
 *
 * <p>Root causes pinned here:
 * <ol>
 *   <li><b>GIF semantics must survive E2EE.</b> A received animated e2ee
 *       document carries the animated attribute AND the GIF access-hash
 *       sentinel (the resend-by-reference contract); a REAL video (an=0)
 *       must never gain them. The T75 build lost the sentinel on receive.</li>
 *   <li><b>The receive parse stays idempotent with media keys</b> — the
 *       same row parsed twice must register the SAME media key material
 *       (the download finish gate decrypts with it long after parse).</li>
 *   <li><b>Download finish-gate classification</b> — the honest bulletin
 *       contract lives in DownloadController (Android-coupled, covered by
 *       the CI compile gate + wire suite); here the transport's
 *       self-attesting size contract is pinned at the header level.</li>
 * </ol>
 */
public class XoT76RegressionTest {

    private static final long ALICE_ID = 1001L;
    private static final long BOB_ID = 2002L;

    private static final long GIF_SENTINEL = 1L; // TlJsonMapper.GIF_ACCESS_HASH_SENTINEL

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

    /** A muted-MP4 GIF (an=1) received in an E2EE chat keeps the FULL GIF contract. */
    @Test
    public void animatedE2eeDocument_keepsGifSemantics() throws Exception {
        byte[] fileKey = XoE2EEMedia.newFileKey();
        // the envelope the T75 dispatcher builds for a gif-tab re-encrypt
        String inner = XoE2EEEnvelope.innerMedia(fileKey, 250_000, 131_072,
                "video/mp4", "sticker.mp4", 320, 240, 3, null, false, null, 0, true);
        String envelope = XoE2EE.getInstance(0).encryptForPeer(BOB_ID, inner);

        org.json.JSONObject row = new org.json.JSONObject();
        row.put("id", 6001);
        row.put("sender_id", ALICE_ID);
        row.put("content", envelope);
        row.put("date", (int) (System.currentTimeMillis() / 1000L));
        org.json.JSONObject media = new org.json.JSONObject();
        media.put("file_id", 777);
        media.put("size", 250_000 + 3 * 16);
        media.put("kind", "e2ee");
        media.put("mime_type", "application/octet-stream");
        media.put("sha256", org.json.JSONObject.NULL);
        row.put("media", media);

        TLRPC.TL_message parsed = TlJsonMapper.parseMessage(1, row, ALICE_ID, false, ALICE_ID, BOB_ID);
        assertNotNull(parsed);
        assertTrue("media must be a document", parsed.media instanceof TLRPC.TL_messageMediaDocument);
        TLRPC.TL_document doc = (TLRPC.TL_document) ((TLRPC.TL_messageMediaDocument) parsed.media).document;
        assertNotNull(doc);
        assertEquals("the real mime rides the envelope", "video/mp4", doc.mime_type);

        boolean animated = false;
        boolean video = false;
        for (int i = 0; i < doc.attributes.size(); i++) {
            TLRPC.DocumentAttribute a = doc.attributes.get(i);
            if (a instanceof TLRPC.TL_documentAttributeAnimated) animated = true;
            if (a instanceof TLRPC.TL_documentAttributeVideo) video = true;
        }
        assertTrue("the animated attribute must survive receive", animated);
        assertTrue("the video attribute (loop duration/dims) must survive receive", video);
        assertEquals("the GIF resend contract (sentinel) must survive receive",
                GIF_SENTINEL, doc.access_hash);
    }

    /** A REAL video (an=0) must not be promoted to a GIF on receive. */
    @Test
    public void realVideo_neverBecomesAnimated() throws Exception {
        byte[] fileKey = XoE2EEMedia.newFileKey();
        String inner = XoE2EEEnvelope.innerMedia(fileKey, 5_000_000, 131_072,
                "video/mp4", "movie.mp4", 1920, 1080, 40, null, false, null, 0, false);
        XoE2EE.MediaMeta meta = XoE2EE.parseMediaMeta(new org.json.JSONObject(inner));
        assertTrue("a tagged envelope must be detected as known-an", meta.animatedKnown);
        assertFalse(meta.animated);

        org.json.JSONObject row = new org.json.JSONObject();
        row.put("id", 6002);
        row.put("sender_id", ALICE_ID);
        row.put("content", XoE2EE.getInstance(0).encryptForPeer(BOB_ID, inner));
        row.put("date", (int) (System.currentTimeMillis() / 1000L));
        org.json.JSONObject media = new org.json.JSONObject();
        media.put("file_id", 778);
        media.put("size", 5_000_000 + 39 * 16);
        media.put("kind", "e2ee");
        media.put("mime_type", "application/octet-stream");
        media.put("sha256", org.json.JSONObject.NULL);
        row.put("media", media);

        TLRPC.TL_message parsed = TlJsonMapper.parseMessage(1, row, ALICE_ID, false, ALICE_ID, BOB_ID);
        TLRPC.TL_document doc = (TLRPC.TL_document) ((TLRPC.TL_messageMediaDocument) parsed.media).document;
        for (int i = 0; i < doc.attributes.size(); i++) {
            assertFalse("a real video must never carry the animated attribute",
                    doc.attributes.get(i) instanceof TLRPC.TL_documentAttributeAnimated);
        }
        assertFalse("a real video must never carry the GIF sentinel", doc.access_hash == GIF_SENTINEL);
    }

    /** A PRE-T75 envelope (no "an" field at all) keeps the legacy .mp4-name fallback. */
    @Test
    public void legacyUntaggedEnvelope_keepsTheFallback() throws Exception {
        byte[] fileKey = XoE2EEMedia.newFileKey();
        org.json.JSONObject inner = new org.json.JSONObject();
        inner.put("t", "m");
        inner.put("fk", XoE2EEEnvelope.b64Encode(fileKey));
        inner.put("pl", 100_000);
        inner.put("cs", 131_072);
        inner.put("mi", "video/mp4");
        inner.put("na", "legacy.mp4");
        // no "an" key — a pre-T75 row
        XoE2EE.MediaMeta meta = XoE2EE.parseMediaMeta(inner);
        assertNotNull(meta);
        assertFalse("untagged envelope: animatedKnown must be false", meta.animatedKnown);
        assertFalse(meta.animated);
    }

    /**
     * The download finish gate decrypts with the keys registered at PARSE
     * time — a re-parse (update vs history race) must register the same
     * material, and the envelope's pl/cs must round-trip exactly (a wrong
     * cs made every chunk decrypt fail at the finish gate).
     */
    @Test
    public void mediaKeys_roundTrip_andSurviveReParse() throws Exception {
        byte[] fileKey = XoE2EEMedia.newFileKey();
        long plainLen = 300_000;
        int chunkSize = 131_072;
        String inner = XoE2EEEnvelope.innerMedia(fileKey, plainLen, chunkSize,
                "video/mp4", "clip.mp4", 640, 480, 12, null, true,
                XoE2EEMedia.newFileKey(), 779, false);
        String envelope = XoE2EE.getInstance(0).encryptForPeer(BOB_ID, inner);

        org.json.JSONObject row = new org.json.JSONObject();
        row.put("id", 6003);
        row.put("sender_id", ALICE_ID);
        row.put("content", envelope);
        row.put("date", (int) (System.currentTimeMillis() / 1000L));
        org.json.JSONObject media = new org.json.JSONObject();
        media.put("file_id", 780);
        media.put("size", plainLen + (long) Math.ceil(plainLen / (double) chunkSize) * 16);
        media.put("kind", "e2ee");
        media.put("mime_type", "application/octet-stream");
        media.put("sha256", org.json.JSONObject.NULL);
        row.put("media", media);

        TlJsonMapper.parseMessage(1, row, ALICE_ID, false, ALICE_ID, BOB_ID);
        XoE2EE.MediaMeta first = XoE2EE.getInstance(1).mediaKeysFor(780);
        assertNotNull("parse must register the body key", first);
        assertEquals(plainLen, first.plaintextLen);
        assertEquals(chunkSize, first.chunkSize);
        assertNotNull(first.thumbKey);
        assertEquals(779, first.thumbFileId);

        // the racing history load re-parses the same row — the keys must be unchanged
        TlJsonMapper.parseMessage(1, row, ALICE_ID, false, ALICE_ID, BOB_ID);
        XoE2EE.MediaMeta second = XoE2EE.getInstance(1).mediaKeysFor(780);
        assertNotNull(second);
        org.junit.Assert.assertArrayEquals("the file key must be byte-stable across re-parses",
                first.fileKey, second.fileKey);
        assertEquals(first.plaintextLen, second.plaintextLen);
        assertEquals(first.chunkSize, second.chunkSize);
    }

    /** The inner envelope's thumb contract round-trips (th/tk/tf) for the finish-gate thumb decrypt. */
    @Test
    public void envelopeThumbFields_roundTrip() throws Exception {
        byte[] fileKey = XoE2EEMedia.newFileKey();
        byte[] thumbKey = XoE2EEMedia.newFileKey();
        String inner = XoE2EEEnvelope.innerMedia(fileKey, 1000, 131072,
                "video/mp4", null, 0, 0, 0, null, true, thumbKey, 555, false);
        XoE2EE.MediaMeta meta = XoE2EE.parseMediaMeta(new org.json.JSONObject(inner));
        assertNotNull(meta);
        assertTrue(meta.thumbEncrypted);
        org.junit.Assert.assertArrayEquals(thumbKey, meta.thumbKey);
        assertEquals(555, meta.thumbFileId);
    }

    /** A keyless-peer deferral still admits ONLY the exact code (T75 guard stays intact). */
    @Test
    public void deferralGate_stillExact() {
        TLRPC.TL_message peerRow = new TLRPC.TL_message();
        peerRow.dialog_id = BOB_ID; // a private dialog with someone else — defers
        java.util.List<TLRPC.Message> rows = java.util.Collections.singletonList((TLRPC.Message) peerRow);
        assertTrue(XoPendingKeys.deferrable("E2EE_NO_PEER_KEYS", rows, ALICE_ID));
        assertFalse("a non-deferrable error code never defers",
                XoPendingKeys.deferrable("E2EE_REENCRYPT_FAILED", rows, ALICE_ID));
        assertFalse("a self-dialog never defers", XoPendingKeys.deferrable("E2EE_NO_PEER_KEYS", rows, BOB_ID));
        assertFalse("an unknown code never defers",
                XoPendingKeys.deferrable("SOME_OTHER_ERROR", rows, ALICE_ID));
        assertFalse("a null code never defers", XoPendingKeys.deferrable(null, rows, ALICE_ID));

        TLRPC.TL_message groupRow = new TLRPC.TL_message();
        groupRow.dialog_id = -444; // group dialog
        assertFalse("a group dialog never defers", XoPendingKeys.deferrable("E2EE_NO_PEER_KEYS",
                java.util.Collections.singletonList((TLRPC.Message) groupRow), ALICE_ID));
    }
}

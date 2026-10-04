package org.telegram.tgnet.rest.e2ee;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T71 — envelope codec tests: the XOE1: wire format is unambiguous,
 * tamper-evident at the framing layer, and round-trips byte-exact.
 */
public class XoE2EEEnvelopeTest {

    @Test
    public void prefixDetectionIsExact() {
        assertFalse(XoE2EEEnvelope.isEnvelope(null));
        assertFalse(XoE2EEEnvelope.isEnvelope(""));
        assertFalse(XoE2EEEnvelope.isEnvelope("XOE1:"));
        assertFalse(XoE2EEEnvelope.isEnvelope("plain سلام"));
        assertFalse(XoE2EEEnvelope.isEnvelope("XOE2:not-ours"));
        assertTrue(XoE2EEEnvelope.isEnvelope("XOE1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
    }

    @Test
    public void wrapUnwrapRoundTrip() {
        byte[] body = new byte[255];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) i;
        }
        String envelope = XoE2EEEnvelope.wrap(3, body);
        assertTrue(envelope.startsWith(XoE2EEEnvelope.PREFIX));

        XoE2EEEnvelope.Unwrapped unwrapped = XoE2EEEnvelope.unwrap(envelope);
        assertEquals(3, unwrapped.wireType);
        assertArrayEquals(body, unwrapped.body);

        String envelope2 = XoE2EEEnvelope.wrap(2, body);
        assertEquals(2, XoE2EEEnvelope.unwrap(envelope2).wireType);
    }

    @Test
    public void malformedEnvelopesUnwrapToNull() {
        assertNull(XoE2EEEnvelope.unwrap("XOE1:!!!not-base64!!!"));
        assertNull(XoE2EEEnvelope.unwrap("XOE1:AQ")); // too short after decode
        assertNull(XoE2EEEnvelope.unwrap(null));
    }

    @Test
    public void innerPayloadBuildersKeepTheContract() throws Exception {
        String text = XoE2EEEnvelope.innerText("سلام");
        org.json.JSONObject json = new org.json.JSONObject(text);
        assertEquals("t", json.optString("t"));
        assertEquals("سلام", json.optString("x"));

        String edit = XoE2EEEnvelope.innerEdit("ویرایش");
        assertEquals("e", new org.json.JSONObject(edit).optString("t"));

        byte[] fk = XoE2EEMedia.newFileKey();
        byte[] tk = XoE2EEMedia.newFileKey();
        String media = XoE2EEEnvelope.innerMedia(fk, 12345, 131072, "video/mp4",
                "a.mp4", 640, 480, 12, "cap", true, tk, 77);
        org.json.JSONObject m = new org.json.JSONObject(media);
        assertEquals("m", m.optString("t"));
        assertArrayEquals(fk, XoE2EEEnvelope.b64Decode(m.optString("fk")));
        assertArrayEquals(tk, XoE2EEEnvelope.b64Decode(m.optString("tk")));
        assertEquals(77, m.optLong("tf"));
        assertEquals(12345, m.optLong("pl"));

        XoE2EE.MediaMeta meta = XoE2EE.parseMediaMeta(m);
        assertNotNull(meta);
        assertArrayEquals(fk, meta.fileKey);
        assertTrue(meta.thumbEncrypted);
    }

    @Test
    public void mediaMetaRejectsWrongKeyLength() throws Exception {
        // a 16-byte "file key" is structurally invalid — parse must refuse
        String bad = XoE2EEEnvelope.innerMedia(new byte[16], 1, 131072, "a/b", null,
                0, 0, 0, null, false, null, 0);
        assertNull(XoE2EE.parseMediaMeta(new org.json.JSONObject(bad)));
    }
}

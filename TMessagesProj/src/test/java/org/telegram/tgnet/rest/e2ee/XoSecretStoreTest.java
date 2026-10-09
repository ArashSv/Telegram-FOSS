package org.telegram.tgnet.rest.e2ee;

import org.junit.BeforeClass;
import org.telegram.tgnet.rest.XoTestEnv;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T78 — the secret-chat STORE mechanics on the JVM (temp-dir blob): own key
 * pair persistence, peer-key cache, media keys, upload intents (the full
 * in-flight lifecycle), album thumbs and the own-echo inner cache.
 */
public class XoSecretStoreTest {

    @BeforeClass
    public static void init() throws Exception {
        XoTestEnv.init();
        boolean needsFilesDir = false;
        try {
            needsFilesDir = org.telegram.messenger.ApplicationLoader.applicationContext == null
                    || org.telegram.messenger.ApplicationLoader.applicationContext.getFilesDir() == null;
        } catch (Throwable t) {
            needsFilesDir = true; // JVM ContextWrapper NPEs on the null base
        }
        if (needsFilesDir) {
            File tmp = Files.createTempDirectory("xosecret").toFile();
            org.telegram.messenger.ApplicationLoader.applicationContext =
                    new android.content.ContextWrapper(
                            org.telegram.messenger.ApplicationLoader.applicationContext) {
                        @Override
                        public File getFilesDir() {
                            return tmp;
                        }
                    };
        }
        XoSecretStore.resetForTests();
    }

    @Test
    public void keyPairGeneratesAndPersists() {
        XoSecretStore store = XoSecretStore.getInstance(0);
        assertFalse(store.hasIdentity());
        store.generateKeyPairIfAbsent();
        assertTrue(store.hasIdentity());
        byte[] priv = store.getPrivateKey();
        byte[] pub = store.getPublicKey();
        assertNotNull(priv);
        assertNotNull(pub);
        // a FRESH singleton instance reloads from the blob and derives the
        // same pair (persistence works)
        XoSecretStore.resetForTests();
        XoSecretStore reloaded = XoSecretStore.getInstance(0);
        assertTrue(reloaded.hasIdentity());
        assertArrayEquals(priv, reloaded.getPrivateKey());
        assertArrayEquals(pub, reloaded.getPublicKey());
    }

    @Test
    public void peerKeyCacheRoundTrip() {
        XoSecretStore store = XoSecretStore.getInstance(0);
        String pk = XoSecretEnvelope.b64Encode(XoSecretCrypto.generateKeyPair()[1]);
        store.putPeerKey(10001, pk);
        assertEquals(pk, store.getPeerKey(10001));
        assertTrue(store.hasPeerKey(10001));
        assertFalse(store.hasPeerKey(10002));
        assertNull(store.getPeerKey(999999));
    }

    @Test
    public void uploadIntentLifecycle() {
        XoSecretStore store = XoSecretStore.getInstance(0);
        String location = "msg42_photo";
        TLRPCish.document();
        store.noteUploadIntent(location, 10001, null, TLRPCish.document());
        // idempotent refresh keeps the SAME key
        byte[] key1 = store.uploadKeyForTree(0); // not bound yet
        assertNull(key1);
        store.bindTreeUploadId(location, 777);
        byte[] key2 = store.uploadKeyForTree(777);
        assertNotNull(key2);
        store.noteUploadIntent(location, 10001, null, TLRPCish.document());
        byte[] key3 = store.uploadKeyForTree(777);
        assertArrayEquals(key2, key3); // same key after re-note

        // part tracking -> plaintext totals
        store.noteUploadPart(777, 0, 96 * 1024);
        store.noteUploadPart(777, 1, 40000);
        long[] totals = store.uploadPlaintextTotals(777);
        assertNotNull(totals);
        assertEquals(96 * 1024 + 40000L, totals[0]);
        assertEquals(96 * 1024, totals[1]);
        assertEquals(2, totals[2]);

        // manifest mirrors the metadata
        assertNotNull(store.uploadManifest(777));
        assertEquals(10001L, store.uploadPeerForTree(777));

        // drop
        store.dropUploadIntent(777);
        assertNull(store.uploadKeyForTree(777));
    }

    @Test
    public void mediaKeysAndAlbumThumbs() throws Exception {
        XoSecretStore store = XoSecretStore.getInstance(0);
        byte[] fk = XoE2EEMedia.newFileKey();
        org.json.JSONObject meta = new org.json.JSONObject();
        meta.put("fk", XoSecretEnvelope.b64Encode(fk));
        meta.put("pl", 123L);
        meta.put("cs", 96 * 1024);
        meta.put("th", 0);
        store.putMediaKeys(9001, meta.toString());
        assertEquals(meta.toString(), store.getMediaKeys(9001));

        byte[] thumbKey = XoE2EEMedia.newFileKey();
        store.noteAlbumThumb(9001, 9002, thumbKey);
        org.json.JSONObject thumb = store.albumThumbFor(9001);
        assertNotNull(thumb);
        assertEquals(9002L, thumb.optLong("tf"));
        assertArrayEquals(thumbKey, XoSecretEnvelope.b64Decode(thumb.optString("tk")));
        store.dropAlbumThumb(9001);
        assertNull(store.albumThumbFor(9001));
    }

    @Test
    public void sentInnerCacheRoundTrip() {
        XoSecretStore store = XoSecretStore.getInstance(0);
        store.noteSentInner(12345, "{\"t\":\"t\",\"x\":\"پیام خودم\"}");
        assertEquals("{\"t\":\"t\",\"x\":\"پیام خودم\"}", store.getSentInner(12345));
        assertNull(store.getSentInner(999999));
    }

    /** Tiny TL_document factory without dragging the TLRPC constructor zoo in. */
    private static final class TLRPCish {
        static org.telegram.tgnet.TLRPC.TL_document document() {
            org.telegram.tgnet.TLRPC.TL_document doc = new org.telegram.tgnet.TLRPC.TL_document();
            doc.mime_type = "video/mp4";
            doc.size = 123456;
            org.telegram.tgnet.TLRPC.TL_documentAttributeVideo video = new org.telegram.tgnet.TLRPC.TL_documentAttributeVideo();
            video.duration = 42f;
            video.w = 854;
            video.h = 480;
            doc.attributes.add(video);
            org.telegram.tgnet.TLRPC.TL_documentAttributeFilename name = new org.telegram.tgnet.TLRPC.TL_documentAttributeFilename();
            name.file_name = "clip.mp4";
            doc.attributes.add(name);
            return doc;
        }
    }
}

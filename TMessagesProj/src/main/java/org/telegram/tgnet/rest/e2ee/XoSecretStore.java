package org.telegram.tgnet.rest.e2ee;

import android.content.Context;
import android.os.Build;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.TLRPC;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

/**
 * T78 — per-account state for the NEW secret-chat scheme. A fraction of the
 * previous protocol store: ONE own key pair, a peer public-key cache, the
 * media-key registry, upload intents and the own-echo inner cache. No
 * sessions, no prekeys, no trust-flag machinery — statelessness removed
 * them by design.
 *
 * <p>Persistence: single JSON blob, keystore-encrypted (same proven
 * pattern the previous store used), atomic write + fsync, corrupt blob
 * wipes. Own key pair loss is RECOVERABLE (a new pair is generated and
 * registered; peers learn it from the next envelope + the registry), which
 * is why no escrow/backup machinery is needed.
 */
public final class XoSecretStore {

    public static final int MAX_PEER_KEYS = 200;
    public static final int SENT_INNER_CACHE_MAX = 2000;
    public static final int MEDIA_KEYS_MAX = 400;

    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String KEY_ALIAS_PREFIX = "xo_secret_";
    private static final String BLOB_FILE_PREFIX = "xo_secret_state_";
    private static final String BLOB_FILE_SUFFIX = ".bin";

    private static final XoSecretStore[] instances = new XoSecretStore[org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT];

    public static XoSecretStore getInstance(int account) {
        if (account < 0 || account >= instances.length) {
            account = 0;
        }
        XoSecretStore store;
        synchronized (XoSecretStore.class) {
            store = instances[account];
            if (store == null) {
                store = new XoSecretStore(account);
                instances[account] = store;
            }
        }
        return store;
    }

    private final int account;
    private final Object lock = new Object();

    // own identity
    private byte[] privateKey;
    private byte[] publicKey;
    private boolean registeredThisInstall; // our pk was PUT at least once

    // peer userId -> base64 public key (TOFU cache of the registry)
    private final HashMap<Long, String> peerKeys = new HashMap<>();

    // backend file id -> media meta json (fk/pl/cs/th/tk/tf)
    private final LinkedHashMap<Long, String> mediaKeys = new LinkedHashMap<>();

    // upload intents (location -> json) + tree binding, identical mechanics to before
    private final HashMap<String, String> uploadIntents = new HashMap<>();
    private final HashMap<Long, String> uploadTreeLocations = new HashMap<>();

    // album item body file id -> {tf, tk}
    private final HashMap<Long, String> albumThumbKeys = new HashMap<>();

    // own outgoing message id -> plaintext inner JSON (echo rendering)
    private final LinkedHashMap<Long, String> sentInners = new LinkedHashMap<>();

    private boolean loaded;
    private boolean dirty;

    private XoSecretStore(int account) {
        this.account = account;
    }

    // ------------------------------------------------------------------ own identity

    /** Generates the account key pair when absent. Thread-safe. */
    public void generateKeyPairIfAbsent() {
        synchronized (lock) {
            loadLocked();
            if (privateKey != null && publicKey != null) {
                return;
            }
            try {
                byte[][] kp = XoSecretCrypto.generateKeyPair();
                privateKey = kp[0];
                publicKey = kp[1];
                markDirtyLocked();
                XoE2eeLog.event(account, "secret.keygen", 0, "pair generated");
            } catch (Throwable t) {
                FileLog.e("XoSecretStore: keygen failed", t);
            }
        }
    }

    public boolean hasIdentity() {
        synchronized (lock) {
            loadLocked();
            return privateKey != null && publicKey != null;
        }
    }

    public byte[] getPrivateKey() {
        synchronized (lock) {
            loadLocked();
            return privateKey;
        }
    }

    public byte[] getPublicKey() {
        synchronized (lock) {
            loadLocked();
            return publicKey;
        }
    }

    /** Marks that our public key was successfully PUT to the registry. */
    public void noteRegistered() {
        synchronized (lock) {
            loadLocked();
            if (!registeredThisInstall) {
                registeredThisInstall = true;
                markDirtyLocked();
            }
        }
    }

    public boolean isRegisteredThisInstall() {
        synchronized (lock) {
            loadLocked();
            return registeredThisInstall;
        }
    }

    // ------------------------------------------------------------------ peer keys

    /** Caches a peer's registry public key (base64). */
    public void putPeerKey(long userId, String pkB64) {
        if (userId <= 0 || pkB64 == null || pkB64.isEmpty()) {
            return;
        }
        synchronized (lock) {
            loadLocked();
            peerKeys.remove(userId); // LRU refresh
            peerKeys.put(userId, pkB64);
            while (peerKeys.size() > MAX_PEER_KEYS) {
                Long eldest = peerKeys.keySet().iterator().next();
                peerKeys.remove(eldest);
            }
            markDirtyLocked();
        }
    }

    public String getPeerKey(long userId) {
        synchronized (lock) {
            loadLocked();
            return peerKeys.get(userId);
        }
    }

    public boolean hasPeerKey(long userId) {
        synchronized (lock) {
            loadLocked();
            return peerKeys.containsKey(userId);
        }
    }

    // ------------------------------------------------------------------ media keys

    public void putMediaKeys(long backendFileId, String metaJson) {
        synchronized (lock) {
            loadLocked();
            mediaKeys.remove(backendFileId);
            mediaKeys.put(backendFileId, metaJson);
            while (mediaKeys.size() > MEDIA_KEYS_MAX) {
                mediaKeys.remove(mediaKeys.keySet().iterator().next());
            }
            markDirtyLocked();
        }
    }

    public String getMediaKeys(long backendFileId) {
        synchronized (lock) {
            loadLocked();
            return mediaKeys.get(backendFileId);
        }
    }

    // ------------------------------------------------------------- album thumb keys

    public void noteAlbumThumb(long bodyBackendFileId, long thumbBackendFileId, byte[] thumbKey) {
        if (bodyBackendFileId <= 0 || thumbBackendFileId <= 0 || thumbKey == null) {
            return;
        }
        synchronized (lock) {
            loadLocked();
            try {
                JSONObject json = new JSONObject();
                json.put("tf", thumbBackendFileId);
                json.put("tk", XoSecretEnvelope.b64Encode(thumbKey));
                albumThumbKeys.put(bodyBackendFileId, json.toString());
                markDirtyLocked();
            } catch (Exception e) {
                FileLog.e("XoSecretStore: album thumb note failed", e);
            }
        }
    }

    public JSONObject albumThumbFor(long bodyBackendFileId) {
        synchronized (lock) {
            loadLocked();
            String json = albumThumbKeys.get(bodyBackendFileId);
            if (json == null) {
                return null;
            }
            try {
                return new JSONObject(json);
            } catch (Exception e) {
                return null;
            }
        }
    }

    public void dropAlbumThumb(long bodyBackendFileId) {
        synchronized (lock) {
            loadLocked();
            if (albumThumbKeys.remove(bodyBackendFileId) != null) {
                markDirtyLocked();
            }
        }
    }

    // --------------------------------------------------------------- sent-inner cache

    public void noteSentInner(long messageId, String innerJson) {
        if (messageId <= 0 || innerJson == null || innerJson.isEmpty()) {
            return;
        }
        synchronized (lock) {
            loadLocked();
            sentInners.remove(messageId);
            sentInners.put(messageId, innerJson);
            while (sentInners.size() > SENT_INNER_CACHE_MAX) {
                sentInners.remove(sentInners.keySet().iterator().next());
            }
            markDirtyLocked();
        }
    }

    public String getSentInner(long messageId) {
        if (messageId <= 0) {
            return null;
        }
        synchronized (lock) {
            loadLocked();
            return sentInners.get(messageId);
        }
    }

    // ------------------------------------------------------------------ upload intents

    /**
     * Registers/refreshes the upload intent for a pending secret upload.
     * First call mints the single-use file key; later calls refresh only
     * the metadata manifest so the SAME key always decrypts the SAME upload.
     */
    public void noteUploadIntent(String location, long peerUserId, TLRPC.PhotoSize photoSize, TLRPC.TL_document document) {
        synchronized (lock) {
            loadLocked();
            try {
                JSONObject entry;
                String existing = uploadIntents.get(location);
                if (existing != null) {
                    entry = new JSONObject(existing);
                    if (entry.has("fk")) {
                        entry.put("peer", peerUserId);
                        putManifestMeta(entry, photoSize, document);
                        uploadIntents.put(location, entry.toString());
                        markDirtyLocked();
                        return;
                    }
                }
                byte[] key = new byte[XoE2EEMedia.FILE_KEY_LEN];
                new java.security.SecureRandom().nextBytes(key);
                entry = existing != null ? new JSONObject(existing) : new JSONObject();
                entry.put("peer", peerUserId);
                entry.put("fk", XoSecretEnvelope.b64Encode(key));
                entry.put("ts", System.currentTimeMillis());
                putManifestMeta(entry, photoSize, document);
                uploadIntents.put(location, entry.toString());
                markDirtyLocked();
            } catch (Exception e) {
                FileLog.e("XoSecretStore: upload intent encode failed", e);
            }
        }
    }

    private static void putManifestMeta(JSONObject entry, TLRPC.PhotoSize photoSize, TLRPC.TL_document document) throws Exception {
        if (document != null) {
            if (document.mime_type != null && document.mime_type.length() > 0) {
                entry.put("mi", document.mime_type);
            }
            for (int a = 0; a < document.attributes.size(); a++) {
                TLRPC.DocumentAttribute attr = document.attributes.get(a);
                if (attr instanceof TLRPC.TL_documentAttributeFilename) {
                    entry.put("na", ((TLRPC.TL_documentAttributeFilename) attr).file_name);
                } else if (attr instanceof TLRPC.TL_documentAttributeImageSize) {
                    entry.put("w", ((TLRPC.TL_documentAttributeImageSize) attr).w);
                    entry.put("h", ((TLRPC.TL_documentAttributeImageSize) attr).h);
                } else if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                    TLRPC.TL_documentAttributeVideo video = (TLRPC.TL_documentAttributeVideo) attr;
                    entry.put("du", (int) Math.round(video.duration));
                    if (video.w > 0) {
                        entry.put("w", video.w);
                    }
                    if (video.h > 0) {
                        entry.put("h", video.h);
                    }
                } else if (attr instanceof TLRPC.TL_documentAttributeAudio) {
                    entry.put("du", (int) Math.round(((TLRPC.TL_documentAttributeAudio) attr).duration));
                } else if (attr instanceof TLRPC.TL_documentAttributeAnimated) {
                    entry.put("an", 1);
                }
            }
        } else if (photoSize != null && photoSize.w > 0) {
            entry.put("mi", "image/jpeg");
            entry.put("w", photoSize.w);
            entry.put("h", photoSize.h);
        }
    }

    /** Binds a tree upload id to a pending location intent (FileUploadOperation). */
    public void bindTreeUploadId(String location, long treeUploadId) {
        synchronized (lock) {
            loadLocked();
            if (uploadIntents.containsKey(location)) {
                uploadTreeLocations.put(treeUploadId, location);
                markDirtyLocked();
            }
        }
    }

    /** @return the file key for an in-flight encrypted upload, or null. */
    public byte[] uploadKeyForTree(long treeUploadId) {
        synchronized (lock) {
            loadLocked();
            String location = uploadTreeLocations.get(treeUploadId);
            if (location == null) {
                return null;
            }
            String json = uploadIntents.get(location);
            if (json == null) {
                return null;
            }
            try {
                return XoSecretEnvelope.b64Decode(new JSONObject(json).optString("fk", ""));
            } catch (Exception e) {
                return null;
            }
        }
    }

    public long uploadPeerForTree(long treeUploadId) {
        synchronized (lock) {
            loadLocked();
            String location = uploadTreeLocations.get(treeUploadId);
            if (location == null) {
                return 0;
            }
            String json = uploadIntents.get(location);
            try {
                return json == null ? 0 : new JSONObject(json).optLong("peer", 0);
            } catch (Exception e) {
                return 0;
            }
        }
    }

    public void noteUploadPart(long treeUploadId, int part, int plainLen) {
        synchronized (lock) {
            loadLocked();
            String location = uploadTreeLocations.get(treeUploadId);
            if (location == null) {
                return;
            }
            try {
                String json = uploadIntents.get(location);
                if (json == null) {
                    return;
                }
                JSONObject entry = new JSONObject(json);
                JSONObject parts = entry.optJSONObject("parts");
                if (parts == null) {
                    parts = new JSONObject();
                }
                parts.put(String.valueOf(part), plainLen);
                entry.put("parts", parts);
                uploadIntents.put(location, entry.toString());
                markDirtyLocked();
            } catch (Exception e) {
                FileLog.e("XoSecretStore: noteUploadPart failed", e);
            }
        }
    }

    /** @return {plaintextLen, chunkSize, count} for an in-flight upload, or null. */
    public long[] uploadPlaintextTotals(long treeUploadId) {
        synchronized (lock) {
            loadLocked();
            String location = uploadTreeLocations.get(treeUploadId);
            if (location == null) {
                return null;
            }
            String json = uploadIntents.get(location);
            if (json == null) {
                return null;
            }
            try {
                JSONObject parts = new JSONObject(json).optJSONObject("parts");
                if (parts == null) {
                    return null;
                }
                long total = 0, max = 0;
                int count = 0;
                java.util.Iterator<String> it = parts.keys();
                while (it.hasNext()) {
                    long len = parts.optLong(it.next(), 0);
                    total += len;
                    max = Math.max(max, len);
                    count++;
                }
                return new long[]{total, max, count};
            } catch (Exception e) {
                return null;
            }
        }
    }

    public JSONObject uploadManifest(long treeUploadId) {
        synchronized (lock) {
            loadLocked();
            String location = uploadTreeLocations.get(treeUploadId);
            if (location == null) {
                return null;
            }
            String json = uploadIntents.get(location);
            try {
                return json == null ? null : new JSONObject(json);
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** Direct tree-id intent for blocking small thumbs. */
    public void putDirectTreeIntent(long treeUploadId, long peerUserId, byte[] fileKey) {
        synchronized (lock) {
            loadLocked();
            try {
                JSONObject json = new JSONObject();
                json.put("peer", peerUserId);
                json.put("fk", XoSecretEnvelope.b64Encode(fileKey));
                json.put("ts", System.currentTimeMillis());
                uploadIntents.put("tree:" + treeUploadId, json.toString());
                uploadTreeLocations.put(treeUploadId, "tree:" + treeUploadId);
                markDirtyLocked();
            } catch (Exception e) {
                FileLog.e("XoSecretStore: direct intent failed", e);
            }
        }
    }

    /** Upload finished (or failed) — drop the intent. */
    public void dropUploadIntent(long treeUploadId) {
        synchronized (lock) {
            loadLocked();
            String location = uploadTreeLocations.remove(treeUploadId);
            if (location != null) {
                uploadIntents.remove(location);
                markDirtyLocked();
            }
        }
    }

    // ------------------------------------------------------------------ wipe

    /** Destroys ALL secret-chat state (logout). */
    public void wipe() {
        synchronized (lock) {
            privateKey = null;
            publicKey = null;
            registeredThisInstall = false;
            peerKeys.clear();
            mediaKeys.clear();
            albumThumbKeys.clear();
            sentInners.clear();
            uploadIntents.clear();
            uploadTreeLocations.clear();
            dirty = false;
            File file = blobFile();
            if (file.exists() && !file.delete()) {
                FileLog.e("XoSecretStore: unable to delete " + file.getName());
            }
        }
    }

    // ------------------------------------------------------------------ persistence

    private void markDirtyLocked() {
        dirty = true;
        persistLocked();
    }

    private void persistLocked() {
        if (!dirty) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("v", 1);
            json.put("priv", privateKey == null ? null : XoSecretEnvelope.b64Encode(privateKey));
            json.put("pub", publicKey == null ? null : XoSecretEnvelope.b64Encode(publicKey));
            json.put("reg", registeredThisInstall);

            JSONObject pk = new JSONObject();
            for (Map.Entry<Long, String> e : peerKeys.entrySet()) {
                pk.put(String.valueOf(e.getKey()), e.getValue());
            }
            json.put("peerkeys", pk);

            JSONObject mk = new JSONObject();
            for (Map.Entry<Long, String> e : mediaKeys.entrySet()) {
                mk.put(String.valueOf(e.getKey()), e.getValue());
            }
            json.put("media", mk);

            JSONObject ui = new JSONObject();
            for (Map.Entry<String, String> e : uploadIntents.entrySet()) {
                ui.put(e.getKey(), e.getValue());
            }
            json.put("uploads", ui);

            JSONObject ut = new JSONObject();
            for (Map.Entry<Long, String> e : uploadTreeLocations.entrySet()) {
                ut.put(String.valueOf(e.getKey()), e.getValue());
            }
            json.put("uploadtrees", ut);

            JSONObject si = new JSONObject();
            for (Map.Entry<Long, String> e : sentInners.entrySet()) {
                si.put(String.valueOf(e.getKey()), e.getValue());
            }
            json.put("sentinners", si);

            JSONObject at = new JSONObject();
            for (Map.Entry<Long, String> e : albumThumbKeys.entrySet()) {
                at.put(String.valueOf(e.getKey()), e.getValue());
            }
            json.put("albumthumbs", at);

            byte[] plain = json.toString().getBytes("UTF-8");
            byte[] blob = protect(plain);
            atomicWrite(blobFile(), blob);
            dirty = false;
        } catch (Throwable t) {
            FileLog.e("XoSecretStore: persist failed (will retry on next mutation)", t);
        }
    }

    private void loadLocked() {
        if (loaded) {
            return;
        }
        loaded = true;
        File file = blobFile();
        if (!file.exists()) {
            return;
        }
        byte[] blob = readFile(file);
        if (blob == null) {
            return;
        }
        try {
            JSONObject json = new JSONObject(new String(unprotect(blob), "UTF-8"));
            String priv = json.optString("priv", null);
            String pub = json.optString("pub", null);
            privateKey = priv == null ? null : XoSecretEnvelope.b64Decode(priv);
            publicKey = pub == null ? null : XoSecretEnvelope.b64Decode(pub);
            registeredThisInstall = json.optBoolean("reg", false);

            JSONObject pk = json.optJSONObject("peerkeys");
            if (pk != null) {
                java.util.Iterator<String> it = pk.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    peerKeys.put(Long.parseLong(k), pk.optString(k));
                }
            }
            JSONObject mk = json.optJSONObject("media");
            if (mk != null) {
                java.util.Iterator<String> it = mk.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    mediaKeys.put(Long.parseLong(k), mk.optString(k));
                }
            }
            JSONObject ui = json.optJSONObject("uploads");
            if (ui != null) {
                java.util.Iterator<String> it = ui.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    uploadIntents.put(k, ui.optString(k));
                }
            }
            JSONObject ut = json.optJSONObject("uploadtrees");
            if (ut != null) {
                java.util.Iterator<String> it = ut.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    uploadTreeLocations.put(Long.parseLong(k), ut.optString(k));
                }
            }
            JSONObject si = json.optJSONObject("sentinners");
            if (si != null) {
                java.util.Iterator<String> it = si.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    sentInners.put(Long.parseLong(k), si.optString(k));
                }
            }
            JSONObject at = json.optJSONObject("albumthumbs");
            if (at != null) {
                java.util.Iterator<String> it = at.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    albumThumbKeys.put(Long.parseLong(k), at.optString(k));
                }
            }
        } catch (Throwable t) {
            // corrupt blob or keystore invalidation: our OWN key pair is the
            // only critical state — it regenerates and re-registers on next
            // use, and peers learn the new key from the registry/envelopes.
            FileLog.e("XoSecretStore: state unreadable, wiping", t);
            wipe();
        }
    }

    // ------------------------------------------------- blob protection (Keystore)

    private byte[] protect(byte[] plain) throws Exception {
        if (Build.VERSION.SDK_INT >= 23) {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreKey());
            byte[] iv = cipher.getIV();
            byte[] cipherText = cipher.doFinal(plain);
            byte[] out = new byte[GCM_IV_LEN + cipherText.length];
            System.arraycopy(iv, 0, out, 0, Math.min(iv.length, GCM_IV_LEN));
            System.arraycopy(cipherText, 0, out, GCM_IV_LEN, cipherText.length);
            return out;
        }
        return plain; // API 19-22: app-private storage (documented precedent)
    }

    private byte[] unprotect(byte[] blob) throws Exception {
        if (Build.VERSION.SDK_INT >= 23) {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKeystoreKey(),
                    new GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_LEN));
            return cipher.doFinal(blob, GCM_IV_LEN, blob.length - GCM_IV_LEN);
        }
        return blob;
    }

    private SecretKey getOrCreateKeystoreKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);
        KeyStore.Entry entry;
        try {
            entry = keyStore.getEntry(KEY_ALIAS_PREFIX + account, null);
        } catch (KeyStoreException | java.security.UnrecoverableEntryException e) {
            entry = null;
        }
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS_PREFIX + account,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private File blobFile() {
        Context context = ApplicationLoader.applicationContext;
        return new File(context.getFilesDir(), BLOB_FILE_PREFIX + account + BLOB_FILE_SUFFIX);
    }

    private void atomicWrite(File target, byte[] data) throws Exception {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(data);
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(target)) {
            target.delete();
            if (!tmp.renameTo(target)) {
                throw new IllegalStateException("unable to persist secret state blob");
            }
        }
    }

    private byte[] readFile(File file) {
        if (!file.exists()) {
            return null;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buf = new byte[(int) file.length()];
            int off = 0;
            while (off < buf.length) {
                int read = in.read(buf, off, buf.length - off);
                if (read < 0) {
                    break;
                }
                off += read;
            }
            return buf;
        } catch (Exception e) {
            FileLog.e("XoSecretStore: readFile failed", e);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    /** Test seam: fresh singleton state for the next getInstance (JVM suite). */
    public static void resetForTests() {
        synchronized (XoSecretStore.class) {
            for (int a = 0; a < instances.length; a++) {
                instances[a] = null;
            }
        }
    }
}

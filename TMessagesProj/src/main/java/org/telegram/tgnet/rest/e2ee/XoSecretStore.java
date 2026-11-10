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
    // HOT CACHE ONLY (bounded): the durable lifetime guarantee is the
    // MediaKeyJournal below — evicting from this map no longer loses keys.
    private final LinkedHashMap<Long, String> mediaKeys = new LinkedHashMap<>();

    // T86: unbounded persistent media-key journal (append-only, encrypted
    // with a journal key kept keystore-protected in the main blob). The
    // pre-T86 400-entry LRU evicted keys permanently — the finish decrypt
    // gate then treated "keys missing" as "not encrypted" and cached raw
    // ciphertext as the real file. The journal makes key loss a structural
    // impossibility short of whole-store wipes (covered by the e2ee file
    // registry's fail-closed gate).
    private MediaKeyJournal mediaKeyJournal;

    // upload intents (location -> json) + tree binding, identical mechanics to before
    private final HashMap<String, String> uploadIntents = new HashMap<>();
    private final HashMap<Long, String> uploadTreeLocations = new HashMap<>();

    // album item body file id -> {tf, tk}
    private final HashMap<Long, String> albumThumbKeys = new HashMap<>();

    // own outgoing message id -> plaintext inner JSON (echo rendering)
    private final LinkedHashMap<Long, String> sentInners = new LinkedHashMap<>();

    // T82: canonical pair id (min(userA,userB)) -> the remembered file رمز.
    // Keystore-encrypted at rest; the server NEVER sees it. Entering the
    // correct password once per pair makes every later encrypted file of
    // that pair unlock transparently.
    private final HashMap<Long, String> filePasswords = new HashMap<>();

    // T82: backend file ids of LOCKED (not yet unwrapped) XOSF2 files — the
    // download gate refuses to fetch ciphertext for these until the password
    // unlocks them (auto-download can never silently save raw ciphertext).
    private final java.util.HashSet<Long> pwLockedFiles = new java.util.HashSet<>();

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
        if (backendFileId <= 0 || metaJson == null || metaJson.isEmpty()) {
            return;
        }
        synchronized (lock) {
            loadLocked();
            mediaKeys.remove(backendFileId);
            mediaKeys.put(backendFileId, metaJson);
            while (mediaKeys.size() > MEDIA_KEYS_MAX) {
                mediaKeys.remove(mediaKeys.keySet().iterator().next());
            }
            journalLocked().put(backendFileId, metaJson);
            markDirtyLocked();
        }
        // T86: every registration is also a registry note — the download
        // finish gate uses this to FAIL-CLOSED when keys are ever missing
        // for a known encrypted file (never again pass ciphertext through).
        long thumbId = 0;
        try {
            thumbId = new JSONObject(metaJson).optLong("tf", 0);
        } catch (Exception ignore) {
        }
        XoE2eeFileRegistry registry = XoE2eeFileRegistry.getInstance(account);
        registry.note(backendFileId);
        if (thumbId > 0) {
            registry.note(thumbId);
        }
    }

    public String getMediaKeys(long backendFileId) {
        synchronized (lock) {
            loadLocked();
            String json = mediaKeys.get(backendFileId);
            if (json != null) {
                return json;
            }
            return journalLocked().get(backendFileId);
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
                XoE2eeFileRegistry.getInstance(account).note(thumbBackendFileId);
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

    /**
     * T81 — registers a KEYLESS "ss" upload intent for the encrypted-file
     * feature in private cloud chats. NO key is minted here (the caller may
     * be on the main thread): the file key is the static-static derived key,
     * computed once at the first part upload (dispatcher) via
     * {@link #noteDerivedUploadKey}. Refresh semantics match
     * {@link #noteUploadIntent}: re-scheduling keeps any already-derived key.
     */
    public void noteUploadIntentSs(String location, long peerUserId, TLRPC.PhotoSize photoSize, TLRPC.TL_document document) {
        synchronized (lock) {
            loadLocked();
            try {
                JSONObject entry;
                String existing = uploadIntents.get(location);
                if (existing != null) {
                    entry = new JSONObject(existing);
                    if (entry.has("fk") || "ss".equals(entry.optString("kf", ""))) {
                        entry.put("peer", peerUserId);
                        putManifestMeta(entry, photoSize, document);
                        uploadIntents.put(location, entry.toString());
                        markDirtyLocked();
                        return;
                    }
                }
                entry = existing != null ? new JSONObject(existing) : new JSONObject();
                entry.put("peer", peerUserId);
                entry.put("kf", "ss");
                entry.put("ts", System.currentTimeMillis());
                putManifestMeta(entry, photoSize, document);
                uploadIntents.put(location, entry.toString());
                markDirtyLocked();
            } catch (Exception e) {
                FileLog.e("XoSecretStore: ss upload intent encode failed", e);
            }
        }
    }

    /** T81: TRUE when this tree needs its ss file key derived before the first part encrypts. */
    public boolean uploadNeedsDerivedKey(long treeUploadId) {
        synchronized (lock) {
            loadLocked();
            String location = uploadTreeLocations.get(treeUploadId);
            if (location == null) {
                return false;
            }
            String json = uploadIntents.get(location);
            if (json == null) {
                return false;
            }
            try {
                JSONObject entry = new JSONObject(json);
                return "ss".equals(entry.optString("kf", "")) && !entry.has("fk");
            } catch (Exception e) {
                return false;
            }
        }
    }

    /**
     * T82 — registers a "pw" upload intent for the PASSWORD-based encrypted
     * file (XOSF2). A fresh random CEK + KDF salt are minted HERE (SecureRandom
     * nextBytes is microseconds — safe on any thread) and persisted
     * keystore-encrypted together with the user-typed رمز, so resumed uploads
     * after a process death stay decryptable. Refresh semantics: re-scheduling
     * the same location keeps the SAME cek/salt/password (idempotent).
     */
    public void noteUploadIntentPw(String location, long peerUserId, TLRPC.PhotoSize photoSize,
                                   TLRPC.TL_document document, String password) {
        synchronized (lock) {
            loadLocked();
            try {
                JSONObject entry;
                String existing = uploadIntents.get(location);
                if (existing != null) {
                    entry = new JSONObject(existing);
                    if ("pw".equals(entry.optString("kf", "")) && entry.has("fk") && entry.has("s")) {
                        entry.put("peer", peerUserId);
                        putManifestMeta(entry, photoSize, document);
                        uploadIntents.put(location, entry.toString());
                        markDirtyLocked();
                        return;
                    }
                }
                byte[][] minted = XoFilePassCrypto.mintCek();
                entry = existing != null ? new JSONObject(existing) : new JSONObject();
                entry.put("peer", peerUserId);
                entry.put("kf", "pw");
                entry.put("fk", XoSecretEnvelope.b64Encode(minted[0]));
                entry.put("s", XoSecretEnvelope.b64Encode(minted[1]));
                entry.put("i", XoFilePassCrypto.PBKDF2_ITERS);
                entry.put("pw", password == null ? "" : password);
                entry.put("ts", System.currentTimeMillis());
                putManifestMeta(entry, photoSize, document);
                uploadIntents.put(location, entry.toString());
                markDirtyLocked();
            } catch (Exception e) {
                FileLog.e("XoSecretStore: pw upload intent encode failed", e);
            }
        }
    }

    /** T82: TRUE when this tree carries a password-based (XOSF2) intent. */
    public boolean uploadIsPwIntent(long treeUploadId) {
        synchronized (lock) {
            loadLocked();
            String location = uploadTreeLocations.get(treeUploadId);
            String json = location == null ? null : uploadIntents.get(location);
            if (json == null) {
                return false;
            }
            try {
                return "pw".equals(new JSONObject(json).optString("kf", ""));
            } catch (Exception e) {
                return false;
            }
        }
    }

    /** T82: the KDF salt of a pw intent, or null. */
    public byte[] uploadSaltForTree(long treeUploadId) {
        synchronized (lock) {
            loadLocked();
            String location = uploadTreeLocations.get(treeUploadId);
            String json = location == null ? null : uploadIntents.get(location);
            if (json == null) {
                return null;
            }
            try {
                return XoSecretEnvelope.b64Decode(new JSONObject(json).optString("s", ""));
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** T82: the user-typed رمز of a pw intent (keystore-protected at rest), or null. */
    public String uploadPassForTree(long treeUploadId) {
        synchronized (lock) {
            loadLocked();
            String location = uploadTreeLocations.get(treeUploadId);
            String json = location == null ? null : uploadIntents.get(location);
            if (json == null) {
                return null;
            }
            try {
                String pw = new JSONObject(json).optString("pw", "");
                return pw.length() > 0 ? pw : null;
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** T81: stores the once-derived file key on an ss intent (idempotent). */
    public void noteDerivedUploadKey(long treeUploadId, byte[] key) {
        if (key == null || key.length != XoE2EEMedia.FILE_KEY_LEN) {
            return;
        }
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
                if (!entry.has("fk")) { // never overwrite a minted/derived key
                    entry.put("fk", XoSecretEnvelope.b64Encode(key));
                    uploadIntents.put(location, entry.toString());
                    markDirtyLocked();
                }
            } catch (Exception e) {
                FileLog.e("XoSecretStore: derived key note failed", e);
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
                JSONObject entry = new JSONObject(json);
                // T81: a KEYLESS ss intent has NO fk — the caller must derive
                // and note the key first (uploadNeedsDerivedKey). Returning an
                // EMPTY array here would break that contract (non-null empty
                // key skips the derivation block and fails every chunk).
                if (!entry.has("fk")) {
                    return null;
                }
                byte[] key = XoSecretEnvelope.b64Decode(entry.optString("fk", ""));
                return key.length == XoE2EEMedia.FILE_KEY_LEN ? key : null;
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

    // ------------------------------------------------------------------ T82: password-based files

    /** Registers a LOCKED XOSF2 file id (the download gate refuses it). */
    public void notePwLockedFile(long backendFileId) {
        if (backendFileId <= 0) {
            return;
        }
        synchronized (lock) {
            loadLocked();
            if (pwLockedFiles.add(backendFileId)) {
                markDirtyLocked();
            }
        }
    }

    /** Unlocked (or send-side registered) — the download gate releases it. */
    public void clearPwLockedFile(long backendFileId) {
        if (backendFileId <= 0) {
            return;
        }
        synchronized (lock) {
            loadLocked();
            if (pwLockedFiles.remove(backendFileId)) {
                markDirtyLocked();
            }
        }
    }

    public boolean isPwLockedFile(long backendFileId) {
        synchronized (lock) {
            loadLocked();
            return pwLockedFiles.contains(backendFileId);
        }
    }

    /** Remembers the file رمز for a canonical pair (keystore-protected). */
    public void noteChatFilePass(long canonicalPairId, String password) {
        if (canonicalPairId <= 0 || password == null || password.length() == 0) {
            return;
        }
        synchronized (lock) {
            loadLocked();
            filePasswords.put(canonicalPairId, password);
            markDirtyLocked();
        }
    }

    /** @return the remembered file رمز for a canonical pair, or null. */
    public String chatFilePass(long canonicalPairId) {
        synchronized (lock) {
            loadLocked();
            return filePasswords.get(canonicalPairId);
        }
    }

    public void forgetChatFilePass(long canonicalPairId) {
        synchronized (lock) {
            loadLocked();
            if (filePasswords.remove(canonicalPairId) != null) {
                markDirtyLocked();
            }
        }
    }

    // ------------------------------------------------------------------ wipe

    /** Destroys ALL secret-chat state (logout). Clears the e2ee file registry too. */
    public void wipe() {
        synchronized (lock) {
            wipeCoreLocked();
            XoE2eeFileRegistry.getInstance(account).wipe();
            dirty = false;
            File file = blobFile();
            if (file.exists() && !file.delete()) {
                FileLog.e("XoSecretStore: unable to delete " + file.getName());
            }
        }
    }

    /**
     * T86: the corrupt-blob recovery path. Clears the same in-memory state
     * as {@link #wipe()} but PRESERVES the e2ee file registry — the
     * registry is the plaintext, keystore-independent record of which file
     * ids are encrypted, and its fail-closed gate is exactly what protects
     * old media when the keystore dies and the keys become unrecoverable.
     * Wiping it here would re-open the pre-T86 ciphertext-pass-through hole
     * for every message recorded before the wipe.
     */
    private void wipeBlobCorruptLocked() {
        wipeCoreLocked();
        dirty = false;
        File file = blobFile();
        if (file.exists() && !file.delete()) {
            FileLog.e("XoSecretStore: unable to delete " + file.getName());
        }
    }

    private void wipeCoreLocked() {
        privateKey = null;
        publicKey = null;
        registeredThisInstall = false;
        peerKeys.clear();
        mediaKeys.clear();
        albumThumbKeys.clear();
        sentInners.clear();
        uploadIntents.clear();
        uploadTreeLocations.clear();
        filePasswords.clear();
        pwLockedFiles.clear();
        mediaJournalKey = null;
        if (mediaKeyJournal != null) {
            mediaKeyJournal.wipe();
            mediaKeyJournal = null;
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

            JSONObject fp = new JSONObject();
            for (Map.Entry<Long, String> e : filePasswords.entrySet()) {
                fp.put(String.valueOf(e.getKey()), e.getValue());
            }
            json.put("filepasses", fp);

            org.json.JSONArray pl = new org.json.JSONArray();
            for (Long fid : pwLockedFiles) {
                pl.put(fid.longValue());
            }
            json.put("pwlocked", pl);

            // T86: the media-key journal key — a random AES-256 key wrapped by
            // the keystore-protected blob. Journal appends stay O(1) software
            // AES-GCM ops; losing the blob loses the journal (by design — the
            // whole store is unreadable then, and the e2ee file registry's
            // fail-closed gate is the backstop).
            if (mediaJournalKey != null) {
                json.put("mkj", XoSecretEnvelope.b64Encode(mediaJournalKey));
            }

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
            JSONObject fp = json.optJSONObject("filepasses");
            if (fp != null) {
                java.util.Iterator<String> it = fp.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    filePasswords.put(Long.parseLong(k), fp.optString(k));
                }
            }
            org.json.JSONArray pla = json.optJSONArray("pwlocked");
            if (pla != null) {
                for (int a = 0; a < pla.length(); a++) {
                    pwLockedFiles.add(pla.optLong(a));
                }
            }
            String mkj = json.optString("mkj", null);
            if (mkj != null && mkj.length() > 0) {
                try {
                    mediaJournalKey = XoSecretEnvelope.b64Decode(mkj);
                } catch (Exception e) {
                    FileLog.e("XoSecretStore: journal key decode failed", e);
                    mediaJournalKey = null;
                }
            }
        } catch (Throwable t) {
            // corrupt blob or keystore invalidation: our OWN key pair is the
            // only critical state — it regenerates and re-registers on next
            // use, and peers learn the new key from the registry/envelopes.
            // T86: the e2ee file registry SURVIVES this wipe (see
            // wipeBlobCorruptLocked) so the download gate stays fail-closed
            // for every encrypted file the keys of which died with the blob.
            FileLog.e("XoSecretStore: state unreadable, wiping", t);
            wipeBlobCorruptLocked();
        }
    }

    // ------------------------------------------------------------------ media-key journal

    /** T86: random AES-256 journal key (wrapped in the keystore-protected blob). */
    private byte[] mediaJournalKey;

    private MediaKeyJournal journalLocked() {
        if (mediaJournalKey == null) {
            mediaJournalKey = new byte[32];
            new java.security.SecureRandom().nextBytes(mediaJournalKey);
            markDirtyLocked(); // persist the key BEFORE the journal is used
        }
        if (mediaKeyJournal == null) {
            mediaKeyJournal = new MediaKeyJournal();
            mediaKeyJournal.initLocked(mediaJournalKey, mediaKeys);
        }
        return mediaKeyJournal;
    }

    /**
     * T86 — unbounded persistent store for e2ee media keys, replacing the
     * old 400-entry LRU as the lifetime guarantee. Append-only binary log:
     * each record is one file-id → meta-json mapping, AES-GCM encrypted with
     * the journal key from the main blob. Appends are O(1); loads decrypt
     * with software AES (no keystore round-trips); compaction rewrites the
     * file (dedupe only — nothing is dropped). A journal that fails to
     * decrypt with the CURRENT key (blob wiped → new key) self-resets —
     * stale records are worthless, and the e2ee file registry keeps the
     * download gate fail-closed for the ids the lost journal covered.
     */
    private final class MediaKeyJournal {

        private static final int MAX_JOURNAL_ENTRIES = 50_000;
        private static final long MAX_JOURNAL_BYTES = 8L * 1024 * 1024;

        private final HashMap<Long, String> entries = new HashMap<>();
        private boolean initialized;
        private int rawRecords;
        private FileOutputStream appendStream;
        private boolean appendHeaderWritten;

        private static final byte[] HEADER = {'X', 'O', 'M', 'K', 1};

        void initLocked(byte[] key, LinkedHashMap<Long, String> hotCache) {
            if (initialized) {
                return;
            }
            initialized = true;
            File file = journalFile();
            if (!file.exists()) {
                // one-time migration: carry the blob's LRU entries (the only
                // keys the pre-T86 builds had) into the durable journal
                for (Map.Entry<Long, String> e : hotCache.entrySet()) {
                    put(e.getKey(), e.getValue());
                }
                return;
            }
            loadLocked(key, file);
        }

        void put(long backendFileId, String metaJson) {
            if (backendFileId <= 0 || metaJson == null || metaJson.isEmpty()) {
                return;
            }
            try {
                JSONObject payload = new JSONObject();
                payload.put("i", backendFileId);
                payload.put("m", metaJson);
                byte[] plain = payload.toString().getBytes("UTF-8");
                byte[] iv = new byte[12];
                new java.security.SecureRandom().nextBytes(iv);
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                        new javax.crypto.spec.SecretKeySpec(mediaJournalKey, "AES"),
                        new javax.crypto.spec.GCMParameterSpec(128, iv));
                byte[] ct = cipher.doFinal(plain);
                if (!initialized) {
                    // journal key minted during a put before init — init lazily
                    initLocked(mediaJournalKey, mediaKeys);
                }
                File file = journalFile();
                if (appendStream == null) {
                    appendStream = new FileOutputStream(file, true);
                    appendHeaderWritten = false;
                }
                if (!appendHeaderWritten && file.length() == 0) {
                    // T86 fix (run 181): a FRESH journal must carry the magic
                    // header or the loader discards the whole file as
                    // "unrecognized" — appends without it lost every key on
                    // the first restart. Header goes before the first record.
                    appendStream.write(HEADER);
                    appendHeaderWritten = true;
                }
                byte[] len = new byte[2];
                int recordLen = iv.length + ct.length;
                len[0] = (byte) recordLen;
                len[1] = (byte) (recordLen >> 8);
                appendStream.write(len);
                appendStream.write(iv);
                appendStream.write(ct);
                appendStream.flush();
                entries.put(backendFileId, metaJson);
                rawRecords++;
                compactIfNeededLocked(file);
            } catch (Throwable t) {
                FileLog.e("XoSecretStore: media-key journal put failed", t);
                closeAppendStreamLocked();
            }
        }

        String get(long backendFileId) {
            return entries.get(backendFileId);
        }

        void wipe() {
            entries.clear();
            rawRecords = 0;
            closeAppendStreamLocked();
            File file = journalFile();
            if (file.exists() && !file.delete()) {
                FileLog.e("XoSecretStore: unable to delete " + file.getName());
            }
        }

        private void loadLocked(byte[] key, File file) {
            FileInputStream in = null;
            try {
                byte[] buf = new byte[(int) file.length()];
                in = new FileInputStream(file);
                int off = 0;
                while (off < buf.length) {
                    int read = in.read(buf, off, buf.length - off);
                    if (read < 0) {
                        break;
                    }
                    off += read;
                }
                if (buf.length < 5 || buf[0] != 'X' || buf[1] != 'O' || buf[2] != 'M'
                        || buf[3] != 'K' || buf[4] != 1) {
                    FileLog.e("XoSecretStore: media-key journal unrecognized, starting fresh");
                    return;
                }
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(key, "AES");
                int pos = 5;
                while (pos + 2 <= buf.length) {
                    int recordLen = ((buf[pos] & 0xFF) | ((buf[pos + 1] & 0xFF) << 8));
                    if (recordLen <= 12 || pos + 2 + recordLen > buf.length) {
                        // torn tail — keep everything before it
                        break;
                    }
                    try {
                        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec,
                                new javax.crypto.spec.GCMParameterSpec(128, buf, pos + 2, 12));
                        byte[] plain = cipher.doFinal(buf, pos + 14, recordLen - 12);
                        JSONObject payload = new JSONObject(new String(plain, "UTF-8"));
                        long id = payload.optLong("i", 0);
                        String meta = payload.optString("m", null);
                        if (id > 0 && meta != null) {
                            entries.put(id, meta);
                            rawRecords++;
                        }
                    } catch (Throwable authFail) {
                        // GCM auth failure with the CURRENT journal key = the
                        // journal was written by a lost key generation (store
                        // was wiped and re-minted). Stale records are useless.
                        FileLog.e("XoSecretStore: media-key journal key mismatch, resetting journal");
                        entries.clear();
                        rawRecords = 0;
                        closeAppendStreamLocked();
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                        return;
                    }
                    pos += 2 + recordLen;
                }
            } catch (Throwable t) {
                FileLog.e("XoSecretStore: media-key journal load failed", t);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        }

        private void compactIfNeededLocked(File file) {
            if (rawRecords <= entries.size() * 4
                    || rawRecords <= 512
                    || entries.size() >= MAX_JOURNAL_ENTRIES
                    || file.length() <= MAX_JOURNAL_BYTES) {
                return;
            }
            closeAppendStreamLocked();
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(tmp);
                out.write(HEADER);
                for (Map.Entry<Long, String> e : entries.entrySet()) {
                    JSONObject payload = new JSONObject();
                    payload.put("i", e.getKey());
                    payload.put("m", e.getValue());
                    byte[] plain = payload.toString().getBytes("UTF-8");
                    byte[] iv = new byte[12];
                    new java.security.SecureRandom().nextBytes(iv);
                    javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                            new javax.crypto.spec.SecretKeySpec(mediaJournalKey, "AES"),
                            new javax.crypto.spec.GCMParameterSpec(128, iv));
                    byte[] ct = cipher.doFinal(plain);
                    int recordLen = iv.length + ct.length;
                    out.write((byte) recordLen);
                    out.write((byte) (recordLen >> 8));
                    out.write(iv);
                    out.write(ct);
                }
                out.flush();
                out.getFD().sync();
                out.close();
                out = null;
                if (!tmp.renameTo(file)) {
                    file.delete();
                    if (!tmp.renameTo(file)) {
                        throw new IllegalStateException("journal compaction rename failed");
                    }
                }
                rawRecords = entries.size();
            } catch (Throwable t) {
                FileLog.e("XoSecretStore: journal compaction failed (will retry)", t);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        }

        private void closeAppendStreamLocked() {
            if (appendStream != null) {
                try {
                    appendStream.close();
                } catch (Exception ignore) {
                }
                appendStream = null;
                appendHeaderWritten = false;
            }
        }

        private File journalFile() {
            Context context = ApplicationLoader.applicationContext;
            return new File(context.getFilesDir(), "xo_media_keys_" + account + ".bin");
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
        XoE2eeFileRegistry.resetForTests();
    }
}

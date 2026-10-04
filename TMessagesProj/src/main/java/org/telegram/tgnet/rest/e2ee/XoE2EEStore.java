package org.telegram.tgnet.rest.e2ee;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.state.IdentityKeyStore;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.PreKeyStore;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SessionStore;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * T71 — the encrypted local state of the Signal Protocol for ONE account:
 * identity keys, sessions (ratchet state), one-time prekeys, signed prekeys,
 * trust decisions and the per-file media keys — everything the protocol
 * spec says must live ONLY on the device.
 *
 * <p>Persistence model (the RestAuthStore house pattern, applied to the
 * protocol state): the whole state is one JSON document serialized to a
 * per-account blob file, encrypted with AES-256/GCM under a non-exportable
 * Android Keystore key (alias {@code xo_e2ee_<account>}, API 23+). On API
 * 19–22 the blob sits in app-private storage — the exact protection level
 * the fork already accepted for auth tokens (never worse than before,
 * documented limitation). A blob that fails to decrypt (device restore /
 * keystore invalidation) is treated as absent and WIPED: the account then
 * re-registers a fresh identity — losing crypto state is recoverable;
 * shipping a crash is not.
 *
 * <p>Trust model (TOFU + pinning — the Signal decision, docs/E2EE.md §trust):
 * <ul>
 *   <li>first sighting of a peer identity key → saved and trusted;</li>
 *   <li>same key later → trusted;</li>
 *   <li>DIFFERENT key later → NOT trusted. {@link #isTrustedIdentity}
 *       returns false (libsignal then refuses the operation with
 *       UntrustedIdentityException) and the peer is marked FLAGGED — the UI
 *       shows a security warning and sending stops until the user verifies
 *       the new safety number and resets the session. A malicious server can
 *       therefore never silently swap a peer's identity key.</li>
 * </ul>
 *
 * <p>Threading: every access is guarded by the instance lock; each mutating
 * operation persists synchronously (writes are tiny — sessions are compact
 * protobufs — and crash-consistency of ratchet state matters more than raw
 * throughput here; the atomic-write + fsync below makes every save
 * power-safe, per the "crash cannot corrupt ratchet state" acceptance
 * criterion).
 */
public final class XoE2EEStore implements IdentityKeyStore, SessionStore, PreKeyStore, SignedPreKeyStore {

    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALIAS_PREFIX = "xo_e2ee_";
    private static final String BLOB_FILE_PREFIX = "xo_e2ee_state_";
    private static final String BLOB_FILE_SUFFIX = ".bin";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;

    /** Single Signal device slot per account in v1 (multi-device is a later milestone). */
    public static final int DEVICE_ID = 1;

    private static final XoE2EEStore[] instances = new XoE2EEStore[UserConfig.MAX_ACCOUNT_COUNT];

    public static XoE2EEStore getInstance(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            FileLog.e("XoE2EEStore: invalid account " + account + ", clamping to 0");
            account = 0;
        }
        XoE2EEStore store;
        synchronized (XoE2EEStore.class) {
            store = instances[account];
            if (store == null) {
                store = new XoE2EEStore(account);
                instances[account] = store;
            }
        }
        return store;
    }

    // ------------------------------------------------------------------ state

    private final int account;
    private final Object lock = new Object();

    // identity
    private byte[] identityPrivateKey;   // 32B Curve25519 private
    private byte[] identityPublicKey;    // 33B serialized IdentityKey
    private int registrationId;

    // prekeys: id -> serialized record
    private final Map<Integer, byte[]> preKeys = new HashMap<>();
    private final Map<Integer, byte[]> signedPreKeys = new HashMap<>();

    // sessions: "name:deviceId" -> serialized SessionRecord
    private final Map<String, byte[]> sessions = new HashMap<>();

    // trust: name -> serialized IdentityKey; flagged = identity CHANGED under us
    private final Map<String, byte[]> trustedIdentities = new HashMap<>();
    private final Set<String> flaggedPeers = new HashSet<>();
    private final Set<String> verifiedPeers = new HashSet<>();

    // media: backendFileId -> envelope meta JSON (contains the SECRET file key)
    private final Map<Long, String> mediaKeys = new HashMap<>();

    // pending upload intents: location -> {peer, key}; tree upload id -> location
    private final Map<String, String> uploadIntents = new HashMap<>();
    private final Map<Long, String> uploadTreeLocations = new HashMap<>();

    private boolean loaded;
    private boolean dirty;

    private XoE2EEStore(int account) {
        this.account = account;
    }

    // ------------------------------------------------------------------ identity lifecycle

    /** @return true when this account already has a local Signal identity. */
    public boolean hasIdentity() {
        synchronized (lock) {
            loadLocked();
            return identityPrivateKey != null;
        }
    }

    /** Generates the account identity (private part NEVER leaves this store). */
    public IdentityKeyPair generateIdentityIfAbsent() {
        synchronized (lock) {
            loadLocked();
            if (identityPrivateKey != null) {
                return new IdentityKeyPair(identityPairBytes());
            }
            IdentityKeyPair pair = org.whispersystems.libsignal.util.KeyHelper.generateIdentityKeyPair();
            identityPrivateKey = pair.getPrivateKey().serialize();
            identityPublicKey = pair.getPublicKey().serialize();
            registrationId = org.whispersystems.libsignal.util.KeyHelper.generateRegistrationId(false);
            markDirtyLocked();
            return pair;
        }
    }

    public IdentityKeyPair getIdentityKeyPairObj() {
        synchronized (lock) {
            loadLocked();
            if (identityPrivateKey == null) {
                return null;
            }
            try {
                return new IdentityKeyPair(new IdentityKey(identityPublicKey, 0),
                        Curve.decodePrivatePoint(identityPrivateKey));
            } catch (InvalidKeyException e) {
                FileLog.e("XoE2EEStore: identity unreadable, wiping", e);
                wipeLocked();
                return null;
            }
        }
    }

    public int getRegistrationIdObj() {
        synchronized (lock) {
            loadLocked();
            return registrationId;
        }
    }

    // ------------------------------------------------------------------ IdentityKeyStore

    @Override
    public IdentityKeyPair getIdentityKeyPair() {
        IdentityKeyPair pair = getIdentityKeyPairObj();
        if (pair == null) {
            // never return null to libsignal: it would NPE deep inside the protocol;
            // generateIdentityIfAbsent() must have run first — throw a clear error instead
            throw new IllegalStateException("e2ee identity missing (registration flow bug)");
        }
        return pair;
    }

    @Override
    public int getLocalRegistrationId() {
        return getRegistrationIdObj();
    }

    @Override
    public boolean saveIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
        synchronized (lock) {
            loadLocked();
            String name = address.getName();
            byte[] stored = trustedIdentities.get(name);
            byte[] incoming = identityKey.serialize();
            if (stored != null && java.util.Arrays.equals(stored, incoming)) {
                return false; // unchanged
            }
            trustedIdentities.put(name, incoming);
            markDirtyLocked();
            return true;
        }
    }

    @Override
    public boolean isTrustedIdentity(SignalProtocolAddress address, IdentityKey identityKey, Direction direction) {
        synchronized (lock) {
            loadLocked();
            String name = address.getName();
            byte[] stored = trustedIdentities.get(name);
            byte[] incoming = identityKey.serialize();
            if (stored == null) {
                return true; // TOFU: first sighting, saveIdentity() pins it during session build
            }
            if (java.util.Arrays.equals(stored, incoming)) {
                return true;
            }
            // SERVER-SUBSTITUTION ATTEMPT: the pinned key differs. Refuse + flag.
            if (!flaggedPeers.contains(name)) {
                flaggedPeers.add(name);
                verifiedPeers.remove(name);
                markDirtyLocked();
                FileLog.e("XoE2EEStore: IDENTITY KEY CHANGED for peer " + name + " — flagged, session refused");
            }
            return false;
        }
    }

    @Override
    public IdentityKey getIdentity(SignalProtocolAddress address) {
        synchronized (lock) {
            loadLocked();
            byte[] stored = trustedIdentities.get(address.getName());
            try {
                return stored == null ? null : new IdentityKey(stored, 0);
            } catch (InvalidKeyException e) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ SessionStore

    private static String sessionKey(SignalProtocolAddress address) {
        return address.getName() + ":" + address.getDeviceId();
    }

    @Override
    public SessionRecord loadSession(SignalProtocolAddress address) {
        synchronized (lock) {
            loadLocked();
            byte[] data = sessions.get(sessionKey(address));
            if (data == null) {
                return new SessionRecord();
            }
            try {
                return new SessionRecord(data);
            } catch (Exception e) {
                FileLog.e("XoE2EEStore: corrupt session record, discarding", e);
                sessions.remove(sessionKey(address));
                markDirtyLocked();
                return new SessionRecord();
            }
        }
    }

    @Override
    public List<Integer> getSubDeviceSessions(String name) {
        synchronized (lock) {
            loadLocked();
            List<Integer> out = new ArrayList<>();
            for (String key : sessions.keySet()) {
                int sep = key.lastIndexOf(':');
                if (sep <= 0 || !key.substring(0, sep).equals(name)) {
                    continue;
                }
                int deviceId = Integer.parseInt(key.substring(sep + 1));
                if (deviceId != DEVICE_ID) {
                    out.add(deviceId);
                }
            }
            return out;
        }
    }

    @Override
    public void storeSession(SignalProtocolAddress address, SessionRecord record) {
        synchronized (lock) {
            loadLocked();
            sessions.put(sessionKey(address), record.serialize());
            markDirtyLocked();
        }
    }

    @Override
    public boolean containsSession(SignalProtocolAddress address) {
        synchronized (lock) {
            loadLocked();
            byte[] data = sessions.get(sessionKey(address));
            if (data == null) {
                return false;
            }
            try {
                return new SessionRecord(data).getSessionStates().size() > 0;
            } catch (Exception e) {
                return false;
            }
        }
    }

    @Override
    public void deleteSession(SignalProtocolAddress address) {
        synchronized (lock) {
            loadLocked();
            if (sessions.remove(sessionKey(address)) != null) {
                markDirtyLocked();
            }
        }
    }

    @Override
    public void deleteAllSessions(String name) {
        synchronized (lock) {
            loadLocked();
            boolean changed = false;
            for (String key : new ArrayList<>(sessions.keySet())) {
                if (key.startsWith(name + ":")) {
                    sessions.remove(key);
                    changed = true;
                }
            }
            if (changed) {
                markDirtyLocked();
            }
        }
    }

    // ------------------------------------------------------------------ PreKeyStore / SignedPreKeyStore

    @Override
    public PreKeyRecord loadPreKey(int preKeyId) throws org.whispersystems.libsignal.InvalidKeyIdException {
        synchronized (lock) {
            loadLocked();
            byte[] data = preKeys.get(preKeyId);
            if (data == null) {
                throw new org.whispersystems.libsignal.InvalidKeyIdException("no such prekey " + preKeyId);
            }
            try {
                return new PreKeyRecord(data);
            } catch (Exception e) {
                throw new org.whispersystems.libsignal.InvalidKeyIdException("corrupt prekey " + preKeyId);
            }
        }
    }

    @Override
    public void storePreKey(int preKeyId, PreKeyRecord record) {
        synchronized (lock) {
            loadLocked();
            preKeys.put(preKeyId, record.serialize());
            markDirtyLocked();
        }
    }

    @Override
    public boolean containsPreKey(int preKeyId) {
        synchronized (lock) {
            loadLocked();
            return preKeys.containsKey(preKeyId);
        }
    }

    @Override
    public void removePreKey(int preKeyId) {
        synchronized (lock) {
            loadLocked();
            if (preKeys.remove(preKeyId) != null) {
                markDirtyLocked();
            }
        }
    }

    @Override
    public SignedPreKeyRecord loadSignedPreKey(int signedPreKeyId) throws org.whispersystems.libsignal.InvalidKeyIdException {
        synchronized (lock) {
            loadLocked();
            byte[] data = signedPreKeys.get(signedPreKeyId);
            if (data == null) {
                throw new org.whispersystems.libsignal.InvalidKeyIdException("no such signed prekey " + signedPreKeyId);
            }
            try {
                return new SignedPreKeyRecord(data);
            } catch (Exception e) {
                throw new org.whispersystems.libsignal.InvalidKeyIdException("corrupt signed prekey " + signedPreKeyId);
            }
        }
    }

    @Override
    public List<SignedPreKeyRecord> loadSignedPreKeys() {
        synchronized (lock) {
            loadLocked();
            List<SignedPreKeyRecord> out = new ArrayList<>();
            for (byte[] data : signedPreKeys.values()) {
                try {
                    out.add(new SignedPreKeyRecord(data));
                } catch (Exception ignore) {
                }
            }
            return out;
        }
    }

    @Override
    public void storeSignedPreKey(int signedPreKeyId, SignedPreKeyRecord record) {
        synchronized (lock) {
            loadLocked();
            signedPreKeys.put(signedPreKeyId, record.serialize());
            markDirtyLocked();
        }
    }

    @Override
    public boolean containsSignedPreKey(int signedPreKeyId) {
        synchronized (lock) {
            loadLocked();
            return signedPreKeys.containsKey(signedPreKeyId);
        }
    }

    @Override
    public void removeSignedPreKey(int signedPreKeyId) {
        synchronized (lock) {
            loadLocked();
            if (signedPreKeys.remove(signedPreKeyId) != null) {
                markDirtyLocked();
            }
        }
    }

    // ------------------------------------------------------------------ protocol-state maintenance (used by the facade)

    /** Next free signed-prekey id. */
    public int nextSignedPreKeyId() {
        synchronized (lock) {
            loadLocked();
            int max = 0;
            for (Integer id : signedPreKeys.keySet()) {
                max = Math.max(max, id);
            }
            return max + 1;
        }
    }

    /** Next free one-time prekey id. */
    public int nextPreKeyId() {
        synchronized (lock) {
            loadLocked();
            int max = 0;
            for (Integer id : preKeys.keySet()) {
                max = Math.max(max, id);
            }
            return max + 1;
        }
    }

    /** How many one-time prekeys are stored locally (mirrors the server pool). */
    public int localPreKeyCount() {
        synchronized (lock) {
            loadLocked();
            return preKeys.size();
        }
    }

    /** Store serializable PreKeyRecords generated for upload (keeps private side local). */
    public void storePreKeyRecords(List<PreKeyRecord> records) {
        synchronized (lock) {
            loadLocked();
            for (PreKeyRecord r : records) {
                preKeys.put(r.getId(), r.serialize());
            }
            markDirtyLocked();
        }
    }

    /** Snapshot of the whole local one-time prekey pool (for register/refill uploads). */
    public List<PreKeyRecord> loadAllPreKeyRecords() {
        synchronized (lock) {
            loadLocked();
            List<PreKeyRecord> out = new ArrayList<>();
            for (byte[] data : preKeys.values()) {
                try {
                    out.add(new PreKeyRecord(data));
                } catch (Exception ignore) {
                }
            }
            return out;
        }
    }

    /** Test seam: forget singletons so the next getInstance starts clean (JVM suite only). */
    static void resetForTests() {
        synchronized (XoE2EEStore.class) {
            for (int a = 0; a < instances.length; a++) {
                instances[a] = null;
            }
        }
    }

    public void storeSignedPreKeyRecord(SignedPreKeyRecord record) {
        storeSignedPreKey(record.getId(), record);
    }

    // ------------------------------------------------------------------ trust flags / verification (UI surface)

    public boolean isFlagged(long userId) {
        synchronized (lock) {
            loadLocked();
            return flaggedPeers.contains(String.valueOf(userId));
        }
    }

    public void clearFlag(long userId) {
        synchronized (lock) {
            loadLocked();
            flaggedPeers.remove(String.valueOf(userId));
            markDirtyLocked();
        }
    }

    public boolean isVerified(long userId) {
        synchronized (lock) {
            loadLocked();
            return verifiedPeers.contains(String.valueOf(userId));
        }
    }

    public void setVerified(long userId, boolean verified) {
        synchronized (lock) {
            loadLocked();
            if (verified) {
                verifiedPeers.add(String.valueOf(userId));
            } else {
                verifiedPeers.remove(String.valueOf(userId));
            }
            markDirtyLocked();
        }
    }

    /** Clears the pinned identity key of a peer (user-approved reset — the
     * next bundle fetch re-pins via TOFU). Only ever called AFTER the user
     * re-verified the new safety number in the UI. */
    public void clearTrustedIdentity(long userId) {
        synchronized (lock) {
            loadLocked();
            if (trustedIdentities.remove(String.valueOf(userId)) != null) {
                markDirtyLocked();
            }
        }
    }

    /** Identity key of a peer (for safety-number display), or null. */
    public IdentityKey peerIdentity(long userId) {
        return getIdentity(new SignalProtocolAddress(String.valueOf(userId), DEVICE_ID));
    }

    // ------------------------------------------------------------------ media keys (SECRET — lives in the encrypted blob)

    public void putMediaKeys(long backendFileId, String metaJson) {
        synchronized (lock) {
            loadLocked();
            mediaKeys.put(backendFileId, metaJson);
            markDirtyLocked();
        }
    }

    public String getMediaKeys(long backendFileId) {
        synchronized (lock) {
            loadLocked();
            return mediaKeys.get(backendFileId);
        }
    }

    // ------------------------------------------------------------------ upload intents (pending uploads)

    /**
     * Registers/refreshes the upload intent for a pending 1:1 upload
     * location. The first call for a location mints the single-use file key;
     * later calls (reschedules, process-death resume) only refresh the
     * metadata manifest so the SAME key always decrypts the SAME upload.
     * Real metadata (mime/name/w/h/duration) rides along for the envelope.
     */
    public void noteUploadIntent(String location, long peerUserId, TLRPC.PhotoSize photoSize, TLRPC.TL_document document) {
        synchronized (lock) {
            loadLocked();
            try {
                JSONObject entry = new JSONObject();
                String existing = uploadIntents.get(location);
                if (existing != null) {
                    entry = new JSONObject(existing);
                    if (entry.has("fk")) {
                        // keep the minted key; refresh metadata only
                        entry.put("peer", peerUserId);
                        putManifestMeta(entry, photoSize, document);
                        uploadIntents.put(location, entry.toString());
                        markDirtyLocked();
                        return;
                    }
                }
                byte[] key = new byte[XoE2EEMedia.FILE_KEY_LEN];
                new java.security.SecureRandom().nextBytes(key);
                entry.put("peer", peerUserId);
                entry.put("fk", XoE2EEEnvelope.b64Encode(key));
                entry.put("ts", System.currentTimeMillis());
                putManifestMeta(entry, photoSize, document);
                uploadIntents.put(location, entry.toString());
                markDirtyLocked();
            } catch (Exception e) {
                FileLog.e("XoE2EEStore: upload intent encode failed", e);
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
                }
            }
        } else if (photoSize != null && photoSize.w > 0) {
            // photo: the tree classifies it from the mime in the envelope;
            // JPEG is the only format the send pipeline uploads as a photo
            entry.put("mi", "image/jpeg");
            entry.put("w", photoSize.w);
            entry.put("h", photoSize.h);
        }
    }

    /** Binds a tree upload id to a pending location intent (called from FileUploadOperation). */
    public void bindTreeUploadId(String location, long treeUploadId) {
        synchronized (lock) {
            loadLocked();
            if (uploadIntents.containsKey(location)) {
                uploadTreeLocations.put(treeUploadId, location);
                markDirtyLocked();
            }
        }
    }    /** @return the file key for an in-flight encrypted upload, or null when the upload is not E2EE. */
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
                return XoE2EEEnvelope.b64Decode(new JSONObject(json).optString("fk", ""));
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

    /**
     * Tracks plaintext part sizes of an in-flight encrypted upload so the
     * message envelope can declare the exact plaintext length. Idempotent
     * per part (a re-uploaded part overwrites its own entry — the uploader
     * retries parts on transport hiccups).
     */
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
                FileLog.e("XoE2EEStore: noteUploadPart failed", e);
            }
        }
    }

    /**
     * @return {plaintextLen, chunkSize} totals derived from the tracked
     * parts: every non-final part equals the max part length (the uploader
     * reads fixed-size chunks; only the last read is short).
     */
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

    /** Full manifest of an in-flight upload (peer, key, part sizes, real metadata). */
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

    /** Direct tree-id intent for uploads that bypass the location flow (blocking small thumbs). */
    public void putDirectTreeIntent(long treeUploadId, long peerUserId, byte[] fileKey) {
        synchronized (lock) {
            loadLocked();
            try {
                JSONObject json = new JSONObject();
                json.put("peer", peerUserId);
                json.put("fk", XoE2EEEnvelope.b64Encode(fileKey));
                json.put("ts", System.currentTimeMillis());
                uploadIntents.put("tree:" + treeUploadId, json.toString());
                uploadTreeLocations.put(treeUploadId, "tree:" + treeUploadId);
                markDirtyLocked();
            } catch (Exception e) {
                FileLog.e("XoE2EEStore: direct intent failed", e);
            }
        }
    }

    /** Upload finished (or failed) — drop the intent; the key now lives in the message envelope. */
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

    /** Destroys ALL protocol state (logout / keystore-invalidation recovery). */
    public void wipe() {
        synchronized (lock) {
            wipeLocked();
        }
    }

    private void wipeLocked() {
        identityPrivateKey = null;
        identityPublicKey = null;
        registrationId = 0;
        preKeys.clear();
        signedPreKeys.clear();
        sessions.clear();
        trustedIdentities.clear();
        flaggedPeers.clear();
        verifiedPeers.clear();
        mediaKeys.clear();
        uploadIntents.clear();
        uploadTreeLocations.clear();
        dirty = false;
        File file = blobFile();
        if (file.exists() && !file.delete()) {
            FileLog.e("XoE2EEStore: unable to delete " + file.getName());
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
            json.put("ipk", identityPrivateKey == null ? null : XoE2EEEnvelope.b64Encode(identityPrivateKey));
            json.put("ipub", identityPublicKey == null ? null : XoE2EEEnvelope.b64Encode(identityPublicKey));
            json.put("regid", registrationId);

            JSONObject pre = new JSONObject();
            for (Map.Entry<Integer, byte[]> e : preKeys.entrySet()) {
                pre.put(String.valueOf(e.getKey()), XoE2EEEnvelope.b64Encode(e.getValue()));
            }
            json.put("prekeys", pre);

            JSONObject spre = new JSONObject();
            for (Map.Entry<Integer, byte[]> e : signedPreKeys.entrySet()) {
                spre.put(String.valueOf(e.getKey()), XoE2EEEnvelope.b64Encode(e.getValue()));
            }
            json.put("signedprekeys", spre);

            JSONObject ses = new JSONObject();
            for (Map.Entry<String, byte[]> e : sessions.entrySet()) {
                ses.put(e.getKey(), XoE2EEEnvelope.b64Encode(e.getValue()));
            }
            json.put("sessions", ses);

            JSONObject trust = new JSONObject();
            for (Map.Entry<String, byte[]> e : trustedIdentities.entrySet()) {
                trust.put(e.getKey(), XoE2EEEnvelope.b64Encode(e.getValue()));
            }
            json.put("trust", trust);

            json.put("flagged", new JSONArray(new ArrayList<>(flaggedPeers)));
            json.put("verified", new JSONArray(new ArrayList<>(verifiedPeers)));

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

            byte[] plain = json.toString().getBytes("UTF-8");
            byte[] blob = protect(plain);
            atomicWrite(blobFile(), blob);
            dirty = false;
        } catch (Throwable t) {
            // never crash the app for storage errors; state stays in memory and
            // the next mutation retries the write
            FileLog.e("XoE2EEStore: persist failed (will retry on next mutation)", t);
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
            registrationId = json.optInt("regid", 0);
            String ipk = json.optString("ipk", null);
            String ipub = json.optString("ipub", null);
            identityPrivateKey = ipk == null ? null : XoE2EEEnvelope.b64Decode(ipk);
            identityPublicKey = ipub == null ? null : XoE2EEEnvelope.b64Decode(ipub);

            JSONObject pre = json.optJSONObject("prekeys");
            if (pre != null) {
                java.util.Iterator<String> it = pre.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    preKeys.put(Integer.parseInt(k), XoE2EEEnvelope.b64Decode(pre.optString(k)));
                }
            }
            JSONObject spre = json.optJSONObject("signedprekeys");
            if (spre != null) {
                java.util.Iterator<String> it = spre.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    signedPreKeys.put(Integer.parseInt(k), XoE2EEEnvelope.b64Decode(spre.optString(k)));
                }
            }
            JSONObject ses = json.optJSONObject("sessions");
            if (ses != null) {
                java.util.Iterator<String> it = ses.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    sessions.put(k, XoE2EEEnvelope.b64Decode(ses.optString(k)));
                }
            }
            JSONObject trust = json.optJSONObject("trust");
            if (trust != null) {
                java.util.Iterator<String> it = trust.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    trustedIdentities.put(k, XoE2EEEnvelope.b64Decode(trust.optString(k)));
                }
            }
            JSONArray flagged = json.optJSONArray("flagged");
            if (flagged != null) {
                for (int a = 0; a < flagged.length(); a++) {
                    flaggedPeers.add(flagged.optString(a));
                }
            }
            JSONArray verified = json.optJSONArray("verified");
            if (verified != null) {
                for (int a = 0; a < verified.length(); a++) {
                    verifiedPeers.add(verified.optString(a));
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
        } catch (Throwable t) {
            // corrupted blob or keystore invalidation after a device restore:
            // the protocol state is UNRECOVERABLE by design (no escrow) — start clean
            FileLog.e("XoE2EEStore: state unreadable, wiping crypto state", t);
            wipeLocked();
        }
    }

    // ------------------------------------------------------------------ blob protection (Keystore, RestAuthStore pattern)

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
        return plain; // API 19-22: app-private storage, same precedent as RestAuthStore (documented)
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
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS_PREFIX + account, null);
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
                throw new IllegalStateException("unable to persist e2ee state blob");
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
            FileLog.e("XoE2EEStore: readFile failed", e);
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
}

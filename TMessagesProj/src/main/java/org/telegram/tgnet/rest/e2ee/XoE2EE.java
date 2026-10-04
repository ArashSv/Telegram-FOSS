package org.telegram.tgnet.rest.e2ee;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.SessionBuilder;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.UntrustedIdentityException;
import org.whispersystems.libsignal.fingerprint.Fingerprint;
import org.whispersystems.libsignal.fingerprint.NumericFingerprintGenerator;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.PreKeySignalMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.state.PreKeyBundle;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.util.KeyHelper;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * T71 — the E2EE facade for 1:1 (private, non-self) chats. The ONLY class
 * the messaging pipeline talks to; everything else (dispatcher, mapper,
 * file loader) calls these static-shaped per-account methods.
 *
 * <p>Crypto protocol: Signal (X3DH via prekey bundles + Double Ratchet),
 * implemented by libsignal 2.8.1 — no hand-rolled protocol. This class only
 * wires the protocol to OUR transport (REST) and OUR storage
 * ({@link XoE2EEStore}).
 *
 * <p>Registration flow (mirrors the Signal spec / Sesame model):
 * <ol>
 *   <li>generate identity + signed prekey + 100 one-time prekeys locally;</li>
 *   <li>upload ONLY public parts to the server (keys/register);</li>
 *   <li>refill OTKs server-side whenever the bundle answer reports a low pool.</li>
 * </ol>
 *
 * <p>Sending: lazily fetch the peer's prekey bundle once, build the session
 * (X3DH on device), then encrypt every message with the ratcheting cipher.
 * Receiving: mirror path — the first prekey message rebuilds the session
 * automatically inside libsignal; the server only ever relayed ciphertext.
 *
 * <p>Failure policy (user requirement: NEVER a silent plaintext fallback):
 * if a private-chat send cannot be encrypted (no bundle on the server =
 * peer runs an old client, flagged identity, transport failure), the send
 * FAILS with {@link E2eeUnavailableException} — the message shows the
 * standard error state in the UI. No plaintext of a private chat ever
 * reaches the server from this build.
 */
public final class XoE2EE {

    /** Raised when a 1:1 send cannot be encrypted; NEVER swallowed into a plaintext send. */
    public static final class E2eeUnavailableException extends Exception {
        public final String reasonCode;

        public E2eeUnavailableException(String reasonCode, String message) {
            super(message);
            this.reasonCode = reasonCode;
        }
    }

    /** Bundle-fetch result the send path needs to decide between build / retry / fail. */
    private static final int OTK_REFILL_THRESHOLD = 20;
    private static final int OTK_BATCH = 100;
    private static final int MAX_ENVELOPE_CHARS = 16000; // backend MAX_CONTENT_LENGTH 16384, headroom

    private static final XoE2EE[] instances = new XoE2EE[UserConfig.MAX_ACCOUNT_COUNT];

    public static XoE2EE getInstance(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            account = 0;
        }
        XoE2EE mgr;
        synchronized (XoE2EE.class) {
            mgr = instances[account];
            if (mgr == null) {
                mgr = new XoE2EE(account);
                instances[account] = mgr;
            }
        }
        return mgr;
    }

    private final int account;
    private final Object lock = new Object();
    private final AtomicBoolean registeredThisProcess = new AtomicBoolean(false);
    private final java.util.Set<Long> bundlesFetchedThisProcess = new java.util.HashSet<>();

    private XoE2EE(int account) {
        this.account = account;
    }

    private XoE2EEStore store() {
        return XoE2EEStore.getInstance(account);
    }

    private XoE2EEApi api() {
        return XoE2EEApi.getInstance(account);
    }

    // ------------------------------------------------------------------ registration

    /**
     * Idempotent: generates + uploads keys when absent, tops up the server
     * OTK pool when low. Network errors propagate to the caller (send path
     * retries later; the async start path just logs). Returns true when the
     * account is registered (or already was).
     */
    public boolean ensureRegistered() throws Exception {
        synchronized (lock) {
            if (registeredThisProcess.get() && store().hasIdentity()) {
                return true;
            }
            IdentityKeyPair identity = store().generateIdentityIfAbsent();
            if (store().localPreKeyCount() == 0) {
                uploadNewSignedPreKeyLocked();
                uploadPreKeyBatchLocked(); // full initial batch
            }
            int remaining = api().register(identity, store().getRegistrationIdObj(), signedPreKeyJsonLocked(), oneTimeKeysJsonLocked());
            registeredThisProcess.set(true);
            FileLog.d("XoE2EE: registered account " + account + " (server otk pool " + remaining + ")");
            return true;
        }
    }

    /** Fire-and-forget registration on login/app start (never blocks the UI, never throws). */
    public void ensureRegisteredAsync() {
        if (store().hasIdentity() && registeredThisProcess.get()) {
            return;
        }
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            try {
                ensureRegistered();
            } catch (Throwable t) {
                FileLog.e("XoE2EE: async registration failed (retried before first send)", t);
            }
        });
    }

    private void uploadNewSignedPreKeyLocked() throws Exception {
        int id = store().nextSignedPreKeyId();
        SignedPreKeyRecord record = KeyHelper.generateSignedPreKey(store().getIdentityKeyPair(), id);
        store().storeSignedPreKeyRecord(record);
    }

    private List<PreKeyRecord> uploadPreKeyBatchLocked() throws Exception {
        int start = store().nextPreKeyId();
        List<PreKeyRecord> records = KeyHelper.generatePreKeys(start, OTK_BATCH);
        store().storePreKeyRecords(records);
        return records;
    }

    private JSONObject signedPreKeyJsonLocked() throws Exception {
        List<SignedPreKeyRecord> all = store().loadSignedPreKeys();
        SignedPreKeyRecord latest = all.get(all.size() - 1);
        JSONObject json = new JSONObject();
        json.put("key_id", latest.getId());
        json.put("pub", XoE2EEEnvelope.b64Encode(latest.getKeyPair().getPublicKey().serialize()));
        json.put("sig", XoE2EEEnvelope.b64Encode(latest.getSignature()));
        return json;
    }

    private JSONArray oneTimeKeysJsonLocked() throws Exception {
        return oneTimeKeysJson(store().loadAllPreKeyRecords());
    }

    private static JSONArray oneTimeKeysJson(List<PreKeyRecord> records) throws Exception {
        JSONArray arr = new JSONArray();
        for (PreKeyRecord r : records) {
            JSONObject k = new JSONObject();
            k.put("key_id", r.getId());
            k.put("pub", XoE2EEEnvelope.b64Encode(r.getKeyPair().getPublicKey().serialize()));
            arr.put(k);
        }
        return arr;
    }

    /**
     * Post-register OTK top-up: generate + upload a fresh batch (server adds;
     * consumed keys are already gone server-side).
     */
    public void refillOneTimeKeys() {
        try {
            synchronized (lock) {
                List<PreKeyRecord> batch = uploadPreKeyBatchLocked();
                api().refill(oneTimeKeysJson(batch));
                FileLog.d("XoE2EE: refilled " + batch.size() + " otk for account " + account);
            }
        } catch (Throwable t) {
            FileLog.e("XoE2EE: otk refill failed", t);
        }
    }

    // ------------------------------------------------------------------ send-side encryption

    /** @return true when 1:1 messages to this peer must be encrypted. */
    public boolean isE2eeChat(long peerUserId, long selfUserId) {
        return peerUserId > 0 && peerUserId != selfUserId;
    }

    /**
     * Encrypts an outgoing text (or edit) payload for a private chat.
     * Blocking (bundle fetch + X3DH on first contact) — call from the
     * dispatcher's IO queue only. Throws {@link E2eeUnavailableException}
     * rather than ever returning plaintext.
     */
    public String encryptForPeer(long peerUserId, String innerJson) throws E2eeUnavailableException {
        try {
            // a FLAGGED peer (identity key changed under us) stops all 1:1
            // traffic until the user re-verifies + resets — even an existing
            // session is not trusted after the pin mismatch
            if (store().isFlagged(peerUserId)) {
                throw new E2eeUnavailableException("E2EE_IDENTITY_CHANGED",
                        "recipient identity key changed; verify the safety number before continuing");
            }
            ensureRegistered();
            SessionCipher cipher = cipherFor(peerUserId);
            if (!store().containsSession(addressFor(peerUserId))) {
                fetchAndBuildSession(peerUserId);
                cipher = cipherFor(peerUserId);
            }
            CiphertextMessage cm = cipher.encrypt(innerJson.getBytes("UTF-8"));
            String envelope = XoE2EEEnvelope.wrap(cm.getType(), cm.serialize());
            if (envelope.length() > MAX_ENVELOPE_CHARS) {
                throw new E2eeUnavailableException("E2EE_PAYLOAD_TOO_LARGE", "encrypted payload exceeds transport cap");
            }
            maybeRefillOtk();
            return envelope;
        } catch (E2eeUnavailableException e) {
            throw e;
        } catch (UntrustedIdentityException e) {
            throw new E2eeUnavailableException("E2EE_IDENTITY_CHANGED",
                    "recipient identity key changed; verify the safety number before continuing");
        } catch (Exception e) {
            FileLog.e("XoE2EE: encrypt failed for peer " + peerUserId, e);
            throw new E2eeUnavailableException("E2EE_ENCRYPT_FAILED", "could not encrypt the message");
        }
    }

    /** Convenience: text message inner payload. */
    public String encryptText(long peerUserId, String text) throws E2eeUnavailableException {
        try {
            return encryptForPeer(peerUserId, XoE2EEEnvelope.innerText(text));
        } catch (Exception e) {
            throw asUnavailable(e);
        }
    }

    private E2eeUnavailableException asUnavailable(Exception e) {
        if (e instanceof E2eeUnavailableException) {
            return (E2eeUnavailableException) e;
        }
        return new E2eeUnavailableException("E2EE_ENCRYPT_FAILED", "could not encrypt the message");
    }

    private void maybeRefillOtk() {
        if (store().localPreKeyCount() < OTK_REFILL_THRESHOLD) {
            refillOneTimeKeys();
        }
    }

    // ------------------------------------------------------------------ receive-side decryption

    /**
     * Decrypts an incoming envelope. Returns the inner JSON string, or null
     * when the envelope cannot be opened (old/rotated identity, tamper,
     * unknown session) — callers then show a placeholder, never garbage, and
     * NEVER let an exception escape into the update pipeline.
     */
    public String decryptFromPeer(long peerUserId, String envelope) {
        try {
            XoE2EEEnvelope.Unwrapped unwrapped = XoE2EEEnvelope.unwrap(envelope);
            if (unwrapped == null) {
                return null;
            }
            SessionCipher cipher = cipherFor(peerUserId);
            byte[] plain;
            if (unwrapped.wireType == CiphertextMessage.PREKEY_TYPE) {
                plain = cipher.decrypt(new PreKeySignalMessage(unwrapped.body));
            } else if (unwrapped.wireType == CiphertextMessage.WHISPER_TYPE) {
                plain = cipher.decrypt(new SignalMessage(unwrapped.body));
            } else {
                return null;
            }
            return new String(plain, "UTF-8");
        } catch (org.whispersystems.libsignal.DuplicateMessageException e) {
            // protocol-level replay protection did its job; the duplicate is dropped
            FileLog.w("XoE2EE: duplicate/replayed message rejected from peer " + peerUserId);
            return null;
        } catch (UntrustedIdentityException e) {
            FileLog.e("XoE2EE: untrusted identity on decrypt from " + peerUserId);
            return null;
        } catch (Throwable t) {
            FileLog.e("XoE2EE: decrypt failed from peer " + peerUserId, t);
            return null;
        }
    }

    // ------------------------------------------------------------------ session / trust management

    private SignalProtocolAddress addressFor(long userId) {
        return new SignalProtocolAddress(String.valueOf(userId), XoE2EEStore.DEVICE_ID);
    }

    private SessionCipher cipherFor(long userId) {
        XoE2EEStore s = store();
        return new SessionCipher(s, s, s, s, addressFor(userId));
    }

    /**
     * Fetches the peer's prekey bundle and performs X3DH (SessionBuilder.process).
     * Throws when the peer has no keys (old client) or the bundle is hostile.
     */
    private void fetchAndBuildSession(long peerUserId) throws Exception {
        JSONObject bundle = api().bundle(peerUserId);
        if (bundle == null || bundle.optJSONArray("error") != null) {
            throw new E2eeUnavailableException("E2EE_NO_PEER_KEYS",
                    "recipient has no end-to-end encryption keys (needs a newer app version)");
        }
        if (!bundle.optBoolean("ok", false)) {
            throw new E2eeUnavailableException("E2EE_NO_PEER_KEYS", "bundle fetch failed");
        }
        IdentityKey identityKey = new IdentityKey(XoE2EEEnvelope.b64Decode(bundle.getString("identity_key")), 0);
        int registrationId = bundle.getInt("registration_id");
        JSONObject spk = bundle.getJSONObject("signed_prekey");
        int signedPreKeyId = spk.getInt("key_id");
        org.whispersystems.libsignal.ecc.ECPublicKey signedPreKeyPublic =
                org.whispersystems.libsignal.ecc.Curve.decodePoint(XoE2EEEnvelope.b64Decode(spk.getString("pub")), 0);
        byte[] signature = XoE2EEEnvelope.b64Decode(spk.getString("sig"));
        JSONObject otk = bundle.optJSONObject("one_time_prekey");
        Integer preKeyId = null;
        org.whispersystems.libsignal.ecc.ECPublicKey preKeyPublic = null;
        if (otk != null) {
            preKeyId = otk.getInt("key_id");
            preKeyPublic = org.whispersystems.libsignal.ecc.Curve.decodePoint(XoE2EEEnvelope.b64Decode(otk.getString("pub")), 0);
        }
        PreKeyBundle pb = new PreKeyBundle(registrationId, XoE2EEStore.DEVICE_ID,
                preKeyId == null ? 0 : preKeyId, preKeyPublic,
                signedPreKeyId, signedPreKeyPublic, signature, identityKey);

        // Trust check happens INSIDE SessionBuilder.process via our IdentityKeyStore
        // (TOFU + pinning): a substituted identity key fails the build right here.
        SessionBuilder builder = new SessionBuilder(store(), store(), store(), store(), addressFor(peerUserId));
        builder.process(pb);
        bundlesFetchedThisProcess.add(peerUserId);
    }

    /**
     * 60-digit safety number for the chat (both identity keys combined,
     * SHA-512 over 5200 iterations — the Signal display convention).
     * Returns null when either side has no identity yet.
     */
    public String safetyNumber(long selfUserId, long peerUserId) {
        try {
            IdentityKey own = store().peerIdentity(selfUserId);
            if (own == null) {
                own = store().getIdentityKeyPair().getPublicKey();
            }
            IdentityKey peer = store().peerIdentity(peerUserId);
            if (own == null || peer == null) {
                return null;
            }
            Fingerprint fp = new NumericFingerprintGenerator(5200).createFor(
                    0,
                    String.valueOf(selfUserId).getBytes("UTF-8"), own,
                    String.valueOf(peerUserId).getBytes("UTF-8"), peer);
            String text = fp.getDisplayableFingerprint().getDisplayText();
            return text == null ? null : text.trim();
        } catch (Throwable t) {
            FileLog.e("XoE2EE: safety number failed", t);
            return null;
        }
    }

    public boolean isFlagged(long peerUserId) {
        return store().isFlagged(peerUserId);
    }

    public boolean isVerified(long peerUserId) {
        return store().isVerified(peerUserId);
    }

    public void markVerified(long selfUserId, long peerUserId, String expectedSafetyNumber) {
        String actual = safetyNumber(selfUserId, peerUserId);
        if (expectedSafetyNumber != null && actual != null && !expectedSafetyNumber.equals(actual)) {
            return; // stale screen: never pin against a different number than shown
        }
        store().setVerified(peerUserId, true);
        store().clearFlag(peerUserId);
    }

    public void unverify(long peerUserId) {
        store().setVerified(peerUserId, false);
    }

    /**
     * User-approved session reset after an identity change: drop the session,
     * unflag, and UNPIN the old identity key — the next send fetches the NEW
     * key bundle and re-pins it (TOFU). Only meaningful after the user
     * re-verified the new safety number in the UI; never called implicitly.
     */
    public void resetSession(long peerUserId) {
        synchronized (lock) {
            store().deleteSession(addressFor(peerUserId));
            store().clearTrustedIdentity(peerUserId);
            store().clearFlag(peerUserId);
            bundlesFetchedThisProcess.remove(peerUserId);
        }
    }

    /** Media envelope metadata extracted from a decrypted media payload. */
    public static final class MediaMeta {
        public byte[] fileKey;
        public long plaintextLen;
        public int chunkSize;
        public String mime;
        public String name;
        public int width, height, duration;
        public String caption;
        public boolean thumbEncrypted;
        public byte[] thumbKey;
        public long thumbFileId;
    }

    public static MediaMeta parseMediaMeta(JSONObject inner) {
        MediaMeta meta = new MediaMeta();
        try {
            meta.fileKey = XoE2EEEnvelope.b64Decode(inner.getString("fk"));
            meta.plaintextLen = inner.optLong("pl", 0);
            meta.chunkSize = inner.optInt("cs", 0);
            meta.mime = inner.optString("mi", "application/octet-stream");
            meta.name = inner.isNull("na") ? null : inner.optString("na", null);
            meta.width = inner.optInt("w", 0);
            meta.height = inner.optInt("h", 0);
            meta.duration = inner.optInt("du", 0);
            meta.caption = inner.isNull("cap") ? null : inner.optString("cap", null);
            meta.thumbEncrypted = inner.optInt("th", 0) == 1;
            String tk = inner.optString("tk", null);
            meta.thumbKey = tk == null || tk.length() == 0 ? null : XoE2EEEnvelope.b64Decode(tk);
            meta.thumbFileId = inner.optLong("tf", 0);
            return meta.fileKey != null && meta.fileKey.length == XoE2EEMedia.FILE_KEY_LEN ? meta : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Persists the media keys for a backend file (both sides of the conversation). */
    public void noteMediaKeys(long backendFileId, MediaMeta meta) {
        if (backendFileId <= 0 || meta == null) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("fk", XoE2EEEnvelope.b64Encode(meta.fileKey));
            json.put("pl", meta.plaintextLen);
            json.put("cs", meta.chunkSize);
            json.put("th", meta.thumbEncrypted ? 1 : 0);
            if (meta.thumbKey != null) {
                json.put("tk", XoE2EEEnvelope.b64Encode(meta.thumbKey));
            }
            if (meta.thumbFileId > 0) {
                json.put("tf", meta.thumbFileId);
            }
            store().putMediaKeys(backendFileId, json.toString());
        } catch (Exception e) {
            FileLog.e("XoE2EE: noteMediaKeys failed", e);
        }
    }

    public MediaMeta mediaKeysFor(long backendFileId) {
        String json = store().getMediaKeys(backendFileId);
        if (json == null) {
            return null;
        }
        try {
            return parseMediaMeta(new JSONObject(json));
        } catch (Exception e) {
            return null;
        }
    }

    /** Registers the local (sender-side) media key for an upload in flight. */
    public void noteMediaKeysFromUpload(long backendFileId, byte[] fileKey, long plaintextLen, int chunkSize, boolean thumbEncrypted) {
        MediaMeta meta = new MediaMeta();
        meta.fileKey = fileKey;
        meta.plaintextLen = plaintextLen;
        meta.chunkSize = chunkSize;
        meta.thumbEncrypted = thumbEncrypted;
        noteMediaKeys(backendFileId, meta);
    }

    /** Destroys ALL E2EE state for this account (logout). */
    public void wipeLocal() {
        synchronized (lock) {
            registeredThisProcess.set(false);
            bundlesFetchedThisProcess.clear();
            store().wipe();
        }
    }

    /** Test seam: fresh singleton state for the next getInstance (JVM suite only). */
    static void resetForTests() {
        synchronized (XoE2EE.class) {
            for (int a = 0; a < instances.length; a++) {
                instances[a] = null;
            }
        }
        XoE2EEStore.resetForTests();
        XoE2EEApi.resetForTests();
    }
}

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
    private final AtomicBoolean sweptThisProcess = new AtomicBoolean(false);
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
                maybeSweepSelfPinsOnce();
                return true;
            }
            long t0 = android.os.SystemClock.elapsedRealtime();
            IdentityKeyPair identity = store().generateIdentityIfAbsent();
            if (store().localPreKeyCount() == 0) {
                uploadNewSignedPreKeyLocked();
                uploadPreKeyBatchLocked(); // full initial batch
            }
            int remaining = api().register(identity, store().getRegistrationIdObj(), signedPreKeyJsonLocked(), oneTimeKeysJsonLocked());
            registeredThisProcess.set(true);
            XoE2eeLog.event(account, "register.ok", 0, "otk_remaining=" + remaining
                    + " tookMs=" + (android.os.SystemClock.elapsedRealtime() - t0));
            FileLog.d("XoE2EE: registered account " + account + " (server otk pool " + remaining + ")");
            maybeSweepSelfPinsOnce();
            return true;
        }
    }

    /**
     * T74 — one-shot recovery sweep per process: removes any PROVEN-bogus
     * self-pin left by build-108 (own key pinned under a peer address) so an
     * upgraded, previously-bricked client un-bricks itself before the first
     * send. Impossible-key logic cannot weaken a legitimate pin.
     */
    private void maybeSweepSelfPinsOnce() {
        if (sweptThisProcess.compareAndSet(false, true)) {
            try {
                store().sweepSelfPins();
            } catch (Throwable t) {
                FileLog.e("XoE2EE: self-pin sweep failed", t);
            }
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
            // T75: resume the pending-keys probe loop — deferred sends for
            // peers that had no keys at defer time may now be flushable, and
            // the watch set persists across process death (restored lazily).
            try {
                XoPendingKeys.getInstance(account).startProbing();
            } catch (Throwable t) {
                FileLog.e("XoE2EE: pending-keys probe resume failed", t);
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
                int remaining = api().refill(oneTimeKeysJson(batch));
                XoE2eeLog.event(account, "otk.refill", 0, "uploaded=" + batch.size() + " remaining=" + remaining);
                FileLog.d("XoE2EE: refilled " + batch.size() + " otk for account " + account);
            }
        } catch (Throwable t) {
            XoE2eeLog.event(account, "otk.refill.fail", 0, String.valueOf(t.getClass().getSimpleName()));
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
        return encryptForPeerInner(peerUserId, innerJson, false);
    }

    private String encryptForPeerInner(long peerUserId, String innerJson, boolean retried) throws E2eeUnavailableException {
        try {
            // a FLAGGED peer (identity key changed under us) stops all 1:1
            // traffic until the user re-verifies + resets — even an existing
            // session is not trusted after the pin mismatch. T73/T74: before
            // failing, run the DETERMINISTIC recovery ladder —
            //   1) self-pin (our own key pinned under the peer's address by
            //      build-108) — provably bogus, healed unconditionally;
            //   2) false-positive flag — server still serves the pinned key,
            //      so the flag came from the T71 own-echo defect; it is
            //      cleared AND the (possibly confused) session is dropped for
            //      a clean re-ratchet;
            //   3) genuine substitution — stays blocked for the user.
            if (store().isFlagged(peerUserId)) {
                healFalsePositiveFlag(peerUserId);
                if (store().isFlagged(peerUserId)) {
                    XoE2eeLog.event(account, "encrypt.blocked", peerUserId, "E2EE_IDENTITY_CHANGED (genuine)");
                    throw new E2eeUnavailableException("E2EE_IDENTITY_CHANGED",
                            "recipient identity key changed; verify the safety number before continuing");
                }
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
                XoE2eeLog.event(account, "encrypt.fail", peerUserId, "E2EE_PAYLOAD_TOO_LARGE len=" + envelope.length());
                throw new E2eeUnavailableException("E2EE_PAYLOAD_TOO_LARGE", "encrypted payload exceeds transport cap");
            }
            XoE2eeLog.event(account, "encrypt.ok", peerUserId, "wire=" + cm.getType() + " retried=" + retried);
            maybeRefillOtk();
            return envelope;
        } catch (E2eeUnavailableException e) {
            throw e;
        } catch (UntrustedIdentityException e) {
            // T74: the only auto-recoverable UntrustedIdentity is the proven-
            // bogus self-pin; heal once and retry ONCE (bounded).
            if (!retried && store().isSelfPin(peerUserId) && store().healSelfPin(peerUserId)) {
                XoE2eeLog.event(account, "trust.selfPinHealed", peerUserId, "during encrypt, one retry");
                bundlesFetchedThisProcess.remove(peerUserId);
                return encryptForPeerInner(peerUserId, innerJson, true);
            }
            XoE2eeLog.event(account, "encrypt.fail", peerUserId, "UntrustedIdentity");
            throw new E2eeUnavailableException("E2EE_IDENTITY_CHANGED",
                    "recipient identity key changed; verify the safety number before continuing");
        } catch (Exception e) {
            XoE2eeLog.event(account, "encrypt.fail", peerUserId, String.valueOf(e.getClass().getSimpleName()));
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

    /**
     * T73 — flag auto-heal. A flag is only legitimate when the peer's
     * CURRENT server-side identity differs from the key we pinned. If the
     * server still serves exactly the pinned key, nothing changed and the
     * flag must have been a false positive (the T71 own-echo defect); it is
     * cleared and traffic resumes. A real substitution (server-served key
     * differs) keeps the flag — the user must verify the safety number.
     * Transport failures NEVER clear a flag (an unreachable server cannot
     * vouch for anyone).
     */
    private void healFalsePositiveFlag(long peerUserId) {
        // 1) PROVEN-bogus self-pin (build-108 artifact): our own key pinned
        //    under the peer's address. No server round-trip needed — the
        //    impossibility argument is local and absolute.
        if (store().isSelfPin(peerUserId) && store().healSelfPin(peerUserId)) {
            bundlesFetchedThisProcess.remove(peerUserId);
            return;
        }
        try {
            JSONObject bundle = api().bundle(peerUserId);
            if (bundle == null || !bundle.optBoolean("ok", false)) {
                return; // no keys / transport problem — stay flagged
            }
            byte[] served = XoE2EEEnvelope.b64Decode(bundle.getString("identity_key"));
            IdentityKey pinned = store().getIdentity(addressFor(peerUserId));
            if (pinned != null && java.util.Arrays.equals(pinned.serialize(), served)) {
                // 2) false-positive flag: the served identity is EXACTLY the
                //    pinned one, so nothing changed — the flag came from the
                //    T71 own-echo defect. Clear it AND drop the session: the
                //    own-echo processing may have confused the ratchet state,
                //    and a fresh X3DH is always safe (one OTK, refilled).
                FileLog.w("XoE2EE: false-positive identity flag healed for peer " + peerUserId
                        + " (server identity still matches the pinned key; session rebuilt fresh)");
                store().deleteSession(addressFor(peerUserId));
                store().clearFlag(peerUserId);
                bundlesFetchedThisProcess.remove(peerUserId);
                XoE2eeLog.event(account, "trust.flagHealed", peerUserId, "served==pinned, session dropped");
            } else {
                XoE2eeLog.event(account, "trust.flagStays", peerUserId,
                        pinned == null ? "no pin" : "served!=pinned (genuine substitution)");
            }
        } catch (Throwable t) {
            XoE2eeLog.event(account, "trust.healCheckFailed", peerUserId, String.valueOf(t.getClass().getSimpleName()));
            FileLog.e("XoE2EE: flag heal check failed (staying flagged)", t);
        }
    }

    // ------------------------------------------------------- own-echo rendering (T73)

    /**
     * Records the plaintext inner JSON of an OWN outgoing 1:1 message under
     * its server id (dispatcher calls this right after the send is accepted).
     * libsignal cannot re-open the sender's own ciphertext; the cache is what
     * history/send echoes render from instead.
     */
    public void noteSentInner(long messageId, String innerJson) {
        store().noteSentInner(messageId, innerJson);
        XoE2eeLog.event(account, "sentInner.note", 0, "id=" + messageId);
    }

    /** Plaintext inner JSON for an OWN outgoing message id, or null. */
    public String getSentInnerForRender(long messageId) {
        String inner = store().getSentInner(messageId);
        XoE2eeLog.event(account, inner != null ? "sentInner.hit" : "sentInner.miss", 0, "id=" + messageId);
        return inner;
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
                XoE2eeLog.event(account, "decrypt.fail", peerUserId, "unwrappable");
                return null;
            }
            SessionCipher cipher = cipherFor(peerUserId);
            byte[] plain;
            if (unwrapped.wireType == CiphertextMessage.PREKEY_TYPE) {
                plain = cipher.decrypt(new PreKeySignalMessage(unwrapped.body));
            } else if (unwrapped.wireType == CiphertextMessage.WHISPER_TYPE) {
                plain = cipher.decrypt(new SignalMessage(unwrapped.body));
            } else {
                XoE2eeLog.event(account, "decrypt.fail", peerUserId, "wireType=" + unwrapped.wireType);
                return null;
            }
            XoE2eeLog.event(account, "decrypt.ok", peerUserId, "wire=" + unwrapped.wireType);
            return new String(plain, "UTF-8");
        } catch (org.whispersystems.libsignal.DuplicateMessageException e) {
            // protocol-level replay protection did its job; the duplicate is dropped
            XoE2eeLog.event(account, "decrypt.reject", peerUserId, "DuplicateMessage (replay)");
            FileLog.w("XoE2EE: duplicate/replayed message rejected from peer " + peerUserId);
            return null;
        } catch (UntrustedIdentityException e) {
            // T74: the ONLY auto-recoverable UntrustedIdentity is the proven-
            // bogus self-pin (our own key pinned under this peer's address —
            // a build-108 artifact). Heal it and retry ONCE so the receive
            // path un-bricks without any user action. A REAL substitution
            // (pinned key != served key, neither is ours) never heals here.
            if (store().isSelfPin(peerUserId) && store().healSelfPin(peerUserId)) {
                XoE2eeLog.event(account, "trust.selfPinHealed", peerUserId, "during decrypt, one retry");
                return decryptFromPeer(peerUserId, envelope);
            }
            XoE2eeLog.event(account, "decrypt.fail", peerUserId, "UntrustedIdentity (stays blocked)");
            FileLog.e("XoE2EE: untrusted identity on decrypt from " + peerUserId);
            return null;
        } catch (Throwable t) {
            XoE2eeLog.event(account, "decrypt.fail", peerUserId, String.valueOf(t.getClass().getSimpleName()));
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
        XoE2eeLog.event(account, "bundle.fetch", peerUserId,
                bundle == null ? "null"
                        : bundle.optBoolean("ok", false)
                                ? "otk_remaining=" + bundle.optInt("otk_remaining", -1)
                                : "ok=false" + (bundle.optString("transport", null) != null ? " (" + bundle.optString("transport") + ")" : ""));
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
        XoE2eeLog.event(account, "session.built", peerUserId, "x3dh ok otk=" + (preKeyId == null ? "none" : String.valueOf(preKeyId)));
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

    /**
     * T74 — central "send blocked" surface: one diagnostic event + one UI
     * event (ChatActivity shows the bulletin). Called from the dispatcher's
     * E2EE catch seams so a blocked send is NEVER silent again.
     */
    public static void notifySendBlocked(int account, long peerUserId, String reasonCode) {
        XoE2eeLog.event(account, "send.blocked", peerUserId, String.valueOf(reasonCode));
        try {
            org.telegram.messenger.NotificationCenter.getInstance(account)
                    .postNotificationName(org.telegram.messenger.NotificationCenter.xoE2eeSendBlocked, peerUserId, reasonCode);
        } catch (Throwable ignore) {
        }
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
        /** T75 — explicit Telegram-GIF flag carried in the envelope ("an":1). */
        public boolean animated;
        /**
         * T76 — TRUE when the envelope CARRIES the "an" field at all. The
         * legacy ".mp4 name" heuristic may only speak when the field is
         * ABSENT (pre-T75 rows): with the field present it is authoritative,
         * and applying the heuristic anyway promoted EVERY .mp4-named real
         * video to a GIF (the field-confirmed "GIF type is broken" class).
         */
        public boolean animatedKnown;
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
            meta.animated = inner.optInt("an", 0) == 1;
            meta.animatedKnown = inner.has("an");
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

    /** Test seam: drops ONLY the session (trust pins stay) — the next send
     *  re-fetches the bundle. Used by the T73 heal regression tests. */
    void forceNewSessionForTests(long peerUserId) {
        synchronized (lock) {
            store().deleteSession(addressFor(peerUserId));
        }
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

package org.telegram.tgnet.rest.e2ee;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.rest.RestChatIndex;

/**
 * T78 — the secret-chat facade. Replaces the Signal-protocol XoE2EE with a
 * stateless per-user ECIES scheme (see XoSecretCrypto) bound to SEPARATE
 * secret chats (chats.type='secret', TL_chat dialogs, lock icon).
 *
 * <p>CLOUD chats never call into this class — they send plaintext over TLS
 * and the backend encrypts at rest (XOCC1). The send-path decision is
 * {@link #isSecret(long)}: true ONLY for secret chat ids (negative dialog
 * space, chatTypes from RestChatIndex).
 */
public final class XoSecret {

    /** Raised when a secret-chat send cannot be encrypted; never swallowed. */
    public static final class SecretUnavailableException extends Exception {
        public final String reasonCode;

        public SecretUnavailableException(String reasonCode, String message) {
            super(message);
            this.reasonCode = reasonCode;
        }
    }

    public static final String REASON_NO_PEER_KEY = "SECRET_NO_PEER_KEY";
    public static final String REASON_NO_OWN_KEY = "SECRET_NO_OWN_KEY";
    public static final String REASON_PAYLOAD_TOO_LARGE = "SECRET_PAYLOAD_TOO_LARGE";
    public static final String REASON_ENCRYPT_FAILED = "SECRET_ENCRYPT_FAILED";

    private static final int MAX_ENVELOPE_CHARS = 16000; // backend MAX_CONTENT_LENGTH 16384

    private static final XoSecret[] instances = new XoSecret[UserConfig.MAX_ACCOUNT_COUNT];

    public static XoSecret getInstance(int account) {
        if (account < 0 || account >= instances.length) {
            account = 0;
        }
        XoSecret mgr;
        synchronized (XoSecret.class) {
            mgr = instances[account];
            if (mgr == null) {
                mgr = new XoSecret(account);
                instances[account] = mgr;
            }
        }
        return mgr;
    }

    private final int account;
    private final Object lock = new Object();
    private final AtomicBoolean registeredThisProcess = new AtomicBoolean(false);

    private XoSecret(int account) {
        this.account = account;
    }

    private XoSecretStore store() {
        return XoSecretStore.getInstance(account);
    }

    private XoSecretApi api() {
        return XoSecretApi.getInstance(account);
    }

    // ------------------------------------------------------------------ chat mode

    /** @return true when this BACKEND chat id (positive) is a secret chat. */
    public static boolean isSecretChatId(long backendChatId) {
        return isSecretChatId(UserConfig.selectedAccount, backendChatId);
    }

    /** Account-aware form — ALWAYS prefer this from per-account pipelines
     *  (dispatcher, mapper, send helper): the selected account can differ
     *  from the account whose rows are being parsed (T78 fix: latent
     *  multi-account mismatch). */
    public static boolean isSecretChatId(int account, long backendChatId) {
        return backendChatId > 0 && RestChatIndex.getInstance(account).isSecret(backendChatId);
    }

    /** @return true when this UI dialog id (negative = chat space) is a secret chat. */
    public static boolean isSecretDialog(long dialogId) {
        return isSecretDialog(UserConfig.selectedAccount, dialogId);
    }

    /** Account-aware form — see {@link #isSecretChatId(int, long)}. */
    public static boolean isSecretDialog(int account, long dialogId) {
        return dialogId < 0 && isSecretChatId(account, -dialogId);
    }

    /** Backend chat id for a secret dialog id (negates). */
    public static long chatIdOfDialog(long dialogId) {
        return -dialogId;
    }

    /** Peer user id of a secret BACKEND chat id. */
    public static long secretPeerUser(long backendChatId) {
        return secretPeerUser(UserConfig.selectedAccount, backendChatId);
    }

    /** Account-aware form — see {@link #isSecretChatId(int, long)}. */
    public static long secretPeerUser(int account, long backendChatId) {
        return RestChatIndex.getInstance(account).secretPeerUser(backendChatId);
    }

    // ------------------------------------------------------------------ registration

    /**
     * Idempotent: generates our key pair when absent and PUTs the public key
     * to the registry. Returns true when the account is usable for
     * encryption. Network errors propagate (send path retries later).
     */
    public boolean ensureRegistered() throws Exception {
        synchronized (lock) {
            if (registeredThisProcess.get() && store().hasIdentity() && store().isRegisteredThisInstall()) {
                return true;
            }
            store().generateKeyPairIfAbsent();
            if (!store().hasIdentity()) {
                throw new SecretUnavailableException(REASON_NO_OWN_KEY, "key generation failed");
            }
            JSONObject resp = api().putKey(XoSecretEnvelope.b64Encode(store().getPublicKey()));
            if (resp == null || !resp.optBoolean("ok", false)) {
                // transport failure — retried before the next send
                throw new SecretUnavailableException(REASON_NO_OWN_KEY, "key registry unreachable");
            }
            store().noteRegistered();
            registeredThisProcess.set(true);
            XoE2eeLog.event(account, "secret.register.ok", 0,
                    "changed=" + resp.optBoolean("changed", false));
            return true;
        }
    }

    /** Fire-and-forget registration on login/app start. */
    public void ensureRegisteredAsync() {
        if (store().hasIdentity() && registeredThisProcess.get()) {
            return;
        }
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            try {
                ensureRegistered();
            } catch (Throwable t) {
                FileLog.e("XoSecret: async registration failed (retried before first send)", t);
            }
            try {
                XoSecretPending.getInstance(account).startProbing();
            } catch (Throwable t) {
                FileLog.e("XoSecret: pending probe resume failed", t);
            }
        });
    }

    // ------------------------------------------------------------------ peer keys

    /**
     * Blocking fetch of the peer's public key (registry + cache).
     *
     * @return the 32-byte public key, or null when the peer has none yet
     * (deferral case) or the transport failed
     */
    public byte[] keyForPeer(long peerUserId, boolean forceRefresh) {
        XoSecretStore s = store();
        if (!forceRefresh) {
            String cached = s.getPeerKey(peerUserId);
            if (cached != null) {
                try {
                    return XoSecretEnvelope.b64Decode(cached);
                } catch (Throwable t) {
                    s.putPeerKey(peerUserId, null); // drop corrupt cache entry
                    return null;
                }
            }
        }
        JSONObject resp = api().getKey(peerUserId);
        if (resp == null || !resp.optBoolean("ok", false)) {
            return null; // transport problem — caller may retry later
        }
        if (!resp.optBoolean("registered", false)) {
            return null; // genuinely keyless (old client / never installed)
        }
        String pk = resp.optString("pk", null);
        if (pk == null || pk.isEmpty()) {
            return null;
        }
        s.putPeerKey(peerUserId, pk);
        XoE2eeLog.event(account, "secret.key.fetch", peerUserId, "ok");
        try {
            return XoSecretEnvelope.b64Decode(pk);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ send-side encryption

    /**
     * Encrypts an outgoing inner payload for a secret chat. Blocking (key
     * fetch on first contact) — call from the dispatcher's IO queue only.
     * Throws rather than ever returning plaintext.
     */
    public String encryptForPeer(long chatId, long peerUserId, String innerJson) throws SecretUnavailableException {
        try {
            ensureRegistered();
            byte[] peerPub = keyForPeer(peerUserId, false);
            if (peerPub == null) {
                // one forced refresh (the cache may be stale-empty)
                peerPub = keyForPeer(peerUserId, true);
            }
            if (peerPub == null) {
                XoE2eeLog.event(account, "secret.encrypt.blocked", peerUserId, REASON_NO_PEER_KEY);
                throw new SecretUnavailableException(REASON_NO_PEER_KEY,
                        "the peer has no secret-chat key yet (needs a newer app version)");
            }
            byte[] ownPriv = store().getPrivateKey();
            byte[] ownPub = store().getPublicKey();
            byte[] inner = innerJson.getBytes("UTF-8");
            String envelope = XoSecretCrypto.encrypt(chatId, UserConfig.getInstance(account).clientUserId,
                    peerUserId, ownPriv, ownPub, peerPub, inner);
            if (envelope.length() > MAX_ENVELOPE_CHARS) {
                XoE2eeLog.event(account, "secret.encrypt.fail", peerUserId, "PAYLOAD_TOO_LARGE len=" + envelope.length());
                throw new SecretUnavailableException(REASON_PAYLOAD_TOO_LARGE, "encrypted payload exceeds transport cap");
            }
            XoE2eeLog.event(account, "secret.encrypt.ok", peerUserId, "chat=" + chatId);
            return envelope;
        } catch (SecretUnavailableException e) {
            throw e;
        } catch (Exception e) {
            XoE2eeLog.event(account, "secret.encrypt.fail", peerUserId, String.valueOf(e.getClass().getSimpleName()));
            FileLog.e("XoSecret: encrypt failed for peer " + peerUserId, e);
            throw new SecretUnavailableException(REASON_ENCRYPT_FAILED, "could not encrypt the message");
        }
    }

    /** Convenience: text message inner payload. */
    public String encryptText(long chatId, long peerUserId, String text) throws SecretUnavailableException {
        try {
            return encryptForPeer(chatId, peerUserId, XoSecretEnvelope.innerText(text));
        } catch (SecretUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new SecretUnavailableException(REASON_ENCRYPT_FAILED, "could not encrypt the message");
        }
    }

    // ------------------------------------------------------- own-echo rendering

    public void noteSentInner(long messageId, String innerJson) {
        store().noteSentInner(messageId, innerJson);
    }

    public String getSentInnerForRender(long messageId) {
        return store().getSentInner(messageId);
    }

    // ------------------------------------------------------------------ receive-side decryption

    /**
     * Decrypts an incoming secret envelope. Returns the inner JSON string,
     * or null when it cannot be opened. Stateless: ALWAYS deterministic for
     * the same inputs — re-parses are free (the T75 memo machinery is gone
     * because it is no longer needed).
     */
    public String decryptFromPeer(long chatId, long senderId, String envelope) {
        try {
            XoSecretStore s = store();
            if (!s.hasIdentity()) {
                XoE2eeLog.event(account, "secret.decrypt.fail", senderId, "no own key");
                return null;
            }
            byte[] inner = XoSecretCrypto.decrypt(chatId, senderId,
                    UserConfig.getInstance(account).clientUserId,
                    s.getPrivateKey(), s.getPublicKey(), envelope);
            if (inner == null) {
                XoE2eeLog.event(account, "secret.decrypt.fail", senderId, "open failed");
                return null;
            }
            // key-change detection: the envelope carries the sender's static
            // public key — compare with the registry cache and surface a
            // notice when it moved (Telegram semantics: notify, keep working)
            byte[] envelopePub = XoSecretCrypto.envelopeSenderPub(envelope);
            if (envelopePub != null && s.hasPeerKey(senderId)) {
                byte[] cached = XoSecretEnvelope.b64Decode(s.getPeerKey(senderId));
                if (!java.util.Arrays.equals(envelopePub, cached)) {
                    XoE2eeLog.event(account, "secret.key.changed", senderId, "envelope!=cache");
                    keyChangedPeers.add(senderId);
                    NotificationCenter.getInstance(account)
                            .postNotificationName(NotificationCenter.xoSecretKeyChanged, senderId);
                    s.putPeerKey(senderId, XoSecretEnvelope.b64Encode(envelopePub));
                }
            }
            XoE2eeLog.event(account, "secret.decrypt.ok", senderId, "chat=" + chatId);
            return new String(inner, "UTF-8");
        } catch (Throwable t) {
            XoE2eeLog.event(account, "secret.decrypt.fail", senderId, String.valueOf(t.getClass().getSimpleName()));
            FileLog.e("XoSecret: decrypt failed from peer " + senderId, t);
            return null;
        }
    }

    // ------------------------------------------------------------------ media keys

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
        /** Telegram-GIF flag carried in the envelope ("an":1). */
        public boolean animated;
        /** TRUE when the envelope CARRIES the "an" field (T76 contract). */
        public boolean animatedKnown;
    }

    public static MediaMeta parseMediaMeta(JSONObject inner) {
        MediaMeta meta = new MediaMeta();
        try {
            meta.fileKey = XoSecretEnvelope.b64Decode(inner.getString("fk"));
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
            meta.thumbKey = tk == null || tk.length() == 0 ? null : XoSecretEnvelope.b64Decode(tk);
            meta.thumbFileId = inner.optLong("tf", 0);
            return meta.fileKey != null && meta.fileKey.length == XoE2EEMedia.FILE_KEY_LEN ? meta : null;
        } catch (Exception e) {
            return null;
        }
    }

    public void noteMediaKeys(long backendFileId, MediaMeta meta) {
        if (backendFileId <= 0 || meta == null) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("fk", XoSecretEnvelope.b64Encode(meta.fileKey));
            json.put("pl", meta.plaintextLen);
            json.put("cs", meta.chunkSize);
            json.put("th", meta.thumbEncrypted ? 1 : 0);
            if (meta.thumbKey != null) {
                json.put("tk", XoSecretEnvelope.b64Encode(meta.thumbKey));
            }
            if (meta.thumbFileId > 0) {
                json.put("tf", meta.thumbFileId);
            }
            store().putMediaKeys(backendFileId, json.toString());
        } catch (Exception e) {
            FileLog.e("XoSecret: noteMediaKeys failed", e);
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

    public void noteMediaKeysFromUpload(long backendFileId, byte[] fileKey, long plaintextLen, int chunkSize, boolean thumbEncrypted) {
        MediaMeta meta = new MediaMeta();
        meta.fileKey = fileKey;
        meta.plaintextLen = plaintextLen;
        meta.chunkSize = chunkSize;
        meta.thumbEncrypted = thumbEncrypted;
        noteMediaKeys(backendFileId, meta);
    }

    // ------------------------------------------------------------------ trust surface

    /** Safety fingerprint for the secret chat, or null when keys are missing. */
    public String safetyNumber(long selfUserId, long peerUserId) {
        try {
            byte[] own = store().getPublicKey();
            if (own == null) {
                store().generateKeyPairIfAbsent();
                own = store().getPublicKey();
            }
            byte[] peer = keyForPeer(peerUserId, false);
            if (peer == null) {
                peer = keyForPeer(peerUserId, true);
            }
            return XoSecretCrypto.safetyFingerprint(selfUserId, own, peerUserId, peer);
        } catch (Throwable t) {
            FileLog.e("XoSecret: safety number failed", t);
            return null;
        }
    }

    /** TRUE when the peer's cached key was flagged as changed this session. */
    public boolean isKeyChanged(long peerUserId) {
        return keyChangedPeers.contains(peerUserId);
    }

    private final java.util.HashSet<Long> keyChangedPeers = new java.util.HashSet<>();

    // ------------------------------------------------------------------ blocked-send surface

    /** Central "send blocked" surface: diagnostics + UI event. */
    public static void notifySendBlocked(int account, long peerUserId, String reasonCode) {
        XoE2eeLog.event(account, "secret.send.blocked", peerUserId, String.valueOf(reasonCode));
        try {
            NotificationCenter.getInstance(account)
                    .postNotificationName(NotificationCenter.xoE2eeSendBlocked, peerUserId, reasonCode);
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Destroys ALL secret-chat state for this account (logout). */
    public void wipeLocal() {
        synchronized (lock) {
            registeredThisProcess.set(false);
            keyChangedPeers.clear();
            store().wipe();
        }
    }

    /** Test seam: fresh singleton state for the next getInstance (JVM suite). */
    public static void resetForTests() {
        synchronized (XoSecret.class) {
            for (int a = 0; a < instances.length; a++) {
                instances[a] = null;
            }
        }
        XoSecretStore.resetForTests();
        XoSecretApi.resetForTests();
    }
}

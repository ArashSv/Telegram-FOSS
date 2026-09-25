package org.telegram.tgnet.rest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.io.IOException;

/**
 * T3: the single REST gateway per account towards the MyMessenger backend
 * (contract: mymessenger-backend docs/API.md v1; deployed under {@link #BASE_URL}).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>v1 envelope parsing, including a {@code MALFORMED_RESPONSE} guard — a
 *       shared host answers proxy/PHP failures with HTML, which must surface as
 *       a typed error, never as a parser crash;</li>
 *   <li>{@code Authorization: Bearer} injection from {@link RestAuthStore};</li>
 *   <li>proactive refresh when the client clock says the access token is nearly
 *       spent, plus reactive refresh on 401 {@code TOKEN_EXPIRED} — both funnel
 *       through the single-flight claim in {@link RestAuthStore}, and a request
 *       is retried <b>once</b> at most. There is no retry loop by construction;</li>
 *   <li>NO automatic session destruction (T10): a rejected refresh surfaces as
 *       a typed error and the tokens stay. The only session-destruction path is
 *       the user's manual logout (MessagesController.performLogout).</li>
 * </ul>
 *
 * <p>Threading: every method blocks on network I/O — worker threads only.
 * Nothing here is wired into the app yet; this is the typed API that
 * LoginActivity (T4) and the ConnectionsManager facade (T5) will consume.
 * {@link RestAuthStore} remains the single source of truth for tokens; the
 * gateway only persists what the server hands out.
 */
public final class RestGateway {

    public static final String BASE_URL = "https://xorbit.ir/tele/api/v1/";

    private static final int DEFAULT_TOKEN_TTL_SECONDS = 7 * 24 * 60 * 60; // 7 days, API.md §11
    private static final int REFRESH_AWAIT_TIMEOUT_MS = 20 * 1000;
    private static final int REFRESH_AWAIT_SLEEP_MS = 200;

    /**
     * T8 reliability: extra transport-level attempts for the IDEMPOTENT file
     * routes (init/chunk/finalize/metadata/download — all safe to re-issue by
     * contract: a retried part overwrites itself, finalize is idempotent,
     * init under RestFileBridge's lock may at worst orphan a row for the
     * server-side cleanup tool). Rationale: FileUploadOperation fails the
     * WHOLE upload on one part error — a 300 MB video is ~2400 part requests,
     * and a single Wi-Fi/LTE handover would otherwise kill the send. Only
     * network-level failures retry; typed API errors (VALIDATION_ERROR,
     * PAYLOAD_TOO_LARGE, ...) propagate untouched. Messaging routes are NOT
     * covered — the tree owns their retry semantics.
     */
    private static final int FILE_TRANSPORT_RETRIES = 2;
    private static final long FILE_RETRY_BACKOFF_MS = 600;

    /** Notified when a token family is revoked or absent and a fresh login is required. */
    public interface SessionInvalidListener {
        void onSessionInvalid(int account);
    }

    /** Result of {@link #sendCode(String)} (API.md §3.1). */
    public static final class SendCodeResult {
        public final String phoneCodeHash;
        public final int codeLength;
        public final int expiresIn;
        public final boolean testMode;
        /** Phone already has an account (MTProto TL_auth_sentCode.registered semantics).
         *  Default false when the backend predates the field: the register-name view
         *  is shown, and the backend ignores the name for existing users — safe. */
        public final boolean registered;
        public final String devCode; // present in test mode only

        SendCodeResult(String phoneCodeHash, int codeLength, int expiresIn, boolean testMode, boolean registered, String devCode) {
            this.phoneCodeHash = phoneCodeHash;
            this.codeLength = codeLength;
            this.expiresIn = expiresIn;
            this.testMode = testMode;
            this.registered = registered;
            this.devCode = devCode;
        }
    }

    /** Result of {@link #verify(String, String, String, String, String)} (API.md §3.2). */
    public static final class VerifyResult {
        public final boolean isNewUser;
        public final TLRPC.TL_user user;

        VerifyResult(boolean isNewUser, TLRPC.TL_user user) {
            this.isNewUser = isNewUser;
            this.user = user;
        }
    }

    private static final RestGateway[] instances = new RestGateway[UserConfig.MAX_ACCOUNT_COUNT];

    /** Per-account singleton, keyed like the rest of the app (0..MAX_ACCOUNT_COUNT-1). */
    public static RestGateway getInstance(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            FileLog.e("RestGateway: invalid account " + account + ", clamping to 0");
            account = 0;
        }
        RestGateway gateway;
        synchronized (RestGateway.class) {
            gateway = instances[account];
            if (gateway == null) {
                gateway = new RestGateway(account);
                instances[account] = gateway;
            }
        }
        return gateway;
    }

    private final int account;
    private final RestAuthStore store;
    private volatile SessionInvalidListener sessionInvalidListener;

    private RestGateway(int account) {
        this.account = account;
        this.store = RestAuthStore.getInstance(account);
    }

    public void setSessionInvalidListener(SessionInvalidListener listener) {
        this.sessionInvalidListener = listener;
    }

    // ------------------------------------------------------------------ auth API

    /**
     * POST /auth/send-code.php — pre-auth, no bearer.
     * Backend codes surfaced on failure: VALIDATION_ERROR, TOO_MANY_ATTEMPTS.
     * {@code registered} lets the client pick sign-in vs register-name BEFORE
     * verify — the code is consumed by the first successful verify, so the
     * MTProto sign-in→signUpRequired→signUp re-verify flow cannot exist here.
     */
    public SendCodeResult sendCode(String phone) {
        JSONObject body = put(new JSONObject(), "phone", phone);
        JSONObject response = unauthenticatedRequest("POST", "auth/send-code.php", body);
        return new SendCodeResult(
                response.optString("phone_code_hash", null),
                response.optInt("code_length", 5),
                response.optInt("expires_in", 300),
                response.optBoolean("test_mode", false),
                response.optBoolean("registered", false),
                response.optString("dev_code", null));
    }

    /**
     * POST /auth/verify.php — single call does signIn AND signUp (API.md §10).
     * Persists the returned token pair on success. Backend codes: CODE_EXPIRED,
     * INVALID_CODE, TOO_MANY_ATTEMPTS.
     *
     * @param firstName optional, used by the backend only on first signup
     */
    public VerifyResult verify(String phone, String phoneCodeHash, String code, String firstName, String lastName) {
        JSONObject body = put(new JSONObject(), "phone", phone);
        put(body, "phone_code_hash", phoneCodeHash);
        put(body, "code", code);
        if (firstName != null && firstName.length() > 0) {
            put(body, "first_name", firstName);
        }
        if (lastName != null && lastName.length() > 0) {
            put(body, "last_name", lastName);
        }
        JSONObject response = unauthenticatedRequest("POST", "auth/verify.php", body);
        persistTokens(response);
        return new VerifyResult(response.optBoolean("is_new_user", false), selfUser(response));
    }

    /**
     * GET /auth/me.php — validates the stored session against the server,
     * refreshing first when the client clock considers the access token spent.
     * Throws {@code XoApiException#isSessionInvalid()} when no valid session exists.
     */
    public TLRPC.TL_user me() {
        JSONObject response = authenticatedRequest("GET", "auth/me.php", null);
        return selfUser(response);
    }

    // ------------------------------------------------------------------ messaging API (T5/T6)

    /** GET /chats/list.php — all chats, newest first, with last_message + unread_count. */
    public JSONArray chatsList() {
        JSONObject response = authenticatedRequest("GET", "chats/list.php", null);
        return response.optJSONArray("chats");
    }

    /**
     * POST /chats/create.php — find-or-create private chat (deterministic
     * pair_key makes repeated calls idempotent). Returns the chat JSON.
     */
    public JSONObject createPrivateChat(long peerUserId) {
        JSONObject body = put(new JSONObject(), "type", "private");
        putNumber(body, "peer_user_id", peerUserId);
        return authenticatedRequest("POST", "chats/create.php", body);
    }

    /**
     * GET /messages/history.php — two modes: maxId > 0 scrolls back (older
     * than maxId), maxId == 0 loads the newest page. Returns ascending.
     */
    public JSONArray history(long chatId, int maxId, int limit) {
        StringBuilder url = new StringBuilder("messages/history.php?chat_id=").append(chatId);
        url.append("&max_id=").append(Math.max(0, maxId));
        url.append("&limit=").append(Math.max(1, Math.min(100, limit)));
        JSONObject response = authenticatedRequest("GET", url.toString(), null);
        return response.optJSONArray("messages");
    }

    /** POST /messages/send.php — text only in v1 client scope (media = T8). */
    public JSONObject send(long chatId, String content, int replyToId) {
        JSONObject body = putNumber(new JSONObject(), "chat_id", chatId);
        put(body, "content", content);
        if (replyToId > 0) {
            putNumber(body, "reply_to_id", replyToId);
        }
        return authenticatedRequest("POST", "messages/send.php", body);
    }

    /**
     * POST /messages/edit.php {message_id, content} — text & caption edits
     * (T38/13). The backend validates ownership + membership server-side,
     * persists content + edited_at, pushes message_edit to the other
     * members, and answers {message: fresh json}.
     */
    public JSONObject messagesEdit(long chatId, int messageId, String content) {
        JSONObject body = putNumber(new JSONObject(), "message_id", messageId);
        put(body, "content", content);
        return authenticatedRequest("POST", "messages/edit.php", body);
    }

    /** POST /messages/read.php — idempotent on the server (only advances). */
    public JSONObject read(long chatId, int maxId) {
        JSONObject body = putNumber(new JSONObject(), "chat_id", chatId);
        putNumber(body, "max_id", maxId);
        return authenticatedRequest("POST", "messages/read.php", body);
    }

    /** POST /messages/delete.php — delete-for-everyone semantics, 100 ids max. */
    public JSONObject delete(long chatId, int[] messageIds) {
        JSONObject body = putNumber(new JSONObject(), "chat_id", chatId);
        JSONArray ids = new JSONArray();
        for (int id : messageIds) {
            ids.put(id);
        }
        try {
            body.put("message_ids", ids);
        } catch (JSONException e) {
            throw new IllegalStateException("static JSON build failed for message_ids", e);
        }
        return authenticatedRequest("POST", "messages/delete.php", body);
    }

    /** GET /users/get.php?ids=1,2,3 — hydration only, no search in v1. */
    public JSONArray usersGet(long[] userIds) {
        if (userIds == null || userIds.length == 0) {
            return new JSONArray();
        }
        StringBuilder ids = new StringBuilder();
        for (int a = 0; a < userIds.length; a++) {
            if (a > 0) {
                ids.append(',');
            }
            ids.append(userIds[a]);
        }
        JSONObject response = authenticatedRequest("GET", "users/get.php?ids=" + ids, null);
        return response.optJSONArray("users");
    }

    /** GET /sync/index.php — short-polling page; cursor contract API.md §9. */
    public JSONObject sync(long cursor, int limit) {
        String url = "sync/index.php?cursor=" + Math.max(0, cursor)
                + "&limit=" + Math.max(1, Math.min(500, limit));
        return authenticatedRequest("GET", url, null);
    }

    // ------------------------------------------------------------------ file & media API (T8)

    /**
     * POST /files/init.php — streaming start (size always 0: the tree decides
     * part counts, may extend them on the fly, and the real size is computed
     * from the assembled blob at finalize). Returns the backend file_id.
     */
    public long fileInit(int chunksTotalEstimate) {
        JSONObject body = putNumber(new JSONObject(), "size", 0);
        putNumber(body, "chunks_total", Math.max(1, chunksTotalEstimate));
        JSONObject response = fileRequestRetry("files/init.php",
                () -> authenticatedRequest("POST", "files/init.php", body));
        return response.optLong("file_id", 0);
    }

    /**
     * POST /files/chunk.php?file_id=&index=&len= — raw octet-stream part (128 KB
     * Telegram-style; idempotent: a retried part overwrites itself). T14:
     * {@code realLen} declares the true byte count when the body is PADDED —
     * the host WAF rejects binary POST bodies under ~10 KB (short final tails
     * and small thumbnails), so the dispatcher pads sub-32 KB bodies to 32 KB
     * and the backend truncates back to {@code len} before storing. 0 = no
     * padding (body is exact).
     *
     * @return the byte count the server actually stored (backend v1.2.2+);
     *         -1 when the backend predates the field — the caller then skips
     *         the part-level integrity check.
     */
    public long fileChunk(long backendFileId, int index, byte[] bytes, int realLen) {
        String q = "?file_id=" + backendFileId + "&index=" + index;
        if (realLen > 0 && realLen < bytes.length) {
            q += "&len=" + realLen;
        }
        final String query = q;
        JSONObject response = fileRequestRetry("files/chunk.php",
                () -> authenticatedBinaryPost(
                        "files/chunk.php" + query, bytes));
        if (!response.optBoolean("ok", false)) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "chunk answer without ok");
        }
        return response.optLong("size", -1);
    }

    /**
     * T29: part upload WITH content attestation — the backend v1.3 echoes
     * sha256 of the bytes it actually stored (after &len= truncation), so the
     * dispatcher can detect a request body that arrived cut or byte-mangled
     * but length-consistent (the one corruption class the size echo cannot
     * see). Returns the raw envelope; callers read "size" and "sha256".
     */
    public JSONObject fileChunkAttested(long backendFileId, int index, byte[] bytes, int realLen) {
        String q = "?file_id=" + backendFileId + "&index=" + index;
        if (realLen > 0 && realLen < bytes.length) {
            q += "&len=" + realLen;
        }
        final String query = q;
        JSONObject response = fileRequestRetry("files/chunk.php",
                () -> authenticatedBinaryPost(
                        "files/chunk.php" + query, bytes));
        if (!response.optBoolean("ok", false)) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "chunk answer without ok");
        }
        return response;
    }

    public long fileChunk(long backendFileId, int index, byte[] bytes) {
        return fileChunk(backendFileId, index, bytes, 0);
    }

    /**
     * POST /files/finalize.php — assembles + inspects the blob server-side and
     * returns the v1.1 File JSON (kind/width/height/duration/thumb_file_id/
     * sha256). {@code mediaMime/mediaName/width/height/duration} are the
     * client-declared values the server cannot detect itself (video/audio);
     * images are fully server-validated. Idempotent on ready files.
     */
    public JSONObject fileFinalize(long backendFileId, int chunksTotal, String mediaMime, String mediaName,
                                   Integer width, Integer height, Integer duration, long declaredBytes) {
        JSONObject body = putNumber(new JSONObject(), "file_id", backendFileId);
        putNumber(body, "chunks_total", Math.max(1, chunksTotal));
        if (declaredBytes > 0) {
            // T14: exact streamed byte total — the backend cross-checks it
            // against the assembled blob (truncation/corruption detector).
            putNumber(body, "size", declaredBytes);
        }
        if (mediaMime != null && mediaMime.length() > 0) {
            put(body, "mime_type", mediaMime);
        }
        if (mediaName != null && mediaName.length() > 0) {
            put(body, "name", mediaName);
        }
        if (width != null && width > 0) {
            putNumber(body, "width", width);
        }
        if (height != null && height > 0) {
            putNumber(body, "height", height);
        }
        if (duration != null && duration > 0) {
            putNumber(body, "duration", duration);
        }
        return fileRequestRetry("files/finalize.php",
                () -> authenticatedRequest("POST", "files/finalize.php", body));
    }

    /** GET /files/get.php?file_id= — v1.1 metadata (kind/dimensions/thumb_file_id/sha256); thumb resolution + resume awareness. */
    public JSONObject fileMetadata(long backendFileId) {
        return fileRequestRetry("files/get.php",
                () -> authenticatedRequest("GET", "files/get.php?file_id=" + backendFileId, null));
    }

    /**
     * T31: ONE authed binary window request, for {@link XoFileTransport} (the
     * download path's single owner). Auth + refresh stay here; length and
     * content verification live in the transport. No retry loop here — the
     * transport retries each window on a fresh connection with the same
     * idempotent semantics this class applies to JSON routes.
     */
    public XoHttp.BinaryResponse binaryWindow(long backendFileId, long startInclusive, long endInclusive) {
        RestAuthStore.TokenSet tokens = requireTokens("files/download.php");
        try {
            return XoHttp.binaryRequest(BASE_URL + "files/download.php?file_id=" + backendFileId,
                    tokens.accessToken, startInclusive, endInclusive);
        } catch (IOException e) {
            FileLog.e("RestGateway: transport failure on file window", e);
            throw new XoTransportException("file window failed: " + e.getMessage(), e);
        }
    }

    /**
     * Transport-retry wrapper for the idempotent file routes (see the class
     * constants for the reasoning). {@code XoTransportException} (DNS/TLS/
     * timeout/reset) retries with a small linear backoff; typed
     * {@link XoApiException} answers NEVER retry — the server said no, and
     * resending the same bytes cannot change its mind.
     */
    private <T> T fileRequestRetry(String path, FileRequest<T> request) {
        XoTransportException last = null;
        for (int attempt = 0; attempt <= FILE_TRANSPORT_RETRIES; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(FILE_RETRY_BACKOFF_MS * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw last != null ? last : new XoTransportException(path + " interrupted", e);
                }
            }
            try {
                return request.run();
            } catch (XoTransportException e) {
                last = e;
                FileLog.w("RestGateway: transport retry " + attempt + "/" + FILE_TRANSPORT_RETRIES + " on " + path);
            }
        }
        throw last;
    }

    /** Work unit for {@link #fileRequestRetry} (checked-exception-free lambda target). */
    private interface FileRequest<T> {
        T run();
    }

    /**
     * POST /messages/send.php with {@code media_file_id} — the media message
     * contract (media ≠ file: only the reference travels in the message). The
     * response carries the full message JSON (media joined by the backend).
     */
    public JSONObject sendMedia(long chatId, long mediaFileId, String caption, int replyToId) {
        return sendMedia(chatId, mediaFileId, caption, replyToId, 0);
    }

    /**
     * POST /messages/send.php with {@code media_file_id} — the media message
     * contract (media ≠ file: only the reference travels in the message). The
     * response carries the full message JSON (media joined by the backend).
     * T14: {@code thumbFileId} > 0 links a client-uploaded thumbnail (the
     * tree uploads video/document thumbs as separate files) — the backend
     * validates + links it, and the receive mapper plants the doc thumb.
     */
    public JSONObject sendMedia(long chatId, long mediaFileId, String caption, int replyToId, long thumbFileId) {
        JSONObject body = putNumber(new JSONObject(), "chat_id", chatId);
        putNumber(body, "media_file_id", mediaFileId);
        put(body, "content", caption == null ? "" : caption);
        if (thumbFileId > 0) {
            putNumber(body, "thumb_file_id", thumbFileId);
        }
        if (replyToId > 0) {
            putNumber(body, "reply_to_id", replyToId);
        }
        return authenticatedRequest("POST", "messages/send.php", body);
    }

    // ------------------------------------------------------------------ T32: groups + avatars + profile (backend v1.5.0)

    /**
     * POST /chats/create.php {type:"group", title, member_ids} — group
     * creation. Response {chat, created}; the caller wraps the chat json into
     * the TL_messages_invitedUsers contract the tree's createChat expects.
     */
    public JSONObject createGroupChat(String title, long[] memberIds) {
        JSONObject body = put(new JSONObject(), "type", "group");
        put(body, "title", title);
        if (memberIds != null && memberIds.length > 0) {
            JSONArray ids = new JSONArray();
            for (long id : memberIds) {
                ids.put(id);
            }
            try {
                body.put("member_ids", ids);
            } catch (JSONException e) {
                throw new IllegalStateException("static JSON build failed for member_ids", e);
            }
        }
        return authenticatedRequest("POST", "chats/create.php", body);
    }

    /** POST /chats/add-member.php {chat_id, user_id} — creator/admin only on the backend. */
    public JSONObject addChatMember(long chatId, long userId) {
        JSONObject body = putNumber(new JSONObject(), "chat_id", chatId);
        putNumber(body, "user_id", userId);
        return authenticatedRequest("POST", "chats/add-member.php", body);
    }

    /** POST /chats/edit.php {chat_id, title} — group rename (creator/admin). */
    public JSONObject editChatTitle(long chatId, String title) {
        JSONObject body = putNumber(new JSONObject(), "chat_id", chatId);
        put(body, "title", title);
        return authenticatedRequest("POST", "chats/edit.php", body);
    }

    /** POST /chats/set-photo.php {chat_id, file_id} — group avatar (creator/admin). */
    public JSONObject setChatPhoto(long chatId, long fileId) {
        JSONObject body = putNumber(new JSONObject(), "chat_id", chatId);
        putNumber(body, "file_id", fileId);
        return authenticatedRequest("POST", "chats/set-photo.php", body);
    }

    /** POST /chats/set-photo.php {chat_id, remove:true} — clear the group avatar (v1.8). */
    public JSONObject clearChatPhoto(long chatId) {
        JSONObject body = putNumber(new JSONObject(), "chat_id", chatId);
        try {
            body.put("remove", true);
        } catch (JSONException e) {
            throw new IllegalStateException("static JSON build failed for remove", e);
        }
        return authenticatedRequest("POST", "chats/set-photo.php", body);
    }

    /** POST /users/set-photo.php {file_id} — own avatar from an uploaded image. */
    public JSONObject setUserPhoto(long fileId) {
        JSONObject body = putNumber(new JSONObject(), "file_id", fileId);
        return authenticatedRequest("POST", "users/set-photo.php", body);
    }

    /** POST /users/delete-photo.php {} — clear the own avatar. */
    public JSONObject deleteUserPhoto() {
        return authenticatedRequest("POST", "users/delete-photo.php", new JSONObject());
    }

    // ------------------------------------------------------------------ T33: usernames + bio + deep links

    /**
     * GET /users/username-check.php?username=… — live availability state
     * (always 200: available/reason is UI state, not an error surface).
     */
    public JSONObject usernameCheck(String username) {
        return authenticatedRequest("GET", "users/username-check.php?username=" + android.net.Uri.encode(username), null);
    }

    /** POST /users/username-set.php {username} — empty string clears (backend contract). */
    public JSONObject usernameSet(String username) {
        JSONObject body = put(new JSONObject(), "username", username);
        return authenticatedRequest("POST", "users/username-set.php", body);
    }

    /** GET /users/resolve.php?username=… — @username deep-link resolution. */
    public JSONObject resolveUsername(String username) {
        return authenticatedRequest("GET", "users/resolve.php?username=" + android.net.Uri.encode(username), null);
    }

    /**
     * POST /users/edit.php {display_name?, bio?} — null omits the field so the
     * backend's "at least one of" contract stays truthful for bio-only edits
     * (ChangeBioActivity) and name-only edits (ChangeNameActivity).
     */
    public JSONObject updateProfile(String displayName, String bio) {
        JSONObject body = new JSONObject();
        if (displayName != null) {
            put(body, "display_name", displayName);
        }
        if (bio != null) {
            put(body, "bio", bio);
        }
        return authenticatedRequest("POST", "users/edit.php", body);
    }

    // ------------------------------------------------------------------ T35: group full info + group about + contacts

    /**
     * GET /chats/members.php?chat_id=… — group members + roles + the viewer's
     * chat payload (v1.8): one call feeds the TL_messages_chatFull contract
     * (members, about, chat surfaces).
     */
    public JSONObject chatMembers(long chatId) {
        return authenticatedRequest("GET", "chats/members.php?chat_id=" + chatId, null);
    }

    /**
     * POST /chats/edit.php {chat_id, about} — group description (v1.8).
     * Separate from editChatTitle so "at least one of" holds per request;
     * empty string clears the about on the backend.
     */
    public JSONObject editChatAbout(long chatId, String about) {
        JSONObject body = putNumber(new JSONObject(), "chat_id", chatId);
        put(body, "about", about);
        return authenticatedRequest("POST", "chats/edit.php", body);
    }

    /**
     * GET /contacts/list.php — the caller's registered contacts. Answers the
     * {ok, contacts:[…], saved_count} envelope; the dispatcher maps it onto
     * TL_contacts_contacts.
     */
    public JSONObject contactsGet() {
        return authenticatedRequest("GET", "contacts/list.php", null);
    }

    /**
     * POST /contacts/save.php {user_id?|phone?, name} — add or rename a
     * contact. Exactly one of userId/phone; userId derives the phone
     * server-side (Add-to-contacts from a profile), phone is the New-contact
     * path. Response {contact, user?, saved_count}.
     */
    public JSONObject contactsSave(Long userId, String phone, String name) {
        JSONObject body = put(new JSONObject(), "name", name);
        if (userId != null) {
            putNumber(body, "user_id", userId);
        } else {
            put(body, "phone", phone);
        }
        return authenticatedRequest("POST", "contacts/save.php", body);
    }

    /** POST /contacts/delete.php {user_ids:[…]} — remove contacts by user id. */
    public JSONObject contactsDelete(long[] userIds) {
        JSONObject body = new JSONObject();
        JSONArray ids = new JSONArray();
        for (long id : userIds) {
            ids.put(id);
        }
        try {
            body.put("user_ids", ids);
        } catch (JSONException e) {
            throw new IllegalStateException("static JSON build failed for user_ids", e);
        }
        return authenticatedRequest("POST", "contacts/delete.php", body);
    }

    /**
     * POST /contacts/import.php {contacts:[{phone, name}…]} — device-phonebook
     * batch sync (performSyncPhoneBook sends up to 500 entries per request).
     * The backend stores every entry and answers {imported, users, saved_count}
     * with ONLY the registered matches.
     */
    public JSONObject contactsImport(JSONObject[] entries) {
        JSONObject body = new JSONObject();
        JSONArray arr = new JSONArray();
        for (JSONObject entry : entries) {
            arr.put(entry);
        }
        try {
            body.put("contacts", arr);
        } catch (JSONException e) {
            throw new IllegalStateException("static JSON build failed for contacts", e);
        }
        return authenticatedRequest("POST", "contacts/import.php", body);
    }

    // ------------------------------------------------------------------ request core

    private JSONObject unauthenticatedRequest(String method, String path, JSONObject body) {
        XoHttp.Response response = httpCall(method, path, body, null);
        return parseEnvelope(response, path);
    }

    /**
     * Authenticated request with the 401 policy: proactive refresh when the
     * client clock says the token is nearly spent, reactive refresh on
     * TOKEN_EXPIRED/UNAUTHORIZED, retry once — never twice.
     */
    private JSONObject authenticatedRequest(String method, String path, JSONObject body) {
        for (int attempt = 0; attempt < 2; attempt++) {
            RestAuthStore.TokenSet tokens = requireTokens(path);
            XoHttp.Response response = httpCall(method, path, body, tokens.accessToken);
            if (response.code == 401 && attempt == 0) {
                String errorCode = envelopeErrorCode(response.body);
                boolean expired = XoApiException.TOKEN_EXPIRED.equals(errorCode)
                        || XoApiException.UNAUTHORIZED.equals(errorCode);
                if (expired) {
                    RestAuthStore.TokenSet current = store.getTokens();
                    if (current != null && !current.accessToken.equals(tokens.accessToken)) {
                        // T7d: another thread already rotated past our view — do NOT
                        // rotate again (each needless rotation burns a family row and
                        // widens the replay window); retry with the current token.
                        continue;
                    }
                    refreshNow(current != null ? current : tokens);
                    continue; // exactly one retry with the fresh token
                }
            }
            return parseEnvelope(response, path);
        }
        throw sessionInvalid("token still rejected after one refresh + retry: " + path);
    }

    /**
     * T8: authenticated raw-octet-stream POST (file parts). Same 401 policy as
     * {@link #authenticatedRequest} — the envelope semantics are identical, only
     * the request body is binary.
     */
    private JSONObject authenticatedBinaryPost(String path, byte[] bytes) {
        for (int attempt = 0; attempt < 2; attempt++) {
            RestAuthStore.TokenSet tokens = requireTokens(path);
            XoHttp.Response response;
            try {
                response = XoHttp.request(BASE_URL + path, "POST", bytes,
                        "application/octet-stream", tokens.accessToken);
            } catch (Exception e) {
                FileLog.e("RestGateway: transport failure on " + path, e);
                throw new XoTransportException(path + " failed: " + e.getMessage(), e);
            }
            if (response.code == 401 && attempt == 0) {
                String errorCode = envelopeErrorCode(response.body);
                boolean expired = XoApiException.TOKEN_EXPIRED.equals(errorCode)
                        || XoApiException.UNAUTHORIZED.equals(errorCode);
                if (expired) {
                    RestAuthStore.TokenSet current = store.getTokens();
                    if (current != null && !current.accessToken.equals(tokens.accessToken)) {
                        continue; // T7d: foreign refresh already moved past our view
                    }
                    refreshNow(current != null ? current : tokens);
                    continue; // exactly one retry with the fresh token
                }
            }
            return parseEnvelope(response, path);
        }
        throw sessionInvalid("token still rejected after one refresh + retry: " + path);
    }

    /** Valid tokens, proactively refreshed when the clock says they are nearly spent. */
    private RestAuthStore.TokenSet requireTokens(String path) {
        RestAuthStore.TokenSet tokens = store.getTokens();
        if (tokens == null) {
            throw sessionInvalid("no stored tokens for " + path);
        }
        if (tokens.isAccessTokenProbablyExpired()) {
            RestAuthStore.TokenSet fresh = refreshNow(tokens);
            if (fresh != null) {
                tokens = fresh; // null = another thread refreshed; re-read below
            }
            if (tokens == null) {
                tokens = store.getTokens();
                if (tokens == null) {
                    throw sessionInvalid("refresh left no usable tokens for " + path);
                }
            }
        }
        return tokens;
    }

    /**
     * Single-flight refresh. Returns the fresh TokenSet, or null when another
     * thread was already refreshing (caller should re-read tokens and proceed).
     * Throws SESSION_INVALID (family revoked/absent; listener notified) or
     * passes transport/validation errors through.
     */
    private RestAuthStore.TokenSet refreshNow(RestAuthStore.TokenSet known) {
        if (known == null) {
            throw sessionInvalid("refresh requested without tokens");
        }
        if (!store.beginRefresh(known.refreshToken)) {
            awaitForeignRefresh();
            return store.getTokens();
        }
        try {
            JSONObject body = put(new JSONObject(), "refresh_token", known.refreshToken);
            // pre-auth endpoint: no bearer, and NO retry path — refresh cannot recurse
            XoHttp.Response response = httpCall("POST", "auth/refresh.php", body, null);
            JSONObject data = parseEnvelope(response, "auth/refresh.php");
            long expiresAt = nowSeconds() + data.optLong("expires_in", DEFAULT_TOKEN_TTL_SECONDS);
            RestAuthStore.TokenSet fresh = new RestAuthStore.TokenSet(
                    data.getString("access_token"),
                    data.getString("refresh_token"),
                    expiresAt);
            store.endRefresh(known.refreshToken, fresh);
            return fresh;
        } catch (XoApiException e) {
            store.endRefresh(known.refreshToken, null);
            // T10: NO automatic session destruction, EVER. A 401 on refresh
            // (replay detection / family revocation — typically caused by a
            // refresh response lost under VPN) previously wiped the store and
            // kicked the user. Now the tokens STAY: the still-valid access
            // token keeps the app working, and recovery is a manual logout +
            // fresh login (the ONLY session-destruction path is the logout
            // button, wired in MessagesController.performLogout).
            if (e.httpStatus == 401 && e.isGenuineServerRejection()) {
                FileLog.e("RestGateway: refresh rejected by backend (http 401, code " + e.errorCode + ") — tokens kept, no auto logout");
            }
            throw e;
        } catch (XoTransportException e) {
            store.endRefresh(known.refreshToken, null);
            throw e;
        } catch (JSONException e) {
            store.endRefresh(known.refreshToken, null);
            throw new XoApiException(0, XoApiException.MALFORMED_RESPONSE, "refresh body malformed: " + e.getMessage());
        }
    }

    /** Bounded wait while another thread owns the refresh slot. */
    private void awaitForeignRefresh() {
        long deadline = System.currentTimeMillis() + REFRESH_AWAIT_TIMEOUT_MS;
        while (store.isRefreshInFlight() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(REFRESH_AWAIT_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private XoHttp.Response httpCall(String method, String path, JSONObject body, String bearerToken) {
        try {
            String jsonBody = body == null ? null : body.toString();
            return XoHttp.request(BASE_URL + path, method, jsonBody, bearerToken);
        } catch (Exception e) {
            FileLog.e("RestGateway: transport failure on " + path, e);
            throw new XoTransportException(path + " failed: " + e.getMessage(), e);
        }
    }

    /** Parses the v1 envelope; ok → data, failure → XoApiException with backend code. */
    private JSONObject parseEnvelope(XoHttp.Response response, String path) {
        String body = response.body == null ? "" : response.body.trim();
        if (body.length() == 0) {
            throw new XoApiException(response.code, XoApiException.MALFORMED_RESPONSE,
                    "empty body from " + path + " (http " + response.code + ")");
        }
        JSONObject json;
        try {
            json = new JSONObject(body);
        } catch (JSONException e) {
            // shared-host HTML error pages (502/503/maintenance) land here — typed, not fatal
            throw new XoApiException(response.code, XoApiException.MALFORMED_RESPONSE,
                    "non-JSON body from " + path + " (http " + response.code + ")");
        }
        if (json.optBoolean("ok", false)) {
            return json;
        }
        JSONObject error = json.optJSONObject("error");
        String code = error == null ? "UNKNOWN" : error.optString("code", "UNKNOWN");
        String message = error == null ? "no error detail (http " + response.code + ")"
                : error.optString("message", "");
        throw new XoApiException(response.code, code, message);
    }

    /** Best-effort error-code read for the 401 retry decision, without envelope commitment. */
    private static String envelopeErrorCode(String body) {
        try {
            JSONObject error = new JSONObject(body.trim()).optJSONObject("error");
            return error == null ? null : error.optString("code", null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Persists the token pair returned by verify/refresh; the store stays authoritative. */
    private void persistTokens(JSONObject response) {
        String access = response.optString("access_token", null);
        String refresh = response.optString("refresh_token", null);
        long expiresIn = response.optLong("expires_in", DEFAULT_TOKEN_TTL_SECONDS);
        if (access != null && refresh != null && access.length() > 0 && refresh.length() > 0) {
            store.saveTokens(access, refresh, nowSeconds() + expiresIn);
        } else {
            FileLog.w("RestGateway: response without a token pair, nothing persisted");
        }
    }

    private static TLRPC.TL_user selfUser(JSONObject response) {
        JSONObject user = response.optJSONObject("user");
        if (user == null) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "response lacks the user object");
        }
        try {
            return TlJsonMapper.parseUser(user, true);
        } catch (JSONException e) {
            throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "malformed user object: " + e.getMessage());
        }
    }

    private XoApiException sessionInvalid(String why) {
        FileLog.e("RestGateway: session invalid on account " + account + ": " + why);
        SessionInvalidListener listener = sessionInvalidListener;
        if (listener != null) {
            try {
                listener.onSessionInvalid(account);
            } catch (Exception e) {
                FileLog.e("RestGateway: session-invalid listener threw", e);
            }
        }
        // T7d: the T7b auto-performLogout here is deliberately REVERTED. It fired
        // from worker threads while the UI could be mid-login-transition —
        // LaunchActivity.clearFragments() then left an empty fragment stack: the
        // exact "confetti, then BLACK screen, no crash" report. Lifecycle moves
        // belong to the UI layer (via SessionInvalidListener), never to network
        // callbacks. Recovery for a genuinely dead family = manual logout or a
        // fresh login; the poller self-stops and errors surface as typed TL_errors.
        return new XoApiException(401, XoApiException.SESSION_INVALID, why);
    }

    /** Fluent put for statically-built bodies; JSONObject.put is checked but cannot fail on string values. */
    private static JSONObject put(JSONObject json, String key, String value) {
        try {
            json.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException("static JSON build failed for key " + key, e);
        }
        return json;
    }

    /** Same contract as {@link #put} for long values. */
    private static JSONObject putNumber(JSONObject json, String key, long value) {
        try {
            json.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException("static JSON build failed for key " + key, e);
        }
        return json;
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }
}

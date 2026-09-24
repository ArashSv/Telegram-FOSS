package org.telegram.tgnet.rest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * T3: JSON (docs/API.md v1 object shapes) to TLRPC object mapper.
 *
 * <p>Field mapping follows API.md §10. TLRPC flag bits were read from
 * {@code TLRPC.TL_user.readParams} in this tree (10.14.3), not guessed:
 * first_name = 2, username = 8, phone = 16, self = 1024; id is int64.
 *
 * <p>Deliberate gaps (frozen v1 contract has no equivalent):
 * <ul>
 *   <li>{@code access_hash} stays 0 — no REST notion; serialize bodies remain
 *       intact per plan, we only fill fields for UI/database consumption.</li>
 *   <li>{@code avatar_file_id} is ignored for now — photo stays null, the UI
 *       renders its standard initials placeholder. Wiring comes with file
 *       support (T8).</li>
 *   <li>{@code display_name} lands in {@code first_name} so dialogs render it
 *       directly ("Ali Rezaei" as one name is valid in the Telegram UI).</li>
 * </ul>
 */
public final class TlJsonMapper {

    private TlJsonMapper() {
    }

    /**
     * Public- or self-shaped user JSON to a filled {@link TLRPC.TL_user}.
     *
     * @param self true for responses that carry the self shape (verify / refresh
     *             / me) — sets the self flag so UserConfig treats it as the
     *             current account user
     * @throws JSONException when the JSON is not a usable v1 user object
     */
    public static TLRPC.TL_user parseUser(JSONObject object, boolean self) throws JSONException {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = object.getLong("id");

        String displayName = object.optString("display_name", null);
        if (displayName == null || displayName.length() == 0) {
            displayName = "user" + user.id;
        }
        user.first_name = displayName;
        user.flags |= 2; // first_name present

        Object usernameObj = object.opt("username");
        // T33: explicit JSON null must NOT become the string "null" (the
        // optString trap — org.json's NULL sentinel toString()s to "null"),
        // which would pollute objectsByUsernames with a "null" key.
        if (usernameObj instanceof String) {
            String username = (String) usernameObj;
            if (username.length() > 0) {
                user.username = username;
                user.flags |= 8;
            }
        }
        String phone = object.optString("phone", null); // self shape only
        if (phone != null && phone.length() > 0) {
            user.phone = phone;
            user.flags |= 16;
        }
        if (self) {
            user.flags |= 1024;
            user.self = true;
        }
        // T32: the avatar surface json ({small,big} crop file ids) — the ONLY
        // place a user's profile photo crosses JSON->TL. null keeps the
        // field-proven initials-fallback path (getForUser null-checks photo).
        JSONObject photoJson = object.optJSONObject("photo");
        if (photoJson != null) {
            TLRPC.TL_userProfilePhoto photo = parseUserProfilePhoto(photoJson);
            if (photo != null) {
                user.photo = photo;
                user.flags |= 32; // photo present — serialize contract
            }
        }
        // T32: ImageLocation.getForUser refuses access_hash == 0 (guard before
        // the photo check), so avatars would never render. The backend has no
        // hash concept; plant a stable non-zero value and keep it flag-coherent
        // (bit 0) so the MessagesStorage round-trip preserves it.
        user.access_hash = 1;
        user.flags |= 1;
        // null status NPEs in legacy UI paths (UserObject.isOnline and friends);
        // every MTProto-parsed user carries one, so we do too
        user.status = new TLRPC.TL_userStatusEmpty();
        return user;
    }

    /** Convenience for {@code users/get.php}-style arrays; entries map as public users. */
    public static ArrayList<TLRPC.TL_user> parseUsers(JSONArray array) throws JSONException {
        ArrayList<TLRPC.TL_user> users = new ArrayList<>();
        if (array != null) {
            for (int a = 0; a < array.length(); a++) {
                users.add(parseUser(array.getJSONObject(a), false));
            }
        }
        return users;
    }

    // ------------------------------------------------------------------ chats & dialogs (T5)

    /**
     * Chat JSON (public shape, API.md §4) to a filled {@link TLRPC.TL_chat}.
     * Only group/channel chats carry a title; private chats are rendered via
     * the peer user instead, so this is only called for group types.
     *
     * <p>photo must be non-null (serialize writes it unconditionally), so it
     * stays {@code TL_chatPhotoEmpty} — the UI renders its initials fallback.
     * {@code role: "creator"} maps to the MTProto creator flag.
     */
    public static TLRPC.TL_chat parseGroupChat(JSONObject chat) throws JSONException {
        TLRPC.TL_chat result = new TLRPC.TL_chat();
        result.id = chat.getLong("id");
        result.title = chat.optString("title", null);
        if (result.title == null || result.title.length() == 0) {
            result.title = "chat" + result.id;
        }
        result.participants_count = chat.optInt("members_count", 0);
        result.date = (int) chat.optLong("created_at", System.currentTimeMillis() / 1000L);
        result.version = 0;
        if ("creator".equals(chat.optString("role", null))) {
            result.creator = true;
            result.flags |= 1;
        }
        // T32: group avatar surface — same {small,big} json as users.
        JSONObject photoJson = chat.optJSONObject("photo");
        TLRPC.TL_chatPhoto chatPhoto = photoJson != null ? parseChatPhoto(photoJson) : null;
        result.photo = chatPhoto != null ? chatPhoto : new TLRPC.TL_chatPhotoEmpty();
        return result;
    }

    // ------------------------------------------------------------------ T32: avatar surfaces

    /**
     * Avatar location factory: {@code volume_id = -cropFileId, local_id = 'a'/'c'}.
     * The download route decodes |volume_id| to the CROP file id and the
     * letters are deliberately NOT 's'/'m' (those mean "thumb of the parent"
     * in the dispatcher — an avatar crop is a full file of its own).
     * Storage reload note: TL_userProfilePhoto/TL_chatPhoto reconstruct both
     * slots from photo_id (volume_id = -photo_id), so photo_id is planted with
     * the BIG crop id — after a reload both slots resolve to a real file.
     */
    private static TLRPC.TL_fileLocationToBeDeprecated avatarLocation(long cropFileId, char letter) {
        TLRPC.TL_fileLocationToBeDeprecated location = new TLRPC.TL_fileLocationToBeDeprecated();
        location.volume_id = -cropFileId; // download route decodes |volume_id|
        location.local_id = letter;
        location.dc_id = VIRTUAL_DC;
        return location;
    }

    /**
     * photo json (AvatarService contract: {small:{file_id,...},big:{...}}) to a
     * filled TL_userProfilePhoto; null when the json is degenerate (caller
     * keeps its empty/absent path).
     */
    public static TLRPC.TL_userProfilePhoto parseUserProfilePhoto(JSONObject photoJson) {
        if (photoJson == null) {
            return null;
        }
        JSONObject small = photoJson.optJSONObject("small");
        JSONObject big = photoJson.optJSONObject("big");
        long smallId = small != null ? small.optLong("file_id", 0) : 0;
        long bigId = big != null ? big.optLong("file_id", 0) : 0;
        if (smallId <= 0 || bigId <= 0) {
            return null;
        }
        TLRPC.TL_userProfilePhoto photo = new TLRPC.TL_userProfilePhoto();
        photo.photo_id = bigId; // reload-stable: see avatarLocation javadoc
        photo.photo_small = avatarLocation(smallId, 'a');
        photo.photo_big = avatarLocation(bigId, 'c');
        return photo;
    }

    /** Group-avatar variant of {@link #parseUserProfilePhoto}. */
    public static TLRPC.TL_chatPhoto parseChatPhoto(JSONObject photoJson) {
        if (photoJson == null) {
            return null;
        }
        JSONObject small = photoJson.optJSONObject("small");
        JSONObject big = photoJson.optJSONObject("big");
        long smallId = small != null ? small.optLong("file_id", 0) : 0;
        long bigId = big != null ? big.optLong("file_id", 0) : 0;
        if (smallId <= 0 || bigId <= 0) {
            return null;
        }
        TLRPC.TL_chatPhoto photo = new TLRPC.TL_chatPhoto();
        photo.photo_id = bigId; // reload-stable: see avatarLocation javadoc
        photo.photo_small = avatarLocation(smallId, 'a');
        photo.photo_big = avatarLocation(bigId, 'c');
        return photo;
    }

    /**
     * TL_photo for the TL_photos_uploadProfilePhoto response: sizes must let
     * ProfileActivity pick "closest to 150" (the small crop) and "closest to
     * 800" (the big crop), with locations the tree copies into its fresh
     * TL_userProfilePhoto. The consumer ALSO renames the local crop file into
     * the small slot's cache path, so the avatar renders without a download.
     */
    public static TLRPC.TL_photo avatarPhoto(JSONObject photoJson, int dateSeconds) {
        if (photoJson == null) {
            return null;
        }
        JSONObject small = photoJson.optJSONObject("small");
        JSONObject big = photoJson.optJSONObject("big");
        long smallId = small != null ? small.optLong("file_id", 0) : 0;
        long bigId = big != null ? big.optLong("file_id", 0) : 0;
        if (smallId <= 0 || bigId <= 0) {
            return null;
        }
        int smallBytes = small != null ? small.optInt("size", 0) : 0;
        int bigBytes = big != null ? big.optInt("size", 0) : 0;
        TLRPC.TL_photo photo = new TLRPC.TL_photo();
        photo.id = bigId;
        photo.access_hash = 0;
        photo.file_reference = new byte[0];
        photo.date = dateSeconds;
        photo.dc_id = VIRTUAL_DC;
        TLRPC.TL_photoSize smallSize = new TLRPC.TL_photoSize();
        smallSize.type = "a";
        smallSize.w = small != null ? Math.max(1, small.optInt("width", 160)) : 160;
        smallSize.h = small != null ? Math.max(1, small.optInt("height", 160)) : 160;
        smallSize.size = Math.max(0, smallBytes);
        smallSize.location = avatarLocation(smallId, 'a');
        photo.sizes.add(smallSize);
        TLRPC.TL_photoSize bigSize = new TLRPC.TL_photoSize();
        bigSize.type = "c";
        bigSize.w = big != null ? Math.max(1, big.optInt("width", 640)) : 640;
        bigSize.h = big != null ? Math.max(1, big.optInt("height", 640)) : 640;
        bigSize.size = Math.max(0, bigBytes);
        bigSize.location = avatarLocation(bigId, 'c');
        photo.sizes.add(bigSize);
        return photo;
    }

    /**
     * Message JSON (API.md §4) to a filled {@link TLRPC.TL_message}.
     *
     * @param dialogId   Telegram-space dialog id (peer user id, or -chat_id for groups)
     * @param isGroup    false = private dialog (peer_id is the peer user)
     * @param peerUserId peer of the private dialog (ignored for groups)
     * @param selfId     current account user id — decides the out flag
     * @throws JSONException when the JSON is not a usable v1 message
     */
    public static TLRPC.TL_message parseMessage(JSONObject msg, long dialogId, boolean isGroup,
                                                long peerUserId, long selfId) throws JSONException {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = (int) msg.getLong("id");
        long senderId = msg.getLong("sender_id");
        message.date = (int) msg.optLong("date", System.currentTimeMillis() / 1000L);
        String content = msg.optString("content", null);
        message.message = content == null ? "" : content;

        message.from_id = new TLRPC.TL_peerUser();
        message.from_id.user_id = senderId;
        message.flags |= 256; // MESSAGE_FLAG_HAS_FROM_ID — always present in v1

        if (isGroup) {
            message.peer_id = new TLRPC.TL_peerChat();
            message.peer_id.chat_id = -dialogId; // dialog id = -chat_id by convention
        } else {
            message.peer_id = new TLRPC.TL_peerUser();
            message.peer_id.user_id = peerUserId;
        }
        message.out = senderId == selfId;
        if (message.out) {
            message.flags |= 2;
        }

        // Reply contract (T11): the ONLY place reply metadata crosses JSON->TL.
        // TL_messageReplyHeader serializes reply_to_msg_id ONLY under its own
        // flag 16 (see TLRPC.TL_messageReplyHeader.serializeToStream) — without
        // that bit the header round-trips through MessagesStorage EMPTY, which
        // is why replies rendered live but vanished after a history/storage
        // reload. Every header we emit must therefore be flag-coherent.
        long replyTo = msg.optLong("reply_to_id", 0L);
        if (replyTo > 0) {
            TLRPC.TL_messageReplyHeader header = new TLRPC.TL_messageReplyHeader();
            header.flags |= 16; // REPLY_HEADER_FLAG_HAS_MSG_ID — mandatory for serialization
            header.reply_to_msg_id = (int) replyTo;
            message.reply_to = header;
            message.flags |= 8; // MESSAGE_FLAG_HAS_REPLY
        }

        // media must never be null (legacy UI paths deref it) and must stay
        // coherent with the flag bit so storage round-trips survive
        JSONObject mediaJson = msg.optJSONObject("media");
        message.media = parseMedia(mediaJson, message.date);
        message.flags |= 512;
        message.dialog_id = dialogId;

        // T33: the REST pipeline carries no message entities, so @mentions were
        // never tappable. Detect Telegram-style handles client-side and plant
        // TL_messageEntityMention entries (offsets/lengths are UTF-16 code
        // units — Java String indexes already are). The HAS_ENTITIES flag bit
        // must ride along or MessagesStorage round-trips silently drop the
        // list — same lesson as the T11 reply-header flag.
        ArrayList<TLRPC.TL_messageEntityMention> mentions = detectMentions(message.message);
        if (!mentions.isEmpty()) {
            message.entities.addAll(mentions);
            message.flags |= 128;
        }
        return message;
    }

    /** Telegram-style handle: '@' + 5..32 of [A-Za-z0-9_], boundary-checked. */
    private static final java.util.regex.Pattern MENTION_PATTERN =
            java.util.regex.Pattern.compile("@[A-Za-z0-9_]{5,32}");

    /**
     * Client-side @mention detection over UTF-16 offsets.
     *
     * Boundary rules keep e-mail fragments ("a@user_x") and sub-strings
     * ("x@user_y") out; a greedy over-match past the real handle (a 33+ char
     * token) is rejected by the trailing-boundary check, matching Telegram's
     * no-mention-at-all behavior for impossible handles.
     */
    public static ArrayList<TLRPC.TL_messageEntityMention> detectMentions(String text) {
        ArrayList<TLRPC.TL_messageEntityMention> out = new ArrayList<>();
        if (text == null || text.length() < 6 || text.indexOf('@') < 0) {
            return out;
        }
        java.util.regex.Matcher matcher = MENTION_PATTERN.matcher(text);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            if (start > 0) {
                char prev = text.charAt(start - 1);
                if (prev == '@' || prev == '_' || Character.isLetterOrDigit(prev)) {
                    continue;
                }
            }
            if (end < text.length()) {
                char next = text.charAt(end);
                if (next == '_' || Character.isLetterOrDigit(next)) {
                    continue;
                }
            }
            TLRPC.TL_messageEntityMention entity = new TLRPC.TL_messageEntityMention();
            entity.offset = start;
            entity.length = end - start;
            out.add(entity);
        }
        return out;
    }

    // ------------------------------------------------------------------ media (T8d)

    /** PhotoSize type letters this mapper plants; shared with the download-route resolver. */
    public static final String PHOTO_SIZE_FULL = "x";
    public static final String PHOTO_SIZE_THUMB = "s";
    /**
     * T28: the chat BUBBLE size. It points at the SAME small thumb bytes as
     * {@link #PHOTO_SIZE_THUMB} but carries nominal dims capped at
     * {@link #BUBBLE_MAX_SIDE} so the tree's "smallest size that fits the view"
     * picker selects it for message bubbles. Before this letter existed, the
     * only entry big enough for a bubble was PHOTO_SIZE_FULL — every photo
     * bubble downloaded the FULL original (the "thumbnails are way too heavy"
     * field report). Full-screen still picks PHOTO_SIZE_FULL.
     */
    public static final String PHOTO_SIZE_BUBBLE = "m";
    /** Nominal long-side cap for the bubble size (720p-class hint). */
    private static final int BUBBLE_MAX_SIDE = 720;

    /**
     * Virtual datacenter id planted on every synthetic Photo, Document and
     * PhotoSize location (v1.2 contract correction — the root cause of the
     * "progress bar never moves" media-download outage, both accounts).
     *
     * <p>MTProto media is addressable per-DC, and this tree treats
     * {@code dc_id == 0} as "not a downloadable object" in four independent
     * places, every one verified in-tree while debugging the outage:
     * <ul>
     *   <li>{@code FileLoadOperation.start()} — {@code datacenterId == 0}
     *       calls {@code onFail(true, 0)} BEFORE any network request, in BOTH
     *       branches (volume-id branch :919, id branch :942). Every download
     *       of every media type died here instantly and silently: no bytes,
     *       no progress, no error, no timeout — exactly the field report;</li>
     *   <li>{@code ImageLocation.getKey()} :405 — {@code document.dc_id == 0}
     *       yields a null key, so {@code ImageLocation.getForDocument(document)}
     *       locations never even enter the ImageLoader pipeline;</li>
     *   <li>{@code MessageObject.isEditingMedia()} :8215 —
     *       {@code document.dc_id == 0} reports true for every document
     *       message (wrong edit-state semantics);</li>
     *   <li>{@code ImageReceiver} :599 — the document-preview path refuses
     *       {@code dc_id == 0} documents.</li>
     * </ul>
     * The value itself is irrelevant under the REST layer — the T5 hook
     * ignores {@code datacenterId} end-to-end — so 1 (the canonical "first
     * DC") is safe and keeps document cache keys ({@code dc_id}
     * + "_" + id) stable.
     *
     * <p>Durability note: {@code TL_fileLocationToBeDeprecated} does NOT
     * serialize {@code dc_id} — after a MessagesStorage reload the location
     * dc reverts to 0 and {@code ImageLocation.getForPhoto} falls back to
     * {@code photo.dc_id}. {@code TL_photo} and {@code TL_document} DO
     * serialize {@code dc_id}, so the durable value lives on the parents;
     * locations are planted too for in-memory coherence.
     */
    public static final int VIRTUAL_DC = 1;
    /** Server thumb cap (FilesController::makeThumbnail) — mirrored for local layout hints. */
    private static final int THUMB_MAX_SIDE = 320;

    /**
     * v1.1 media JSON (MessageMapper join: file_id/mime_type/size/name/kind/
     * width/height/duration/thumb_file_id) to a flag-coherent MessageMedia.
     * Never returns null — degenerate input degrades to {@code TL_messageMediaEmpty}
     * (parse failures must never lose the message; PHASE3_PLAN.md §8).
     *
     * <p>Synthetic location contract (the pivot of the download route): every
     * planted PhotoSize carries {@code TL_fileLocationToBeDeprecated{volume_id =
     * -parentFileId, local_id = type letter}} — byte-identical to what the
     * tree's PhotoSize factory reconstructs after a MessagesStorage reload
     * ({@code volume_id = -photo_id}, T11 flag-coherence lesson applied to
     * locations). The dispatcher decodes {@code |volume_id|} and — for thumb
     * letters — resolves the separate thumb file via a cached metadata lookup.
     *
     * <p>Photos (kind=image) render as TL_messageMediaPhoto with a thumb +
     * full size; everything else is a TL_messageMediaDocument (video, audio,
     * voice notes, "send as file" images — the tree's own semantics), with
     * attributes rebuilt so the UI renders the right bubble.
     */
    public static TLRPC.MessageMedia parseMedia(JSONObject mediaJson, int messageDate) {
        if (mediaJson == null) {
            return new TLRPC.TL_messageMediaEmpty();
        }
        long fileId = mediaJson.optLong("file_id", 0);
        if (fileId <= 0) {
            return new TLRPC.TL_messageMediaEmpty();
        }
        String kind = mediaJson.optString("kind", null);
        String mime = mediaJson.optString("mime_type", null);
        if (mime == null || mime.length() == 0) {
            mime = "application/octet-stream";
        }
        long size = Math.max(0, mediaJson.optLong("size", 0));
        int width = mediaJson.optInt("width", 0);
        int height = mediaJson.optInt("height", 0);
        int duration = mediaJson.optInt("duration", 0);
        long thumbFileId = mediaJson.optLong("thumb_file_id", 0);

        // T29: the media json IS the server's attestation of the file's size and
        // sha256 — feed the download-integrity index so every later range
        // request for this file is clamped at the true EOF and the assembled
        // download is verified against these exact values before "success".
        RestFileBridge.noteFileMeta(fileId, size,
                mediaJson.isNull("sha256") ? null : mediaJson.optString("sha256", null));

        if ("image".equals(kind) && width > 0 && height > 0) {
            return photoMedia(fileId, size, width, height, thumbFileId, messageDate);
        }
        return documentMedia(fileId, mime, size, mediaJson.optString("name", null),
                kind, width, height, duration, thumbFileId, messageDate);
    }

    /** Photo with one thumb + one full size, both located on the photo id (factory-consistent). */
    private static TLRPC.MessageMedia photoMedia(long fileId, long size, int width, int height,
                                                 long thumbFileId, int messageDate) {
        TLRPC.TL_messageMediaPhoto media = new TLRPC.TL_messageMediaPhoto();
        media.flags |= 1; // photo present — serialize contract (readParams expects flag 1)
        TLRPC.TL_photo photo = new TLRPC.TL_photo();
        photo.id = fileId;
        photo.access_hash = 0;
        photo.file_reference = new byte[0];
        photo.date = messageDate > 0 ? messageDate : (int) (System.currentTimeMillis() / 1000L);
        photo.dc_id = VIRTUAL_DC; // durable across storage reload (TL_photo serializes dc_id) — see VIRTUAL_DC javadoc
        if (thumbFileId > 0) {
            int[] dims = thumbDims(width, height);
            photo.sizes.add(photoSize(PHOTO_SIZE_THUMB, dims[0], dims[1], 0, fileId));
            // T28: bubble-size entry backed by the SAME thumb bytes — see the
            // PHOTO_SIZE_BUBBLE javadoc. Without it the bubble picker falls
            // through to the full original.
            double bubbleScale = Math.min(1.0, BUBBLE_MAX_SIDE / (double) Math.max(1, Math.max(width, height)));
            photo.sizes.add(photoSize(PHOTO_SIZE_BUBBLE,
                    Math.max(1, (int) Math.round(width * bubbleScale)),
                    Math.max(1, (int) Math.round(height * bubbleScale)), 0, fileId));
        }
        photo.sizes.add(photoSize(PHOTO_SIZE_FULL, width, height, (int) Math.min(size, Integer.MAX_VALUE), fileId));
        media.photo = photo;
        return media;
    }

    /** One TL_photoSize with the factory-consistent synthetic location. */
    private static TLRPC.TL_photoSize photoSize(String type, int w, int h, int size, long parentFileId) {
        TLRPC.TL_photoSize photoSize = new TLRPC.TL_photoSize();
        photoSize.type = type;
        photoSize.w = Math.max(1, w);
        photoSize.h = Math.max(1, h);
        photoSize.size = Math.max(0, size);
        TLRPC.TL_fileLocationToBeDeprecated location = new TLRPC.TL_fileLocationToBeDeprecated();
        location.volume_id = -parentFileId; // download route decodes |volume_id|
        location.local_id = type.charAt(0);
        location.dc_id = VIRTUAL_DC; // in-memory coherence only — this class does not serialize dc_id
        photoSize.location = location;
        return photoSize;
    }

    /** Document-shaped media: video, audio/voice, gif, generic files, image-as-file. */
    private static TLRPC.MessageMedia documentMedia(long fileId, String mime, long size, String name,
                                                    String kind, int width, int height, int duration,
                                                    long thumbFileId, int messageDate) {
        TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
        media.flags |= 1; // document present
        TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = fileId;
        document.access_hash = 0;
        document.file_reference = new byte[0];
        document.date = messageDate > 0 ? messageDate : (int) (System.currentTimeMillis() / 1000L);
        document.mime_type = mime;
        document.size = size;
        document.dc_id = VIRTUAL_DC; // durable across storage reload (TL_document serializes dc_id) — see VIRTUAL_DC javadoc
        if (thumbFileId > 0) {
            document.flags |= 1; // thumbs vector present
            int[] dims = thumbDims(width > 0 ? width : 320, height > 0 ? height : 320);
            TLRPC.TL_photoSize thumb = photoSize(PHOTO_SIZE_THUMB, dims[0], dims[1], 0, fileId);
            thumb.location.local_id = 1000 + thumb.type.charAt(0); // document-thumb factory convention
            document.thumbs.add(thumb);
        }
        if (name != null && name.length() > 0) {
            TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename();
            filename.file_name = name;
            document.attributes.add(filename);
        }
        boolean isVoice = mime.startsWith("audio/ogg");
        if (mime.startsWith("video/") || "video".equals(kind)) {
            TLRPC.TL_documentAttributeVideo video = new TLRPC.TL_documentAttributeVideo();
            video.duration = Math.max(0.5, duration > 0 ? duration : 1);
            video.w = Math.max(1, width);
            video.h = Math.max(1, height);
            document.attributes.add(video);
        } else if (mime.startsWith("audio/")) {
            TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
            audio.duration = Math.max(1, duration);
            if (isVoice) {
                audio.flags |= 1024; // voice note bit — serialize contract
                audio.voice = true;
                media.flags |= 256; // media.voice — the UI's voice-bubble selector
            }
            document.attributes.add(audio);
        } else if ("image".equals(kind) && "image/gif".equals(mime)) {
            document.attributes.add(new TLRPC.TL_documentAttributeAnimated());
        } else if ("image".equals(kind) && width > 0 && height > 0) {
            // image sent as file: keep dimensions so the gallery preview renders
            TLRPC.TL_documentAttributeImageSize imageSize = new TLRPC.TL_documentAttributeImageSize();
            imageSize.w = width;
            imageSize.h = height;
            document.attributes.add(imageSize);
        }
        media.document = document;
        return media;
    }

    /** Longest side scaled to the server's thumb cap (layout hint only). */
    private static int[] thumbDims(int width, int height) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        double scale = Math.min(1.0, THUMB_MAX_SIDE / (double) Math.max(w, h));
        return new int[]{Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale))};
    }

    /**
     * Full {@code /chats/list.php} answer to a {@link TLRPC.TL_messages_dialogs}
     * container — the exact class MessagesController casts the
     * TL_messages_getDialogs response to (MessagesController.java:10881).
     *
     * <p>Dialog ids follow Telegram semantics: peer user id for private, -chat_id
     * for groups. {@code read_inbox_max_id} is estimated from unread_count because
     * the v1 chat shape carries no read cursors; the value only positions the
     * "unread" divider and heals as soon as the user opens the chat.
     *
     * @param index chat index warmed with every scanned chat (peer<->chat_id map)
     */
    public static TLRPC.TL_messages_dialogs parseDialogs(JSONArray chatsJson, long selfId, RestChatIndex index) {
        TLRPC.TL_messages_dialogs container = new TLRPC.TL_messages_dialogs();
        RestChatIndex.ScanResult scan = index.scanChats(chatsJson);
        container.users.addAll(scan.users);
        container.chats.addAll(scan.chats);
        if (chatsJson != null) {
            for (int a = 0; a < chatsJson.length(); a++) {
                JSONObject chat = chatsJson.optJSONObject(a);
                if (chat == null) {
                    continue;
                }
                try {
                    long chatId = chat.getLong("id");
                    boolean isGroup = !"private".equals(chat.optString("type", "private"));
                    TLRPC.TL_user peer = scan.userByChat.get(chatId);
                    if (!isGroup && peer == null) {
                        continue; // malformed entry — no peer to address the dialog by
                    }
                    long dialogId = isGroup ? -chatId : peer.id;

                    TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
                    dialog.id = dialogId;
                    if (isGroup) {
                        dialog.peer = new TLRPC.TL_peerChat();
                        dialog.peer.chat_id = chatId;
                    } else {
                        dialog.peer = new TLRPC.TL_peerUser();
                        dialog.peer.user_id = peer.id;
                    }
                    dialog.unread_count = chat.optInt("unread_count", 0);
                    dialog.notify_settings = new TLRPC.TL_peerNotifySettings();

                    JSONObject lastJson = chat.optJSONObject("last_message");
                    if (lastJson != null) {
                        TLRPC.TL_message last = parseMessage(lastJson, dialogId, isGroup,
                                isGroup ? 0 : peer.id, selfId);
                        dialog.top_message = last.id;
                        dialog.last_message_date = last.date;
                        dialog.read_outbox_max_id = last.id; // v1 has no outbox cursor; sync 'read' events correct it
                        dialog.read_inbox_max_id = dialog.unread_count == 0 ? last.id
                                : Math.max(0, last.id - dialog.unread_count);
                        container.messages.add(last);
                    }
                    container.dialogs.add(dialog);
                } catch (JSONException e) {
                    FileLog.e("TlJsonMapper: malformed chat in list", e);
                }
            }
        }
        container.count = container.dialogs.size();
        return container;
    }

    /**
     * {@code /messages/history.php} messages (ascending, oldest first) to
     * TL_message objects in the same order. The dispatcher decides the
     * ordering Telegram expects (descending for getHistory) — the mapper
     * stays a pure JSON->TL translator.
     */
    public static ArrayList<TLRPC.TL_message> parseHistory(JSONArray messagesJson, long dialogId,
                                                           boolean isGroup, long peerUserId,
                                                           long selfId) throws JSONException {
        ArrayList<TLRPC.TL_message> messages = new ArrayList<>();
        if (messagesJson != null) {
            for (int a = 0; a < messagesJson.length(); a++) {
                JSONObject msg = messagesJson.getJSONObject(a);
                messages.add(parseMessage(msg, dialogId, isGroup, peerUserId, selfId));
            }
        }
        return messages;
    }
}

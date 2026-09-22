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

        String username = object.optString("username", null);
        if (username != null && username.length() > 0) {
            user.username = username;
            user.flags |= 8;
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
        result.photo = new TLRPC.TL_chatPhotoEmpty();
        return result;
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

        // media must never be null (legacy UI paths deref it) and must stay
        // coherent with the flag bit so storage round-trips survive
        message.media = new TLRPC.TL_messageMediaEmpty();
        message.flags |= 512;
        message.dialog_id = dialogId;

        long replyTo = msg.optLong("reply_to_id", 0L);
        if (replyTo != 0L) {
            TLRPC.TL_messageReplyHeader header = new TLRPC.TL_messageReplyHeader();
            header.reply_to_msg_id = (int) replyTo;
            message.reply_to = header;
            message.flags |= 8;
        }
        return message;
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

package org.telegram.tgnet.rest;

import android.util.LongSparseArray;
import android.util.SparseArray;

import org.telegram.messenger.FileLog;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * T5: in-memory bridge between the two id worlds.
 *
 * <p>The Telegram UI addresses private dialogs by peer user id and group
 * dialogs by -chat id; the backend v1 only knows its own chat_id sequence
 * (chats are independent rows, a private chat's id has no relation to the
 * peer's user id). Rewriting dialog ids inside the UI would touch every
 * screen — instead the dispatcher translates at the same single funnel:
 * <ul>
 *   <li>private: user_id <-> backend chat_id (pair_key find-or-create on
 *       /chats/create.php makes the resolve-on-miss path idempotent);</li>
 *   <li>group: TLRPC.Chat.id IS the backend chat_id (peer_id = TL_peerChat,
 *       dialog id = -chat_id), cached here so sync events can rehydrate the
 *       Chat object without a server round trip.</li>
 * </ul>
 *
 * <p>The index is rebuilt from the first /chats/list.php answer (which the
 * app fetches on every start via TL_messages_getDialogs) and stays warm via
 * send/create/chat_new handling. Nothing is persisted: the source of truth
 * is always the next chats/list answer.
 */
public final class RestChatIndex {

    private static final RestChatIndex[] instances = new RestChatIndex[4];

    public static RestChatIndex getInstance(int account) {
        if (account < 0 || account >= instances.length) {
            account = 0;
        }
        RestChatIndex index;
        synchronized (RestChatIndex.class) {
            index = instances[account];
            if (index == null) {
                index = new RestChatIndex();
                instances[account] = index;
            }
        }
        return index;
    }

    /** user_id -> backend chat_id for private chats. */
    private final LongSparseArray<Long> privateChatByUser = new LongSparseArray<>();
    /** backend chat_id -> user_id for private chats (reverse view). */
    private final SparseArray<Long> userByPrivateChat = new SparseArray<>();
    /** backend chat_id -> last seen group Chat object (for sync chatsArr hydration). */
    private final SparseArray<TLRPC.TL_chat> groupChats = new SparseArray<>();
    /** backend chat_id -> chat type ("private" | "group" | "channel"). */
    private final HashMap<Long, String> chatTypes = new HashMap<>();
    /** message id -> backend chat_id, for revoke deletes (deleteMessages carries ids only). */
    private final SparseArray<Long> chatByMessageId = new SparseArray<>();

    private RestChatIndex() {
    }

    /** Clears everything — used when a session is invalidated. */
    public void clear() {
        synchronized (this) {
            privateChatByUser.clear();
            userByPrivateChat.clear();
            groupChats.clear();
            chatTypes.clear();
            chatByMessageId.clear();
        }
    }

    /** Registers a private chat mapping and returns the backend chat_id. */
    public long putPrivate(long userId, long chatId) {
        synchronized (this) {
            privateChatByUser.put(userId, chatId);
            userByPrivateChat.put((int) chatId, userId);
            chatTypes.put(chatId, "private");
            return chatId;
        }
    }

    /** Registers/updates a group (or channel) chat object. */
    public void putGroup(TLRPC.TL_chat chat) {
        if (chat == null) {
            return;
        }
        synchronized (this) {
            groupChats.put((int) chat.id, chat);
            chatTypes.put(chat.id, "group");
        }
    }

    /** @return the backend chat_id for a private peer, or 0 when unknown. */
    public long privateChatIdFor(long userId) {
        synchronized (this) {
            Long chatId = privateChatByUser.get(userId);
            return chatId == null ? 0L : chatId;
        }
    }

    /** @return the peer user id of a private chat, or 0 when unknown. */
    public long userForPrivateChat(long chatId) {
        synchronized (this) {
            Long userId = userByPrivateChat.get((int) chatId);
            return userId == null ? 0L : userId;
        }
    }

    /** @return the cached group Chat object, or null. */
    public TLRPC.TL_chat groupChat(long chatId) {
        synchronized (this) {
            return groupChats.get((int) chatId);
        }
    }

    /** @return true when the chat id is known to be a group/channel. */
    public boolean isGroup(long chatId) {
        synchronized (this) {
            String type = chatTypes.get(chatId);
            return "group".equals(type) || "channel".equals(type);
        }
    }

    /** @return true when ANY mapping exists for the backend chat id. */
    public boolean isKnownChat(long chatId) {
        synchronized (this) {
            return chatTypes.containsKey(chatId);
        }
    }

    /**
     * Records which backend chat owns which message ids. The dispatcher feeds
     * every message it parses through here (dialogs last messages, history
     * pages, sends, sync events) — revoke deletes arrive as bare ids, and the
     * UI can only delete messages it has seen, so memory coverage is exact.
     */
    public void rememberMessages(long chatId, java.util.List<TLRPC.TL_message> messages) {
        if (messages == null) {
            return;
        }
        synchronized (this) {
            for (int a = 0; a < messages.size(); a++) {
                chatByMessageId.put(messages.get(a).id, chatId);
            }
        }
    }

    /**
     * @return the backend chat_id owning any of the given message ids, or 0
     * when none of them were seen in this session
     */
    public long chatIdForAnyMessage(java.util.ArrayList<Integer> messageIds) {
        synchronized (this) {
            for (int a = 0; a < messageIds.size(); a++) {
                Long chatId = chatByMessageId.get(messageIds.get(a));
                if (chatId != null) {
                    return chatId;
                }
            }
        }
        return 0L;
    }

    /**
     * Rebuilds the index from a /chats/list.php or /chats/create.php chat
     * array. Group chat objects are parsed through {@link TlJsonMapper} and
     * cached. Returns the parsed Chat/User objects so callers can fill TL
     * container responses without re-parsing.
     */
    public static final class ScanResult {
        public final ArrayList<TLRPC.TL_user> users = new ArrayList<>();
        public final ArrayList<TLRPC.TL_chat> chats = new ArrayList<>();
        public final LongSparseArray<TLRPC.TL_user> userByChat = new LongSparseArray<>();
    }

    public ScanResult scanChats(org.json.JSONArray chatsJson) {
        ScanResult result = new ScanResult();
        if (chatsJson == null) {
            return result;
        }
        for (int a = 0; a < chatsJson.length(); a++) {
            org.json.JSONObject chatJson = chatsJson.optJSONObject(a);
            if (chatJson == null) {
                continue;
            }
            try {
                long chatId = chatJson.getLong("id");
                String type = chatJson.optString("type", "private");
                org.json.JSONObject peerJson = chatJson.optJSONObject("peer");
                synchronized (this) {
                    chatTypes.put(chatId, type);
                }
                if ("private".equals(type) && peerJson != null) {
                    TLRPC.TL_user peer = TlJsonMapper.parseUser(peerJson, false);
                    putPrivate(peer.id, chatId);
                    result.users.add(peer);
                    result.userByChat.put(chatId, peer);
                } else {
                    TLRPC.TL_chat chat = TlJsonMapper.parseGroupChat(chatJson);
                    if (chat != null) {
                        putGroup(chat);
                        result.chats.add(chat);
                    }
                }
            } catch (Exception e) {
                FileLog.e("RestChatIndex: malformed chat entry in scan", e);
            }
        }
        return result;
    }
}

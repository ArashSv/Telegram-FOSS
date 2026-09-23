package org.telegram.tgnet.rest;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Xo (T7c): "add chat by numeric ID". v1 has no contacts and no user search —
 * chats exist either server-side (pairing script) or via this feature. The
 * pencil FAB long-press opens a dialog that shows the account's own numeric id
 * and accepts the peer's id; this tool does the rest:
 *
 * <ol>
 *   <li>{@code GET /users/get.php?ids=} — hydrate the peer (fails with
 *       {@code USER_NOT_FOUND} on an unknown id, so typos never create ghost
 *       chats);</li>
 *   <li>{@code POST /chats/create.php} (type=private, pair_key idempotent) —
 *       safe to repeat, {@code created:false} just reloads the existing chat;</li>
 *   <li>{@link RestChatIndex#scanChats} — the peer-&lt;-&gt;chat_id map and the
 *       chat type cache are warmed so history/send/read address the dialog
 *       immediately, exactly like the {@code chat_new} poller path;</li>
 *   <li>UI thread: peer user + chat go into {@link MessagesController}, then a
 *       full {@code loadDialogs} reload surfaces the dialog in the list.</li>
 * </ol>
 *
 * <p>Threading: blocking REST on a private executor, callbacks on the UI thread.
 */
public final class XoChatTools {

    /** T9: first message of a freshly created chat — makes the dialog surface on both sides. */
    private static final String SEED_TEXT = "Chat created — say hi!";

    public interface Result {
        /** @param dialogId Telegram-space dialog id (= peer user id for private chats) */
        void onReady(long dialogId, String peerName, boolean created);
        void onError(String message);
    }

    private static final ExecutorService IO_QUEUE = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "XoChatTools");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private XoChatTools() {
    }

    /** Creates (or finds) the private chat between the account and peerUserId. */
    public static void addPrivateChatById(int account, long peerUserId, Result callback) {
        long selfId = UserConfig.getInstance(account).clientUserId;
        if (peerUserId <= 0) {
            callback.onError("Invalid ID");
            return;
        }
        if (peerUserId == selfId) {
            callback.onError("That is your own ID");
            return;
        }
        IO_QUEUE.execute(() -> {
            try {
                RestGateway gateway = RestGateway.getInstance(account);

                JSONArray users = gateway.usersGet(new long[]{peerUserId});
                if (users == null || users.length() == 0) {
                    postError(callback, "User " + peerUserId + " not found");
                    return;
                }
                JSONObject userJson = users.optJSONObject(0);
                if (userJson == null) {
                    postError(callback, "Malformed user answer");
                    return;
                }
                TLRPC.TL_user peer = TlJsonMapper.parseUser(userJson, false);

                JSONObject created = gateway.createPrivateChat(peerUserId);
                JSONObject chatJson = created.optJSONObject("chat");
                if (chatJson == null) {
                    postError(callback, "Malformed chat answer");
                    return;
                }
                boolean isNewChat = created.optBoolean("created", false);

                JSONArray single = new JSONArray();
                single.put(chatJson);
                RestChatIndex.ScanResult scan = RestChatIndex.getInstance(account).scanChats(single);

                long chatId = chatJson.optLong("id", 0);
                long dialogId = peer.id;
                TLRPC.TL_message seedMessage = null;
                if (isNewChat && chatId > 0) {
                    // T9: a brand-new chat has NO last_message, and a dialog without
                    // one does not surface in the legacy list (the user's own dot
                    // message was what finally made it appear). Send a seed message
                    // and apply it through the SAME path the send flow uses — the
                    // dialog then appears on BOTH sides instantly, without any
                    // logout/re-login. The peer gets it via the message_new poll
                    // event.
                    try {
                        JSONObject sent = gateway.send(chatId, SEED_TEXT, 0);
                        JSONObject msgJson = sent.optJSONObject("message");
                        if (msgJson != null) {
                            seedMessage = TlJsonMapper.parseMessage(msgJson, dialogId, false, peer.id, selfId);
                            RestChatIndex.getInstance(account).rememberMessages(chatId,
                                    new ArrayList<>(java.util.Collections.singletonList(seedMessage)));
                        }
                    } catch (Exception e) {
                        FileLog.e("XoChatTools: seed message failed (chat still created)", e);
                    }
                }

                ArrayList<TLRPC.User> usersToPut = new ArrayList<>();
                usersToPut.add(peer);
                ArrayList<TLRPC.Chat> chatsToPut = new ArrayList<>(scan.chats);

                final TLRPC.TL_message fSeed = seedMessage;
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        MessagesController messagesController = MessagesController.getInstance(account);
                        messagesController.putUsers(usersToPut, false);
                        messagesController.putChats(chatsToPut, false);
                        if (fSeed != null) {
                            // apply the seed through the canonical update path —
                            // same as a message arriving from the other device:
                            // creates the dialog, updates the list, stores it
                            TLRPC.TL_updates updates = new TLRPC.TL_updates();
                            TLRPC.TL_updateNewMessage update = new TLRPC.TL_updateNewMessage();
                            update.message = fSeed;
                            update.pts = 0;
                            update.pts_count = 0;
                            updates.updates.add(update);
                            updates.date = (int) (System.currentTimeMillis() / 1000L);
                            updates.seq = 0;
                            ArrayList<TLRPC.User> usersArr = new ArrayList<>(usersToPut);
                            Utilities.stageQueue.postRunnable(() -> {
                                try {
                                    messagesController.processUpdateArray(updates.updates, usersArr, chatsToPut, false, (int) (System.currentTimeMillis() / 1000L));
                                } catch (Exception e) {
                                    FileLog.e("XoChatTools: seed apply failed", e);
                                }
                            });
                        } else {
                            // no seed (existing chat): best-effort full page reload
                            messagesController.loadDialogs(0, 0, 100, false);
                        }
                    } catch (Exception e) {
                        FileLog.e("XoChatTools: post-create UI refresh failed", e);
                    }
                    callback.onReady(peer.id, peer.first_name, isNewChat);
                });
            } catch (XoApiException e) {
                String message = e.errorCode != null && !"UNKNOWN".equals(e.errorCode)
                        ? e.errorCode + (TextUtils.isEmpty(e.getMessage()) ? "" : ": " + e.getMessage())
                        : (e.getMessage() != null ? e.getMessage() : "Request failed");
                FileLog.e("XoChatTools: add by id failed", e);
                postError(callback, message);
            } catch (Exception e) {
                FileLog.e("XoChatTools: add by id failed", e);
                postError(callback, e.getMessage() != null ? e.getMessage() : "Connection failed");
            }
        });
    }

    private static void postError(Result callback, String message) {
        AndroidUtilities.runOnUIThread(() -> callback.onError(message));
    }
}

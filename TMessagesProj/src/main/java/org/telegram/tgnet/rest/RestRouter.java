package org.telegram.tgnet.rest;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.HashMap;

/**
 * Default-deny route table (plan critical correction #1): every TL method is
 * DENIED unless it is explicitly allowlisted here. The table grows task by
 * task — T4 wired auth into LoginActivity, T5 serves messages/chats/users
 * behind the ConnectionsManager funnel, T6 serves the sync state stubs.
 * Routes are never enabled before the code that serves them exists.
 *
 * <p>Keys are the concrete request classes (TLObject carries no instance
 * constructor field in this tree — the constructor int is a static constant
 * per class), so the lookup is an exact-class map read, no reflection. The
 * constructor ints below were read from TLRPC.java in this tree (10.14.3)
 * and are kept as provenance: TL_auth_sendCode = 0xa677244f,
 * TL_auth_signUp = 0x80eee427, TL_auth_signIn = 0x8d52a951,
 * TL_messages_getDialogs = 0xa0f4cb4f, TL_messages_getHistory = 0x4423e6c5,
 * TL_messages_sendMessage = 0x983f9745, TL_messages_readHistory = 0xe306d3a1,
 * TL_messages_deleteMessages = 0xe58e95d2, TL_users_getUsers = 0xd91a548,
 * TL_updates_getState = 0xedd4882a, TL_updates_getDifference = 0x25939651.
 *
 * <p><b>Response-class contract</b> (T12 lesson — the single most important
 * invariant of this package): the tree consumes routed responses with
 * hard casts, and a wrong class is a ClassCastException on the stageQueue —
 * for deleteMessages it even became a launch crash loop, because the request
 * is persisted as a pending task that only that callback clears. Every route
 * below therefore names its consuming call site; a response class may only
 * change together with that call site:
 * <ul>
 *   <li>ROUTE_DIALOGS → TL_messages_dialogs; consumer MessagesController
 *       getDialogs callback (hard cast, ~:10881);</li>
 *   <li>ROUTE_HISTORY → TL_messages_messages; consumer MessagesController
 *       getHistory callback (hard cast, ~:10196);</li>
 *   <li>ROUTE_SEND → TL_updates with one TL_updateNewMessage; consumer
 *       SendMessagesHelper (~:6420, hard cast);</li>
 *   <li>ROUTE_READ → TL_messages_affectedHistory (schema type of
 *       messages.readHistory); consumer MessagesController.completeReadTask
 *       (~:12920, instanceof-guarded — ignores it);</li>
 *   <li>ROUTE_DELETE → TL_messages_affectedMessages (schema type of
 *       messages.deleteMessages); consumer MessagesController.deleteMessages
 *       callback (~:8262, HARD cast + pending-task clear);</li>
 *   <li>ROUTE_USERS_GET → Vector of TL_user; consumer MessagesController
 *       users_getUsers callback (hard cast);</li>
 *   <li>ROUTE_STATE / ROUTE_DIFFERENCE → zeroed stubs (pts machinery stays
 *       pinned; getState cannot be error-denied — loadCurrentState retries
 *       forever on non-401 errors).</li>
 *   <li>ROUTE_FILE_PART / ROUTE_FILE_PART_BIG → TL_boolTrue; consumer
 *       FileUploadOperation callback (~:573) tests
 *       {@code response instanceof TL_boolTrue} and treats EVERYTHING else
 *       (null response with error, or any other class) as upload failure —
 *       so the answer class is non-negotiable (T8b).</li>
 *   <li>ROUTE_FILE_GET → TL_upload_file{bytes: NativeByteBuffer}; consumer
 *       FileLoadOperation (~:2542) instanceof-tests TL_upload_file first,
 *       TL_upload_webFile second, and HARD-CASTS the remaining branch to
 *       TL_upload_cdnFile — answering anything else is a ClassCastException
 *       (T8b; bytes.buffer must be positioned at 0, limit = byte count).</li>
 *   <li>ROUTE_SEND_MEDIA → TL_updates with one TL_updateNewMessage; the
 *       consumer is the SAME unified send path as ROUTE_SEND
 *       (SendMessagesHelper ~:6417: {@code response instanceof Updates} →
 *       extract TL_updateNewMessage) (T8c).</li>
 *   <li>ROUTE_GET_FULL_CHAT → TL_messages_chatFull; consumer MessagesController
 *       loadFullChat callback (~:6481 HARD cast {@code (TL_messages_chatFull) response})
 *       → putUsersAndChats + updateChatInfo(full_chat) → chatInfoDidLoad.
 *       That chatFull feeds ProfileActivity's inline members list and
 *       ChatUsersActivity's local members/administrators reads (T35).</li>
 *   <li>ROUTE_EDIT_CHAT_ABOUT → TL_boolTrue; consumer MessagesController
 *       updateChatAbout (~:13505 {@code response instanceof TL_boolTrue}) →
 *       patches info.about + chatInfoDidLoad (T35).</li>
 *   <li>ROUTE_CONTACTS_GET → TL_contacts_contacts; consumer ContactsController
 *       loadContacts (~:1518 HARD cast to contacts_Contacts) →
 *       processLoadedContacts (T35).</li>
 *   <li>ROUTE_CONTACTS_IMPORT → TL_contacts_importedContacts; consumers
 *       NewContactBottomSheet (~:666) and ContactsController
 *       performSyncPhoneBook (~:1359) HARD casts (T35).</li>
 *   <li>ROUTE_CONTACTS_ADD → TL_updates with the saved user; consumer
 *       ContactsController.addContact (~:2367 HARD cast to Updates,
 *       consumes res.users) (T35).</li>
 *   <li>ROUTE_CONTACTS_DELETE → TL_updates (empty); consumer
 *       ContactsController.deleteContact (~:505 HARD cast to Updates,
 *       processUpdates only) (T35).</li>
 *   <li>ROUTE_EDIT_DATA → TL_messages_messageEditData{caption=false}; consumer
 *       ChatActivity.startEditingMessageObject (~:30108, response == null →
 *       EditMessageError dialog + edit-mode exit) — a non-null answer of this
 *       class is all the consumer requires (T39).</li>
 *   <li>ROUTE_DELETE_HISTORY → TL_messages_affectedHistory; consumer
 *       MessagesController.deleteDialog callback (~:8795 HARD cast; offset=0
 *       stops the re-loop, pts=0 stays at the pinned baseline) (T39).</li>
 *   <li>ROUTE_DELETE_CHAT_USER → TL_updates; consumers
 *       MessagesController.deleteParticipantFromChat callbacks (~:13953 and
 *       ~:14029 HARD cast to Updates → processUpdates). SELF (leave) answers
 *       an EMPTY TL_updates — the local deleteDialog already ran before the
 *       send; KICK answers the authoritative post-kick snapshot (chats +
 *       TL_updateChatParticipants + users, same fabrication as addChatUser)
 *       so processUpdates applies the persisted member list (T40).</li>
 *   <li>ROUTE_EDIT_CHAT_ADMIN → TL_boolTrue; consumer
 *       MessagesController.setUserAdminRole basic-group branch (~:7661)
 *       tests error == null ONLY — the response is unused; the acting UI
 *       refreshes via loadFullChat 1 s later, every other member via the
 *       chat_member sync event (T40).</li>
 *   <li>ROUTE_DELETE_CHAT → TL_boolTrue; consumer
 *       MessagesController.deleteParticipantFromChat forceDelete branch
 *       (~:13935/:14010) IGNORES the response entirely — the local dialog
 *       deletion already ran and every ex-member converges via chat_member
 *       {event: deleted} (T40).</li>
 * </ul>
 */
public final class RestRouter {

    /** Returned for every TL method not (yet) routed: the caller must reject it. */
    public static final int ROUTE_NONE = -1;

    public static final int ROUTE_AUTH_SEND_CODE = 1;
    public static final int ROUTE_AUTH_VERIFY = 2; // signIn and signUp share one REST endpoint
    public static final int ROUTE_DIALOGS = 3;     // TL_messages_getDialogs -> GET /chats/list.php
    public static final int ROUTE_HISTORY = 4;     // TL_messages_getHistory -> GET /messages/history.php
    public static final int ROUTE_SEND = 5;        // TL_messages_sendMessage -> POST /messages/send.php
    public static final int ROUTE_READ = 6;        // TL_messages_readHistory -> POST /messages/read.php
    public static final int ROUTE_DELETE = 7;      // TL_messages_deleteMessages (revoke only) -> POST /messages/delete.php
    public static final int ROUTE_USERS_GET = 8;   // TL_users_getUsers -> GET /users/get.php
    public static final int ROUTE_STATE = 9;       // TL_updates_getState -> stub (sync cursor lives in UpdatePoller)
    public static final int ROUTE_DIFFERENCE = 10; // TL_updates_getDifference -> stub (UpdatePoller is the updates source)
    public static final int ROUTE_FILE_GET = 11;        // TL_upload_getFile -> GET /files/download.php (Range)
    public static final int ROUTE_FILE_PART = 12;       // TL_upload_saveFilePart -> lazy init + POST /files/chunk.php
    public static final int ROUTE_FILE_PART_BIG = 13;   // TL_upload_saveBigFilePart -> same (parts > 1 MB files)
    public static final int ROUTE_SEND_MEDIA = 14;      // TL_messages_sendMedia -> finalize + POST /messages/send.php

    // T32 — group lifecycle + avatars + profile (backend v1.5.0)
    public static final int ROUTE_CREATE_CHAT = 15;          // TL_messages_createChat -> POST /chats/create.php {group}
    public static final int ROUTE_ADD_CHAT_USER = 16;        // TL_messages_addChatUser -> POST /chats/add-member.php
    public static final int ROUTE_EDIT_CHAT_TITLE = 17;      // TL_messages_editChatTitle -> POST /chats/edit.php
    public static final int ROUTE_EDIT_CHAT_PHOTO = 18;      // TL_messages_editChatPhoto -> POST /chats/set-photo.php
    public static final int ROUTE_UPLOAD_PROFILE_PHOTO = 19; // TL_photos_uploadProfilePhoto -> POST /users/set-photo.php
    public static final int ROUTE_DELETE_PHOTOS = 20;        // TL_photos_deletePhotos -> POST /users/delete-photo.php
    public static final int ROUTE_UPDATE_PROFILE = 21;       // TL_account_updateProfile -> POST /users/edit.php

    // T33 — usernames + bio + @username deep links (backend v1.6.0)
    public static final int ROUTE_CHECK_USERNAME = 22;    // TL_account_checkUsername -> GET /users/username-check.php
    public static final int ROUTE_UPDATE_USERNAME = 23;   // TL_account_updateUsername -> POST /users/username-set.php
    public static final int ROUTE_RESOLVE_USERNAME = 24;  // TL_contacts_resolveUsername -> GET /users/resolve.php
    public static final int ROUTE_GET_FULL_USER = 25;     // TL_users_getFullUser -> GET /users/get.php (bio rides the json)

    // T35 — group full info + group about + contacts (backend v1.8.0)
    public static final int ROUTE_GET_FULL_CHAT = 26;     // TL_messages_getFullChat -> GET /chats/members.php (+chat payload)
    public static final int ROUTE_EDIT_CHAT_ABOUT = 27;   // TL_messages_editChatAbout -> POST /chats/edit.php {about}
    public static final int ROUTE_CONTACTS_GET = 28;      // TL_contacts_getContacts -> GET /contacts/list.php
    public static final int ROUTE_CONTACTS_IMPORT = 29;   // TL_contacts_importContacts -> POST /contacts/save.php (phone path)
    public static final int ROUTE_CONTACTS_ADD = 30;      // TL_contacts_addContact -> POST /contacts/save.php (user_id path)
    public static final int ROUTE_CONTACTS_DELETE = 31;   // TL_contacts_deleteContacts -> POST /contacts/delete.php
    public static final int ROUTE_EDIT_MESSAGE = 32;      // TL_messages_editMessage -> POST /messages/edit.php (text + captions)

    // T39 — edit pre-check + real dialog deletion (backend v2.1.0)
    public static final int ROUTE_EDIT_DATA = 33;         // TL_messages_getMessageEditData -> LOCAL stub (edit permission already gates client-side)
    public static final int ROUTE_DELETE_HISTORY = 34;    // TL_messages_deleteHistory -> POST /chats/delete-dialog.php (hidden_dialogs, for-me)

    // T40 — group member management: promote/kick/leave/delete (backend v2.2.0)
    public static final int ROUTE_DELETE_CHAT_USER = 35;  // TL_messages_deleteChatUser -> POST /chats/leave.php (self) | /chats/kick.php (other)
    public static final int ROUTE_EDIT_CHAT_ADMIN = 36;   // TL_messages_editChatAdmin -> POST /chats/promote.php (binary is_admin, creator-only)
    public static final int ROUTE_DELETE_CHAT = 37;       // TL_messages_deleteChat -> POST /chats/delete.php (creator, delete for everyone)

    // T42 — GIFs + avatar delete (backend v2.3.0)
    public static final int ROUTE_GET_SAVED_GIFS = 38;    // TL_messages_getSavedGifs -> GET /gifs/list.php (TL_messages_savedGifs{gifs:[Document]})
    public static final int ROUTE_SAVE_GIF = 39;          // TL_messages_saveGif -> POST /gifs/save.php {file_id, unsave} (TL_boolTrue)
    public static final int ROUTE_UPDATE_PROFILE_PHOTO = 40; // TL_photos_updateProfilePhoto -> POST /users/delete-photo.php {} (TL_inputPhotoEmpty = delete; TL_photos_photo)

    // constructor ints (TLRPC.java, this tree): TL_upload_getFile = 0xbe5335be,
    // TL_upload_saveFilePart = 0xb304a621, TL_upload_saveBigFilePart = 0xde7b673d,
    // TL_messages_sendMedia = 0x7852834e

    private static final HashMap<Class<?>, Integer> ROUTES = new HashMap<>();

    static {
        ROUTES.put(TLRPC.TL_auth_sendCode.class, ROUTE_AUTH_SEND_CODE);
        ROUTES.put(TLRPC.TL_auth_signIn.class, ROUTE_AUTH_VERIFY);
        ROUTES.put(TLRPC.TL_auth_signUp.class, ROUTE_AUTH_VERIFY);
        ROUTES.put(TLRPC.TL_messages_getDialogs.class, ROUTE_DIALOGS);
        ROUTES.put(TLRPC.TL_messages_getHistory.class, ROUTE_HISTORY);
        ROUTES.put(TLRPC.TL_messages_sendMessage.class, ROUTE_SEND);
        ROUTES.put(TLRPC.TL_messages_readHistory.class, ROUTE_READ);
        ROUTES.put(TLRPC.TL_messages_deleteMessages.class, ROUTE_DELETE);
        ROUTES.put(TLRPC.TL_users_getUsers.class, ROUTE_USERS_GET);
        ROUTES.put(TLRPC.TL_updates_getState.class, ROUTE_STATE);
        ROUTES.put(TLRPC.TL_updates_getDifference.class, ROUTE_DIFFERENCE);
        ROUTES.put(TLRPC.TL_upload_getFile.class, ROUTE_FILE_GET);
        ROUTES.put(TLRPC.TL_upload_saveFilePart.class, ROUTE_FILE_PART);
        ROUTES.put(TLRPC.TL_upload_saveBigFilePart.class, ROUTE_FILE_PART_BIG);
        ROUTES.put(TLRPC.TL_messages_sendMedia.class, ROUTE_SEND_MEDIA);
        // T32 — group lifecycle + avatars + profile
        ROUTES.put(TLRPC.TL_messages_createChat.class, ROUTE_CREATE_CHAT);
        ROUTES.put(TLRPC.TL_messages_addChatUser.class, ROUTE_ADD_CHAT_USER);
        ROUTES.put(TLRPC.TL_messages_editChatTitle.class, ROUTE_EDIT_CHAT_TITLE);
        ROUTES.put(TLRPC.TL_messages_editChatPhoto.class, ROUTE_EDIT_CHAT_PHOTO);
        ROUTES.put(TLRPC.TL_photos_uploadProfilePhoto.class, ROUTE_UPLOAD_PROFILE_PHOTO);
        ROUTES.put(TLRPC.TL_photos_deletePhotos.class, ROUTE_DELETE_PHOTOS);
        ROUTES.put(TLRPC.TL_account_updateProfile.class, ROUTE_UPDATE_PROFILE);
        // T33 — usernames + bio + deep links
        ROUTES.put(TLRPC.TL_account_checkUsername.class, ROUTE_CHECK_USERNAME);
        ROUTES.put(TLRPC.TL_account_updateUsername.class, ROUTE_UPDATE_USERNAME);
        ROUTES.put(TLRPC.TL_contacts_resolveUsername.class, ROUTE_RESOLVE_USERNAME);
        ROUTES.put(TLRPC.TL_users_getFullUser.class, ROUTE_GET_FULL_USER);
        // T35 — group full info + group about + contacts
        ROUTES.put(TLRPC.TL_messages_getFullChat.class, ROUTE_GET_FULL_CHAT);
        ROUTES.put(TLRPC.TL_messages_editChatAbout.class, ROUTE_EDIT_CHAT_ABOUT);
        ROUTES.put(TLRPC.TL_contacts_getContacts.class, ROUTE_CONTACTS_GET);
        ROUTES.put(TLRPC.TL_contacts_importContacts.class, ROUTE_CONTACTS_IMPORT);
        ROUTES.put(TLRPC.TL_contacts_addContact.class, ROUTE_CONTACTS_ADD);
        ROUTES.put(TLRPC.TL_contacts_deleteContacts.class, ROUTE_CONTACTS_DELETE);
        // T38/13 — message & caption editing (the "Can't edit this message" class:
        // the request class existed, the route did not — default-deny killed it)
        ROUTES.put(TLRPC.TL_messages_editMessage.class, ROUTE_EDIT_MESSAGE);
        // T39 — edit ENTRY pre-check: ChatActivity.startEditingMessageObject sends
        // TL_messages_getMessageEditData right after putting the text into the
        // input; default-deny answered XO_NOT_ROUTED within milliseconds and the
        // consumer (ChatActivity ~:30113, response == null branch) popped the
        // EditMessageError AlertDialog and exited edit mode — the "text flashes
        // into the input, then the old dialog" report. The edit-permission model
        // already gates locally (MessageObject.canEditMessage), so the answer is
        // a local stub: TL_messages_messageEditData{caption = false}.
        ROUTES.put(TLRPC.TL_messages_getMessageEditData.class, ROUTE_EDIT_DATA);
        // T39 — real dialog deletion: every "delete chat"/"clear history" sends
        // TL_messages_deleteHistory (MessagesController.deleteDialog ~:8783);
        // default-deny meant the backend NEVER learned about the deletion and
        // chats/list.php resurrected the dialog on the next load (the reported
        // "deleted chat comes back"). Routed to the new hidden_dialogs contract.
        ROUTES.put(TLRPC.TL_messages_deleteHistory.class, ROUTE_DELETE_HISTORY);
        // T40 — group member management. TL_messages_deleteChatUser is the
        // kick/leave funnel (deleteParticipantFromChat ~:13940/:14016),
        // TL_messages_editChatAdmin the basic-group promote/demote
        // (setUserAdminRole ~:7655), TL_messages_deleteChat the creator's
        // delete-for-everyone (~:13934/:14009). All three were default-denied
        // — every kick/leave/promote attempt in the UI died with
        // XO_NOT_ROUTED (kick silently, promote via the error alert).
        ROUTES.put(TLRPC.TL_messages_deleteChatUser.class, ROUTE_DELETE_CHAT_USER);
        ROUTES.put(TLRPC.TL_messages_editChatAdmin.class, ROUTE_EDIT_CHAT_ADMIN);
        ROUTES.put(TLRPC.TL_messages_deleteChat.class, ROUTE_DELETE_CHAT);
        // T42 — the saved-GIF collection. The GIFs tab always existed in the
        // emoji panel, but its server sync (MediaDataController.loadRecents
        // ~:1919 -> TL_messages_getSavedGifs) and the "Save to GIFs" action
        // (MessagesController.saveGif ~:8878 / MediaDataController
        // .removeRecentGif ~:1043 -> TL_messages_saveGif) were default-denied
        // — the panel only ever showed locally-cached recents and the server
        // never learned about a save. Routed to the v2.3.0 gifs endpoints.
        ROUTES.put(TLRPC.TL_messages_getSavedGifs.class, ROUTE_GET_SAVED_GIFS);
        ROUTES.put(TLRPC.TL_messages_saveGif.class, ROUTE_SAVE_GIF);
        // T42 — avatar DELETE of the CURRENT photo. ProfileActivity's delete
        // dialog / PhotoViewer's delete row funnel through
        // MessagesController.deleteUserPhoto(null) ~:7757 which sends
        // TL_photos_updateProfilePhoto{id: TL_inputPhotoEmpty}; default-deny
        // meant the backend never learned, the avatar resurrected on the next
        // users/get and other devices never saw the removal. (Set-as-main with
        // a real TL_inputPhoto is a no-op success on this single-avatar backend.)
        ROUTES.put(TLRPC.TL_photos_updateProfilePhoto.class, ROUTE_UPDATE_PROFILE_PHOTO);
    }

    private RestRouter() {
    }

    /** @return {@link #ROUTE_NONE} (= deny) for anything not yet routed. */
    public static int routeFor(TLObject object) {
        Integer route = ROUTES.get(object.getClass());
        return route == null ? ROUTE_NONE : route;
    }
}

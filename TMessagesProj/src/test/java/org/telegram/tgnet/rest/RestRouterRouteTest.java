package org.telegram.tgnet.rest;

import org.junit.BeforeClass;
import org.junit.Test;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.TLObject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T63 — client-local special/demo account mechanism + the default-deny
 * route table. The route pins are the single most important client
 * invariant: a routed class that silently stops being routed answers
 * XO_NOT_ROUTED at runtime (the T38/T39/T56 bug class).
 */
public class RestRouterRouteTest {

    @BeforeClass
    public static void setUp() {
        XoTestEnv.init();
    }

    @Test
    public void everyDocumentedRoute_isWired() {
        // auth (T4)
        assertEquals(RestRouter.ROUTE_AUTH_SEND_CODE, RestRouter.routeFor(new TLRPC.TL_auth_sendCode()));
        assertEquals(RestRouter.ROUTE_AUTH_VERIFY, RestRouter.routeFor(new TLRPC.TL_auth_signIn()));
        assertEquals(RestRouter.ROUTE_AUTH_VERIFY, RestRouter.routeFor(new TLRPC.TL_auth_signUp()));
        // core surface (T5)
        assertEquals(RestRouter.ROUTE_DIALOGS, RestRouter.routeFor(new TLRPC.TL_messages_getDialogs()));
        assertEquals(RestRouter.ROUTE_HISTORY, RestRouter.routeFor(new TLRPC.TL_messages_getHistory()));
        assertEquals(RestRouter.ROUTE_SEND, RestRouter.routeFor(new TLRPC.TL_messages_sendMessage()));
        assertEquals(RestRouter.ROUTE_READ, RestRouter.routeFor(new TLRPC.TL_messages_readHistory()));
        assertEquals(RestRouter.ROUTE_DELETE, RestRouter.routeFor(new TLRPC.TL_messages_deleteMessages()));
        assertEquals(RestRouter.ROUTE_USERS_GET, RestRouter.routeFor(new TLRPC.TL_users_getUsers()));
        assertEquals(RestRouter.ROUTE_STATE, RestRouter.routeFor(new TLRPC.TL_updates_getState()));
        assertEquals(RestRouter.ROUTE_DIFFERENCE, RestRouter.routeFor(new TLRPC.TL_updates_getDifference()));
        // files (T8)
        assertEquals(RestRouter.ROUTE_FILE_GET, RestRouter.routeFor(new TLRPC.TL_upload_getFile()));
        assertEquals(RestRouter.ROUTE_FILE_PART, RestRouter.routeFor(new TLRPC.TL_upload_saveFilePart()));
        assertEquals(RestRouter.ROUTE_FILE_PART_BIG, RestRouter.routeFor(new TLRPC.TL_upload_saveBigFilePart()));
        assertEquals(RestRouter.ROUTE_SEND_MEDIA, RestRouter.routeFor(new TLRPC.TL_messages_sendMedia()));
        // groups + profile (T32)
        assertEquals(RestRouter.ROUTE_CREATE_CHAT, RestRouter.routeFor(new TLRPC.TL_messages_createChat()));
        assertEquals(RestRouter.ROUTE_ADD_CHAT_USER, RestRouter.routeFor(new TLRPC.TL_messages_addChatUser()));
        assertEquals(RestRouter.ROUTE_EDIT_CHAT_TITLE, RestRouter.routeFor(new TLRPC.TL_messages_editChatTitle()));
        assertEquals(RestRouter.ROUTE_EDIT_CHAT_PHOTO, RestRouter.routeFor(new TLRPC.TL_messages_editChatPhoto()));
        assertEquals(RestRouter.ROUTE_UPDATE_PROFILE, RestRouter.routeFor(new TLRPC.TL_account_updateProfile()));
        // usernames (T33)
        assertEquals(RestRouter.ROUTE_CHECK_USERNAME, RestRouter.routeFor(new TLRPC.TL_account_checkUsername()));
        assertEquals(RestRouter.ROUTE_UPDATE_USERNAME, RestRouter.routeFor(new TLRPC.TL_account_updateUsername()));
        assertEquals(RestRouter.ROUTE_RESOLVE_USERNAME, RestRouter.routeFor(new TLRPC.TL_contacts_resolveUsername()));
        assertEquals(RestRouter.ROUTE_GET_FULL_USER, RestRouter.routeFor(new TLRPC.TL_users_getFullUser()));
        // group full info + contacts (T35)
        assertEquals(RestRouter.ROUTE_GET_FULL_CHAT, RestRouter.routeFor(new TLRPC.TL_messages_getFullChat()));
        assertEquals(RestRouter.ROUTE_CONTACTS_GET, RestRouter.routeFor(new TLRPC.TL_contacts_getContacts()));
        assertEquals(RestRouter.ROUTE_CONTACTS_IMPORT, RestRouter.routeFor(new TLRPC.TL_contacts_importContacts()));
        assertEquals(RestRouter.ROUTE_CONTACTS_ADD, RestRouter.routeFor(new TLRPC.TL_contacts_addContact()));
        assertEquals(RestRouter.ROUTE_CONTACTS_DELETE, RestRouter.routeFor(new TLRPC.TL_contacts_deleteContacts()));
        // edit + dialog delete (T39)
        assertEquals(RestRouter.ROUTE_EDIT_MESSAGE, RestRouter.routeFor(new TLRPC.TL_messages_editMessage()));
        assertEquals(RestRouter.ROUTE_EDIT_DATA, RestRouter.routeFor(new TLRPC.TL_messages_getMessageEditData()));
        assertEquals(RestRouter.ROUTE_DELETE_HISTORY, RestRouter.routeFor(new TLRPC.TL_messages_deleteHistory()));
        // member management (T40)
        assertEquals(RestRouter.ROUTE_DELETE_CHAT_USER, RestRouter.routeFor(new TLRPC.TL_messages_deleteChatUser()));
        assertEquals(RestRouter.ROUTE_EDIT_CHAT_ADMIN, RestRouter.routeFor(new TLRPC.TL_messages_editChatAdmin()));
        assertEquals(RestRouter.ROUTE_DELETE_CHAT, RestRouter.routeFor(new TLRPC.TL_messages_deleteChat()));
        // gifs + avatar delete (T42)
        assertEquals(RestRouter.ROUTE_GET_SAVED_GIFS, RestRouter.routeFor(new TLRPC.TL_messages_getSavedGifs()));
        assertEquals(RestRouter.ROUTE_SAVE_GIF, RestRouter.routeFor(new TLRPC.TL_messages_saveGif()));
        assertEquals(RestRouter.ROUTE_UPDATE_PROFILE_PHOTO, RestRouter.routeFor(new TLRPC.TL_photos_updateProfilePhoto()));
        // albums/forward/pin (T56)
        assertEquals(RestRouter.ROUTE_FORWARD, RestRouter.routeFor(new TLRPC.TL_messages_forwardMessages()));
        assertEquals(RestRouter.ROUTE_UPLOAD_MEDIA, RestRouter.routeFor(new TLRPC.TL_messages_uploadMedia()));
        assertEquals(RestRouter.ROUTE_SEND_MULTI_MEDIA, RestRouter.routeFor(new TLRPC.TL_messages_sendMultiMedia()));
        assertEquals(RestRouter.ROUTE_TOGGLE_DIALOG_PIN, RestRouter.routeFor(new TLRPC.TL_messages_toggleDialogPin()));
        assertEquals(RestRouter.ROUTE_REORDER_PINNED, RestRouter.routeFor(new TLRPC.TL_messages_reorderPinnedDialogs()));
        // privacy + blocked (T61)
        assertEquals(RestRouter.ROUTE_GET_PRIVACY, RestRouter.routeFor(new TLRPC.TL_account_getPrivacy()));
        assertEquals(RestRouter.ROUTE_SET_PRIVACY, RestRouter.routeFor(new TLRPC.TL_account_setPrivacy()));
        assertEquals(RestRouter.ROUTE_GET_BLOCKED, RestRouter.routeFor(new TLRPC.TL_contacts_getBlocked()));
        assertEquals(RestRouter.ROUTE_BLOCK_PEER, RestRouter.routeFor(new TLRPC.TL_contacts_block()));
        assertEquals(RestRouter.ROUTE_UNBLOCK_PEER, RestRouter.routeFor(new TLRPC.TL_contacts_unblock()));
    }

    @Test
    public void defaultDeny_unknownRequestsAnswerNone() {
        // unrouted TL request classes must be DENIED, never guessed
        assertEquals(RestRouter.ROUTE_NONE, RestRouter.routeFor(new TLRPC.TL_channels_createChannel()));
        assertEquals(RestRouter.ROUTE_NONE, RestRouter.routeFor(new TLRPC.TL_account_reportPeer()));
    }

    @Test
    public void routeTable_isCompleteAgainstItsOwnConstants() throws Exception {
        // every ROUTE_* constant must be reachable from the table — read the
        // private ROUTES map size via reflection and compare with the distinct
        // constants declared in RestRouter
        java.util.Map<?, ?> routes = (java.util.Map<?, ?>) XoTestEnv.readStaticField(RestRouter.class, "ROUTES");
        java.util.Set<Integer> distinct = new java.util.HashSet<>();
        for (Object v : routes.values()) {
            distinct.add((Integer) v);
        }
        assertFalse("route table must not be empty", routes.isEmpty());
        // the private ROUTES map carries exactly the declared route ints
        java.lang.reflect.Field[] fields = RestRouter.class.getDeclaredFields();
        java.util.Set<Integer> declared = new java.util.HashSet<>();
        for (java.lang.reflect.Field f : fields) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    && f.getType() == int.class && f.getName().startsWith("ROUTE_")
                    && !f.getName().equals("ROUTE_NONE")) {
                f.setAccessible(true);
                declared.add(f.getInt(null));
            }
        }
        assertEquals("every ROUTE_* constant must map at least one request class",
                declared, distinct);
    }
}

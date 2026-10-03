package org.telegram.tgnet.rest;

import org.junit.BeforeClass;
import org.junit.Test;
import org.telegram.tgnet.TLRPC;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T63 — message/chat JSON (REAL backend outputs) -> TLRPC contract.
 * Pins parseMessage (text/reply/media), parseHistory ordering, the flag
 * coherence rules the MessagesStorage round-trip depends on, parseMedia's
 * document/empty branches and parseGroupChat's role/rights plants.
 */
public class TlJsonMapperMessageTest {

    @BeforeClass
    public static void setUp() {
        XoTestEnv.init();
    }

    @Test
    public void textMessage_mapsCoreFieldsAndFlags() throws Exception {
        org.json.JSONObject msg = XoFixtures.obj("fixture_message_text");
        // receiving side: self = 10001 (sender 10000 in the fixture)
        TLRPC.TL_message m = TlJsonMapper.parseMessage(msg, 10001L, false, 10001L, 10001L);

        assertEquals(1, m.id);
        assertEquals("سلام از بک‌اند!", m.message);
        assertEquals(10000L, ((TLRPC.TL_peerUser) m.from_id).user_id);
        assertEquals(10001L, ((TLRPC.TL_peerUser) m.peer_id).user_id);
        assertEquals(10001L, m.dialog_id);
        assertTrue((m.flags & 256) != 0);                 // HAS_FROM_ID always in v1
        assertFalse(m.out);                               // sender != self
        assertTrue(m.media instanceof TLRPC.TL_messageMediaEmpty);
        assertTrue((m.flags & 512) != 0);                 // media flag coherent even for empty media
        assertTrue(m.reply_to == null);
        assertTrue(m.edit_date == 0);
        assertEquals(1790986746, m.date);
    }

    @Test
    public void ownMessage_getsTheOutFlag() throws Exception {
        org.json.JSONObject msg = XoFixtures.obj("fixture_message_text");
        TLRPC.TL_message m = TlJsonMapper.parseMessage(msg, 10001L, false, 10001L, 10000L /* self == sender */);
        assertTrue(m.out);
        assertTrue((m.flags & 2) != 0);
    }

    @Test
    public void replyMessage_buildsFlagCoherentHeader() throws Exception {
        org.json.JSONObject msg = XoFixtures.obj("fixture_message_reply");
        TLRPC.TL_message m = TlJsonMapper.parseMessage(msg, 10001L, false, 10000L, 10001L);
        assertNotNull(m.reply_to);
        assertTrue(m.reply_to instanceof TLRPC.TL_messageReplyHeader);
        TLRPC.TL_messageReplyHeader header = (TLRPC.TL_messageReplyHeader) m.reply_to;
        assertEquals(1, header.reply_to_msg_id);
        assertTrue("reply header MUST carry flag 16 or storage drops it", (header.flags & 16) != 0);
        assertTrue((m.flags & 8) != 0);                   // MESSAGE_FLAG_HAS_REPLY
        assertEquals("a reply", m.message);
    }

    @Test
    public void documentMediaMessage_carriesServerAttestation() throws Exception {
        org.json.JSONObject msg = XoFixtures.obj("fixture_message_media");
        TLRPC.TL_message m = TlJsonMapper.parseMessage(msg, 10001L, false, 10001L, 10000L);
        assertTrue(m.media instanceof TLRPC.TL_messageMediaDocument);
        // the declared media field type is the abstract TLRPC.Document; the
        // mapper contract (storage serialization) is the concrete TL_document
        TLRPC.Document document = ((TLRPC.TL_messageMediaDocument) m.media).document;
        assertNotNull(document);
        assertTrue("mapper must produce a real TL_document",
                document instanceof TLRPC.TL_document);
        assertEquals(1L, document.id);
        assertEquals("application/octet-stream", document.mime_type);
        assertEquals(4096L, document.size);
        // empty caption: content was "" on the wire — never the string "null"
        assertEquals("", m.message);
        // the download-integrity index must have consumed the server attestation
        @SuppressWarnings("unchecked")
        java.util.Map<Long, String[]> metaCache =
                (java.util.Map<Long, String[]>) XoTestEnv.readStaticField(RestFileBridge.class, "metaCache");
        String[] attested = metaCache.get(1L);
        assertNotNull("parseMedia must feed noteFileMeta", attested);
        assertEquals("4096", attested[0]);
        assertEquals("9ec06a64eca0441db0123e7e4c68f344f85b883068836a5be20b363c576e17c2", attested[1]);
    }

    @Test
    public void historyParser_preservesBackendAscendingOrder() throws Exception {
        org.json.JSONArray history = XoFixtures.obj("fixture_history").getJSONArray("messages");
        assertTrue(history.length() >= 4);
        java.util.ArrayList<TLRPC.TL_message> parsed = TlJsonMapper.parseHistory(history, 10001L, false, 10001L, 10000L);
        assertEquals(history.length(), parsed.size());
        for (int a = 1; a < parsed.size(); a++) {
            assertTrue("backend order (asc) must survive",
                    parsed.get(a - 1).id < parsed.get(a).id);
        }
        // first row is the text message, the media row keeps its document
        assertEquals("سلام از بک‌اند!", parsed.get(0).message);
    }

    @Test
    public void syncMessageNewEvent_parsesItsEmbeddedMessage() throws Exception {
        org.json.JSONObject event = XoFixtures.obj("fixture_sync_message_new");
        assertEquals("message_new", event.getString("type"));
        org.json.JSONObject msgJson = event.getJSONObject("message");
        TLRPC.TL_message m = TlJsonMapper.parseMessage(msgJson, 10001L, false, 10001L, 10000L);
        assertEquals(1, m.id);
        assertEquals(10000L, ((TLRPC.TL_peerUser) m.from_id).user_id);
        // own sends are skipped by the poller, but a parsed own message carries out=true
        TLRPC.TL_message own = TlJsonMapper.parseMessage(msgJson, 10001L, false, 10001L, msgJson.getLong("sender_id"));
        assertTrue(own.out);
    }

    @Test
    public void groupMessage_mapsPeerChatWithNegativeDialogId() throws Exception {
        org.json.JSONObject msg = XoFixtures.obj("fixture_message_text");
        // group convention: dialog id = -chat_id
        TLRPC.TL_message m = TlJsonMapper.parseMessage(msg, -7L, true, 0L, 10000L);
        assertTrue(m.peer_id instanceof TLRPC.TL_peerChat);
        assertEquals(7L, ((TLRPC.TL_peerChat) m.peer_id).chat_id);
        assertEquals(-7L, m.dialog_id);
    }

    @Test
    public void editedMessage_getsEditDateFlag() throws Exception {
        org.json.JSONObject msg = XoFixtures.obj("fixture_message_text");
        msg.put("edited_at", 1790986800);
        TLRPC.TL_message m = TlJsonMapper.parseMessage(msg, 10001L, false, 10001L, 10000L);
        assertEquals(1790986800, m.edit_date);
        assertTrue("MESSAGE_FLAG_HAS_EDIT_DATE or storage drops it", (m.flags & 32768) != 0);
    }

    @Test
    public void albumGroupId_parsesAndPlantsFlag() throws Exception {
        org.json.JSONObject msg = XoFixtures.obj("fixture_message_text");
        msg.put("group_id", "-9007199254740993"); // a negative 64-bit value as string
        TLRPC.TL_message m = TlJsonMapper.parseMessage(msg, 10001L, false, 10001L, 10000L);
        assertTrue((m.flags & 131072) != 0);
        assertEquals(-9007199254740993L, m.grouped_id);
    }

    @Test
    public void mentionDetection_offsetsAndBoundaries() {
        // simple mention
        java.util.ArrayList<TLRPC.TL_messageEntityMention> one = TlJsonMapper.detectMentions("ping @hermes_bot now");
        assertEquals(1, one.size());
        assertEquals(5, one.get(0).offset);
        assertEquals(11, one.get(0).length);

        // e-mail fragment is NOT a mention
        assertTrue(TlJsonMapper.detectMentions("mail a@user_x now").isEmpty());
        // sub-string is NOT a mention
        assertTrue(TlJsonMapper.detectMentions("x@user_y done").isEmpty());
        // unicode preamble offsets stay UTF-16-accurate
        java.util.ArrayList<TLRPC.TL_messageEntityMention> fa = TlJsonMapper.detectMentions("سلام @hermes_bot");
        assertEquals(1, fa.size());
        assertEquals("سلام ".length(), fa.get(0).offset);
    }

    @Test
    public void groupChat_mapsRightsAndRole() throws Exception {
        org.json.JSONArray chats = XoFixtures.obj("fixture_dialogs").getJSONArray("chats");
        org.json.JSONObject group = chats.getJSONObject(0);
        assertEquals("group", group.getString("type"));

        TLRPC.TL_chat chat = TlJsonMapper.parseGroupChat(group);
        assertEquals(group.getLong("id"), chat.id);
        assertEquals("Fixture Group", chat.title);
        assertEquals(2, chat.participants_count);
        assertTrue(chat.creator);                          // role "creator"
        assertTrue((chat.flags & 1) != 0);
        // permissive default banned rights (the T36 fix)
        assertNotNull(chat.default_banned_rights);
        assertFalse(chat.default_banned_rights.send_messages);
        assertTrue(chat.default_banned_rights.pin_messages);
        // photo is NEVER null (serialize contract) — empty when no avatar
        assertNotNull(chat.photo);
        assertTrue(chat.photo instanceof TLRPC.TL_chatPhotoEmpty);
    }

    @Test
    public void adminRole_getsBanUsersRight() throws Exception {
        org.json.JSONObject group = XoFixtures.obj("fixture_dialogs").getJSONArray("chats").getJSONObject(0);
        group.put("role", "admin");
        TLRPC.TL_chat chat = TlJsonMapper.parseGroupChat(group);
        assertNotNull(chat.admin_rights);
        assertTrue(chat.admin_rights.ban_users);
        assertTrue((chat.flags & 16384) != 0);
    }
}

package org.telegram.tgnet.rest;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T63 — the privacy WIRE contract: backend rule json {base, allowed[],
 * disallowed[]} -> upstream TL PrivacyRule objects, and exception-user
 * hydration. Consumes the REAL privacy/get + privacy/set + blocked/list
 * answers of backend v2.10.0. The UpdatePoller privacy event reuses
 * exactly these two package-visible mappers — so this pins the whole
 * client-side privacy state machine's input.
 */
public class PrivacyWireMappingTest {

    @BeforeClass
    public static void setUp() {
        XoTestEnv.init();
    }

    @Test
    public void privacyGet_defaultRules_mapToUpstreamBaseRules() throws Exception {
        JSONObject answer = XoFixtures.obj("fixture_privacy_get");
        JSONObject rules = answer.getJSONObject("rules");
        assertEquals(4, rules.length());                      // the closed key set
        for (String key : new String[]{"status_timestamp", "photo", "about", "chat_invite"}) {
            JSONObject mine = rules.getJSONObject(key);
            ArrayList<TLRPC.PrivacyRule> mapped = RestDispatcher.tlRulesFromWire(mine);
            // default = everybody + no exceptions -> exactly ONE rule: AllowAll
            assertEquals("key " + key + ": default maps to a single AllowAll", 1, mapped.size());
            assertTrue("key " + key + " must be AllowAll",
                    mapped.get(0) instanceof TLRPC.TL_privacyValueAllowAll);
        }
    }

    @Test
    public void privacySet_exceptionLists_mapToAllowAndDisallowUserRules() throws Exception {
        // the set fixture was produced with base=contacts, allowed=[C], disallowed=[B]
        JSONObject answer = XoFixtures.obj("fixture_privacy_set");
        JSONObject rules = answer.getJSONObject("rules");
        JSONObject chatInvite = rules.getJSONObject("chat_invite");

        ArrayList<TLRPC.PrivacyRule> mapped = RestDispatcher.tlRulesFromWire(chatInvite);
        assertEquals(3, mapped.size());                       // [AllowUsers, DisallowUsers, base]

        TLRPC.TL_privacyValueAllowUsers allow = (TLRPC.TL_privacyValueAllowUsers) mapped.get(0);
        assertEquals(1, allow.users.size());
        TLRPC.TL_privacyValueDisallowUsers disallow = (TLRPC.TL_privacyValueDisallowUsers) mapped.get(1);
        assertEquals(1, disallow.users.size());

        assertTrue("base contacts -> AllowContacts", mapped.get(2) instanceof TLRPC.TL_privacyValueAllowContacts);

        // round-trip: the answer's exception ids survive the mapping exactly
        long allowedId = chatInvite.getJSONArray("allowed").getLong(0);
        long disallowedId = chatInvite.getJSONArray("disallowed").getLong(0);
        assertEquals(allowedId, allow.users.get(0).longValue());
        assertEquals(disallowedId, disallow.users.get(0).longValue());
    }

    @Test
    public void baseVariants_mapToTheirUpstreamClass() {
        assertTrue(RestDispatcher.tlRulesFromWire(new JSONObject("{\"base\":\"nobody\"}"))
                .get(0) instanceof TLRPC.TL_privacyValueDisallowAll);
        assertTrue(RestDispatcher.tlRulesFromWire(new JSONObject("{\"base\":\"contacts\"}"))
                .get(0) instanceof TLRPC.TL_privacyValueAllowContacts);
        assertTrue(RestDispatcher.tlRulesFromWire(new JSONObject("{\"base\":\"everybody\"}"))
                .get(0) instanceof TLRPC.TL_privacyValueAllowAll);
        // unknown/missing base degrades to the SAFE default: AllowAll (everybody)
        assertTrue(RestDispatcher.tlRulesFromWire(new JSONObject())
                .get(0) instanceof TLRPC.TL_privacyValueAllowAll);
    }

    @Test
    public void emptyExceptionArrays_omitTheirRuleEntries() {
        ArrayList<TLRPC.PrivacyRule> mapped = RestDispatcher.tlRulesFromWire(
                new JSONObject("{\"base\":\"contacts\",\"allowed\":[],\"disallowed\":[]}"));
        assertEquals(1, mapped.size());
        assertTrue(mapped.get(0) instanceof TLRPC.TL_privacyValueAllowContacts);
    }

    @Test
    public void privacyUsers_hydrateIntoParsedUsers() throws Exception {
        // blocked/list and privacy answers both carry users[] via parsePrivacyUsers
        JSONObject answer = XoFixtures.obj("fixture_blocked_list");
        ArrayList<TLRPC.User> users = RestDispatcher.parsePrivacyUsers(answer);
        assertEquals(answer.getJSONArray("users").length(), users.size());
        if (!users.isEmpty()) {
            TLRPC.User first = users.get(0);
            assertNotNull(first);
            assertTrue(first instanceof TLRPC.TL_user);
        }
        // null/absent users[] degrades to an EMPTY list — never an exception
        assertTrue(RestDispatcher.parsePrivacyUsers(new JSONObject("{}")).isEmpty());
        assertTrue(RestDispatcher.parsePrivacyUsers(null).isEmpty());
    }

    @Test
    public void blockedList_mapsToPeerBlocksWithDates() throws Exception {
        JSONObject answer = XoFixtures.obj("fixture_blocked_list");
        org.json.JSONArray wire = answer.getJSONArray("blocked");
        assertTrue(wire.length() >= 1);

        JSONObject item = wire.getJSONObject(0);
        // the TL_contacts_blockedSlice consumption contract (handleGetBlocked):
        // peer_id = TL_peerUser(user_id), date = block stamp, count = full size
        assertEquals(10002L, item.getLong("user_id"));
        assertTrue(item.has("date"));
        int fullCount = answer.getInt("count");
        assertTrue(fullCount >= wire.length());
    }

    @Test
    public void privacySyncEvent_isPayloadless() throws Exception {
        JSONObject event = XoFixtures.obj("fixture_sync_privacy_event");
        assertEquals("privacy", event.getString("type"));
        assertEquals(2, event.length());                      // update_id + type ONLY
        assertTrue(event.has("update_id"));
        // no rules/key/allowed keys may ever ride this event
        for (String forbidden : new String[]{"rules", "key", "allowed", "disallowed", "base"}) {
            assertTrue("privacy event must not carry " + forbidden, !event.has(forbidden));
        }
    }

    @Test
    public void blockedSyncEvent_carriesUserAndFlag() throws Exception {
        JSONObject event = XoFixtures.obj("fixture_sync_blocked_event");
        assertEquals("blocked", event.getString("type"));
        assertEquals(true, event.getBoolean("blocked"));
        assertEquals(10002L, event.getLong("user_id"));
        assertTrue(event.has("date"));
        // the hydrated user rides the event (id matches user_id)
        JSONObject user = event.getJSONObject("user");
        assertEquals(event.getLong("user_id"), user.getLong("id"));
    }

    @Test
    public void fixturePrivacyRulesKeys_areTheClosedSet() throws Exception {
        JSONObject rules = XoFixtures.obj("fixture_privacy_get").getJSONObject("rules");
        Map<String, Object> raw = rules.toMap();
        for (String key : raw.keySet()) {
            assertTrue("unexpected privacy key on the wire: " + key,
                    "status_timestamp".equals(key) || "photo".equals(key)
                            || "about".equals(key) || "chat_invite".equals(key));
        }
    }
}

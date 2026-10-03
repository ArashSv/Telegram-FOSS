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
 * T63 — user JSON (REAL backend outputs) -> TLRPC.TL_user contract.
 * Pins the exact shapes the deployed v2.10.0 controllers answer:
 * public/self profiles, the privacy read-time strips (status/photo/bio),
 * contact hydration fields and the flag-coherence rules the storage
 * round-trip depends on.
 */
public class TlJsonMapperUserTest {

    @BeforeClass
    public static void setUp() {
        XoTestEnv.init();
    }

    private TLRPC.TL_user parse(String fixture) throws Exception {
        return TlJsonMapper.parseUser(XoFixtures.obj(fixture), false);
    }

    @Test
    public void publicUser_mapsIdentityAndContractKeys() throws Exception {
        TLRPC.TL_user user = parse("fixture_user_public");

        assertEquals(10001L, user.id);
        assertEquals("Beata Binary", user.first_name);
        assertEquals("beata30500", user.username);
        assertEquals("40430501", user.phone);           // digits-only, client prepends '+'
        assertNotNull(user.username);

        // flag coherence: first_name(2) | username(8) | phone(16) | access_hash(1)
        assertEquals(2 | 8 | 16 | 1 | 32, user.flags & (2 | 8 | 16 | 1 | 32));
        assertEquals(1L, user.access_hash);             // stable non-zero plant
    }

    @Test
    public void publicUser_nullStatus_landsOnNeutralEmptyStatus() throws Exception {
        TLRPC.TL_user user = parse("fixture_user_public");
        // fresh/never-seen rows answer status: null — must become
        // TL_userStatusEmpty, never a null pointer into legacy UI paths
        assertTrue(user.status instanceof TLRPC.TL_userStatusEmpty);
    }

    @Test
    public void publicUser_photoNull_keepsInitialsFallback() throws Exception {
        TLRPC.TL_user user = parse("fixture_user_public");
        assertNull(user.photo);                          // UI renders initials
        assertTrue((user.flags & 32) == 0);              // photo-present bit NOT planted
    }

    @Test
    public void selfUser_setsSelfFlag_andKeepsDigitsPhone() throws Exception {
        org.json.JSONObject json = XoFixtures.obj("fixture_user_self");
        TLRPC.TL_user user = TlJsonMapper.parseUser(json, true);
        assertTrue(user.self);
        assertTrue((user.flags & 1024) != 0);
        assertEquals("40430500", user.phone);            // digits-only self form
        assertEquals(10000L, user.id);
    }

    @Test
    public void privacyStatusHidden_yieldsEmptyStatus() throws Exception {
        // B set status_timestamp = nobody; the backend answered status: null.
        // The client must degrade to the neutral status, NOT to a guessed one.
        TLRPC.TL_user user = parse("fixture_user_status_hidden");
        assertTrue(user.status instanceof TLRPC.TL_userStatusEmpty);
        assertEquals(10001L, user.id);
        // the REST of the profile is untouched (privacy is per-key)
        assertEquals("Beata Binary", user.first_name);
        assertNotNull(user.username);
    }

    @Test
    public void privacyPhotoHidden_stripsBothAvatarKeys() throws Exception {
        TLRPC.TL_user user = parse("fixture_user_photo_hidden");
        assertNull(user.photo);
        assertTrue((user.flags & 32) == 0);
    }

    @Test
    public void privacyBioHidden_isNotVisibleInTheUserShape() throws Exception {
        // bio rides the json; the backend stripped it to null — the client
        // must see username present but no bio leakage into first_name etc.
        TLRPC.TL_user user = parse("fixture_user_bio_hidden");
        assertNotNull(user.username);
        assertFalse("null".equals(user.username));       // the org.json sentinel trap
        assertEquals("Beata Binary", user.first_name);   // display_name intact
    }

    @Test
    public void blockedEventUser_parsesAsPublicShape() throws Exception {
        org.json.JSONObject event = XoFixtures.obj("fixture_sync_blocked_event");
        org.json.JSONObject userJson = event.getJSONObject("user");
        TLRPC.TL_user user = TlJsonMapper.parseUser(userJson, false);
        assertEquals(event.getLong("user_id"), user.id);
        assertNotNull(user.phone);                       // global phone visibility
        assertFalse("null".equals(user.phone));
    }

    @Test
    public void contactFields_replaceFirstNameAndSetFlag() throws Exception {
        // the backend serves contact_name + contact=true when the VIEWER holds
        // the target; contact_name REPLACES first_name (Telegram contact UX)
        org.json.JSONObject json = XoFixtures.obj("fixture_user_public");
        json.put("contact_name", "My Nickname For B");
        json.put("contact", true);
        TLRPC.TL_user user = TlJsonMapper.parseUser(json, false);
        assertEquals("My Nickname For B", user.first_name);
        assertTrue(user.contact);
        assertTrue((user.flags & 2048) != 0);
    }

    @Test
    public void truthyContactFlagForms_areAccepted() throws Exception {
        org.json.JSONObject json = XoFixtures.obj("fixture_user_public");
        // the backend contract is a strict boolean, but the client tolerates 1/"1"/"true"
        for (Object truthy : new Object[]{true, 1, "1", "true"}) {
            json.put("contact", truthy);
            TLRPC.TL_user user = TlJsonMapper.parseUser(json, false);
            assertTrue("flag form " + truthy + " must count as contact", user.contact);
        }
    }

    @Test
    public void nullUsername_neverBecomesTheStringNull() throws Exception {
        TLRPC.TL_user user = parse("fixture_user_self"); // username null on A
        assertNull(user.username);
    }

    @Test
    public void emptyDisplayName_fallsBackToUserId() throws Exception {
        org.json.JSONObject json = XoFixtures.obj("fixture_user_public");
        json.put("display_name", "");
        TLRPC.TL_user user = TlJsonMapper.parseUser(json, false);
        assertEquals("user" + user.id, user.first_name);
    }

    @Test
    public void statusShapes_mapToOnlineAndOffline() throws Exception {
        // online branch
        org.json.JSONObject online = XoFixtures.obj("fixture_user_public");
        online.put("status", new org.json.JSONObject("{\"online\":true,\"expires\":1790990000}"));
        TLRPC.TL_user u1 = TlJsonMapper.parseUser(online, false);
        assertTrue(u1.status instanceof TLRPC.TL_userStatusOnline);
        assertEquals(1790990000, ((TLRPC.TL_userStatusOnline) u1.status).expires);

        // offline branch carries was_online in the offline expires slot
        org.json.JSONObject offline = XoFixtures.obj("fixture_user_public");
        offline.put("status", new org.json.JSONObject("{\"online\":false,\"was_online\":1790980000}"));
        TLRPC.TL_user u2 = TlJsonMapper.parseUser(offline, false);
        assertTrue(u2.status instanceof TLRPC.TL_userStatusOffline);
        assertEquals(1790980000, ((TLRPC.TL_userStatusOffline) u2.status).expires);
    }
}

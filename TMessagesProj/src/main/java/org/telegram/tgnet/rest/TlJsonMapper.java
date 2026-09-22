package org.telegram.tgnet.rest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
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
}

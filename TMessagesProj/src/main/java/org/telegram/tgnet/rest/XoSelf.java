package org.telegram.tgnet.rest;

import android.os.Looper;
import android.util.SparseArray;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * T34: the ONE funnel through which a user json in SELF context enters the
 * client. v1.6's field bugs all shared a single architectural flaw: mutation
 * responses (username set, profile edit, avatar set, full-user loads) were
 * parsed as PUBLIC users and then putUser'd, and MessagesController.putUser
 * replaces the whole self user (UserConfig.setCurrentUser) — so the self user
 * lost its phone (drawer "+null") and its self flag (settings page rendered
 * like a foreign profile, edit surfaces vanished, my-profile crashed).
 *
 * <p>XoSelf makes degradation structurally impossible:
 *
 * <ul>
 *   <li><b>Merge, never replace.</b> A key PRESENT in the json is
 *       authoritative (an explicit null CLEARS the field — username/photo
 *       clears flow through); a key ABSENT keeps the current value. Backend
 *       v1.7 mutation responses are self-shaped (phone included), so every
 *       merge carries the complete identity.</li>
 *   <li><b>Self flag is forced</b> — the result always answers
 *       {@code UserObject.isUserSelf(user) == true}.</li>
 *   <li><b>Flags are rebuilt coherently</b> from the fields actually set, so
 *       the MessagesStorage round-trip preserves exactly what we hold.</li>
 *   <li><b>Applies through the canonical paths</b> (MessagesController cache +
 *       UserConfig + storage + interface notifications) on the UI thread,
 *       wherever the caller was running.</li>
 * </ul>
 *
 * <p>Consumers: every RestDispatcher handler that answers a self-context
 * request (updateProfile / updateUsername / uploadProfilePhoto /
 * resolveUsername(self) / getFullUser(self)) and UpdatePoller's own
 * {@code user_updated} pushes. Callers on any thread; the apply is posted.
 */
public final class XoSelf {

    private XoSelf() {
    }

    /**
     * Merge a backend user json (public or self shape) into the current self
     * user and apply it. Returns the merged TL_user synchronously (safe to
     * hand to callers that putUser the response again — re-applying the same
     * whole object is a no-op), or null when the json was not usable.
     */
    public static TLRPC.TL_user mergeApply(int account, JSONObject userJson) {
        if (userJson == null) {
            return null;
        }
        TLRPC.TL_user incoming;
        try {
            incoming = TlJsonMapper.parseUser(userJson, false);
        } catch (Exception e) {
            FileLog.e("XoSelf: self-context user json unusable", e);
            return null;
        }
        return mergeApplyInternal(account, incoming, userJson);
    }

    /**
     * Merge from an already-parsed user; the raw json is still required for
     * key-presence (explicit-null) decisions. Package-private for the
     * dispatcher's parse-then-branch paths.
     */
    static TLRPC.TL_user mergeApplyInternal(int account, TLRPC.TL_user incoming, JSONObject userJson) {
        UserConfig userConfig = UserConfig.getInstance(account);
        TLRPC.User current = userConfig.getCurrentUser();
        TLRPC.TL_user merged = buildMerged(current, incoming, userJson);
        apply(account, merged);
        return merged;
    }

    /**
     * Pure merge: current user + parsed incoming + key-presence map → the
     * self user the client should hold. No side effects.
     */
    private static TLRPC.TL_user buildMerged(TLRPC.User current, TLRPC.TL_user incoming, JSONObject userJson) {
        TLRPC.TL_user merged = new TLRPC.TL_user();
        // identity always comes from the payload (it IS the user being described)
        merged.id = incoming.id;
        if (current != null && current.id == merged.id) {
            // carry over state the REST contract does not model
            merged.premium = current.premium;
            merged.bot = current.bot;
            merged.verified = current.verified;
            merged.contact = current.contact;
            merged.mutual_contact = current.mutual_contact;
            merged.min = current.min;
            merged.usernames = current.usernames;
            merged.emoji_status = current.emoji_status;
            if (incomingStatusIsPlaceholder(current, incoming)) {
                merged.status = current.status;
            } else {
                merged.status = incoming.status;
            }
        } else {
            merged.status = incoming.status;
        }

        // --- authoritative fields from the json -----------------------------
        if (incomingHasDisplayName(userJson)) {
            // v1.7 identity model: the backend stores exactly ONE name. A
            // display_name fully (re)defines the name pair — keeping a stale
            // local last_name would render "Ali Rezaei Rezaei".
            merged.first_name = incoming.first_name;
            merged.last_name = null;
        } else if (current != null && current.id == merged.id) {
            merged.first_name = current.first_name;
            merged.last_name = current.last_name;
        } else {
            merged.first_name = incoming.first_name;
        }

        if (userJson.has("username")) {
            // explicit authoritative set/clear
            Object usernameObj = userJson.opt("username");
            if (usernameObj instanceof String && ((String) usernameObj).length() > 0) {
                merged.username = (String) usernameObj;
            } else {
                merged.username = null;
            }
        } else if (current != null && current.id == merged.id) {
            merged.username = current.username;
        } else {
            merged.username = incoming.username;
        }

        if (userJson.has("phone")) {
            String phone = userJson.optString("phone", "");
            merged.phone = phone.length() > 0 ? phone : null;
        } else if (current != null && current.id == merged.id) {
            merged.phone = current.phone;
        } // else: a foreign-shaped payload must never invent a phone

        if (userJson.has("photo")) {
            JSONObject photoJson = userJson.optJSONObject("photo");
            TLRPC.TL_userProfilePhoto photo = TlJsonMapper.parseUserProfilePhoto(photoJson);
            merged.photo = photo; // null ⇒ explicit clear (delete-photo flow)
        } else if (current != null && current.id == merged.id) {
            merged.photo = current.photo;
        } else {
            merged.photo = incoming.photo;
        }

        // --- self flag + coherent serialization flags -----------------------
        merged.self = true;
        merged.access_hash = incoming.access_hash != 0 ? incoming.access_hash : 1;
        merged.flags = 1 | 1024; // access_hash + self
        if (merged.first_name != null && merged.first_name.length() > 0) {
            merged.flags |= 2;
        }
        if (merged.last_name != null && merged.last_name.length() > 0) {
            merged.flags |= 4;
        }
        if (merged.username != null && merged.username.length() > 0) {
            merged.flags |= 8;
        }
        if (merged.phone != null && merged.phone.length() > 0) {
            merged.flags |= 16;
        }
        if (merged.photo != null) {
            merged.flags |= 32;
        }
        if (merged.status != null) {
            merged.flags |= 64; // status present
        }
        return merged;
    }

    /** display_name key present and usable (backend always sends it non-empty). */
    private static boolean incomingHasDisplayName(JSONObject userJson) {
        Object name = userJson.opt("display_name");
        return name instanceof String && ((String) name).length() > 0;
    }

    /**
     * parseUser plants a TL_userStatusEmpty when the json carries none; if the
     * current user holds a real status, prefer it over a fresh placeholder.
     */
    private static boolean incomingStatusIsPlaceholder(TLRPC.User current, TLRPC.TL_user incoming) {
        return current != null
                && current.status != null
                && !(current.status instanceof TLRPC.TL_userStatusEmpty)
                && incoming.status instanceof TLRPC.TL_userStatusEmpty;
    }

    /**
     * Apply a whole self user through the canonical paths. UI thread.
     * putUser(merged, false) refreshes the cache + objectsByUsernames and
     * calls setCurrentUser for the self id; we then re-assert the config so a
     * cache miss still lands (putUser only replaces when the ids match).
     */
    private static void apply(final int account, final TLRPC.TL_user merged) {
        Runnable apply = () -> {
            try {
                ArrayList<TLRPC.User> single = new ArrayList<>();
                single.add(merged);
                MessagesController.getInstance(account).putUsers(single, false);
                UserConfig.getInstance(account).setCurrentUser(merged);
                UserConfig.getInstance(account).saveConfig(true);
                MessagesStorage.getInstance(account).putUsersAndChats(single, null, false, true);
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.mainUserInfoChanged);
                NotificationCenter.getInstance(account).postNotificationName(
                        NotificationCenter.updateInterfaces,
                        MessagesController.UPDATE_MASK_NAME | MessagesController.UPDATE_MASK_AVATAR);
            } catch (Exception e) {
                FileLog.e("XoSelf: apply failed", e);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply.run();
        } else {
            AndroidUtilities.runOnUIThread(apply);
        }
    }

    /**
     * Upgrade-path self-heal (called once per process on the first successful
     * poll): a self user that lost its self flag or phone (v1.6 corrupted
     * state, persisted across restarts by the very replacement that caused
     * it) is repaired from auth/me.php — the one endpoint that always answers
     * the complete self shape. Cheap, idempotent, no-op when healthy.
     *
     * @return true when a repair round-trip was issued
     */
    public static boolean ensureFresh(int account) {
        try {
            TLRPC.User current = UserConfig.getInstance(account).getCurrentUser();
            boolean degraded = current == null
                    || !current.self
                    || current.phone == null
                    || current.phone.length() == 0;
            if (!degraded) {
                return false;
            }
            FileLog.w("XoSelf: self user degraded (self=" + (current != null && current.self)
                    + ", phone=" + (current != null ? current.phone : "null") + ") — repairing from auth/me");
            new Thread(() -> {
                try {
                    TLRPC.TL_user me = RestGateway.getInstance(account).me();
                    if (me != null) {
                        // me() already parsed self-shaped; re-merge to reuse the apply path
                        apply(account, me);
                    }
                } catch (Exception e) {
                    FileLog.e("XoSelf: auth/me repair failed (retry on next poll cycle)", e);
                }
            }, "XoSelf-heal-" + account).start();
            return true;
        } catch (Exception e) {
            FileLog.e("XoSelf: ensureFresh failed", e);
            return false;
        }
    }

    /** Small helper so callers can key one-shot behavior per account. */
    public static final class Once {
        private static final SparseArray<Boolean> fired = new SparseArray<>();

        private Once() {
        }

        public static boolean firstTime(int account) {
            synchronized (fired) {
                if (fired.get(account, false)) {
                    return false;
                }
                fired.put(account, true);
                return true;
            }
        }
    }
}

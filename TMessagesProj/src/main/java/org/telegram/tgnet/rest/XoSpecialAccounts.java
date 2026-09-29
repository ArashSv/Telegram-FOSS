package org.telegram.tgnet.rest;

import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.List;

/**
 * T53 — the client-local special/demo account mechanism (tasks 8 + 9).
 *
 * WHAT IT IS
 *   A hard-coded allowlist of EIGHT demo numbers ("+11 1127", "+22 2222", ...).
 *   These are hypothetical numbers (task 10 wording); they are NOT real
 *   country codes, and the backend namespace is CLOSED: v2.6.0 accepts only
 *   "404" + exactly 5 digits ("the fixed prefix +404 followed by exactly 5
 *   digits"). Nothing else can ever reach the server.
 *
 * HOW A SPECIAL LOGIN WORKS WITHOUT TOUCHING THE BACKEND
 *   Each special number maps to a UNIQUE ordinary wire phone inside the
 *   existing 404 namespace:  wire = "404" + last 5 digits of the display
 *   digits. On the server the special accounts ARE ordinary accounts (they
 *   were registered through the public API); the mapping lives only here:
 *
 *       display form   "+11 1127"     (what the user types and sees)
 *       wire form      "40411127"     (what goes to the backend)
 *
 * SECURITY POSTURE (task 8/9 requirements)
 *   - The prefix-editing unlock (hidden 4-tap, LoginActivity) grants exactly
 *     ONE thing: the "+404" label becomes editable so "11"/"22" can be typed.
 *     Submitting still requires either the normal "404"+5-digits namespace or
 *     an EXACT allowlist match — arbitrary prefixes are rejected client-side,
 *     so there is no hidden bypass for arbitrary users.
 *   - The backend keeps its password auth for every login, special or not.
 *     The temp passwords set when the accounts were provisioned are NOT
 *     stored in the APK (they live with the provisioning records and can be
 *     changed any time through the app's own password-change screen).
 *   - Every mapping is exact-match: only these eight numbers are transformed.
 */
public final class XoSpecialAccounts {

    /** One demo account: display prefix ("11"), display number ("1127"), wire phone. */
    public static final class Special {
        public final String code;
        public final String number;
        public final String wirePhone;

        Special(String code, String number) {
            this.code = code;
            this.number = number;
            // wire = 404 + last 5 digits of the display digits ("111127" -> "40411127")
            String displayDigits = code + number;
            this.wirePhone = "404" + displayDigits.substring(displayDigits.length() - 5);
        }

        /** Full display digits, e.g. "111127" for "+11 1127". */
        public String displayDigits() {
            return code + number;
        }

        /** Pretty display, e.g. "+11 1127". */
        public String pretty() {
            return "+" + code + " " + number;
        }
    }

    private static final List<Special> SPECIALS = new ArrayList<>();

    static {
        // +11 group
        SPECIALS.add(new Special("11", "1127"));
        SPECIALS.add(new Special("11", "1130"));
        // +22 group
        SPECIALS.add(new Special("22", "2222"));
        SPECIALS.add(new Special("22", "1234"));
        SPECIALS.add(new Special("22", "1000"));
        SPECIALS.add(new Special("22", "2020"));
        SPECIALS.add(new Special("22", "2220"));
        SPECIALS.add(new Special("22", "0222"));
    }

    /** Prefixes accepted by the hidden editing unlock. */
    public static final String[] SPECIAL_PREFIXES = {"11", "22"};

    private static final String PREF_UNLOCKED = "xoSpecialPrefixUnlocked";
    private static volatile Boolean unlockedCache;

    private XoSpecialAccounts() {
    }

    // ── unlock state (hidden 4-tap activation, task 8) ──────────────────────

    /** True after the hidden 4-tap activation ran at least once on this install. */
    public static boolean isPrefixUnlockActive() {
        Boolean cache = unlockedCache;
        if (cache != null) {
            return cache;
        }
        try {
            boolean v = ApplicationLoader.applicationContext
                    .getSharedPreferences("xoconf", ApplicationLoader.applicationContext.MODE_PRIVATE)
                    .getBoolean(PREF_UNLOCKED, false);
            unlockedCache = v;
            return v;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    /** Called from LoginActivity after the 4th tap. Hidden; persists for the install. */
    public static void setPrefixUnlockActive() {
        try {
            ApplicationLoader.applicationContext
                    .getSharedPreferences("xoconf", ApplicationLoader.applicationContext.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_UNLOCKED, true)
                    .apply();
            unlockedCache = true;
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Is this prefix text an exact special demo prefix ("11"/"22")? */
    public static boolean isExactSpecialPrefix(String codeText) {
        for (String prefix : SPECIAL_PREFIXES) {
            if (prefix.equals(codeText)) {
                return true;
            }
        }
        return false;
    }

    // ── login-side mapping (exact match only) ───────────────────────────────

    /**
     * Exact-match resolver for the login screen: returns the Special whose
     * prefix+number equal the entered fields, or null. The caller gates on
     * {@link #isPrefixUnlockActive()}.
     */
    public static Special matchEntry(String codeFieldText, String phoneFieldText) {
        String code = digitsOnly(codeFieldText);
        String number = digitsOnly(phoneFieldText);
        if (TextUtils.isEmpty(code) || TextUtils.isEmpty(number)) {
            return null;
        }
        for (Special s : SPECIALS) {
            if (s.code.equals(code) && s.number.equals(number)) {
                return s;
            }
        }
        return null;
    }

    // ── render mapping (display side; must be unlock-independent) ───────────

    /**
     * Pretty form for a wire phone, e.g. "40411127" -> "+11 1127". Returns
     * null when the digits are not one of the eight special wire phones.
     * Callers: PhoneFormat.format (the single funnel every phone display site
     * goes through).
     */
    public static String prettyForWireDigits(String digits) {
        if (TextUtils.isEmpty(digits)) {
            return null;
        }
        for (Special s : SPECIALS) {
            if (s.wirePhone.equals(digits)) {
                return s.pretty();
            }
        }
        return null;
    }

    /** Wire phones of the eight special accounts (used by the provisioning probe). */
    public static String wirePhoneOf(String code, String number) {
        for (Special s : SPECIALS) {
            if (s.code.equals(code) && s.number.equals(number)) {
                return s.wirePhone;
            }
        }
        return null;
    }

    // ── multi-account entitlement (T56: hidden everywhere except one demo) ──

    /**
     * The ONLY account allowed to use multi-account (the drawer "Add
     * Account" row, account switching, the settings search entry, the logout
     * screen entry): the demo account "+11 1130". Every other account —
     * including the other seven specials — gets the whole account section
     * hidden; the backend has no multi-account surface to begin with
     * (sessions are independent JWTs), so this client gate IS the product
     * rule, not a cosmetic one.
     */
    public static final String MULTI_ACCOUNT_WIRE = "40411130";

    /** True when the given wire phone (UserConfig phone digits) is the privileged demo account. */
    public static boolean isMultiAccountWire(String wireDigits) {
        return MULTI_ACCOUNT_WIRE.equals(digitsOnly(wireDigits));
    }

    /** True when the CURRENTLY selected account may use multi-account. */
    public static boolean isMultiAccountAllowedForCurrent() {
        try {
            org.telegram.messenger.UserConfig config = org.telegram.messenger.UserConfig.getInstance(org.telegram.messenger.UserConfig.selectedAccount);
            org.telegram.tgnet.TLRPC.User user = config == null ? null : config.getCurrentUser();
            return user != null && isMultiAccountWire(user.phone);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String digitsOnly(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                b.append(c);
            }
        }
        return b.toString();
    }
}

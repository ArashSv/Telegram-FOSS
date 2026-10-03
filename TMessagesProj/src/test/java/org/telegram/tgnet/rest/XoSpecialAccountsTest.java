package org.telegram.tgnet.rest;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T63 — the special/demo account mapping (display form <-> wire form) and
 * the multi-account entitlement key. The mapping is EXACT-MATCH by design
 * (no arbitrary prefix bypass), and the owner wire phone must stay in lock
 * step with the backend's AuthController.MULTI_ACCOUNT_OWNER_PHONE.
 */
public class XoSpecialAccountsTest {

    @BeforeClass
    public static void setUp() {
        XoTestEnv.init();
    }

    @Test
    public void theTenSpecialNumbers_mapExactly() {
        String[][] specials = {
                {"11", "1127"}, {"11", "1130"},
                {"22", "2222"}, {"22", "1234"}, {"22", "1000"}, {"22", "2020"},
                {"22", "2220"}, {"22", "0222"}, {"22", "2009"}, {"22", "0000"},
        };
        for (String[] s : specials) {
            XoSpecialAccounts.Special matched = XoSpecialAccounts.matchEntry(s[0], s[1]);
            assertNotNull("special " + s[0] + " " + s[1] + " must resolve", matched);
            assertEquals("+" + s[0] + " " + s[1], matched.pretty());
            // wire = 404 + last 5 display digits
            String digits = s[0] + s[1];
            assertEquals("404" + digits.substring(digits.length() - 5), matched.wirePhone);
            assertEquals(matched.wirePhone, XoSpecialAccounts.wirePhoneOf(s[0], s[1]));
        }
    }

    @Test
    public void displayAndWireForms_roundTrip() {
        assertEquals("+11 1130", XoSpecialAccounts.prettyForWireDigits("40411130"));
        assertEquals("+11 1127", XoSpecialAccounts.prettyForWireDigits("40411127"));
        assertEquals("+22 0000", XoSpecialAccounts.prettyForWireDigits("40400000"));
        assertNull("non-special wire phones have no pretty form",
                XoSpecialAccounts.prettyForWireDigits("40499999"));
        assertNull(XoSpecialAccounts.prettyForWireDigits(""));
        assertNull(XoSpecialAccounts.prettyForWireDigits(null));
    }

    @Test
    public void matchEntry_isExactMatchOnly() {
        assertNull(XoSpecialAccounts.matchEntry("11", "9999"));     // right prefix, wrong number
        assertNull(XoSpecialAccounts.matchEntry("33", "1127"));    // unknown prefix
        assertNull(XoSpecialAccounts.matchEntry("", "1127"));
        assertNull(XoSpecialAccounts.matchEntry("11", ""));
        assertNull(XoSpecialAccounts.matchEntry(null, null));
        // formatting noise is normalized away (digits-only comparison)
        XoSpecialAccounts.Special s = XoSpecialAccounts.matchEntry("+11", " 1127 ");
        assertNotNull(s);
        assertEquals("40411127", s.wirePhone);
    }

    @Test
    public void multiAccountOwner_isThe40411130WireNumber() {
        // MUST mirror AuthController.MULTI_ACCOUNT_OWNER_PHONE ("+40411130")
        assertTrue(XoSpecialAccounts.isMultiAccountWire("40411130"));
        assertTrue(XoSpecialAccounts.isMultiAccountWire("+40411130"));  // digit cleanup
        assertFalse(XoSpecialAccounts.isMultiAccountWire("40411127"));
        assertFalse(XoSpecialAccounts.isMultiAccountWire("4041130"));   // 7 digits
        assertFalse(XoSpecialAccounts.isMultiAccountWire(""));
        assertFalse(XoSpecialAccounts.isMultiAccountWire(null));
    }

    @Test
    public void specialPrefixes_areTheExactPair() {
        assertTrue(XoSpecialAccounts.isExactSpecialPrefix("11"));
        assertTrue(XoSpecialAccounts.isExactSpecialPrefix("22"));
        assertFalse(XoSpecialAccounts.isExactSpecialPrefix("1"));
        assertFalse(XoSpecialAccounts.isExactSpecialPrefix("33"));
        assertFalse(XoSpecialAccounts.isExactSpecialPrefix("110"));
    }

    @Test
    public void displayDigitsJoin_codeAndNumber() {
        XoSpecialAccounts.Special s = XoSpecialAccounts.matchEntry("22", "2009");
        assertEquals("222009", s.displayDigits());
        // 6 display digits -> wire keeps the LAST 5
        assertEquals("40422009", s.wirePhone);
    }
}

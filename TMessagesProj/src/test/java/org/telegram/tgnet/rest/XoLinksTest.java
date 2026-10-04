package org.telegram.tgnet.rest;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T70 — the xorbit.ir identity-link gate. The client resolves ONLY links that
 * match the backend's username rules exactly (5..32, [A-Za-z0-9_], no leading
 * digit) and that never collide with the site's infrastructure namespace;
 * everything else on xorbit.ir belongs to the web site and must reach the
 * browser, never the username resolver.
 *
 * <p>JVM-scope: the android.net.Uri path (XoLinks.isProfileUri) is exercised
 * on CI's device-less JVM only for pure-string helpers here; Uri itself is a
 * stub under returnDefaultValues and is covered by the compile gate.</p>
 */
public class XoLinksTest {

    @BeforeClass
    public static void setUp() {
        XoTestEnv.init();
    }

    @Test
    public void usernameShape_matchesBackendRules() {
        // valid: 5..32 chars, [A-Za-z0-9_], no leading digit
        assertTrue(XoLinks.isUsernameShape("hadifan"));
        assertTrue(XoLinks.isUsernameShape("Hadi_Fan1"));
        assertTrue(XoLinks.isUsernameShape("a_bCd5"));
        assertTrue(XoLinks.isUsernameShape("abcde"));
        assertTrue(XoLinks.isUsernameShape("a".repeat(32)));

        // invalid shapes
        assertFalse("null", XoLinks.isUsernameShape(null));
        assertFalse("empty", XoLinks.isUsernameShape(""));
        assertFalse("4 chars (backend USERNAME_MIN=5)", XoLinks.isUsernameShape("hadi"));
        assertFalse("33 chars", XoLinks.isUsernameShape("a".repeat(33)));
        assertFalse("dash not in charset", XoLinks.isUsernameShape("bad-name"));
        assertFalse("dot not in charset", XoLinks.isUsernameShape("a.html"));
        assertFalse("leading digit (backend USERNAME_INVALID_START)", XoLinks.isUsernameShape("5hadifan"));
        assertFalse("leading underscore", XoLinks.isUsernameShape("_hadi"));
    }

    @Test
    public void reservedPaths_coverInfrastructureNamespace() {
        // webroot + API namespace (mirrors UsersController::USERNAME_RESERVED)
        for (String name : new String[]{"tele", "api", "storage", "assets", "logs",
                "files", "webmail", "mail", "cpanel", "webdisk", "tmp", "backup",
                "download", "downloads", "upload", "uploads", "images", "img",
                "css", "js", "data", "docs", "hermes", "messenger", "app", "www",
                "cdn", "static", "media", "ftp", "web", "site", "home", "main",
                "telegram", "admin", "support", "xorbit", "bot", "help"}) {
            assertTrue("reserved: " + name, XoLinks.isReservedPath(name));
        }
        // case-insensitive
        assertTrue(XoLinks.isReservedPath("TELE"));
        assertTrue(XoLinks.isReservedPath("Media"));
        // ordinary usernames are NOT reserved
        assertFalse(XoLinks.isReservedPath("hadifan"));
        assertFalse(XoLinks.isReservedPath("ali_gold5"));
        assertFalse(XoLinks.isReservedPath(null));
    }

    @Test
    public void resolvableProfiles_shapeAndNamespaceGate() {
        assertTrue(XoLinks.isResolvableProfile("hadifan"));
        assertTrue(XoLinks.isResolvableProfile("Ali_Gold5"));

        // the user's own example class: an ID equal to a webroot folder is
        // refused — for 4-char 'hadi' by the length rule first, for >=5-char
        // folders by the reserved set (or by the backend's live webroot probe
        // for folders outside the static list)
        assertFalse(XoLinks.isResolvableProfile("hadi"));
        assertFalse(XoLinks.isResolvableProfile("media"));
        assertFalse(XoLinks.isResolvableProfile("5digits"));
        assertFalse(XoLinks.isResolvableProfile("hadifan/extra"));
    }

    @Test
    public void profileHosts_areTheIdentityDomainOnly() {
        assertTrue(XoLinks.isProfileHost("xorbit.ir"));
        assertTrue(XoLinks.isProfileHost("www.xorbit.ir"));
        assertTrue(XoLinks.isProfileHost("XORBIT.IR"));
        assertFalse("t.me is retired as the identity domain", XoLinks.isProfileHost("t.me"));
        assertFalse(XoLinks.isProfileHost("xorbit.com"));
        assertFalse(XoLinks.isProfileHost("sub.xorbit.ir"));
        assertFalse(XoLinks.isProfileHost(null));
    }

    @Test
    public void profileUrl_buildsCanonicalLinks() {
        assertEquals("https://xorbit.ir/hadifan", XoLinks.profileUrl("hadifan"));
        assertEquals("https://xorbit.ir/", XoLinks.profileUrl(""));
        assertEquals("https://xorbit.ir/", XoLinks.profileUrl(null));
    }

    @Test
    public void linkPrefix_constantIsTheIdentityDomain() {
        assertEquals("xorbit.ir", XoLinks.LINK_PREFIX);
        assertEquals("https://xorbit.ir/", XoLinks.BASE);
        assertEquals("https://hermes.xorbit.ir/", XoLinks.WEB_APP_FALLBACK);
    }
}

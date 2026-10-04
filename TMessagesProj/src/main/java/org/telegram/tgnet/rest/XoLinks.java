package org.telegram.tgnet.rest;

import org.telegram.messenger.Utilities;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * T70 — Hermes identity links: xorbit.ir replaces t.me everywhere.
 *
 * <p>Profile links are {@code https://xorbit.ir/<username>} — the same shape
 * Telegram serves under t.me. The web layer answers with the "View in Hermes"
 * landing page (public/webroot/hermes.php); the app answers by resolving the
 * username and opening the profile/chat.</p>
 *
 * <p>Everything else on the domain belongs to the SITE (folders, pages, the
 * /tele API tree) and must go to the browser — including when App Links
 * verification routes a site URL into the app. That is why the profile-shape
 * gate is strict: exactly one (or username/<message-id>) path segment,
 * username charset [A-Za-z0-9_]{5,32}, no leading digit (mirrors the backend
 * USERNAME_* rules) and never a reserved infrastructure name.</p>
 *
 * <p>The reserved set mirrors UsersController::USERNAME_RESERVED on the
 * backend — update both together.</p>
 */
public final class XoLinks {

    public static final String HOST = "xorbit.ir";
    public static final String WWW_HOST = "www.xorbit.ir";

    /** Value for MessagesController.linkPrefix — the domain users see, no scheme. */
    public static final String LINK_PREFIX = HOST;

    public static final String BASE = "https://" + HOST + "/";

    /** Where visitors land when Hermes has nothing for the requested ID. */
    public static final String WEB_APP_FALLBACK = "https://hermes.xorbit.ir/";

    private static final Pattern USERNAME_RE = Pattern.compile("^[A-Za-z0-9_]{5,32}$");

    /** Infrastructure names that must never be treated as profile links. */
    private static final Set<String> RESERVED_PATHS = new HashSet<>(Arrays.asList(
            "telegram", "admin", "administrator", "support", "xorbit", "api",
            "official", "help", "security", "moderation", "moderator",
            "bot", "spam", "abuse",
            "tele", "storage", "assets", "logs", "files", "well", "known",
            "webmail", "mail", "cpanel", "webdisk", "tmp", "backup",
            "download", "downloads", "upload", "uploads", "images", "img",
            "css", "js", "data", "docs", "hermes", "messenger", "app", "www",
            "cdn", "static", "media", "ftp", "web", "site", "home", "main"
    ));

    private XoLinks() {
    }

    /** true for the identity hosts (xorbit.ir / www.xorbit.ir), scheme-less,
     *  case-insensitive defensively. */
    public static boolean isProfileHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase();
        return HOST.equals(h) || WWW_HOST.equals(h);
    }

    /** Backend-exact username shape: 5..32, [A-Za-z0-9_], no leading digit,
     *  no leading/trailing underscore (both are USERNAME_INVALID server-side
     *  and in the username editor). */
    public static boolean isUsernameShape(String s) {
        if (s == null || !USERNAME_RE.matcher(s).matches()) {
            return false;
        }
        char first = s.charAt(0);
        if (first >= '0' && first <= '9') {
            return false;
        }
        if (first == '_' || s.charAt(s.length() - 1) == '_') {
            return false;
        }
        return true;
    }

    /** Site-owned namespace (never a profile), case-insensitive. */
    public static boolean isReservedPath(String s) {
        return s != null && RESERVED_PATHS.contains(s.toLowerCase());
    }

    /** A single path segment this app should resolve as a profile link. */
    public static boolean isResolvableProfile(String segment) {
        return isUsernameShape(segment) && !isReservedPath(segment);
    }

    /**
     * https://xorbit.ir/{username} or https://xorbit.ir/{username}/{messageId}
     * — the two link shapes the app opens in-app; anything else on the
     * domain is site content for the browser.
     */
    public static boolean isProfileUri(android.net.Uri uri) {
        if (uri == null) {
            return false;
        }
        try {
            String host = uri.getHost();
            if (host == null || !isProfileHost(host.toLowerCase())) {
                return false;
            }
            String scheme = uri.getScheme();
            if (!"https".equals(scheme) && !"http".equals(scheme)) {
                return false;
            }
            List<String> segments = uri.getPathSegments();
            if (segments.isEmpty()) {
                return false;
            }
            if (!isResolvableProfile(segments.get(0))) {
                return false;
            }
            if (segments.size() == 1) {
                return true;
            }
            if (segments.size() == 2) {
                return Utilities.parseInt(segments.get(1)) != 0;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** The canonical profile URL for a username. */
    public static String profileUrl(String username) {
        // Pure-Java null/empty handling: this class must stay runnable on the
        // JVM test gate (returnDefaultValues makes android.text.TextUtils
        // answer false for everything, which would concat "null" here).
        if (username == null || username.length() == 0) {
            return BASE;
        }
        return BASE + username;
    }
}

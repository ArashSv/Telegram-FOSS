#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
T67 static crash-hygiene gate (pure stdlib, runs on CI and locally).

This suite encodes the FIELD-PROVEN crash classes reported through the
android_log pipeline (builds 0.1.0-91/92, Oct 2026) so they can never
regress:

  CR-A  Android 14/15/16 launch crash — "crash the moment the splash exits".
        Reports: crash-1ccc947c / 139f58be / 95488ca2 (Xiaomi 25028PC03G
        "serenity", A15) and crash-dd357528 / c00606ff (Samsung SM-A366B
        "a36xq", A16, two units). Root cause:
        NotificationsService.onCreate built
            new Intent("android.intent.action.VIEW")     <- IMPLICIT
            PendingIntent.getActivity(.., FLAG_MUTABLE)  <- MUTABLE
        which is a hard IllegalArgumentException for targetSdk 34+ ("Targeting
        U+ disallows creating or retrieving a PendingIntent with
        FLAG_MUTABLE, an implicit Intent ..."). START_STICKY then turned it
        into a crash loop. Android 13 (SDK 33) was immune only because the
        rule is not enforced below SDK 34.

  CR-B  The :crash report process itself crashed while delivering a report
        (report crash-06d40a41, process com.hermes.chat:crash, A15):
        ApplicationLoader.onConfigurationChanged ->
        LocaleController.getInstance() -> MessagesController ->
        ConnectionsManager.native_isTestBackend -> UnsatisfiedLinkError,
        because native libs are deliberately main-process-only (T66) and the
        method was guarded with catch(Exception) — UnsatisfiedLinkError is an
        Error, not an Exception.

  CR-C  Latent same-class violations found by the T67 audit:
        MusicPlayerService.setListeners built five action-only implicit
        intents with FLAG_MUTABLE (media-notification buttons) — would crash
        the moment a media notification with RemoteViews listeners was built
        on Android 14/15/16. Fixed to explicit components.

Rules enforced (see test classes below):
  A1  NotificationsService.java is an inert stub (no PendingIntent /
      notification / foreground / alarm / broadcast / prefs code).
  A2  No Java code references NotificationsService anymore (comments are
      stripped before scanning); exactly one non-exported manifest
      declaration remains as a no-op shell.
  A3  ApplicationLoader.startPushService() is eradicated (no code refs).
  B1  ApplicationLoader.onConfigurationChanged is guarded by isMainProcess()
      BEFORE any LocaleController usage.
  B2  ...and catches Throwable (UnsatisfiedLinkError is an Error).
  D1  Every PendingIntent.get*() call declares mutability explicitly
      (FLAG_IMMUTABLE or FLAG_MUTABLE) — required since Android 12 (S).
  D2  Every FLAG_MUTABLE PendingIntent uses an EXPLICIT intent — required
      since Android 14 (U) for targetSdk 34+ (the CR-A/CR-C crash class).
  S1  Scanner self-check: the exact CR-A bug pattern must be FLAGGED by the
      scanner, and compliant patterns must pass (guards against scanner rot).

Exit code is non-zero on any failure -> the CI unit-tests job (and the APK
build behind it) refuses to run.
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]          # .../TMessagesProj
SRC = ROOT / "src" / "main" / "java"
MANIFEST = ROOT / "src" / "main" / "AndroidManifest.xml"

PENDING_INTENT_RE = re.compile(
    r"\bPendingIntent\s*\.\s*(getActivity|getBroadcast|getService|getForegroundService)\s*\(")


# ────────────────────────── scanning helpers ──────────────────────────

def strip_comments(src):
    """Remove // and /* */ comments while preserving strings and newlines."""
    out = []
    i, n = 0, len(src)
    state = "code"
    str_ch = ""
    while i < n:
        ch = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if state == "code":
            if ch == "/" and nxt == "/":
                state = "line"
                i += 2
                continue
            if ch == "/" and nxt == "*":
                state = "block"
                i += 2
                continue
            if ch in "\"'":
                state = "str"
                str_ch = ch
            out.append(ch)
            i += 1
        elif state == "line":
            if ch == "\n":
                state = "code"
                out.append("\n")
            else:
                out.append(" ")
            i += 1
        elif state == "block":
            if ch == "*" and nxt == "/":
                state = "code"
                i += 2
                out.append(" ")
            else:
                out.append("\n" if ch == "\n" else " ")
                i += 1
        else:  # inside a string literal
            if ch == "\\":
                out.append(ch)
                if nxt:
                    out.append(nxt)
                i += 2
                continue
            if ch == str_ch:
                state = "code"
            out.append(ch)
            i += 1
    return "".join(out)


def extract_balanced_call(src, open_idx):
    """Given the index of an opening '(' or '{', return (inner, close_idx)."""
    is_brace = src[open_idx] == "{"
    open_ch, close_ch = ("{", "}") if is_brace else ("(", ")")
    depth = 0
    i = open_idx
    n = len(src)
    in_str = False
    str_ch = ""
    while i < n:
        ch = src[i]
        if in_str:
            if ch == "\\":
                i += 2
                continue
            if ch == str_ch:
                in_str = False
        else:
            if ch in "\"'":
                in_str = True
                str_ch = ch
            elif ch == open_ch:
                depth += 1
            elif ch == close_ch:
                depth -= 1
                if depth == 0:
                    return src[open_idx + 1:i], i
        i += 1
    return None, None


def split_top_level(text):
    """Split on commas that are not nested inside () [] {} or strings."""
    parts, buf = [], []
    depth = 0
    in_str = False
    str_ch = ""
    i = 0
    while i < len(text):
        ch = text[i]
        if in_str:
            buf.append(ch)
            if ch == "\\":
                if i + 1 < len(text):
                    buf.append(text[i + 1])
                i += 2
                continue
            if ch == str_ch:
                in_str = False
        elif ch in "\"'":
            in_str = True
            str_ch = ch
            buf.append(ch)
        elif ch in "([{":
            depth += 1
            buf.append(ch)
        elif ch in ")]}":
            depth -= 1
            buf.append(ch)
        elif ch == "," and depth == 0:
            parts.append("".join(buf))
            buf = []
        else:
            buf.append(ch)
        i += 1
    if buf:
        parts.append("".join(buf))
    return parts


def _last_new_intent_is_two_arg(window):
    """True if the last `new Intent(...)` in window has >= 2 ctor args
    (context+Class / context+ComponentName => component set => explicit)."""
    last = None
    for m in re.finditer(r"new\s+Intent\s*\(", window):
        last = m
    if last is None:
        return False
    inner, _ = extract_balanced_call(window, last.end() - 1)
    if inner is None:
        return False
    return len(split_top_level(inner)) >= 2


def intent_is_explicit(intent_expr, source, call_start):
    """Decide whether the intent passed to a PendingIntent factory is explicit
    (component/class set) — the U+ rule allows FLAG_MUTABLE only for those."""
    expr = intent_expr.strip()

    # Case 1: inline `new Intent(...)[.chain()]`
    if re.search(r"new\s+Intent\s*\(", expr):
        for m in re.finditer(r"new\s+Intent\s*\(", expr):
            inner, _ = extract_balanced_call(expr, m.end() - 1)
            if inner is None:
                return False
            if len(split_top_level(inner)) >= 2:
                return True  # (context, Class) or (context, ComponentName)
        return bool(re.search(r"\.setComponent\s*\(|\.setClass\s*\(", expr))

    # Case 2: a bare identifier — resolve `Intent <name> =` declarations
    name = expr
    if not re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*", name):
        return False  # method call / field chain we cannot resolve
    decl_re = re.compile(r"\bIntent\s+" + re.escape(name) + r"\s*=")
    decls = [m for m in decl_re.finditer(source[:call_start])]
    if not decls:
        return False  # cannot resolve -> treated as violation
    decl = decls[-1]
    # window = from the declaration to the PendingIntent call (bounded): the
    # component may be set in a SEPARATE statement, e.g.
    #   Intent i = new Intent(ACTION);
    #   i.setComponent(name);            <- must still count as explicit
    window = source[decl.start():call_start][:4000]
    if re.search(r"\.setComponent\s*\(|\.setClass\s*\(", window):
        return True
    return _last_new_intent_is_two_arg(window)


def classify_sites(base_dir=None):
    """Classify all PendingIntent.get* sites; returns (no_mutability,
    mutable_implicit). Each entry is ((relpath, line), detail)."""
    root = base_dir if base_dir is not None else SRC
    no_mutability, mutable_implicit = [], []
    for path in sorted(root.rglob("*.java")):
        raw = path.read_text(encoding="utf-8", errors="replace")
        code = strip_comments(raw)
        for m in PENDING_INTENT_RE.finditer(code):
            args, _close = extract_balanced_call(code, m.end() - 1)
            loc = (str(path.relative_to(root)), code.count("\n", 0, m.start()) + 1)
            if args is None:
                mutable_implicit.append((loc, "UNBALANCED_CALL"))
                continue
            has_imm = "FLAG_IMMUTABLE" in args
            has_mut = "FLAG_MUTABLE" in args
            if not has_imm and not has_mut:
                no_mutability.append((loc, args[:120]))
                continue
            if has_mut and not has_imm:
                parts = split_top_level(args)
                intent_expr = parts[2].strip() if len(parts) >= 4 else ""
                ok = bool(intent_expr) and intent_is_explicit(intent_expr, code, m.start())
                if not ok:
                    mutable_implicit.append((loc, (intent_expr or args)[:160]))
    return no_mutability, mutable_implicit


def method_body(source, signature_regex):
    """Return the { ... } body of the first method matching signature_regex."""
    m = re.search(signature_regex, source)
    if not m:
        return None
    open_idx = source.find("{", m.end() - 1)
    if open_idx < 0:
        return None
    body, _ = extract_balanced_call(source, open_idx)
    return body


# ────────────────────────────── tests ──────────────────────────────

class TestA_NotificationsServiceEradicated(unittest.TestCase):
    SRC_FILE = SRC / "org" / "telegram" / "messenger" / "NotificationsService.java"

    @classmethod
    def setUpClass(cls):
        cls.code = strip_comments(cls.SRC_FILE.read_text(encoding="utf-8"))

    def test_a1_stub_is_inert(self):
        for banned in ("PendingIntent", "startForeground", "NotificationManager",
                       "NotificationChannel", "NotificationCompat", "AlarmManager",
                       "sendBroadcast", "SharedPreferences", "postInitApplication"):
            self.assertNotIn(banned, self.code,
                             "NotificationsService stub must not contain %s" % banned)

    def test_a1b_stub_never_sticky(self):
        self.assertIn("START_NOT_STICKY", self.code)
        self.assertNotIn("START_STICKY", self.code.replace("START_NOT_STICKY", ""))

    def test_a2_no_code_references_left(self):
        offenders = []
        for path in SRC.rglob("*.java"):
            if path == self.SRC_FILE:
                continue
            code = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
            if re.search(r"\bNotificationsService\b", code):
                offenders.append(str(path.relative_to(SRC)))
        self.assertEqual(offenders, [],
                         "code references to NotificationsService must be gone: %s" % offenders)

    def test_a2b_manifest_keeps_exactly_one_inert_declaration(self):
        xml = MANIFEST.read_text(encoding="utf-8")
        decls = re.findall(r'android:name="\.NotificationsService"', xml)
        self.assertEqual(len(decls), 1)
        m = re.search(r'<service[^>]*\.NotificationsService[^>]*>', xml)
        self.assertIsNotNone(m)
        self.assertIn('android:exported="false"', m.group(0))

    def test_a3_start_push_service_gone(self):
        offenders = []
        for path in SRC.rglob("*.java"):
            code = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
            if re.search(r"\bstartPushService\b", code):
                offenders.append(str(path.relative_to(SRC)))
        self.assertEqual(offenders, [], "startPushService() must stay deleted: %s" % offenders)


class TestB_CrashProcessGuard(unittest.TestCase):
    SRC_FILE = SRC / "org" / "telegram" / "messenger" / "ApplicationLoader.java"
    SIG = r"public\s+void\s+onConfigurationChanged\s*\(\s*Configuration\s+newConfig\s*\)"

    @classmethod
    def setUpClass(cls):
        cls.body = method_body(
            strip_comments(cls.SRC_FILE.read_text(encoding="utf-8")), cls.SIG)

    def test_b1_on_configuration_changed_is_guarded(self):
        self.assertIsNotNone(self.body, "onConfigurationChanged not found")
        guard = self.body.find("isMainProcess()")
        locale = self.body.find("LocaleController.getInstance()")
        self.assertGreaterEqual(guard, 0, "isMainProcess() guard missing")
        self.assertGreaterEqual(locale, 0, "LocaleController usage missing")
        self.assertLess(guard, locale,
                        "the isMainProcess() guard must run BEFORE LocaleController "
                        "(:crash process has no native libs — UnsatisfiedLinkError)")

    def test_b2_catches_throwable_not_just_exception(self):
        self.assertIsNotNone(self.body)
        self.assertIn("catch (Throwable", self.body,
                      "UnsatisfiedLinkError is an Error — catch (Exception) let the "
                      ":crash process die (crash-06d40a41)")


class TestD_PendingIntentHygiene(unittest.TestCase):
    def test_d0_scanner_saw_a_real_number_of_sites(self):
        sites = 0
        for path in SRC.rglob("*.java"):
            code = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
            sites += len(PENDING_INTENT_RE.findall(code))
        self.assertGreaterEqual(sites, 30,
                                "scanner degenerated — found suspiciously few PendingIntent sites")

    def test_d1_every_site_declares_mutability(self):
        no_mut, _ = classify_sites()
        self.assertEqual(no_mut, [],
                         "Since Android 12 (S) every PendingIntent must declare "
                         "FLAG_IMMUTABLE or FLAG_MUTABLE explicitly:\n" +
                         "\n".join("  %s:%s" % loc for loc, _ in no_mut))

    def test_d2_mutable_requires_explicit_intent(self):
        _, mut_impl = classify_sites()
        self.assertEqual(mut_impl, [],
                         "Since Android 14 (U) + targetSdk 34+, FLAG_MUTABLE with an "
                         "IMPLICIT intent throws IllegalArgumentException at creation "
                         "(the NotificationsService splash-exit crash class):\n" +
                         "\n".join("  {0}:{1}  {2}".format(loc[0], loc[1], why)
                                   for loc, why in mut_impl))


class TestS_ScannerSelfCheck(unittest.TestCase):
    """The scanner must reproduce the field bug if the code regressed."""

    OLD_CRASH_PATTERN = (
        "package org.telegram.messenger;\n"
        "public class X {\n"
        "    void f(android.content.Context ctx) {\n"
        "        Intent explainIntent = new Intent(\"android.intent.action.VIEW\");\n"
        "        explainIntent.setData(android.net.Uri.parse(\"https://example.test/x\"));\n"
        "        PendingIntent explainPendingIntent = PendingIntent.getActivity(ctx, 0, "
        "explainIntent, PendingIntent.FLAG_MUTABLE);\n"
        "    }\n"
        "}\n"
    )

    def _violations_in(self, snippet, tmp_path):
        probe = tmp_path / "Probe.java"
        probe.write_text(snippet, encoding="utf-8")
        module = globals()
        saved = module["SRC"]
        try:
            module["SRC"] = tmp_path
            return classify_sites(tmp_path)
        finally:
            module["SRC"] = saved

    def test_s1_original_bug_is_flagged(self):
        import tempfile
        with tempfile.TemporaryDirectory() as td:
            no_mut, mut_impl = self._violations_in(self.OLD_CRASH_PATTERN, Path(td))
            self.assertFalse(no_mut)
            self.assertEqual(len(mut_impl), 1,
                             "scanner FAILED to flag the exact field bug pattern "
                             "(implicit intent + FLAG_MUTABLE): %r" % (mut_impl,))

    def test_s2_compliant_patterns_pass(self):
        import tempfile
        good_immutable = (
            "package org.telegram.messenger;\n"
            "public class Y {\n"
            "    void f(android.content.Context ctx) {\n"
            "        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, "
            "new Intent(\"some.ACTION\"), PendingIntent.FLAG_IMMUTABLE | "
            "PendingIntent.FLAG_UPDATE_CURRENT);\n"
            "    }\n"
            "}\n"
        )
        good_mutable_explicit = (
            "package org.telegram.messenger;\n"
            "public class Z {\n"
            "    void f(android.content.Context ctx) {\n"
            "        PendingIntent pj = PendingIntent.getActivity(ctx, 0, "
            "new Intent(ctx, org.telegram.ui.LaunchActivity.class), "
            "PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);\n"
            "    }\n"
            "}\n"
        )
        with tempfile.TemporaryDirectory() as td:
            for snippet in (good_immutable, good_mutable_explicit):
                no_mut, mut_impl = self._violations_in(snippet, Path(td))
                self.assertFalse(no_mut, snippet)
                self.assertFalse(mut_impl, snippet)


if __name__ == "__main__":
    unittest.main(verbosity=2)

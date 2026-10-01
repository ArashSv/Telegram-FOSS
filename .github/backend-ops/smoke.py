#!/usr/bin/env python3
"""Smoke test the live tele API (https://xorbit.ir/tele) from GitHub Actions.

Usage: smoke.py baseline|verify
- baseline: informational run. The known-bad check-phone behaviour (403
  MULTI_ACCOUNT_FORBIDDEN) is REPORTED but never hard-fails the run.
- verify: run after deploying a fix; every step's expected outcome must match
  (nonzero exit otherwise).

Browser User-Agent REQUIRED (plain curl UA gets WAF-killed). JSON POST bodies.
Transport failures (curl code 000) retried up to 3x with 2s backoff.
"""
import json
import os
import random
import re
import subprocess
import sys
import time

BASE = os.environ.get("API_BASE", "https://xorbit.ir/tele").rstrip("/")
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36"
PASSWORD = "xo-test-951"
MODE = sys.argv[1] if len(sys.argv) > 1 else "baseline"
OWNER_PHONE = "+40411130"  # the owner's +11 1130 (multi-account keying number)


def fresh_phone():
    """404 + 5 random digits in the 90000-98999 range -> +4049xxxx."""
    return "+404" + str(random.randint(90000, 98999))


def curl_once(method, path, body=None, headers=None):
    cmd = ["curl", "-sS", "-m", "25", "-A", UA, "-H", "User-Agent: " + UA,
           "-X", method]
    for k, v in (headers or {}).items():
        cmd += ["-H", f"{k}: {v}"]
    if body is not None:
        cmd += ["-H", "Content-Type: application/json",
                "--data-binary", json.dumps(body)]
    cmd += [BASE + path, "-w", "\n%{http_code}"]
    p = subprocess.run(cmd, capture_output=True, timeout=40)
    text = p.stdout.decode(errors="replace")
    body_out, _, code = text.rpartition("\n")
    return code.strip(), body_out


def http(method, path, body=None, headers=None, tries=3):
    """Retry transport failures (code 000) up to 3x with 2s backoff."""
    last = ("000", "")
    for i in range(tries):
        code, body_out = curl_once(method, path, body, headers)
        if code != "000":
            return code, body_out
        last = (code, body_out)
        if i < tries - 1:
            time.sleep(2)
    return last


def extract_token(body):
    try:
        d = json.loads(body)
        if isinstance(d, dict):
            if d.get("access_token"):
                return d["access_token"]
            data = d.get("data")
            if isinstance(data, dict) and data.get("access_token"):
                return data["access_token"]
    except Exception:  # noqa: BLE001
        pass
    m = re.search(r'"access_token"\s*:\s*"([^"]+)"', body)
    return m.group(1) if m else None


def registered_false(body):
    try:
        d = json.loads(body)
        if isinstance(d, dict):
            if d.get("registered") is False:
                return True
            if isinstance(d.get("data"), dict) and d["data"].get("registered") is False:
                return True
    except Exception:  # noqa: BLE001
        pass
    return '"registered": false' in body or '"registered":false' in body


results = []  # (name, expected_outcome_or_None, matched_bool)


def step(name, code, body, expect=None):
    """Print the step line; track expectation match. expect=None -> report-only."""
    matched = True
    if expect is not None:
        matched = expect(code, body)
    exp = "report-only" if expect is None else expect.__name__.replace("expect_", "")
    print(f"STEP {name}: HTTP {code} {body[:300]}", flush=True)
    results.append((name, exp, matched, expect is not None))
    return matched


def expect_200(code, body):
    return code == "200"


def expect_200_registered_false(code, body):
    return code == "200" and registered_false(body)


def expect_200_access_token(code, body):
    return code == "200" and extract_token(body) is not None


def expect_403_multi_account(code, body):
    return code == "403" and "MULTI_ACCOUNT_FORBIDDEN" in body


def main():
    phone = fresh_phone()
    other = fresh_phone()
    while other == phone:
        other = fresh_phone()
    print(f"smoke mode={MODE} phone={phone} other={other} base={BASE}", flush=True)
    transport_ok = True

    # -- which auth path shape works? (use EXACT api/v1 paths first) -------------
    prefix = "/api/v1/auth"
    code, body = http("POST", f"{prefix}/check-phone.php", {"phone": phone})
    if code == "404":
        code2, body2 = http("POST", "/auth/check-phone.php", {"phone": phone})
        print(f"NOTE: /api/v1/auth/check-phone.php -> 404; fallback "
              f"/auth/check-phone.php -> {code2}", flush=True)
        if code2 != "404":
            prefix = "/auth"
            code, body = code2, body2
    print(f"NOTE: auth path shape in use: {prefix}/", flush=True)

    # 1. service info -----------------------------------------------------------
    code, body = http("GET", "/")
    if code == "000":
        transport_ok = False
    step("service-info", code, body, expect_200)
    v = re.search(r"v[0-9]+(?:\.[0-9]+)+", body)
    if v:
        print(f"SERVICE VERSION: {v.group(0)}", flush=True)

    # 2. check-phone (OWNER's own number, NO bearer) -----------------------------
    # verify: HTTP 200 expected (bootstrap case 1 — the owner's own number
    # with no bearer is open under the v2.9.3 session-state-aware gate).
    # baseline: report-only.
    code, body = http("POST", f"{prefix}/check-phone.php", {"phone": OWNER_PHONE})
    if code == "000":
        transport_ok = False
    if MODE == "verify":
        step("check-phone-owner", code, body, expect_200)
    else:
        step("check-phone-owner", code, body)

    # 3. check-phone (fresh number) ---------------------------------------------
    code, body = http("POST", f"{prefix}/check-phone.php", {"phone": phone})
    if code == "000":
        transport_ok = False
    if MODE == "verify":
        step("check-phone", code, body, expect_200_registered_false)
    else:
        # baseline: expected per the reported disaster is 403 MULTI_ACCOUNT_FORBIDDEN
        step("check-phone", code, body, expect_403_multi_account)
    print(f"NOTE: check-phone path shape that works: {prefix}/check-phone.php "
          f"(tried /api/v1/auth/ first)", flush=True)

    # 4. register ---------------------------------------------------------------
    code, body = http("POST", f"{prefix}/register.php",
                      {"phone": phone, "password": PASSWORD})
    if code == "000":
        transport_ok = False
    if MODE == "verify":
        step("register", code, body, expect_200_access_token)
    else:
        step("register", code, body)
    bearer = extract_token(body) or ""

    # 5. login (fresh number) ----------------------------------------------------
    code, body = http("POST", f"{prefix}/login.php",
                      {"phone": phone, "password": PASSWORD})
    if code == "000":
        transport_ok = False
    if MODE == "verify":
        step("login", code, body, expect_200_access_token)
    else:
        step("login", code, body)
    bearer = extract_token(body) or bearer
    auth = {"Authorization": f"Bearer {bearer}"} if bearer else {}

    # 6. login for a DIFFERENT fresh number WITH the fresh account's bearer ------
    code, body = http("POST", f"{prefix}/login.php",
                      {"phone": other, "password": "x"}, headers=auth)
    if code == "000":
        transport_ok = False
    if MODE == "verify":
        step("login-other-with-bearer", code, body, expect_403_multi_account)
    else:
        step("login-other-with-bearer", code, body)

    # 7. self re-auth: SAME fresh number WITH its own bearer ----------------------
    code, body = http("POST", f"{prefix}/login.php",
                      {"phone": phone, "password": PASSWORD}, headers=auth)
    if code == "000":
        transport_ok = False
    if MODE == "verify":
        step("login-self-reauth", code, body, expect_200)
    else:
        step("login-self-reauth", code, body)

    defined = [r for r in results if r[3]]
    n = sum(1 for r in defined if r[2])
    m = len(defined)
    for name, exp, matched, is_defined in results:
        print(f"  {name}: {'PASS' if matched else 'FAIL'}"
              f"{'' if is_defined else ' (report-only)'}", flush=True)
    print(f"SMOKE {MODE}: {n}/{m} expected-outcome-matches", flush=True)

    if not transport_ok:
        print("TRANSPORT FAILURE: API unreachable (curl 000 persisted)", flush=True)
        return 1
    if MODE == "verify" and n < m:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

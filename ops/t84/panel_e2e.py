#!/usr/bin/env python3
"""T84 — LIVE E2E validation of the ADMIN PANEL (https://xorbit.ir/tele/panel/).

Exercises, against the production backend:
  L1 public surface (service info + panel login page)
  L2 admin auth (uniform 401, session cookie flags, X-Panel + CSRF gates,
     stats/users/groups/media/audit lists)
  L3 account-disable enforcement (login/refresh/access-token/download all
     refuse a disabled account; re-enable restores it)
  L4 group management (ban blocks re-add, unban restores, admin delete
     removes the group for every member)
  L5 audit trail completeness

Transport discipline (T77 lessons): curl -4 --http1.1 + retries.
Runs on the GH Actions runner (the sandbox is banned from the host).
"""
import json
import subprocess
import sys
import tempfile
import time

BASE = "https://xorbit.ir/tele"
PANEL = BASE + "/panel/api.php"
ADMIN_USER = "hermes-admin"
ADMIN_PASS = "n2Bz7kvxnFvgsoQUF21ZKZ"
PROBE1 = {"phone": "+40499395", "password": "T81Probe1#k9m2"}
PROBE2 = {"phone": "+40453878", "password": "T81Probe2#w4n7"}

JAR = tempfile.NamedTemporaryFile(suffix=".jar", delete=False).name
PASS_N = 0
FAIL_N = 0


def check(name, cond, extra=""):
    global PASS_N, FAIL_N
    if cond:
        PASS_N += 1
        print(f"  ok  {name}", flush=True)
    else:
        FAIL_N += 1
        print(f" FAIL {name} {extra}", flush=True)


def _curl_once(method, url, body=None, headers=None):
    cmd = ["curl", "-4", "--http1.1", "-sS", "-m", "25", "-k", "-X", method]
    if body is not None:
        cmd += ["--data-binary", json.dumps(body), "-H", "Content-Type: application/json"]
    for k, v in (headers or {}).items():
        cmd += ["-H", f"{k}: {v}"]
    cmd += ["-b", JAR, "-c", JAR, url, "-w", "\n__CODE__%{http_code}\n__SETC__%{header_json}"]
    p = subprocess.run(cmd, capture_output=True, timeout=50)
    out = p.stdout.decode(errors="replace")
    if "__CODE__" not in out:
        return 0, {}, ""
    body_part, rest = out.split("__CODE__", 1)
    code_str, rest2 = rest.split("\n", 1)
    setc = ""
    if "__SETC__" in rest2:
        try:
            hdrs = json.loads(rest2.split("__SETC__", 1)[1])
            v = hdrs.get("set-cookie") or hdrs.get("Set-Cookie")
            if isinstance(v, list) and v:
                setc = v[0]
            elif isinstance(v, str):
                setc = v
        except Exception:
            setc = ""
    try:
        payload = json.loads(body_part.strip() or "{}")
    except Exception:
        payload = {"raw": body_part[:20000]}
    return int(code_str.strip() or 0), payload, setc


def http(method, url, body=None, headers=None, budget_s=90):
    start = time.time()
    n = 0
    last = (0, {}, "")
    while time.time() - start < budget_s:
        n += 1
        try:
            code, payload, setc = _curl_once(method, url, body, headers)
            if code:
                return code, payload, setc
            last = (code, payload, setc)
        except Exception:
            pass
        time.sleep(1.5 + n % 4)
    return last


def panel(action, body=None, csrf=None, cookie=None, omit_xpanel=False):
    h = {}
    b = dict(body or {})
    b["action"] = action
    if csrf is not None:
        b["csrf"] = csrf
        h["X-CSRF"] = csrf
    if cookie:
        h["Cookie"] = cookie
    if not omit_xpanel:
        h["X-Panel"] = "1"
    return http("POST", PANEL, b, h)


def main():
    print("== L1. public surface ==")
    st, j, _ = http("GET", BASE + "/index.php")
    check("service info reachable", st == 200 and j.get("ok") is True, str(j)[:120])

    st, j, _ = http("GET", BASE + "/panel/")
    check("panel login page", st == 200 and 'data-view="login"' in (j.get("raw") or ""))

    print("== L2. admin auth ==")
    st, j, _ = panel("login", {"username": ADMIN_USER, "password": "definitely-wrong"})
    check("wrong pw -> 401 uniform", st == 401 and j.get("error", {}).get("code") == "ADMIN_AUTH_FAILED")

    st, j, setc = panel("login", {"username": ADMIN_USER, "password": ADMIN_PASS})
    check("admin login ok", st == 200 and bool(j.get("csrf")), str(j)[:150])
    check("cookie HttpOnly+Strict+Secure", all(x in setc for x in ("HttpOnly", "SameSite=Strict", "secure")))
    if not j.get("csrf"):
        print("FATAL: admin login failed", flush=True)
        return False
    CSRF, COOKIE = j["csrf"], setc.split(";")[0]

    st, j, _ = panel("me", csrf=CSRF, cookie=COOKIE)
    check("me ok", st == 200 and j.get("username") == ADMIN_USER)

    st, j, _ = panel("stats", omit_xpanel=True, csrf=CSRF, cookie=COOKIE)
    check("missing X-Panel -> 403", st == 403)

    st, j, _ = panel("stats", csrf="f" * 64, cookie=COOKIE)
    check("bad csrf -> 403", st == 403 and j.get("error", {}).get("code") == "CSRF_FAILED")

    st, j, _ = panel("stats", csrf=CSRF, cookie=COOKIE)
    check("stats live", st == 200 and j.get("users", {}).get("total", 0) >= 100, str(j)[:150])

    st, j, _ = panel("users_list", {"q": PROBE1["phone"]}, csrf=CSRF, cookie=COOKIE)
    check("users_list finds probe1", st == 200 and len(j.get("items", [])) == 1)
    P1_ID = j["items"][0]["id"] if j.get("items") else 0

    st, j, _ = panel("users_list", {"q": PROBE2["phone"]}, csrf=CSRF, cookie=COOKIE)
    check("users_list finds probe2", st == 200 and len(j.get("items", [])) == 1)
    P2_ID = j["items"][0]["id"] if j.get("items") else 0

    st, j, _ = panel("media_list", csrf=CSRF, cookie=COOKIE)
    check("media_list live", st == 200 and "items" in j)

    st, j, _ = panel("groups_list", csrf=CSRF, cookie=COOKIE)
    check("groups_list live", st == 200 and "items" in j)

    st, j, _ = panel("audit_list", csrf=CSRF, cookie=COOKIE)
    check("audit_list live", st == 200 and "items" in j)

    print("== L3. disable enforcement ==")
    # re-entrancy: repair partial state from previous runs
    panel("user_enable", {"user_id": P1_ID}, csrf=CSRF, cookie=COOKIE)
    panel("user_enable", {"user_id": P2_ID}, csrf=CSRF, cookie=COOKIE)
    st, j, _ = panel("groups_list", {"q": "T84 Live Group"}, csrf=CSRF, cookie=COOKIE)
    for g in j.get("items", []):
        panel("group_delete", {"chat_id": g["id"]}, csrf=CSRF, cookie=COOKIE)

    st, j, _ = http("POST", BASE + "/api/v1/auth/login.php", PROBE1)
    check("probe1 login pre-disable", st == 200 and j.get("access_token"), str(j)[:120])
    P1H = {"Authorization": "Bearer " + j.get("access_token", "")}
    P1_REFRESH = j.get("refresh_token", "")

    st, j, _ = panel("user_disable", {"user_id": P1_ID, "reason": "T84 live e2e"}, csrf=CSRF, cookie=COOKIE)
    check("admin disables probe1", st == 200 and j.get("ok") is True, str(j)[:150])

    st, j, _ = http("POST", BASE + "/api/v1/auth/me.php", {}, P1H)
    check("access token dead -> ACCOUNT_DISABLED", st == 403 and j.get("error", {}).get("code") == "ACCOUNT_DISABLED", str(j)[:150])

    st, j, _ = http("POST", BASE + "/api/v1/auth/login.php", PROBE1)
    check("login blocked -> ACCOUNT_DISABLED", st == 403 and j.get("error", {}).get("code") == "ACCOUNT_DISABLED", str(j)[:150])

    st, j, _ = http("POST", BASE + "/api/v1/auth/refresh.php", {"refresh_token": P1_REFRESH})
    check("refresh blocked", st in (401, 403), str(j)[:120])

    st, j, _ = panel("users_list", {"status": "disabled"}, csrf=CSRF, cookie=COOKIE)
    check("disabled filter shows probe1", st == 200 and any(u["id"] == P1_ID for u in j.get("items", [])))

    st, j, _ = panel("user_enable", {"user_id": P1_ID}, csrf=CSRF, cookie=COOKIE)
    check("admin re-enables probe1", st == 200 and j.get("ok") is True)

    st, j, _ = http("POST", BASE + "/api/v1/auth/login.php", PROBE1)
    check("probe1 login again ok", st == 200 and j.get("access_token"))
    P1H = {"Authorization": "Bearer " + j.get("access_token", "")}

    print("== L4. group management ==")
    st, j, _ = http("POST", BASE + "/api/v1/chats/create.php", {"type": "group", "title": "T84 Live Group"}, P1H)
    check("probe1 creates group", st == 200 and j.get("chat", {}).get("id"), str(j)[:150])
    CHAT = j.get("chat", {}).get("id", 0)

    st, j, _ = http("POST", BASE + "/api/v1/chats/add-member.php", {"chat_id": CHAT, "user_id": P2_ID}, P1H)
    check("probe2 added", st == 200, str(j)[:120])

    st, j, _ = panel("groups_list", {"q": "T84 Live Group"}, csrf=CSRF, cookie=COOKIE)
    g = next((x for x in j.get("items", []) if x["id"] == CHAT), None)
    check("groups_list shows it (members=2)", st == 200 and g is not None and g["member_count"] == 2, str(j)[:200])

    st, j, _ = panel("group_get", {"chat_id": CHAT}, csrf=CSRF, cookie=COOKIE)
    check("group_get members", st == 200 and len(j.get("members", [])) == 2)

    st, j, _ = panel("group_ban", {"chat_id": CHAT, "user_id": P2_ID}, csrf=CSRF, cookie=COOKIE)
    check("admin bans probe2", st == 200 and j.get("was_member") is True)

    st, j, _ = http("POST", BASE + "/api/v1/chats/add-member.php", {"chat_id": CHAT, "user_id": P2_ID}, P1H)
    check("re-add banned -> USER_BANNED", st == 403 and j.get("error", {}).get("code") == "USER_BANNED", str(j)[:150])

    st, j, _ = panel("group_unban", {"chat_id": CHAT, "user_id": P2_ID}, csrf=CSRF, cookie=COOKIE)
    check("admin unbans probe2", st == 200)

    st, j, _ = http("POST", BASE + "/api/v1/chats/add-member.php", {"chat_id": CHAT, "user_id": P2_ID}, P1H)
    check("re-add after unban ok", st == 200, str(j)[:120])

    st, j, _ = panel("group_delete", {"chat_id": CHAT}, csrf=CSRF, cookie=COOKIE)
    check("admin deletes group", st == 200 and j.get("ok") is True)

    st, j, _ = http("POST", BASE + "/api/v1/messages/history.php?chat_id=" + str(CHAT), {}, P1H)
    check("history of deleted group -> 403", st == 403, str(j)[:120])

    st, j, _ = panel("groups_list", {"q": "T84 Live Group"}, csrf=CSRF, cookie=COOKIE)
    check("group gone from list", st == 200 and all(x["id"] != CHAT for x in j.get("items", [])))

    print("== L5. audit ==")
    st, j, _ = panel("audit_list", csrf=CSRF, cookie=COOKIE)
    actions = [a["action"] for a in j.get("items", [])]
    need = {"user.disable", "user.enable", "group.ban", "group.unban", "group.delete"}
    check("audit has all T84 actions", need <= set(actions), str(actions[:12]))
    check("audit rows carry ip", all(a.get("ip") for a in j.get("items", []) if a["action"].startswith(("user.", "group."))))

    st, j, _ = panel("user_get", {"user_id": P1_ID}, csrf=CSRF, cookie=COOKIE)
    blob = json.dumps(j.get("chats", []))
    check("user_get = metadata only (no content/media keys)", st == 200
          and "content" not in blob and "media" not in blob)

    print(f"\nRESULT: {PASS_N} passed, {FAIL_N} failed")
    return FAIL_N == 0


ok = main()
sys.exit(0 if ok else 1)

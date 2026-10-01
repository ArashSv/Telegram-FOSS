#!/usr/bin/env python3
"""Surgical version bump of the LIVE config/config.php ('version' => '2.9.3').

Same backup discipline as deploy_live.py: the current file is copied to
<same path>.bak_pre_v293 on the server (only once) before the PUT.
"""
import os
import re
import subprocess
import sys
import time

WEB = os.environ.get("WD_URL", "https://cip19.mizbanfadns.net:2078")
AUTH = os.environ.get("WD_USER", "xorbitir") + ":" + os.environ.get("WD_PASS", "OwG1+++-fsE5+UZAQW_@#$-(")
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36"
PATH = "/config/config.php"
NEW_VERSION = "2.9.3"
BAK = PATH + ".bak_pre_v293"


def wd(method, path, out=None, inp=None):
    cmd = ["curl", "-sS", "-m", "60", "-k", "-u", AUTH, "-X", method, "-H", "User-Agent: " + UA]
    if inp is not None:
        cmd += ["--data-binary", "@" + inp, "-H", "Content-Type: application/octet-stream"]
    if out:
        cmd += ["-o", out]
    cmd.append(WEB + path)
    code = "000"
    for attempt in range(5):
        p = subprocess.run(cmd, capture_output=True)
        code = p.stdout.decode(errors="replace").strip()[-3:] if out is None else "200"
        if out is None:
            pass
        r = subprocess.run(["curl", "-sS", "-m", "60", "-k", "-u", AUTH, "-X", method,
                            "-H", "User-Agent: " + UA] +
                           (["--data-binary", "@" + inp, "-H", "Content-Type: application/octet-stream"] if inp else []) +
                           (["-o", out] if out else []) +
                           ["-w", "%{http_code}", WEB + path], capture_output=True)
        # The server writes a body like "Resource Created" BEFORE curl's
        # %{http_code} with NO newline separator; HTTP codes are always the
        # final 3 characters of the output, so parse from the tail.
        code = r.stdout.decode(errors="replace").strip()[-3:]
        if code.startswith(("2", "3")):
            return True
        time.sleep(1 + attempt)
    print(f"WD FAIL {method} {path} code={code}")
    return False


def main():
    tmp = "/tmp/config_live.php"
    if not wd("GET", PATH, out=tmp):
        print("FAIL: could not GET live config")
        return 1
    text = open(tmp, encoding="utf-8").read()
    m = re.search(r"('version'\s*=>\s*')([^']+)(')", text)
    if not m:
        print("FAIL: version line not found in live config")
        return 1
    old = m.group(2)
    if old == NEW_VERSION:
        print(f"version already {NEW_VERSION}; nothing to do")
        return 0
    # backup (only once)
    bakprobe = "/tmp/config_bak_probe"
    bak = subprocess.run(["curl", "-sS", "-m", "60", "-k", "-u", AUTH, "-X", "GET",
                          "-H", "User-Agent: " + UA, "-w", "%{http_code}", "-o", bakprobe,
                          WEB + BAK], capture_output=True)
    bakcode = bak.stdout.decode(errors="replace").strip().rsplit("\n", 1)[-1]
    if not bakcode.startswith("2"):
        if not wd("PUT", BAK, inp=tmp):
            print("FAIL: could not create backup")
            return 1
        print(f"backup created: {BAK} (was version {old})")
    else:
        print(f"backup already exists: {BAK}")
    new_text = re.sub(r"('version'\s*=>\s*')([^']+)(')", r"\g<1>" + NEW_VERSION + r"\g<3>", text, count=1)
    open(tmp, "w", encoding="utf-8").write(new_text)
    if not wd("PUT", PATH, inp=tmp):
        print("FAIL: could not PUT updated config")
        return 1
    print(f"version bumped on server: {old} -> {NEW_VERSION}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

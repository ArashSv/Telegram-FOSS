#!/usr/bin/env python3
"""Deploy .github/backend-ops/live/* to the live server via WebDAV (from Actions).

Local layout (argv[1]):
  <dir>/app/...      -> WebDAV /app/...
  <dir>/public/...   -> WebDAV /public_html/tele/...
  <dir>/config/...   -> WebDAV /config/...

For every local file:
  (a) GET the server original (with retries). If 200, PUT it to
      <server-path>.bak_pre_v293 unless that backup already exists (GET check).
      If the original GET is 404, that's fine (new file, nothing to back up).
  (b) PUT the new content (Content-Type: application/octet-stream).

Every action is printed. Any failure is collected and the script exits nonzero
at the END (all files are attempted first), with a summary.
"""
import os
import subprocess
import sys
import urllib.parse

WEB = os.environ.get("WD_URL", "https://cip19.mizbanfadns.net:2078").rstrip("/")
AUTH = (os.environ.get("WD_USER", "xorbitir") + ":" +
        os.environ.get("WD_PASS", "OwG1+++-fsE5+UZAQW_@#$-("))
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36"
LOCAL = sys.argv[1] if len(sys.argv) > 1 else ".github/backend-ops/live"
MAP = {"app": "/app", "public": "/public_html/tele", "config": "/config"}
BACKUP_SUFFIX = ".bak_pre_v293"


def q(path):
    return urllib.parse.quote(path, safe="/")


def curl(method, server_path, extra=(), tries=1, timeout=30):
    """One HTTP op against the WebDAV server; returns (code, body). No retry loop."""
    cmd = ["curl", "-sS", "-m", str(timeout), "-k", "-u", AUTH, "-X", method,
           "-A", UA, "-H", "User-Agent: " + UA] + list(extra) + \
          [WEB + q(server_path), "-w", "\n%{http_code}"]
    p = subprocess.run(cmd, capture_output=True, timeout=timeout + 15)
    body, _, code = p.stdout.decode(errors="replace").rpartition("\n")
    return code.strip(), body


def curl_retry(method, server_path, extra=(), tries=6):
    last = ("000", "")
    for i in range(tries):
        code, body = curl(method, server_path, extra)
        if code.startswith(("2", "3")) or code == "404":
            return code, body
        last = (code, body)
        import time
        time.sleep(0.8 + 0.7 * i)
    return last


def main():
    jobs = []  # (relative_display, local_path, server_path)
    for sub, server_root in MAP.items():
        base = os.path.join(LOCAL, sub)
        if not os.path.isdir(base):
            print(f"NOTE: no local '{sub}/' to deploy (skipped)", flush=True)
            continue
        for root, _, files in os.walk(base):
            for f in sorted(files):
                lp = os.path.join(root, f)
                rel = os.path.relpath(lp, base).replace(os.sep, "/")
                jobs.append((f"{sub}/{rel}", lp, server_root + "/" + rel))
    if not jobs:
        print(f"ERROR: nothing to deploy under {LOCAL} "
              f"(expected subdirs: {', '.join(MAP)})", flush=True)
        return 2
    print(f"deploy plan: {len(jobs)} file(s) from {LOCAL}", flush=True)

    failures = []
    for display, lp, sp in jobs:
        print(f"--- {display} -> {sp}", flush=True)

        # (a) back up the server original
        code, _ = curl_retry("GET", sp)
        if code.startswith("2"):
            bak = sp + BACKUP_SUFFIX
            bcode, _ = curl("GET", bak)
            if bcode.startswith("2"):
                print(f"    backup exists, skipping backup PUT: {bak}", flush=True)
            else:
                bcode2, _ = curl_retry("PUT", bak,
                                       extra=["--data-binary", "@" + lp,
                                              "-H", "Content-Type: application/octet-stream"])
                print(f"    backup PUT {bak}: HTTP {bcode2}", flush=True)
                if not bcode2.startswith("2"):
                    failures.append((display, f"backup PUT {bak} -> {bcode2}"))
                    continue
        elif code == "404":
            print(f"    original GET 404 (new file, no backup needed)", flush=True)
        else:
            print(f"    original GET FAILED: HTTP {code} -- NOT overwriting", flush=True)
            failures.append((display, f"original GET {sp} -> {code}"))
            continue

        # (b) deploy the new content
        pcode, _ = curl_retry("PUT", sp,
                              extra=["--data-binary", "@" + lp,
                                     "-H", "Content-Type: application/octet-stream"])
        print(f"    deploy PUT {sp}: HTTP {pcode}", flush=True)
        if not pcode.startswith("2"):
            failures.append((display, f"deploy PUT {sp} -> {pcode}"))

    print(f"SUMMARY {len(jobs) - len(failures)}/{len(jobs)} deployed OK", flush=True)
    if failures:
        for display, why in failures:
            print(f"  FAILURE {display}: {why}", flush=True)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

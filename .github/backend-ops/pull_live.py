#!/usr/bin/env python3
"""Pull the LIVE backend via WebDAV (runs on GitHub Actions, which can reach the host).

Walks /app, /config and /public_html/tele (the user's home dir = WebDAV root) with
Depth:1 PROPFIND walks (Depth:infinity is rejected by the server), downloads every
file with retries into the output dir preserving the path layout, skips files
larger than 5MB, then writes _inventory.txt and prints counts.

Adapted from the proven scripts/t53b_pull_home_app.py pattern
(curl_once / with_retry / propfind_dir kept nearly verbatim).
"""
import os
import re
import subprocess
import sys
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor

WEB = os.environ.get("WD_URL", "https://cip19.mizbanfadns.net:2078").rstrip("/")
AUTH = (os.environ.get("WD_USER", "xorbitir") + ":" +
        os.environ.get("WD_PASS", "OwG1+++-fsE5+UZAQW_@#$-("))
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36"
OUT = sys.argv[1] if len(sys.argv) > 1 else "out/tree"
SRC_DIRS = ["/app", "/config", "/public_html/tele"]
MAX_BYTES = 5 * 1024 * 1024

TOKEN_RE = re.compile(r"<(?:[A-Za-z0-9]+:)?href>(.*?)</(?:[A-Za-z0-9]+:)?href>")


def norm(href):
    """Server href -> decoded absolute path starting with '/' (collections keep their '/')."""
    h = href.strip()
    if h.lower().startswith("http"):
        h = urllib.parse.urlsplit(h).path
    h = urllib.parse.unquote(h)
    return h if h.startswith("/") else "/" + h


def curl_once(method, path, out=None, depth=None, timeout=25):
    cmd = ["curl", "-sS", "-m", str(timeout), "-k", "-u", AUTH, "-X", method,
           "-A", UA, "-H", "User-Agent: " + UA]
    if depth is not None:
        cmd += ["-H", f"Depth: {depth}"]
    if out:
        cmd += ["-o", out]
    # hrefs are kept decoded internally; re-quote for the wire (safe="/" keeps slashes)
    cmd += [WEB + urllib.parse.quote(path, safe="/"), "-w", "\n%{http_code}"]
    p = subprocess.run(cmd, capture_output=True, timeout=timeout + 15)
    body, _, code = p.stdout.decode(errors="replace").rpartition("\n")
    return code.strip(), body


def with_retry(fn, tries=8):
    last = ("000", "")
    for i in range(tries):
        code, body = fn()
        if code.startswith(("2", "3")):
            return code, body
        last = (code, body)
        time.sleep(0.8 + 0.7 * i)
    return last


def propfind_dir(path):
    code, body = with_retry(lambda: curl_once("PROPFIND", path, depth=1))
    if not code.startswith("207"):
        return None
    self_path = norm(path).rstrip("/")
    children = [norm(m.group(1)) for m in TOKEN_RE.finditer(body)]
    return [p for p in children if p.rstrip("/") != self_path]


def main():
    pool = ThreadPoolExecutor(max_workers=8)
    all_files = []          # decoded absolute server paths
    skipped = []
    seen, queue = set(), [d if d.endswith("/") else d + "/" for d in SRC_DIRS]

    while queue:
        batch = queue[:]
        queue = []
        seen.update(batch)
        for d, children in pool.map(lambda x: (x, propfind_dir(x)), batch):
            if children is None:
                print(f"  PROPFAIL {d}", flush=True)
                continue
            for href in children:
                if href.endswith("/"):
                    if href not in seen:
                        queue.append(href)
                else:
                    all_files.append(href)

    all_files = sorted(set(all_files))
    print(f"live backend files under {' '.join(SRC_DIRS)}: {len(all_files)}", flush=True)

    def fetch(href):
        dest = OUT.rstrip("/") + href
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        code, _ = with_retry(lambda: curl_once("GET", href, out=dest))
        if code.startswith("2") and os.path.isfile(dest):
            if os.path.getsize(dest) > MAX_BYTES:
                os.remove(dest)
                return href, code, "skipped>5MB"
            return href, code, "ok"
        return href, code, "fail"

    ok = fail = 0
    results = []
    for href, code, status in pool.map(fetch, all_files):
        results.append((href, status))
        if status == "ok":
            ok += 1
        elif status == "skipped>5MB":
            skipped.append(href)
        else:
            fail += 1
            print(f"  GET FAIL {href} HTTP {code}", flush=True)
    print(f"  downloaded {ok}, skipped>5MB {len(skipped)}, failed {fail}", flush=True)

    os.makedirs(OUT, exist_ok=True)
    inv = os.path.join(OUT, "_inventory.txt")
    with open(inv, "w", encoding="utf-8") as f:
        for href, status in sorted(results):
            if status == "ok":
                size = os.path.getsize(OUT.rstrip("/") + href)
                f.write(f"{href} {size}\n")
            else:
                f.write(f"{href} {status}\n")
    print(f"inventory written: {inv}", flush=True)
    print(f"SUMMARY files={ok} skipped={len(skipped)} failed={fail}", flush=True)


if __name__ == "__main__":
    main()

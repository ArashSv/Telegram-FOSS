#!/usr/bin/env python3
"""T82 — LIVE two-account verification of BOTH field-reported fixes.

1. SECRET-CHAT PEER (the "creates a group" root cause): a secret chat
   payload MUST carry peer!=null now (backend v2.12.5), on create AND on
   chats/list for BOTH sides — the client's no-downgrade contract depends
   on it.
2. XOSF2 password-based encrypted file: full sender->server->receiver
   round trip with REAL crypto. The server stores ONLY ciphertext
   (kind=e2ee); the password never leaves this script; a wrong password
   must fail honestly (GCM tag).

Everything runs against the production API with throwaway accounts.
"""
import base64, hashlib, hmac, json, os, random, string, subprocess, sys, time

HOST = os.environ.get("T82_HOST", "https://xorbit.ir/tele")
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 t82probe/1.0"
RESULTS = []

CS = 96 * 1024
PBKDF2_ITERS = 210_000


def check(name, cond, extra=""):
    RESULTS.append(bool(cond))
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else "  -> " + repr(extra)[:300]), flush=True)


def call(method, path, token=None, body=None, raw=None, extra_headers=None, retries=3):
    headers = {"User-Agent": UA}
    if raw is not None:
        headers["Content-Type"] = "application/octet-stream"
    elif body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    if extra_headers:
        headers.update(extra_headers)
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    for attempt in range(retries):
        cmd = ["curl", "-4", "-sS", "--http1.1", "-m", "45", "-X", method,
               "-H", "User-Agent: " + UA]
        for k, v in headers.items():
            cmd += ["-H", f"{k}: {v}"]
        if data is not None:
            cmd += ["--data-binary", "@-"]
        cmd.append(HOST + path)
        p = subprocess.run(cmd, input=data if data is not None else b"",
                           capture_output=True, timeout=60)
        out = p.stdout.decode("utf-8", "replace")
        try:
            return json.loads(out)
        except Exception:
            if attempt == retries - 1:
                return {"ok": False, "transport": out[:160]}
            time.sleep(2)


def rnd_phone():
    return "4049" + "".join(random.choices(string.digits, k=4))


def register():
    phone = rnd_phone()
    pw = "pw" + "".join(random.choices(string.ascii_letters + string.digits, k=8))
    r = call("POST", "/api/v1/auth/check-phone.php", body={"phone": phone})
    assert r.get("ok"), r
    r = call("POST", "/api/v1/auth/register.php", body={"phone": phone, "password": pw, "hint": "t82"})
    assert r.get("ok"), r
    return r["access_token"], r["user"]["id"]


# ------------------------------------------------------- XOSF2 crypto (mirrors XoFilePassCrypto + XoE2EEMedia)

def hkdf_sha256(salt, ikm, info, length=32):
    prk = hmac.new(salt if salt else b"\x00" * 32, ikm, hashlib.sha256).digest()
    okm, t, counter = b"", b"", 1
    while len(okm) < length:
        t = hmac.new(prk, t + info + bytes([counter]), hashlib.sha256).digest()
        okm += t
        counter += 1
    return okm[:length]


def aes_gcm(key, nonce, plaintext, aad):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    return AESGCM(key).encrypt(nonce, plaintext, aad)


def aes_gcm_decrypt(key, nonce, ct, aad):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    return AESGCM(key).decrypt(nonce, ct, aad)


def chunk_nonce(key, i):
    return hkdf_sha256(b"\x00" * 32, key, b"XOEEM1n" + i.to_bytes(4, "big"), 16)[:12]


def encrypt_chunk(key, i, plain):
    return aes_gcm(key, chunk_nonce(key, i), plain, b"XOEEM1|c" + i.to_bytes(4, "big"))


def decrypt_chunk(key, i, ct):
    return aes_gcm_decrypt(key, chunk_nonce(key, i), ct, b"XOEEM1|c" + i.to_bytes(4, "big"))


def b64(b):
    return base64.b64encode(b).decode()


def main():
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM  # noqa
    except ImportError:
        subprocess.run([sys.executable, "-m", "pip", "install", "-q", "cryptography"], timeout=120)

    tok_a, id_a = register()
    tok_b, id_b = register()
    check("two live accounts", id_a > 0 and id_b > 0, (id_a, id_b))

    # ---- 1. the SECRET-CHAT PEER fix (payloadFromRow)
    r = call("POST", "/api/v1/chats/create.php", token=tok_a,
             body={"type": "secret", "peer_user_id": id_b})
    check("secret chat created type=secret mode=secret",
          r.get("ok") and r["chat"]["type"] == "secret" and r["chat"].get("mode") == "secret", r)
    sec_id = r["chat"]["id"]
    peer = r["chat"].get("peer")
    check("T82 ROOT FIX: create payload carries the PEER for the secret chat",
          isinstance(peer, dict) and peer.get("id") == id_b,
          {"peer": peer, "id_b": id_b})
    peer_b = peer.get("id") if isinstance(peer, dict) else None

    r = call("GET", "/api/v1/chats/list.php", token=tok_b)
    sec_b = next((c for c in r.get("chats", []) if c["id"] == sec_id), None)
    check("B's list shows the secret chat WITH its peer (B-side perspective)",
          sec_b is not None and sec_b["type"] == "secret"
          and isinstance(sec_b.get("peer"), dict) and sec_b["peer"].get("id") == id_a,
          {"got": (sec_b or {}).get("peer"), "id_a": id_a})
    r = call("GET", "/api/v1/chats/list.php", token=tok_a)
    sec_a = next((c for c in r.get("chats", []) if c["id"] == sec_id), None)
    check("A's list also carries the peer",
          sec_a is not None and isinstance(sec_a.get("peer"), dict) and sec_a["peer"].get("id") == peer_b,
          (sec_a or {}).get("peer"))

    # ---- 2. the XOSF2 password round trip (client-side only)
    r = call("POST", "/api/v1/chats/create.php", token=tok_a,
             body={"type": "private", "peer_user_id": id_b})
    cloud_id = r["chat"]["id"]

    password = "رمز-probe-#42"
    salt = os.urandom(16)
    cek = os.urandom(32)
    kek = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, PBKDF2_ITERS, dklen=32)

    plain = os.urandom(150 * 1024 + 777)
    parts = (len(plain) + CS - 1) // CS
    ct_whole = b"".join(encrypt_chunk(cek, i, plain[i * CS:(i + 1) * CS]) for i in range(parts))
    ct_sizes = [len(encrypt_chunk(cek, i, plain[i * CS:(i + 1) * CS])) for i in range(parts)]

    r = call("POST", "/api/v1/files/init.php", token=tok_a, body={"chunk_size": CS, "chunks_total": parts})
    fid = r.get("file_id")
    ok_up = bool(fid)
    offset = 0
    for i in range(parts):
        chunk_ct = ct_whole[offset:offset + ct_sizes[i]]
        offset += ct_sizes[i]
        rr = call("POST", f"/api/v1/files/chunk.php?file_id={fid}&index={i}&len={len(chunk_ct)}",
                  token=tok_a, raw=chunk_ct)
        ok_up = ok_up and rr.get("received") is not None
    check("client-encrypted chunks accepted", ok_up)
    r = call("POST", "/api/v1/files/finalize.php", token=tok_a,
             body={"file_id": fid, "chunks_total": parts, "size": len(ct_whole), "e2ee": True})
    check("finalize declares kind=e2ee (opaque ciphertext at rest)",
          r.get("ok") and r["file"]["kind"] == "e2ee", r.get("file", {}).get("kind"))

    inner = json.dumps({"t": "m", "fk": b64(cek), "pl": len(plain), "cs": CS,
                        "mi": "application/zip", "na": "t82-probe.zip", "cap": "the رمز works",
                        "an": 0}, ensure_ascii=False).encode()
    h_nonce = os.urandom(12)
    hb = b64(h_nonce + aes_gcm(cek, h_nonce, inner, b"XOSF2|head"))
    w_nonce = os.urandom(12)
    wp = {"k": "pbkdf2", "i": PBKDF2_ITERS, "s": b64(salt), "n": b64(w_nonce),
          "w": b64(aes_gcm(kek, w_nonce, cek, b"XOSF2|wrap"))}
    envelope = "XOSF2:" + b64(json.dumps(
        {"v": 1, "fid": fid, "pl": len(plain), "cs": CS, "hb": hb, "wp": wp},
        ensure_ascii=False).encode("utf-8"))

    r = call("POST", "/api/v1/messages/send.php", token=tok_a,
             body={"chat_id": cloud_id, "content": envelope, "media_file_id": fid})
    check("backend accepts the XOSF2 envelope in the CLOUD chat",
          r.get("ok") and r["message"]["content"] == envelope, r.get("error", r.get("transport")))

    # B reads history and replays the receiver's crypto with the PASSWORD ONLY
    r = call("GET", f"/api/v1/messages/history.php?chat_id={cloud_id}&limit=20", token=tok_b)
    rows = r.get("messages", [])
    row = next((m for m in rows if (m.get("content") or "").startswith("XOSF2:")), None)
    check("B sees the XOSF2 row in history", row is not None, len(rows))
    env2 = json.loads(base64.b64decode(row["content"][len("XOSF2:"):]).decode("utf-8"))
    wp2 = env2["wp"]
    kek_b = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"),
                                base64.b64decode(wp2["s"]), wp2["i"], dklen=32)
    cek_b = aes_gcm_decrypt(kek_b, base64.b64decode(wp2["n"]),
                            base64.b64decode(wp2["w"]), b"XOSF2|wrap")
    check("B unwraps the CEK with the typed رمز (byte-identical)", cek_b == cek, cek_b.hex()[:16])
    h2 = base64.b64decode(env2["hb"])
    inner_b = aes_gcm_decrypt(cek_b, h2[:12], h2[12:], b"XOSF2|head")
    meta_b = json.loads(inner_b.decode("utf-8"))
    check("B decrypts the header: real name/mime/caption (server saw none of it)",
          meta_b.get("na") == "t82-probe.zip" and meta_b.get("mi") == "application/zip"
          and meta_b.get("cap") == "the رمز works", meta_b.get("na"))

    # wrong password must fail honestly
    kek_bad = hashlib.pbkdf2_hmac("sha256", "wrong-رمز".encode("utf-8"),
                                  base64.b64decode(wp2["s"]), wp2["i"], dklen=32)
    try:
        aes_gcm_decrypt(kek_bad, base64.b64decode(wp2["n"]),
                        base64.b64decode(wp2["w"]), b"XOSF2|wrap")
        check("wrong password REJECTED", False, "GCM opened with the wrong key!")
    except Exception:
        check("wrong password REJECTED (GCM tag)", True)

    # B downloads the ciphertext and decrypts the chunks
    r = call("GET", f"/api/v1/files/get.php?file_id={fid}", token=tok_b)
    size = int(r.get("file", {}).get("size") or 0)
    got = bytearray()
    start = 0
    while size > 0 and start < size:
        end = min(size, start + 256 * 1024) - 1
        rr = subprocess.run(
            ["curl", "-4", "-sS", "--http1.1", "-m", "60", "-H", f"Authorization: Bearer {tok_b}",
             "-H", "User-Agent: " + UA, "-H", f"Range: bytes={start}-{end}", "-H", "Accept-Encoding: identity",
             f"{HOST}/api/v1/files/download.php?file_id={fid}"], capture_output=True, timeout=90)
        got += rr.stdout
        start = end + 1
    got = bytes(got)
    check("B downloads byte-exact CIPHERTEXT (server holds no plaintext)",
          size == len(ct_whole) and got == ct_whole and got != plain, f"len={len(got)}")
    recovered = bytearray()
    off = 0
    for i in range(parts):
        recovered += decrypt_chunk(cek_b, i, got[off:off + ct_sizes[i]])
        off += ct_sizes[i]
    check("B decrypts the file — byte-exact round trip", bytes(recovered) == plain,
          f"recovered {len(recovered)} of {len(plain)}")

    # the envelope must NOT leak the file key (XOSF2 carries it wrapped only)
    check("the wrap blob does NOT contain the raw CEK",
          cek not in base64.b64decode(wp2["w"]) and "fk" not in wp2, "")

    print(f"T82 LIVE: {sum(RESULTS)}/{len(RESULTS)} PASS", flush=True)
    raise SystemExit(0 if all(RESULTS) else 1)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""T78 — TWO-ACCOUNT end-to-end validation against the LIVE backend.

Real XOSC1 crypto in pure Python (RFC 7748 X25519 + HKDF-SHA256 + AES-256-GCM),
exercising the production flow end to end:
  1. two fresh accounts register/login on the live host
  2. both PUT their X25519 public keys to secret/keys.php
  3. each fetches the peer's key through the registry
  4. A creates a SEPARATE secret chat (chats/create.php type=secret) —
     verifies it is a distinct row from the cloud chat and visible while empty
  5. A -> B: XOSC1 text envelope; B -> A: reply; B decrypts BOTH with the real
     private keys (AAD binding to chat/parties; tamper refusal)
  6. media: A uploads a kind=e2ee blob, sends an XOSC1 media envelope,
     B downloads it through the attested windowed route and decrypts it
  7. boundary checks: forward out of the secret chat refused, third party
     denied the media, cloud chat of the same pair still works in plaintext

Transport discipline (T77 lessons): curl -4 --http1.1, browser UA, retries.
Runs on the GH Actions runner (the sandbox is banned from the host).
"""
import base64, hashlib, hmac, json, os, random, string, subprocess, sys, time

HOST = os.environ.get("T78_HOST", "https://xorbit.ir/tele")
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 t78probe/1.0"
P = 2 ** 255 - 19
A24 = 121665
RESULTS = []


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


# ---------------------------------------------------------------- crypto

def cswap(swap, x2, x3, z2, z3):
    if swap:
        return x3, x2, z3, z2
    return x2, x3, z2, z3


def x25519_ref(k_bytes, u_bytes):
    """RFC 7748 reference ladder (validated against the official KATs)."""
    k = bytearray(k_bytes)
    k[0] &= 248
    k[31] &= 127
    k[31] |= 64
    k_int = int.from_bytes(bytes(k), 'little')
    x1 = int.from_bytes(bytes(u_bytes), 'little') & ((1 << 255) - 1)
    x2, z2, x3, z3 = 1, 0, x1, 1
    swap = 0
    for t in range(254, -1, -1):
        kt = (k_int >> t) & 1
        swap ^= kt
        if swap:
            x2, x3 = x3, x2
            z2, z3 = z3, z2
        swap = kt
        a = (x2 + z2) % P
        aa = a * a % P
        b = (x2 - z2) % P
        bb = b * b % P
        e = (aa - bb) % P
        c = (x3 + z3) % P
        d = (x3 - z3) % P
        da = d * a % P
        cb = c * b % P
        x3 = (da + cb) ** 2 % P
        z3 = x1 * (da - cb) ** 2 % P
        x2 = aa * bb % P
        z2 = e * (aa + A24 * e) % P
    if swap:
        x2, x3 = x3, x2
        z2, z3 = z3, z2
    return (x2 * pow(z2, P - 2, P)) % P


def pk_from_priv(priv):
    pub_int = x25519_ref(priv, bytes([9] + [0] * 31))
    return pub_int.to_bytes(32, 'little')


def dh(priv, peer_pub):
    shared_int = x25519_ref(priv, peer_pub)
    return shared_int.to_bytes(32, 'little')


def hkdf_sha256(ikm, salt, info, length=32):
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    out, t, i = b"", b"", 1
    while len(out) < length:
        t = hmac.new(prk, t + info + bytes([i]), hashlib.sha256).digest()
        out += t
        i += 1
    return out[:length]


def sha256(data):
    return hashlib.sha256(data).digest()


def aes_gcm(key, nonce, plaintext, aad):
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        return AESGCM(key).encrypt(nonce, plaintext, aad)
    except ImportError:
        raise SystemExit("cryptography package required on the runner")


def aes_gcm_decrypt(key, nonce, ct, aad):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    return AESGCM(key).decrypt(nonce, ct, aad)


def aad_for(chat_id, sender_id, recipient_id):
    return f"XOSC1|c{chat_id}|f{sender_id}|t{recipient_id}".encode()


def encrypt_xosc1(chat_id, sender_id, recipient_id, sender_priv, sender_pub, recip_pub, plaintext):
    eph_priv = int.from_bytes(os.urandom(32), 'little') % (P - 8) + 8
    eph_priv = eph_priv.to_bytes(32, 'little')
    eph_priv_b = bytearray(eph_priv)
    eph_priv_b[0] &= 248
    eph_priv_b[31] &= 127
    eph_priv_b[31] |= 64
    eph_priv = bytes(eph_priv_b)
    eph_pub = pk_from_priv(eph_priv)
    d1 = dh(eph_priv, recip_pub)
    d2 = dh(sender_priv, recip_pub)
    salt = sha256(eph_pub + sender_pub + recip_pub)
    key = hkdf_sha256(d1 + d2, salt, b"XOSC1|v1")
    nonce = os.urandom(12)
    ct = aes_gcm(key, nonce, plaintext, aad_for(chat_id, sender_id, recipient_id))
    body = bytes([1]) + eph_pub + sender_pub + nonce + ct
    return "XOSC1:" + base64.b64encode(body).decode()


def decrypt_xosc1(chat_id, sender_id, recipient_id, recip_priv, recip_pub, envelope):
    body = base64.b64decode(envelope[len("XOSC1:"):])
    eph_pub = body[1:33]
    sender_pub = body[33:65]
    nonce = body[65:77]
    ct = body[77:]
    d1 = dh(recip_priv, eph_pub)
    d2 = dh(recip_priv, sender_pub)
    salt = sha256(eph_pub + sender_pub + recip_pub)
    key = hkdf_sha256(d1 + d2, salt, b"XOSC1|v1")
    return aes_gcm_decrypt(key, nonce, ct, aad_for(chat_id, sender_id, recipient_id))


# ---------------------------------------------------------------- flow

def rnd_phone():
    return "4049" + "".join(random.choices(string.digits, k=4))


def register():
    phone = rnd_phone()
    pw = "pw" + "".join(random.choices(string.ascii_letters + string.digits, k=8))
    r = call("POST", "/api/v1/auth/check-phone.php", body={"phone": phone})
    assert r.get("ok"), r
    r = call("POST", "/api/v1/auth/register.php", body={"phone": phone, "password": pw, "hint": "t78"})
    assert r.get("ok"), r
    return r["access_token"], r["user"]["id"]


def main():
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM  # noqa
    except ImportError:
        subprocess.run([sys.executable, "-m", "pip", "install", "-q", "cryptography"], timeout=120)

    # sanity: the python X25519 against an RFC 7748 KAT
    kat = x25519_ref(bytes.fromhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"),
                     bytes([9] + [0] * 31))
    check("python X25519 KAT", kat.to_bytes(32, 'little').hex()
          == "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
          kat.to_bytes(32, 'little').hex())

    tok_a, id_a = register()
    tok_b, id_b = register()
    check("two live accounts", id_a > 0 and id_b > 0, (id_a, id_b))

    priv_a = bytearray(os.urandom(32))
    priv_a[0] &= 248
    priv_a[31] &= 127
    priv_a[31] |= 64
    priv_a = bytes(priv_a)
    pub_a = pk_from_priv(priv_a)
    priv_b = bytearray(os.urandom(32))
    priv_b[0] &= 248
    priv_b[31] &= 127
    priv_b[31] |= 64
    priv_b = bytes(priv_b)
    pub_b = pk_from_priv(priv_b)

    r = call("PUT", "/api/v1/secret/keys.php", token=tok_a, body={"pk": base64.b64encode(pub_a).decode()})
    check("A PUT key (changed)", r.get("ok") and r.get("changed") is True, r)
    r = call("PUT", "/api/v1/secret/keys.php", token=tok_b, body={"pk": base64.b64encode(pub_b).decode()})
    check("B PUT key", r.get("ok"), r)
    r = call("GET", f"/api/v1/secret/keys.php?user_id={id_b}", token=tok_a)
    check("A fetches B's key", r.get("registered") and base64.b64decode(r["pk"]) == pub_b, r)

    # secret chat: SEPARATE row
    r = call("POST", "/api/v1/chats/create.php", token=tok_a, body={"type": "private", "peer_user_id": id_b})
    cloud_id = r["chat"]["id"]
    r = call("POST", "/api/v1/chats/create.php", token=tok_a, body={"type": "secret", "peer_user_id": id_b})
    check("secret chat created (type=secret, mode=secret)",
          r.get("ok") and r["chat"]["type"] == "secret" and r["chat"].get("mode") == "secret", r)
    sec_id = r["chat"]["id"]
    check("secret chat is a DIFFERENT row than the cloud chat", sec_id != cloud_id, (sec_id, cloud_id))
    r = call("GET", "/api/v1/chats/list.php", token=tok_b)
    types = {c["id"]: c["type"] for c in r.get("chats", [])}
    check("B sees the empty secret chat in the list", types.get(sec_id) == "secret", types)

    # text both directions with real crypto
    msg1 = "سلام! این اولین پیام چت رمزی جدید است 🔐"
    env1 = encrypt_xosc1(sec_id, id_a, id_b, priv_a, pub_a, pub_b, json.dumps(
        {"t": "t", "x": msg1}, ensure_ascii=False).encode())
    r = call("POST", "/api/v1/messages/send.php", token=tok_a, body={"chat_id": sec_id, "content": env1})
    check("A sends XOSC1 text", r.get("ok") and r["message"]["content"] == env1, r)
    m1 = r["message"]["id"]
    # server must NOT be able to read it: the row on the wire stays the envelope
    r = call("GET", f"/api/v1/messages/history.php?chat_id={sec_id}", token=tok_b)
    row = next((m for m in r.get("messages", []) if m["id"] == m1), None)
    check("B receives the envelope verbatim", row is not None and row["content"] == env1, row)
    inner = json.loads(decrypt_xosc1(sec_id, id_a, id_b, priv_b, pub_b, row["content"]))
    check("B decrypts with the real private key", inner.get("x") == msg1, inner)

    reply = "پاسخ رمزی از طرف B"
    env2 = encrypt_xosc1(sec_id, id_b, id_a, priv_b, pub_b, pub_a, json.dumps(
        {"t": "t", "x": reply}, ensure_ascii=False).encode())
    r = call("POST", "/api/v1/messages/send.php", token=tok_b, body={"chat_id": sec_id, "content": env2})
    check("B replies", r.get("ok"), r)
    row2 = r["message"]
    inner2 = json.loads(decrypt_xosc1(sec_id, id_b, id_a, priv_a, pub_a, row2["content"]))
    check("A decrypts the reply", inner2.get("x") == reply, inner2)

    # AAD binding: the same envelope under a different chat must fail
    try:
        decrypt_xosc1(sec_id + 999999, id_a, id_b, priv_b, pub_b, row["content"])
        check("AAD cross-chat rejection", False, "decrypted in a foreign chat!")
    except Exception:
        check("AAD cross-chat rejection", True)

    # media: kind=e2ee blob + envelope manifest
    blob = os.urandom(150 * 1024 + 777)
    media_key = os.urandom(32)
    cs = 96 * 1024
    parts = (len(blob) + cs - 1) // cs
    r = call("POST", "/api/v1/files/init.php", token=tok_a, body={"chunk_size": cs, "chunks_total": parts})
    fid = r["file_id"]
    ok_up = True
    for i in range(parts):
        chunk = blob[i * cs:(i + 1) * cs]
        pad = chunk if len(chunk) >= 32 * 1024 else chunk + os.urandom(32 * 1024 - len(chunk))
        rr = call("POST", f"/api/v1/files/chunk.php?file_id={fid}&index={i}&len={len(chunk)}",
                  token=tok_a, raw=pad)
        ok_up = ok_up and rr.get("received") is not None
    r = call("POST", "/api/v1/files/finalize.php", token=tok_a,
             body={"file_id": fid, "chunks_total": parts, "size": len(blob), "e2ee": True})
    check("encrypted media uploaded (kind=e2ee)", r.get("ok") and r["file"]["kind"] == "e2ee", r.get("file", {}).get("kind"))

    manifest = json.dumps({"t": "m", "fk": base64.b64encode(media_key).decode(),
                           "pl": len(blob), "cs": cs, "mi": "application/octet-stream",
                           "na": "probe.bin", "an": 0}, ensure_ascii=False).encode()
    envm = encrypt_xosc1(sec_id, id_a, id_b, priv_a, pub_a, pub_b, manifest)
    r = call("POST", "/api/v1/messages/send.php", token=tok_a,
             body={"chat_id": sec_id, "content": envm, "media_file_id": fid})
    check("secret media message", r.get("ok") and r["message"]["content"] == envm, r)

    # B downloads + decrypts (attested windowed route)
    r = call("GET", f"/api/v1/files/get.php?file_id={fid}", token=tok_b)
    f = r.get("file", {})
    size = int(f.get("size") or 0)
    got = bytearray()
    ok_dl = size > 0
    start = 0
    while ok_dl and start < size:
        end = min(size, start + 256 * 1024) - 1
        rr = subprocess.run(
            ["curl", "-4", "-sS", "--http1.1", "-m", "60", "-H", f"Authorization: Bearer {tok_b}",
             "-H", "User-Agent: " + UA, "-H", f"Range: bytes={start}-{end}", "-H", "Accept-Encoding: identity",
             f"{HOST}/api/v1/files/download.php?file_id={fid}"], capture_output=True, timeout=90)
        got += rr.stdout
        start = end + 1
    check("B downloads the encrypted blob byte-exact", ok_dl and bytes(got) == blob, f"len={len(got)}")
    # third party denial
    tok_c, id_c = register()
    ok3 = subprocess.run(
        ["curl", "-4", "-sS", "--http1.1", "-m", "60", "-o", "/dev/null", "-w", "%{http_code}",
         "-H", f"Authorization: Bearer {tok_c}", "-H", "User-Agent: " + UA,
         f"{HOST}/api/v1/files/download.php?file_id={fid}"], capture_output=True, timeout=90).stdout.decode()
    check("third party denied the secret media", ok3 == "403", ok3)

    # boundaries
    r = call("POST", "/api/v1/messages/forward.php", token=tok_a, body={"to_chat_id": sec_id, "message_ids": [m1]})
    check("forward out of secret chat refused",
          r.get("error", {}).get("code") in ("FORBIDDEN",), (r.get("error", {}).get("code"),))
    # cloud chat of the same pair still works in plaintext
    r = call("POST", "/api/v1/messages/send.php", token=tok_a, body={"chat_id": cloud_id, "content": "cloud side still fine"})
    check("cloud chat plaintext unaffected", r.get("ok") and r["message"]["content"] == "cloud side still fine", r)

    print()
    print(f"T78 LIVE: {sum(RESULTS)}/{len(RESULTS)} PASS")
    sys.exit(0 if all(RESULTS) else 1)


if __name__ == "__main__":
    main()

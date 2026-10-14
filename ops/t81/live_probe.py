#!/usr/bin/env python3
"""T81 — TWO-ACCOUNT end-to-end validation of the ENCRYPTED-FILE feature
against the LIVE backend (v2.12.4).

Real crypto in pure Python (RFC 7748 X25519 + HKDF + AES-256-GCM), exercising
the exact production wire contract:
  1. two fresh accounts register/login + publish their X25519 static keys
  2. BOTH sides independently derive THE one symmetric file key
     (static-static ECDH, canonical salt by user id, info "XOSF1|file v1")
     — the derivation must agree without any key transport
  3. A encrypts a blob in the XoE2EEMedia chunk format under the DERIVED
     key, uploads it (kind='e2ee' — the server stores ciphertext) and sends
     the XOSF1 envelope (NO key inside, only "kf":"ss" + metadata) into the
     pair's CLOUD chat
  4. B pulls the row, opens the XOSF1 envelope (canonical pair-low-id AAD),
     re-derives the file key from the envelope-carried sender pub, downloads
     the blob through the attested windowed route and decrypts it — byte-exact
  5. the served blob IS ciphertext (the server really holds no plaintext)
  6. mode-boundary guards: XOSC1 still refused in the cloud chat;
     XOSF1 refused in a secret chat; plaintext cloud text unaffected

Transport discipline (T77 lessons): curl -4 --http1.1, retries.
Runs on the GH Actions runner (the sandbox is banned from the host).
"""
import base64, hashlib, hmac, json, os, random, string, subprocess, sys, time

HOST = os.environ.get("T81_HOST", "https://xorbit.ir/tele")
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 t81probe/1.0"
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


# ---------------------------------------------------------------- crypto (mirrors the client exactly)

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
    return x25519_ref(priv, peer_pub).to_bytes(32, 'little')


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
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    return AESGCM(key).encrypt(nonce, plaintext, aad)


def aes_gcm_decrypt(key, nonce, ct, aad):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    return AESGCM(key).decrypt(nonce, ct, aad)


def derive_file_key(self_id, self_priv, self_pub, peer_id, peer_pub):
    """XoSecretCrypto.deriveFileKey — THE one symmetric key of the pair."""
    d = dh(self_priv, peer_pub)
    self_low = self_id <= peer_id
    lo_pub = self_pub if self_low else peer_pub
    hi_pub = peer_pub if self_low else self_pub
    lo_id = str(self_id if self_low else peer_id).encode()
    hi_id = str(peer_id if self_low else self_id).encode()
    prefix = b"XOSF1"
    salt_input = prefix + len(lo_id).to_bytes(2, 'big') + lo_id + lo_pub \
        + len(hi_id).to_bytes(2, 'big') + hi_id + hi_pub
    return hkdf_sha256(d, sha256(salt_input), b"XOSF1|file v1")


def encrypt_xosf1(ctx, sender_id, recipient_id, s_priv, s_pub, r_pub, plaintext):
    eph_priv = int.from_bytes(os.urandom(32), 'little') % (P - 8) + 8
    eph_priv = bytearray(eph_priv.to_bytes(32, 'little'))
    eph_priv[0] &= 248
    eph_priv[31] &= 127
    eph_priv[31] |= 64
    eph_priv = bytes(eph_priv)
    eph_pub = pk_from_priv(eph_priv)
    d1 = dh(eph_priv, r_pub)
    d2 = dh(s_priv, r_pub)
    salt = sha256(eph_pub + s_pub + r_pub)
    key = hkdf_sha256(d1 + d2, salt, b"XOSF1|v1")
    nonce = os.urandom(12)
    aad = f"XOSF1|c{ctx}|f{sender_id}|t{recipient_id}".encode()
    ct = aes_gcm(key, nonce, plaintext, aad)
    body = bytes([1]) + eph_pub + s_pub + nonce + ct
    return "XOSF1:" + base64.b64encode(body).decode()


def decrypt_xosf1(ctx, sender_id, recipient_id, r_priv, r_pub, envelope):
    body = base64.b64decode(envelope[len("XOSF1:"):])
    eph_pub, s_pub, nonce, ct = body[1:33], body[33:65], body[65:77], body[77:]
    d1 = dh(r_priv, eph_pub)
    d2 = dh(r_priv, s_pub)
    salt = sha256(eph_pub + s_pub + r_pub)
    key = hkdf_sha256(d1 + d2, salt, b"XOSF1|v1")
    aad = f"XOSF1|c{ctx}|f{sender_id}|t{recipient_id}".encode()
    return aes_gcm_decrypt(key, nonce, ct, aad)


def chunk_nonce(file_key, index):
    info = b"XOEEM1n" + index.to_bytes(4, 'big')
    return hkdf_sha256(bytes(32), file_key, info, 16)[:12]


def chunk_aad(index):
    return b"XOEEM1|c" + index.to_bytes(4, 'big')


def encrypt_chunk(file_key, index, plain):
    return aes_gcm(file_key, chunk_nonce(file_key, index), plain, chunk_aad(index))


def decrypt_chunk(file_key, index, ct):
    return aes_gcm_decrypt(file_key, chunk_nonce(file_key, index), ct, chunk_aad(index))


# ---------------------------------------------------------------- flow

def rnd_phone():
    return "4049" + "".join(random.choices(string.digits, k=4))


def register():
    phone = rnd_phone()
    pw = "pw" + "".join(random.choices(string.ascii_letters + string.digits, k=8))
    r = call("POST", "/api/v1/auth/check-phone.php", body={"phone": phone})
    if not r.get("ok"):
        return None, None, r
    r = call("POST", "/api/v1/auth/register.php", body={"phone": phone, "password": pw, "hint": "t81"})
    if not r.get("ok"):
        return None, None, r
    return r["access_token"], r["user"]["id"], r


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

    print("== T81 LIVE: encrypted-file (XOSF1) contract on production ==")
    tok_a, id_a, _ = register()
    tok_b, id_b, _ = register()
    check("two live accounts registered", bool(tok_a and tok_b), (id_a, id_b))

    ka, kb = os.urandom(32), os.urandom(32)
    pub_a, pub_b = pk_from_priv(ka), pk_from_priv(kb)
    check("A publishes its static key",
          call("PUT", "/api/v1/secret/keys.php", token=tok_a, body={"pk": base64.b64encode(pub_a).decode()}).get("ok"))
    check("B publishes its static key",
          call("PUT", "/api/v1/secret/keys.php", token=tok_b, body={"pk": base64.b64encode(pub_b).decode()}).get("ok"))
    peer_b = call("GET", f"/api/v1/secret/keys.php?user_id={id_b}", token=tok_a)
    check("A fetches B's key through the registry",
          peer_b.get("ok") and peer_b.get("registered")
          and base64.b64decode(peer_b["pk"]) == pub_b, peer_b.get("registered"))

    # THE file key — derived INDEPENDENTLY on both sides (no transport)
    key_a = derive_file_key(id_a, ka, pub_a, id_b, pub_b)
    key_b = derive_file_key(id_b, kb, pub_b, id_a, pub_a)
    check("THE one symmetric file key derives identically on both sides",
          key_a == key_b and len(key_a) == 32, f"len={len(key_a)}")

    # the pair's CLOUD chat
    r = call("POST", "/api/v1/chats/create.php", token=tok_a, body={"type": "private", "peer_user_id": id_b})
    cloud_id = r.get("chat", {}).get("id") or r.get("chat_id")
    check("cloud chat of the pair exists", bool(cloud_id), r)

    # ---- the encrypted-file round-trip
    plain = os.urandom(150 * 1024 + 777)
    cs = 96 * 1024
    parts = (len(plain) + cs - 1) // cs
    ct_whole = b"".join(encrypt_chunk(key_a, i, plain[i * cs:(i + 1) * cs]) for i in range(parts))
    ct_sizes = [len(encrypt_chunk(key_a, i, plain[i * cs:(i + 1) * cs])) for i in range(parts)]

    r = call("POST", "/api/v1/files/init.php", token=tok_a, body={"chunk_size": cs, "chunks_total": parts})
    fid = r.get("file_id")
    # real upload: the exact ciphertext of each part
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
    check("finalize declares kind=e2ee (ciphertext at rest)",
          r.get("ok") and r["file"]["kind"] == "e2ee", r.get("file", {}).get("kind"))

    inner = json.dumps({"t": "m", "kf": "ss", "pl": len(plain), "cs": cs,
                        "mi": "application/octet-stream", "na": "t81probe.bin", "an": 0},
                       ensure_ascii=False).encode()
    env = encrypt_xosf1(min(id_a, id_b), id_a, id_b, ka, pub_a, pub_b, inner)
    check("envelope carries the XOSF1 prefix", env.startswith("XOSF1:"))
    r = call("POST", "/api/v1/messages/send.php", token=tok_a,
             body={"chat_id": cloud_id, "content": env, "media_file_id": fid})
    check("v2.12.4 accepts the XOSF1 envelope in the CLOUD chat",
          r.get("ok") and r["message"]["content"] == env, r.get("error", r.get("transport")))
    row = r.get("message", {})

    # B opens the envelope with ITS perspective (its dialog id is A's user id)
    inner_b = decrypt_xosf1(min(id_a, id_b), id_a, id_b, kb, pub_b, row["content"])
    meta = json.loads(inner_b)
    check("B opens the envelope (canonical pair-low-id AAD)",
          meta.get("kf") == "ss" and meta.get("pl") == len(plain), meta.get("kf"))
    check("the envelope carries NO file key", "fk" not in meta)

    # B re-derives the file key from the ENVELOPE-carried sender pub
    env_body = base64.b64decode(row["content"][len("XOSF1:"):])
    env_sender_pub = env_body[33:65]
    check("envelope sender pub matches the registry", env_sender_pub == pub_a)
    key_b2 = derive_file_key(id_b, kb, pub_b, id_a, env_sender_pub)
    check("B re-derives the SAME file key from the envelope", key_b2 == key_a)

    # B downloads (attested windowed route) and decrypts
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
        recovered += decrypt_chunk(key_b2, i, got[off:off + ct_sizes[i]])
        off += ct_sizes[i]
    recovered = bytes(recovered)
    check("B decrypts the file with the derived key — byte-exact round-trip", recovered == plain,
          f"recovered {len(recovered)} of {len(plain)}")

    # ---- mode boundaries
    xosc1_text = "XOSC1:" + base64.b64encode(b"\x01" + os.urandom(80)).decode()
    r = call("POST", "/api/v1/messages/send.php", token=tok_a, body={"chat_id": cloud_id, "content": xosc1_text})
    check("XOSC1 still refused in the cloud chat",
          r.get("error", {}).get("code") == "E2EE_CONTENT_REFUSED", r.get("error", {}).get("code"))

    r = call("POST", "/api/v1/chats/create.php", token=tok_a, body={"type": "secret", "peer_user_id": id_b})
    sec_id = r.get("chat", {}).get("id") or r.get("chat_id")
    if sec_id:
        r = call("POST", "/api/v1/messages/send.php", token=tok_a, body={"chat_id": sec_id, "content": env})
        check("XOSF1 refused in a SECRET chat (contract stays XOE1/XOSC1)",
              r.get("error", {}).get("code") == "E2EE_CONTENT_REQUIRED", r.get("error", {}).get("code"))
    r = call("POST", "/api/v1/messages/send.php", token=tok_b, body={"chat_id": cloud_id, "content": "plain cloud text still fine"})
    check("cloud plaintext unaffected", r.get("ok") and r["message"]["content"] == "plain cloud text still fine", r)

    print()
    print(f"T81 LIVE: {sum(RESULTS)}/{len(RESULTS)} PASS")
    sys.exit(0 if all(RESULTS) else 1)


if __name__ == "__main__":
    main()

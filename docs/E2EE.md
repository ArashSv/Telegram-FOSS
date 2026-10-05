# End-to-End Encryption (E2EE) for 1:1 Chats — Architecture & Threat Model

**Version:** 1.2 (backend v2.11.1, client build T74)
**Scope:** private 1:1 chats (text, media, files). Groups stay plaintext in this
milestone (Sender Keys is the next milestone, per the roadmap below).
Self-chats ("Saved Messages") stay plaintext: they are the server-inserted
diagnostic relay target, and a self-session adds no privacy.

---

## 1. Guarantee (the contract this design is built around)

> Only the sender and the recipient can read message content or open media.
> **The operator cannot read user messages even by modifying the PHP, the
> database, the storage, or any server-side code.**

What the server CAN still do (physics of the model, same as Signal/WhatsApp):
delete, drop, delay, re-order and duplicate opaque blobs, and observe
metadata (who talks to whom, when, sizes). E2EE = confidentiality,
integrity, authentication — **not** availability.

What is OUT of scope: device compromise (malware/root), metadata
de-anonymization, and local-DB-at-rest encryption of the existing cache4.db
(the app's pre-existing architecture; protocol state itself IS
Keystore-encrypted — see §5).

---

## 2. Protocol — Signal, not hand-rolled

Implemented with the reference implementation
`org.whispersystems:signal-protocol-java:2.8.1` (pure JVM: curve25519-java +
protobuf-javalite — **no native .so**, so the T66 16KB-page CI gate and every
ABI are unaffected, and the same code runs on the device and in the CI JVM
test job).

| Piece | Role |
|---|---|
| **X3DH** (Extended Triple Diffie-Hellman) | asynchronous session establishment against the peer's uploaded prekey bundle — works while the peer is offline |
| **Double Ratchet** | per-message keys (forward secrecy), DH re-ratcheting (post-compromise security), skipped-message keys (out-of-order) |
| **Curve25519 + AES-256-GCM + HKDF** | the primitives, all inside libsignal — the client never invents crypto |

Client-owned classes only WIRE the protocol to our transport and storage:
`XoE2EE` (facade), `XoE2EEStore` (encrypted state), `XoE2EEApi` (key
transport), `XoE2EEEnvelope` (wire framing), `XoE2EEMedia` (media crypto).
License note: libsignal 2.8.1 is GPLv3 — matches this fork's license.

---

## 3. Key lifecycle

```
device (per account, per login)
  ├── IdentityKeyPair           private: device-only      public: keys/register
  ├── SignedPreKey (rotated)    private: device-only      public + signature: keys/register
  └── 100 One-Time PreKeys      private: device-only      public: keys/register / refill
                                                    (refill when pool < 20)
```

- Registration happens lazily: opportunistically after login
  (UpdatePoller arm), and synchronously before the first encrypted send.
- The server stores **public halves only** in `e2ee_identities`,
  `e2ee_signed_prekeys`, `e2ee_one_time_prekeys`.
- Logout (performLogout) **destroys** all protocol state — identity keys are
  never escrowed, never backed up. A fresh login = a fresh identity.

---

## 4. Message flow

```
Alice types "سلام"
   └─ inner JSON {"t":"t","x":"سلام"}
       └─ SessionCipher.encrypt (Double Ratchet)
           └─ [1B wire-type][libsignal CiphertextMessage]
               └─ base64  ->  "XOE1:..."
                   └─ POST messages/send.php {content: "XOE1:..."}
                       └─ server stores the OPAQUE string (ciphertext relay)
                           └─ sync/message_new delivers to Bob
                               └─ TlJsonMapper.parseMessage DECRYPT HOOK
                                   └─ "سلام"
```

- First contact: the sender fetches the peer bundle
  (`GET /e2ee/keys/bundle.php?user_id=`) and performs X3DH inside
  `SessionBuilder.process`. The one-time prekey row is CONSUMED server-side
  (deleted atomically in the same transaction that reads it) — a bundle is
  never served twice.
- Edits re-encrypt the new text (`messages/edit.php` carries the fresh
  envelope). Deletes are server-side soft deletes of ciphertext.
- Forwards INTO an encrypted 1:1 chat are re-encrypted client-side
  (`forwardEncryptedIntoPrivate`); the server-side verbatim-copy forward
  endpoint is never used for that direction, because it would either leak
  plaintext rows or copy unreadable ciphertext. Trade-off: converted
  forwards lose the "Forwarded from" header.
- **Own-echo rendering (T73/T74):** libsignal can never re-open the sender's
  own ciphertext (the owning chains live on the peer), and a PreKey-type
  self-decrypt consults `isTrustedIdentity` with OUR key under the peer's
  address — flagging the peer and killing all further sends (the T73
  "no messages can be sent" defect, proven against the real backend).
  The mapper therefore NEVER self-decrypts: own rows render from the
  sent-inner cache the dispatcher records at send time (LRU 2000 since
  T74; a cache miss degrades to the neutral 🔒 placeholder). Consumed
  OTKs are also ARCHIVED client-side (last 20) so a re-served prekey
  message still bootstraps instead of dying unreadable.
- **Store-level impossibility guard (T74):** our own public key can never
  legitimately be a PEER's identity key (that would require the peer to
  hold our private key). `saveIdentity`/`isTrustedIdentity` therefore
  refuse an own-key sighting WITHOUT pinning and WITHOUT flagging — the
  two poison effects of build-108 (self-pin + false-positive flag) are
  structurally impossible to re-create, from any call site, forever.

### No-plaintext-fallback policy (hard requirement)

If a private-chat send cannot be encrypted — peer has no keys yet (old
client), identity flagged, transport failure — the send **fails loudly**
(`E2eeUnavailableException` → the message shows the standard error state).
There is NO silent plaintext fallback, ever. Mixed-fleet consequence:
an old build's user cannot RECEIVE readable text from a new build (and new
builds refuse referencing plaintext media); both sides updating resolves it.

---

## 5. Media & files (chunked AEAD)

Before upload, the whole file is encrypted on-device with a fresh 32-byte
single-use **file key**:

```
part i (upload part, ≤128 KB)
  nonce_i = HKDF-SHA256(fileKey, salt=0^32, info="XOEEM1n"||uint32(i))[0..12]
  aad_i   = "XOEEM1|c" || uint32(i)
  ct_i    = AES-256-GCM(fileKey, nonce_i, aad_i, part_i)     (+16B tag)
server blob = ct_0 || ct_1 || ... (assembled exactly like plaintext parts today)
```

- The upload funnel (`RestDispatcher.uploadPart`) encrypts each part on the
  fly, so the existing init/chunk/finalize/Range/attestation pipeline is
  reused unchanged — attestations (size/sha256) are over CIPHERTEXT.
- Real metadata (name, MIME, dimensions, duration, caption) rides ONLY
  inside the encrypted envelope; the server file row is
  `kind='e2ee'`, `application/octet-stream`, no name/dimensions. The
  finalize `e2ee:true` flag SKIPS magic-byte inspection (random ciphertext
  can coincidentally match signatures) and applies the global cap (2 GB).
- Thumbnails are client-generated (videos: the existing T14/T48 path),
  encrypted under their own key (`tk`) and linked as separate e2ee files —
  the server can never synthesize or read thumbs of encrypted media.
- The file key travels to the recipient inside the message envelope
  (`{"t":"m","fk":...,"tk":...}`) — never in plaintext, never to the server.
- Download: the assembled ciphertext is verified (existing attestation),
  then decrypted chunk-wise IN PLACE in FileLoadOperation before any
  consumer sees the file. Any tamper/truncate/reorder fails the download.
- Sizes shown in the UI are the ciphertext sizes (attestation contract).

---

## 6. Trust verification (anti-MITM)

The classic attack on a dumb relay: the server swaps the peer's prekey
bundle for attacker keys. Defenses, in order:

1. **Signed prekeys** — the signed prekey carries an identity-key signature;
   libsignal's SessionBuilder verifies it during session build. A forged
   bundle never bootstraps a session (tested).
2. **TOFU + pinning** — the first sighting of a peer identity key is pinned
   locally (inside the Keystore-encrypted store). `isTrustedIdentity`
   refuses any DIFFERENT key: the send fails, the peer is FLAGGED, and
   1:1 traffic to them stops until the user acts.
3. **Safety Number** — 60 digits derived from BOTH identity keys
   (NumericFingerprintGenerator, 5200 iterations — Signal's display
   convention). Both sides compare it out-of-band (in person / any trusted
   channel); matching numbers ⇒ no MITM. Shown in
   `XoE2EEInfoFragment` (chat menu → «رمزنگاری»), with
   mark-verified / unverify / reset-session actions.
4. **User-approved reset** — after a flagged identity change the user
   re-verifies the NEW safety number, then "reset session" unpins the old
   key (TOFU re-pin). There is no implicit path that re-pins.
5. **Deterministic recovery ladder (T74)** — a flagged peer no longer
   dead-ends silently. On the next send (and, for the self-pin case, on
   decrypt too) the facade runs, in order:
   (a) **self-pin heal** — the pinned key equals OUR own identity key:
   provably bogus, removed locally without any server round-trip;
   (b) **false-positive flag heal** — the server still serves exactly the
   pinned key: the flag came from the T71 own-echo defect; it is cleared
   AND the session is dropped for a clean re-ratchet;
   (c) **genuine substitution** — server serves a different key: the flag
   persists and traffic stays stopped until the user verifies.
   Transport failures never clear anything (an unreachable server cannot
   vouch for anyone). A once-per-process `sweepSelfPins()` un-bricks every
   poisoned chat at registration time, so an upgraded build-108 client
   self-recovers before the first send.

---

## 7. Local state security

`XoE2EEStore` (identity privates, sessions, prekeys, trust decisions, media
keys, upload intents) is one JSON blob per account, AES-256/GCM-encrypted
with a non-exportable **Android Keystore** key (alias `xo_e2ee_<account>`,
API 23+; the RestAuthStore pattern). API 19–22 falls back to app-private
storage — the same documented protection level the fork already accepted for
auth tokens. Writes are atomic (tmp + fsync + rename): a crash cannot
corrupt ratchet state. An UNREADABLE blob (device restore / keystore
invalidation) is wiped and a fresh identity registered — recoverable by
design, never a crash.

Never logged: plaintext, private keys, chain keys, message keys, file keys.
Never crash-reported: message content (the T66 pipeline only reports stack
traces).

**T74 diagnostics channel (`XoE2eeLog`):** every protocol decision is
recorded as a structured, content-free event (register/re-register +
otk_remaining, bundle fetches, session builds, encrypt/decrypt ok/fail
with reason codes, trust pin/flag/heal/sweep transitions, sent-inner
note/hit/miss, refills). Events live in an in-memory ring, a local
mirrored file (files/e2ee_logs, 256 KB cap + rotation), and are batched
(bounded, throttled ≤~7/hour) to the unauthenticated T66 `client_log`
endpoint (kind=e2ee with kind=ping fallback; the breaker skips kinds the
host stalls on) so the server-side android_log files + the operator's
Saved-Messages relay show exactly what the client did — without adb and
without ever carrying message content.

---

## 8. Server surface (v2.11.1) — and why it stays "dumb"

| Endpoint | Semantics |
|---|---|
| `POST /e2ee/keys/register.php` | full (re-)registration of the caller's PUBLIC key set. **v2.11.1:** a re-register with an UNCHANGED identity is a re-confirm — the OTK pool is PRESERVED (consumed rows stay consumed; submitted keys dedupe-insert). An identity CHANGE is the recovery path: full REPLACE. (The old delete-all behavior resurrected served OTKs and permanently broke late-arriving prekey messages.) |
| `GET /e2ee/keys/bundle.php?user_id=` | serve the peer bundle; CONSUME one OTK atomically; `otk_remaining` count for refills |
| `POST /e2ee/keys/refill.php` | append OTKs (identity untouched) |

Plus two dumb-transport tweaks: `messages` content cap 4096→16384 (opaque
envelopes need the headroom; the column is TEXT 64 KB) and
`files/finalize.php` accepting `e2ee:true` (kind='e2ee', octet-stream,
no inspection, global cap, no server thumb). Every other endpoint is
untouched — `messages/send`, `history`, `sync`, `files/chunk`,
`files/download` already treat content as an opaque blob.

The server CANNOT verify signed-prekey signatures (XEdDSA over Curve25519
is client-domain); it validates only shapes/lengths and throttles. That is
by design: the security-critical signature check happens on-device in
libsignal, where trust lives.

**Endpoints a malicious operator cannot weaponize:** register/bundle/refill
only ever move PUBLIC bytes; messages/files only ever move OPAQUE bytes.

---

## 9. Deployment & compatibility notes

- Backend v2.11.0 = one idempotent migration (3 new tables, version stamp)
  + 2 new endpoint files + 1 controller + 2 dumb tweaks. No existing
  column/table is altered.
- Older clients are unaffected: they never call the e2ee endpoints, and
  plaintext group/self flows are byte-identical.
- Known v1 limitations (documented, deliberate): groups + Saved Messages
  plaintext; GIF-tab re-sends by server reference are REFUSED in 1:1 chats
  (the referenced file is server-plaintext — the hard guarantee wins);
  E2EE photo bubbles may download the full image when no encrypted thumb
  exists; forwards into 1:1 chats lose forward headers.

---

## 10. Test matrix (where each guarantee is proven)

| Guarantee | Proven by |
|---|---|
| X3DH + ratchet correct end-to-end | `XoE2EEProtocolTest` (real libsignal, simulated transport) |
| Out-of-order recovery | `outOfOrderDeliveryRecoversViaSkippedKeys` |
| Modified ciphertext rejected | `modifiedCiphertextIsRejected` |
| Replay rejected | `replayedMessageIsRejected` |
| Identity substitution detected + pinned + flagged | `identityKeySubstitutionIsDetectedAndPinned` |
| Self-pin poison (build-108) heals on encrypt — one call | `selfPinPoisonHealsOnEncryptInOneCall` |
| Self-pin poison heals on decrypt — real message opens | `selfPinPoisonHealsOnDecryptAndRealMessageOpens` |
| Own-echo through the protocol layer poisons NOTHING (guard) | `ownEchoThroughDecryptNeverPoisonsAndConversationSurvives`, `guardRefusesOwnKeyInSaveIdentityAndTrustCheck` |
| False-positive flag heals AND fresh session interops | `falsePositiveFlagHealsAndFreshSessionStillInterops` |
| Genuine substitution still blocked after the heal ladder | `genuineSubstitutionStillBlockedAfterHealLogic` |
| LIVE production round-trip incl. register-preserve + self-pin heal | `XoE2EELiveHarnessTest` (XO_LIVE_T74=1, offline in CI) |
| User-approved reset recovers | `userApprovedResetRecoversTheSession` |
| Forged signed-prekey signature never bootstraps | `forgedSignedPrekeySignatureIsRejected` |
| Media chunk tamper/reorder/truncate fail; round-trip exact | `XoE2EEMediaCryptoTest` (+ RFC 5869 HKDF vectors) |
| Store persistence / corruption wipe / isolation | `XoE2EEStoreTest` |
| Server is a dumb key store: OTK single-use, 404 without keys, verbatim ciphertext relay, e2ee file kind + inspection bypass gating | `tools/t67_local_e2e.py` (44/44 local; rerun vs production after deploy) |
| No regression on existing flows | `tools/smoke_test.py` (58/58) |

---

## 9. T75 addendum — idempotent receive, media completeness, pending chats

**Version 1.3 (backend v2.11.2, client build T75).**

### 9.1 Receive path is IDEMPOTENT (first-message 🔒 fix)

The same server row is parsed multiple times BY DESIGN (update_queue
`message_new` racing the chat-open history load, transport retries,
scroll-back). libsignal's replay protection makes every 2nd+ open of the
same ciphertext throw `DuplicateMessageException`. Pre-T75 the losing
parse's "🔒" row could replace the winning parse's good row in storage/UI —
field evidence: the FIRST message of a fresh chat (the one present in BOTH
the initial history load and the first update tick) rendered 🔒 forever
while every later message decrypted.

Fix: `XoE2EEStore.decryptedInners` — a persisted LRU (2000) of
`message id → plaintext inner JSON` for RECEIVED rows, written exactly when
`decryptFromPeer` succeeds and consulted BEFORE any libsignal call in the
mapper's decrypt hook. Parsing a row is now deterministic and cheap; 🔒 only
appears for genuinely undecryptable rows. Per-account by construction (the
memo lives inside the Keystore-encrypted per-account blob).

### 9.2 Media completeness inside encrypted chats

Field evidence (production DB): videos/thumbs/photos uploaded + finalized as
`kind='e2ee'` but ZERO message rows referenced them; no album row ever
existed. Three independent defects:

| Defect | Mechanism | Fix |
|---|---|---|
| **Album shape mismatch** | `uploadMedia`'s response for an opaque e2ee row parsed as a DOCUMENT even for a PHOTO item; the album callback requires the response family to equal the request item → `markAsError()` on the whole group | `TlJsonMapper.parseMediaE2ee(fileJson, date, requestWasPhoto)` forces the family by the REQUEST type |
| **Video thumb rejected server-side** | `messages/send.php` required `kind='image'` for `thumb_file_id`; an encrypted thumb IS `kind='e2ee'` (the server cannot inspect it) → silent 400 AFTER the full upload | backend accepts `kind IN ('image','e2ee')` (owner + readiness still enforced) |
| **Referenced plaintext media refused** | GIF-tab taps / re-sent media reference a plaintext file row — correct policy, missing mechanism | `XoE2EEReencrypt.reencrypt()`: authorized download → fresh key → chunk AEAD → attested multi-part upload → NEW opaque e2ee file; the encrypted chat references only the new row |

Album items also carry their thumb INSIDE the envelope now (tk/tf fields,
keyed per item by the uploadMedia step), with the backend linking
`files.thumb_file_id` per send-multi item (metadata-free, zero-trust
preserved). The manifest carries an explicit `an` (animated) bit — the old
".mp4 name" heuristic misclassified real videos as GIFs and only survives
as a legacy fallback for pre-T75 envelopes.

### 9.3 Pending chats (the "chat creation request")

A private chat is creatable — and messageable — even when the peer has no
keys yet (not logged in, fresh install, old build). The chat row is created
server-side before the encryption gate (pre-existing `requireChatId`
behavior), so the peer sees it on their next sync. The SEND defers instead
of failing:

- `XoPendingKeys.maybeDefer` (send error branches): ONLY the exact
  `E2EE_NO_PEER_KEYS` code, ONLY private non-self dialogs. Rows keep the
  clock icon (`send_state=SENDING`, persisted as unsent messages by the
  tree) — never an error row, never a bulletin, never plaintext.
- Probe loop: `GET e2ee/keys/exists.php?user_id=` (NEW; a pure SELECT —
  NEVER bundle.php, which consumes one-time prekeys). Foreground 45 s /
  background 3 min; single-flight per dialog; watch set persisted.
- Flush: dialog-scoped unsent rows (`MessagesStorage.getUnsentMessagesForDialog`)
  re-dispatch through `SendMessagesHelper.retrySendMessage` — the canonical
  pipeline (same upload locations → same minted file keys → consistent
  ciphertext). Identity-rotation failures are NEVER deferred.

### 9.4 Diagnostics

`XoE2eeLog` events now actually reach the backend: `logs/client_log.php`
accepts `kind=e2ee` (v2.11.2 whitelist fix; unknown kinds previously
coerced to crash-kind and relayed noisily). New events:
`pending.defer`, `pending.flush`, `reencrypt.start/ok/fail`.

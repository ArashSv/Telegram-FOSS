# CHAT MODES — Cloud Chat vs Secret Chat (T80, v2.12.0)

## Problem (why)

Since T71 **every** 1:1 chat was forced through Signal E2EE. Any session
corruption (reinstall, restored blob, replayed prekey, untrusted identity)
made incoming messages arrive as `🔒` and — worse — broke whole chats with no
way back. Encryption strength was purchased with fragility, and the failure
mode was user-visible data loss.

## Decision (what)

Two explicitly separated chat modes, server-authoritative:

| | **Cloud chat** (default) | **Secret chat** (opt-in) |
|---|---|---|
| Transport | HTTPS (TLS) | HTTPS (TLS) |
| Content in DB | **AES-256-GCM at rest, server-held key** (`XOC1:` envelope) | opaque client ciphertext (`XOE1:` envelope) |
| Who can read | server + participants (Telegram cloud semantics) | participants only |
| Works with keyless peer | **yes, always** | no (requires both sides registered keys) |
| Lock indicator | none | 🔒 header badge + safety number |
| Media | plain file rows (kind=mime, server thumbs) | opaque e2ee blobs + encrypted thumbs |
| Multi-device | native | per-device sessions (inherent) |

**A chat's mode is a property of the CHAT (`chats.mode`), not of the
message.** Both clients learn it from `chats/list.php`, `chat_new`,
and a new `chat_mode` update. Existing chats backfill to `cloud` — that is
the fix: the broken E2EE sessions stop being load-bearing for new traffic.

Message rendering stays **content-triggered** on the client (`XOE1:` prefix →
try decrypt), so legacy rows inside a migrated cloud chat still render (or
honestly show `🔒` when the old session truly is dead), while new cloud
messages render with zero libsignal involvement.

## Backend (v2.12.0)

### At-rest crypto (`App\Core\CloudCrypto`)
- Master key = `Config::get('crypto.master_key')` (base64, 32 B) **or**, when
  absent, HKDF-SHA256 derived from `jwt.secret` (`info='xoc1:master'`).
  Deterministic per environment, zero-config, no live-config surgery needed.
  Versioned prefix (`XOC1:`) leaves room for future re-key sweeps.
- Per-chat key = `hash_hkdf('sha256', master, 32, 'xoc1:v1:chat:'.$chatId)`.
- Wire format stored in `messages.content`:
  `XOC1:` + base64( 12 B nonce ‖ ciphertext+tag ), AAD = `xoc1:msg:<id>`
  (row-bound; ciphertext cannot be moved between rows or chats).
- **Plaintext never touches storage**: the send transaction inserts the row
  with placeholder content, then updates it to the envelope *inside the same
  transaction* (T66 lesson applied to data, not just config).
- Passthrough: content without the `XOC1:` prefix is returned as-is
  (legacy plaintext + `XOE1:` rows). Tag failure → `error_log` security
  event + content `''` (honest, no crash, media unaffected).

### Mode-aware write path (`MessagesController`)
- `send`, `sendMulti`, `edit`, `forward` load `chats.mode` and enforce:
  - **secret** chat: content MUST start with `XOE1:` → stored verbatim
    (server cannot read it — guaranteed by construction);
    plain content → `400 E2EE_CONTENT_REQUIRED` (stale client guard).
  - **cloud** chat: content MUST NOT start with `XOE1:` →
    `400 E2EE_CONTENT_REFUSED`; stored via `CloudCrypto::encrypt`.
- `forward` target decides per row: cloud target re-encrypts decrypted source
  under the new row id; secret target accepts only client-converted `XOE1:`.
  Direct server-copies of `XOE1:` rows into cloud chats are refused
  (`E2EE_FORWARD_UNAVAILABLE`) — the client converts them instead.

### Mode flip (`POST chats/set-mode.php {chat_id, mode}`)
- private chats only, requester must be a member.
- `mode=secret` requires **both** members to have `e2ee_identities` rows,
  else `400 E2EE_KEYS_MISSING`.
- `chats.mode` updated in a transaction + `chat_mode` update pushed to BOTH
  members (own other devices converge too).
- History is untouched: rows keep their original form. New writes follow the
  new mode. (Telegram semantics: enabling a secret chat protects new
  messages; it does not rewrite history.)

### Sync surface
- `chats/list.php` + `chatPayload` + `createPrivate` ship `"mode"`.
- `UpdateQueue::TYPE_CHAT_MODE='chat_mode'`, payload `{chat_id, mode, by_user_id}`,
  passed through by `SyncController`.

### Schema
- MySQL: `ALTER TABLE chats ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'cloud'`;
  `ALTER TABLE update_queue MODIFY type enum(...,'chat_mode')`. (idempotent,
  information_schema-guarded, one-shot token-gated script)
- SQLite (dev/test): column add + CHECK-rebuild of `update_queue`.

## Client

### Single decision points (the old 6 inline predicates die)
- `RestChatIndex` gains `chatModes` + `isSecret(chatId)` / `isSecretPeer(userId)`
  (unknown ⇒ `cloud` — safe default, always works).
- `RestDispatcher.isSecretTarget()` replaces every inline
  `!isGroup && userId>0 && userId!=selfId` predicate:
  text send (+own-echo), sendMedia, sendMulti, edit, forward, seed message.
- `SendMessagesHelper.noteE2eeUploadIntent` mints media keys **only** for
  secret peers ⇒ cloud media returns to the proven plain pipeline
  (real mime/name/dims, server thumbs, plain finalize).
- `XoPendingKeys.maybeDefer` defers **only** secret sends ⇒ keyless peers are
  chat-able instantly in cloud mode (T75 deferral stays for secret mode).
- `TlJsonMapper` decrypt hook stays **content-triggered** (unchanged for
  legacy/secret rows; cloud rows never enter it).

### Cross-mode forwarding
- cloud target, plain source → server reference-forward (batched, unchanged).
- cloud target, `XOE1:` source → client decrypts text / downloads-decrypts-
  reuploads media as a **plain** copy (`XoE2EEReencrypt.reuploadPlain`)
  then sends normally.
- secret target → existing re-encryption path (unchanged).

### UI
- `chat_mode` update: index update + `NotificationCenter.xoChatModeChanged`.
- `ChatActivity` (1:1): secret → 🔒 menu/info entry (existing); cloud →
  **"Start Secret Chat"** menu item calling `chats/set-mode.php`; live
  header/bulletin reaction to mode flips.
- `ProfileActivity`: the dead upstream MTProto `StartEncryptedChat` handler
  becomes the real mode flip (create-if-missing chat → set-mode → open).
- New strings (en/fa/de/es/it/ru/tr/ar).

## Tests (guarantee)
- **Backend wire test** (`tools/t80_wire.py`, local sqlite): 16 asserts —
  default cloud mode, plaintext ack, **ciphertext actually in DB**, history +
  sync fan-out decrypt, edit round-trip, secret-mode key gate, mode flip
  events to both members, `XOE1:`-into-cloud refused, plain-into-secret
  refused, secret chat opaque storage, forward semantics both directions,
  group at-rest encryption.
- **Client unit tests** (`XoT80ChatModeTest`): index parsing/defaults/setMode,
  secret-only deferral, upload-intent gating, content-triggered decrypt
  independence from mode. CI gate: `testDebugUnitTest`.
- **Live deploy verification**: health probe + 2-account live round-trip
  (cloud text both directions + mode flip + secret round-trip) after WebDAV
  deploy with lint-asserted PUTs + md5 read-backs (T77 ops discipline).

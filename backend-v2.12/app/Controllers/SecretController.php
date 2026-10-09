<?php

declare(strict_types=1);

namespace App\Controllers;

use App\Core\ApiError;
use App\Core\Auth;
use App\Core\Db;
use App\Core\Request;
use App\Core\Throttle;
use App\Core\Validator;

/**
 * v2.12.0 (T78) — public-key registry for SECRET CHATS (new E2EE scheme).
 *
 * Trust model: every account owns exactly ONE asymmetric key pair (X25519).
 * The private key NEVER leaves the device. This endpoint is a dumb,
 * authenticated PUBLIC-key registry:
 *   - the caller uploads their own public key (idempotent),
 *   - anyone authenticated can FETCH another user's public key.
 * No bundles, no one-time keys, no consumption, no state machine — a secret
 * message is encrypted to (peer public key) and is decryptable with nothing
 * but the recipient's own private key. That statelessness is the root fix
 * for the entire "locked message" bug class of the previous protocol.
 *
 *   PUT /api/v1/secret/keys.php  {pk: "<b64 32B X25519 public key>"}
 *       -> {ok, changed: bool, pk, updated_at}
 *   GET /api/v1/secret/keys.php?user_id=N
 *       -> {ok, user_id, registered: true, pk, updated_at}
 *        | {ok, user_id, registered: false}
 *   GET /api/v1/secret/keys.php            (no user_id -> the caller's own)
 *
 * Key replacement policy: the server stores WHAT THE CLIENT UPLOADS. A
 * different pk from the same account (new device / reinstall) simply
 * replaces the row and `changed:true` tells the uploader; peers learn about
 * a change because every XOSC1 envelope carries the sender's public key —
 * the receiving client compares it with its cached value and surfaces a
 * "peer key changed" notice (Telegram semantics). No server-side push is
 * needed for key changes.
 *
 * Client contract: org.telegram.tgnet.rest.e2ee.XoSecretApi (T78).
 * The legacy e2ee/keys/* (Signal prekey store) stays deployed but DEPRECATED.
 */
final class SecretController
{
    /** base64(32 bytes) = 44 chars; the cap leaves headroom for formats. */
    private const MAX_KEY_B64 = 128;

    // ------------------------------------------------------------------ put

    /** PUT /secret/keys.php — register/replace the CALLER's public key. */
    public static function putKey(Request $request): array
    {
        $me = Auth::requireUser($request);
        Throttle::hit('secret:put:' . (int) $me['id'], 30, 3600, 600);

        $pk = self::publicKeyFromInput($request->input('pk'));
        $uid = (int) $me['id'];
        $now = time();

        $changed = Db::transaction(static function () use ($uid, $pk, $now): bool {
            $existing = Db::fetch('SELECT secret_pk FROM users WHERE id = ?', [$uid]);
            $same = $existing !== null
                && is_string($existing['secret_pk'])
                && $existing['secret_pk'] !== ''
                && hash_equals($existing['secret_pk'], $pk);

            if ($same) {
                return false; // idempotent re-confirm (app restart / retry)
            }
            $rows = Db::run(
                'UPDATE users SET secret_pk = ?, secret_pk_updated = ? WHERE id = ?',
                [$pk, $now, $uid],
            );
            if ($rows === 0) {
                throw new ApiError('SERVER_ERROR', 'Key row vanished', 500);
            }
            return true;
        });

        return ['changed' => $changed, 'pk' => $pk, 'updated_at' => $now];
    }

    // ------------------------------------------------------------------ get

    /** GET /secret/keys.php[?user_id=N] — fetch a public key (pure SELECT). */
    public static function getKey(Request $request): array
    {
        $me = Auth::requireUser($request);
        Throttle::hit('secret:get:' . (int) $me['id'], 600, 3600, 600);

        $rawId = $request->q('user_id'); // GET query string, not a JSON body
        $userId = $rawId !== null && $rawId !== ''
            ? Validator::int($rawId, 'user_id', 1)
            : (int) $me['id'];

        $row = Db::fetch('SELECT secret_pk, secret_pk_updated FROM users WHERE id = ?', [$userId]);
        if ($row === null) {
            throw new ApiError('NOT_FOUND', "User {$userId} does not exist", 404);
        }
        $pk = $row['secret_pk'];
        if (!is_string($pk) || $pk === '') {
            return ['user_id' => $userId, 'registered' => false];
        }
        return [
            'user_id'    => $userId,
            'registered' => true,
            'pk'         => $pk,
            'updated_at' => $row['secret_pk_updated'] !== null ? (int) $row['secret_pk_updated'] : null,
        ];
    }

    // --------------------------------------------------------------- helpers

    /**
     * Validate a base64 X25519 public key: strict decode, EXACTLY 32 bytes.
     * (The 32-byte rule is the whole server-side crypto validation — shapes
     * only; security lives entirely on the devices.)
     */
    private static function publicKeyFromInput(mixed $value): string
    {
        if (!is_string($value) || $value === '') {
            throw new ApiError('VALIDATION_ERROR', 'pk must be a base64 string', 400, ['field' => 'pk']);
        }
        if (strlen($value) > self::MAX_KEY_B64) {
            throw new ApiError('VALIDATION_ERROR', 'pk too long', 400, ['field' => 'pk']);
        }
        $raw = base64_decode($value, true);
        if ($raw === false || strlen($raw) !== 32) {
            throw new ApiError('VALIDATION_ERROR', 'pk must decode to exactly 32 bytes (X25519 public key)', 400, ['field' => 'pk']);
        }
        return $value;
    }
}

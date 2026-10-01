<?php

declare(strict_types=1);

namespace App\Controllers;

use App\Core\ApiError;
use App\Core\Auth;
use App\Core\Db;
use App\Core\Request;
use App\Core\Throttle;
use App\Core\Validator;
use App\Mappers\UserMapper;

/**
 * v2.6.0 (T50) — password-first auth: a two-step-verification password
 * REPLACES the OTP entirely (the send-code/verify pair is gone).
 *
 *   1. POST /auth/check-phone.php       {phone}
 *        -> {registered: bool, password_hint?: string|null}
 *        The client decides the page: registered = "already registered"
 *        dialog -> password entry; not registered = password setup.
 *   2. POST /auth/register.php          {phone, password, hint?}
 *        -> tokens + user            (new numbers ONLY; 409 otherwise)
 *      POST /auth/login.php           {phone, password}
 *        -> tokens + user            (existing numbers)
 *   3. POST /auth/verify-password.php   bearer {password}
 *        -> {valid: true}            (the privacy "current password" step)
 *      POST /auth/change-password.php  bearer {current_password, new_password, hint?}
 *        -> fresh tokens + user      (bumps token_ver: every other device
 *                                    is signed out, this one survives)
 *   4. POST /auth/refresh.php / GET /auth/me.php — unchanged.
 *
 * Identity namespace (closed): +404 followed by EXACTLY 5 digits —
 * Validator::xoPhone is the single gate; canonical storage is "+404xxxxx".
 *
 * Multi-account entitlement history:
 *   v2.9.0 (T56) "STRICT form" — check-phone/register/login for any phone
 *   other than the owner's REQUIRED a live owner (+11 1130) bearer. This
 *   was an architecture error: authentication BOOTSTRAPS a session, so it
 *   can never require one — the gate was a circular dependency that locked
 *   every first-time user (and every post-logout re-login, and every
 *   non-owner self re-auth) out of the product with MULTI_ACCOUNT_FORBIDDEN.
 *   v2.9.3 — the entitlement is SESSION-STATE-AWARE (see
 *   assertMultiAccountAllowed): bootstrap and self re-auth are always
 *   open; the owner's live session keys multi-account for any number; a
 *   valid NON-owner session trying to enter a DIFFERENT number is refused
 *   cleanly. Non-owner installs hold ONE account and their clients hide
 *   every Add-Account surface; the refusal exists as defense in depth for
 *   stale clients, not as a UX path.
 *
 * Security model:
 *   - password_hash() (bcrypt/argon2 per PHP build); passwords are never
 *     logged and never echoed back;
 *   - Throttle guards every sensitive surface: login 5 fails / 15 min ->
 *     15 min lockout, wrong-current-password 5 / 15 min, register and
 *     check-phone per-number windows (number enumeration blunting);
 *   - uniform PASSWORD_INVALID + dummy-hash work when the number is
 *     unknown (no user/timing enumeration inside login);
 *   - account existence IS visible in check-phone by design (Telegram
 *     "registered" semantics — the dialog needs it); hints are returned
 *     only for registered numbers, as Telegram's auth.getPassword does.
 */
final class AuthController
{
    private const PASSWORD_MIN = 4;
    private const PASSWORD_MAX = 64;
    private const HINT_MAX = 64;

    private const LOGIN_MAX_ATTEMPTS = 5;
    private const LOGIN_WINDOW = 900;   // 15 min failure window
    private const LOGIN_LOCKOUT = 900;  // 15 min lockout
    private const CHECK_MAX = 30;       // per hour, per number
    private const REGISTER_MAX = 5;     // per hour, per number

    /**
     * The ONLY number whose live session keys the multi-account
     * capability — the owner's "+11 1130" (wire 40411130, canonical
     * "+40411130"). Must stay in sync with the client's
     * XoSpecialAccounts.MULTI_ACCOUNT_WIRE ("40411130").
     */
    private const MULTI_ACCOUNT_OWNER_PHONE = '+40411130';

    /** Memoised hash used to equalise timing when the number is unknown. */
    private static ?string $dummyHash = null;

    /**
     * v2.9.3 — the multi-account entitlement, SESSION-STATE-AWARE.
     * Evaluated on every session-minting/probing surface (check-phone,
     * register, login):
     *
     *   1. BOOTSTRAP — the request carries NO valid bearer (fresh install,
     *      first-ever login, post-logout login, registration): ALLOWED
     *      unconditionally. Authentication bootstraps a session; it can
     *      never require one. An absent, expired, stale-era or malformed
     *      bearer is simply "no holder" (Auth::optionalUser) — this case,
     *      never an error.
     *   2. SELF — the presented session's own number: ALLOWED (re-auth of
     *      an account the holder already has is idempotent and harmless).
     *   3. OWNER — the presented session belongs to the owner
     *      (+40411130): ALLOWED for any target number. This is the
     *      product's multi-account capability, keyed to the owner's live
     *      session exactly like the client's UI gates
     *      (XoSpecialAccounts.isMultiAccountAllowedForCurrent).
     *   4. ADDITIVE by anyone else — a valid NON-owner session entering a
     *      DIFFERENT number: refused with a clean MULTI_ACCOUNT_FORBIDDEN.
     *
     * Backward compatibility: v2.9.x clients attach the owner bearer when
     * an owner session exists and nothing otherwise — under this contract
     * every one of their flows behaves correctly (owner self/add allowed,
     * non-owner bootstrap allowed). The v2.9.0 owner-bootstrap early
     * return (target == owner always allowed) is intentionally dropped:
     * it is subsumed by cases 1+2, and a NON-owner session entering the
     * OWNER's number is now correctly treated as an additive attempt
     * (case 4).
     */
    private static function assertMultiAccountAllowed(Request $request, string $targetPhone): void
    {
        $holder = Auth::optionalUser($request);
        if ($holder === null) {
            return; // case 1: bootstrap — authentication is always open
        }
        $holderPhone = (string) ($holder['phone'] ?? '');
        if ($holderPhone === $targetPhone) {
            return; // case 2: self re-auth
        }
        if ($holderPhone === self::MULTI_ACCOUNT_OWNER_PHONE) {
            return; // case 3: the owner's live session keys multi-account
        }
        throw new ApiError('MULTI_ACCOUNT_FORBIDDEN', 'Adding another account requires the +11 1130 session', 403);
    }

    /** @return array<string,mixed> */
    public static function checkPhone(Request $request): array
    {
        $phone = Validator::xoPhone($request->input('phone'));
        self::assertMultiAccountAllowed($request, $phone);
        // Every check burns a slot: 30/hour/number, then 1 h lockout —
        // enough for any legitimate login flow, hostile to number farming.
        Throttle::hit('check:' . $phone, self::CHECK_MAX, 3600, 3600);

        $row = Db::fetch('SELECT password_hint FROM users WHERE phone = ? LIMIT 1', [$phone]);
        if ($row === null) {
            return ['registered' => false];
        }
        $hint = $row['password_hint'] !== null ? trim((string) $row['password_hint']) : '';
        return [
            'registered'    => true,
            'password_hint' => $hint !== '' ? $hint : null,
        ];
    }

    /** @return array<string,mixed> */
    public static function register(Request $request): array
    {
        $phone = Validator::xoPhone($request->input('phone'));
        self::assertMultiAccountAllowed($request, $phone);
        $password = Validator::str($request->input('password'), 'password', self::PASSWORD_MIN, self::PASSWORD_MAX);
        $hint = Validator::optStr($request->input('hint'), 'hint', self::HINT_MAX);

        Throttle::guard('register:' . $phone, self::REGISTER_MAX, 3600, 3600);

        if (Db::fetch('SELECT id FROM users WHERE phone = ? LIMIT 1', [$phone]) !== null) {
            throw new ApiError('ACCOUNT_EXISTS', 'This number is already registered; log in with your password', 409);
        }

        $hash = password_hash($password, PASSWORD_DEFAULT);
        $name = 'User' . substr(md5($phone . random_int(0, PHP_INT_MAX)), 0, 4);

        try {
            $result = Db::transaction(static function () use ($phone, $hash, $hint, $name): array {
                // v1.7 semantics preserved: explicit id, 5-digit floor,
                // retry on concurrent-race PK collision.
                $attempts = 0;
                do {
                    $maxRow = Db::fetch('SELECT COALESCE(MAX(id), 9999) AS m FROM users');
                    $userId = max((int) ($maxRow['m'] ?? 9999) + 1, 10000);
                    try {
                        Db::insert('users', [
                            'id'            => $userId,
                            'phone'         => $phone,
                            'display_name'  => $name,
                            'password_hash' => $hash,
                            'password_hint' => $hint,
                            'created_at'    => time(),
                        ]);
                        break;
                    } catch (\Throwable $e) {
                        // Same phone inserted concurrently -> ACCOUNT_EXISTS.
                        if (Db::isDuplicate($e)) {
                            throw new ApiError('ACCOUNT_EXISTS', 'This number is already registered; log in with your password', 409);
                        }
                        if (++$attempts >= 3) {
                            throw $e;
                        }
                    }
                } while (true);

                $user = Db::fetch('SELECT * FROM users WHERE id = ?', [$userId]);
                if ($user === null) {
                    throw new ApiError('SERVER_ERROR', 'User creation failed', 500);
                }
                return ['user' => $user] + Auth::issuePair((int) $user['id']);
            });
        } catch (ApiError $e) {
            if ($e->getErrorCode() === 'ACCOUNT_EXISTS') {
                Throttle::fail('register:' . $phone, self::REGISTER_MAX, 3600, 3600);
            }
            throw $e;
        }

        Throttle::clear('register:' . $phone);
        return [
            'is_new_user'   => true,
            'user'          => UserMapper::selfJson($result['user']),
            'access_token'  => $result['access_token'],
            'refresh_token' => $result['refresh_token'],
            'expires_in'    => $result['expires_in'],
        ];
    }

    /** @return array<string,mixed> */
    public static function login(Request $request): array
    {
        $phone = Validator::xoPhone($request->input('phone'));
        self::assertMultiAccountAllowed($request, $phone);
        $password = Validator::str($request->input('password'), 'password', 1, self::PASSWORD_MAX);

        Throttle::guard('login:' . $phone, self::LOGIN_MAX_ATTEMPTS, self::LOGIN_WINDOW, self::LOGIN_LOCKOUT);

        $user = Db::fetch('SELECT * FROM users WHERE phone = ? LIMIT 1', [$phone]);
        $hash = $user['password_hash'] ?? null;
        if ($user === null || !is_string($hash) || $hash === '') {
            // Unknown number or password-less row: identical observable
            // behaviour (uniform error, same bcrypt work) either way.
            password_verify($password, self::dummyHash());
            Throttle::fail('login:' . $phone, self::LOGIN_MAX_ATTEMPTS, self::LOGIN_WINDOW, self::LOGIN_LOCKOUT);
            throw new ApiError('PASSWORD_INVALID', 'Wrong password', 400);
        }

        if (!password_verify($password, $hash)) {
            Throttle::fail('login:' . $phone, self::LOGIN_MAX_ATTEMPTS, self::LOGIN_WINDOW, self::LOGIN_LOCKOUT);
            throw new ApiError('PASSWORD_INVALID', 'Wrong password', 400);
        }

        // Transparent rehash when the PHP default algorithm/cost moved.
        if (password_needs_rehash($hash, PASSWORD_DEFAULT)) {
            Db::run('UPDATE users SET password_hash = ? WHERE id = ?',
                [password_hash($password, PASSWORD_DEFAULT), (int) $user['id']]);
        }

        Throttle::clear('login:' . $phone);
        return ['user' => UserMapper::selfJson($user)] + Auth::issuePair((int) $user['id']);
    }

    /** @return array<string,mixed> */
    public static function refresh(Request $request): array
    {
        $refreshToken = Validator::str($request->input('refresh_token'), 'refresh_token', 32, 128);

        $rotated = Auth::rotateRefresh($refreshToken);
        Auth::revoke($refreshToken);

        $user = Db::fetch('SELECT * FROM users WHERE id = ?', [$rotated['user_id']]);
        if ($user === null) {
            throw new ApiError('UNAUTHORIZED', 'User no longer exists', 401);
        }

        return [
            'user'          => UserMapper::selfJson($user),
            'access_token'  => $rotated['access_token'],
            'refresh_token' => $rotated['refresh_token'],
            'expires_in'    => $rotated['expires_in'],
        ];
    }

    /** @return array<string,mixed> */
    public static function me(Request $request): array
    {
        $user = Auth::requireUser($request);
        return ['user' => UserMapper::selfJson($user)];
    }

    /** @return array<string,mixed> */
    public static function verifyPassword(Request $request): array
    {
        $me = Auth::requireUser($request);
        $password = Validator::str($request->input('password'), 'password', 1, self::PASSWORD_MAX);
        self::assertCurrentPassword($me, $password);
        return ['valid' => true];
    }

    /** @return array<string,mixed> */
    public static function changePassword(Request $request): array
    {
        $me = Auth::requireUser($request);
        $current = Validator::str($request->input('current_password'), 'current_password', 1, self::PASSWORD_MAX);
        $new = Validator::str($request->input('new_password'), 'new_password', self::PASSWORD_MIN, self::PASSWORD_MAX);
        // 'hint' honoured only when the key is present: null/""
        // clears it, an absent key keeps the stored one.
        $hintProvided = array_key_exists('hint', $request->getBody());
        $hint = $hintProvided ? Validator::optStr($request->input('hint'), 'hint', self::HINT_MAX) : null;

        self::assertCurrentPassword($me, $current);

        $userId = (int) $me['id'];
        $newHash = password_hash($new, PASSWORD_DEFAULT);

        // One era boundary: new hash + ver bump + ALL refresh tokens dead.
        // The fresh pair below re-admits only the calling device.
        Db::transaction(static function () use ($userId, $newHash, $hint, $hintProvided): void {
            if ($hintProvided) {
                Db::run('UPDATE users SET password_hash = ?, password_hint = ?, token_ver = token_ver + 1 WHERE id = ?',
                    [$newHash, $hint, $userId]);
            } else {
                Db::run('UPDATE users SET password_hash = ?, token_ver = token_ver + 1 WHERE id = ?',
                    [$newHash, $userId]);
            }
            Auth::revokeAllSessions($userId);
        });

        $fresh = Db::fetch('SELECT * FROM users WHERE id = ?', [$userId]);
        return ['user' => UserMapper::selfJson($fresh ?? $me)] + Auth::issuePair($userId);
    }

    /** Shared wrong-current-password path (verify + change): uniform + throttled. */
    private static function assertCurrentPassword(array $me, string $password): void
    {
        $key = 'pwchange:' . (int) $me['id'];
        Throttle::guard($key, self::LOGIN_MAX_ATTEMPTS, self::LOGIN_WINDOW, self::LOGIN_LOCKOUT);

        $hash = $me['password_hash'] ?? null;
        if (!is_string($hash) || $hash === '' || !password_verify($password, $hash)) {
            Throttle::fail($key, self::LOGIN_MAX_ATTEMPTS, self::LOGIN_WINDOW, self::LOGIN_LOCKOUT);
            throw new ApiError('PASSWORD_INVALID', 'Wrong password', 400);
        }
    }

    private static function dummyHash(): string
    {
        if (self::$dummyHash === null) {
            self::$dummyHash = password_hash('xo-dummy-password', PASSWORD_DEFAULT);
        }
        return self::$dummyHash;
    }
}

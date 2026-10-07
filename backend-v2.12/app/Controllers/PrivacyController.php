<?php

declare(strict_types=1);

namespace App\Controllers;

use App\Core\ApiError;
use App\Core\Auth;
use App\Core\Db;
use App\Core\PrivacyService;
use App\Core\Request;
use App\Core\Validator;
use App\Mappers\UserMapper;

/**
 * v2.10.0 (T61) privacy rules endpoints — endpoint wrappers reconstructed in
 * T80 (they were absent from the backend copy pulled in T75; the controller
 * logic always lived in App\Core\PrivacyService).
 *
 *   GET  /privacy/get.php -> the caller's WHOLE rule set in one payload:
 *        {ok, rules:{<key>:{base, allowed[], disallowed[]}}, users:[…],
 *        updated_at}
 *   POST /privacy/set.php {key, base, allowed?, disallowed?} — write ONE
 *        key's rule; answers the same shape as privacy/get (the dispatcher
 *        filters per request key client-side).
 *
 * Keys/bases are validated inside PrivacyService (single source of truth).
 */
final class PrivacyController
{
    /** GET /privacy/get.php */
    public static function get(Request $request): array
    {
        $me = Auth::requireUser($request);
        $meId = (int) $me['id'];

        return self::rulesPayload($meId);
    }

    /** POST /privacy/set.php {key, base, allowed?, disallowed?} */
    public static function set(Request $request): array
    {
        $me = Auth::requireUser($request);
        $meId = (int) $me['id'];

        $key = Validator::str($request->input('key'), 'key', 1, 40);
        $base = Validator::str($request->input('base'), 'base', 1, 20);
        $allowed = self::idList($request->input('allowed'), 'allowed');
        $disallowed = self::idList($request->input('disallowed'), 'disallowed');

        PrivacyService::setRule($meId, $key, $base, $allowed, $disallowed);

        return self::rulesPayload($meId);
    }

    /** @return array<string, mixed> */
    private static function rulesPayload(int $meId): array
    {
        $rules = [];
        $exceptionIds = [];
        $updatedAt = 0;
        foreach (PrivacyService::rulesFor($meId) as $key => $row) {
            $allowed = self::decodeIds($row['allowed'] ?? null);
            $disallowed = self::decodeIds($row['disallowed'] ?? null);
            $rules[$key] = [
                'base'       => (string) $row['base'],
                'allowed'    => $allowed,
                'disallowed' => $disallowed,
            ];
            foreach (array_merge($allowed, $disallowed) as $uid) {
                $exceptionIds[$uid] = true;
            }
            $updatedAt = max($updatedAt, (int) ($row['updated_at'] ?? 0));
        }

        // The users array carries the public json of every exception user
        // referenced by any rule (upstream putUsers hydrates them).
        $users = [];
        foreach (array_keys($exceptionIds) as $uid) {
            $row = Db::fetch('SELECT * FROM users WHERE id = ?', [$uid]);
            if ($row !== null) {
                $users[] = UserMapper::publicJsonForViewer($row, $meId);
            }
        }

        return [
            'rules'      => $rules,
            'users'      => $users,
            'updated_at' => $updatedAt,
        ];
    }

    /** @return list<int> */
    private static function idList(mixed $raw, string $field): array
    {
        if ($raw === null) {
            return [];
        }
        if (!is_array($raw)) {
            throw new ApiError('VALIDATION_ERROR', "{$field} must be an array of user ids", 400, ['field' => $field]);
        }
        $out = [];
        foreach ($raw as $v) {
            $out[] = Validator::int($v, $field, 1);
        }
        return $out;
    }

    /** @return list<int> */
    private static function decodeIds(mixed $json): array
    {
        if ($json === null || $json === '') {
            return [];
        }
        $decoded = json_decode((string) $json, true);
        if (!is_array($decoded)) {
            return [];
        }
        return array_values(array_map('intval', $decoded));
    }
}

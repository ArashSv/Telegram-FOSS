<?php

declare(strict_types=1);

namespace App\Controllers;

use App\Core\Auth;
use App\Core\Config;
use App\Core\Db;
use App\Core\Presence;
use App\Core\Request;
use App\Core\UpdateQueue;
use App\Core\Validator;
use App\Mappers\MessageMapper;

/**
 * GET /sync/index.php?cursor=N&limit=200
 *
 * The heart of the short-polling architecture (Architecture A):
 *   - returns every queued update with id > cursor, ordered ascending
 *   - the returned `cursor` is the id of the last delivered row (or the
 *     requested cursor when nothing new); the client MUST persist it
 *   - each row is expanded into a self-contained update: message_new /
 *     message_edit carry the FULL message object, so no follow-up call
 *     is needed (important on slow, expensive polls)
 *   - ~2% of calls opportunistically purge rows older than
 *     sync.retention_days so the table stays small without a cron job
 */
final class SyncController
{
    /** @return array<string,mixed> */
    public static function index(Request $request): array
    {
        $me = Auth::requireUser($request);
        $meId = (int) $me['id'];

        // v2.5.0 (T49): presence piggyback — the poll the client already
        // sends while foregrounded doubles as the presence heartbeat. ZERO
        // extra requests: Presence::touch is a throttled conditional UPDATE
        // (<= 1 write / 60 s / user) that also detects the offline->online
        // transition and fans a 'user_status' update out to chat peers.
        // Backgrounded pollers send presence=0 and never refresh the stamp,
        // so a backgrounded session ages out instead of looking online.
        $presence = $request->q('presence');
        Presence::touch($meId, $presence !== '0');

        $cursor = Validator::optInt($request->q('cursor'), 'cursor') ?? 0;
        $limit = Validator::optInt($request->q('limit'), 'limit')
            ?? (int) Config::get('sync.default_limit', 200);
        $limit = max(1, min($limit, (int) Config::get('sync.max_limit', 500)));

        $rows = Db::fetchAll(
            'SELECT * FROM update_queue
             WHERE id > ? AND (user_id IS NULL OR user_id = ?)
             ORDER BY id ASC
             LIMIT ' . $limit,
            [$cursor, $meId],
        );

        $updates = [];
        $newCursor = $cursor;
        foreach ($rows as $row) {
            $newCursor = (int) $row['id'];
            $update = self::expand($row, $me);
            if ($update !== null) {
                $updates[] = $update;
            }
        }

        if (mt_rand(1, 50) === 1) {
            $cutoff = time() - ((int) Config::get('sync.retention_days', 7) * 86400);
            Db::run('DELETE FROM update_queue WHERE created_at < ?', [$cutoff]);
        }

        // v2.11.3 (T76): the LAST-SEEN GUARANTEE — sessions that died without
        // the explicit offline POST (killed app, dead battery) age out here
        // with ONE user_status offline fan-out to peers, on the same
        // opportunistic cadence as the retention purge. No cron, no cost on
        // the hot path (the sweep only runs on 1 in 50 polls).
        if (mt_rand(1, 50) === 1) {
            Presence::sweepAgedSessions();
        }

        return [
            'cursor'      => $newCursor,
            'updates'     => $updates,
            'server_time' => time(),
        ];
    }

    /**
     * Convert an update_queue row into the client-facing update object.
     *
     * @param array<string,mixed> $row
     * @param array<string,mixed> $me
     * @return array<string,mixed>|null
     */
    private static function expand(array $row, array $me): ?array
    {
        $payload = json_decode((string) ($row['payload'] ?? '{}'), true);
        if (!is_array($payload)) {
            $payload = [];
        }
        $type = (string) $row['type'];

        $update = [
            'update_id' => (int) $row['id'],
            'type'      => $type,
        ];

        switch ($type) {
            case UpdateQueue::TYPE_MESSAGE_NEW:
            case UpdateQueue::TYPE_MESSAGE_EDIT:
                $messageRow = MessageMapper::findById((int) ($payload['message_id'] ?? 0));
                if ($messageRow === null) {
                    return null;
                }
                $update['chat_id'] = (int) $messageRow['chat_id'];
                $update['message'] = MessageMapper::json($messageRow);
                return $update;

            case UpdateQueue::TYPE_MESSAGE_DELETE:
                $update['chat_id'] = (int) ($payload['chat_id'] ?? 0);
                $update['message_ids'] = array_map('intval', (array) ($payload['message_ids'] ?? []));
                $update['by_user_id'] = (int) ($payload['by_user_id'] ?? 0);
                return $update;

            case UpdateQueue::TYPE_READ:
                $update['chat_id'] = (int) ($payload['chat_id'] ?? 0);
                $update['user_id'] = (int) ($payload['user_id'] ?? 0);
                $update['max_id'] = (int) ($payload['max_id'] ?? 0);
                return $update;

            case UpdateQueue::TYPE_CHAT_NEW:
                $chat = ChatsController::chatPayload($me, (int) ($payload['chat_id'] ?? 0));
                if ($chat === null) {
                    return null;
                }
                $update['chat'] = $chat;
                return $update;

            case UpdateQueue::TYPE_CHAT_MODE:
                // v2.12.0 (T80): a private chat's encryption mode flipped.
                // Passed through as queued — {chat_id, mode, by_user_id}; the
                // client updates RestChatIndex.chatModes and refreshes the UI.
                $update['chat_id']    = (int) ($payload['chat_id'] ?? 0);
                $update['mode']       = ((string) ($payload['mode'] ?? 'cloud')) === 'secret' ? 'secret' : 'cloud';
                $update['by_user_id'] = (int) ($payload['by_user_id'] ?? 0);
                if ($update['chat_id'] <= 0) {
                    return null;
                }
                return $update;

            case UpdateQueue::TYPE_USER_UPDATED:
                // v1.5: profile changes (name/avatar) carry the full public
                // user json - no follow-up call needed. Kept verbatim from
                // the queue: re-hydrating here would double the photo lookups.
                $user = $payload['user'] ?? null;
                if (!is_array($user) || (int) ($user['id'] ?? 0) <= 0) {
                    return null;
                }
                $update['user'] = $user;
                return $update;

            case UpdateQueue::TYPE_CHAT_MEMBER:
                // v2.2 (T40): group membership changed around the recipient.
                // Deliberately ids-only (see UpdateQueue::TYPE_CHAT_MEMBER):
                // kick/leave targets may already be out of the chat, so no
                // chat/user payload can ride along. The client decides:
                // self-removal applies locally, everything else re-pulls the
                // authoritative snapshot from chats/members.php.
                $update['chat_id']  = (int) ($payload['chat_id'] ?? 0);
                $update['event']    = (string) ($payload['event'] ?? '');
                $update['user_id']  = (int) ($payload['user_id'] ?? 0);
                $update['actor_id'] = (int) ($payload['actor_id'] ?? 0);
                if ($update['chat_id'] <= 0 || $update['event'] === '') {
                    return null;
                }
                return $update;

            case UpdateQueue::TYPE_GIFS:
                // v2.3 (T42): the recipient's saved-GIF collection changed on
                // another device. The payload carries no list — the client
                // force-reloads gifs/list.php (the collection is capped at 30
                // rows, the reload is cheap and always authoritative).
                $update['user_id'] = (int) ($payload['user_id'] ?? $me['id']);
                return $update;

            case UpdateQueue::TYPE_USER_STATUS:
                // v2.5.0 (T49): a chat peer's presence flipped. Small ids+
                // flags payload, applied in place by the poller (UPDATE_MASK
                // _STATUS refreshes every open header/profile). Delivered as
                // queued — no re-hydration, the subject's user row may be
                // unknown to this recipient.
                $update['user_id']    = (int) ($payload['user_id'] ?? 0);
                $update['online']     = (bool) ($payload['online'] ?? false);
                if ($update['user_id'] <= 0) {
                    return null;
                }
                if ($update['online']) {
                    // Online carries no timestamp by design: the client keeps
                    // rendering "Online" and self-heals to the last known
                    // "last seen" when the expiry lapses (session death).
                    $update['expires'] = (int) ($payload['expires'] ?? 0);
                } else {
                    $update['was_online'] = (int) ($payload['was_online'] ?? 0);
                }
                return $update;

            case UpdateQueue::TYPE_DIALOG_PIN:
                // v2.7.0 (T56): the owner's dialog pin changed on another
                // device. Small ids+flags payload applied in place by the
                // poller (TL_updateDialogPinned); only ever delivered to the
                // pin owner.
                $update['chat_id']   = (int) ($payload['chat_id'] ?? 0);
                $update['pinned']    = (bool) ($payload['pinned'] ?? false);
                $update['pinned_at'] = (int) ($payload['pinned_at'] ?? 0);
                if ($update['chat_id'] <= 0) {
                    return null;
                }
                return $update;

            case UpdateQueue::TYPE_PRIVACY:
                // v2.10.0 (T61): MY privacy rule set changed on another
                // device. Deliberately payloadless — the client answers by
                // re-pulling privacy/get.php (hydrating the exception users)
                // and re-applying every key through setPrivacyRules. Only
                // ever delivered to the rule owner.
                return $update;

            case UpdateQueue::TYPE_BLOCKED:
                // v2.10.0 (T61): MY blocked list changed on another device.
                // {user_id, blocked, user?, date?} — merged in place by
                // UpdatePoller.handleBlockedChanged (blockePeers + count +
                // blockedUsersDidLoad). Only ever delivered to the blocker.
                $update['user_id'] = (int) ($payload['user_id'] ?? 0);
                if ($update['user_id'] <= 0) {
                    return null;
                }
                $update['blocked'] = (bool) ($payload['blocked'] ?? true);
                if (isset($payload['date'])) {
                    $update['date'] = (int) $payload['date'];
                }
                if (isset($payload['user']) && is_array($payload['user'])) {
                    $update['user'] = $payload['user'];
                }
                return $update;

            default:
                return null;
        }
    }
}

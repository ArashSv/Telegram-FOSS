<?php

declare(strict_types=1);

namespace App\Core;

/**
 * The global update stream (update_queue table).
 *
 * Every state change the other side must learn about (new/edited/deleted
 * message, read receipt, new chat) inserts one row per recipient; the row's
 * AUTO_INCREMENT id IS the sync cursor the client polls with.
 *
 * Design contract for the Android client:
 *   - ids are strictly increasing  -> "give me everything after cursor X"
 *   - payload JSON contains everything needed to render the update without
 *     a follow-up API call (message_new / message_edit carry the full message)
 *   - rows are retention-purged after sync.retention_days (7 by default);
 *     a client that was offline longer must re-fetch chats + history
 */
final class UpdateQueue
{
    public const TYPE_MESSAGE_NEW    = 'message_new';
    public const TYPE_MESSAGE_EDIT   = 'message_edit';
    public const TYPE_MESSAGE_DELETE = 'message_delete';
    public const TYPE_READ           = 'read';
    public const TYPE_CHAT_NEW       = 'chat_new';
    /** v1.5: a user's profile (name/avatar) changed; payload {user: {...}}. */
    public const TYPE_USER_UPDATED   = 'user_updated';
    /**
     * v2.2 (T40): group membership changed around the recipient — payload
     * {chat_id, event: kick|leave|promote|demote|deleted, user_id, actor_id}.
     * ids only: the affected user may already be OUT of the chat (kick/leave),
     * so a chatPayload snapshot is impossible for them — the client applies
     * ids locally (self-removal deletes the dialog) or re-pulls the
     * authoritative membership snapshot via chats/members.php.
     */
    public const TYPE_CHAT_MEMBER    = 'chat_member';
    /**
     * v2.3 (T42): the recipient's OWN saved-GIF collection changed on another
     * device (save/unsave). Deliberately payloadless beyond the owner id —
     * the client answers it by force-reloading gifs/list.php. Only ever
     * pushed to the collection owner (not a broadcast).
     */
    public const TYPE_GIFS           = 'gifs';
    /**
     * v2.5.0 (T49): a chat peer's presence flipped (online/offline).
     * Payload {user_id, online: bool, was_online?: unix-ts}. Emitted ONLY on
     * real derived-state transitions by App\Core\Presence (sync-piggyback
     * touch or explicit client screen-on/off signal) and ONLY to users who
     * share a chat with the subject — presence never broadcasts and never
     * polls; stale "online" labels self-heal client-side via the online
     * expiry, matching Telegram's own update model.
     */
    public const TYPE_USER_STATUS    = 'user_status';
    /**
     * v2.12.0 (T80): a private chat's encryption mode flipped (cloud <-> secret).
     * Payload {chat_id, mode: 'cloud'|'secret', by_user_id, at}. Pushed to
     * BOTH members — each account's other devices must converge too. The
     * client answers it by updating RestChatIndex.chatModes and refreshing
     * the chat UI; no content is carried.
     */
    public const TYPE_CHAT_MODE      = 'chat_mode';

    /** @param list<int> $userIds @param array<string,mixed> $payload */
    public static function push(array $userIds, string $type, array $payload): void
    {
        $userIds = array_values(array_unique(array_map('intval', $userIds)));
        if ($userIds === []) {
            return;
        }
        $json = json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        $now = time();
        foreach ($userIds as $userId) {
            Db::insert('update_queue', [
                'user_id'    => $userId,
                'type'       => $type,
                'payload'    => $json,
                'created_at' => $now,
            ]);
        }
    }

    /**
     * Push an update to every member of a chat except $exceptUserId.
     * MUST be called inside the same transaction as the state change.
     *
     * @param array<string,mixed> $payload
     * @return list<int> the recipient ids that the update was queued for
     */
    public static function pushToChatExcept(int $chatId, int $exceptUserId, string $type, array $payload): array
    {
        $rows = Db::fetchAll(
            'SELECT user_id FROM chat_members WHERE chat_id = ? AND user_id <> ?',
            [$chatId, $exceptUserId],
        );
        $userIds = array_map(static fn (array $r): int => (int) $r['user_id'], $rows);
        self::push($userIds, $type, $payload);
        return $userIds;
    }
}

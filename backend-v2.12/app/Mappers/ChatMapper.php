<?php

declare(strict_types=1);

namespace App\Mappers;

use App\Core\AvatarService;

/**
 * chats row + aggregated extras -> JSON (see docs/API.md "Object shapes").
 */
final class ChatMapper
{
    /**
     * @param array<string,mixed>      $row         chats row (+ role, my_last_read aliases)
     * @param array<string,mixed>|null $lastMessage MessageMapper::json() result or null
     * @param array<string,mixed>|null $peer        peer user (private chats only)
     */
    public static function json(
        array $row,
        ?array $lastMessage,
        int $unreadCount,
        ?array $peer,
        int $membersCount,
    ): array {
        return [
            'id'             => (int) $row['id'],
            'type'           => (string) $row['type'],
            // v2.12.0 (T80): the chat's encryption mode — 'cloud' (server-side
            // at-rest AES-256-GCM, default) or 'secret' (client E2EE). Rows
            // from before the mode column exist report 'cloud' (their content
            // is plain/XOE1, never XOC1).
            'mode'           => ((string) ($row['mode'] ?? 'cloud')) === 'secret' ? 'secret' : 'cloud',
            'title'          => $row['title'] !== null ? (string) $row['title'] : null,
            // v1.8: group description ("About"/Bio); private chats carry null.
            // The client's getFullChat route maps this onto TL_chatFull.about.
            'about'          => isset($row['about']) && $row['about'] !== null && trim((string) $row['about']) !== ''
                ? (string) $row['about']
                : null,
            'avatar_file_id' => $row['avatar_file_id'] !== null ? (int) $row['avatar_file_id'] : null,
            'photo'          => AvatarService::photoJson(
                $row['avatar_file_id'] !== null ? (int) $row['avatar_file_id'] : null,
            ),
            'created_at'     => (int) $row['created_at'],
            'role'           => (string) $row['role'],
            'members_count'  => $membersCount,
            'unread_count'   => $unreadCount,
            'last_message'   => $lastMessage,
            'peer'           => $peer,
        ];
    }
}

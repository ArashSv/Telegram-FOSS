<?php

declare(strict_types=1);

namespace App\Mappers;

use App\Core\CloudCrypto;
use App\Core\Db;

/**
 * Messages table -> JSON. Always join the media file so the client can
 * render media messages without a second request.
 */
final class MessageMapper
{
    /**
     * Base SELECT used by every message fetch (history, sync, send response).
     * Aliases prefixed m_ belong to the joined files row.
     * v1.1: media metadata columns joined so the client can render photos
     * (dimensions + thumb) and documents without a second request.
     */
    public const SELECT_WITH_MEDIA =
        'SELECT m.*, f.mime_type AS m_mime, f.size AS m_size, f.name AS m_name,
                f.kind AS m_kind, f.width AS m_width, f.height AS m_height,
                f.duration AS m_duration, f.thumb_file_id AS m_thumb_file_id,
                f.sha256 AS m_sha256
         FROM messages m
         LEFT JOIN files f ON f.id = m.media_file_id';

    /** @param array<string,mixed> $row @return array<string,mixed> */
    public static function json(array $row): array
    {
        $mediaFileId = $row['media_file_id'] !== null ? (int) $row['media_file_id'] : null;

        $media = null;
        if ($mediaFileId !== null) {
            $media = [
                'file_id'       => $mediaFileId,
                'mime_type'     => $row['m_mime'] ?? null,
                'size'          => (int) ($row['m_size'] ?? 0),
                'name'          => $row['m_name'] ?? null,
                'kind'          => $row['m_kind'] ?? null,
                'width'         => $row['m_width'] !== null ? (int) $row['m_width'] : null,
                'height'        => $row['m_height'] !== null ? (int) $row['m_height'] : null,
                'duration'      => $row['m_duration'] !== null ? (int) $row['m_duration'] : null,
                'thumb_file_id' => $row['m_thumb_file_id'] !== null ? (int) $row['m_thumb_file_id'] : null,
                // v1.4: the SERVER-ATTESTED content hash ships with every media
                // message. The client verifies the downloaded bytes against it
                // before declaring the download successful — a truncated or
                // mangled transfer can no longer pose as a complete file.
                'sha256'        => $row['m_sha256'] !== null ? (string) $row['m_sha256'] : null,
            ];
        }

        return [
            'id'          => (int) $row['id'],
            'chat_id'     => (int) $row['chat_id'],
            'sender_id'   => (int) $row['sender_id'],
            // v2.4.2 (T48): media messages carry no text, but JSON null used to
            // reach the client's optString() as the literal string "null" and
            // was rendered as a bogus "null" caption under every photo/video/
            // gif/file. Ship "" instead — empty caption, never a stringified
            // null (the client parse also guards with isNull()).
            //
            // v2.12.0 (T80): the SINGLE decrypt boundary for cloud-chat at-rest
            // envelopes. XOC1: rows open here (every read path — history, sync,
            // send ack, forward, last_message — flows through json()); legacy
            // plaintext and secret-chat XOE1: rows pass through untouched.
            // Tag/key failure degrades to '' with a security log, never an
            // exception into the read path.
            'content'     => isset($row['chat_id'])
                ? self::renderableContent((int) $row['chat_id'], (int) $row['id'], $row['content'])
                : ($row['content'] !== null ? (string) $row['content'] : ''),
            'media'       => $media,
            'reply_to_id' => $row['reply_to_id'] !== null ? (int) $row['reply_to_id'] : null,
            'edited_at'   => $row['edited_at'] !== null ? (int) $row['edited_at'] : null,
            'deleted_at'  => $row['deleted_at'] !== null ? (int) $row['deleted_at'] : null,
            'date'        => (int) $row['created_at'], // TLRPC-style unix timestamp
        ];
    }

    /** @return array<string,mixed>|null */
    public static function findById(int $messageId): ?array
    {
        return Db::fetch(self::SELECT_WITH_MEDIA . ' WHERE m.id = ?', [$messageId]);
    }

    /**
     * v2.12.0 (T80): open the cloud at-rest envelope for rendering. Non-XOC1
     * content (legacy plaintext, secret XOE1:) passes through byte-exact.
     *
     * NOTE: callers that need the RAW stored form (forward re-encryption
     * decisions) must read the row directly — json() always renders.
     */
    public static function renderableContent(int $chatId, int $msgId, mixed $stored): string
    {
        if ($stored === null) {
            return '';
        }
        $plain = CloudCrypto::decryptForChat($chatId, $msgId, (string) $stored);
        return $plain === null ? '' : $plain;
    }
}

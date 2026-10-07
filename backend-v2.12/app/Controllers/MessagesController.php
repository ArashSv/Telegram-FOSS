<?php

declare(strict_types=1);

namespace App\Controllers;

use App\Core\ApiError;
use App\Core\Auth;
use App\Core\CloudCrypto;
use App\Core\Db;
use App\Core\PrivacyService;
use App\Core\Request;
use App\Core\UpdateQueue;
use App\Core\Validator;
use App\Mappers\MessageMapper;

/**
 * Messages: send, history (initial load + delta sync), edit, delete, read.
 *
 * Cursor model (contract for the Android client):
 *   - messages.id is a per-chat cursor:  history.php?since_id=X returns
 *     every message with id > X in ascending order (delta catch-up).
 *   - history.php?max_id=Y&limit=N returns the newest N messages up to Y
 *     (initial load / scroll-back; has_older says whether older exist).
 *   - global events reach the client through /sync/index.php (update_queue).
 */
final class MessagesController
{
    // v2.11.0 (T71): 16384 — E2EE envelopes (XOE1:… base64) are opaque to the
    // server and need the headroom; messages.content is TEXT (64 KB).
    private const MAX_CONTENT_LENGTH = 16384;
    private const MAX_HISTORY_LIMIT = 100;

    // v2.12.0 (T80): the two chat modes. chats.mode is the authority; every
    // private chat created before T80 backfills to 'cloud' via the column
    // default. Group/channel rows keep 'cloud' (they never had another mode).
    public const MODE_CLOUD = 'cloud';
    public const MODE_SECRET = 'secret';

    /**
     * v2.12.0 (T80): the chat's encryption mode. Reads the row defensively
     * (c.* + ?? default) so the API stays up even during the deploy window
     * before the chats.mode migration has run — pre-T80 rows are cloud by
     * definition (their content is plain or XOE1:, never XOC1:).
     */
    private static function chatMode(int $chatId): string
    {
        $row = Db::fetch('SELECT c.* FROM chats c WHERE c.id = ?', [$chatId]);
        if ($row === null) {
            throw new ApiError('NOT_FOUND', 'Chat not found', 404);
        }
        $mode = (string) ($row['mode'] ?? self::MODE_CLOUD);
        return $mode === self::MODE_SECRET ? self::MODE_SECRET : self::MODE_CLOUD;
    }

    /**
     * v2.12.0 (T80): server-side guard making the mode split REAL.
     *  - secret chats accept ONLY client envelopes (the server cannot read
     *    them — the no-plaintext guarantee now holds on the wire, not just
     *    in the client);
     *  - cloud chats refuse envelopes outright (a stale pre-T80 client
     *    cannot silently keep pushing libsignal ciphertext into a cloud
     *    chat, which the peer would render as a broken 🔒 row).
     */
    private static function assertContentForMode(string $mode, ?string $content, string $field = 'content'): void
    {
        if ($content === null || $content === '') {
            return;
        }
        $isEnvelope = str_starts_with($content, 'XOE1:');
        if ($mode === self::MODE_SECRET && !$isEnvelope) {
            throw new ApiError('E2EE_CONTENT_REQUIRED', 'This is a secret chat: messages must arrive as XOE1 envelopes', 400, ['field' => $field]);
        }
        if ($mode === self::MODE_CLOUD && $isEnvelope) {
            throw new ApiError('E2EE_CONTENT_REFUSED', 'This is a cloud chat: envelope content is not accepted (update the client)', 400, ['field' => $field]);
        }
    }

    /**
     * v2.12.0 (T80): the storage transform for a cloud chat. Plaintext is
     * sealed under the server-held at-rest key; secret-chat envelopes are
     * stored verbatim (never touched, never wrapped).
     */
    private static function storeContent(int $chatId, int $msgId, string $mode, ?string $content): ?string
    {
        if ($content === null || $content === '') {
            return $content;
        }
        return $mode === self::MODE_CLOUD
            ? CloudCrypto::encrypt($chatId, $msgId, $content)
            : $content;
    }

    /** POST /messages/send.php  {chat_id, content?, media_file_id?, reply_to_id?} */
    public static function send(Request $request): array
    {
        $me = Auth::requireUser($request);
        $meId = (int) $me['id'];

        $chatId = Validator::int($request->input('chat_id'), 'chat_id', 1);
        $content = Validator::optStr($request->input('content'), 'content', self::MAX_CONTENT_LENGTH);
        $mediaFileId = Validator::optInt($request->input('media_file_id'), 'media_file_id', 1);
        $replyToId = Validator::optInt($request->input('reply_to_id'), 'reply_to_id', 1);
        // v2.4.0 (T46): the client has uploaded media previews as separate small
        // files and passed them here since T14 — the link was silently dropped,
        // so video/gif messages shipped without a usable preview. Link it now
        // (server thumbs stay authoritative; this fills the gap for kinds the
        // host cannot decode, e.g. mp4 — no ffmpeg on the box).
        $thumbFileId = Validator::optInt($request->input('thumb_file_id'), 'thumb_file_id', 1);
        // v2.7.0 (T56): album grouping key. The client generates a 64-bit value
        // shared by every member of one album; a negative long is legal (TL
        // grouped_id is an arbitrary long), hence the signed bounds.
        $groupId = Validator::optInt($request->input('group_id'), 'group_id', PHP_INT_MIN, PHP_INT_MAX);

        if ($content === null && $mediaFileId === null) {
            throw new ApiError('VALIDATION_ERROR', 'Provide content and/or media_file_id', 400);
        }
        if ($thumbFileId !== null && $mediaFileId === null) {
            throw new ApiError('VALIDATION_ERROR', 'thumb_file_id requires media_file_id', 400, ['field' => 'thumb_file_id']);
        }

        if ($mediaFileId !== null) {
            // v2.9.1 (T57): the media must be a ready file OWNED BY THE SENDER.
            // send-multi.php always enforced this; plain send.php checked only
            // readiness, which let any user attach ANY ready file id (ids are
            // sequential) into a chat they control — the chat audience rule
            // would then let its members download a stranger's private file.
            // Re-sharing other people's media is the forward endpoint's job
            // (it verifies the source chat membership).
            $file = Db::fetch('SELECT id, status, owner_id FROM files WHERE id = ?', [$mediaFileId]);
            if ($file === null || $file['status'] !== 'ready') {
                throw new ApiError('VALIDATION_ERROR', 'media_file_id does not reference a ready (finalized) file', 400, ['field' => 'media_file_id']);
            }
            if ((int) $file['owner_id'] !== $meId) {
                throw new ApiError('FILE_ACCESS_DENIED', 'media_file_id must reference a file owned by the sender', 403, ['field' => 'media_file_id']);
            }
            if ($thumbFileId !== null) {
                // v2.11.2 (T75): kind may also be 'e2ee' — an encrypted chat's
                // thumbnail is an opaque AEAD blob the server cannot inspect
                // (zero-trust: even the MIME is out of server reach). The
                // strict kind='image' check silently killed EVERY single
                // video/GIF send into an encrypted chat with a 400 AFTER the
                // full upload had completed. Ownership + readiness still apply.
                $thumb = Db::fetch('SELECT id, owner_id, status, kind FROM files WHERE id = ?', [$thumbFileId]);
                $thumbKind = (string) ($thumb['kind'] ?? '');
                if ($thumb === null || (string) $thumb['status'] !== 'ready' || !in_array($thumbKind, ['image', 'e2ee'], true) || (int) $thumb['owner_id'] !== $meId) {
                    throw new ApiError('VALIDATION_ERROR', 'thumb_file_id must reference a ready image (or e2ee blob) owned by the sender', 400, ['field' => 'thumb_file_id']);
                }
            }
        }

        self::requireMembership($chatId, $meId, 'send messages to this chat');
        if (Db::fetch('SELECT id FROM chats WHERE id = ?', [$chatId]) === null) {
            throw new ApiError('NOT_FOUND', 'Chat not found', 404);
        }
        self::assertNotBlockedByPeers($chatId, $meId);

        // v2.12.0 (T80): the mode decides the whole content contract.
        $mode = self::chatMode($chatId);
        self::assertContentForMode($mode, $content);

        if ($replyToId !== null) {
            $reply = Db::fetch('SELECT id, chat_id FROM messages WHERE id = ?', [$replyToId]);
            if ($reply === null || (int) $reply['chat_id'] !== $chatId) {
                throw new ApiError('VALIDATION_ERROR', 'reply_to_id must reference a message in the same chat', 400, ['field' => 'reply_to_id']);
            }
        }

        $now = time();
        $messageId = Db::transaction(static function () use ($chatId, $meId, $content, $mediaFileId, $replyToId, $thumbFileId, $groupId, $now, $mode): int {
            // v2.12.0 (T80): plaintext never touches storage — the row starts
            // contentless and the sealed envelope is written in the SAME
            // transaction (secret-mode rows write their XOE1 envelope the
            // same way; the storage shape is uniform for both modes).
            $messageId = Db::insert('messages', [
                'chat_id'       => $chatId,
                'sender_id'     => $meId,
                'content'       => null,
                'media_file_id' => $mediaFileId,
                'reply_to_id'   => $replyToId,
                'group_id'      => $groupId,
                'created_at'    => $now,
            ]);
            if ($content !== null) {
                Db::run('UPDATE messages SET content = ? WHERE id = ?', [
                    self::storeContent($chatId, $messageId, $mode, $content),
                    $messageId,
                ]);
            }
            self::pushToChatAudience($chatId, $meId, UpdateQueue::TYPE_MESSAGE_NEW, [
                'chat_id'    => $chatId,
                'message_id' => $messageId,
            ]);
            // v2.4.0 (T46): link the client-provided preview (server thumbs win;
            // idempotent — the first link sticks, re-sends never overwrite).
            if ($thumbFileId !== null) {
                Db::run('UPDATE files SET thumb_file_id = ? WHERE id = ? AND thumb_file_id IS NULL', [$thumbFileId, $mediaFileId]);
            }
            return $messageId;
        });

        $row = MessageMapper::findById($messageId);
        if ($row === null) {
            throw new ApiError('SERVER_ERROR', 'Message vanished after insert', 500);
        }
        return ['message' => MessageMapper::json($row)];
    }

    /**
     * POST /messages/forward.php  {to_chat_id, message_ids:[1..100], drop_author?:bool}
     *
     * v2.7.0 (T56): Telegram-style forwarding. Every source message is copied
     * into the target chat BY REFERENCE (media_file_id points at the SAME
     * files row — no re-upload; the target-chat message row makes the file
     * visible to the target audience through the standard audience rule).
     *
     * Forward metadata: private-chat sources carry fwd_from_user_id (the
     * original sender); group sources carry fwd_from_chat_id (the group) plus
     * the original sender; forwards of forwards inherit the ORIGINAL origin
     * (never the intermediate copy). drop_author=true strips the header — the
     * copy presents itself as the forwarder's own message.
     *
     * Album membership is preserved: copies keep the source group_id, so an
     * album forwards as an album. reply_to context is intentionally dropped
     * (Telegram semantics). The response preserves the request order 1:1, so
     * the client can pair its random_id[] list with the returned rows.
     *
     * v2.9.1 (T57): drop_media_captions (TL_messages_forwardMessages'
     * drop_media_captions) is honoured — when true every copy's text
     * content is stripped (media-only forward). Until this version the flag
     * never reached the backend and captions re-appeared on the ack.
     */
    public static function forward(Request $request): array
    {
        $me = Auth::requireUser($request);
        $meId = (int) $me['id'];

        $toChatId = Validator::int($request->input('to_chat_id'), 'to_chat_id', 1);
        $raw = $request->input('message_ids');
        if (!is_array($raw) || $raw === [] || count($raw) > 100) {
            throw new ApiError('VALIDATION_ERROR', 'message_ids must be an array of 1..100 message ids', 400, ['field' => 'message_ids']);
        }
        $ids = array_values(array_unique(array_map(
            static fn ($v): int => Validator::int($v, 'message_ids', 1),
            $raw,
        )));
        $dropAuthor = $request->input('drop_author') === true;
        $dropCaptions = $request->input('drop_media_captions') === true;

        if (Db::fetch('SELECT id FROM chats WHERE id = ?', [$toChatId]) === null) {
            throw new ApiError('NOT_FOUND', 'Target chat not found', 404);
        }
        self::requireMembership($toChatId, $meId, 'send messages to this chat');
        self::assertNotBlockedByPeers($toChatId, $meId);

        // v2.12.0 (T80): the TARGET mode decides what a copy may contain.
        //  - cloud target: server-readable rows are decrypted and re-sealed
        //    under the new row id; secret-chat envelopes (XOE1:) cannot be
        //    opened server-side and are REFUSED here — the client converts
        //    them (decrypt → fresh plain send) before they reach this path.
        //  - secret target: only client-converted XOE1 copies are accepted
        //    (the client's re-encryption forward path already produces them).
        $targetMode = self::chatMode($toChatId);

        $now = time();
        $copied = [];
        $events = [];

        Db::transaction(static function () use ($ids, $meId, $toChatId, $targetMode, $dropAuthor, $dropCaptions, $now, &$copied, &$events): void {
            foreach ($ids as $msgId) {
                $src = MessageMapper::findById($msgId);
                if ($src === null || $src['deleted_at'] !== null) {
                    throw new ApiError('NOT_FOUND', "Message {$msgId} not found", 404, ['field' => 'message_ids']);
                }
                $srcChatId = (int) $src['chat_id'];

                // Reading the source is the permission that guards forwarding:
                // must be a member of the source chat AND the message must sit
                // above the user's own delete-chat boundary (a message the user
                // can no longer see cannot be forwarded).
                self::requireMembership($srcChatId, $meId, 'read the source chat');
                $hiddenBeforeId = Db::fetchColumn(
                    'SELECT hidden_before_id FROM hidden_dialogs WHERE user_id = ? AND chat_id = ?',
                    [$meId, $srcChatId],
                );
                if ($hiddenBeforeId !== null && $msgId <= (int) $hiddenBeforeId) {
                    throw new ApiError('FORBIDDEN', "Message {$msgId} is outside your visible history", 403);
                }

                $fwdUser = null;
                $fwdChat = null;
                $fwdName = null;
                $fwdMsgId = null;
                $fwdDate = null;
                if (!$dropAuthor) {
                    if ($src['fwd_date'] !== null) {
                        // Forward of a forward: inherit the ORIGINAL origin.
                        $fwdUser = $src['fwd_from_user_id'] !== null ? (int) $src['fwd_from_user_id'] : null;
                        $fwdChat = $src['fwd_from_chat_id'] !== null ? (int) $src['fwd_from_chat_id'] : null;
                        $fwdName = $src['fwd_from_name'];
                        $fwdMsgId = $src['fwd_from_msg_id'] !== null ? (int) $src['fwd_from_msg_id'] : null;
                        $fwdDate = (int) $src['fwd_date'];
                    } else {
                        $srcChat = Db::fetch('SELECT id, type, title FROM chats WHERE id = ?', [$srcChatId]);
                        if ($srcChat !== null && $srcChat['type'] !== 'private') {
                            $fwdChat = (int) $src['chat_id'];
                        }
                        $fwdUser = (int) $src['sender_id'];
                        $senderRow = Db::fetch('SELECT id, display_name, username FROM users WHERE id = ?', [(int) $src['sender_id']]);
                        if ($senderRow !== null) {
                            $name = trim((string) ($senderRow['display_name'] ?? ''));
                            if ($name === '' && ($senderRow['username'] ?? '') !== '') {
                                $name = (string) $senderRow['username'];
                            }
                            $fwdName = $name !== '' ? $name : null;
                        }
                        $fwdMsgId = (int) $src['id'];
                        $fwdDate = (int) $src['created_at'];
                    }
                }

                // v2.12.0 (T80): the copy's content obeys the target mode.
                // $src['content'] is the RAW stored form (XOC1 / XOE1 / legacy
                // plain) — renderable only through CloudCrypto for cloud rows.
                $copyContent = $dropCaptions ? null : $src['content'];
                if ($copyContent !== null && $copyContent !== '') {
                    $rawCopy = (string) $copyContent;
                    if ($targetMode === self::MODE_SECRET) {
                        if (!str_starts_with($rawCopy, 'XOE1:')) {
                            throw new ApiError('E2EE_CONTENT_REQUIRED', 'secret-chat copies must arrive as client envelopes (update the client)', 400, ['field' => 'message_ids']);
                        }
                        $storedCopy = $rawCopy;
                    } else {
                        if (str_starts_with($rawCopy, 'XOE1:')) {
                            throw new ApiError('E2EE_FORWARD_UNAVAILABLE', "message {$msgId} is end-to-end encrypted; forward it from its chat so the client can convert it", 400, ['field' => 'message_ids']);
                        }
                        $plainCopy = CloudCrypto::decryptForChat($srcChatId, (int) $src['id'], $rawCopy);
                        if ($plainCopy === null) {
                            // tampered/unreadable row — refuse rather than copy junk
                            throw new ApiError('SERVER_ERROR', "message {$msgId} content is unreadable", 500);
                        }
                        $storedCopy = $plainCopy; // sealed after insert, under the NEW row id
                    }
                } else {
                    $storedCopy = null;
                }

                $newId = Db::insert('messages', [
                    'chat_id'          => $toChatId,
                    'sender_id'        => $meId,
                    'content'          => null, // v2.12.0: sealed below, inside the transaction
                    'media_file_id'    => $src['media_file_id'],
                    // reply context is deliberately NOT copied (Telegram semantics)
                    'group_id'         => $src['group_id'] !== null ? (int) $src['group_id'] : null,
                    'fwd_from_user_id' => $fwdUser,
                    'fwd_from_chat_id' => $fwdChat,
                    'fwd_from_name'    => $fwdName,
                    'fwd_from_msg_id'  => $fwdMsgId,
                    'fwd_date'         => $fwdDate,
                    'created_at'       => $now,
                ]);
                if ($storedCopy !== null) {
                    Db::run('UPDATE messages SET content = ? WHERE id = ?', [
                        self::storeContent($toChatId, $newId, $targetMode, $storedCopy),
                        $newId,
                    ]);
                }
                $copied[] = $newId;
                $events[] = ['chat_id' => $toChatId, 'message_id' => $newId];
            }

            // One message_new event per copy, AFTER all rows exist (same
            // transaction as the inserts — the standard push contract).
            foreach ($events as $payload) {
                self::pushToChatAudience($toChatId, $meId, UpdateQueue::TYPE_MESSAGE_NEW, $payload);
            }
        });

        // Response rows in the exact request order (unique ids kept the
        // pairing stable; duplicates collapse to one copy by design).
        $messages = [];
        foreach ($copied as $newId) {
            $row = MessageMapper::findById($newId);
            if ($row !== null) {
                $messages[] = MessageMapper::json($row);
            }
        }
        return ['messages' => $messages];
    }

    /**
     * POST /messages/send-multi.php  {chat_id, items:[{media_file_id, content?}, ..1..10]}
     *
     * v2.7.0 (T56): the ALBUM endpoint, mirroring Telegram's server contract
     * for TL_messages_sendMultiMedia — the whole request IS one album and the
     * SERVER assigns one fresh 64-bit group_id shared by every copy (the
     * client never supplies it; local optimistic group ids are replaced at
     * the ack). Media rides by reference (each media_file_id must be a ready
     * file owned by the sender — the album items were finalized before this
     * call). Response preserves item order 1:1 so the client can pair its
     * random_id[] list with the returned rows.
     */
    public static function sendMulti(Request $request): array
    {
        $me = Auth::requireUser($request);
        $meId = (int) $me['id'];

        $chatId = Validator::int($request->input('chat_id'), 'chat_id', 1);
        $rawItems = $request->input('items');
        if (!is_array($rawItems) || count($rawItems) < 1 || count($rawItems) > 10) {
            throw new ApiError('VALIDATION_ERROR', 'items must be an array of 1..10 media objects', 400, ['field' => 'items']);
        }

        $parsed = [];
        foreach (array_values($rawItems) as $i => $item) {
            if (!is_array($item)) {
                throw new ApiError('VALIDATION_ERROR', "items[$i] must be an object", 400, ['field' => 'items']);
            }
            $mediaFileId = Validator::int($item['media_file_id'] ?? null, "items[$i].media_file_id", 1);
            $content = Validator::optStr($item['content'] ?? null, "items[$i].content", self::MAX_CONTENT_LENGTH);
            // v2.11.2 (T75): optional per-item thumb link (same contract as
            // send.php; kind 'image' OR 'e2ee' — the encrypted thumb is an
            // opaque blob). Without the link, album video items referenced a
            // thumb nobody but the sender could authorize or decrypt.
            $thumbFileId = Validator::optInt($item['thumb_file_id'] ?? null, "items[$i].thumb_file_id", 1);
            $parsed[] = ['media_file_id' => $mediaFileId, 'content' => $content, 'thumb_file_id' => $thumbFileId];
        }

        self::requireMembership($chatId, $meId, 'send messages to this chat');
        if (Db::fetch('SELECT id FROM chats WHERE id = ?', [$chatId]) === null) {
            throw new ApiError('NOT_FOUND', 'Chat not found', 404);
        }
        self::assertNotBlockedByPeers($chatId, $meId);

        // v2.12.0 (T80): one mode governs every item of the album.
        $mode = self::chatMode($chatId);
        foreach ($parsed as $i => $item) {
            self::assertContentForMode($mode, $item['content'], "items[$i].content");
        }

        foreach ($parsed as $i => $item) {
            $file = Db::fetch('SELECT id, status, owner_id FROM files WHERE id = ?', [$item['media_file_id']]);
            if ($file === null || (string) $file['status'] !== 'ready' || (int) $file['owner_id'] !== $meId) {
                throw new ApiError('VALIDATION_ERROR', "items[$i].media_file_id does not reference a ready file owned by the sender", 400, ['field' => 'items']);
            }
            if ($item['thumb_file_id'] !== null) {
                $thumb = Db::fetch('SELECT id, owner_id, status, kind FROM files WHERE id = ?', [$item['thumb_file_id']]);
                $thumbKind = (string) ($thumb['kind'] ?? '');
                if ($thumb === null || (string) $thumb['status'] !== 'ready' || !in_array($thumbKind, ['image', 'e2ee'], true) || (int) $thumb['owner_id'] !== $meId) {
                    throw new ApiError('VALIDATION_ERROR', "items[$i].thumb_file_id must reference a ready image (or e2ee blob) owned by the sender", 400, ['field' => 'items']);
                }
            }
        }

        // fresh album key: positive 63-bit value, string-safe on every client
        $groupId = random_int(1, PHP_INT_MAX);
        $now = time();
        $ids = [];
        $events = [];

        Db::transaction(static function () use ($chatId, $meId, $parsed, $groupId, $now, $mode, &$ids, &$events): void {
            foreach ($parsed as $item) {
                // v2.12.0 (T80): plaintext never touches storage (see send()).
                $newId = Db::insert('messages', [
                    'chat_id'       => $chatId,
                    'sender_id'     => $meId,
                    'content'       => null,
                    'media_file_id' => $item['media_file_id'],
                    'group_id'      => $groupId,
                    'created_at'    => $now,
                ]);
                if ($item['content'] !== null) {
                    Db::run('UPDATE messages SET content = ? WHERE id = ?', [
                        self::storeContent($chatId, $newId, $mode, $item['content']),
                        $newId,
                    ]);
                }
                $ids[] = $newId;
                $events[] = ['chat_id' => $chatId, 'message_id' => $newId];
                // v2.11.2 (T75): idempotent thumb link (first link sticks),
                // identical to send.php's behavior
                if ($item['thumb_file_id'] !== null) {
                    Db::run('UPDATE files SET thumb_file_id = ? WHERE id = ? AND thumb_file_id IS NULL', [$item['thumb_file_id'], $item['media_file_id']]);
                }
            }
            foreach ($events as $payload) {
                self::pushToChatAudience($chatId, $meId, UpdateQueue::TYPE_MESSAGE_NEW, $payload);
            }
        });

        $messages = [];
        foreach ($ids as $id) {
            $row = MessageMapper::findById($id);
            if ($row !== null) {
                $messages[] = MessageMapper::json($row);
            }
        }
        return ['group_id' => (string) $groupId, 'messages' => $messages];
    }

    /** GET /messages/history.php?chat_id=1&since_id=0 | &max_id=0&limit=50 */
    public static function history(Request $request): array
    {
        $me = Auth::requireUser($request);

        $chatId = Validator::int($request->q('chat_id'), 'chat_id', 1);
        $sinceId = Validator::optInt($request->q('since_id'), 'since_id') ?? 0;
        $maxId = Validator::optInt($request->q('max_id'), 'max_id') ?? 0;
        $limit = Validator::optInt($request->q('limit'), 'limit') ?? 50;
        $limit = max(1, min($limit, self::MAX_HISTORY_LIMIT));

        self::requireMembership($chatId, (int) $me['id'], 'read this chat');

        // v2.1 (T39): a hidden_dialogs row clips the pre-delete history for
        // this user — "delete chat"/"clear history" must not replay messages
        // at or below the ID boundary when the chat is reopened (delete mode)
        // or after a clear. IDs are monotonic, so same-second sends stay
        // correctly separated. Newer messages flow normally.
        $hiddenBeforeIdRaw = Db::fetchColumn(
            'SELECT hidden_before_id FROM hidden_dialogs WHERE user_id = ? AND chat_id = ?',
            [(int) $me['id'], $chatId],
        );
        $hiddenFilter = $hiddenBeforeIdRaw !== null
            ? ' AND m.id > ' . (int) $hiddenBeforeIdRaw
            : '';
        $hiddenFilterNoAlias = $hiddenBeforeIdRaw !== null
            ? ' AND id > ' . (int) $hiddenBeforeIdRaw
            : '';

        if ($maxId > 0) {
            // Initial load / scroll-back: newest N messages up to max_id,
            // returned oldest -> newest.
            $rows = Db::fetchAll(
                MessageMapper::SELECT_WITH_MEDIA
                . " WHERE m.chat_id = ? AND m.id <= ? AND m.deleted_at IS NULL{$hiddenFilter}
                   ORDER BY m.id DESC LIMIT " . $limit,
                [$chatId, $maxId],
            );
            $rows = array_reverse($rows);
        } else {
            // Delta catch-up: everything newer than since_id.
            $rows = Db::fetchAll(
                MessageMapper::SELECT_WITH_MEDIA
                . " WHERE m.chat_id = ? AND m.id > ? AND m.deleted_at IS NULL{$hiddenFilter}
                   ORDER BY m.id ASC LIMIT " . $limit,
                [$chatId, $sinceId],
            );
        }

        $messages = array_map(static fn (array $row): array => MessageMapper::json($row), $rows);
        $nextSinceId = $messages !== [] ? (int) end($messages)['id'] : $sinceId;

        $hasOlder = false;
        if (count($messages) === $limit) {
            $oldestId = (int) $messages[0]['id'];
            $hasOlder = (int) (Db::fetchColumn(
                "SELECT COUNT(*) FROM messages WHERE chat_id = ? AND id < ? AND deleted_at IS NULL{$hiddenFilterNoAlias}",
                [$chatId, $oldestId],
            ) ?? 0) > 0;
        }

        return [
            'messages'      => $messages,
            'next_since_id' => $nextSinceId,
            'has_older'     => $hasOlder,
        ];
    }

    /** POST /messages/edit.php  {message_id, content} */

    /**
     * T38/15: chat-audience push that ALSO reaches the sender's own devices
     * for the SAVED MESSAGES self-chat (pair_key private:me:me). Normal
     * chats exclude the sender — their own device applied the response and
     * (today) has no other-device story; the self-chat's ONLY audience is
     * the owner, and the owner's other devices must see saved content.
     * The sending device's poller skips own-sender message_new for normal
     * chats and re-applies self-chat messages idempotently.
     */
    private static function pushToChatAudience(int $chatId, int $meId, string $type, array $payload): void
    {
        $audience = UpdateQueue::pushToChatExcept($chatId, $meId, $type, $payload);
        $chat = Db::fetch('SELECT pair_key FROM chats WHERE id = ?', [$chatId]);
        if ($chat !== null && $chat['pair_key'] === sprintf('private:%d:%d', $meId, $meId)) {
            UpdateQueue::push([$meId], $type, $payload);
        }
    }

    public static function edit(Request $request): array
    {
        $me = Auth::requireUser($request);
        $meId = (int) $me['id'];

        $messageId = Validator::int($request->input('message_id'), 'message_id', 1);
        $content = Validator::str($request->input('content'), 'content', 1, self::MAX_CONTENT_LENGTH);

        $row = MessageMapper::findById($messageId);
        if ($row === null || $row['deleted_at'] !== null) {
            throw new ApiError('NOT_FOUND', 'Message not found', 404);
        }
        $chatId = (int) $row['chat_id'];

        self::requireMembership($chatId, $meId, 'edit messages in this chat');
        if ((int) $row['sender_id'] !== $meId) {
            throw new ApiError('FORBIDDEN', 'Only the sender can edit a message', 403);
        }

        // v2.12.0 (T80): the edit obeys the CURRENT chat mode, not the row's
        // historic form. Cloud chats seal the new text under the SAME row id
        // (AAD stays row-bound); secret chats require the client's fresh
        // XOE1 envelope. A cloud edit of a legacy XOE1 row is legal — the
        // stored form becomes XOC1 and the content-triggered client hook
        // keeps rendering every historic row correctly.
        $mode = self::chatMode($chatId);
        self::assertContentForMode($mode, $content);
        $stored = self::storeContent($chatId, $messageId, $mode, $content);

        Db::run('UPDATE messages SET content = ?, edited_at = ? WHERE id = ?', [$stored, time(), $messageId]);

        self::pushToChatAudience($chatId, $meId, UpdateQueue::TYPE_MESSAGE_EDIT, [
            'chat_id'    => $chatId,
            'message_id' => $messageId,
        ]);

        $fresh = MessageMapper::findById($messageId);
        return ['message' => $fresh !== null ? MessageMapper::json($fresh) : null];
    }

    /**
     * POST /messages/delete.php  {message_ids:[...], revoke?:true}
     * MVP semantics: delete-for-everyone (soft delete). Per-user deletion
     * can be added later via a per-user hidden table without breaking v1.
     */
    public static function delete(Request $request): array
    {
        $me = Auth::requireUser($request);
        $meId = (int) $me['id'];

        $raw = $request->input('message_ids');
        if (!is_array($raw) || $raw === [] || count($raw) > 100) {
            throw new ApiError('VALIDATION_ERROR', 'message_ids must be an array of 1..100 message ids', 400, ['field' => 'message_ids']);
        }
        $ids = array_values(array_unique(array_map(
            static fn ($v): int => Validator::int($v, 'message_ids', 1),
            $raw,
        )));

        $first = MessageMapper::findById($ids[0]);
        if ($first === null) {
            throw new ApiError('NOT_FOUND', 'Message not found', 404);
        }
        $chatId = (int) $first['chat_id'];

        self::requireMembership($chatId, $meId, 'delete messages in this chat');

        $placeholders = implode(',', array_fill(0, count($ids), '?'));
        $inChat = (int) (Db::fetchColumn(
            "SELECT COUNT(*) FROM messages WHERE id IN ($placeholders) AND chat_id = ?",
            [...$ids, $chatId],
        ) ?? 0);
        if ($inChat !== count($ids)) {
            throw new ApiError('VALIDATION_ERROR', 'All message_ids must belong to the same chat', 400, ['field' => 'message_ids']);
        }

        if ((int) $first['sender_id'] !== $meId) {
            $role = Db::fetchColumn('SELECT role FROM chat_members WHERE chat_id = ? AND user_id = ?', [$chatId, $meId]);
            if (!in_array($role, ['creator', 'admin'], true)) {
                throw new ApiError('FORBIDDEN', 'Only the sender or a chat admin can delete messages', 403);
            }
        }

        $now = time();
        Db::run(
            "UPDATE messages SET deleted_at = ? WHERE id IN ($placeholders) AND deleted_at IS NULL",
            [$now, ...$ids],
        );

        $deletedRows = Db::fetchAll(
            "SELECT id FROM messages WHERE id IN ($placeholders) AND deleted_at = ?",
            [...$ids, $now],
        );
        $deletedIds = array_map(static fn (array $r): int => (int) $r['id'], $deletedRows);

        if ($deletedIds !== []) {
            self::pushToChatAudience($chatId, $meId, UpdateQueue::TYPE_MESSAGE_DELETE, [
                'chat_id'    => $chatId,
                'message_ids' => $deletedIds,
                'by_user_id' => $meId,
            ]);
        }

        return ['chat_id' => $chatId, 'deleted' => $deletedIds];
    }

    /** POST /messages/read.php  {chat_id, max_id} */
    public static function read(Request $request): array
    {
        $me = Auth::requireUser($request);
        $meId = (int) $me['id'];

        $chatId = Validator::int($request->input('chat_id'), 'chat_id', 1);
        $maxId = Validator::int($request->input('max_id'), 'max_id', 0);

        self::requireMembership($chatId, $meId, 'read this chat');

        $current = (int) (Db::fetchColumn(
            'SELECT last_read_message_id FROM chat_members WHERE chat_id = ? AND user_id = ?',
            [$chatId, $meId],
        ) ?? 0);

        $new = max($current, $maxId);
        if ($new > $current) {
            Db::run(
                'UPDATE chat_members SET last_read_message_id = ? WHERE chat_id = ? AND user_id = ?',
                [$new, $chatId, $meId],
            );
            UpdateQueue::pushToChatExcept($chatId, $meId, UpdateQueue::TYPE_READ, [
                'chat_id' => $chatId,
                'user_id' => $meId,
                'max_id'  => $new,
            ]);
        }

        return ['last_read_message_id' => $new];
    }

    /** @return array<string,mixed>|null */
    private static function requireMembership(int $chatId, int $userId, string $action): void
    {
        $member = Db::fetch('SELECT user_id FROM chat_members WHERE chat_id = ? AND user_id = ?', [$chatId, $userId]);
        if ($member === null) {
            throw new ApiError('FORBIDDEN', "You must be a member of this chat to {$action}", 403);
        }
    }

    /**
     * v2.10.0 (T61): blocking is a DM concept (Telegram semantics) — the
     * delivery gate fires ONLY in private chats: if the PEER has blocked the
     * sender, delivery is refused with the MTProto-canonical USER_IS_BLOCKED.
     * Group delivery is never block-gated (a block is not a mute for shared
     * groups), and a self-chat has no peer. Peer id + block check ride one
     * pair of indexed lookups per send.
     */
    private static function assertNotBlockedByPeers(int $chatId, int $meId): void
    {
        $type = Db::fetchColumn('SELECT type FROM chats WHERE id = ?', [$chatId]);
        if ($type !== 'private') {
            return;
        }
        $peerId = Db::fetchColumn(
            'SELECT user_id FROM chat_members WHERE chat_id = ? AND user_id <> ? LIMIT 1',
            [$chatId, $meId],
        );
        if ($peerId !== null && PrivacyService::blocked((int) $peerId, $meId)) {
            throw new ApiError('USER_IS_BLOCKED', 'This user is not receiving your messages', 403);
        }
    }
}

<?php

declare(strict_types=1);

namespace App\Controllers;

use App\Core\ApiError;
use App\Core\Auth;
use App\Core\AvatarService;
use App\Core\Db;
use App\Core\PrivacyService;
use App\Core\Request;
use App\Core\UpdateQueue;
use App\Core\Validator;
use App\Mappers\ChatMapper;
use App\Mappers\MessageMapper;
use App\Mappers\UserMapper;
use Throwable;

/**
 * Chat management: list, create (private + group), members, add-member.
 *
 * Private chats are find-or-create via the deterministic pair_key
 * ('private:<min_id>:<max_id>'), so creating the same chat twice returns
 * the same chat row instead of duplicating it.
 */
final class ChatsController
{
    private const MAX_CHATS_LIST = 200;
    private const MAX_GROUP_MEMBERS = 200;

    /**
     * GET /chats/list.php
     *
     * v1.6 dialog-visibility rule (Telegram semantics, the "no chat on click"
     * guarantee): a PRIVATE chat with zero messages is NOT a dialog. Opening a
     * chat page (or a @username deep link) materializes the chat row lazily,
     * but it stays invisible here until the first message exists. Group chats
     * surface immediately after creation, empty or not.
     *
     * v2.1 (T39) dialog-deletion rule: a DELETE-mode hidden_dialogs row hides
     * the dialog from the list until a message with a NEWER id than
     * hidden_before_id arrives (an incoming message re-opens the chat —
     * Telegram semantics; ids are monotonic so same-second messages are
     * safe); a CLEAR-mode row keeps the dialog listed but empty (its
     * last_message and unread counters only count post-boundary messages).
     */
    public static function listChats(Request $request): array
    {
        $me = Auth::requireUser($request);

        $rows = Db::fetchAll(
            'SELECT c.*, m.role, m.last_read_message_id AS my_last_read,
                    h.hidden_before_id AS _hidden_before_id, h.just_clear AS _just_clear,
                    p.pinned_at AS _pinned_at
             FROM chat_members m
             JOIN chats c ON c.id = m.chat_id
             LEFT JOIN hidden_dialogs h ON h.user_id = m.user_id AND h.chat_id = c.id
             LEFT JOIN dialog_pins p ON p.user_id = m.user_id AND p.chat_id = c.id
             WHERE m.user_id = ?
               AND NOT (c.type = \'private\' AND NOT EXISTS (
                   SELECT 1 FROM messages msg WHERE msg.chat_id = c.id
               ))
               AND (h.id IS NULL OR h.just_clear = 1 OR EXISTS (
                   SELECT 1 FROM messages msg2
                    WHERE msg2.chat_id = c.id AND msg2.deleted_at IS NULL
                      AND msg2.id > h.hidden_before_id
               ))
             ORDER BY c.id DESC
             LIMIT ' . self::MAX_CHATS_LIST,
            [(int) $me['id']],
        );

        $meId = (int) $me['id'];
        return [
            'chats' => array_map(static fn (array $row): array => self::payloadFromRow($row, $meId), $rows),
        ];
    }

    /**
     * POST /chats/pin.php  {chat_id, pinned: bool}
     *
     * v2.7.0 (T56): per-USER dialog pin state (Telegram semantics — pinning
     * reorders MY list, never the chat itself). The dialog_pins row is keyed
     * (user_id, chat_id); pinned_order = pinned_at so the newest pin sorts
     * first on the client (comparator: pinned first, pinnedNum DESC).
     * A dialog_pin event is pushed ONLY to the owner so their other devices
     * converge (the receiving chat never learns about anyone's pin).
     */
    public static function pin(Request $request): array
    {
        $me = Auth::requireUser($request);
        $meId = (int) $me['id'];
        $chatId = Validator::int($request->input('chat_id'), 'chat_id', 1);
        $pinnedRaw = $request->input('pinned');
        if (!is_bool($pinnedRaw)) {
            throw new ApiError('VALIDATION_ERROR', 'pinned must be a boolean', 400, ['field' => 'pinned']);
        }

        if (Db::fetch('SELECT id FROM chats WHERE id = ?', [$chatId]) === null) {
            throw new ApiError('NOT_FOUND', 'Chat not found', 404);
        }

        $now = time();
        if ($pinnedRaw) {
            $existing = Db::fetch('SELECT chat_id FROM dialog_pins WHERE user_id = ? AND chat_id = ?', [$meId, $chatId]);
            if ($existing === null) {
                try {
                    Db::insert('dialog_pins', ['user_id' => $meId, 'chat_id' => $chatId, 'pinned_at' => $now]);
                } catch (Throwable $e) {
                    if (!Db::isDuplicate($e)) {
                        throw $e;
                    }
                    Db::run('UPDATE dialog_pins SET pinned_at = ? WHERE user_id = ? AND chat_id = ?', [$now, $meId, $chatId]);
                }
            } else {
                Db::run('UPDATE dialog_pins SET pinned_at = ? WHERE user_id = ? AND chat_id = ?', [$now, $meId, $chatId]);
            }
        } else {
            Db::run('DELETE FROM dialog_pins WHERE user_id = ? AND chat_id = ?', [$meId, $chatId]);
        }

        UpdateQueue::push([$meId], UpdateQueue::TYPE_DIALOG_PIN, [
            'chat_id'   => $chatId,
            'pinned'    => $pinnedRaw,
            'pinned_at' => $pinnedRaw ? $now : 0,
        ]);

        return ['chat_id' => $chatId, 'pinned' => $pinnedRaw];
    }

    /**
     * POST /chats/pin-order.php  {chat_ids: [first(topmost) .. last]}
     *
     * v2.7.0 (T56): FULL-SYNC of the user's pinned set (the client sends the
     * complete ordered pinned prefix — typically from
     * TL_messages_reorderPinnedDialogs, which fires after every preview-menu
     * pin/unpin and drag-reorder). Semantics: chat_ids = the authoritative
     * pinned set in display order; any dialog_pins row NOT listed is REMOVED.
     * Ordering value: first chat gets the largest pinned_at (the client
     * comparator sorts pinned first, pinnedNum DESC). One dialog_pin event
     * per changed chat, owner-only.
     */
    public static function pinOrder(Request $request): array
    {
        $me = Auth::requireUser($request);
        $meId = (int) $me['id'];

        $raw = $request->input('chat_ids');
        if (!is_array($raw) || count($raw) > 100) {
            throw new ApiError('VALIDATION_ERROR', 'chat_ids must be an array of at most 100 chat ids', 400, ['field' => 'chat_ids']);
        }
        $chatIds = array_values(array_unique(array_map(
            static fn ($v): int => Validator::int($v, 'chat_ids', 1),
            $raw,
        )));

        foreach ($chatIds as $chatId) {
            if (Db::fetch('SELECT id FROM chats WHERE id = ?', [$chatId]) === null) {
                throw new ApiError('NOT_FOUND', "Chat {$chatId} not found", 404);
            }
        }

        $now = time();
        $n = count($chatIds);
        $changed = [];

        Db::transaction(static function () use ($chatIds, $meId, $n, $now, &$changed): void {
            $current = [];
            foreach (Db::fetchAll('SELECT chat_id FROM dialog_pins WHERE user_id = ?', [$meId]) as $row) {
                $current[(int) $row['chat_id']] = true;
            }

            foreach ($chatIds as $i => $chatId) {
                // topmost (i=0) gets the LARGEST pinned_at (client sorts DESC)
                $pinnedAt = $now + ($n - $i);
                $exists = Db::fetch('SELECT chat_id FROM dialog_pins WHERE user_id = ? AND chat_id = ?', [$meId, $chatId]);
                if ($exists === null) {
                    try {
                        Db::insert('dialog_pins', ['user_id' => $meId, 'chat_id' => $chatId, 'pinned_at' => $pinnedAt]);
                    } catch (Throwable $e) {
                        if (!Db::isDuplicate($e)) {
                            throw $e;
                        }
                        Db::run('UPDATE dialog_pins SET pinned_at = ? WHERE user_id = ? AND chat_id = ?', [$pinnedAt, $meId, $chatId]);
                    }
                } else {
                    Db::run('UPDATE dialog_pins SET pinned_at = ? WHERE user_id = ? AND chat_id = ?', [$pinnedAt, $meId, $chatId]);
                }
                $changed[] = ['chat_id' => $chatId, 'pinned' => true, 'pinned_at' => $pinnedAt];
                unset($current[$chatId]);
            }

            foreach (array_keys($current) as $removedId) {
                Db::run('DELETE FROM dialog_pins WHERE user_id = ? AND chat_id = ?', [$meId, $removedId]);
                $changed[] = ['chat_id' => $removedId, 'pinned' => false, 'pinned_at' => 0];
            }

            foreach ($changed as $payload) {
                UpdateQueue::push([$meId], UpdateQueue::TYPE_DIALOG_PIN, $payload);
            }
        });

        return ['pinned' => count($chatIds), 'changed' => count($changed)];
    }

    /**
     * POST /chats/delete-dialog.php  {chat_id, just_clear?:bool}
     *
     * v2.1 (T39) — "Delete chat" / "Clear history" FOR ME. Upserts the
     * caller's hidden_dialogs row: hidden_before_id = the chat's newest
     * message id at this moment (an ID boundary, immune to same-second
     * sends). just_clear=true (clear history) keeps the dialog listed but
     * empty; just_clear=false (delete chat) removes it from the list until a
     * newer-id message arrives. messages/history.php applies the same
     * boundary, so reopening the chat never replays the pre-delete history.
     * revoke ("delete for both") is NOT backed in v1 — the peer keeps their
     * copy, exactly like the v1 message-level delete contract in API.md §7.
     *
     * v2.2 (T40): the guard is chat-existence, NOT membership. A user who
     * was just kicked/left is no longer a member while their OTHER client
     * sessions still replay the standard local deleteDialog flow (it sends
     * delete-history before the chat_member update lands) — hiding a dialog
     * for me is per-user visibility state, never privileged.
     */
    public static function deleteDialog(Request $request): array
    {
        $me = Auth::requireUser($request);
        $meId = (int) $me['id'];
        $chatId = Validator::int($request->input('chat_id'), 'chat_id', 1);
        $justClear = false;
        $rawJustClear = $request->input('just_clear');
        if ($rawJustClear !== null) {
            $justClear = (bool) $rawJustClear;
        }

        if (Db::fetch('SELECT id FROM chats WHERE id = ?', [$chatId]) === null) {
            throw new ApiError('NOT_FOUND', 'Chat not found', 404);
        }

        $now = time();
        // The boundary is the newest message id in the chat RIGHT NOW —
        // everything at or below it is history for this user from this moment.
        $boundary = (int) (Db::fetchColumn(
            'SELECT COALESCE(MAX(id), 0) FROM messages WHERE chat_id = ?',
            [$chatId],
        ) ?? 0);
        $existing = Db::fetch(
            'SELECT id FROM hidden_dialogs WHERE user_id = ? AND chat_id = ?',
            [$meId, $chatId],
        );
        if ($existing === null) {
            try {
                Db::insert('hidden_dialogs', [
                    'user_id'          => $meId,
                    'chat_id'          => $chatId,
                    'hidden_before_id' => $boundary,
                    'just_clear'       => $justClear ? 1 : 0,
                    'created_at'       => $now,
                ]);
            } catch (Throwable $e) {
                if (!Db::isDuplicate($e)) {
                    throw $e;
                }
                Db::run(
                    'UPDATE hidden_dialogs SET hidden_before_id = ?, just_clear = ?, created_at = ? WHERE user_id = ? AND chat_id = ?',
                    [$boundary, $justClear ? 1 : 0, $now, $meId, $chatId],
                );
            }
        } else {
            Db::run(
                'UPDATE hidden_dialogs SET hidden_before_id = ?, just_clear = ?, created_at = ? WHERE user_id = ? AND chat_id = ?',
                [$boundary, $justClear ? 1 : 0, $now, $meId, $chatId],
            );
        }

        return ['ok' => true, 'chat_id' => $chatId, 'just_clear' => $justClear, 'hidden_before_id' => $boundary];
    }

    /** POST /chats/create.php  {type:"private", peer_user_id} | {type:"group", title, member_ids?} */
    public static function create(Request $request): array
    {
        $me = Auth::requireUser($request);
        $meId = (int) $me['id'];
        $type = Validator::str($request->input('type'), 'type', 4, 10);

        if ($type === 'private') {
            return self::createPrivate($request, $me, $meId);
        }
        if ($type === 'group') {
            return self::createGroup($request, $me, $meId);
        }
        throw new ApiError('VALIDATION_ERROR', 'type must be "private" or "group"', 400, ['field' => 'type']);
    }

    /** POST /chats/add-member.php  {chat_id, user_id} */
    public static function addMember(Request $request): array
    {
        $me = Auth::requireUser($request);
        $meId = (int) $me['id'];
        $chatId = Validator::int($request->input('chat_id'), 'chat_id', 1);
        $userId = Validator::int($request->input('user_id'), 'user_id', 1);

        $chat = Db::fetch('SELECT * FROM chats WHERE id = ?', [$chatId]);
        if ($chat === null) {
            throw new ApiError('NOT_FOUND', 'Chat not found', 404);
        }
        if ($chat['type'] !== 'group') {
            throw new ApiError('VALIDATION_ERROR', 'Members can only be added to group chats', 400, ['field' => 'chat_id']);
        }

        $myMemberRow = Db::fetch('SELECT role FROM chat_members WHERE chat_id = ? AND user_id = ?', [$chatId, $meId]);
        if ($myMemberRow === null) {
            throw new ApiError('FORBIDDEN', 'You are not a member of this chat', 403);
        }
        if (!in_array($myMemberRow['role'], ['creator', 'admin'], true)) {
            throw new ApiError('FORBIDDEN', 'Only the creator or admins can add members', 403);
        }

        if (Db::fetch('SELECT id FROM users WHERE id = ?', [$userId]) === null) {
            throw new ApiError('NOT_FOUND', "User {$userId} does not exist", 404);
        }
        // v2.10.0 (T61): after existence, before membership — the invite
        // must respect the target's chat_invite rule and block state.
        self::assertInvitable($userId, $meId);

        if (Db::fetch('SELECT user_id FROM chat_members WHERE chat_id = ? AND user_id = ?', [$chatId, $userId]) !== null) {
            // v2.2 (T40): the code is the TL-canonical one the Android client
            // branches on — MessagesController.addUserToChat swallows
            // USER_ALREADY_PARTICIPANT when ignoreIfAlreadyExists=true, which
            // is exactly the promote chain (setUserAdminRole re-sends
            // addChatUser before editChatAdmin; a re-add must be a silent
            // no-op there, not a promoted-error dialog).
            throw new ApiError('USER_ALREADY_PARTICIPANT', 'User is already a member of this chat', 409);
        }

        Db::insert('chat_members', [
            'chat_id'   => $chatId,
            'user_id'   => $userId,
            'role'      => 'member',
            'joined_at' => time(),
        ]);

        // v2.2 (T40): being re-added re-opens the chat (Telegram semantics) —
        // a stale DELETE-mode hidden_dialog would otherwise keep the chat
        // hidden until a brand-new message arrives.
        Db::run('DELETE FROM hidden_dialogs WHERE user_id = ? AND chat_id = ?', [$userId, $chatId]);

        // Let the added user's client discover the chat on its next /sync poll.
        UpdateQueue::push([$userId], UpdateQueue::TYPE_CHAT_NEW, ['chat_id' => $chatId]);

        // v1.8.1: the response now carries the authoritative membership snapshot
        // ({members, count} — same shape as chats/members.php) so the adding
        // client can apply the post-insert participant state in ONE round-trip
        // instead of keeping UI-local optimism as its only truth.
        $payload = self::membersPayload((int) $me['id'], $chatId);
        $payload['chat'] = self::chatPayload($me, $chatId);
        return $payload;
    }

    /**
     * POST /chats/promote.php  {chat_id, user_id, is_admin}   (v2.2, T40)
     *
     * Promote a member to admin (is_admin=true) or demote an admin back to
     * member (is_admin=false). Telegram basic-group rule: ONLY the creator
     * manages admins (the client's TL_messages_editChatAdmin carries a binary
     * is_admin — no granular rights — and its UI gates promote on
     * chat.creator; this endpoint enforces the same rule server-side).
     *
     * The response is the authoritative post-change snapshot ({members,
     * count, chat} — same shape as add-member) so the acting client applies
     * the persisted state in one round-trip. Every member (the actor
     * included — their OTHER devices need it) receives a chat_member update
     * {event: promote|demote}; their clients re-pull chats/members.php.
     */
    public static function promoteMember(Request $request): array
    {
        $me = Auth::requireUser($request);
        $meId = (int) $me['id'];
        $chatId = Validator::int($request->input('chat_id'), 'chat_id', 1);
        $userId = Validator::int($request->input('user_id'), 'user_id', 1);
        $isAdmin = self::requireBool($request->input('is_admin'), 'is_admin');

        self::requireGroupChat($chatId);

        $myRole = self::memberRole($chatId, $meId);
        if ($myRole === null) {
            throw new ApiError('FORBIDDEN', 'You are not a member of this chat', 403);
        }
        if ($myRole !== 'creator') {
            throw new ApiError('FORBIDDEN', 'Only the group creator can promote or demote admins', 403);
        }

        $targetRole = self::memberRole($chatId, $userId);
        if ($targetRole === null) {
            throw new ApiError('USER_NOT_PARTICIPANT', 'User is not a member of this chat', 404);
        }
        if ($targetRole === 'creator') {
            throw new ApiError('VALIDATION_ERROR', 'The group creator cannot be promoted or demoted', 400, ['field' => 'user_id']);
        }

        $newRole = $isAdmin ? 'admin' : 'member';
        if ($newRole !== $targetRole) {
            Db::run('UPDATE chat_members SET role = ? WHERE chat_id = ? AND user_id = ?', [$newRole, $chatId, $userId]);
            // Recipients: the whole member set (unchanged by a role flip) —
            // the actor included so their other devices converge.
            UpdateQueue::push(self::chatMemberIds($chatId), UpdateQueue::TYPE_CHAT_MEMBER, [
                'chat_id'  => $chatId,
                'event'    => $isAdmin ? 'promote' : 'demote',
                'user_id'  => $userId,
                'actor_id' => $meId,
            ]);
        }

        $payload = self::membersPayload($meId, $chatId);
        $payload['chat'] = self::chatPayload($me, $chatId);
        return $payload;
    }

    /**
     * POST /chats/kick.php  {chat_id, user_id}   (v2.2, T40)
     *
     * Remove a member from a group (the client's TL_messages_deleteChatUser
     * with a NON-self user). Telegram basic-group permission matrix:
     *   - creator: may remove admins and members
     *   - admin:   may remove plain members only (not the creator, not
     *              other admins)
     *   - member:  may remove nobody (self-removal is chats/leave.php)
     *
     * The membership row is deleted — a kicked user is OUT (their history
     * access ends: messages/history.php is membership-guarded; being
     * re-added later restores access and clears their hidden dialog). The
     * kicked user is notified via chat_member {event: kick} together with
     * the remaining members; their client deletes the dialog locally.
     */
    public static function kickMember(Request $request): array
    {
        $me = Auth::requireUser($request);
        $meId = (int) $me['id'];
        $chatId = Validator::int($request->input('chat_id'), 'chat_id', 1);
        $userId = Validator::int($request->input('user_id'), 'user_id', 1);

        self::requireGroupChat($chatId);

        $myRole = self::memberRole($chatId, $meId);
        if ($myRole === null) {
            throw new ApiError('FORBIDDEN', 'You are not a member of this chat', 403);
        }
        if ($userId === $meId) {
            throw new ApiError('VALIDATION_ERROR', 'Use chats/leave.php to remove yourself', 400, ['field' => 'user_id']);
        }
        $targetRole = self::memberRole($chatId, $userId);
        if ($targetRole === null) {
            throw new ApiError('USER_NOT_PARTICIPANT', 'User is not a member of this chat', 404);
        }
        if ($myRole === 'creator') {
            // creator removes admins and members; the creator role itself is
            // the target's own guard below (never the caller here).
        } elseif ($myRole === 'admin') {
            if ($targetRole !== 'member') {
                throw new ApiError('FORBIDDEN', 'Admins can only remove plain members', 403);
            }
        } else {
            throw new ApiError('FORBIDDEN', 'Only the creator or admins can remove members', 403);
        }

        // Captured BEFORE the delete: the recipient set must include the
        // member being removed (they are out of the chat afterwards).
        $recipients = self::chatMemberIds($chatId);
        Db::run('DELETE FROM chat_members WHERE chat_id = ? AND user_id = ?', [$chatId, $userId]);
        UpdateQueue::push($recipients, UpdateQueue::TYPE_CHAT_MEMBER, [
            'chat_id'  => $chatId,
            'event'    => 'kick',
            'user_id'  => $userId,
            'actor_id' => $meId,
        ]);

        $payload = self::membersPayload($meId, $chatId);
        $payload['chat'] = self::chatPayload($me, $chatId);
        $payload['removed_user_id'] = $userId;
        return $payload;
    }

    /**
     * POST /chats/leave.php  {chat_id}   (v2.2, T40)
     *
     * Leave a group (the client's TL_messages_deleteChatUser with SELF).
     * The caller's membership row is deleted; the client deletes its own
     * dialog locally BEFORE sending (MessagesController.deleteParticipantFromChat
     * runs deleteDialog first), so the response is only a confirmation.
     * The creator cannot leave (Telegram semantics) — they delete the group
     * via chats/delete.php instead; the member set captured before the
     * delete includes the leaver so their OTHER devices learn about it.
     */
    public static function leaveChat(Request $request): array
    {
        $me = Auth::requireUser($request);
        $meId = (int) $me['id'];
        $chatId = Validator::int($request->input('chat_id'), 'chat_id', 1);

        self::requireGroupChat($chatId);

        $myRole = self::memberRole($chatId, $meId);
        if ($myRole === null) {
            throw new ApiError('FORBIDDEN', 'You are not a member of this chat', 403);
        }
        if ($myRole === 'creator') {
            throw new ApiError('CANNOT_LEAVE_AS_CREATOR', 'The group creator cannot leave; delete the group instead', 400);
        }

        $recipients = self::chatMemberIds($chatId);
        Db::run('DELETE FROM chat_members WHERE chat_id = ? AND user_id = ?', [$chatId, $meId]);
        UpdateQueue::push($recipients, UpdateQueue::TYPE_CHAT_MEMBER, [
            'chat_id'  => $chatId,
            'event'    => 'leave',
            'user_id'  => $meId,
            'actor_id' => $meId,
        ]);

        return ['ok' => true, 'chat_id' => $chatId];
    }

    /**
     * POST /chats/delete.php  {chat_id}   (v2.2, T40)
     *
     * Delete a group FOR EVERYONE (the client's TL_messages_deleteChat —
     * the creator's "delete and exit" with delete-for-all). Creator-only.
     * Real deletion, v1 philosophy: the chats row, every membership, the
     * hidden_dialog rows and the messages are removed server-side; every
     * ex-member (the actor included — their other devices) receives
     * chat_member {event: deleted} and their clients drop the dialog.
     * Referenced media files are NOT purged from disk (orphan cleanup stays
     * a tools/ concern, like cleanup-uploads.php).
     */
    public static function deleteChat(Request $request): array
    {
        $me = Auth::requireUser($request);
        $meId = (int) $me['id'];
        $chatId = Validator::int($request->input('chat_id'), 'chat_id', 1);

        $chat = Db::fetch('SELECT * FROM chats WHERE id = ?', [$chatId]);
        if ($chat === null) {
            throw new ApiError('NOT_FOUND', 'Chat not found', 404);
        }
        if ($chat['type'] !== 'group') {
            throw new ApiError('VALIDATION_ERROR', 'Only group chats can be deleted for everyone', 400, ['field' => 'chat_id']);
        }

        $myRole = self::memberRole($chatId, $meId);
        if ($myRole === null) {
            throw new ApiError('FORBIDDEN', 'You are not a member of this chat', 403);
        }
        if ($myRole !== 'creator') {
            throw new ApiError('FORBIDDEN', 'Only the group creator can delete the group', 403);
        }

        $recipients = self::chatMemberIds($chatId);
        Db::transaction(static function () use ($chatId): void {
            Db::run('DELETE FROM messages WHERE chat_id = ?', [$chatId]);
            Db::run('DELETE FROM chat_members WHERE chat_id = ?', [$chatId]);
            Db::run('DELETE FROM hidden_dialogs WHERE chat_id = ?', [$chatId]);
            Db::run('DELETE FROM chats WHERE id = ?', [$chatId]);
        });
        UpdateQueue::push($recipients, UpdateQueue::TYPE_CHAT_MEMBER, [
            'chat_id'  => $chatId,
            'event'    => 'deleted',
            'user_id'  => 0,
            'actor_id' => $meId,
        ]);

        return ['ok' => true, 'chat_id' => $chatId];
    }

    /**
     * POST /chats/set-photo.php  {chat_id, file_id | remove: true}
     * Group avatar. The file is an uploaded, finalized IMAGE (same contract
     * as users/set-photo); `{chat_id, remove: true}` clears the avatar
     * (v1.8 — Telegram's "delete photo"). Creator/admin only; emits chat_new
     * to every member so the change propagates on the next sync poll.
     */
    public static function setPhoto(Request $request): array
    {
        $me = Auth::requireUser($request);
        $meId = (int) $me['id'];
        $chatId = Validator::int($request->input('chat_id'), 'chat_id', 1);

        $chat = Db::fetch('SELECT * FROM chats WHERE id = ?', [$chatId]);
        if ($chat === null) {
            throw new ApiError('NOT_FOUND', 'Chat not found', 404);
        }
        if ($chat['type'] !== 'group') {
            throw new ApiError('VALIDATION_ERROR', 'Avatars apply to group chats only', 400, ['field' => 'chat_id']);
        }
        self::assertChatAdmin($chatId, $meId);

        $removeRaw = $request->input('remove');
        $remove = $removeRaw === true || $removeRaw === 1 || $removeRaw === '1' || $removeRaw === 'true';

        if ($remove) {
            $oldAvatarId = $chat['avatar_file_id'] !== null ? (int) $chat['avatar_file_id'] : null;
            if ($oldAvatarId !== null) {
                Db::run('UPDATE chats SET avatar_file_id = NULL WHERE id = ?', [$chatId]);
                AvatarService::purge($oldAvatarId);
            }
            $payload = self::chatPayload($me, $chatId);
            UpdateQueue::pushToChatExcept($chatId, 0, UpdateQueue::TYPE_CHAT_NEW, ['chat_id' => $chatId]);
            return ['chat' => $payload];
        }

        $fileId = Validator::int($request->input('file_id'), 'file_id', 1);

        $chat = Db::fetch('SELECT * FROM chats WHERE id = ?', [$chatId]);
        if ($chat === null) {
            throw new ApiError('NOT_FOUND', 'Chat not found', 404);
        }
        if ($chat['type'] !== 'group') {
            throw new ApiError('VALIDATION_ERROR', 'Avatars apply to group chats only', 400, ['field' => 'chat_id']);
        }
        self::assertChatAdmin($chatId, $meId);

        $file = Db::fetch('SELECT * FROM files WHERE id = ?', [$fileId]);
        if ($file === null || (string) $file['status'] !== 'ready') {
            throw new ApiError('NOT_FOUND', 'File not found or not finalized', 404);
        }
        if ((int) $file['owner_id'] !== $meId) {
            throw new ApiError('FORBIDDEN', 'Only the uploader can use this file as an avatar', 403);
        }
        $kind = (string) ($file['kind'] ?? '');
        if ($kind === 'video') {
            throw new ApiError('AVATAR_VIDEO_UNSUPPORTED', 'Video avatars are not supported yet; send an image', 400);
        }
        if ($kind !== 'image' && $kind !== 'avatar' && $kind !== 'gif') {
            // v2.3: kind 'gif' accepted — the crop takes the GIF's first frame.
            throw new ApiError('VALIDATION_ERROR', 'Avatar source must be an image file', 400, ['kind' => $kind]);
        }
        if ((int) $file['size'] > AvatarService::MAX_INPUT_BYTES) {
            throw new ApiError('FILE_TOO_LARGE', 'Avatar source is limited to 16 MB', 413);
        }
        if ($file['storage_path'] === null || !is_file((string) $file['storage_path'])) {
            throw new ApiError('NOT_FOUND', 'Avatar source file is missing on disk', 404);
        }

        $crops = AvatarService::makeFromImage($fileId, (string) $file['storage_path'], $meId);

        $oldAvatarId = $chat['avatar_file_id'] !== null ? (int) $chat['avatar_file_id'] : null;
        Db::run('UPDATE chats SET avatar_file_id = ? WHERE id = ?', [$fileId, $chatId]);
        if ($oldAvatarId !== null) {
            AvatarService::purge($oldAvatarId, $fileId);
        }

        $payload = self::chatPayload($me, $chatId);
        UpdateQueue::pushToChatExcept($chatId, 0, UpdateQueue::TYPE_CHAT_NEW, ['chat_id' => $chatId]);
        return ['chat' => $payload];
    }

    /**
     * POST /chats/edit.php  {chat_id, title?, about?}  (group rename and/or
     * description; creator/admin). v1.8: `about` (the group "Bio" in the
     * Telegram UI) — the client's TL_messages_editChatAbout route lands here
     * with `about` only. At least one of title/about is required; "" clears
     * the about to NULL.
     */
    public static function edit(Request $request): array
    {
        $me = Auth::requireUser($request);
        $meId = (int) $me['id'];
        $chatId = Validator::int($request->input('chat_id'), 'chat_id', 1);
        $rawTitle = $request->input('title');
        $rawAbout = $request->input('about');

        if ($rawTitle === null && $rawAbout === null) {
            throw new ApiError('VALIDATION_ERROR', 'title or about is required', 400);
        }

        $chat = Db::fetch('SELECT * FROM chats WHERE id = ?', [$chatId]);
        if ($chat === null) {
            throw new ApiError('NOT_FOUND', 'Chat not found', 404);
        }
        if ($chat['type'] !== 'group') {
            throw new ApiError('VALIDATION_ERROR', 'Only group chats can be edited', 400, ['field' => 'chat_id']);
        }
        self::assertChatAdmin($chatId, $meId);

        $sets = [];
        $params = [];
        if ($rawTitle !== null) {
            $title = Validator::str($rawTitle, 'title', 1, 200);
            $sets[] = 'title = ?';
            $params[] = $title;
        }
        if ($rawAbout !== null) {
            if (!is_string($rawAbout)) {
                throw new ApiError('VALIDATION_ERROR', 'about must be a string', 400, ['field' => 'about']);
            }
            $about = trim($rawAbout);
            if (mb_strlen($about) > 255) {
                throw new ApiError('ABOUT_TOO_LONG', 'about must be at most 255 characters', 400, ['field' => 'about']);
            }
            $sets[] = 'about = ?';
            $params[] = $about === '' ? null : $about;
        }
        $params[] = $chatId;

        Db::run('UPDATE chats SET ' . implode(', ', $sets) . ' WHERE id = ?', $params);

        $payload = self::chatPayload($me, $chatId);
        UpdateQueue::pushToChatExcept($chatId, 0, UpdateQueue::TYPE_CHAT_NEW, ['chat_id' => $chatId]);
        return ['chat' => $payload];
    }

    /**
     * v2.10.0 (T61): the invite gate shared by createPrivate / createGroup /
     * addMember. User $targetId may be invited by actor $actorId unless:
     *   - the target has BLOCKED the actor   -> USER_IS_BLOCKED (MTProto name
     *     the upstream client maps; a block stops every interaction), or
     *   - the target's chat_invite rule DENIES the actor ->
     *     USER_PRIVACY_RESTRICTED (Telegram's privacy-restricted add).
     */
    private static function assertInvitable(int $targetId, int $actorId): void
    {
        if (PrivacyService::blocked($targetId, $actorId)) {
            throw new ApiError('USER_IS_BLOCKED', 'This user is not accepting interactions from you', 403);
        }
        if (!PrivacyService::decide($targetId, $actorId, 'chat_invite')) {
            throw new ApiError('USER_PRIVACY_RESTRICTED', 'This user restricts who can add them to chats', 403);
        }
    }

    /** Creator/admin guard shared by set-photo and edit. */
    private static function assertChatAdmin(int $chatId, int $meId): void {
        $row = Db::fetch('SELECT role FROM chat_members WHERE chat_id = ? AND user_id = ?', [$chatId, $meId]);
        if ($row === null) {
            throw new ApiError('FORBIDDEN', 'You are not a member of this chat', 403);
        }
        if (!in_array($row['role'], ['creator', 'admin'], true)) {
            throw new ApiError('FORBIDDEN', 'Only the creator or admins can manage this chat', 403);
        }
    }

    /** v2.2 (T40): the chat row must exist AND be a group (member-management target). @return array<string,mixed> */
    private static function requireGroupChat(int $chatId): array
    {
        $chat = Db::fetch('SELECT * FROM chats WHERE id = ?', [$chatId]);
        if ($chat === null) {
            throw new ApiError('NOT_FOUND', 'Chat not found', 404);
        }
        if ($chat['type'] !== 'group') {
            throw new ApiError('VALIDATION_ERROR', 'Only group chats have members to manage', 400, ['field' => 'chat_id']);
        }
        return $chat;
    }

    /** v2.2 (T40): the caller's membership role, or null when not a member. */
    private static function memberRole(int $chatId, int $userId): ?string
    {
        $row = Db::fetch('SELECT role FROM chat_members WHERE chat_id = ? AND user_id = ?', [$chatId, $userId]);
        return $row !== null ? (string) $row['role'] : null;
    }

    /** v2.2 (T40): every current member id of a chat (recipient capture happens BEFORE a delete). @return list<int> */
    private static function chatMemberIds(int $chatId): array
    {
        $rows = Db::fetchAll('SELECT user_id FROM chat_members WHERE chat_id = ?', [$chatId]);
        return array_map(static fn (array $r): int => (int) $r['user_id'], $rows);
    }

    /** v2.2 (T40): strict boolean body field (true/1/'1'/'true' vs false/0/'0'/'false'). */
    private static function requireBool(mixed $value, string $field): bool
    {
        if ($value === null) {
            throw new ApiError('VALIDATION_ERROR', "{$field} is required (boolean)", 400, ['field' => $field]);
        }
        if (is_bool($value)) {
            return $value;
        }
        if (is_int($value) && ($value === 0 || $value === 1)) {
            return $value === 1;
        }
        if (is_string($value) && in_array(strtolower($value), ['0', '1', 'true', 'false'], true)) {
            return strtolower($value) === '1' || strtolower($value) === 'true';
        }
        throw new ApiError('VALIDATION_ERROR', "{$field} must be a boolean", 400, ['field' => $field]);
    }

    /** GET /chats/members.php?chat_id=1
     *
     * v1.8: the response additionally carries the requesting viewer's full
     * `chat` payload (title/about/photo) — the client's getFullChat route
     * builds TL_chatFull from members + chat in ONE call. Member user jsons
     * pass through the v1.8 contact-visibility rule, so a contact member
     * shows the saved name + phone inside the member list.
     */
    public static function members(Request $request): array
    {
        $me = Auth::requireUser($request);
        $meId = (int) $me['id'];
        $chatId = Validator::int($request->q('chat_id'), 'chat_id', 1);

        if (Db::fetch('SELECT user_id FROM chat_members WHERE chat_id = ? AND user_id = ?', [$chatId, $meId]) === null) {
            throw new ApiError('FORBIDDEN', 'You are not a member of this chat', 403);
        }

        $payload = self::membersPayload($meId, $chatId);
        $payload['chat'] = self::chatPayload($me, $chatId);
        return $payload;
    }

    /**
     * Authoritative membership snapshot for one chat: every member row with its
     * role, ordered by join order, plus the live count. Shared by
     * chats/members.php and the v1.8.1 add-member response.
     *
     * @return array{members: list<array<string,mixed>>, count: int}
     */
    private static function membersPayload(int $meId, int $chatId): array
    {
        $rows = Db::fetchAll(
            'SELECT cm.role, cm.last_read_message_id, cm.joined_at, u.*
             FROM chat_members cm
             JOIN users u ON u.id = cm.user_id
             WHERE cm.chat_id = ?
             ORDER BY cm.joined_at ASC, u.id ASC',
            [$chatId],
        );

        $members = array_map(static fn (array $row): array => [
            'user'                 => UserMapper::publicJsonForViewer($row, $meId),
            'role'                 => (string) $row['role'],
            'last_read_message_id' => (int) $row['last_read_message_id'],
            'joined_at'            => (int) $row['joined_at'],
        ], $rows);

        return [
            'members' => $members,
            'count'   => count($members),
        ];
    }

    /**
     * Full chat JSON for one chat; null when the user is not a member.
     * Shared by create/add-member and by SyncController (chat_new).
     *
     * @param array<string,mixed> $me
     * @return array<string,mixed>|null
     */
    public static function chatPayload(array $me, int $chatId): ?array
    {
        $row = Db::fetch(
            'SELECT c.*, m.role, m.last_read_message_id AS my_last_read, p.pinned_at AS _pinned_at
             FROM chats c
             JOIN chat_members m ON m.chat_id = c.id AND m.user_id = ?
             LEFT JOIN dialog_pins p ON p.user_id = m.user_id AND p.chat_id = c.id
             WHERE c.id = ?',
            [(int) $me['id'], $chatId],
        );
        if ($row === null) {
            return null;
        }
        return self::payloadFromRow($row, (int) $me['id']);
    }

    /**
     * POST /chats/set-mode.php  {chat_id, mode: 'cloud'|'secret'}
     *
     * v2.12.0 (T80): flips a private chat between the two encryption modes.
     *  - 'secret': BOTH members must have registered E2EE identities
     *    (e2ee_identities rows) — otherwise the chat would be locked for the
     *    unregistered side (400 E2EE_KEYS_MISSING, field 'peer_user_id').
     *  - 'cloud': always allowed — the escape hatch that keeps chats usable
     *    when sessions rot.
     * History is untouched: existing rows keep their stored form (plain,
     * XOC1 or XOE1) and render via the client's content-triggered hook; only
     * NEW writes obey the new mode. Both members (all their devices) learn
     * the flip through a chat_mode update.
     */
    public static function setMode(Request $request): array
    {
        $me = Auth::requireUser($request);
        $meId = (int) $me['id'];

        $chatId = Validator::int($request->input('chat_id'), 'chat_id', 1);
        $mode = Validator::str($request->input('mode'), 'mode', 1, 16);
        if ($mode !== MessagesController::MODE_CLOUD && $mode !== MessagesController::MODE_SECRET) {
            throw new ApiError('VALIDATION_ERROR', "mode must be 'cloud' or 'secret'", 400, ['field' => 'mode']);
        }

        $chat = Db::fetch('SELECT c.* FROM chats c WHERE c.id = ?', [$chatId]);
        if ($chat === null) {
            throw new ApiError('NOT_FOUND', 'Chat not found', 404);
        }
        if ((string) $chat['type'] !== 'private') {
            throw new ApiError('VALIDATION_ERROR', 'only private chats carry an encryption mode', 400, ['field' => 'chat_id']);
        }
        $meMember = Db::fetch('SELECT user_id FROM chat_members WHERE chat_id = ? AND user_id = ?', [$chatId, $meId]);
        if ($meMember === null) {
            throw new ApiError('FORBIDDEN', 'You are not a member of this chat', 403);
        }

        $current = (string) ($chat['mode'] ?? MessagesController::MODE_CLOUD);
        if ($current === $mode) {
            return ['chat' => self::chatPayload($me, $chatId), 'changed' => false];
        }

        $memberIds = array_map(
            static fn (array $r): int => (int) $r['user_id'],
            Db::fetchAll('SELECT user_id FROM chat_members WHERE chat_id = ?', [$chatId]),
        );

        if ($mode === MessagesController::MODE_SECRET) {
            // every member (usually both) must hold a registered identity
            foreach ($memberIds as $userId) {
                $has = Db::fetch('SELECT user_id FROM e2ee_identities WHERE user_id = ?', [$userId]);
                if ($has === null) {
                    throw new ApiError('E2EE_KEYS_MISSING', "user {$userId} has not registered end-to-end keys yet", 400, ['field' => 'peer_user_id', 'user_id' => $userId]);
                }
            }
        }

        $now = time();
        Db::transaction(static function () use ($chatId, $mode, $meId, $memberIds, $now): void {
            Db::run('UPDATE chats SET mode = ? WHERE id = ?', [$mode, $chatId]);
            UpdateQueue::push($memberIds, UpdateQueue::TYPE_CHAT_MODE, [
                'chat_id'    => $chatId,
                'mode'       => $mode,
                'by_user_id' => $meId,
                'at'         => $now,
            ]);
        });

        return ['chat' => self::chatPayload($me, $chatId), 'changed' => true];
    }

    /** @param array<string,mixed> $me @return array<string,mixed> */
    private static function createPrivate(Request $request, array $me, int $meId): array
    {
        $peerId = Validator::int($request->input('peer_user_id'), 'peer_user_id', 1);
        // T38/15: peer == self is the SAVED MESSAGES chat — a first-class
        // self-chat (pair_key private:me:me, one member row) through the
        // ordinary message system, never a special case that bypasses it.
        $isSelfChat = $peerId === $meId;
        if (!$isSelfChat) {
            if (Db::fetch('SELECT id FROM users WHERE id = ?', [$peerId]) === null) {
                throw new ApiError('NOT_FOUND', "User {$peerId} does not exist", 404);
            }
            // v2.10.0 (T61): starting a DM is an interaction with the peer —
            // refused when the peer has BLOCKED the initiator. chat_invite is
            // deliberately NOT consulted here: it governs group/channel
            // invites (Telegram semantics), never direct messages.
            if (PrivacyService::blocked($peerId, $meId)) {
                throw new ApiError('USER_IS_BLOCKED', 'This user is not accepting interactions from you', 403);
            }
        }

        $pairKey = sprintf('private:%d:%d', min($meId, $peerId), max($meId, $peerId));
        $existing = Db::fetch('SELECT * FROM chats WHERE pair_key = ?', [$pairKey]);
        if ($existing !== null) {
            return ['chat' => self::chatPayload($me, (int) $existing['id']), 'created' => false];
        }

        $now = time();
        try {
            $chatId = Db::transaction(static function () use ($meId, $peerId, $pairKey, $now, $isSelfChat): int {
                $chatId = Db::insert('chats', [
                    'type'       => 'private',
                    // v2.12.0 (T80): private chats are CLOUD by default; the
                    // secret mode is an explicit, later per-chat decision.
                    'mode'       => MessagesController::MODE_CLOUD,
                    'pair_key'   => $pairKey,
                    'created_by' => $meId,
                    'created_at' => $now,
                ]);
                Db::insert('chat_members', ['chat_id' => $chatId, 'user_id' => $meId, 'role' => 'creator', 'joined_at' => $now]);
                if (!$isSelfChat) {
                    Db::insert('chat_members', ['chat_id' => $chatId, 'user_id' => $peerId, 'role' => 'member', 'joined_at' => $now]);
                }
                return $chatId;
            });
        } catch (Throwable $e) {
            if (!Db::isDuplicate($e)) {
                throw $e;
            }
            // A concurrent request created the same chat first: reuse it.
            $existing = Db::fetch('SELECT * FROM chats WHERE pair_key = ?', [$pairKey]);
            if ($existing === null) {
                throw $e;
            }
            return ['chat' => self::chatPayload($me, (int) $existing['id']), 'created' => false];
        }

        // A self-chat has no other member: notify the OWNER's own devices so
        // the dialog surfaces everywhere the account is logged in. A normal
        // private chat notifies the peer only (the creator applied the answer).
        UpdateQueue::push([$isSelfChat ? $meId : $peerId], UpdateQueue::TYPE_CHAT_NEW, ['chat_id' => $chatId]);
        return ['chat' => self::chatPayload($me, $chatId), 'created' => true];
    }

    /** @param array<string,mixed> $me @return array<string,mixed> */
    private static function createGroup(Request $request, array $me, int $meId): array
    {
        $title = Validator::str($request->input('title'), 'title', 1, 200);

        $raw = $request->input('member_ids');
        $memberIds = [];
        if (is_array($raw)) {
            foreach ($raw as $v) {
                $memberIds[] = Validator::int($v, 'member_ids', 1);
            }
        } elseif ($raw !== null) {
            throw new ApiError('VALIDATION_ERROR', 'member_ids must be an array of user ids', 400, ['field' => 'member_ids']);
        }

        $memberIds = array_values(array_unique(array_diff($memberIds, [$meId])));
        if (count($memberIds) > self::MAX_GROUP_MEMBERS) {
            throw new ApiError('VALIDATION_ERROR', 'At most ' . self::MAX_GROUP_MEMBERS . ' members can be added at creation', 400, ['field' => 'member_ids']);
        }
        if ($memberIds !== []) {
            $placeholders = implode(',', array_fill(0, count($memberIds), '?'));
            $found = (int) (Db::fetchColumn("SELECT COUNT(*) FROM users WHERE id IN ($placeholders)", $memberIds) ?? 0);
            if ($found !== count($memberIds)) {
                throw new ApiError('VALIDATION_ERROR', 'member_ids contains a user that does not exist', 400, ['field' => 'member_ids']);
            }
            // v2.10.0 (T61): every invited member's chat_invite rule (and
            // block state towards the actor) is honoured at creation too.
            foreach ($memberIds as $memberId) {
                self::assertInvitable($memberId, $meId);
            }
        }

        $now = time();
        $chatId = Db::transaction(static function () use ($meId, $title, $memberIds, $now): int {
            $chatId = Db::insert('chats', [
                'type'       => 'group',
                'title'      => $title,
                'created_by' => $meId,
                'created_at' => $now,
            ]);
            Db::insert('chat_members', ['chat_id' => $chatId, 'user_id' => $meId, 'role' => 'creator', 'joined_at' => $now]);
            foreach ($memberIds as $memberId) {
                Db::insert('chat_members', ['chat_id' => $chatId, 'user_id' => $memberId, 'role' => 'member', 'joined_at' => $now]);
            }
            return $chatId;
        });

        if ($memberIds !== []) {
            UpdateQueue::push($memberIds, UpdateQueue::TYPE_CHAT_NEW, ['chat_id' => $chatId]);
        }
        return ['chat' => self::chatPayload($me, $chatId), 'created' => true];
    }

    /** @param array<string,mixed> $row @return array<string,mixed> */
    private static function payloadFromRow(array $row, int $meId): array
    {
        $chatId = (int) $row['id'];

        // v2.1 (T39): a hidden_dialogs row clips the visible history for this
        // user — the dialog's last_message (and unread count) may only count
        // messages with an id greater than hidden_before_id (listChats
        // selected it as _hidden_before_id; other callers have none).
        $hiddenBeforeId = isset($row['_hidden_before_id']) && $row['_hidden_before_id'] !== null
            ? (int) $row['_hidden_before_id']
            : null;

        $lastRow = Db::fetch(
            MessageMapper::SELECT_WITH_MEDIA
            . ' WHERE m.chat_id = ? AND m.deleted_at IS NULL'
            . ($hiddenBeforeId !== null ? ' AND m.id > ' . $hiddenBeforeId : '')
            . ' ORDER BY m.id DESC LIMIT 1',
            [$chatId],
        );
        $lastMessage = $lastRow !== null ? MessageMapper::json($lastRow) : null;

        $unread = (int) (Db::fetchColumn(
            'SELECT COUNT(*) FROM messages
             WHERE chat_id = ? AND id > ? AND sender_id <> ? AND deleted_at IS NULL'
            . ($hiddenBeforeId !== null ? ' AND id > ' . $hiddenBeforeId : ''),
            [$chatId, (int) $row['my_last_read'], $meId],
        ) ?? 0);

        $membersCount = (int) (Db::fetchColumn(
            'SELECT COUNT(*) FROM chat_members WHERE chat_id = ?',
            [$chatId],
        ) ?? 0);

        // v2.7.0 (T56): per-user pin state rides every chat payload.
        $pinnedAt = isset($row['_pinned_at']) && $row['_pinned_at'] !== null ? (int) $row['_pinned_at'] : 0;

        $peer = null;
        if ($row['type'] === 'private') {
            $peerRow = Db::fetch(
                'SELECT u.* FROM users u
                 JOIN chat_members cm ON cm.user_id = u.id
                 WHERE cm.chat_id = ? AND u.id <> ?
                 LIMIT 1',
                [$chatId, $meId],
            );
            $isSelfChat = false;
            if ($peerRow === null) {
                // T38/15: no "other" member — the Saved Messages self-chat.
                // The peer payload is the owner's own row (public shape; the
                // client renders this dialog as Saved Messages by dialog id).
                $peerRow = Db::fetch('SELECT * FROM users WHERE id = ?', [$meId]);
                $isSelfChat = true;
            }
            // v2.10.0 (T61): the peer json obeys the peer's privacy rules for
            // this viewer (photo/about/status). The self-chat keeps its
            // viewer-0 call (the owner sees their own whole row; a selfJson
            // here would wholesale-replace the client's self user).
            $peer = $peerRow !== null
                ? UserMapper::publicJsonForViewer($peerRow, $isSelfChat ? 0 : $meId)
                : null;
        }

        return ChatMapper::json($row, $lastMessage, $unread, $peer, $membersCount, $pinnedAt);
    }
}

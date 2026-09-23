package org.telegram.tgnet.rest;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;

/**
 * T13 structural pass: the sender-hydration primitive shared by the two
 * consumers that used to duplicate it —
 * {@link RestDispatcher#hydrateSenders} (history/send/dialogs answer vectors)
 * and {@link UpdatePoller#resolveUsers} (sync message_new batches).
 *
 * <p>Contract: one /users/get.php per batch for the ids the in-memory
 * MessagesController cache does not know; ids the cache knows cost nothing.
 * Failures degrade to an empty list — callers keep their own fallbacks
 * (raw-id rendering / placeholder users), a hydration miss never fails a
 * message.
 */
final class UserHydration {

    private UserHydration() {
    }

    /** Distinct from_id user ids of the messages, in first-seen order. */
    static ArrayList<Long> senderIds(List<? extends TLRPC.Message> messages) {
        ArrayList<Long> ids = new ArrayList<>();
        if (messages == null) {
            return ids;
        }
        for (int a = 0; a < messages.size(); a++) {
            TLRPC.Message message = messages.get(a);
            if (message.from_id instanceof TLRPC.TL_peerUser) {
                long senderId = ((TLRPC.TL_peerUser) message.from_id).user_id;
                if (senderId != 0 && !ids.contains(senderId)) {
                    ids.add(senderId);
                }
            }
        }
        return ids;
    }

    /**
     * One /users/get.php call for the subset of {@code senderIds} missing
     * from the MessagesController cache. Empty list when everything is
     * cached, the batch is empty, or the fetch failed (logged, not thrown).
     */
    static ArrayList<TLRPC.TL_user> fetchUncached(int account, List<Long> senderIds) {
        ArrayList<TLRPC.TL_user> fetched = new ArrayList<>();
        ArrayList<Long> missing = null;
        MessagesController controller = MessagesController.getInstance(account);
        for (int a = 0; a < senderIds.size(); a++) {
            long senderId = senderIds.get(a);
            if (!(controller.getUser(senderId) instanceof TLRPC.TL_user)) {
                if (missing == null) {
                    missing = new ArrayList<>();
                }
                missing.add(senderId);
            }
        }
        if (missing == null) {
            return fetched;
        }
        long[] ids = new long[missing.size()];
        for (int a = 0; a < ids.length; a++) {
            ids[a] = missing.get(a);
        }
        try {
            fetched.addAll(TlJsonMapper.parseUsers(RestGateway.getInstance(account).usersGet(ids)));
        } catch (Exception e) {
            FileLog.e("UserHydration: sender hydration failed, callers degrade gracefully", e);
        }
        return fetched;
    }
}

package org.telegram.tgnet.rest.e2ee;

import org.junit.Test;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T78 — the deferral gate: ONLY the "peer has no key yet" code, ONLY secret
 * (chat-space, negative) dialogs, never cloud rows.
 */
public class XoSecretPendingTest {

    private static TLRPC.Message row(long dialogId) {
        TLRPC.TL_message m = new TLRPC.TL_message();
        m.dialog_id = dialogId;
        return m;
    }

    @Test
    public void onlyTheNoPeerKeyCodeDefers() {
        List<TLRPC.Message> rows = new ArrayList<>();
        rows.add(row(-4242));
        assertTrue(XoSecretPending.deferrable(XoSecret.REASON_NO_PEER_KEY, rows));
        assertFalse(XoSecretPending.deferrable("SECRET_IDENTITY_CHANGED", rows));
        assertFalse(XoSecretPending.deferrable(null, rows));
        assertFalse(XoSecretPending.deferrable(XoSecret.REASON_NO_PEER_KEY, new ArrayList<>()));
        assertFalse(XoSecretPending.deferrable(XoSecret.REASON_NO_PEER_KEY, null));
    }

    @Test
    public void cloudDialogsNeverDefer() {
        List<TLRPC.Message> rows = new ArrayList<>();
        rows.add(row(10001)); // cloud private dialog = positive user id
        rows.add(row(-33));   // group dialog
        assertFalse(XoSecretPending.deferrable(XoSecret.REASON_NO_PEER_KEY, rows));
    }

    @Test
    public void mixedBatchNeverDefers() {
        // one cloud row in the batch -> the whole batch fails loudly instead
        List<TLRPC.Message> rows = new ArrayList<>();
        rows.add(row(-4242));
        rows.add(row(10001));
        assertFalse(XoSecretPending.deferrable(XoSecret.REASON_NO_PEER_KEY, rows));
    }
}

package org.telegram.tgnet.rest;

import org.junit.BeforeClass;
import org.junit.Test;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * T63 — small pure primitives: sender hydration ids, the server-corrected
 * clock, and the transport-vs-api exception split.
 */
public class ClientPrimitivesTest {

    @BeforeClass
    public static void setUp() {
        XoTestEnv.init();
    }

    // ---- UserHydration.senderIds --------------------------------------------------

    @Test
    public void senderIds_extractsDistinctSendersInFirstSeenOrder() {
        List<TLRPC.Message> messages = new ArrayList<>();
        messages.add(messageFrom(7L));
        messages.add(messageFrom(3L));
        messages.add(messageFrom(7L));
        messages.add(messageFrom(3L));
        messages.add(messageFrom(12L));
        ArrayList<Long> ids = UserHydration.senderIds(messages);
        assertEquals(Arrays.asList(7L, 3L, 12L), ids);
    }

    @Test
    public void senderIds_ignoresNonUserPeersAndZero() {
        TLRPC.Message channelPost = new TLRPC.Message();
        channelPost.from_id = new TLRPC.TL_peerChannel();
        ((TLRPC.TL_peerChannel) channelPost.from_id).channel_id = 42;
        List<TLRPC.Message> messages = new ArrayList<>();
        messages.add(channelPost);
        messages.add(messageFrom(0L));   // zero id — never hydrated
        messages.add(new TLRPC.Message()); // no from_id at all
        assertTrue(UserHydration.senderIds(messages).isEmpty());
    }

    @Test
    public void senderIds_nullAndEmptyAreSafe() {
        assertTrue(UserHydration.senderIds(null).isEmpty());
        assertTrue(UserHydration.senderIds(new ArrayList<TLRPC.Message>()).isEmpty());
    }

    private static TLRPC.Message messageFrom(long userId) {
        TLRPC.Message m = new TLRPC.Message();
        m.from_id = new TLRPC.TL_peerUser();
        ((TLRPC.TL_peerUser) m.from_id).user_id = userId;
        return m;
    }

    // ---- XoClock (server-corrected time) ------------------------------------------

    private static void resetClock() throws Exception {
        XoTestEnv.writeStaticField(XoClock.class, "serverAtSyncSeconds", 0L);
        XoTestEnv.writeStaticField(XoClock.class, "elapsedAtSyncMs", 0L);
        XoTestEnv.writeStaticField(XoClock.class, "deviceAtSyncSeconds", 0L);
    }

    @Test
    public void clock_withoutSyncObservation_fallsBackToDeviceWallClock() throws Exception {
        resetClock();
        int deviceNow = (int) (System.currentTimeMillis() / 1000L);
        int now = XoClock.currentTimeSeconds();
        assertTrue("before the first poll the clock is the device clock",
                Math.abs(now - deviceNow) < 5);
    }

    @Test
    public void clock_anchorsOnTheLastServerTime() throws Exception {
        resetClock();
        long deviceNow = System.currentTimeMillis() / 1000L;
        // a server 60 s ahead of the device
        XoClock.onServerTime(deviceNow + 60);
        int now = XoClock.currentTimeSeconds();
        assertTrue("server-corrected now must be ~device+60 (elapsed==0 on the JVM stub)",
                Math.abs(now - (deviceNow + 60)) < 5);
    }

    @Test
    public void clock_rejectsGarbageDeltas_beyond24h() throws Exception {
        resetClock();
        long deviceNow = System.currentTimeMillis() / 1000L;
        XoClock.onServerTime(deviceNow + 60);   // accepted anchor
        XoClock.onServerTime(deviceNow + 3 * 86400); // garbage: 3 days ahead
        int now = XoClock.currentTimeSeconds();
        assertTrue("garbage observation must be ignored, anchor kept",
                Math.abs(now - (deviceNow + 60)) < 5);
    }

    @Test
    public void clock_ignoresNonPositiveObservations() throws Exception {
        resetClock();
        XoClock.onServerTime(0);
        XoClock.onServerTime(-42);
        long deviceNow = System.currentTimeMillis() / 1000L;
        int now = XoClock.currentTimeSeconds();
        assertTrue(Math.abs(now - deviceNow) < 5);
    }

    // ---- transport vs api exceptions ----------------------------------------------

    @Test
    public void transportException_isDistinctFromApiFailures() {
        // network-level failures (DNS/TLS/timeout) must be XoTransportException;
        // anything carrying a backend code must be XoApiException
        XoTransportException transport = new XoTransportException("sync/index.php failed: timeout",
                new java.io.IOException("timeout"));
        assertEquals("sync/index.php failed: timeout", transport.getMessage());
        assertTrue(transport.getCause() instanceof java.io.IOException);
        assertFalseInheritance(transport);
    }

    private static void assertFalseInheritance(XoTransportException transport) {
        // XoApiException.isGenuineServerRejection can never be true for a transport failure
        org.junit.Assert.assertFalse(transport instanceof XoApiException);
    }
}

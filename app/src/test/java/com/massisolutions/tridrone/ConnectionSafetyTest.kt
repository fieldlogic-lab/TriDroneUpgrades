package com.massisolutions.tridrone

import org.junit.Assert.*
import org.junit.Test

class ConnectionSafetyTest {
    @Test fun reconnectIsBoundedAndFreshnessUsesNonnegativeMonotonicAge() {
        assertTrue(ConnectionPolicy.retry(1))
        assertTrue(ConnectionPolicy.retry(2))
        assertFalse(ConnectionPolicy.retry(3))
        assertFalse(ConnectionPolicy.retry(0))
        assertNull(ConnectionPolicy.age(100, 101))
        assertNull(ConnectionPolicy.age(100, 0))
        assertFalse(ConnectionPolicy.fresh(-1))
        assertFalse(ConnectionPolicy.fresh(5001))
        assertTrue(ConnectionPolicy.fresh(ConnectionPolicy.age(6000, 1000)))
    }

    @Test fun newTransportCancelsOldAndRejectsItsUpdates() {
        var cancelled = false
        var writes = 0
        GnssConnectionOwner.claim("old") { cancelled = true }
        GnssConnectionOwner.claim("new") {}
        assertTrue(cancelled)
        GnssConnectionOwner.withCurrent("old") { writes++ }
        GnssConnectionOwner.release("old")
        assertTrue(GnssConnectionOwner.current("new"))
        GnssConnectionOwner.withCurrent("new") { writes++ }
        assertEquals(1, writes)
        GnssConnectionOwner.release("new")
        assertFalse(GnssConnectionOwner.active())
    }

    @Test fun receiverEpochRejectsFrozenAndBackwardsDataButAllowsMidnight() {
        val tracker = ReceiverEpochTracker()
        assertTrue(tracker.accept(86399.0))
        assertFalse(tracker.accept(86399.0))
        assertTrue(tracker.accept(0.0))
        assertFalse(tracker.accept(86398.0))
        assertFalse(tracker.accept(Double.NaN))
        assertTrue(NmeaEpoch.aligned(86399.5, 0.2))
        assertFalse(NmeaEpoch.aligned(1.0, 3.0))
        assertNull(NmeaEpoch.seconds("246000"))
        assertNull(NmeaEpoch.seconds("1234"))
    }

    @Test fun profileRequiresLiteralValidIpv4() {
        assertTrue(ConnectionProfile.validIpv4("192.168.42.1"))
        assertFalse(ConnectionProfile.validIpv4("192.168.42.256"))
        assertFalse(ConnectionProfile.validIpv4("receiver.local"))
        assertFalse(ConnectionProfile.validIpv4(" 192.168.42.1"))
    }

    @Test fun csvPreservesRawSentenceCommasAndQuotes() {
        val values = listOf("123", "\$GNGGA,raw,\"quoted\"*AB", "")
        assertEquals(values, Csv.parse(Csv.row(values).trimEnd('\n')))
        assertNull(Csv.parse("\"unterminated"))
        assertNull(Csv.parse("\"closed\"junk"))
    }
}

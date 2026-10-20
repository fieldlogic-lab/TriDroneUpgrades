package com.massisolutions.tridrone

import org.junit.Assert.*
import org.junit.Test

class NmeaGnssAdapterTest {
    private fun sentence(body: String): String {
        val checksum = body.fold(0) { value, char -> value xor char.code }
        return "$" + body + "*" + checksum.toString(16).uppercase().padStart(2, '0')
    }

    @Test fun parsesValidatedRtkFixedGga() {
        val input = sentence("GPGGA,123519,4048.0000,N,07303.0000,W,4,12,0.7,10.2,M,-30.0,M,,")
        val fix = NmeaGnssAdapter.parseGga(input, 123456L)
        assertNotNull(fix)
        assertEquals(DataOrigin.LIVE_HARDWARE, fix!!.origin)
        assertEquals(4, fix.fixQuality)
        assertEquals(40.8, fix.latitude, 0.000001)
        assertEquals(-73.05, fix.longitude, 0.000001)
        assertEquals(10.2, fix.altitudeMeters!!, 0.000001)
    }

    @Test fun rejectsCorruptChecksumAndInvalidCoordinates() {
        val good = sentence("GPGGA,123519,4048.0000,N,07303.0000,W,4,12,0.7,10.2,M,-30.0,M,,")
        assertNull(NmeaGnssAdapter.parseGga(good.dropLast(2) + "ZZ", 123L))
        val invalid = sentence("GPGGA,123519,9948.0000,N,07303.0000,W,4,12,0.7,10.2,M,-30.0,M,,")
        assertNull(NmeaGnssAdapter.parseGga(invalid, 123L))
    }

    @Test fun floatDoesNotPassLiveGate() {
        val input = sentence("GPGGA,123519,4048.0000,N,07303.0000,W,5,12,0.7,10.2,M,-30.0,M,,")
        val fix = NmeaGnssAdapter.parseGga(input, 123L)!!
        assertFalse(ObservationGate.validForLiveSurvey(fix, DepthObservation(123L, 2.0, DataOrigin.LIVE_HARDWARE)))
    }
    @Test fun explicitNoFixAndMalformedGgaInvalidatePreviousEpochImmediately() {
        // Synthetic malformed protocol inputs; these are not captured pool observations.
        val state = HydroGnssState()
        state.connection(true)
        val good = HydroGnss("fixed", 1000, 1000, 40.8, -73.05, 4, 10.0, null, null, "120000")
        val invalidBodies = listOf(
            "GPGGA,120000,,,,,0,00,99.9,,M,,M,,",
            "GPGGA,120000,4060.0000,N,07303.0000,W,4,12,0.7,10.2,M,-30.0,M,,",
            "GPGGA,120000,4048.0000,X,07303.0000,W,4,12,0.7,10.2,M,-30.0,M,,",
            "GPGGA,120000,4048.0000,N,07303.0000,N,4,12,0.7,10.2,M,-30.0,M,,",
            "GPGGA,120000,-4048.0000,N,07303.0000,W,4,12,0.7,10.2,M,-30.0,M,,")
        invalidBodies.forEach { body ->
            state.accept(good)
            val input = sentence(body)
            assertTrue(NmeaGnssAdapter.invalidatesFix(input))
            assertTrue(state.invalidateForSentence(input))
            assertNull(state.latest)
        }
        state.accept(good)
        assertFalse(state.invalidateForSentence("$" + "GPGGA*00"))
        assertNotNull(state.latest)
    }

    @Test fun gstPrecisionRequiresSameReceiverEpochAndFreshReceipt() {
        val tracker = NmeaPrecisionTracker()
        val gst = sentence("GPGST,120000.00,0.1,0.1,0.1,0.0,0.01,0.02,0.03")
        val gga = sentence("GPGGA,120000,4048.0000,N,07303.0000,W,4,12,0.7,10.2,M,-30.0,M,,")
        tracker.accept(gst, 1000, 1_000_000_000)
        val precision = tracker.forGga(gga, 1_500_000_000)
        assertNotNull(precision)
        assertEquals(gst, precision!!.raw)
        assertEquals(0.03, precision.verticalMeters, 0.0)
        assertNull(tracker.forGga(sentence("GPGGA,120001,4048.0000,N,07303.0000,W,4,12,0.7,10.2,M,-30.0,M,,"), 1_500_000_000))
        assertNull(tracker.forGga(gga, 4_000_000_001))
        assertNull(tracker.forGga(gga, 1))
        tracker.accept(sentence("GPGST,246060,0.1,0.1,0.1,0.0,0.01,0.02,0.03"), 1500, 1_500_000_000)
        assertNull(tracker.forGga(gga, 1_500_000_000))
        tracker.accept(gst, 1000, 1_000_000_000)
        tracker.reset()
        assertNull(tracker.forGga(gga, 1_500_000_000))
        // Real receivers may emit GST after GGA; attach only to that same epoch.
        tracker.accept(gga, 1000, 1_000_000_000)
        assertNull(tracker.forLatest(1_000_000_000))
        tracker.accept(gst, 1100, 1_100_000_000)
        assertNotNull(tracker.forLatest(1_100_000_000))
        tracker.accept(sentence("GPGGA,120001,4048.0000,N,07303.0000,W,4,12,0.7,10.2,M,-30.0,M,,"), 2000, 2_000_000_000)
        assertNull(tracker.forLatest(2_000_000_000))
    }

}


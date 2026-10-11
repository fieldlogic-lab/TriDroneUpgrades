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
}

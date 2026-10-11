package com.massisolutions.tridrone

import org.junit.Assert.*
import org.junit.Test

class ObservationGateTest {
    private fun gnss(origin: DataOrigin, quality: Int = 4) =
        GnssObservation(1000L, 40.8, -73.05, 10.0, quality, origin)
    private fun depth(origin: DataOrigin) =
        DepthObservation(1000L, 2.5, origin)

    @Test fun rejectMixedHardwareAndDemo() {
        assertFalse(ObservationGate.sameSource(gnss(DataOrigin.LIVE_HARDWARE), depth(DataOrigin.DEMO_SYNTHETIC)))
        assertFalse(ObservationGate.validForLiveSurvey(gnss(DataOrigin.DEMO_SYNTHETIC), depth(DataOrigin.DEMO_SYNTHETIC)))
    }

    @Test fun rejectNonFixedLivePosition() {
        assertFalse(ObservationGate.validForLiveSurvey(gnss(DataOrigin.LIVE_HARDWARE, 5), depth(DataOrigin.LIVE_HARDWARE)))
    }

    @Test fun acceptEligibleLiveSampleWithoutClaimingSurveyReadiness() {
        assertTrue(ObservationGate.validForLiveSurvey(gnss(DataOrigin.LIVE_HARDWARE), depth(DataOrigin.LIVE_HARDWARE)))
    }
}

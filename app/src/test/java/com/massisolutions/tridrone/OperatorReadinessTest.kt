package com.massisolutions.tridrone

import org.junit.Assert.*
import org.junit.Test

class OperatorReadinessTest {
    private fun state(
        receiver: String = "disconnected", quality: String? = null,
        age: Long? = null, sounder: String = "disconnected",
        depthVerified: Boolean = false, synchronized: Boolean = false
    ) = OperatorReadiness.State(receiver, quality, age, sounder, depthVerified, synchronized)

    @Test fun disconnectedShowsConnectInstructionAndCannotRecord() {
        val result = OperatorReadiness.evaluate(state())
        assertTrue(result.gnss.contains("Connect"))
        assertTrue(result.sounder.contains("Connect"))
        assertFalse(result.canRecord)
    }

    @Test fun connectedTransportWithoutNmeaIsNotAValidFix() {
        val result = OperatorReadiness.evaluate(state(receiver = "connected"))
        assertTrue(result.gnss.contains("no recent position"))
        assertFalse(result.canRecord)
    }

    @Test fun staleFixedQualityMustNotShowLiveFix() {
        val result = OperatorReadiness.evaluate(state(receiver = "connected", quality = "4", age = 6000))
        assertTrue(result.gnss.contains("no recent position"))
        assertFalse(result.canRecord)
    }

    @Test fun floatWaitsForFix() {
        val result = OperatorReadiness.evaluate(state(receiver = "connected", quality = "5", age = 1000))
        assertTrue(result.gnss.contains("FLOAT"))
        assertFalse(result.canRecord)
    }

    @Test fun bluetoothConnectionDoesNotClaimVerifiedDepth() {
        val result = OperatorReadiness.evaluate(state(receiver = "connected", quality = "4", age = 300, sounder = "connected"))
        assertTrue(result.gnss.contains("RTK FIX"))
        assertTrue(result.sounder.contains("not yet verified"))
        assertFalse(result.canRecord)
    }

    @Test fun reconnectDoesNotClaimFixDespiteRecentCachedQuality() {
        val result = OperatorReadiness.evaluate(state(receiver = "reconnecting", quality = "4", age = 100))
        assertTrue(result.gnss.contains("reconnecting"))
        assertFalse(result.canRecord)
    }

    @Test fun completedConnectionIndicatorsStillDoNotUnlockUnverifiedRecording() {
        val result = OperatorReadiness.evaluate(state(receiver = "connected", quality = "4", age = 500, sounder = "connected", depthVerified = true, synchronized = true))
        assertFalse(result.canRecord)
        assertTrue(result.recording.contains("field acceptance"))
    }
}

package com.massisolutions.tridrone

/**
 * Crew-facing instructions, independent of Android UI.
 * A transport connection never means that survey-grade observations are ready.
 */
object OperatorReadiness {
    data class State(
        val receiverState: String,
        val fixQuality: String?,
        val fixAgeMs: Long?,
        val sounderState: String,
        val depthProtocolVerified: Boolean = false,
        val synchronizationVerified: Boolean = false
    )

    data class Display(
        val gnss: String,
        val sounder: String,
        val recording: String,
        val canRecord: Boolean
    )

    fun evaluate(state: State): Display {
        val fresh = state.receiverState == "connected" &&
            state.fixAgeMs != null && state.fixAgeMs in 0..5000L
        val gnss = when {
            state.receiverState == "reconnecting" -> "Receiver lost — reconnecting; check Wi-Fi"
            state.receiverState == "error" -> "Receiver error — check connection settings"
            state.receiverState == "connecting" -> "Connecting to RS2+ — awaiting data"
            state.receiverState != "connected" -> "Connect the Reach RS2+"
            !fresh -> "Connected, but no recent position — check NMEA output"
            state.fixQuality == "4" -> "RTK FIX received — verify control before surveying"
            state.fixQuality == "5" -> "RTK FLOAT — wait for FIX"
            else -> "Position received, but RTK FIX required"
        }
        val sounder = when {
            state.sounderState == "error" -> "Sounder connection failed — check pairing"
            state.sounderState == "connecting" -> "Connecting to HydroLite"
            state.sounderState == "connected" && !state.depthProtocolVerified ->
                "Bluetooth connected — raw depth not yet verified"
            state.sounderState == "connected" && state.depthProtocolVerified ->
                "Depth interface verified — check live samples"
            else -> "Connect and inspect HydroLite"
        }
        // No validated depth protocol or synchronized capture in this milestone.
        // Keep the actual recording control locked even after a GNSS FIX.
        return Display(gnss, sounder,
            if (!state.depthProtocolVerified) "Recording locked — depth protocol unverified"
            else if (!state.synchronizationVerified) "Recording locked — synchronization unverified"
            else "Recording requires final field acceptance",
            canRecord = false)
    }
}

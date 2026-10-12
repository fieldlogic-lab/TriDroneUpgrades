package com.massisolutions.tridrone

/** Pure crew-facing readiness. Connection never implies a valid survey observation. */
object OperatorReadiness {
    enum class Lamp { RED, AMBER, GREEN }
    data class State(
        val receiverState: String,
        val fixQuality: String?,
        val fixAgeMs: Long?,
        val sounderState: String,
        val depthProtocolVerified: Boolean = false,
        val synchronizationVerified: Boolean = false,
        val horizontalMeters: Double? = null,
        val verticalMeters: Double? = null,
        val gstAgeMs: Long? = null,
        val correctionAgeSeconds: Double? = null,
        val depthAgeMs: Long? = null,
        val depthMeters: Double? = null,
        val precisionEpochAligned: Boolean = true
    )
    data class Display(
        val gnss: String, val sounder: String, val recording: String, val canRecord: Boolean,
        val gnssLamp: Lamp, val depthLamp: Lamp, val collectionLamp: Lamp,
        val gnssLabel: String, val depthLabel: String, val collectionLabel: String,
        val horizontalMeters: Double?, val verticalMeters: Double?
    )
    fun evaluate(state: State): Display {
        val connected = state.receiverState == "connected"
        val fresh = connected && ConnectionPolicy.fresh(state.fixAgeMs)
        val gstFresh = fresh && ConnectionPolicy.fresh(state.gstAgeMs) && state.precisionEpochAligned
        val horizontal = state.horizontalMeters?.takeIf { gstFresh && it.isFinite() && it >= 0 }
        val vertical = state.verticalMeters?.takeIf { gstFresh && it.isFinite() && it >= 0 }
        val oldCorrections = state.correctionAgeSeconds?.let { !it.isFinite() || it < 0 || it > 10.0 } == true
        val readyGnss = fresh && state.fixQuality == "4" && horizontal != null && vertical != null && !oldCorrections
        val gnss = when {
            state.receiverState == "reconnecting" -> "Receiver lost — reconnecting; check receiver Wi-Fi/network and power"
            state.receiverState == "connecting" -> "Connecting to RS2+ — keep the phone on the receiver network"
            state.receiverState == "error" -> "Receiver error — check network, receiver power and configured NMEA IP/port, then reconnect"
            !connected -> "Connect the Reach RS2+ using Equipment"
            !fresh -> "Connected, but no recent position — enable GGA and GST NMEA output; check receiver power"
            state.fixQuality == "5" -> "RTK FLOAT — wait for FIX; check base/NTRIP corrections and sky view"
            state.fixQuality != "4" -> "RTK FIX required — check correction source and sky view"
            oldCorrections -> "RTK FIX reports old corrections — restore base/NTRIP correction link"
            horizontal == null || vertical == null -> "RTK FIX received — enable fresh GST horizontal and vertical precision"
            else -> "RTK FIX received — receiver precision available; verify independent control before surveying"
        }
        val gnssLabel = when {
            readyGnss -> "RTK FIX"
            state.receiverState in listOf("connecting", "reconnecting") -> "CONNECTING"
            connected && !fresh -> "POSITION STALE"
            fresh && state.fixQuality == "5" -> "FLOAT — WAIT"
            fresh && oldCorrections -> "CHECK CORRECTIONS"
            fresh && state.fixQuality == "4" -> "CHECK H/V"
            connected -> "CHECK FIX"
            state.receiverState == "error" -> "CONNECTION ERROR"
            else -> "DISCONNECTED"
        }
        val depthFresh = state.sounderState == "connected" && state.depthProtocolVerified &&
            ConnectionPolicy.fresh(state.depthAgeMs) && state.depthMeters?.let { it.isFinite() && it >= 0 } == true
        val sounder = when {
            state.sounderState == "error" -> "Sounder connection failed — confirm pairing and the supported interface with the hardware supplier"
            state.sounderState == "connecting" -> "Connecting to HydroLite diagnostic interface — keep the sounder powered"
            depthFresh -> "Verified real depth observation received"
            state.sounderState == "connected" -> "Bluetooth connected — actual depth stream not yet verified; inspect raw diagnostics"
            else -> "Connect and inspect HydroLite in Equipment; confirm the supported Bluetooth interface"
        }
        // A verified decoder, geometry, datum handling, epoch pairing and field acceptance are
        // not implemented. Neither preference flags nor a GNSS FIX can authorize acquisition.
        val recording = if (state.depthProtocolVerified && state.synchronizationVerified)
            "Recording requires final field acceptance" else "Recording unavailable — real depth and synchronized acquisition are unverified"
        return Display(gnss, sounder, recording, false,
            when { readyGnss -> Lamp.GREEN; connected || state.receiverState in listOf("connecting", "reconnecting") -> Lamp.AMBER; else -> Lamp.RED },
            when { depthFresh -> Lamp.GREEN; state.sounderState in listOf("connected", "connecting") -> Lamp.AMBER; else -> Lamp.RED },
            if (state.depthProtocolVerified && state.synchronizationVerified) Lamp.AMBER else Lamp.RED,
            gnssLabel, when { depthFresh -> "VERIFIED"; state.sounderState == "connected" -> "UNVERIFIED"; state.sounderState == "connecting" -> "CONNECTING"; else -> "DISCONNECTED" },
            if (state.depthProtocolVerified && state.synchronizationVerified) "PREFLIGHT" else "UNAVAILABLE", horizontal, vertical)
    }
}

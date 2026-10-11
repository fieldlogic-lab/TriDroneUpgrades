package com.massisolutions.tridrone

/**
 * Android-independent state machine for mode selection and demo readiness.
 * Hardware services and UI must not mutate this state directly.
 */
class SurveyModeController {
    enum class Mode { LIVE, DEMO }
    data class State(
        val mode: Mode = Mode.LIVE,
        val demoGnssConnected: Boolean = false,
        val demoSounderConnected: Boolean = false,
        val recording: Boolean = false
    ) {
        val canRecordDemo: Boolean
            get() = mode == Mode.DEMO && demoGnssConnected && demoSounderConnected
    }

    var state: State = State()
        private set

    fun switchMode(mode: Mode): Boolean {
        if (state.recording) return false
        state = State(mode = mode)
        return true
    }

    fun connectDemoGnss(): Boolean {
        if (state.mode != Mode.DEMO || state.recording) return false
        state = state.copy(demoGnssConnected = true)
        return true
    }

    fun connectDemoSounder(): Boolean {
        if (state.mode != Mode.DEMO || !state.demoGnssConnected || state.recording) return false
        state = state.copy(demoSounderConnected = true)
        return true
    }

    fun startDemo(): Boolean {
        if (!state.canRecordDemo || state.recording) return false
        state = state.copy(recording = true)
        return true
    }

    fun stopDemo() {
        state = state.copy(recording = false)
    }
}

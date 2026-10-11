package com.massisolutions.tridrone

import org.junit.Assert.*
import org.junit.Test

class SurveyModeControllerTest {
    @Test fun liveCannotStartDemo() {
        val controller = SurveyModeController()
        assertFalse(controller.connectDemoGnss())
        assertFalse(controller.startDemo())
        assertEquals(SurveyModeController.Mode.LIVE, controller.state.mode)
    }

    @Test fun requiresOrderedConnections() {
        val controller = SurveyModeController()
        assertTrue(controller.switchMode(SurveyModeController.Mode.DEMO))
        assertFalse(controller.connectDemoSounder())
        assertFalse(controller.startDemo())
        assertTrue(controller.connectDemoGnss())
        assertFalse(controller.startDemo())
        assertTrue(controller.connectDemoSounder())
        assertTrue(controller.startDemo())
    }

    @Test fun cannotSwitchOrReconnectWhileRecording() {
        val controller = SurveyModeController()
        controller.switchMode(SurveyModeController.Mode.DEMO)
        controller.connectDemoGnss()
        controller.connectDemoSounder()
        controller.startDemo()
        assertFalse(controller.switchMode(SurveyModeController.Mode.LIVE))
        assertFalse(controller.connectDemoGnss())
        assertFalse(controller.connectDemoSounder())
        assertTrue(controller.state.recording)
    }

    @Test fun stoppingThenSwitchingResetsConnections() {
        val controller = SurveyModeController()
        controller.switchMode(SurveyModeController.Mode.DEMO)
        controller.connectDemoGnss()
        controller.connectDemoSounder()
        controller.startDemo()
        controller.stopDemo()
        assertTrue(controller.switchMode(SurveyModeController.Mode.LIVE))
        assertFalse(controller.state.demoGnssConnected)
        assertFalse(controller.state.demoSounderConnected)
        assertFalse(controller.state.recording)
    }
}

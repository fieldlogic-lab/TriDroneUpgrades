package com.massisolutions.tridrone

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class SurveyViewportTest {
    @Test fun initialFrameUsesRecordedExtentAndSinglePointIsFinite() {
        val view = SurveyViewport()
        view.receive(listOf(MapPosition(40.8, -73.05)), MapPosition(41.0, -74.0))
        assertEquals(40.8, view.latitude, 0.0)
        assertTrue(view.span.isFinite() && view.span > 0)
        assertTrue(view.fit(listOf(MapPosition(40.8, -73.05), MapPosition(40.9, -73.15), MapPosition(Double.NaN, 0.0))))
        assertEquals(40.85, view.latitude, 1e-10)
        assertEquals(-73.1, view.longitude, 1e-10)
        assertTrue(view.span >= 0.1)
    }
    @Test fun emptyDataWaitsForFirstValidLivePositionAndNeverFitsInvalidCoordinates() {
        val view = SurveyViewport()
        view.receive(emptyList(), null); assertFalse(view.initialized)
        assertFalse(view.fit(listOf(MapPosition(91.0, 0.0), MapPosition(0.0, Double.NaN))))
        view.receive(emptyList(), MapPosition(40.0, -73.0))
        assertEquals(40.0, view.latitude, 0.0)
        view.receive(emptyList(), MapPosition(41.0, -74.0))
        assertEquals(40.0, view.latitude, 0.0) // free pan never follows implicitly.
    }
    @Test fun manualPanAndZoomSuspendFollowUntilExplicitReenableAndFitRestoresSurvey() {
        val view = SurveyViewport(); val track = listOf(MapPosition(40.0, -73.0), MapPosition(40.1, -73.1))
        view.receive(track, track.last()); view.follow(track.last())
        assertEquals(SurveyViewport.Mode.FOLLOW_VESSEL, view.mode)
        view.pan(0.2, 0.2); val panned = view.latitude
        view.receive(track, MapPosition(42.0, -72.0))
        assertEquals(panned, view.latitude, 0.0)
        assertEquals(SurveyViewport.Mode.FREE_PAN, view.mode)
        view.follow(null); view.receive(track, MapPosition(42.0, -72.0))
        assertEquals(42.0, view.latitude, 0.0)
        view.zoom(2.0); assertEquals(SurveyViewport.Mode.FREE_PAN, view.mode)
        view.fit(track); assertEquals(40.05, view.latitude, 1e-10)
        view.follow(track.last()); assertEquals(40.1, view.latitude, 0.0)
    }
    @Test fun manualMovementBeforeFirstRecordingPreventsUnwantedInitialSnap() {
        val view = SurveyViewport(); view.pan(1.0, 1.0)
        view.receive(listOf(MapPosition(40.0, -73.0)), MapPosition(41.0, -74.0))
        assertEquals(1.0, view.latitude, 0.0)
        val zoomed = SurveyViewport(); zoomed.zoom(2.0)
        zoomed.receive(emptyList(), MapPosition(40.0, -73.0))
        assertEquals(0.0, zoomed.latitude, 0.0)
    }
    @Test fun enablingFollowBeforeRecordedPositionsKeepsFollowWhenRecordingBegins() {
        val view = SurveyViewport(); view.follow(null)
        view.receive(listOf(MapPosition(40.0, -73.0)), MapPosition(41.0, -74.0))
        assertEquals(SurveyViewport.Mode.FOLLOW_VESSEL, view.mode)
        assertEquals(41.0, view.latitude, 0.0)
    }
    @Test fun legacyDiagnosticAutoFitContinuesUntilManualMovement() {
        val view = SurveyViewport()
        val first = MapPosition(40.0, -73.0); val second = MapPosition(41.0, -74.0)
        view.receiveLegacy(listOf(first), first)
        view.receiveLegacy(listOf(first, second), second)
        assertEquals(40.5, view.latitude, 0.0)
        view.pan(0.1, 0.1); val manual = view.latitude
        view.receiveLegacy(listOf(first, second, MapPosition(42.0, -75.0)), null)
        assertEquals(manual, view.latitude, 0.0)
        view.follow(second); view.receiveLegacy(listOf(first), second)
        assertEquals(41.0, view.latitude, 0.0)
    }
    @Test fun newSessionFramesAutomaticallyUnlessOperatorOwnsNavigation() {
        val view = SurveyViewport()
        view.receive(listOf(MapPosition(40.0, -73.0)), null)
        view.beginSurvey(); view.receive(listOf(MapPosition(41.0, -74.0)), null)
        assertEquals(41.0, view.latitude, 0.0)
        view.pan(0.1, 0.1); val manualLatitude = view.latitude
        view.beginSurvey(); view.receive(listOf(MapPosition(42.0, -75.0)), null)
        assertEquals(manualLatitude, view.latitude, 0.0)
        view.follow(MapPosition(43.0, -76.0)); view.beginSurvey()
        view.receive(listOf(MapPosition(44.0, -77.0)), MapPosition(45.0, -78.0))
        assertEquals(SurveyViewport.Mode.FOLLOW_VESSEL, view.mode)
        assertEquals(45.0, view.latitude, 0.0)
    }
    @Test fun longitudeWrapUsesSmallExtentAndPolarPointsRemainFinite() {
        val view = SurveyViewport()
        view.fit(listOf(MapPosition(10.0, 179.9), MapPosition(10.1, -179.9)))
        assertTrue(abs(abs(view.longitude) - 180) < 1e-8)
        assertTrue(view.span < 1.0)
        assertTrue(abs(view.x(MapPosition(10.0, 179.9))) < 0.2)
        view.fit(listOf(MapPosition(90.0, 180.0), MapPosition(90.0, -180.0)))
        assertTrue(view.span.isFinite() && view.longitudeScale > 0)
    }
}

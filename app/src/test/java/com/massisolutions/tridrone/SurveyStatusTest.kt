package com.massisolutions.tridrone

import org.junit.Assert.*
import org.junit.Test

class SurveyStatusTest {
    @Test fun precisionRequiresLiveMatchedReceiverData() {
        val fixed = receiverPrecision(true, 90, 50, 4, 0.012, 0.021)
        assertEquals(PrecisionState.VALID, fixed.state)
        assertEquals("H: 0.04   V: 0.07 US survey ft", fixed.text)
        assertEquals(PrecisionState.DEGRADED, receiverPrecision(true, 90, 50, 5, 0.012, 0.021).state)
        assertEquals(PrecisionState.UNAVAILABLE, receiverPrecision(true, null, null, null, null, null).state)
        assertEquals(PrecisionState.UNAVAILABLE, receiverPrecision(true, 90, null, 4, 0.012, 0.021).state)
        assertEquals(PrecisionState.UNAVAILABLE, receiverPrecision(true, 90, 50, 4, Double.NaN, 0.021).state)
        assertEquals(PrecisionState.UNAVAILABLE, receiverPrecision(false, 90, 50, 4, 0.012, 0.021).state)
        assertEquals(PrecisionState.STALE, receiverPrecision(true, 90, 3001, 4, 0.012, 0.021).state)
        assertEquals(PrecisionState.STALE, receiverPrecision(true, 3001, 50, 4, 0.012, 0.021, 3000).state)
    }

    @Test fun hydroLiteDepthIsShownAndExportedAsReportedDespiteWaterlineSetting() {
        val depth = HydroLiteDecoder.decode("1 0.93 0 0 0 11.0 100 0", 10, 1000)
        val fresh = surveyDepth(depth.status(true, 1100), true, depth.meters)
        val stale = surveyDepth(depth.status(true, 3_000_001_001), true, depth.meters)
        val disconnected = surveyDepth(depth.status(false, 1100), false, depth.meters)
        assertEquals("WATER DEPTH  3.05 US survey ft", fresh.title)
        assertNull(fresh.status)
        assertEquals("Stale", stale.status)
        assertEquals("WATER DEPTH  —", stale.title)
        assertEquals("Disconnected", disconnected.status)
        assertEquals("WATER DEPTH  —", disconnected.title)

        val equipment = EquipmentSettings(waterlineOffsetUsft = 1.22)
        val cells = HydroCsv.row(depth, null, equipment, "UNKNOWN").trimEnd().split(',')
        assertEquals(HydroCsv.columns.size, cells.size)
        assertEquals(SurveyUnits.feet(0.93), cells[HydroCsv.columns.indexOf("depth_usft")].toDouble(), 0.0)
    }

    @Test fun coveragePreviewRetainsEarlierPointsWhenBounded() {
        val trace = SurveyMapTrace(10)
        for (i in 0..99) trace.add(i.toDouble() to 0.0)
        val snapshot = trace.snapshot()
        assertTrue(snapshot.size <= 10)
        assertEquals(0.0 to 0.0, snapshot.first())
        assertEquals(99.0 to 0.0, snapshot.last())
        assertEquals(snapshot.sortedBy { it.first }, snapshot)
    }
}

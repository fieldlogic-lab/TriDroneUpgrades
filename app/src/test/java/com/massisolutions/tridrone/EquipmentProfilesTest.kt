package com.massisolutions.tridrone

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class EquipmentProfilesTest {
    @Test fun geometryAndNoDoubleDraft() {
        val g = VerticalGeometry(2.35, 0.42, "ANTENNA_REFERENCE_POINT", true)
        assertTrue(g.usable)
        assertEquals(1.93, g.gpsAboveWaterlineUsft()!!, 1e-9)
        assertEquals(0.42 + 3.0 * SurveyUnits.US_FEET_PER_METER,
            g.bottomBelowWaterlineUsft(3.0)!!, 1e-9)
        assertEquals(50.0 - 2.35 - 3.0 * SurveyUnits.US_FEET_PER_METER,
            g.bottomElevationUsft(50.0, 3.0)!!, 1e-9)
        assertNull(g.bottomElevationUsft(null, 3.0))
        assertNull(VerticalGeometry(2.35, 0.42).bottomBelowWaterlineUsft(3.0))
    }

    @Test fun profilesPersistAndSwitchWithoutLeakingGeometry() {
        val dir = Files.createTempDirectory("equipment-profiles").toFile()
        var recording = false
        val store = EquipmentProfiles(dir) { recording }
        store.seedDefaults()
        assertEquals(2, store.all().size)
        val drone = store.all().first { it.id == "tridrone_hydrolite" }
        store.save(drone.copy(geometry = VerticalGeometry(2.35, 0.42, "ANTENNA_REFERENCE_POINT", true)))
        store.select("pole_cee_line")
        assertNull(store.active()!!.geometry.gpsToSounderUsft)
        store.select("tridrone_hydrolite")
        assertEquals(2.35, EquipmentProfiles(dir) { recording }.active()!!.geometry.gpsToSounderUsft!!, 0.0)
        recording = true
        try { store.select("pole_cee_line"); fail("Switched during recording") } catch (_: IllegalStateException) {}
        try { store.save(drone); fail("Edited during recording") } catch (_: IllegalStateException) {}
    }

    @Test fun rejectsUnconfirmedOffsetsAndInvalidDepth() {
        val g = VerticalGeometry(2.35, 0.42, "ANTENNA_REFERENCE_POINT", false)
        assertNull(g.bottomBelowWaterlineUsft(1.0))
        assertNull(g.bottomElevationUsft(10.0, 1.0))
        val verified = g.copy(verified = true)
        assertNull(verified.bottomBelowWaterlineUsft(-1.0))
        assertNull(verified.bottomBelowWaterlineUsft(Double.NaN))
        assertFalse(g.copy(sounderBelowWaterlineUsft = -0.5).usable)
    }
}

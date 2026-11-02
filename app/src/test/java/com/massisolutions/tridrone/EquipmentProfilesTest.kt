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
        val verified = g.copy(verified = true)
        assertNull(verified.bottomBelowWaterlineUsft(-1.0))
        assertNull(verified.bottomBelowWaterlineUsft(Double.NaN))
        assertFalse(g.copy(sounderBelowWaterlineUsft = -0.5).usable)
    }

    @Test fun connectionsAndCalibrationRemainIndependentAcrossReloads() {
        val dir = Files.createTempDirectory("profile-connections").toFile()
        val store = EquipmentProfiles(dir) { false }
        store.seedDefaults()
        val drone = store.active()!!.copy(connection = ProfileConnection("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02",
            "TCP", "192.168.43.20", 9000, 4800), calibration = DepthCalibration("WATERLINE", 1.02, -0.1, true),
            pairToleranceMillis = 450)
        store.save(drone)
        store.select("pole_cee_line")
        val cee = store.active()!!
        assertEquals(9600, cee.connection.baud)
        assertEquals("", cee.connection.sounderAddress)
        assertFalse(cee.calibration.confirmed)
        store.save(cee.copy(connection = cee.connection.copy(sounderAddress = "00:11:22:33:44:55")))
        store.select(drone.id)
        assertEquals(drone, EquipmentProfiles(dir) { false }.active())
    }

    @Test fun portableImportIsAdditiveUnselectedAndRequiresPhysicalReconfirmation() {
        val dir = Files.createTempDirectory("profile-portable").toFile()
        var recording = false
        val store = EquipmentProfiles(dir) { recording }
        store.seedDefaults()
        val original = store.active()!!.copy(geometry = VerticalGeometry(4.0, 0.5, "ANTENNA_REFERENCE_POINT", true),
            calibration = DepthCalibration("ACOUSTIC_CENTER", 1.01, 0.02, true), checkedUtcMs = 1234)
        store.save(original)
        val transfer = store.exportProfiles()
        val imported = store.importProfiles(transfer)
        assertEquals(2, imported.size)
        assertEquals(original, store.active())
        assertEquals(4, store.all().size)
        val restored = imported.first { it.model == original.model }
        assertNotEquals(original.id, restored.id)
        assertEquals(original.geometry.gpsToSounderUsft, restored.geometry.gpsToSounderUsft)
        assertFalse(restored.geometry.verified)
        assertFalse(restored.calibration.confirmed)
        assertEquals(0L, restored.checkedUtcMs)
        recording = true
        try { store.importProfiles(transfer); fail("Imported while recording") } catch (_: IllegalStateException) {}
        recording = false
        try { store.importProfiles("schema_version=99"); fail("Unknown schema accepted") } catch (_: IllegalArgumentException) {}
        try {
            store.importProfiles(transfer.replace("depth_scale=1.01", "depth_scale=corrupt"))
            fail("Malformed calibration accepted")
        } catch (_: IllegalArgumentException) {}
        assertEquals(4, store.all().size)
    }

    @Test fun sourceBindingIsIndependentOfConnectionOrderButRejectsChangedGeometryOrSelectedDevice() {
        val p = SounderProfile("drone", "Drone", "HYDROLITE_PLUS", "BT", VerticalGeometry(4.0, 0.5))
        val changedGnss = p.copy(connection = p.connection.copy(gnssAddress = "AA:BB:CC:DD:EE:01"))
        assertTrue(ProfileBinding.sounderMatches(changedGnss, p))
        assertFalse(ProfileBinding.gnssMatches(changedGnss, p))
        val changedSounder = p.copy(connection = p.connection.copy(sounderAddress = "AA:BB:CC:DD:EE:02"))
        assertTrue(ProfileBinding.gnssMatches(changedSounder, p))
        assertFalse(ProfileBinding.sounderMatches(changedSounder, p))
        assertFalse(ProfileBinding.sounderMatches(p.copy(id = "cee"), p))
        assertFalse(ProfileBinding.gnssMatches(p.copy(geometry = p.geometry.copy(gpsToSounderUsft = 5.0)), p))
        assertFalse(ProfileBinding.sounderMatches(p, null))
        assertTrue(ProfileBinding.sounderMatches(null, null))
    }
}

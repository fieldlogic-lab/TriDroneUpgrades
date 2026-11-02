package com.massisolutions.tridrone

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ProfileDepthCorrectionTest {
    private val raw = HydroLiteDecoder.decode("1 0.93 0 0 0 11.0 100 0", 2000, 2_000_000_000L)
    private val profile = SounderProfile("drone", "Drone, one", "HYDROLITE_PLUS", "BT",
        VerticalGeometry(4.0, 0.5, "ANTENNA_REFERENCE_POINT", true),
        calibration = DepthCalibration("ACOUSTIC_CENTER", 1.0, 0.0, true))

    @Test fun repeatedDeviceTimeCannotOverwriteHistoricalCsvOrRawFiles() {
        val dir = Files.createTempDirectory("historical-recordings").toFile()
        val csv = java.io.File(dir, "hydro_1000.csv").apply { writeText("original survey\n") }
        val raw = java.io.File(dir, "hydro_1000_1.sounder.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val reserved = HydroSessionFiles.reserveCsv(dir, 1000)
        assertEquals("hydro_1000_2.csv", reserved.name)
        assertEquals("original survey\n", csv.readText())
        assertArrayEquals(byteArrayOf(1, 2, 3), raw.readBytes())
        assertNotEquals(reserved, HydroSessionFiles.reserveCsv(dir, 1000))
    }

    @Test fun acousticDepthAddsDraftOnceAndAntennaElevationDoesNotAddDraft() {
        val result = ProfileDepthCorrection.calculate(raw, profile)
        assertEquals(3.051175, result.acousticDepthUsft!!, 1e-9)
        assertEquals(3.551175, result.belowWaterlineUsft!!, 1e-9)
        val height = VerticalReference(100.0, "ANTENNA_REFERENCE_POINT", "EPSG:6360", true, "control record")
        assertEquals(92.948825, ProfileDepthCorrection.bottomElevation(height, raw, profile)!!, 1e-9)
        assertEquals(ProfileDepthCorrection.bottomElevation(height, raw, profile)!!,
            ProfileDepthCorrection.bottomElevation(height, raw, profile.copy(geometry = profile.geometry.copy(sounderBelowWaterlineUsft = 1.5)))!!, 0.0)
    }

    @Test fun waterlineOutputRemovesExistingDraftBeforeApplyingCalibration() {
        val p = profile.copy(calibration = DepthCalibration("WATERLINE", 1.02, 0.1, true))
        val result = ProfileDepthCorrection.calculate(raw, p)
        assertEquals((3.051175 - 0.5) * 1.02 + 0.1, result.acousticDepthUsft!!, 1e-9)
        assertEquals(result.acousticDepthUsft!! + 0.5, result.belowWaterlineUsft!!, 1e-9)
        val identity = ProfileDepthCorrection.calculate(raw, p.copy(calibration = p.calibration.copy(scale = 1.0, offsetUsft = 0.0)))
        assertEquals(3.051175, identity.belowWaterlineUsft!!, 1e-9)
    }

    @Test fun unknownReferenceInvalidRangeUnconfirmedCalibrationAndUnverifiedControlAreWithheld() {
        assertNull(ProfileDepthCorrection.calculate(raw, profile.copy(calibration = profile.calibration.copy(confirmed = false))).acousticDepthUsft)
        assertNull(ProfileDepthCorrection.calculate(raw, profile.copy(calibration = profile.calibration.copy(depthReference = "UNKNOWN"))).belowWaterlineUsft)
        assertNull(ProfileDepthCorrection.calculate(raw, profile.copy(calibration = profile.calibration.copy(offsetUsft = -10.0))).acousticDepthUsft)
        assertNull(ProfileDepthCorrection.calculate(raw.copy(meters = Double.NaN), profile).acousticDepthUsft)
        val height = VerticalReference(100.0, "ANTENNA_REFERENCE_POINT", "EPSG:6360", true, "control")
        listOf(null, height.copy(datum = "ELLIPSOID"), height.copy(transformationVerified = false),
            height.copy(provenance = ""), height.copy(antennaReference = "RECEIVER_OUTPUT_REFERENCE")).forEach {
            assertNull(ProfileDepthCorrection.bottomElevation(it, raw, profile))
        }
    }

    @Test fun ceeCannotDecodeHydroliteShapedDataEvenWithConfirmedCalibration() {
        val cee = profile.copy(model = "CEE_LINE")
        val sample = ProfileDepthDecoder.decode(cee, raw.raw, raw.utcMillis, raw.elapsedNanos)
        assertFalse(sample.valid)
        assertNull(sample.meters)
        assertEquals(raw.raw, sample.raw)
        assertEquals("UNSUPPORTED_PROTOCOL", sample.validity)
        assertNull(ProfileDepthCorrection.calculate(raw, cee).acousticDepthUsft)
    }

    @Test fun profileSnapshotSurvivesLaterEditsAndFloatRowsRetainRawFixWithoutInventingNavd88() {
        val dir = Files.createTempDirectory("snapshot-profile").toFile()
        val store = EquipmentProfiles(dir) { false }
        store.save(profile)
        val snapshot = store.active()!!
        store.save(profile.copy(geometry = profile.geometry.copy(sounderBelowWaterlineUsft = 2.0)))
        val floatGnss = HydroGnss("GGA FLOAT", 1500, 1_500_000_000L, 40.8, -73.05, 5,
            10.0, null, null, "120000")
        val state = HydroGnssState().apply { connection(true); accept(floatGnss) }
        assertNotNull(state.paired(raw, 1000))
        val csv = HydroCsv.row(raw, floatGnss, snapshot.equipmentSettings(), "EPSG:6318", snapshot)
        // A comma in the profile name is CSV quoted and must not shift the schema.
        assertTrue(csv.contains("\"Drone, one\""))
        assertTrue(csv.contains("RTK_NOT_FIXED"))
        assertTrue(csv.contains("WITHHELD_NO_VERIFIED_VERTICAL_TRANSFORM"))
        assertEquals(0.5, snapshot.geometry.sounderBelowWaterlineUsft!!, 0.0)
        assertEquals(3.551175, ProfileDepthCorrection.calculate(raw, snapshot).belowWaterlineUsft!!, 1e-9)
    }
}

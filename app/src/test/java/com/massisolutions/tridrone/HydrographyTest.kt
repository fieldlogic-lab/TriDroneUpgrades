package com.massisolutions.tridrone

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class HydrographyTest {
    // Only captured observation supplied in issue #7. Other strings below are deliberately invalid inputs.
    private val poolRecord = "1 0.93 0 0 0 11.0 100 0"
    private fun depth(raw: String = poolRecord, nanos: Long = 2_000_000_000L) = HydroLiteDecoder.decode(raw, 2000, nanos)
    private fun gnss(nanos: Long = 1_500_000_000L) = HydroGnss("raw,\"GGA\"", 1500, nanos, 40.0, -73.0, 4,
        20.0, 0.01, 0.02, "120000.00")

    @Test fun poolRecordPreservesAllFieldsAndExactUnits() {
        val d = depth()
        assertTrue(d.valid)
        assertEquals(poolRecord, d.raw)
        assertEquals(0.93, d.meters!!, 0.0)
        assertEquals(3.051175, SurveyUnits.feet(d.meters!!), 0.000000001)
        assertEquals(3937.0 / 1200.0, SurveyUnits.feet(1.0), 0.0)
        assertTrue(d.diagnostic.contains("11.0"))
        assertEquals(2000L, d.utcMillis)
    }

    @Test fun zeroMalformedExtremeAndStaleCannotAppearValid() {
        assertEquals("INVALID_NO_BOTTOM", depth("1 0 0 0 0 11.0 100 0").validity)
        listOf("", "1 nope 0 0 0 11 100 0", "1 0.93", "1 NaN 0 0 0 11 100 0", "1 0.93 x 0 0 11 100 0").forEach {
            assertEquals("INVALID_MALFORMED", depth(it).validity)
        }
        assertEquals("INVALID_RANGE", depth("1 9999 0 0 0 11 100 0").validity)
        assertEquals("INVALID_RANGE", depth("1 -1 0 0 0 11 100 0").validity)
        assertEquals("NO_READING_STALE", depth().status(true, 5_000_000_001L))
        assertEquals("NO_READING_DISCONNECTED", depth().status(false, 2_000_000_000L))
        assertEquals("NO_READING_STALE", depth().status(true, 1L))
    }

    @Test fun fragmentedReplayOfSuppliedPoolRecord() {
        val framer = DepthStreamDecoder()
        assertTrue(framer.accept(poolRecord.substring(0, 7).toByteArray()).isEmpty())
        val frames = framer.accept((poolRecord.substring(7) + "\r\n").toByteArray())
        assertEquals(listOf(poolRecord), frames)
        assertTrue(depth(frames.single()).valid)
    }

    @Test fun gnssDisconnectReconnectRequiresNewEpochAndPairingNeverInterpolates() {
        val state = HydroGnssState()
        state.connection(true); state.accept(gnss())
        assertNotNull(state.paired(depth(), 1000))
        assertNull(state.paired(depth(), 499))
        assertNull(state.paired(depth(nanos = 1L), 1000))
        state.connection(false)
        assertNull(state.paired(depth(), 1000))
        state.connection(true)
        assertNull(state.paired(depth(), 1000))
        state.accept(gnss())
        assertNotNull(state.paired(depth(), 1000))
        state.accept(gnss().copy(quality = 0))
        assertNull(state.paired(depth(), 1000))
    }

    @Test fun equipmentPersistsAcrossInstancesAndDefaultsUnset() {
        val folder = Files.createTempDirectory("hydro-settings").toFile()
        try {
            val file = java.io.File(folder, "equipment.properties")
            assertFalse(EquipmentSettings.load(file).valid)
            val saved = EquipmentSettings(4.25, "ANTENNA_REFERENCE_POINT", true, 800)
            saved.save(file)
            assertEquals(saved, EquipmentSettings.load(file))
            assertTrue(EquipmentSettings.load(file).valid)
            assertFalse(saved.copy(offsetUsft = -1.0).valid)
            assertFalse(saved.copy(offsetUsft = Double.NaN).valid)
            assertFalse(saved.copy(antennaReference = "UNSET").valid)
            assertFalse(saved.copy(confirmed = false).valid)
        } finally { folder.deleteRecursively() }
    }

    @Test fun bottomElevationRequiresVerifiedMatchingReferenceAndAppliesOffsetOnlyOnce() {
        val equipment = EquipmentSettings(4.0, "ANTENNA_REFERENCE_POINT", true)
        val height = VerticalReference(100.0, "ANTENNA_REFERENCE_POINT", "EPSG:6360", true, "independent control")
        assertEquals(92.948825, BottomElevation.calculate(height, equipment, depth())!!, 1e-9)
        assertNull(BottomElevation.calculate(null, equipment, depth()))
        assertNull(BottomElevation.calculate(height.copy(datum = "ELLIPSOID"), equipment, depth()))
        assertNull(BottomElevation.calculate(height.copy(transformationVerified = false), equipment, depth()))
        assertNull(BottomElevation.calculate(height.copy(antennaReference = "RECEIVER_OUTPUT_REFERENCE"), equipment, depth()))
        assertNull(BottomElevation.calculate(height, equipment.copy(confirmed = false), depth()))
        assertNull(BottomElevation.calculate(height, equipment, depth("1 0 0 0 0 11 100 0")))
    }

    @Test fun csvRetainsRawValuesPrecisionConfigurationAndWithheldElevation() {
        val d = depth()
        val row = parseCsv(HydroCsv.row(d, gnss(), EquipmentSettings(4.0, "ANTENNA_REFERENCE_POINT", true), "UNKNOWN"))
        assertEquals(HydroCsv.columns.size, row.size)
        val values = HydroCsv.columns.zip(row).toMap()
        assertEquals(poolRecord, values["raw_depth"])
        assertEquals("raw,\"GGA\"", values["raw_gnss"])
        assertEquals("0.93", values["raw_depth_m"])
        assertEquals(3.051175, values["depth_usft"]!!.toDouble(), 1e-9)
        assertEquals(SurveyUnits.feet(0.01), values["h_precision_usft"]!!.toDouble(), 0.0)
        assertEquals("", values["bottom_navd88_usft"])
        assertEquals("4.0", values["vertical_offset_usft"])
        assertEquals("500.0", values["pair_delta_ms"])
        assertEquals("EPSG:6539", values["horizontal_crs"])
        val missing = HydroCsv.columns.zip(parseCsv(HydroCsv.row(d, null, EquipmentSettings(), "UNKNOWN"))).toMap()
        assertEquals("", missing["latitude_deg"])
        assertEquals("MISSING_OR_STALE_GNSS", missing["pairing_status"])
    }

    @Test fun previousTransportCannotDisconnectOrInjectIntoNewOwner() {
        val state = HydroGnssState()
        state.claim("bluetooth-old")
        state.connection("bluetooth-old", true)
        state.accept("bluetooth-old", gnss())
        state.claim("wifi-new")
        state.connection("wifi-new", true)
        state.accept("wifi-new", gnss().copy(raw = "new owner"))
        state.connection("bluetooth-old", false)
        state.accept("bluetooth-old", gnss().copy(raw = "obsolete epoch"))
        assertTrue(state.connected)
        assertEquals("new owner", state.paired(depth(), 1000)!!.raw)
        assertFalse(state.owns("bluetooth-old"))
        state.connection("wifi-new", false)
        assertNull(state.paired(depth(), 1000))
    }

    @Test fun confirmedNad83ProjectsProvisionallyWithIndependentProjReference() {
        // Independent libproj.so.25, EPSG database: normalized EPSG:6318 -> EPSG:6539.
        // This checks projection arithmetic, not receiver source datum or field control.
        val g = gnss().copy(latitude = 40.8, longitude = -73.05)
        fun cells(source: String, fix: HydroGnss? = g) = HydroCsv.columns.zip(
            parseCsv(HydroCsv.row(depth(), fix, EquipmentSettings(), source))).toMap()
        val projected = cells("EPSG:6318")
        assertEquals(1247266.508899992, projected["easting_usft"]!!.toDouble(), 0.001)
        assertEquals(232164.79807935783, projected["northing_usft"]!!.toDouble(), 0.001)
        assertEquals("PROVISIONAL_CONTROL_CHECK_REQUIRED", projected["horizontal_status"])
        assertEquals("", cells("UNKNOWN")["easting_usft"])
        assertEquals("SOURCE_DATUM_UNVERIFIED", cells("UNKNOWN")["horizontal_status"])
        assertEquals("", cells("EPSG:6318", g.copy(quality = 5))["easting_usft"])
        assertEquals("RTK_NOT_FIXED", cells("EPSG:6318", g.copy(quality = 5))["horizontal_status"])
        assertEquals("", cells("EPSG:6318", g.copy(latitude = 0.0))["northing_usft"])
        assertEquals("OUTSIDE_AREA_OR_INVALID", cells("EPSG:6318", g.copy(latitude = 0.0))["horizontal_status"])
    }

    private fun parseCsv(row: String): List<String> {
        val output = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        while (i < row.trimEnd('\n').length) {
            val c = row[i]
            when {
                c == '"' && quoted && row.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { output.add(cell.toString()); cell.setLength(0) }
                else -> cell.append(c)
            }
            i++
        }
        output.add(cell.toString()); return output
    }
}

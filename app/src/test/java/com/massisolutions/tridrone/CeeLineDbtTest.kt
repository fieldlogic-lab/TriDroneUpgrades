package com.massisolutions.tridrone

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CeeLineDbtTest {
    private fun sentence(body: String): String {
        val sum = body.fold(0) { a, c -> a xor c.code }
        return "$" + body + "*" + "%02X".format(sum)
    }
    private fun decode(body: String) = CeeLineDbt.decode(sentence(body), 100, 2_000_000_000)
    @Test fun checksumAndZeroExample() {
        assertEquals("INVALID_NO_BOTTOM", CeeLineDbt.decode("\$SDDBT,0.00,f,0.00,M,0.00,F*36", 1, 1).validity)
        assertEquals("INVALID_CHECKSUM", CeeLineDbt.decode("\$SDDBT,4.20,f,,M,,F*00", 1, 1).validity)
        assertEquals("INVALID_SENTENCE", decode("GPGGA,4.20,f,,M,,F").validity)
    }
    @Test fun mandatoryWaterDepthExampleAndIndependentVerticalMeasurement() {
        val depth = decode("SDDBT,4.20,f,,M,,F")
        assertTrue(depth.valid)
        val water = WaterDepth.meters(depth.meters, 0.70 * 0.3048)!!
        assertEquals(4.90, water / 0.3048, 1e-12)
        val geometry = EquipmentSettings(8.0, "ANTENNA_REFERENCE_POINT", true, 1000, 0.70 * 0.3048 * SurveyUnits.US_FEET_PER_METER)
        val height = VerticalReference(100.0, "ANTENNA_REFERENCE_POINT", "EPSG:6360", true, "verified control")
        assertEquals(100 - 8 - SurveyUnits.feet(4.20 * 0.3048), BottomElevation.calculate(height, geometry, depth)!!, 1e-10)
        assertEquals(0.0, geometry.copy(waterlineOffsetUsft = 0.0).waterlineOffsetUsft!!, 0.0)
        assertFalse(geometry.copy(waterlineOffsetUsft = null).ceeValid)
    }
    @Test fun conversionsMalformedAndConflictingFields() {
        assertEquals(1.8288, decode("SDDBT,,f,,M,1,F").meters!!, 1e-12)
        assertEquals(1.0, decode("SDDBT,,f,1,M,,F").meters!!, 0.0)
        assertTrue(decode("SDDBT,3.28,f,1.00,M,0.55,F").valid)
        listOf("SDDBT,NaN,f,,M,,F", "SDDBT,-1,f,,M,,F", "SDDBT,1,f,7,M,,F", "SDDBT,,f,,M,,F", "SDDBT,2,x,,M,,F").forEach { assertFalse(decode(it).valid) }
        assertNull(WaterDepth.meters(Double.MAX_VALUE, Double.MAX_VALUE)); assertNull(WaterDepth.meters(Double.MAX_VALUE / 2, 0.0)); assertNull(WaterDepth.meters(0.0, 0.5)); assertNull(WaterDepth.meters(1.0, -0.1))
    }
    @Test fun fragmentsAndResetNeverJoinAcrossDisconnect() {
        val framer = DepthStreamDecoder()
        val message = sentence("SDDBT,4.20,f,,M,,F")
        assertTrue(framer.accept(message.take(8).toByteArray()).isEmpty())
        assertEquals(message, framer.accept((message.drop(8) + "\r\n").toByteArray()).single())
        framer.accept(message.take(8).toByteArray()); framer.reset()
        assertFalse(CeeLineDbt.decode(framer.accept((message.drop(8) + "\n").toByteArray()).single(), 1, 1).valid)
        assertTrue(CeeLineDbt.decode(framer.accept((message + "\n").toByteArray()).single(), 1, 1).valid)
    }
    @Test fun fixedAndFloatPairingStalenessAndQuality() {
        val state = HydroGnssState(); state.connection(true)
        val d = decode("SDDBT,4.20,f,,M,,F")
        for (quality in listOf(4, 5)) {
            state.accept(HydroGnss("GGA", 100, 1_500_000_000, 40.0, -73.0, quality, null, null, null, "120000"))
            assertEquals(quality, state.paired(d, 1000)!!.quality)
        }
        assertNull(state.paired(d.copy(elapsedNanos = 5_000_000_001), 5000))
        state.connection(false); state.connection(true); assertNull(state.paired(d, 1000))
    }
    @Test fun exportRetainsAllFourLengthsAndWithholdsUnverifiedZ() {
        val d = decode("SDDBT,4.20,f,,M,,F")
        val equipment = EquipmentSettings(8.0, "ANTENNA_REFERENCE_POINT", true, 1000, SurveyUnits.feet(0.70 * 0.3048))
        val csv = HydroCsv.header + HydroCsv.row(d, null, equipment, "UNKNOWN")
        val parsed = HydroPnezd.parse(csv)
        val row = parsed[0].zip(parsed[1]).toMap()
        assertEquals(d.meters!!, row["raw_dbt_m"]!!.toDouble(), 0.0)
        assertEquals(4.90, row["water_depth_m"]!!.toDouble() / 0.3048, 1e-12)
        assertEquals("8.0", row["gps_to_transducer_usft"])
        assertEquals("", row["bottom_navd88_usft"])
        val dir = Files.createTempDirectory("cee-export").toFile()
        try { val input = java.io.File(dir, "in.csv"); input.writeText(csv); assertEquals(0, HydroPnezd.export(input, java.io.File(dir, "out.txt"))) } finally { dir.deleteRecursively() }
    }
}

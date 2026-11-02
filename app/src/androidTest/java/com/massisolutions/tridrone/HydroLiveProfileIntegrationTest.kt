package com.massisolutions.tridrone

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Synthetic regression inputs only. Run on the isolated emulator, never the field acquisition phone.
 * Files and preferences use a disposable namespace; no existing recordings are opened for writing.
 */
@RunWith(AndroidJUnit4::class)
class HydroLiveProfileIntegrationTest {
    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val namespace = "profile_integration_${UUID.randomUUID()}"
        val root = File(base.cacheDir, namespace).apply { check(mkdirs()) }
        private val preferenceNames = mutableSetOf<String>()
        override fun getFilesDir() = root
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "${namespace}_$name"
            preferenceNames.add(isolated)
            return baseContext.getSharedPreferences(isolated, mode)
        }
        fun dispose() {
            preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
            root.deleteRecursively()
        }
    }

    private inline fun isolated(block: (IsolatedContext, EquipmentProfiles) -> Unit) {
        // Refuse to interrupt any pre-existing acquisition, including if invoked on the wrong device.
        assertFalse("Integration tests require an idle isolated emulator", HydroLive.recording)
        val c = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            HydroLive.equipmentChanged()
            block(c, EquipmentProfiles(c.filesDir) { HydroLive.recording }.apply { seedDefaults() })
        } finally {
            HydroLive.stop()
            HydroLive.equipmentChanged()
            c.dispose()
        }
    }

    private fun sentence(quality: Int): String {
        val body = "GPGGA,123519,4048.0000,N,07303.0000,W,$quality,12,0.7,10.2,M,-30.0,M,,"
        return "$" + body + "*" + body.fold(0) { checksum, ch -> checksum xor ch.code }
            .toString(16).uppercase().padStart(2, '0')
    }

    private fun bind(profile: SounderProfile) {
        HydroLive.claimGnss("integration-gnss", profile)
        HydroLive.gnssConnection("integration-gnss", true)
        HydroLive.claimSounder("integration-sounder", profile)
        HydroLive.sounderConnection("integration-sounder", true)
    }

    private fun pairedFrame(quality: Int, raw: String = "1 3.0 0 0 0 11.0 100 0") {
        val utc = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        HydroLive.gnssSentence("integration-gnss", sentence(quality), utc, elapsed)
        HydroLive.frame("integration-sounder", raw, utc, elapsed)
    }

    private fun expectLocked(action: () -> Unit) {
        try { action(); fail("Recording must lock profile changes") } catch (_: IllegalStateException) {}
    }

    private fun cells(line: String): List<String> {
        val result = mutableListOf<String>()
        val value = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { value.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { result.add(value.toString()); value.setLength(0) }
                else -> value.append(ch)
            }
            i++
        }
        check(!quoted)
        result.add(value.toString())
        return result
    }

    @Test fun floatAndFixRecordingFreezesSelectedProfileAndPreservesOriginals() = isolated { c, store ->
        val profile = store.active()!!.copy(
            geometry = VerticalGeometry(2.35, 0.42, "ANTENNA_REFERENCE_POINT", true),
            checkedUtcMs = 123456L,
            connection = ProfileConnection("00:11:22:33:44:55", "00:11:22:33:44:66", "TCP", "192.0.2.1", 9001, 9600),
            calibration = DepthCalibration("ACOUSTIC_CENTER", 1.1, 0.05, true))
        store.save(profile)
        val oldCsv = File(c.filesDir, "hydro_surveys/historical.csv").apply {
            parentFile!!.mkdirs(); writeText("historical,original,observations\nnever,recalculate,this\n")
        }
        val oldMetadata = File(c.filesDir, "hydro_surveys/historical.metadata.json").apply { writeText("{\"original\":true}") }
        val oldRaw = File(c.filesDir, "hydro_surveys/historical.sounder.bin").apply { writeBytes(byteArrayOf(0, -1, 5, 13, 10)) }
        val history = listOf(oldCsv, oldMetadata, oldRaw).associateWith { it.readBytes() }
        val legacy = HydroLive.equipmentFile(c).apply { writeText("offset_usft=99\nconfirmed=true\n") }
        val legacyBytes = legacy.readBytes()
        bind(profile)
        pairedFrame(5)
        val csv = HydroLive.start(c) // FLOAT must retain acquisition; no fixed-only collection gate.
        val base = csv.nameWithoutExtension
        val metadataFile = File(csv.parentFile, "$base.metadata.json")
        val metadataBytes = metadataFile.readBytes()
        val metadata = JSONObject(String(metadataBytes, Charsets.UTF_8))
        assertEquals(profile.id, metadata.getString("equipment_profile_id"))
        assertTrue(metadata.getBoolean("profile_geometry_applied_to_recording"))
        assertEquals(2.35, metadata.getDouble("gps_to_sounder_center_usft"), 0.0)
        assertEquals(0.42, metadata.getDouble("sounder_below_waterline_usft"), 0.0)
        assertEquals("192.0.2.1", metadata.getString("gnss_host"))
        assertEquals("WITHHELD_NO_VERIFIED_VERTICAL_TRANSFORM", metadata.getString("vertical_status"))
        expectLocked { store.save(profile.copy(geometry = profile.geometry.copy(gpsToSounderUsft = 99.0))) }
        expectLocked { store.select("pole_cee_line") }
        expectLocked { HydroLive.equipmentChanged() }
        val raw = "1 3.0 0 0 0 11.0 100 0\r\n".toByteArray(Charsets.US_ASCII)
        HydroLive.rawDepth("integration-sounder", raw, raw.size)
        pairedFrame(5)
        pairedFrame(4)
        pairedFrame(5, "1 0 0 0 0 11.0 100 0")
        HydroLive.gnssConnection("integration-gnss", false)
        HydroLive.frame("integration-sounder", "1 3.0 0 0 0 11.0 100 0", System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos())
        assertEquals(4, HydroLive.eventCount)
        assertEquals(2, HydroLive.validCount)
        HydroLive.stop()
        val lines = csv.readLines()
        val header = cells(lines.first())
        val rows = lines.drop(1).map { header.zip(cells(it)).toMap() }
        assertEquals(4, rows.size)
        assertEquals("5", rows[0]["gnss_quality"])
        assertEquals("4", rows[1]["gnss_quality"])
        assertEquals(sentence(5), rows[0]["raw_gnss"])
        assertEquals("1 3.0 0 0 0 11.0 100 0", rows[0]["raw_depth"])
        assertEquals(3.0, rows[0]["raw_depth_m"]!!.toDouble(), 0.0)
        // Independent expected arithmetic: 3m = 9.8425ftUS; 9.8425*1.1+.05 = 10.87675.
        assertEquals(10.87675, rows[0]["corrected_acoustic_depth_usft"]!!.toDouble(), 1e-8)
        assertEquals(11.29675, rows[0]["bottom_below_waterline_usft"]!!.toDouble(), 1e-8)
        assertEquals("", rows[0]["bottom_navd88_usft"])
        assertEquals("INVALID_NO_BOTTOM", rows[2]["depth_validity"])
        assertEquals("MISSING_OR_STALE_GNSS", rows[3]["pairing_status"])
        assertArrayEquals(raw, File(csv.parentFile, "$base.sounder.bin").readBytes())
        assertTrue(File(csv.parentFile, "$base.gnss.csv").readText().contains(sentence(5)))
        val csvBytes = csv.readBytes()
        store.save(profile.copy(geometry = profile.geometry.copy(gpsToSounderUsft = 77.0)))
        store.select("pole_cee_line")
        assertArrayEquals(metadataBytes, metadataFile.readBytes())
        assertArrayEquals(csvBytes, csv.readBytes())
        assertArrayEquals(legacyBytes, legacy.readBytes())
        history.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
    }

    @Test fun changedOrUnsupportedProfileCannotStartOnOldSensorData() = isolated { c, store ->
        val original = store.active()!!
        bind(original)
        pairedFrame(5)
        store.save(original.copy(geometry = VerticalGeometry(2.0, 0.4, "ANTENNA_REFERENCE_POINT", true)))
        expectLocked { HydroLive.start(c) }
        assertFalse(HydroLive.recording)
        HydroLive.equipmentChanged()
        HydroLive.frame("integration-sounder", "1 3.0 0 0 0 11.0 100 0", System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos())
        assertNull(HydroLive.depth)
        store.select("pole_cee_line")
        val cee = store.active()!!
        bind(cee)
        pairedFrame(5)
        assertEquals("UNSUPPORTED_PROTOCOL", HydroLive.depth!!.validity)
        assertNull(HydroLive.depth!!.meters)
        expectLocked { HydroLive.start(c) }
        assertFalse(HydroLive.recording)
        assertTrue(File(c.filesDir, "hydro_surveys").listFiles().orEmpty().isEmpty())
    }
}

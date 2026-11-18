package com.massisolutions.tridrone

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProjectStoreTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val databaseName = "projects-${UUID.randomUUID()}.db"
    private val start = 1_780_000_000_000L
    private val equipment = EquipmentSettings(2.75, "ANTENNA_REFERENCE_POINT", true, 750)

    @Test
    fun threeCollectionsSurviveRestartAndExportWithEveryOriginalRow() {
        val originalColumns = listOf("utc_epoch_ms", "elapsed_realtime_ns", "gnss_received_utc_ms",
            "gnss_elapsed_realtime_ns", "gnss_receiver_utc", "latitude_deg", "longitude_deg", "easting_usft",
            "northing_usft", "source_crs", "gnss_quality", "h_precision_m", "v_precision_m", "h_precision_usft",
            "v_precision_usft", "raw_gga_altitude_m", "raw_gga_height_reference", "raw_depth_m", "depth_usft",
            "vertical_offset_usft", "antenna_reference", "offset_confirmed", "bottom_navd88_usft", "horizontal_crs",
            "vertical_target", "horizontal_status", "vertical_status", "depth_validity", "pairing_status",
            "pair_delta_ms", "protocol", "raw_depth", "raw_gnss", "precision_raw", "precision_received_utc_ms",
            "pair_tolerance_ms", "timestamp_basis")
        assertEquals(originalColumns, HydroCsv.columns)
        val expectedCounts = listOf(100, 150, 200)
        val initial = newStore()
        val project = initial.createProject("Project A", "Synthetic regression data")
        val expectedCsv = linkedMapOf<String, String>()
        val stems = mutableListOf<String>()
        expectedCounts.forEachIndexed { index, size ->
            val sessionStart = start + index * 1_000_000L
            val stem = "hydro_${sessionStart}_sample"
            val protocol = if (index == 1) CeeLineDbt.PROTOCOL else HydroLiteDecoder.PROTOCOL
            val configuration = if (index == 1) equipment.copy(waterlineOffsetUsft = 0.7) else equipment
            val metadata = JSONObject().put("synthetic", true).put("protocol", protocol)
                .put("vertical_offset_usft", equipment.offsetUsft)
                .put("antenna_reference", equipment.antennaReference)
                .put("offset_confirmed", equipment.confirmed)
                .put("pair_tolerance_ms", equipment.pairToleranceMillis)
                .put("vertical_status", "WITHHELD_NO_VERIFIED_VERTICAL_TRANSFORM")
            val collection = initial.startSession(project.id, sessionStart, metadata, stem)
            assertEquals("Collection 00${index + 1}", collection.name)
            val csv = buildString {
                append(HydroCsv.headerFor(protocol))
                repeat(size) { row ->
                    val (fixture, gnss) = sample(sessionStart, row)
                    val depth = if (protocol == CeeLineDbt.PROTOCOL) {
                        CeeLineDbt.decode(withChecksum("\$SDDBT,,f,4.2,M,,F"), fixture.utcMillis, fixture.elapsedNanos)
                    } else fixture
                    assertTrue(depth.valid)
                    val original = HydroCsv.row(depth, gnss, configuration, "UNKNOWN")
                    initial.append(collection.id, depth, gnss, original)
                    append(original)
                }
            }
            expectedCsv[collection.id] = csv
            stems.add(stem)
            val folder = File(app.filesDir, "hydro_surveys").apply { mkdirs() }
            File(folder, "$stem.sounder.bin").writeBytes(byteArrayOf(1, 2, 3, 4))
            File(folder, "$stem.gnss.csv").writeText("received_utc_ms,elapsed_realtime_ns,raw_sentence\n")
            initial.finishSession(collection.id, sessionStart + size * 1000L)
        }
        initial.close()

        val reopened = newStore()
        try {
            assertEquals(project.id, reopened.activeProject()?.id)
            assertEquals("Synthetic regression data", reopened.activeProject()?.description)
            val history = reopened.sessions(project.id)
            assertEquals(expectedCsv.keys.toList(), history.map { it.id })
            assertEquals(expectedCounts.map(Int::toLong), history.map { it.observationCount })
            assertEquals(expectedCounts.map(Int::toLong), history.map { it.validCount })
            assertTrue(history.all { it.status == CollectionStatus.COMPLETED && it.projectId == project.id })
            val positions = reopened.positionsForProject(project.id, limit = 450)
            assertEquals(450, positions.size)
            assertEquals(positions.sortedBy { it.utcMillis }, positions)
            assertEquals(expectedCounts.map(Int::toLong), history.map { reopened.positionsForSession(it.id).size.toLong() })

            for ((id, expected) in expectedCsv) {
                val individual = ByteArrayOutputStream()
                reopened.exportSession(id, individual)
                assertEquals(expected, individual.toString(Charsets.UTF_8.name()))
                assertTrue(individual.toString(Charsets.UTF_8.name()).contains("WITHHELD_NO_VERIFIED_VERTICAL_TRANSFORM"))
            }
            val zip = ByteArrayOutputStream()
            reopened.exportProject(project.id, zip)
            val archive = unzip(zip.toByteArray())
            val manifest = JSONObject(archive.getValue("manifest.json").decodeToString())
            assertEquals(1, manifest.getInt("schema_version"))
            assertEquals(project.id, manifest.getString("project_id"))
            assertEquals("combined.csv", manifest.getString("combined_csv"))
            assertEquals(2, manifest.getInt("combined_csv_schema_version"))
            assertTrue(manifest.getJSONObject("measurement_units").getString("raw_depth_m").startsWith("meters"))
            val sessions = manifest.getJSONArray("sessions")
            assertEquals(3, sessions.length())
            history.forEachIndexed { index, collection ->
                val entry = sessions.getJSONObject(index)
                assertEquals(collection.id, entry.getString("session_id"))
                assertEquals(expectedCounts[index], entry.getInt("observation_count"))
                assertEquals(if (index == 1) 2 else 1, entry.getInt("csv_schema_version"))
                assertTrue(entry.getJSONObject("acquisition_settings").getBoolean("synthetic"))
                assertEquals(2.75, entry.getJSONObject("acquisition_settings").getDouble("vertical_offset_usft"), 0.0)
                assertEquals(expectedCsv.getValue(collection.id), archive.getValue(entry.getString("csv")).decodeToString())
                assertTrue(archive.containsKey(entry.getString("metadata_file")))
                val prefix = "collections/${collection.id}/${stems[index]}"
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), archive.getValue("$prefix.sounder.bin"))
                assertTrue(archive.containsKey("$prefix.gnss.csv"))
            }

            val combined = archive.getValue("combined.csv").decodeToString().trimEnd('\n').lines()
            assertEquals(451, combined.size)
            assertEquals("project_id,session_id,observation_id," + HydroCsv.ceeHeader.trimEnd('\n'), combined.first())
            val rows = combined.drop(1).map { it.split(',', limit = 4) }
            assertTrue(rows.all { it[0] == project.id })
            assertEquals(450, rows.map { UUID.fromString(it[2]) }.toSet().size)
            val bySession = rows.groupBy { it[1] }
            for ((id, csv) in expectedCsv) {
                val padding = if (csv.startsWith(HydroCsv.ceeHeader)) "" else ",".repeat(6)
                assertEquals(csv.trimEnd('\n').lines().drop(1).map { it + padding },
                    bySession.getValue(id).map { it[3] })
            }
            assertEquals(expectedCounts.map(Int::toLong), reopened.sessions(project.id).map { it.observationCount })

            val projectDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
                ?: File(System.getProperty("user.dir"), "app")
            File(projectDir, "build/outputs/test-artifacts/sample-project.zip").apply {
                parentFile?.mkdirs()
                writeBytes(zip.toByteArray())
            }
        } finally {
            reopened.close()
        }
    }

    @Test
    fun restartMarksOnlyActiveCollectionInterruptedAndPreservesCommittedObservations() {
        val initial = newStore()
        val project = initial.createProject("Interrupted")
        val collection = initial.startSession(project.id, start, JSONObject())
        val (depth, gnss) = sample(start, 1)
        initial.append(collection.id, depth, gnss, HydroCsv.row(depth, gnss, equipment, "UNKNOWN"))
        initial.close()

        val reopened = newStore()
        try {
            val recovered = checkNotNull(reopened.session(collection.id))
            assertEquals(CollectionStatus.INTERRUPTED, recovered.status)
            assertEquals(1L, recovered.observationCount)
            assertEquals(depth.utcMillis, recovered.endedUtc)
            assertThrows(IllegalStateException::class.java) {
                reopened.append(collection.id, depth, gnss, HydroCsv.row(depth, gnss, equipment, "UNKNOWN"))
            }
            val next = reopened.startSession(project.id, start + 100_000, JSONObject())
            assertEquals(2L, next.number)
            assertNotEquals(collection.id, next.id)
            assertEquals(0L, next.observationCount)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun legacyFilesAndAuditAttachmentsArePreservedAcrossRepeatMigration() {
        val folder = File(app.filesDir, "hydro_surveys").apply { mkdirs() }
        val stem = "hydro_$start"
        val original = File(folder, "$stem.csv")
        val (depth, gnss) = sample(start, 1, "raw says \"depth, ok\"\ncontinuation")
        val row = HydroCsv.row(depth, gnss, equipment, "UNKNOWN")
        original.writeText(HydroCsv.header + row + "incomplete,record")
        val oldBytes = original.readBytes()
        val diagnostic = File(app.filesDir, "sounder_diagnostics").apply { mkdirs() }
            .resolve("raw_bluetooth.bin").apply { writeBytes(byteArrayOf(42)) }
        File(folder, "$stem.metadata.json").writeText(JSONObject().put("project", "Legacy A")
            .put("started_utc_ms", start).put("vertical_offset_usft", equipment.offsetUsft)
            .put("sounder_raw_bytes_file", diagnostic.absolutePath).toString())
        File(folder, "$stem.sounder.bin").writeBytes(byteArrayOf(11, 10, 12))

        val originalStore = newStore()
        val project = originalStore.projects().single()
        val session = originalStore.sessions(project.id).single()
        originalStore.close()

        val reopened = newStore()
        try {
            assertEquals(project.id, reopened.projects().single().id)
            val imported = reopened.sessions(project.id).single()
            assertEquals(session.id, imported.id)
            assertEquals(CollectionStatus.IMPORTED, imported.status)
            assertEquals(1L, imported.observationCount)
            assertEquals(1L, imported.metadata.getLong("unimported_record_count"))
            assertEquals(depth.utcMillis, imported.endedUtc)
            assertEquals(1, reopened.migrationWarnings.size)
            val audit = ByteArrayOutputStream()
            reopened.exportAudit(imported.id, audit)
            val contents = unzip(audit.toByteArray())
            val projectZip = ByteArrayOutputStream()
            reopened.exportProject(project.id, projectZip)
            assertTrue(unzip(projectZip.toByteArray()).keys.none { it.endsWith("sounder_diagnostic.bin") })
            assertArrayEquals(byteArrayOf(42), contents.getValue("sounder_diagnostic.bin"))
            assertArrayEquals(oldBytes, original.readBytes())
            assertArrayEquals(oldBytes, contents.getValue("original_recording.csv"))
            assertArrayEquals(byteArrayOf(11, 10, 12), contents.getValue("$stem.sounder.bin"))
            assertEquals(HydroCsv.header + row, contents.getValue("$stem.csv").decodeToString())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun activeProjectAndArchivePersistWithoutChangingCollectionMetadata() {
        val store = newStore()
        val old = store.createProject("Name at acquisition")
        val session = store.startSession(old.id, start, JSONObject().put("project", "stale preference"))
        store.finishSession(session.id, start + 100)
        store.editProject(old.id, "Renamed", "New description")
        val other = store.createProject("Other")
        store.selectProject(old.id)
        store.archiveProject(old.id)
        assertNull(store.activeProject())
        store.selectProject(other.id)
        store.close()

        val reopened = newStore()
        try {
            assertEquals(other.id, reopened.activeProject()?.id)
            assertEquals("Renamed", reopened.projects().single { it.id == old.id }.name)
            assertTrue(reopened.projects().single { it.id == old.id }.archived)
            val archived = reopened.sessions(old.id).single()
            assertEquals("Name at acquisition", archived.metadata.getString("project"))
            assertEquals(old.id, archived.metadata.getString("project_id"))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun previousUsbServiceCannotInvalidateOrFeedTheNewOwner() {
        val previous = "old-usb-service"
        val replacement = "new-usb-service"
        val dbt = withChecksum("\$SDDBT,,f,4.2,M,,F")
        try {
            HydroLive.claimSounder(previous, CeeLineDbt.PROTOCOL)
            HydroLive.sounderConnection(previous, true)
            HydroLive.claimSounder(replacement, CeeLineDbt.PROTOCOL)
            HydroLive.sounderConnection(replacement, true)
            HydroLive.frame(replacement, dbt, start, 2_000_000_000)
            assertEquals(4.2, checkNotNull(HydroLive.depth?.meters), 0.0)

            HydroLive.sounderConnection(previous, false)
            HydroLive.frame(previous, "stale frame", start + 100, 2_100_000_000)
            assertTrue(HydroLive.sounderConnected)
            assertEquals(dbt, HydroLive.depth?.raw)

            HydroLive.sounderConnection(replacement, false)
            HydroLive.frame(replacement, dbt, start + 200, 2_200_000_000)
            assertNull(HydroLive.depth)
        } finally {
            HydroLive.sounderConnection(replacement, false)
        }
    }

    private fun withChecksum(sentence: String): String {
        val body = sentence.substringAfter('$').substringBefore('*')
        return "\$$body*%02X".format(body.fold(0) { value, c -> value xor c.code })
    }

    private fun newStore() = ProjectStore(app, databaseName) { start }

    private fun sample(base: Long, row: Int, raw: String? = null): Pair<HydroDepth, HydroGnss> {
        val utc = base + row * 1_000L
        val nanos = row * 1_000_000_000L + 100_000_000
        val meters = 4.2 + row / 1000.0
        val depth = HydroDepth(raw ?: "0 $meters 1 1 1 1 1 1", utc, nanos, meters, "VALID_PROVISIONAL", "test")
        val gnss = HydroGnss("$" + "GNGGA,120000.00,4048.1200,N,07303.0000,W,4", utc - 100, nanos - 100_000_000,
            40.8 + row / 100_000.0, -73.05, 4, 100.25, 0.015, 0.022, "120000.00")
        return depth to gnss
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> =
        ZipInputStream(ByteArrayInputStream(bytes)).use { archive -> buildMap {
            while (true) {
                val entry = archive.nextEntry ?: break
                put(entry.name, archive.readBytes())
            }
        } }
}

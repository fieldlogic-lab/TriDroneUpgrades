package com.massisolutions.tridrone

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exports the immutable acquisition rows. The database is never changed during export. */
internal class ProjectExport(private val db: SQLiteDatabase, private val root: File) {
    fun session(collection: CollectionSession, output: OutputStream) {
        val writer = output.bufferedWriter(Charsets.UTF_8)
        writer.appendLine(collection.csvHeader)
        writeRows(collection, writer)
        writer.flush()
    }

    fun audit(collection: CollectionSession, output: OutputStream) {
        val name = collection.legacyStem ?: "collection_${collection.id}"
        ZipOutputStream(output).use { zip ->
            zip.entry("$name.csv", collection.startedUtc) { session(collection, zip) }
            val attachmentPaths = copyAttachments(collection, zip, "", includeDiagnostic = true)
            val pnezd = "$name.pnezd.txt"
            var verifiedPoints = 0
            if (collection.extendedCsv) {
                zip.entry(pnezd, collection.startedUtc) { verifiedPoints = writePnezd(collection, zip) }
            }
            zip.entry("session.json", collection.startedUtc) {
                val info = collectionJson(collection, "$name.csv", attachmentPaths).apply {
                    if (collection.extendedCsv) put("pnezd_file", pnezd).put("pnezd_verified_count", verifiedPoints)
                }
                zip.write(info.bytes())
            }
        }
    }

    fun project(project: SurveyProject, collections: List<CollectionSession>, output: OutputStream) {
        val extended = collections.any { it.extendedCsv }
        val combinedHeader = if (extended) HydroCsv.ceeHeader else HydroCsv.header
        val manifest = JSONObject()
            .put("schema_version", 1)
            .put("project_id", project.id)
            .put("name", project.name)
            .put("description", project.description ?: JSONObject.NULL)
            .put("created_utc", instant(project.createdUtc))
            .put("modified_utc", instant(project.modifiedUtc))
            .put("archived", project.archived)
            .put("offline_map_reference", project.offlineMapReference ?: JSONObject.NULL)
            .put("combined_csv", "combined.csv")
            .put("combined_csv_schema_version", if (extended) 2 else 1)
            .put("encoding", "UTF-8")
            .put("combined_order", "collection number, then original acquisition order")
            .put("timestamp_basis", "metadata times are ISO 8601 UTC; CSV measurement timestamps retain acquisition values")
            .put("measurement_units", units())
        val entries = JSONArray()
        ZipOutputStream(output).use { zip ->
            for (collection in collections) {
                val directory = "collections/${collection.id}/"
                val csvPath = "${directory}observations.csv"
                zip.entry(csvPath, collection.startedUtc) { session(collection, zip) }
                val attachmentPaths = copyAttachments(collection, zip, directory)
                val info = collectionJson(collection, csvPath, attachmentPaths)
                val metadataPath = "${directory}session.json"
                zip.entry(metadataPath, collection.startedUtc) { zip.write(info.bytes()) }
                entries.put(info.put("metadata_file", metadataPath))
            }
            zip.entry("combined.csv", project.createdUtc) {
                val writer = zip.bufferedWriter(Charsets.UTF_8)
                writer.appendLine("project_id,session_id,observation_id,${combinedHeader.trimEnd('\n')}")
                for (collection in collections) {
                    writeRows(collection, writer, includeIdentifiers = true,
                        padExtendedColumns = extended && !collection.extendedCsv)
                }
                writer.flush()
            }
            manifest.put("sessions", entries)
            zip.entry("manifest.json", project.createdUtc) { zip.write(manifest.bytes()) }
        }
    }

    private fun writeRows(
        collection: CollectionSession, writer: BufferedWriter, includeIdentifiers: Boolean = false,
        padExtendedColumns: Boolean = false
    ) {
        db.rawQuery("SELECT id, csv_row FROM observations WHERE session_id = ? ORDER BY sequence",
            arrayOf(collection.id)).use { rows ->
            while (rows.moveToNext()) {
                if (includeIdentifiers) {
                    writer.append(collection.projectId).append(',')
                        .append(collection.id).append(',').append(rows.getString(0)).append(',')
                }
                writer.append(rows.getString(1))
                if (padExtendedColumns) writer.append(",".repeat(HydroCsv.ceeColumns.size - HydroCsv.columns.size))
                writer.newLine()
            }
        }
    }

    private val CollectionSession.extendedCsv get() = csvHeader == HydroCsv.ceeHeader.trimEnd('\n')

    private fun writePnezd(collection: CollectionSession, output: OutputStream): Int {
        val writer = output.bufferedWriter(Charsets.UTF_8)
        val formatter = HydroPnezd(collection.csvHeader.split(','))
        db.rawQuery("SELECT csv_row FROM observations WHERE session_id = ? ORDER BY sequence",
            arrayOf(collection.id)).use { rows ->
            while (rows.moveToNext()) {
                val row = HydroCsvRecords(rows.getString(0).reader().buffered()).next()?.cells ?: continue
                formatter.point(row)?.let(writer::appendLine)
            }
        }
        writer.flush()
        return formatter.count
    }

    private fun collectionJson(collection: CollectionSession, csvPath: String, attachments: List<String>) =
        JSONObject()
            .put("session_id", collection.id)
            .put("project_id", collection.projectId)
            .put("number", collection.number)
            .put("name", collection.name)
            .put("started_utc", instant(collection.startedUtc))
            .put("ended_utc", collection.endedUtc?.let(::instant) ?: JSONObject.NULL)
            .put("end_time_basis", collection.endTimeBasis ?: JSONObject.NULL)
            .put("status", collection.status.name)
            .put("observation_count", collection.observationCount)
            .put("valid_paired_count", collection.validCount)
            .put("csv", csvPath)
            .put("csv_schema_version", if (collection.extendedCsv) 2 else 1)
            .put("acquisition_settings", collection.metadata)
            .put("attachments", JSONArray(attachments)).apply {
                if ("sounder_diagnostic.bin" in attachments) {
                    put("sounder_diagnostic_status", "Ancillary diagnostic stream; snapshot may include activity outside this collection")
                }
            }

    private fun units() = JSONObject()
        .put("utc_epoch_ms", "milliseconds since Unix epoch (UTC)")
        .put("elapsed_realtime_ns", "nanoseconds since Android boot")
        .put("gnss_received_utc_ms", "milliseconds since Unix epoch (UTC)")
        .put("gnss_elapsed_realtime_ns", "nanoseconds since Android boot")
        .put("gnss_receiver_utc", "NMEA UTC time of day")
        .put("precision_received_utc_ms", "milliseconds since Unix epoch (UTC)")
        .put("latitude_deg", "decimal degrees")
        .put("longitude_deg", "decimal degrees")
        .put("raw_depth_m", "meters (converted from the selected sounder's uncorrected reading)")
        .put("raw_dbt_m", "meters below the CEE-LINE transducer")
        .put("transducer_below_waterline_usft", "US survey feet, positive submerged")
        .put("water_depth_m", "meters below the water surface")
        .put("water_depth_usft", "US survey feet below the water surface")
        .put("gps_to_transducer_usft", "US survey feet, positive downward from the selected GNSS reference")
        .put("raw_gga_altitude_m", "meters, GGA reported MSL reference")
        .put("h_precision_m", "meters")
        .put("v_precision_m", "meters")
        .put("easting_usft", "US survey feet")
        .put("northing_usft", "US survey feet")
        .put("h_precision_usft", "US survey feet")
        .put("v_precision_usft", "US survey feet")
        .put("depth_usft", "US survey feet")
        .put("vertical_offset_usft", "US survey feet, positive downward")
        .put("bottom_navd88_usft", "US survey feet when verified")
        .put("pair_delta_ms", "milliseconds")
        .put("pair_tolerance_ms", "milliseconds")

    private fun attachments(
        collection: CollectionSession, includeDiagnostic: Boolean
    ): Map<String, File> = buildMap {
        val stem = collection.legacyStem ?: return@buildMap
        val folder = File(root, "hydro_surveys").canonicalFile
        for (suffix in listOf(".metadata.json", ".gnss.csv", ".sounder.bin")) {
            val file = File(folder, stem + suffix)
            if (file.isFile && file.canonicalFile.parentFile == folder) put(file.name, file)
        }
        val diagnosticPath = collection.metadata.optString("sounder_raw_bytes_file")
        if (includeDiagnostic && diagnosticPath.isNotBlank()) {
            val file = File(diagnosticPath)
            val diagnosticFolder = File(root, "sounder_diagnostics").canonicalFile
            if (file.isFile && file.canonicalFile.parentFile == diagnosticFolder) {
                // Fixed path avoids using an arbitrary diagnostic basename as a ZIP entry.
                put("sounder_diagnostic.bin", file)
            }
        }
        if (collection.metadata.optLong("unimported_record_count") > 0) {
            val original = File(folder, "$stem.csv")
            if (original.isFile && original.canonicalFile.parentFile == folder) put("original_recording.csv", original)
        }
    }

    private fun copyAttachments(
        collection: CollectionSession, zip: ZipOutputStream, prefix: String, includeDiagnostic: Boolean = false
    ): List<String> = attachments(collection, includeDiagnostic).map { (name, file) ->
            val path = prefix + name
            zip.entry(path, collection.startedUtc) { file.inputStream().use { it.copyTo(zip) } }
            path
        }

    private fun JSONObject.bytes() = toString(2).toByteArray(Charsets.UTF_8)

    private fun instant(epoch: Long) = Instant.ofEpochMilli(epoch).toString()

    private inline fun ZipOutputStream.entry(name: String, epoch: Long, write: () -> Unit) {
        putNextEntry(ZipEntry(name).apply { time = epoch })
        write()
        closeEntry()
    }
}

/** Reads the historical CSV, retaining the original row and separating quoted fields for indexing. */
internal class HydroCsvRecords(private val reader: BufferedReader) {
    data class Record(val raw: String, val cells: List<String>?)

    fun next(): Record? {
        val raw = StringBuilder()
        val cell = StringBuilder()
        val cells = mutableListOf<String>()
        var quoted = false
        var quoteClosed = false
        var malformed = false
        while (true) {
            val value = reader.read()
            if (value == -1) {
                if (raw.isEmpty()) return null
                break
            }
            val char = value.toChar()
            if (!quoted && (char == '\r' || char == '\n')) {
                if (char == '\r') {
                    reader.mark(1)
                    if (reader.read() != '\n'.code) reader.reset()
                }
                break
            }
            raw.append(char)
            when {
                quoted && char == '"' -> {
                    reader.mark(1)
                    if (reader.read() == '"'.code) {
                        raw.append('"')
                        cell.append('"')
                    } else {
                        reader.reset()
                        quoted = false
                        quoteClosed = true
                    }
                }
                quoted -> cell.append(char)
                char == ',' -> {
                    cells.add(cell.toString())
                    cell.clear()
                    quoteClosed = false
                }
                char == '"' && cell.isEmpty() && !quoteClosed -> quoted = true
                else -> {
                    if (quoteClosed || char == '"') malformed = true
                    cell.append(char)
                }
            }
        }
        cells.add(cell.toString())
        return Record(raw.toString(), cells.takeUnless { quoted || malformed })
    }
}

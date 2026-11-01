package com.massisolutions.tridrone

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/** Read-only projections of the established files. These values never feed acquisition or export. */
data class MapPosition(val latitude: Double, val longitude: Double, val utcMillis: Long = 0L) {
    val valid get() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
    fun pair() = Pair(latitude, longitude)
}

data class MapSounding(val recordNumber: Int, val position: MapPosition, val depthMeters: Double,
    val quality: Int, val fields: Map<String, String>) {
    val qualityLabel get() = gnssQualityLabel(quality)
    fun details(): String = buildString {
        append("Record $recordNumber • $qualityLabel\n")
        append("Receipt UTC: ${java.time.Instant.ofEpochMilli(position.utcMillis)}\n")
        append(String.format(Locale.US, "Recorded GNSS: %.8f, %.8f°\n", position.latitude, position.longitude))
        append(String.format(Locale.US, "Depth: %.3f m • %.3f ftUS\n", depthMeters, SurveyUnits.feet(depthMeters)))
        append("Provisional depth below transducer; not a bottom elevation.\n")
        append("H / V receiver precision (1σ): ${precision("h_precision_m")} / ${precision("v_precision_m")}\n")
        append("Source datum: ${fields["source_crs"].orEmpty().ifEmpty { "UNKNOWN" }}\n")
        append("Pairing: ${fields["pairing_status"]}; Δ ${fields["pair_delta_ms"]} ms\n")
        append("Receipt timestamps; sensor latency unverified. No horizontal lever-arm correction.\n")
        append("Recorded metadata:\n")
        fields.forEach { (key, value) -> append("$key: ${value.ifEmpty { "unavailable" }}\n") }
    }
    private fun precision(key: String): String = fields[key]?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0 }?.let { String.format(Locale.US, "%.3f m", it) } ?: "unavailable"
}

fun gnssQualityLabel(quality: Int) = when (quality) {
    4 -> "RTK FIX"; 5 -> "RTK FLOAT"; 2 -> "DGPS"; 1 -> "SINGLE"; 3 -> "PPS"; else -> "QUALITY $quality"
}

data class HydroMapData(val track: List<MapPosition> = emptyList(), val soundings: List<MapSounding> = emptyList(),
    val omittedEvents: Int = 0, val decodedEvents: Int = 0)

/** Incremental tailing avoids rereading a growing survey on each UI tick. Call only on the map worker. */
class HydroSurveyMapReader(private val survey: File) {
    private val events = CsvTail(survey)
    private val gnssFile = File(survey.parentFile, survey.nameWithoutExtension + ".gnss.csv")
    private val gnss = CsvTail(gnssFile)
    private val positions = mutableListOf<MapPosition>()
    private val fallback = mutableListOf<MapPosition>()
    private val soundings = mutableListOf<MapSounding>()
    private var eventNumber = 0
    private var omitted = 0
    private var revision = 0L
    private var snapshotRevision = -1L
    private var cached = HydroMapData()

    fun refresh(): HydroMapData {
        events.read { fields ->
            eventNumber++; revision++
            val point = position(fields["latitude_deg"], fields["longitude_deg"], fields["utc_epoch_ms"])
            val depth = fields["raw_depth_m"]?.toDoubleOrNull()
            val quality = fields["gnss_quality"]?.toIntOrNull()
            if (point != null && quality != null && quality in 1..5 && fields["pairing_status"] == "PAIRED_RECEIPT_TIME") fallback.add(point)
            if (point != null && depth != null && depth.isFinite() && depth > 0 && depth <= HydroLiteDecoder.MAX_DEPTH_METERS &&
                fields["depth_validity"] == "VALID_PROVISIONAL" && fields["pairing_status"] == "PAIRED_RECEIPT_TIME" && quality != null && quality in 1..5) {
                soundings.add(MapSounding(eventNumber, point, depth, quality, fields))
            } else omitted++
        }
        gnss.read { fields ->
            val utc = fields["received_utc_ms"]?.toLongOrNull() ?: return@read
            val raw = fields["raw_sentence"] ?: return@read
            val fix = NmeaGnssAdapter.parseGga(raw, utc) ?: return@read
            val point = MapPosition(fix.latitude, fix.longitude, utc)
            if (point.valid && fix.fixQuality != null && fix.fixQuality in 1..5) { positions.add(point); revision++ }
        }
        if (revision != snapshotRevision) {
            // Stable chronological ordering; duplicate epochs/positions remain recorded, redundant vertices are omitted only here.
            val source = if (positions.isNotEmpty()) positions else fallback
            var previous: MapPosition? = null
            val track = source.sortedBy { it.utcMillis }.filter { point ->
                val keep = previous?.let { it.latitude != point.latitude || it.longitude != point.longitude } ?: true
                previous = point; keep
            }
            cached = HydroMapData(track, soundings.sortedBy { it.position.utcMillis }, omitted, eventNumber)
            snapshotRevision = revision
        }
        return cached
    }

    companion object {
        fun position(lat: String?, lon: String?, utc: String?): MapPosition? {
            val point = MapPosition(lat?.toDoubleOrNull() ?: return null, lon?.toDoubleOrNull() ?: return null,
                utc?.toLongOrNull() ?: return null)
            return point.takeIf { it.valid }
        }
    }
}

/** Complete CSV records only: an acquisition write ending mid-line stays unread until its newline arrives. */
private class CsvTail(private val file: File) {
    private var offset = 0L
    private var header: List<String>? = null
    private var pending = StringBuilder()
    fun read(accept: (Map<String, String>) -> Unit) {
        if (!file.isFile) return
        RandomAccessFile(file, "r").use { input ->
            // A saved survey is append-only. A replacement/truncation must be reopened explicitly, never erase the displayed survey.
            if (input.length() < offset) return
            input.seek(offset)
            val limit = input.length()
            while (input.filePointer < limit && !Thread.currentThread().isInterrupted) {
                val start = input.filePointer
                val raw = input.readLine() ?: break
                if (input.filePointer > limit) { input.seek(start); break }
                if (input.filePointer == limit) {
                    input.seek(limit - 1)
                    if (input.read() != 10) { input.seek(start); break }
                }
                offset = input.filePointer
                val line = String(raw.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                if (pending.isNotEmpty()) pending.append('\n')
                pending.append(line)
                check(pending.length <= 1_048_576) { "Oversized map CSV record" }
                val cells = parseCsvRecord(pending.toString()) ?: continue
                pending = StringBuilder()
                if (header == null) header = cells else {
                    val names = header!!
                    if (cells.size == names.size) accept(names.zip(cells).toMap())
                }
            }
        }
    }
}

/** Returns null for an incomplete quoted record; preserves raw strings and CSV escaping. */
internal fun parseCsvRecord(record: String): List<String>? {
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    while (i < record.length) {
        val c = record[i]
        when {
            c == '"' && quoted && record.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> { cells.add(cell.toString()); cell.setLength(0) }
            else -> cell.append(c)
        }
        i++
    }
    if (quoted) return null
    cells.add(cell.toString())
    return cells
}

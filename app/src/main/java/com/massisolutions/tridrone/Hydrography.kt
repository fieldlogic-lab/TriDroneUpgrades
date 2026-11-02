package com.massisolutions.tridrone

import java.util.Properties
import java.io.File

object SurveyUnits {
    const val US_FEET_PER_METER = 3937.0 / 1200.0
    fun feet(meters: Double) = meters * US_FEET_PER_METER
}

/** Reserve a new session without overwriting recordings if the device clock repeats an old time. */
object HydroSessionFiles {
    fun reserveCsv(directory: File, utcMillis: Long): File {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create survey directory" }
        var suffix = 0
        while (true) {
            val stem = "hydro_$utcMillis" + if (suffix == 0) "" else "_$suffix"
            val csv = File(directory, "$stem.csv")
            val siblings = listOf(".metadata.json", ".gnss.csv", ".sounder.bin")
            if (siblings.none { File(directory, stem + it).exists() } && csv.createNewFile()) return csv
            suffix++
        }
    }
}

data class HydroDepth(val raw: String, val utcMillis: Long, val elapsedNanos: Long,
    val meters: Double?, val validity: String, val diagnostic: String) {
    val valid get() = validity == "VALID_PROVISIONAL"
    fun status(connected: Boolean, nowNanos: Long): String = when {
        !connected -> "NO_READING_DISCONNECTED"
        nowNanos - elapsedNanos !in 0..3_000_000_000L -> "NO_READING_STALE"
        else -> validity
    }
}

/** Field 2 interpretation is provisional; numeric validity does not certify a sounding. */
object HydroLiteDecoder {
    const val PROTOCOL = "HYDROLITE_8_FIELD_PROVISIONAL_V1"
    // Conservative operational guard, not a manufacturer's range specification.
    const val MAX_DEPTH_METERS = 100.0
    fun decode(raw: String, utcMillis: Long, elapsedNanos: Long): HydroDepth {
        val fields = raw.trim().split(Regex("\\s+"))
        val values = fields.map { it.toDoubleOrNull() }
        val depth = fields.getOrNull(1)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        val state = when {
            fields.size != 8 || values.any { it == null || !it.isFinite() } -> "INVALID_MALFORMED"
            depth == null -> "INVALID_MALFORMED"
            depth == 0.0 -> "INVALID_NO_BOTTOM"
            depth < 0.0 || depth > MAX_DEPTH_METERS -> "INVALID_RANGE"
            else -> "VALID_PROVISIONAL"
        }
        return HydroDepth(raw, utcMillis, elapsedNanos, depth, state,
            "Unknown fields (1,3–8): " + fields.filterIndexed { i, _ -> i != 1 }.joinToString(" | "))
    }
}

object ProfileDepthDecoder {
    fun decode(profile: SounderProfile?, raw: String, utcMillis: Long, elapsedNanos: Long): HydroDepth =
        if (profile == null || profile.protocol == HydroLiteDecoder.PROTOCOL)
            HydroLiteDecoder.decode(raw, utcMillis, elapsedNanos)
        else HydroDepth(raw, utcMillis, elapsedNanos, null, "UNSUPPORTED_PROTOCOL",
            "${profile.model}: raw serial capture only; framing, units and output reference require hardware verification")
}

data class EquipmentSettings(val offsetUsft: Double? = null, val antennaReference: String = "UNSET",
    val confirmed: Boolean = false, val pairToleranceMillis: Long = 1000) {
    val valid get() = offsetUsft != null && offsetUsft.isFinite() && offsetUsft >= 0 &&
        antennaReference in REFERENCES && confirmed && pairToleranceMillis in 1..5000
    fun save(file: File) {
        val properties = Properties().apply {
            setProperty("offset_usft", offsetUsft?.toString().orEmpty())
            setProperty("antenna_reference", antennaReference)
            setProperty("confirmed", confirmed.toString())
            setProperty("pair_tolerance_ms", pairToleranceMillis.toString())
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.outputStream().use { properties.store(it, "Positive downward; US survey feet") }
        check(temporary.renameTo(file)) { "Cannot persist equipment settings" }
    }
    companion object {
        val REFERENCES = listOf("ANTENNA_REFERENCE_POINT", "RECEIVER_OUTPUT_REFERENCE")
        fun load(file: File): EquipmentSettings = try {
            val p = Properties().apply { file.inputStream().use { load(it) } }
            EquipmentSettings(p.getProperty("offset_usft")?.toDoubleOrNull(),
                p.getProperty("antenna_reference", "UNSET"), p.getProperty("confirmed") == "true",
                p.getProperty("pair_tolerance_ms")?.toLongOrNull() ?: 1000)
        } catch (_: Exception) { EquipmentSettings() }
    }
}

data class VerticalReference(val elevationUsft: Double, val antennaReference: String,
    val datum: String, val transformationVerified: Boolean, val provenance: String)

object BottomElevation {
    /** Input height must already be at the selected reference. No antenna calibration is added here. */
    fun calculate(height: VerticalReference?, equipment: EquipmentSettings, depth: HydroDepth): Double? {
        if (height == null || !equipment.valid || !depth.valid || depth.meters == null ||
            !depth.meters.isFinite() || depth.meters <= 0 || !height.transformationVerified ||
            height.datum != "EPSG:6360" || height.provenance.isBlank() ||
            height.antennaReference != equipment.antennaReference || !height.elevationUsft.isFinite()) return null
        return height.elevationUsft - equipment.offsetUsft!! - SurveyUnits.feet(depth.meters!!)
    }
}

data class ProfileDepthResult(val acousticDepthUsft: Double?, val belowWaterlineUsft: Double?, val status: String)

/** Normalize the instrument's declared output reference before applying calibration or geometry. */
object ProfileDepthCorrection {
    fun calculate(depth: HydroDepth, profile: SounderProfile?): ProfileDepthResult {
        if (profile == null) return ProfileDepthResult(null, null, "WITHHELD_NO_PROFILE")
        if (profile.protocol != HydroLiteDecoder.PROTOCOL)
            return ProfileDepthResult(null, null, "WITHHELD_UNSUPPORTED_PROTOCOL")
        if (!depth.valid || depth.meters == null || !depth.meters.isFinite() || depth.meters <= 0)
            return ProfileDepthResult(null, null, "WITHHELD_INVALID_DEPTH")
        val cal = profile.calibration
        if (!cal.usable) return ProfileDepthResult(null, null, "WITHHELD_UNCONFIRMED_CALIBRATION_OR_REFERENCE")
        val geometry = profile.geometry
        val rawFeet = SurveyUnits.feet(depth.meters)
        val range = when (cal.depthReference) {
            "ACOUSTIC_CENTER" -> rawFeet
            "WATERLINE" -> if (geometry.usable) rawFeet - geometry.sounderBelowWaterlineUsft!! else
                return ProfileDepthResult(null, null, "WITHHELD_UNCONFIRMED_GEOMETRY")
            else -> return ProfileDepthResult(null, null, "WITHHELD_UNKNOWN_DEPTH_REFERENCE")
        }
        val corrected = range * cal.scale + cal.offsetUsft
        if (!range.isFinite() || range <= 0 || !corrected.isFinite() || corrected <= 0)
            return ProfileDepthResult(null, null, "WITHHELD_INVALID_CORRECTED_DEPTH")
        val waterline = if (geometry.usable) corrected + geometry.sounderBelowWaterlineUsft!! else null
        return ProfileDepthResult(corrected, waterline,
            if (waterline != null) "PROVISIONAL_PROFILE_CORRECTED" else "PROVISIONAL_ACOUSTIC_ONLY_GEOMETRY_UNCONFIRMED")
    }

    /** Waterline draft B is deliberately absent from the antenna-based elevation equation. */
    fun bottomElevation(height: VerticalReference?, depth: HydroDepth, profile: SounderProfile): Double? {
        val acoustic = calculate(depth, profile).acousticDepthUsft ?: return null
        if (height == null || !profile.geometry.usable || !height.transformationVerified ||
            height.datum != "EPSG:6360" || height.provenance.isBlank() || !height.elevationUsft.isFinite() ||
            height.antennaReference != profile.geometry.reference) return null
        return height.elevationUsft - profile.geometry.gpsToSounderUsft!! - acoustic
    }
}

data class HydroGnss(val raw: String, val receivedUtcMillis: Long, val elapsedNanos: Long,
    val latitude: Double, val longitude: Double, val quality: Int, val altitudeMeters: Double?,
    val horizontalSigmaMeters: Double?, val verticalSigmaMeters: Double?, val receiverUtc: String,
    val precisionRaw: String = "", val precisionReceivedUtcMillis: Long? = null)

/** Connection transitions invalidate the previous epoch, including on reconnect. */
class HydroGnssState {
    private var owner = ""
    fun claim(source: String) { owner = source; connection(false) }
    fun owns(source: String) = source == owner
    fun connection(source: String, value: Boolean) { if (owns(source)) connection(value) }
    fun accept(source: String, value: HydroGnss) { if (owns(source)) accept(value) }
    var connected = false; private set
    var latest: HydroGnss? = null; private set
    fun connection(value: Boolean) { connected = value; latest = null }
    fun accept(value: HydroGnss) { if (connected) latest = value }
    fun invalidateForSentence(sentence: String): Boolean {
        if (!NmeaGnssAdapter.invalidatesFix(sentence)) return false
        latest = null
        return true
    }
    fun paired(depth: HydroDepth, toleranceMillis: Long): HydroGnss? = latest?.takeIf {
        connected && depth.elapsedNanos - it.elapsedNanos in 0..toleranceMillis * 1_000_000 && it.quality in 1..5
    }
}

object HydroCsv {
    val columns = listOf("utc_epoch_ms", "elapsed_realtime_ns", "gnss_received_utc_ms", "gnss_elapsed_realtime_ns",
        "gnss_receiver_utc", "latitude_deg", "longitude_deg", "easting_usft", "northing_usft", "source_crs", "gnss_quality", "h_precision_m", "v_precision_m",
        "h_precision_usft", "v_precision_usft", "raw_gga_altitude_m", "raw_gga_height_reference", "raw_depth_m", "depth_usft",
        "vertical_offset_usft", "antenna_reference", "offset_confirmed", "bottom_navd88_usft", "horizontal_crs", "vertical_target",
        "horizontal_status", "vertical_status", "depth_validity", "pairing_status", "pair_delta_ms", "protocol", "raw_depth",
        "raw_gnss", "precision_raw", "precision_received_utc_ms", "pair_tolerance_ms", "timestamp_basis",
        "equipment_profile_id", "equipment_profile_name", "equipment_sounder_model", "equipment_transport",
        "gps_to_sounder_center_usft", "sounder_below_waterline_usft", "geometry_verified", "geometry_checked_utc_ms",
        "depth_output_reference", "calibration_scale", "calibration_offset_usft", "calibration_confirmed",
        "corrected_acoustic_depth_usft", "bottom_below_waterline_usft", "depth_correction_status",
        "sounder_bluetooth_address", "gnss_bluetooth_address", "gnss_transport", "gnss_host", "gnss_port", "serial_baud")
    val header get() = columns.joinToString(",") + "\n"
    fun cell(value: Any?): String {
        val s = value?.toString().orEmpty()
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
    fun row(depth: HydroDepth, gnss: HydroGnss?, equipment: EquipmentSettings, sourceCrs: String,
        profile: SounderProfile? = null): String {
        val correction = ProfileDepthCorrection.calculate(depth, profile)
        val projected = if (sourceCrs == "EPSG:6318" && gnss?.quality == 4)
            StatePlane6539.projectNad83_2011(gnss.latitude, gnss.longitude) else null
        val horizontalStatus = when {
            gnss == null -> "MISSING_OR_STALE_GNSS"
            sourceCrs != "EPSG:6318" -> "SOURCE_DATUM_UNVERIFIED"
            gnss.quality != 4 -> "RTK_NOT_FIXED"
            projected == null -> "OUTSIDE_AREA_OR_INVALID"
            else -> "PROVISIONAL_CONTROL_CHECK_REQUIRED"
        }
        val values = listOf(depth.utcMillis, depth.elapsedNanos, gnss?.receivedUtcMillis, gnss?.elapsedNanos,
            gnss?.receiverUtc, gnss?.latitude, gnss?.longitude, projected?.first, projected?.second, sourceCrs, gnss?.quality,
            gnss?.horizontalSigmaMeters, gnss?.verticalSigmaMeters,
            gnss?.horizontalSigmaMeters?.let(SurveyUnits::feet), gnss?.verticalSigmaMeters?.let(SurveyUnits::feet),
            gnss?.altitudeMeters, "GGA_MSL_UNVERIFIED_NOT_NAVD88", depth.meters, depth.meters?.let(SurveyUnits::feet),
            equipment.offsetUsft, equipment.antennaReference, equipment.confirmed, null, "EPSG:6539", "EPSG:6360",
            horizontalStatus, "WITHHELD_NO_VERIFIED_VERTICAL_TRANSFORM", depth.validity,
            if (gnss != null) "PAIRED_RECEIPT_TIME" else "MISSING_OR_STALE_GNSS",
            gnss?.let { (depth.elapsedNanos - it.elapsedNanos) / 1_000_000.0 }, profile?.protocol ?: HydroLiteDecoder.PROTOCOL,
            depth.raw, gnss?.raw, gnss?.precisionRaw, gnss?.precisionReceivedUtcMillis, equipment.pairToleranceMillis,
            "DEVICE_RECEIPT_UTC_AND_MONOTONIC;SENSOR_LATENCY_UNVERIFIED",
            profile?.id, profile?.name, profile?.model, profile?.transport,
            profile?.geometry?.gpsToSounderUsft, profile?.geometry?.sounderBelowWaterlineUsft,
            profile?.geometry?.usable, profile?.checkedUtcMs,
            profile?.calibration?.depthReference, profile?.calibration?.scale, profile?.calibration?.offsetUsft,
            profile?.calibration?.confirmed, correction.acousticDepthUsft, correction.belowWaterlineUsft,
            correction.status, profile?.connection?.sounderAddress, profile?.connection?.gnssAddress,
            profile?.connection?.gnssTransport, profile?.connection?.gnssHost, profile?.connection?.gnssPort,
            profile?.connection?.baud)
        check(values.size == columns.size)
        return values.joinToString(",") { cell(it) } + "\n"
    }
}

/** UTC time-of-day equality plus receipt-age guard; never attach a neighboring epoch's GST. */
object NmeaEpochTime {
    fun key(raw: String): Double? {
        if (!raw.matches(Regex("[0-9]{6}(\\.[0-9]+)?"))) return null
        val value = raw.toDoubleOrNull() ?: return null
        val hour = (value / 10000).toInt()
        val minute = (value / 100).toInt() % 100
        val second = value % 100
        return value.takeIf { hour in 0..23 && minute in 0..59 && second >= 0 && second < 60 }
    }
}

data class ReceiverPrecision(val horizontalMeters: Double, val verticalMeters: Double,
    val raw: String, val receivedUtcMillis: Long, val elapsedNanos: Long, val epoch: Double)

class NmeaPrecisionTracker {
    private var latest: ReceiverPrecision? = null
    private var gga: Pair<String, Long>? = null
    fun reset() { latest = null; gga = null }
    fun forLatest(nanos: Long): ReceiverPrecision? = gga?.takeIf { nanos - it.second in 0..3_000_000_000L }
        ?.let { forGga(it.first, nanos) }
    fun accept(sentence: String, utc: Long, nanos: Long) {
        val fields = sentence.substringBefore('*').removePrefix("$").split(',')
        if (NmeaGnssAdapter.isGga(sentence)) {
            gga = if (!NmeaGnssAdapter.invalidatesFix(sentence)) Pair(sentence, nanos) else null
            return
        }
        if (!fields.firstOrNull().orEmpty().endsWith("GST")) return
        latest = null
        if (!NmeaGga.valid(sentence)) return
        val sigma = NmeaGst.parse(sentence) ?: return
        val epoch = NmeaEpochTime.key(fields.getOrNull(1).orEmpty()) ?: return
        latest = ReceiverPrecision(sigma.first, sigma.second, sentence, utc, nanos, epoch)
    }
    fun forGga(sentence: String, nanos: Long): ReceiverPrecision? {
        if (!NmeaGnssAdapter.isGga(sentence)) return null
        val epoch = NmeaEpochTime.key(sentence.substringBefore('*').split(',').getOrNull(1).orEmpty()) ?: return null
        return latest?.takeIf { nanos - it.elapsedNanos in 0..3_000_000_000L && kotlin.math.abs(it.epoch - epoch) < 0.000001 }
    }
}

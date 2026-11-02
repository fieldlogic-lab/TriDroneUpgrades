package com.massisolutions.tridrone

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.BufferedWriter
import org.json.JSONObject

/** Shared acquisition coordinator. All sources are external hardware; demo providers never enter here. */
object HydroLive {
    private val gnss = HydroGnssState()
    private val precision = NmeaPrecisionTracker()
    private var writer: BufferedWriter? = null
    private var rawGnssWriter: BufferedWriter? = null
    private var rawDepthOutput: java.io.OutputStream? = null
    private var settings = EquipmentSettings()
    private var recordingProfile: SounderProfile? = null
    private var sounderProfile: SounderProfile? = null
    private var gnssProfile: SounderProfile? = null
    private var sourceCrs = "UNKNOWN"
    private var context: Context? = null
    private var file: File? = null
    private var rawFile: File? = null
    @Volatile var depth: HydroDepth? = null; private set
    @Volatile var sounderConnected = false; private set
    @Volatile var recording = false; private set
    @Volatile var startedUtc = 0L; private set
    @Volatile var startedNanos = 0L; private set
    @Volatile var stoppedNanos = 0L; private set
    @Volatile var validCount = 0; private set
    @Volatile var eventCount = 0; private set
    @Volatile var error = ""; private set

    fun equipmentFile(c: Context) = File(c.filesDir, "equipment.properties")
    @Synchronized fun ownsGnss(source: String) = gnss.owns(source)
    @Synchronized fun claimGnss(source: String, profile: SounderProfile? = null) {
        gnss.claim(source); gnssProfile = profile; precision.reset()
    }
    @Synchronized fun gnssConnection(source: String, connected: Boolean) {
        if (!gnss.owns(source)) return
        gnss.connection(source, connected); precision.reset()
    }
    @Synchronized fun gnssSentence(source: String, sentence: String, utc: Long, nanos: Long) {
        if (!gnss.owns(source)) return
        try {
            rawGnssWriter?.apply { write("$utc,$nanos,${HydroCsv.cell(sentence)}\n"); flush() }
        } catch (e: Exception) { fail(e) }
        precision.accept(sentence, utc, nanos)
        if (gnss.invalidateForSentence(sentence)) { precision.reset(); return }
        if (sentence.substringBefore(',').endsWith("GST")) {
            val previous = gnss.latest
            if (previous != null && nanos - previous.elapsedNanos in 0..3_000_000_000L) {
                val matched = precision.forLatest(nanos)
                gnss.accept(previous.copy(horizontalSigmaMeters = matched?.horizontalMeters,
                    verticalSigmaMeters = matched?.verticalMeters, precisionRaw = matched?.raw.orEmpty(),
                    precisionReceivedUtcMillis = matched?.receivedUtcMillis))
            }
            return
        }
        val observation = NmeaGnssAdapter.parseGga(sentence, utc) ?: return
        val fields = NmeaGga.parse(sentence) ?: return
        val sigma = precision.forGga(sentence, nanos)
        gnss.accept(HydroGnss(sentence, utc, nanos, observation.latitude, observation.longitude,
            observation.fixQuality ?: 0, observation.altitudeMeters, sigma?.horizontalMeters, sigma?.verticalMeters,
            fields[0], sigma?.raw.orEmpty(), sigma?.receivedUtcMillis))
    }
    private var sounderOwner = ""
    @Synchronized fun depthProfile(): SounderProfile? = sounderProfile
    /** Invalidate old transports immediately, before asynchronous services finish stopping. */
    @Synchronized fun equipmentChanged() {
        check(!recording) { "Stop recording before modifying equipment" }
        gnss.claim(""); gnssProfile = null; precision.reset()
        sounderOwner = ""; sounderProfile = null; sounderConnected = false; depth = null
    }
    @Synchronized fun ownsSounder(source: String) = source == sounderOwner
    @Synchronized fun claimSounder(source: String, profile: SounderProfile? = null) {
        sounderOwner = source; sounderProfile = profile; sounderConnected = false; depth = null
    }
    @Synchronized fun sounderConnection(source: String, connected: Boolean) {
        if (source != sounderOwner) return
        sounderConnected = connected
        depth = null
    }
    @Synchronized fun rawDepth(source: String, bytes: ByteArray, count: Int) {
        if (source != sounderOwner || !recording) return
        try { rawDepthOutput?.apply { write(bytes, 0, count); flush() } } catch (e: Exception) { fail(e) }
    }
    @Synchronized fun frame(source: String, frame: String, utc: Long, nanos: Long) {
        if (source != sounderOwner) return
        val sample = ProfileDepthDecoder.decode(sounderProfile, frame, utc, nanos)
        depth = sample
        android.util.Log.d("HydroLite", "${sample.validity}: ${sample.diagnostic}")
        val paired = gnss.paired(sample, settings.pairToleranceMillis)
        if (!recording) return
        try {
            writer?.apply { write(HydroCsv.row(sample, paired, settings, sourceCrs, recordingProfile)); flush() }
            eventCount++
            if (sample.valid && paired != null) validCount++
            persistStatus()
        } catch (e: Exception) { fail(e) }
    }
    @Synchronized fun start(c: Context): File {
        check(!recording) { "Already recording" }
        context = c.applicationContext
        val now = SystemClock.elapsedRealtimeNanos()
        val current = depth
        check(sounderConnected && current != null && current.valid && current.status(true, now) == "VALID_PROVISIONAL") {
            "A fresh valid provisional depth is required" }
        recordingProfile = EquipmentProfiles(c.filesDir) { recording }.active()
        check(ProfileBinding.sounderMatches(recordingProfile, sounderProfile)) { "Equipment changed: reconnect the sounder for the selected profile" }
        check(ProfileBinding.gnssMatches(recordingProfile, gnssProfile)) { "Equipment changed: reconnect GNSS for the selected profile" }
        settings = recordingProfile?.equipmentSettings() ?: EquipmentSettings.load(equipmentFile(c))
        check(gnss.paired(current, settings.pairToleranceMillis) != null) { "A fresh paired external GNSS epoch is required" }
        val folder = File(c.filesDir, "hydro_surveys").apply { mkdirs() }
        startedUtc = System.currentTimeMillis(); startedNanos = now; stoppedNanos = 0
        file = HydroSessionFiles.reserveCsv(folder, startedUtc)
        val stem = file!!.nameWithoutExtension
        rawFile = File(folder, "$stem.gnss.csv")
        sourceCrs = SurveyConfig.load(c).source
        val sounderRaw = c.getSharedPreferences("sounder_diagnostics", Context.MODE_PRIVATE).getString("file", "")
        val metadata = SurveyConfig.load(c).json().apply {
            put("started_utc_ms", startedUtc); put("protocol", recordingProfile?.protocol ?: HydroLiteDecoder.PROTOCOL)
            put("protocol_verified", false); put("depth_range_guard_m", HydroLiteDecoder.MAX_DEPTH_METERS)
            put("vertical_offset_usft", settings.offsetUsft ?: JSONObject.NULL)
            // The same immutable value is used for every CSV row in this new session.
            recordingProfile?.let { p ->
                put("equipment_profile_id", p.id)
                put("equipment_profile_name", p.name)
                put("equipment_sounder_model", p.model)
                put("equipment_transport", p.transport)
                put("gps_to_sounder_center_usft", p.geometry.gpsToSounderUsft ?: JSONObject.NULL)
                put("sounder_below_waterline_usft", p.geometry.sounderBelowWaterlineUsft ?: JSONObject.NULL)
                put("geometry_antenna_reference", p.geometry.reference)
                put("geometry_verified", p.geometry.usable)
                put("geometry_checked_utc_ms", p.checkedUtcMs)
                put("profile_geometry_applied_to_recording", true)
                put("profile_schema_version", 2)
                put("depth_output_reference", p.calibration.depthReference)
                put("calibration_scale", p.calibration.scale)
                put("calibration_offset_usft", p.calibration.offsetUsft)
                put("calibration_confirmed", p.calibration.confirmed)
                put("sounder_bluetooth_address", p.connection.sounderAddress)
                put("gnss_bluetooth_address", p.connection.gnssAddress)
                put("gnss_transport", p.connection.gnssTransport)
                put("gnss_host", p.connection.gnssHost)
                put("gnss_port", p.connection.gnssPort)
                put("serial_baud", p.connection.baud)
                put("serial_baud_configuration", "External adapter UART setting; not configured by Bluetooth RFCOMM")
                put("correction_formula", "acoustic=(raw_ftUS - (output_reference==WATERLINE ? B : 0))*scale+offset; waterline=acoustic+B; antenna_bottom=verified_reference_height-A-acoustic")
                put("correction_status_at_start", ProfileDepthCorrection.calculate(current, p).status)
            }
            put("vertical_status", "WITHHELD_NO_VERIFIED_VERTICAL_TRANSFORM")
            put("antenna_reference", settings.antennaReference); put("offset_confirmed", settings.confirmed)
            put("offset_sign", "positive downward"); put("pair_tolerance_ms", settings.pairToleranceMillis)
            put("time_basis", "device receipt time; hardware latency not measured")
            put("sounder_raw_bytes_file", sounderRaw); put("gnss_raw_file", rawFile!!.name)
            put("recording_raw_sounder_bytes_file", "$stem.sounder.bin")
            put("antenna_correction", "none applied; input height reference must match offset reference")
            put("valid_count_definition", "numeric provisional depth paired with GNSS quality 1–5; not survey-grade certification")
        }
        try {
            File(folder, "$stem.metadata.json").writeText(metadata.toString(2))
            rawDepthOutput = File(folder, "$stem.sounder.bin").outputStream().buffered()
            writer = file!!.bufferedWriter().apply { write(HydroCsv.header); flush() }
            rawGnssWriter = rawFile!!.bufferedWriter().apply { write("received_utc_ms,elapsed_realtime_ns,raw_sentence\n"); flush() }
            // Preserve the epoch used to open the recording, even if no further GNSS arrives.
            gnss.latest?.let { rawGnssWriter!!.apply { write("${it.receivedUtcMillis},${it.elapsedNanos},${HydroCsv.cell(it.raw)}\n"); flush() } }
            validCount = 0; eventCount = 0; error = ""; recording = true; persistStatus()
            return file!!
        } catch (e: Exception) { stop(); throw e }
    }
    @Synchronized fun stop() {
        recording = false; stoppedNanos = SystemClock.elapsedRealtimeNanos()
        try { writer?.close() } catch (e: Exception) { error = e.message.orEmpty() }
        try { rawGnssWriter?.close() } catch (e: Exception) { error = e.message.orEmpty() }
        try { rawDepthOutput?.close() } catch (e: Exception) { error = e.message.orEmpty() }
        writer = null; rawGnssWriter = null; rawDepthOutput = null; persistStatus()
    }
    private fun fail(e: Exception) { error = e.message ?: "Recording write failed"; stop() }
    private fun persistStatus() {
        context?.getSharedPreferences("hydro_status", Context.MODE_PRIVATE)?.edit()
            ?.putBoolean("recording", recording)?.putInt("events", eventCount)?.putInt("valid", validCount)
            ?.putString("file", file?.absolutePath)?.putLong("started_utc_ms", startedUtc)?.putString("error", error)?.apply()
    }
}

package com.massisolutions.tridrone

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.BufferedWriter
import org.json.JSONObject

/** Shared acquisition coordinator. All sources are external hardware; demo providers never enter here. */
object HydroLive {
    private val gnss = HydroGnssState()
    private var precision: Triple<Double, Double, Long>? = null
    private var precisionRaw = ""
    private var precisionUtc: Long? = null
    private var writer: BufferedWriter? = null
    private var rawGnssWriter: BufferedWriter? = null
    private var rawDepthOutput: java.io.OutputStream? = null
    private var settings = EquipmentSettings()
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
    @Synchronized fun claimGnss(source: String) {
        gnss.claim(source); precision = null; precisionRaw = ""; precisionUtc = null
    }
    @Synchronized fun gnssConnection(source: String, connected: Boolean) {
        if (!gnss.owns(source)) return
        gnss.connection(source, connected); precision = null; precisionRaw = ""; precisionUtc = null
    }
    @Synchronized fun gnssSentence(source: String, sentence: String, utc: Long, nanos: Long) {
        if (!gnss.owns(source)) return
        try {
            rawGnssWriter?.apply { write("$utc,$nanos,${HydroCsv.cell(sentence)}\n"); flush() }
        } catch (e: Exception) { fail(e) }
        NmeaGst.parse(sentence)?.let {
            precision = Triple(it.first, it.second, nanos); precisionRaw = sentence; precisionUtc = utc
        }
        val observation = NmeaGnssAdapter.parseGga(sentence, utc) ?: return
        val fields = NmeaGga.parse(sentence) ?: return
        val sigma = precision?.takeIf { nanos - it.third in 0..3_000_000_000L }
        gnss.accept(HydroGnss(sentence, utc, nanos, observation.latitude, observation.longitude,
            observation.fixQuality ?: 0, observation.altitudeMeters, sigma?.first, sigma?.second,
            fields[0], if (sigma != null) precisionRaw else "", if (sigma != null) precisionUtc else null))
    }
    private var sounderOwner = ""
    @Synchronized fun ownsSounder(source: String) = source == sounderOwner
    @Synchronized fun claimSounder(source: String) {
        sounderOwner = source; sounderConnected = false; depth = null
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
        val sample = HydroLiteDecoder.decode(frame, utc, nanos)
        depth = sample
        android.util.Log.d("HydroLite", "${sample.validity}: ${sample.diagnostic}")
        val paired = gnss.paired(sample, settings.pairToleranceMillis)
        if (!recording) return
        try {
            writer?.apply { write(HydroCsv.row(sample, paired, settings, sourceCrs)); flush() }
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
        settings = EquipmentSettings.load(equipmentFile(c))
        check(gnss.paired(current, settings.pairToleranceMillis) != null) { "A fresh paired external GNSS epoch is required" }
        val folder = File(c.filesDir, "hydro_surveys").apply { mkdirs() }
        startedUtc = System.currentTimeMillis(); startedNanos = now; stoppedNanos = 0
        file = File(folder, "hydro_$startedUtc.csv")
        rawFile = File(folder, "hydro_$startedUtc.gnss.csv")
        sourceCrs = SurveyConfig.load(c).source
        val sounderRaw = c.getSharedPreferences("sounder_diagnostics", Context.MODE_PRIVATE).getString("file", "")
        val metadata = SurveyConfig.load(c).json().apply {
            put("started_utc_ms", startedUtc); put("protocol", HydroLiteDecoder.PROTOCOL)
            put("protocol_verified", false); put("depth_range_guard_m", HydroLiteDecoder.MAX_DEPTH_METERS)
            put("vertical_offset_usft", settings.offsetUsft ?: JSONObject.NULL)
            put("antenna_reference", settings.antennaReference); put("offset_confirmed", settings.confirmed)
            put("offset_sign", "positive downward"); put("pair_tolerance_ms", settings.pairToleranceMillis)
            put("time_basis", "device receipt time; hardware latency not measured")
            put("sounder_raw_bytes_file", sounderRaw); put("gnss_raw_file", rawFile!!.name)
            put("recording_raw_sounder_bytes_file", "hydro_$startedUtc.sounder.bin")
            put("antenna_correction", "none applied; input height reference must match offset reference")
            put("valid_count_definition", "numeric provisional depth paired with GNSS quality 1–5; not survey-grade certification")
        }
        try {
            File(folder, "hydro_$startedUtc.metadata.json").writeText(metadata.toString(2))
            rawDepthOutput = File(folder, "hydro_$startedUtc.sounder.bin").outputStream().buffered()
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

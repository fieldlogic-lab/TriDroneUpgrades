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
    private var store: ProjectStore? = null
    private var sessionId: String? = null
    private var rawGnssWriter: BufferedWriter? = null
    private var rawDepthOutput: java.io.OutputStream? = null
    private var settings = EquipmentSettings()
    private var sourceCrs = "UNKNOWN"
    private var context: Context? = null
    @Volatile var depth: HydroDepth? = null; private set
    @Volatile var sounderConnected = false; private set
    @Volatile var recording = false; private set
    @Volatile var startedUtc = 0L; private set
    @Volatile var startedNanos = 0L; private set
    @Volatile var stoppedNanos = 0L; private set
    @Volatile var validCount = 0; private set
    @Volatile var eventCount = 0; private set
    @Volatile var error = ""; private set

    fun equipmentFile(c: Context, protocol: String = HydroLiteDecoder.PROTOCOL): File =
        EquipmentProfile.forProtocol(protocol).file(c)

    fun selectedProtocol(c: Context): String = EquipmentProfile.active(c).protocol

    @Synchronized fun selectProfile(c: Context, profile: EquipmentProfile): Boolean {
        if (recording) return false
        if (profile != EquipmentProfile.active(c)) {
            EquipmentProfile.select(c, profile)
            claimSounder(profile.source)
        }
        return true
    }

    @Synchronized fun ownsGnss(source: String) = gnss.owns(source)
    @Synchronized fun claimGnss(source: String) {
        gnss.claim(source); precision.reset()
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
        if ((recording || mapProjectId == null) && gnss.connected &&
            (observation.fixQuality ?: 0) in 1..5) {
            mapTrack.add(observation.latitude to observation.longitude)
        }
    }
    private var sounderOwner = ""
    private var sounderProtocol = HydroLiteDecoder.PROTOCOL
    private var recordingProtocol = HydroLiteDecoder.PROTOCOL
    private var mapProjectId: String? = null
    private val mapTrack = SurveyMapTrace()
    private val mapSoundings = SurveyMapTrace()

    @Synchronized fun mapSnapshot(nowNanos: Long, projectId: String?): LiveMapSnapshot {
        val current = gnss.latest?.takeIf {
            gnss.connected && it.quality in 1..5 && nowNanos - it.elapsedNanos in 0..5_000_000_000L
        }?.let { it.latitude to it.longitude }
        return if (mapProjectId == null || mapProjectId == projectId) {
            LiveMapSnapshot(current, mapTrack.snapshot(), mapSoundings.snapshot())
        } else LiveMapSnapshot(current, emptyList(), emptyList())
    }

    private fun protocolForSource(source: String) =
        if (source == CeeLineUsbService.SOURCE) CeeLineDbt.PROTOCOL else HydroLiteDecoder.PROTOCOL
    @Synchronized fun ownsSounder(source: String) = source == sounderOwner
    @Synchronized fun claimSounder(source: String, protocol: String = protocolForSource(source)) {
        if (recording && protocol != recordingProtocol) return
        sounderOwner = source
        sounderProtocol = protocol
        sounderConnected = false
        depth = null
    }
    @Synchronized fun sounderConnection(source: String, connected: Boolean) {
        if (source != sounderOwner) return
        sounderConnected = connected
        depth = null
    }
    @Synchronized fun rawDepth(source: String, bytes: ByteArray, count: Int) {
        if (source != sounderOwner || !sounderConnected || !recording) return
        try { rawDepthOutput?.apply { write(bytes, 0, count); flush() } } catch (e: Exception) { fail(e) }
    }
    @Synchronized fun frame(source: String, frame: String, utc: Long, nanos: Long) {
        if (source != sounderOwner || !sounderConnected) return
        val sample = if (sounderProtocol == CeeLineDbt.PROTOCOL) {
            CeeLineDbt.decode(frame, utc, nanos)
        } else HydroLiteDecoder.decode(frame, utc, nanos)
        depth = sample
        android.util.Log.d("HydroSounder", "${sample.validity}: ${sample.diagnostic}")
        val paired = gnss.paired(sample, settings.pairToleranceMillis)
        if (!recording) return
        try {
            checkNotNull(store).append(checkNotNull(sessionId), sample, paired,
                HydroCsv.row(sample, paired, settings, sourceCrs))
            eventCount++
            if (sample.valid && paired != null) {
                validCount++
                mapSoundings.add(paired.latitude to paired.longitude)
            }
            persistStatus()
        } catch (e: Exception) { fail(e) }
    }
    @Synchronized fun waterDepthMeters(c: Context): Double? {
        val sample = depth ?: return null
        if (sample.protocol != CeeLineDbt.PROTOCOL ||
            sample.status(sounderConnected, SystemClock.elapsedRealtimeNanos()) != "VALID_PROVISIONAL") return null
        val geometry = if (recording) settings else EquipmentProfile.BOAT.settings(c)
        return WaterDepth.meters(sample.meters, geometry.waterlineOffsetUsft?.div(SurveyUnits.US_FEET_PER_METER))
    }

    @Synchronized fun start(c: Context, projectId: String, expectedProtocol: String) {
        check(!recording) { "Already recording" }
        if (sessionId != null) {
            closeRecording()
            check(sessionId == null) { "Previous collection could not be finalized. $error" }
        }
        context = c.applicationContext
        val now = SystemClock.elapsedRealtimeNanos()
        val current = depth
        check(sounderConnected && current != null && current.valid && current.status(true, now) == "VALID_PROVISIONAL") {
            "A fresh valid provisional depth is required" }
        check(current.protocol == expectedProtocol) { "Connect the selected sounder before GO" }
        val isCee = current.protocol == CeeLineDbt.PROTOCOL
        val profile = EquipmentProfile.forProtocol(current.protocol)
        settings = profile.settings(c)
        check(!isCee || settings.ceeValid) { "Confirm both vertical measurements before GO" }
        check(gnss.paired(current, settings.pairToleranceMillis) != null) {
            if (isCee) "A fresh paired external RTK FIX/FLOAT epoch is required" else "A fresh paired external GNSS epoch is required"
        }
        val folder = File(c.filesDir, "hydro_surveys").apply { mkdirs() }
        startedUtc = System.currentTimeMillis(); startedNanos = now; stoppedNanos = 0
        val stem = "hydro_${startedUtc}_${java.util.UUID.randomUUID()}"
        val rawFile = File(folder, "$stem.gnss.csv")
        val projectStore = ProjectStore.get(c)
        val surveyConfig = SurveyConfig.load(c)
        sourceCrs = surveyConfig.source
        val sounderRaw = if (isCee) "" else c.getSharedPreferences("sounder_diagnostics", Context.MODE_PRIVATE).getString("file", "")
        val metadata = surveyConfig.json().apply {
            put("started_utc_ms", startedUtc); put("protocol", current.protocol)
            put("schema_version", if (isCee) 2 else 1)
            put("equipment_profile", profile.source)
            put("equipment_profile_name", profile.name(settings))
            put("sounder_reference", settings.sounderReference ?: JSONObject.NULL)
            put("sounder_to_waterline_usft", settings.waterlineOffsetUsft ?: JSONObject.NULL)
            put("depth_correction_status", if (isCee) "EXISTING_DBT_WATERLINE_FORMULA" else "UNVERIFIED_AS_REPORTED")
            if (isCee) {
                put("transducer_below_waterline_usft", settings.waterlineOffsetUsft ?: JSONObject.NULL)
                put("water_depth_formula", "raw_DBT + positive_submerged_transducer_offset")
                put("bottom_formula", "verified_GNSS_reference_elevation - GPS_to_transducer - raw_DBT")
            }
            put("protocol_verified", false)
            put("depth_range_guard_m", if (isCee) JSONObject.NULL else HydroLiteDecoder.MAX_DEPTH_METERS)
            put("vertical_offset_usft", settings.offsetUsft ?: JSONObject.NULL)
            put("antenna_reference", settings.antennaReference); put("offset_confirmed", settings.confirmed)
            val arpOffset = settings.arpToTransducerFaceUsft
            val provenance = settings.verticalProvenance
            val gnssEvidence = provenance.gnssHeightReferenceEvidence?.takeIf(String::isNotBlank)
            val sounderEvidence = provenance.sounderAcousticReferenceEvidence?.takeIf(String::isNotBlank)
            put("arp_to_transducer_face_usft", arpOffset ?: JSONObject.NULL)
            put("arp_to_transducer_face_confirmed", arpOffset != null && settings.confirmed)
            put("gnss_height_reference", if (provenance.declaresGnssHeightAtArp && gnssEvidence != null) "ANTENNA_REFERENCE_POINT" else "UNVERIFIED")
            put("gnss_height_reference_evidence", gnssEvidence ?: JSONObject.NULL)
            put("sounder_acoustic_reference", if (provenance.declaresDepthFromFace && sounderEvidence != null) "TRANSDUCER_FACE" else "UNVERIFIED")
            put("sounder_acoustic_reference_evidence", sounderEvidence ?: JSONObject.NULL)
            put("instrument_reference_evidence_basis", "operator-entered profile evidence; independent verification not performed")
            put("offset_sign", "positive downward"); put("pair_tolerance_ms", settings.pairToleranceMillis)
            put("time_basis", "device receipt time; hardware latency not measured")
            put("sounder_raw_bytes_file", sounderRaw); put("gnss_raw_file", rawFile.name)
            put("recording_raw_sounder_bytes_file", "$stem.sounder.bin")
            put("antenna_correction", "none applied; input height reference must match offset reference")
            val qualities = if (isCee) "4 or 5 (RTK FIX/FLOAT, maximum 3 seconds)" else "1–5"
            put("valid_count_definition", "numeric provisional depth paired with GNSS quality $qualities; not survey-grade certification")
        }
        try {
            val session = projectStore.startSession(projectId, startedUtc, metadata, stem)
            store = projectStore
            sessionId = session.id
            validCount = 0
            eventCount = 0
            error = ""
            rawDepthOutput = File(folder, "$stem.sounder.bin").outputStream().buffered()
            rawGnssWriter = rawFile.bufferedWriter()
            checkNotNull(rawGnssWriter).apply { write("received_utc_ms,elapsed_realtime_ns,raw_sentence\n"); flush() }
            // Preserve the epoch used to open the recording, even if no further GNSS arrives.
            gnss.latest?.let { rawGnssWriter!!.apply { write("${it.receivedUtcMillis},${it.elapsedNanos},${HydroCsv.cell(it.raw)}\n"); flush() } }
            recordingProtocol = current.protocol
            mapProjectId = projectId
            mapTrack.clear()
            gnss.latest?.let { mapTrack.add(it.latitude to it.longitude) }
            mapSoundings.clear()
            recording = true
            persistStatus()
        } catch (e: Exception) { fail(e); throw e }
    }
    @Synchronized fun stop() {
        recording = false; stoppedNanos = SystemClock.elapsedRealtimeNanos()
        closeRecording()
    }

    private fun closeRecording() {
        try { rawGnssWriter?.close() } catch (e: Exception) { setWriteError(e) }
        try { rawDepthOutput?.close() } catch (e: Exception) { setWriteError(e) }
        rawGnssWriter = null
        rawDepthOutput = null
        try {
            sessionId?.let { checkNotNull(store).finishSession(it, System.currentTimeMillis(), error.isNotBlank()) }
            sessionId = null
            store = null
        } catch (e: Exception) {
            setWriteError(e)
        }
        persistStatus()
    }

    private fun setWriteError(e: Exception) {
        if (error.isBlank()) error = e.message ?: "Recording write failed"
    }

    private fun fail(e: Exception) {
        setWriteError(e)
        stop()
    }

    private fun persistStatus() {
        context?.getSharedPreferences("hydro_status", Context.MODE_PRIVATE)?.edit()
            ?.putBoolean("recording", recording)?.putInt("events", eventCount)?.putInt("valid", validCount)
            ?.putString("session_id", sessionId)?.putLong("started_utc_ms", startedUtc)?.putString("error", error)?.apply()
    }
}

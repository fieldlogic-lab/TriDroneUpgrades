package com.massisolutions.tridrone

import android.content.Context
import android.os.SystemClock
import java.io.Closeable
import java.io.File

/** One immutable configuration per acquisition session; append and flush through interruption. */
class GnssSessionLog(context: Context, prefix: String) : Closeable {
    private val capturedUtc = System.currentTimeMillis()
    val id = SessionIdentity.newId(capturedUtc)
    private val settings = SurveyConfig.load(context)
    private val directory = File(context.filesDir, "surveys").apply { mkdirs() }
    private val opened = mutableListOf<Closeable>()
    private fun <T : Closeable> open(factory: () -> T): T = try {
        factory().also { opened.add(it) }
    } catch (error: Exception) { close(); throw error }
    private val bytes = open { File(directory, "${prefix}_bytes_$id.bin").outputStream().buffered() }
    private val raw = open { File(directory, "${prefix}_nmea_$id.csv").bufferedWriter() }
    private val fixes = open { File(directory, "${prefix}_fixes_$id.csv").bufferedWriter() }
    private val chunks = open { File(directory, "${prefix}_chunks_$id.csv").bufferedWriter() }
    private val events = open { File(directory, "${prefix}_connections_$id.csv").bufferedWriter() }
    private var offset = 0L
    init {
        try {
        SurveyConfig.writeSessionMetadata(directory, "${prefix}_$id", settings, id, capturedUtc, "GNSS_RAW_DIAGNOSTIC_$prefix")
        raw.write("received_utc_ms,received_elapsed_ms,nmea_sentence\n")
        fixes.write(RtkCoordinateExport.header)
        chunks.write("received_utc_ms,received_elapsed_ms,byte_offset,byte_count\n")
        events.write("received_utc_ms,received_elapsed_ms,state,detail\n")
        raw.flush(); fixes.flush(); chunks.flush(); events.flush()
        } catch (error: Exception) { close(); throw error }
    }
    fun bytes(buffer: ByteArray, count: Int) {
        bytes.write(buffer, 0, count); bytes.flush()
        chunks.write(Csv.row(listOf(System.currentTimeMillis().toString(), SystemClock.elapsedRealtime().toString(), offset.toString(), count.toString())))
        chunks.flush(); offset += count
    }
    fun sentence(sentence: String, utc: Long, elapsed: Long) {
        raw.write(Csv.row(listOf(utc.toString(), elapsed.toString(), sentence))); raw.flush()
    }
    fun fix(utc: Long, fields: List<String>) {
        fixes.write(RtkCoordinateExport.row(utc, fields, settings)); fixes.flush()
    }
    fun event(state: String, detail: String) {
        events.write(Csv.row(listOf(System.currentTimeMillis().toString(), SystemClock.elapsedRealtime().toString(), state, detail.replace('\n', ' ').replace('\r', ' ')))); events.flush()
    }
    override fun close() {
        opened.forEach { try { it.close() } catch (_: Exception) {} }
    }
}

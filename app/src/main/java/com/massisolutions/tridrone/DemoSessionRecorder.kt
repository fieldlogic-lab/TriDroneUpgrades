package com.massisolutions.tridrone

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.util.Locale
import kotlin.math.sin

/**
 * Synthetic training provider. Never opens hardware or writes to the live surveys directory.
 * One row per observation; CSV is explicitly marked DEMO_SYNTHETIC.
 */
class DemoSessionRecorder(private val context: Context) {
    private var writer: BufferedWriter? = null
    private var activeFile: File? = null
    var count: Int = 0
        private set

    val isRecording: Boolean get() = writer != null
    val file: File? get() = activeFile

    fun start(): File {
        check(!isRecording) { "Demo session already active" }
        val directory = File(context.filesDir, "demo_surveys").apply { mkdirs() }
        val target = File(directory, "DEMO_${System.currentTimeMillis()}.csv")
        val output = target.bufferedWriter()
        try {
            output.write("event_utc_ms,latitude_deg,longitude_deg,simulated_depth_m,source,usage\n")
            output.flush()
        } catch (e: Exception) {
            output.close()
            target.delete()
            throw e
        }
        writer = output
        activeFile = target
        count = 0
        return target
    }

    fun sample(timestamp: Long = System.currentTimeMillis()) {
        val output = checkNotNull(writer) { "Demo session not started" }
        val lat = 40.8000 + count * 0.000002
        val lon = -73.0500 + sin(count * 0.08) * 0.00005
        val depth = 2.5 + sin(count * 0.12) * 0.4
        output.write(String.format(Locale.US, "%d,%.9f,%.9f,%.3f,DEMO_SYNTHETIC,NOT_FOR_SURVEY\n", timestamp, lat, lon, depth))
        output.flush()
        count++
    }

    fun stop() {
        val output = writer
        writer = null
        output?.close()
    }
}

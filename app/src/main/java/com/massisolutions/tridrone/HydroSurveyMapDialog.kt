package com.massisolutions.tridrone

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/** Optional visualization: all disk work is off the UI/acquisition threads and failures stay here. */
class HydroSurveyMapDialog(private val activity: Activity, private val savedSurvey: File? = null) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val map = SurveyMapView(activity)
    private val status = TextView(activity)
    private val mode = TextView(activity)
    private val stop = Button(activity)
    private var closed = false
    private var readerFile: File? = null
    private var reader: HydroSurveyMapReader? = null
    private val dialog: AlertDialog

    init {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 8, 12, 8) }
        status.textSize = 14f
        status.text = "Loading recorded survey…"
        box.addView(status)
        box.addView(TextView(activity).apply {
            text = "Blue line: GNSS track • Blue dot: live vessel\nGreen circle: FIX • Amber triangle: FLOAT • Purple square: other\nDepth: provisional below transducer, m / ftUS; no bottom elevations."
            textSize = 12f
        })
        val controls = LinearLayout(activity)
        fun control(label: String, action: () -> Unit) = Button(activity).apply {
            text = label; minHeight = (48 * resources.displayMetrics.density).toInt()
            setOnClickListener { action() }
            controls.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        control("Fit Survey") {
            if (!map.fitSurvey()) mode.text = "Free Pan • no recorded coordinates to fit"
        }
        control("Follow Vessel") { map.followVessel() }
        control("Free Pan") { map.freePan() }
        box.addView(controls)
        mode.text = "Free Pan • drag / pinch to navigate; tap a sounding"
        box.addView(mode)
        map.contentDescription = "Hydrographic survey map. Drag or pinch suspends Follow Vessel. Tap a sounding for details."
        map.onModeChanged = {
            mode.text = if (map.viewport.mode == SurveyViewport.Mode.FOLLOW_VESSEL)
                "Follow Vessel • waiting for / following fresh GNSS" else "Free Pan • drag / pinch; tap a sounding"
        }
        map.onSoundingSelected = { sounding ->
            val details = TextView(activity).apply { text = sounding.details(); setPadding(16, 12, 16, 12); setTextIsSelectable(true) }
            AlertDialog.Builder(activity).setTitle("Recorded sounding ${sounding.recordNumber}")
                .setView(ScrollView(activity).apply { addView(details) }).setPositiveButton("Close", null).show()
        }
        map.onRenderError = { mode.text = "Map rendering failed; acquisition remains independent" }
        box.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))
        stop.text = "STOP LIVE RECORDING"
        stop.setOnClickListener { HydroLive.stop(); stop.visibility = View.GONE }
        stop.visibility = if (HydroLive.recording) View.VISIBLE else View.GONE
        box.addView(stop)
        dialog = AlertDialog.Builder(activity).setTitle(if (savedSurvey == null) "Live Survey Map" else "Saved Survey Map")
            .setView(box).setPositiveButton("Close", null).create()
        dialog.setOnDismissListener { closed = true; handler.removeCallbacks(tick); worker.shutdownNow() }
        dialog.setOnShowListener {
            dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            handler.post(tick)
        }
    }

    fun show() = dialog.show()

    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            if (activity.isFinishing || activity.isDestroyed) { dialog.dismiss(); return }
            worker.execute {
                try {
                    val live = HydroLive.mapSnapshot(SystemClock.elapsedRealtimeNanos())
                    val selectedFile = savedSurvey ?: live.file ?: lastRecordedFile()
                    if (selectedFile != readerFile) {
                        readerFile = selectedFile
                        reader = selectedFile?.let { HydroSurveyMapReader(it) }
                    }
                    val data = reader?.refresh() ?: HydroMapData()
                    handler.post {
                        if (!closed) {
                            map.showSurvey(data, if (savedSurvey == null) live.position else null)
                            status.text = "${selectedFile?.name ?: "No recorded survey yet"}\n" +
                                "${data.track.size} track vertices • ${data.soundings.size} soundings • ${data.omittedEvents} events without plottable sounding\n" +
                                if (savedSurvey != null) "Saved records • tap markers to inspect" else
                                    "${if (live.position == null) "GNSS stale / disconnected" else gnssQualityLabel(live.quality)} • ${if (HydroLive.recording) "RECORDING" else "Stopped"}"
                            stop.visibility = if (HydroLive.recording) View.VISIBLE else View.GONE
                            handler.postDelayed(this, 500)
                        }
                    }
                } catch (_: Exception) {
                    handler.post {
                        if (!closed) {
                            status.text = "Map read unavailable; retained display. Recording is independent."
                            handler.postDelayed(this, 1000)
                        }
                    }
                }
            }
        }
    }

    private fun lastRecordedFile(): File? {
        val folder = File(activity.filesDir, "hydro_surveys")
        val path = activity.getSharedPreferences("hydro_status", Activity.MODE_PRIVATE).getString("file", null)
        return path?.let { File(it) }?.takeIf { it.isFile && it.parentFile?.canonicalFile == folder.canonicalFile }
            ?: folder.listFiles { f -> f.name.startsWith("hydro_") && f.extension == "csv" && !f.name.endsWith(".gnss.csv") }
                ?.maxByOrNull { it.name }
    }
}

package com.massisolutions.tridrone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.widget.*
import java.io.File
import java.util.Locale
import java.text.DateFormat
import java.util.Date
import android.graphics.Typeface

class MainActivity : Activity() {
    private lateinit var rtkStatus: TextView
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var sessions: TextView
    private lateinit var precision: TextView
    private lateinit var timing: TextView
    private lateinit var lastFix: TextView
    private lateinit var crsSpinner: Spinner
    private val crsLabels = arrayOf("NY Long Island NAD83(2011) — EPSG:6539", "WGS84 geographic — EPSG:4326")
    private val crsCodes = arrayOf("EPSG:6539", "EPSG:4326")
    private val handler = Handler(Looper.getMainLooper())
    private val requestCode = 31
    private val refresh = object : Runnable {
        override fun run() {
            updateDisplay()
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 44, 32, 36)
        }
        fun label(text: String, size: Float): TextView = TextView(this).apply {
            this.text = text
            textSize = size
            setPadding(0, 10, 0, 10)
        }
        layout.addView(label("TRIDRONE  |  SURVEY DASHBOARD", 23f).apply { setTypeface(null, Typeface.BOLD) })
        layout.addView(label("GNSS acquisition  •  Offline  •  Field mode", 14f))
        status = label("Checking logger status...", 19f)
        details = label("Waiting for GPS observations", 17f)
        sessions = label("No sessions yet", 15f)
        layout.addView(Button(this).apply { text = "SURVEY SETTINGS / COORDINATE SYSTEMS"; setOnClickListener { showSurveySettings() } })
        layout.addView(label("HORIZONTAL COORDINATE SYSTEM (CONFIGURATION)", 15f))
        crsSpinner = Spinner(this)
        crsSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, crsLabels)
        val settings = getSharedPreferences("survey_settings", MODE_PRIVATE)
        crsSpinner.setSelection(if (SurveyConfig.load(this).horizontal == "EPSG:4326") 1 else 0)
        crsSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val current = SurveyConfig.load(this@MainActivity)
                SurveyConfig.save(this@MainActivity, current.copy(horizontal = crsCodes[position]))
                updateDisplay()
            }
        }
        layout.addView(crsSpinner)
        layout.addView(label("VERTICAL DATUM: NAVD88 (EPSG:6360) — elevations pending control", 13f))
        layout.addView(label("EXTERNAL RTK — REACH RS2+", 15f))
        rtkStatus = label("RTK: disconnected", 16f)
        layout.addView(rtkStatus)
        layout.addView(Button(this).apply { text = "CONNECT PAIRED RS2+ (BLUETOOTH NMEA)"; setOnClickListener { connectRtk() } })
        layout.addView(Button(this).apply { text = "DISCONNECT RTK"; setOnClickListener { stopService(Intent(this@MainActivity, RtkService::class.java)) } })
        layout.addView(label("RTK stream saves separate raw NMEA and GGA CSV files. Only quality 4 is RTK FIX; 5 is FLOAT. Emlid Flow configures corrections. No automatic fallback to phone GPS.", 13f))
        layout.addView(label("LIVE ACQUISITION", 14f))
        status.setTypeface(null, Typeface.BOLD)
        layout.addView(status)
        timing = label("Survey not started", 17f)
        layout.addView(timing)
        precision = label("Horizontal accuracy: awaiting fix", 19f).apply { setTypeface(null, Typeface.BOLD) }
        layout.addView(precision)
        lastFix = label("Last fix: none", 14f)
        layout.addView(lastFix)
        layout.addView(label("LIVE POSITION", 14f))
        layout.addView(details)
        layout.addView(Button(this).apply {
            text = "START RECORDING"
            setOnClickListener { startSurvey() }
        })
        layout.addView(Button(this).apply {
            text = "STOP RECORDING"
            setOnClickListener {
                stopService(Intent(this@MainActivity, SurveyService::class.java))
                status.text = "Stop requested"
                updateDisplay()
            }
        })
        layout.addView(Button(this).apply {
            text = "EXPORT LATEST CSV"
            setOnClickListener { exportLatest() }
        })
        layout.addView(Button(this).apply { text = "OPEN SURVEY MAP"; setOnClickListener { showSurveyMap() } })
        layout.addView(Button(this).apply { text = "VIEW SAVED SURVEYS AND POINTS"; setOnClickListener { showSavedSurveys() } })
        layout.addView(label("Recent session", 18f))
        layout.addView(sessions)
        layout.addView(label("EPSG:6539 projection is not implemented. Selection is a display preference only; CSV currently stores raw latitude/longitude. Phone GNSS is not survey-grade.", 13f))
        root.addView(layout)
        setContentView(root)
    }
    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }
    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
    private fun showSurveySettings() {
        val current = SurveyConfig.load(this)
        val container = ScrollView(this)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 20, 28, 20) }
        fun heading(t: String) { form.addView(TextView(this).apply { text = t; textSize = 16f; setPadding(0, 14, 0, 8) }) }
        heading("Project name (optional)")
        val project = EditText(this).apply { setSingleLine(); setText(current.project); hint = "Survey project" }
        form.addView(project)
        heading("Horizontal output coordinate system")
        val horizontal = Spinner(this)
        val options = listOf("EPSG:6539 — NAD83(2011) NY Long Island ftUS", "EPSG:4326 — WGS 84 geographic")
        horizontal.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        horizontal.setSelection(if (current.horizontal == "EPSG:4326") 1 else 0)
        form.addView(horizontal)
        heading("Favorites")
        val favorite = CheckBox(this).apply { text = "Favorite selected horizontal CRS"; isChecked = current.horizontal in current.favorites }
        form.addView(favorite)
        heading("Vertical reference")
        form.addView(TextView(this).apply { text = "EPSG:6360 — NAVD88, US survey feet (configuration only; no elevations without vertical control)" })
        heading("Display units")
        val units = Spinner(this)
        units.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("US survey feet", "Meters"))
        units.setSelection(if (current.units == "m") 1 else 0)
        form.addView(units)
        heading("Source geodetic datum (verify receiver output)")
        val source = Spinner(this)
        source.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("UNKNOWN — preserve raw, no projected output", "EPSG:6318 — NAD83(2011), independently verified"))
        source.setSelection(if (current.source == "EPSG:6318") 1 else 0)
        form.addView(source)
        form.addView(TextView(this).apply {
            text = "Selecting EPSG:6318 is an assertion about the incoming receiver coordinates, NOT an automatic transformation. Verify receiver configuration, datum realization/epoch and independent control. Android phone GPS remains unverified. No WGS84-to-NAD83(2011) datum operation is installed."
            setPadding(0, 20, 0, 12)
        })
        container.addView(form)
        android.app.AlertDialog.Builder(this).setTitle("Survey settings").setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val horizontalCode = if (horizontal.selectedItemPosition == 0) "EPSG:6539" else "EPSG:4326"
                val favorites = current.favorites.toMutableSet()
                if (favorite.isChecked) favorites.add(horizontalCode) else favorites.remove(horizontalCode)
                SurveyConfig.save(this, SurveySettings(project.text.toString().trim(), horizontalCode,
                    "EPSG:6360", if (units.selectedItemPosition == 0) "usft" else "m",
                    if (source.selectedItemPosition == 0) "UNKNOWN" else "EPSG:6318", favorites))
                crsSpinner.setSelection(horizontal.selectedItemPosition)
                Toast.makeText(this, "Settings saved; new sessions capture metadata", Toast.LENGTH_LONG).show()
            }.show()
    }
    private fun connectRtk() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 41)
            return
        }
        try {
            val manager = getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            val adapter = manager.adapter
            if (adapter == null || !adapter.isEnabled) {
                Toast.makeText(this, "Enable Bluetooth and pair Reach RS2+ in Android settings", Toast.LENGTH_LONG).show()
                return
            }
            val devices = adapter.bondedDevices.toList().sortedBy { it.name ?: it.address }
            if (devices.isEmpty()) {
                Toast.makeText(this, "No paired Bluetooth devices. Pair the RS2+ first.", Toast.LENGTH_LONG).show()
                return
            }
            val labels = devices.map { (it.name ?: "Unknown") + " (" + it.address + ")" }.toTypedArray()
            android.app.AlertDialog.Builder(this).setTitle("Select paired RTK receiver")
                .setItems(labels) { _, i ->
                    val intent = Intent(this, RtkService::class.java).putExtra("address", devices[i].address)
                    try { startForegroundService(intent) }
                    catch (e: Exception) { Toast.makeText(this, "RTK start failed: " + e.message, Toast.LENGTH_LONG).show() }
                }.setNegativeButton("Cancel", null).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Bluetooth error: " + e.message, Toast.LENGTH_LONG).show()
        }
    }
    private fun latest(): File? = File(filesDir, "surveys").listFiles { f -> f.isFile && f.extension == "csv" }?.maxByOrNull { it.lastModified() }
    private fun updateDisplay() {
        val rp = getSharedPreferences("rtk_status", MODE_PRIVATE)
        val rtkAge = (System.currentTimeMillis() - rp.getLong("last_fix_ms", 0L)) / 1000
        val quality = rp.getString("quality", "?") ?: "?"
        val fixLabel = when(quality) { "4" -> "RTK FIX"; "5" -> "RTK FLOAT"; "2" -> "DGPS"; "1" -> "SINGLE"; "0" -> "INVALID"; else -> "UNKNOWN" }
        rtkStatus.text = "RTK: " + rp.getString("state", "disconnected") + " • " + rp.getString("detail", "") +
            "\\nFix: " + fixLabel + " • Satellites: " + rp.getString("satellites", "—") +
            " • NMEA: " + rp.getInt("sentences", 0) + " • GGA: " + rp.getInt("fixes", 0) +
            (if (rp.getLong("last_fix_ms", 0L) > 0) " • Last GGA: " + rtkAge + "s ago" else "")
        val p = getSharedPreferences("logger_status", MODE_PRIVATE)
        val state = p.getString("state", "idle") ?: "idle"
        val count = p.getInt("points", 0)
        val started = p.getLong("started_at_ms", 0L)
        val active = state == "recording" || state == "waiting"
        val ended = if (active) System.currentTimeMillis() else p.getLong("stopped_at_ms", System.currentTimeMillis())
        val seconds = if (started > 0L) ((ended - started).coerceAtLeast(0L) / 1000L) else 0L
        timing.text = if (started > 0L) "Started " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(started)) +
            "  |  Elapsed " + String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            else "Survey not started"
        val fix = p.getLong("last_fix_ms", 0L)
        val age = if (fix > 0L) (System.currentTimeMillis() - fix).coerceAtLeast(0L) / 1000L else -1L
        lastFix.text = if (age < 0) "Last fix: none" else "Last fix: " + age + " seconds ago" + if (age > 10L) " (STALE)" else ""
        status.text = when(state) {
            "recording" -> "RECORDING • $count GPS points"
            "waiting" -> "WAITING FOR GPS FIX • $count points"
            "error" -> "ERROR: " + p.getString("error", "unknown")
            else -> "Not recording"
        }
        val lat = p.getString("lat", null)
        val lon = p.getString("lon", null)
        val accuracy = p.getFloat("accuracy", -1f)
        precision.text = if (accuracy < 0f) "Horizontal accuracy: unavailable" else
            "Horizontal accuracy: " + String.format(Locale.US, "%.1f m  |  %.1f ft", accuracy, accuracy * 3.280839895) +
            "  (phone estimate)"
        val selectedCrs = SurveyConfig.load(this).horizontal
        details.text = if (lat != null && lon != null) {
            "Latitude: $lat\nLongitude: $lon\nHorizontal accuracy: " +
                (if (accuracy >= 0) String.format(Locale.US, "%.1f m", accuracy) else "Unknown") +
                "\nSession points: $count"
        } else "No GPS fix yet. Try outdoors when convenient.\nSession points: $count"
        val file = latest()
        sessions.text = if (file == null) "No CSV session found" else
            "${file.name}\n${file.length()} bytes"
    }
    private fun showSurveyMap() {
        val files = File(filesDir, "surveys").listFiles { f -> f.isFile && f.extension == "csv" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        val tracks = files.map { file ->
            try {
                file.useLines { lines ->
                    lines.drop(1).mapNotNull { line ->
                        val cells = line.split(",")
                        val lat = cells.getOrNull(2)?.toDoubleOrNull()
                        val lon = cells.getOrNull(3)?.toDoubleOrNull()
                        if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) Pair(lat, lon) else null
                    }.toList()
                }
            } catch (_: Exception) { emptyList<Pair<Double, Double>>() }
        }
        val view = SurveyMapView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val info = TextView(this).apply {
            text = "All saved tracks: " + files.size + "  |  Recorded points: " + tracks.sumOf { it.size } +
                "\\nBlue: latest track  •  Gray: earlier tracks  •  Green: live position"
            textSize = 14f
            setPadding(20, 16, 20, 12)
        }
        box.addView(info)
        box.addView(view, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.55f).toInt()))
        view.tracks = tracks
        fun refreshMap() {
            val prefs = getSharedPreferences("logger_status", MODE_PRIVATE)
            val lat = prefs.getString("lat", null)?.toDoubleOrNull()
            val lon = prefs.getString("lon", null)?.toDoubleOrNull()
            val age = System.currentTimeMillis() - prefs.getLong("last_fix_ms", 0L)
            view.current = if (lat != null && lon != null && age in 0..10000) Pair(lat, lon) else null
        }
        refreshMap()
        val dialog = android.app.AlertDialog.Builder(this).setTitle("Survey map")
            .setView(box).setPositiveButton("Close", null).create()
        val ticker = object : Runnable {
            override fun run() {
                if (dialog.isShowing) {
                    refreshMap()
                    handler.postDelayed(this, 1000)
                }
            }
        }
        dialog.setOnShowListener { handler.post(ticker) }
        dialog.setOnDismissListener { handler.removeCallbacks(ticker) }
        dialog.show()
    }
    private fun showSavedSurveys() {
        val files = File(filesDir, "surveys").listFiles { f -> f.isFile && f.extension == "csv" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            Toast.makeText(this, "No saved surveys", Toast.LENGTH_LONG).show()
            return
        }
        val labels = files.map { file ->
            val count = try { file.useLines { it.count() - 1 }.coerceAtLeast(0) } catch (_: Exception) { 0 }
            file.name + "  |  " + count + " points"
        }.toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle("Saved surveys")
            .setItems(labels) { _, index -> showSavedPoints(files[index]) }
            .setNegativeButton("Close", null).show()
    }
    private fun showSavedPoints(file: File) {
        try {
            val lines = file.readLines()
            val body = lines.drop(1).mapIndexed { i, row ->
                val values = row.split(",")
                val time = values.getOrNull(0)?.toLongOrNull()?.let {
                    java.text.DateFormat.getTimeInstance().format(java.util.Date(it))
                } ?: "unknown"
                "#"+(i+1)+"  "+time+"\\nLat: "+(values.getOrNull(2) ?: "—")+
                    "  Lon: "+(values.getOrNull(3) ?: "—")+
                    "\\nAccuracy: "+(values.getOrNull(4) ?: "—")+" m"
            }.joinToString("\\n\\n")
            val view = ScrollView(this)
            view.addView(TextView(this).apply {
                textSize = 15f
                setPadding(28, 20, 28, 20)
                text = file.name + "\\nRecorded observations: " + lines.drop(1).size + "\\n\\n" + body
                setTextIsSelectable(true)
            })
            android.app.AlertDialog.Builder(this).setTitle("Recorded GPS points")
                .setView(view).setPositiveButton("Close", null)
                .setNeutralButton("Export this survey") { _, _ -> exportFile(file) }.show()
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open CSV: "+e.message, Toast.LENGTH_LONG).show()
        }
    }
    private fun startSurvey() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), requestCode)
            return
        }
        try {
            startForegroundService(Intent(this, SurveyService::class.java))
            status.text = "Starting logger..."
        } catch (e: Exception) {
            status.text = "Unable to start: ${e.message}"
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 32)
        }
    }
    private fun exportLatest() {
        val file = latest()
        if (file == null) {
            Toast.makeText(this, "No survey CSV to export", Toast.LENGTH_LONG).show()
            return
        }
        exportFile(file)
    }
    private fun exportFile(file: File) {
        try {
            val shareCopy = File(cacheDir, file.name)
            file.inputStream().use { input -> shareCopy.outputStream().use { output -> input.copyTo(output) } }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", shareCopy)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Export TriDrone CSV"))
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 41 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) connectRtk()
        if (code == requestCode) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startSurvey()
            else status.text = "Precise location permission required"
        }
    }
}

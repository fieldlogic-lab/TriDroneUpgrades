package com.massisolutions.tridrone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.*
import java.io.File
import java.util.Locale

/** Presentation only. Receiver services own acquisition; connection is not survey readiness. */
class MainActivity : Activity() {
    private val sessionsRepository by lazy { SessionRepository(filesDir) }
    private val handler = Handler(Looper.getMainLooper())
    private val dialogRefreshes = mutableListOf<Runnable>()
    private val navy = Color.rgb(12, 24, 39)
    private val panel = Color.rgb(24, 40, 58)
    private val muted = Color.rgb(192, 209, 221)
    private val cyan = Color.rgb(89, 208, 228)
    private lateinit var gnssLamp: Button
    private lateinit var depthLamp: Button
    private lateinit var collectionLamp: Button
    private lateinit var precision: TextView
    private lateinit var gnssStatus: TextView
    private lateinit var depthStatus: TextView
    private lateinit var recordingStatus: TextView
    private lateinit var settingsStatus: TextView
    private val refresh = object : Runnable {
        override fun run() { updateDisplay(); handler.postDelayed(this, 1000) }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 16f, color: Int = Color.WHITE) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(dp(4), dp(10), dp(4), dp(10))
    }
    private fun action(value: String, click: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; textSize = 16f
        minHeight = dp(52); minimumHeight = dp(52)
        setOnClickListener { click() }
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun card(title: String) = column().apply {
        setPadding(dp(12), dp(10), dp(12), dp(14)); setBackgroundColor(panel)
        addView(text(title, 20f).apply { setTypeface(null, Typeface.BOLD) })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = column().apply { setBackgroundColor(navy) }
        root.addView(text("TRIDRONE", 26f).apply { setTypeface(null, Typeface.BOLD); setPadding(dp(16), dp(18), dp(16), dp(8)) })
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(panel) }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val host = column().apply { setPadding(dp(14), dp(16), dp(14), dp(20)) }
        scroll.addView(host)
        val survey = column()
        val sessions = column()
        val equipment = column()
        val settings = column()
        val pages = listOf(survey, sessions, equipment, settings)
        val buttons = mutableListOf<Button>()
        fun selectPage(index: Int, focus: View? = null) {
            host.removeAllViews()
            val selected = pages[index]
            (selected.parent as? android.view.ViewGroup)?.removeView(selected)
            host.addView(selected)
            buttons.forEachIndexed { i, button ->
                button.setTextColor(if (i == index) cyan else muted)
                button.setBackgroundColor(if (i == index) navy else panel)
            }
            scroll.post { scroll.smoothScrollTo(0, focus?.top ?: 0); focus?.requestFocus() }
        }
        listOf("Survey", "Sessions", "Equipment", "Project Setup").forEachIndexed { index, title ->
            val button = action(title) { selectPage(index) }.apply {
                textSize = 12f; minWidth = 0; minimumWidth = 0; setPadding(0, dp(8), 0, dp(8))
            }
            buttons.add(button); tabs.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val gnssCard = card("GNSS · Reach RS2+")
        gnssStatus = text("Connect the receiver to continue.", 16f, muted)
        gnssCard.addView(gnssStatus)
        gnssCard.addView(action("Connect via Wi-Fi NMEA") { showWifiRtkSetup() })
        gnssCard.addView(action("Use paired Bluetooth instead") { connectRtk() })
        gnssCard.addView(action("Disconnect GNSS") {
            stopService(Intent(this, WifiRtkService::class.java)); stopService(Intent(this, RtkService::class.java))
        })
        gnssCard.addView(text("Use the RS2+ NMEA TCP server on a shared Wi-Fi network. The receiver must output GGA and GST. A network link alone does not validate a fix.", 14f, muted))
        gnssCard.addView(action("Open Android Wi-Fi settings") { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) })
        gnssCard.addView(action("GNSS diagnostic track") { showLiveRtkMap() })
        equipment.addView(gnssCard)
        val depthCard = card("DEPTH · HydroLite Plus")
        depthStatus = text("No verified real depth stream.", 16f, muted)
        depthCard.addView(depthStatus)
        depthCard.addView(action("Pair / inspect HydroLite") { showSounderDiagnostics() })
        depthCard.addView(text("Confirm the supported interface with the hardware supplier. Paired Bluetooth and raw bytes do not prove valid depth observations.", 14f, muted))
        equipment.addView(depthCard)
        val collectionCard = card("Collection preflight")
        recordingStatus = text("Recording unavailable.", 16f, muted)
        collectionCard.addView(recordingStatus)
        equipment.addView(collectionCard)
        fun lamp(title: String, click: () -> Unit) = action(title, click).apply {
            textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
            minHeight = dp(92); minimumHeight = dp(92)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
        }
        gnssLamp = lamp("GNSS · DISCONNECTED") { selectPage(2, gnssCard) }
        depthLamp = lamp("DEPTH · DISCONNECTED") { selectPage(2, depthCard) }
        collectionLamp = lamp("COLLECTION · UNAVAILABLE") { selectPage(2, collectionCard) }
        survey.addView(gnssLamp); survey.addView(depthLamp); survey.addView(collectionLamp)
        precision = text("H PRECISION  UNAVAILABLE\nV PRECISION  UNAVAILABLE", 22f).apply {
            setTypeface(null, Typeface.BOLD); setBackgroundColor(panel); setPadding(dp(14), dp(18), dp(14), dp(18))
        }
        survey.addView(precision)
        survey.addView(text("Emlid RS2+ receiver-reported GST · 1σ\nNot independently verified survey accuracy.", 14f, muted))
        sessions.addView(text("Saved records", 23f))
        sessions.addView(action("Review saved observations") { showSavedSurveys() })
        sessions.addView(action("Export raw acquisition files") { showRawFiles() })
        if (sessionsRepository.list(SessionRepository.Kind.DEMO).isNotEmpty()) {
            sessions.addView(action("Review historical synthetic test records") { showSavedSurveys(SessionRepository.Kind.DEMO) })
        }
        sessions.addView(text("Existing records retain their original source and limitations. GNSS acquisition is not synchronized bathymetric collection.", 14f, muted))
        settings.addView(text("Project Setup", 23f))
        settingsStatus = text("", 16f, muted); settings.addView(settingsStatus)
        settings.addView(action("Edit job, next session & coordinate systems") { showSurveySettings() })
        settings.addView(action("Check for app updates") { AppUpdater.open(this) })
        root.addView(tabs); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root); selectPage(0); updateDisplay()
    }
    override fun onResume() {
        super.onResume(); handler.removeCallbacks(refresh); handler.post(refresh)
        dialogRefreshes.forEach { handler.removeCallbacks(it); handler.post(it) }
    }
    override fun onPause() {
        handler.removeCallbacks(refresh); dialogRefreshes.forEach { handler.removeCallbacks(it) }; super.onPause()
    }
    private fun updateDisplay() {
        val prefs = getSharedPreferences("rtk_status", MODE_PRIVATE)
        val sounder = getSharedPreferences("sounder_diagnostics", MODE_PRIVATE)
        val storedState = prefs.getString("state", "disconnected").orEmpty()
        val state = if (GnssConnectionOwner.active()) storedState else if (storedState == "error") "error" else "disconnected"
        val now = SystemClock.elapsedRealtime()
        val display = OperatorReadiness.evaluate(OperatorReadiness.State(
            receiverState = state,
            fixQuality = prefs.getString("quality", null),
            fixAgeMs = ConnectionPolicy.age(now, prefs.getLong("last_fix_elapsed_ms", 0L)),
            sounderState = if (SounderDiagnosticService.active()) sounder.getString("state", "disconnected").orEmpty()
                else if (sounder.getString("state", "") == "error") "error" else "disconnected",
            horizontalMeters = prefs.getString("h_sigma_m", null)?.toDoubleOrNull(),
            verticalMeters = prefs.getString("v_sigma_m", null)?.toDoubleOrNull(),
            gstAgeMs = ConnectionPolicy.age(now, prefs.getLong("gst_elapsed_ms", 0L)),
            correctionAgeSeconds = prefs.getString("correction_age_s", null)?.toDoubleOrNull(),
            precisionEpochAligned = NmeaEpoch.aligned(prefs.getString("gga_epoch", null)?.toDoubleOrNull(), prefs.getString("gst_epoch", null)?.toDoubleOrNull())
        ))
        fun render(button: Button, title: String, lamp: OperatorReadiness.Lamp) {
            button.text = title
            val color = when (lamp) {
                OperatorReadiness.Lamp.RED -> Color.rgb(145, 35, 35)
                OperatorReadiness.Lamp.AMBER -> Color.rgb(145, 91, 8)
                OperatorReadiness.Lamp.GREEN -> Color.rgb(12, 111, 53)
            }
            button.setBackgroundColor(color); button.elevation = dp(if (lamp == OperatorReadiness.Lamp.GREEN) 10 else 6).toFloat()
            button.contentDescription = title
        }
        render(gnssLamp, "GNSS · ${display.gnssLabel}", display.gnssLamp)
        render(depthLamp, "DEPTH · ${display.depthLabel}", display.depthLamp)
        render(collectionLamp, "COLLECTION · ${display.collectionLabel}", display.collectionLamp)
        val config = SurveyConfig.load(this)
        precision.text = "H PRECISION  ${DisplayUnits.formatPrecision(display.horizontalMeters, config.units)}\n" +
            "V PRECISION  ${DisplayUnits.formatPrecision(display.verticalMeters, config.units)}"
        gnssStatus.text = display.gnss + "\n" + prefs.getString("detail", "").orEmpty() +
            "\nNMEA: ${prefs.getInt("sentences", 0)} · GGA: ${prefs.getInt("fixes", 0)}"
        depthStatus.text = display.sounder
        recordingStatus.text = display.recording + "\nNext session: ${config.sessionName.ifBlank { "Not named" }} · set in Project Setup"
        settingsStatus.text = "Job: ${config.project.ifBlank { "Not named" }}\nNext session: ${config.sessionName.ifBlank { "Not named" }}\n" +
            "Display units: ${DisplayUnits.label(config.units)}\nHorizontal: ${config.horizontal}\nVertical: ${config.vertical}\nSource datum: ${config.source}\n\n" +
            "Display units do not change the selected CRS units or raw observations. Setup changes apply to future sessions; existing snapshots stay unchanged.\n" +
            "Projected coordinates and bottom elevations require verified transformations and vertical control."
    }
    private fun showSurveySettings() {
        val current = SurveyConfig.load(this)
        val container = ScrollView(this)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 20, 28, 20) }
        fun heading(t: String) { form.addView(TextView(this).apply { text = t; textSize = 16f; setPadding(0, dp(12), 0, dp(8)) }) }
        fun selection(title: String, choices: List<Pair<String, String>>, selected: String): Pair<Spinner, List<Pair<String, String>>> {
            heading(title)
            // Unknown historical settings remain explicit and unchanged until the owner selects a supported option.
            val options = if (choices.any { it.first == selected }) choices else choices + (selected to "$selected — stored value; support unverified")
            val spinner = Spinner(this).apply {
                minimumHeight = dp(48)
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options.map { it.second })
                setSelection(options.indexOfFirst { it.first == selected })
            }
            form.addView(spinner)
            return spinner to options
        }
        heading("Job / project name")
        val project = EditText(this).apply {
            setSingleLine(); setText(current.project); hint = "Job or project"; minimumHeight = dp(48)
            filters = arrayOf(android.text.InputFilter.LengthFilter(120))
        }
        form.addView(project)
        heading("Next collection session name — separate from job")
        val session = EditText(this).apply {
            setSingleLine(); setText(current.sessionName); hint = "e.g. Shoreline transect 01"; minimumHeight = dp(48)
            filters = arrayOf(android.text.InputFilter.LengthFilter(120))
        }
        form.addView(session)
        form.addView(TextView(this).apply {
            text = "Names the next acquisition record, not the active session. Current recording is receiver diagnostics; synchronized GNSS/depth collection remains unavailable."
        })
        val (horizontal, horizontalOptions) = selection("Horizontal coordinate system", listOf(
            "EPSG:6539" to "EPSG:6539 — NAD83(2011) / New York Long Island, US survey feet",
            "EPSG:4326" to "EPSG:4326 — WGS 84 geographic, degrees"
        ), current.horizontal)
        heading("Favorites")
        val favorite = CheckBox(this).apply { text = "Favorite selected horizontal CRS"; isChecked = current.horizontal in current.favorites }
        form.addView(favorite)
        val (vertical, verticalOptions) = selection("Vertical coordinate system / datum", listOf(
            "EPSG:6360" to "EPSG:6360 — NAVD88 height, US survey feet (configuration only)"
        ), current.vertical)
        val unitChoices = listOf("usft" to "US survey feet (ftUS) — 1200/3937 meter", "ift" to "International feet (ft) — 0.3048 meter", "m" to "Meters (m)") +
            if (current.units == "ft") listOf("ft" to "International feet (ft) — legacy setting, 0.3048 meter") else emptyList()
        val (units, unitOptions) = selection("Precision display units — separate from CRS units", unitChoices, current.units)
        val (source, sourceOptions) = selection("Source geodetic datum — verify receiver output", listOf(
            "UNKNOWN" to "UNKNOWN — preserve raw, no projected output",
            "EPSG:6318" to "EPSG:6318 — NAD83(2011), owner verified"
        ), current.source)
        form.addView(TextView(this).apply {
            text = "Receiver GST is retained in native meters. Display conversions do not alter observations or CRS units. Selecting a CRS/datum does not validate a transformation or supply vertical corrections. Verify receiver datum, epoch and independent control. NAVD88 bottom elevations remain unavailable."
            setPadding(0, 20, 0, 12)
        })
        container.addView(form)
        android.app.AlertDialog.Builder(this).setTitle("Project Setup").setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val horizontalCode = horizontalOptions[horizontal.selectedItemPosition].first
                val favorites = current.favorites.toMutableSet()
                if (favorite.isChecked) favorites.add(horizontalCode) else favorites.remove(horizontalCode)
                SurveyConfig.save(this, current.copy(project = project.text.toString(), sessionName = session.text.toString(),
                    horizontal = horizontalCode, vertical = verticalOptions[vertical.selectedItemPosition].first,
                    units = unitOptions[units.selectedItemPosition].first, source = sourceOptions[source.selectedItemPosition].first,
                    favorites = favorites))
                updateDisplay()
                Toast.makeText(this, "Project Setup saved for future sessions; existing records unchanged", Toast.LENGTH_LONG).show()
            }.show()
    }
    private fun showWifiRtkSetup() {
        val prefs = getSharedPreferences("wifi_rtk_settings", MODE_PRIVATE)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 12, 28, 12)
        }
        form.addView(TextView(this).apply {
            text = "Put the phone and RS2+ on the same Wi-Fi network (receiver network or a configured hotspot). Enable the receiver NMEA TCP server with GGA and GST. Enter the receiver IPv4 address and TCP port."
        })
        val host = EditText(this).apply {
            setSingleLine(); hint = "Receiver IP address"
            minimumHeight = dp(48)
            setText(prefs.getString("host", ""))
        }
        val port = EditText(this).apply {
            setSingleLine(); hint = "NMEA TCP port"
            minimumHeight = dp(48)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("port", 0).takeIf { it > 0 }?.toString() ?: "")
        }
        form.addView(host); form.addView(port)
        android.app.AlertDialog.Builder(this).setTitle("Emlid Wi-Fi NMEA")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Disconnect") { _, _ ->
                stopService(Intent(this, WifiRtkService::class.java))
            }
            .setPositiveButton("Connect") { _, _ ->
                val h = host.text.toString().trim()
                val p = port.text.toString().toIntOrNull()
                if (!ConnectionProfile.validIpv4(h) || p == null || p !in 1..65535) {
                    Toast.makeText(this, "Enter a receiver IP and TCP port", Toast.LENGTH_LONG).show()
                } else {
                    stopService(Intent(this, RtkService::class.java))
                    try {
                        startForegroundService(Intent(this, WifiRtkService::class.java)
                            .putExtra("host", h).putExtra("port", p))
                    } catch (_: Exception) {
                        Toast.makeText(this, "Unable to start GNSS. Check Android permissions and try again.", Toast.LENGTH_LONG).show()
                    }
                }
            }.show()
    }
    private fun showSounderDiagnostics() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 43)
            return
        }
        val prefs = getSharedPreferences("sounder_diagnostics", MODE_PRIVATE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 12)
        }
        val info = TextView(this).apply { textSize = 14f; setTextIsSelectable(true) }
        panel.addView(info)
        val connect = action("Connect paired sounder") {}
        val stop = action("Stop diagnostic capture") {}
        val share = action("Share raw capture") {}
        panel.addView(connect)
        panel.addView(stop)
        panel.addView(share)
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("HydroLite • raw diagnostics")
            .setView(panel).setPositiveButton("Close", null).create()
        val tick = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                val stored = prefs.getString("state", "disconnected").orEmpty()
                val state = if (SounderDiagnosticService.active()) stored else if (stored == "error") "error" else "disconnected"
                info.text = "State: $state\n" +
                    "Detail: ${prefs.getString("detail", "")}\n" +
                    "Bytes: ${prefs.getLong("bytes", 0L)}\n" +
                    "ASCII frames: ${prefs.getInt("frames", 0)}\n" +
                    "Last ASCII frame: ${prefs.getString("preview", "—")}\n" +
                    "Depth: NOT VERIFIED • not recording survey observations"
                handler.postDelayed(this, 1000)
            }
        }
        connect.setOnClickListener {
            try {
                val manager = getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
                val adapter = manager.adapter
                if (adapter == null || !adapter.isEnabled) {
                    Toast.makeText(this, "Enable Bluetooth and pair the sounder first", Toast.LENGTH_LONG).show()
                } else {
                    val devices = adapter.bondedDevices.toList().sortedBy { it.name ?: it.address }
                    if (devices.isEmpty()) {
                        Toast.makeText(this, "No paired Bluetooth devices", Toast.LENGTH_LONG).show()
                    } else {
                        val labels = devices.map { "${it.name ?: "Unknown"} (${it.address})" }.toTypedArray()
                        android.app.AlertDialog.Builder(this).setTitle("Select sounder Bluetooth device")
                            .setItems(labels) { _, index ->
                                stopService(Intent(this, SounderDiagnosticService::class.java))
                                startForegroundService(Intent(this, SounderDiagnosticService::class.java)
                                    .putExtra("address", devices[index].address))
                            }.setNegativeButton("Cancel", null).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Bluetooth error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        stop.setOnClickListener {
            stopService(Intent(this, SounderDiagnosticService::class.java))
        }
        share.setOnClickListener {
            val path = prefs.getString("file", null)
            val file = path?.let { File(it) }
            val directory = File(filesDir, "sounder_diagnostics").canonicalFile
            if (file == null || !file.isFile || file.parentFile?.canonicalFile != directory) {
                Toast.makeText(this, "No raw capture available", Toast.LENGTH_LONG).show()
            } else {
                try {
                    val copy = File(cacheDir, "sounder_capture.bin")
                    file.copyTo(copy, overwrite = true)
                    val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", copy)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share raw sounder capture"))
                } catch (e: Exception) {
                    Toast.makeText(this, "Cannot share capture: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.setOnShowListener { dialogRefreshes.add(tick); handler.post(tick) }
        dialog.setOnDismissListener { dialogRefreshes.remove(tick); handler.removeCallbacks(tick) }
        dialog.show()
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
                    stopService(Intent(this, WifiRtkService::class.java))
                    val intent = Intent(this, RtkService::class.java).putExtra("address", devices[i].address)
                    try { startForegroundService(intent) }
                    catch (e: Exception) { Toast.makeText(this, "RTK start failed: " + e.message, Toast.LENGTH_LONG).show() }
                }.setNegativeButton("Cancel", null).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Bluetooth error: " + e.message, Toast.LENGTH_LONG).show()
        }
    }
    private fun showLiveRtkMap() {
        val map = SurveyMapView(this)
        val statusLabel = TextView(this).apply {
            textSize = 16f
            setPadding(20, 14, 20, 14)
        }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(statusLabel)
        container.addView(map, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.62f).toInt()
        ))
        val track = mutableListOf<Pair<Double, Double>>()
        var lastTimestamp = 0L
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Live Emlid GNSS track")
            .setView(container)
            .setPositiveButton("Close", null)
            .create()
        val ticker = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                val p = getSharedPreferences("rtk_status", MODE_PRIVATE)
                val timestamp = p.getLong("last_fix_ms", 0L)
                val age = ConnectionPolicy.age(SystemClock.elapsedRealtime(), p.getLong("last_fix_elapsed_ms", 0L))
                val lat = p.getString("lat", null)?.toDoubleOrNull()
                val lon = p.getString("lon", null)?.toDoubleOrNull()
                val valid = GnssConnectionOwner.active() && p.getString("state", "") == "connected" &&
                    ConnectionPolicy.fresh(age) && lat != null && lon != null &&
                    lat in -90.0..90.0 && lon in -180.0..180.0
                val quality = when (p.getString("quality", "")) {
                    "4" -> "RTK FIX"
                    "5" -> "RTK FLOAT"
                    "2" -> "DGPS"
                    "1" -> "SINGLE"
                    else -> "NO FIX"
                }
                if (valid) {
                    val point = Pair(lat!!, lon!!)
                    map.current = point
                    if (timestamp > lastTimestamp) {
                        track.add(point)
                        lastTimestamp = timestamp
                        map.tracks = listOf(track.toList())
                    }
                } else {
                    map.current = null
                }
                statusLabel.text = "Emlid RS2+ • " +
                    (if (valid) quality else "STREAM STALE / DISCONNECTED") +
                    "\nTrack positions: ${track.size} • Satellites: " +
                    p.getString("satellites", "—") +
                    "\nLive preview only • not saved as a survey"
                handler.postDelayed(this, 500L)
            }
        }
        dialog.setOnShowListener { dialogRefreshes.add(ticker); handler.post(ticker) }
        dialog.setOnDismissListener { dialogRefreshes.remove(ticker); handler.removeCallbacks(ticker) }
        dialog.show()
    }
    private fun showSavedSurveys(kind: SessionRepository.Kind = SessionRepository.Kind.LIVE) {
        val sessions = sessionsRepository.list(kind)
        if (sessions.isEmpty()) {
            Toast.makeText(this, "No saved observation records", Toast.LENGTH_LONG).show()
            return
        }
        val labels = sessions.map { "${sessionsRepository.displayName(it)}  |  ${it.observations} points\n${it.file.name}" }.toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle(if (kind == SessionRepository.Kind.DEMO) "Historical synthetic test records" else "Saved observations")
            .setItems(labels) { _, index -> showSavedPoints(sessions[index]) }
            .setNegativeButton("Close", null).show()
    }
    private fun showSavedPoints(session: SessionRepository.Session) {
        try {
            val points = sessionsRepository.read(session)
            val body = points.take(200).mapIndexed { i, point ->
                "#${i + 1}  Lat: ${point.latitude}  Lon: ${point.longitude}"
            }.joinToString("\n")
            val view = ScrollView(this)
            view.addView(TextView(this).apply {
                textSize = 15f
                setPadding(28, 20, 28, 20)
                val source = when {
                    session.kind == SessionRepository.Kind.DEMO -> "HISTORICAL SYNTHETIC TEST — NOT FOR SURVEY"
                    session.file.name.startsWith("gps_") -> "HISTORICAL PHONE GPS — NOT RTK OR BATHYMETRY"
                    else -> "RECEIVER GNSS OBSERVATIONS — NOT SYNCHRONIZED BATHYMETRY"
                }
                text = sessionsRepository.displayName(session) + "\n" + session.file.name + "\nRecorded positions: ${points.size}\n$source\n\n" +
                    body + if (points.size > 200) "\n…first 200 displayed" else ""
                setTextIsSelectable(true)
            })
            android.app.AlertDialog.Builder(this).setTitle("Recorded observations")
                .setView(view).setPositiveButton("Close", null)
                .setNeutralButton("Export CSV") { _, _ -> exportFile(session.file) }.show()
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open CSV: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    private fun showRawFiles() {
        val files = sessionsRepository.artifacts()
        if (files.isEmpty()) { Toast.makeText(this, "No raw acquisition files available", Toast.LENGTH_LONG).show(); return }
        android.app.AlertDialog.Builder(this).setTitle("Raw observations and configuration snapshots")
            .setItems(files.map { "${it.name} · ${it.length()} bytes" }.toTypedArray()) { _, index ->
                exportFile(files[index])
            }.setNegativeButton("Close", null).show()
    }
    private fun exportFile(file: File) {
        try {
            val shareCopy = File(cacheDir, file.name)
            file.inputStream().use { input -> shareCopy.outputStream().use { output -> input.copyTo(output) } }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", shareCopy)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = when (file.extension.lowercase(Locale.US)) {
                    "csv" -> "text/csv"
                    "json" -> "application/json"
                    else -> "application/octet-stream"
                }
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
        if (code == 41 || code == 43) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                if (code == 41) connectRtk() else showSounderDiagnostics()
            } else Toast.makeText(this, "Allow Nearby devices permission to connect paired equipment", Toast.LENGTH_LONG).show()
        }
    }
}

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
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager

class MainActivity : Activity(), LocationListener {
    private lateinit var liveCards: LinearLayout
    private lateinit var liveGnssActions: LinearLayout
    private lateinit var liveSounderActions: LinearLayout
    private val sessionsRepository by lazy { SessionRepository(filesDir) }
    private lateinit var surveySummary: TextView
    private lateinit var surveyGnssLamp: Button
    private lateinit var surveyDepthLamp: Button
    private lateinit var surveyCollectionLamp: Button
    private lateinit var surveyPrecision: TextView
    private lateinit var setupStatus: TextView
    private lateinit var setupNext: Button
    private lateinit var setupSounder: LinearLayout
    private lateinit var legacyPanel: LinearLayout
    private lateinit var readiness: TextView
    private lateinit var sounderStatus: TextView
    private lateinit var gpsBar: TextView
    private lateinit var gpsMetrics: TextView
    private lateinit var locationManager: LocationManager
    private var previewFix: Location? = null
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
        layout.addView(label("TRIDRONE  |  HYDROGRAPHIC SURVEY", 23f).apply { setTypeface(null, Typeface.BOLD) })
        layout.addView(label("GNSS acquisition  •  Offline  •  Field mode", 14f))
        gpsBar = label("GNSS STATUS • SEARCHING", 19f).apply { setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); setBackgroundColor(Color.DKGRAY); setPadding(20,20,20,20) }
        gpsMetrics = label("Horizontal: —\nVertical: —", 17f)
        layout.addView(gpsBar)
        layout.addView(gpsMetrics)
        layout.addView(Button(this).apply { text = "CONNECT REACH RS2+ GNSS"; setOnClickListener { connectRtk() } })
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        layout.addView(label("SOUNDER / HYDROLITE PLUS", 15f).apply { setTypeface(null, Typeface.BOLD) })
        sounderStatus = label("Disconnected • Depth unavailable", 17f)
        layout.addView(sounderStatus)
        layout.addView(Button(this).apply {
            text = "SOUNDER CONNECTION"
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("HydroLite Plus")
                    .setMessage("Sounder transport and message protocol require hardware verification. No live depth connection is implemented yet.")
                    .setPositiveButton("OK", null).show()
            }
        })
        readiness = label("SURVEY READINESS • NOT READY", 17f).apply { setTypeface(null, Typeface.BOLD) }
        layout.addView(readiness)
        layout.addView(Button(this).apply {
            text = "CAPTURE GNSS + DEPTH OBSERVATION"
            isEnabled = false
        })
        layout.addView(label("Capture is unavailable until RTK, sensor offsets, sounder depth and epoch synchronization are validated.", 13f))
        status = label("Checking logger status...", 19f)
        details = label("Waiting for GPS observations", 17f)
        sessions = label("No sessions yet", 15f)
        layout.addView(Button(this).apply { text = "SURVEY SETTINGS / COORDINATE SYSTEMS"; setOnClickListener { showSurveySettings() } })
        layout.addView(label("HORIZONTAL COORDINATE SYSTEM (CONFIGURATION)", 15f).apply { visibility = android.view.View.GONE })
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
        crsSpinner.visibility = android.view.View.GONE
        layout.addView(crsSpinner)

        layout.addView(label("EXTERNAL RTK — REACH RS2+", 15f))
        rtkStatus = label("RTK: disconnected", 16f)
        layout.addView(rtkStatus)
        layout.addView(Button(this).apply { text = "DISCONNECT RTK"; setOnClickListener { stopService(Intent(this@MainActivity, RtkService::class.java)) } })

        layout.addView(label("PHONE GPS LOGGER (NOT RTK)", 14f))
        status.setTypeface(null, Typeface.BOLD)
        layout.addView(status)
        timing = label("Survey not started", 17f)
        layout.addView(timing)
        precision = label("Horizontal accuracy: awaiting fix", 19f).apply { setTypeface(null, Typeface.BOLD) }
        layout.addView(precision)
        lastFix = label("Last fix: none", 14f)
        layout.addView(lastFix)

        layout.addView(details)
        layout.addView(Button(this).apply {
            text = "START PHONE GPS TEST LOG (NOT RTK)"
            setOnClickListener { startSurvey() }
        })
        layout.addView(Button(this).apply {
            text = "STOP PHONE GPS TEST LOG"
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


        layout.addView(label("EPSG:6539 export is provisional pending datum and control verification. Phone GPS is not survey-grade.", 13f))
        // Field console: single clear workflow with diagnostics kept out of the primary view.
        val navy = Color.rgb(12, 24, 39)
        val panel = Color.rgb(24, 40, 58)
        val muted = Color.rgb(165, 185, 201)
        val cyan = Color.rgb(89, 208, 228)
        val wizard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(navy)
        }
        fun heading(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setPadding(8, 12, 8, 12)
        }
        fun section(title: String, subtitle: String): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 16)
            setBackgroundColor(panel)
            addView(heading(title, 19f).apply { setTypeface(null, Typeface.BOLD) })
            addView(heading(subtitle, 13f, muted))
            val params = LinearLayout.LayoutParams(-1, -2)
            params.bottomMargin = 16
            (if (::liveCards.isInitialized) liveCards else wizard).addView(this, params)
        }
        fun action(title: String, click: () -> Unit): Button = Button(this).apply {
            text = title
            isAllCaps = false
            textSize = 15f
            setOnClickListener { click() }
        }
        wizard.addView(heading("TRIDRONE  /  HYDRO", 25f, cyan).apply { setTypeface(null, Typeface.BOLD) })
        wizard.addView(heading("FIELD CONSOLE   •   OFFLINE LOGGER", 12f, muted))
        wizard.addView(heading("Survey workspace", 21f).apply { setTypeface(null, Typeface.BOLD) })
        liveCards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Destination sections are mounted into the tab host below.
        val gnssCard = section("01   GNSS RECEIVER", "Reach RS2+  /  Wi-Fi NMEA preferred")
        setupStatus = heading("Waiting for receiver", 15f, muted)
        gnssCard.addView(setupStatus)
        liveGnssActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        liveGnssActions.addView(action("1  Connect Reach RS2+ via Wi-Fi") { showWifiRtkSetup() })
        liveGnssActions.addView(action("Use Bluetooth instead") { connectRtk() })
        liveGnssActions.addView(heading("Connect to the receiver network first, then configure its NMEA IP and port. The app verifies incoming fixes; a network connection alone is not an RTK fix.", 13f, muted))
        gnssCard.addView(liveGnssActions)
        val sounderCard = section("02   DEPTH SOUNDER", "HydroLite Plus  /  hardware verification pending")
        setupSounder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setupSounder.addView(heading("No verified depth stream", 14f, muted))
        setupSounder.addView(action("2  Inspect HydroLite connection") { showSounderDiagnostics() })
        setupSounder.addView(heading("Actual Bluetooth transport and depth messages must be confirmed on hardware. No verified live depth recording yet.", 13f, muted))
        liveSounderActions = setupSounder
        sounderCard.addView(liveSounderActions)
        val captureCard = section("03   SURVEY COLLECTION", "Continuous GNSS + depth logging")
        setupNext = action("3  Recording locked — depth not verified") {
            Toast.makeText(this@MainActivity, "Connect and validate real GNSS and depth before recording", Toast.LENGTH_LONG).show()
        }
        setupNext.isEnabled = false
        captureCard.addView(setupNext)
        captureCard.addView(heading("Live GNSS and verified sounder data are required. Recording remains disabled until real hardware synchronization is validated.", 13f, muted))
        val reviewCard = section("SESSIONS & REVIEW", "Inspect observations after recovery")
        reviewCard.addView(action("Saved surveys and points") { showSavedSurveys() })
        reviewCard.addView(action("Export latest CSV") { exportLatest() })
        val toolsCard = section("SETTINGS & DIAGNOSTICS", "Advanced tools, configuration and tests")
        toolsCard.addView(action("Survey settings / coordinate systems") { showSurveySettings() })
        toolsCard.addView(action("Check for app updates") { AppUpdater.open(this@MainActivity) })
        toolsCard.addView(action("Live GNSS diagnostic map") { showLiveRtkMap() })
        toolsCard.addView(action("Phone GPS diagnostic") {
            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Phone GPS is not survey-grade")
                .setMessage("Phone-only position logging is a diagnostic and cannot substitute for RTK plus synchronized depth. This feature is excluded from the main survey workflow.")
                .setPositiveButton("OK", null).show()
        })
        // Synthetic testing controls deliberately excluded from the field-facing app.
        legacyPanel = layout
        // Keep the original diagnostic widgets initialized but off-screen.
        // A single navigation host owns all visible destinations.
        val navHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(navy) }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 12)
            setBackgroundColor(navy)
        }
        top.addView(heading("TRIDRONE", 25f, Color.WHITE).apply { setTypeface(null, Typeface.BOLD) })
        top.addView(heading("HYDROGRAPHIC FIELD SYSTEM", 12f, muted))
        val tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(panel) }
        val pages = listOf("Survey", "Sessions", "Equipment", "Settings")
        val tabButtons = mutableListOf<Button>()
        val pageScroll = ScrollView(this).apply { isFillViewport = true }
        val pageHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 28)
        }
        pageScroll.addView(pageHost)
        val surveyPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sessionsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val equipmentPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val settingsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val destinations = listOf(surveyPage, sessionsPage, equipmentPage, settingsPage)
        fun selectPage(index: Int) {
            pageHost.removeAllViews()
            val selected = destinations[index]
            (selected.parent as? android.view.ViewGroup)?.removeView(selected)
            pageHost.addView(selected)
            pageScroll.scrollTo(0, 0)
            tabButtons.forEachIndexed { i, button ->
                button.setTextColor(if (i == index) cyan else muted)
                button.setBackgroundColor(if (i == index) navy else panel)
            }
        }
        pages.forEachIndexed { index, name ->
            val tab = Button(this).apply {
                text = name
                isAllCaps = false
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 8, 0, 8)
                setOnClickListener { selectPage(index) }
            }
            tabButtons.add(tab)
            tabBar.addView(tab, LinearLayout.LayoutParams(0, -2, 1f))
        }
        surveyPage.addView(heading("SURVEY STATUS", 12f, cyan))
        surveyPage.addView(heading("Equipment readiness", 21f).apply { setTypeface(null, Typeface.BOLD) })
        fun signal(title: String, onTap: () -> Unit): Button = action(title, onTap).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            minHeight = (90 * resources.displayMetrics.density).toInt()
            minimumHeight = minHeight
            setBackgroundColor(Color.rgb(143, 43, 43))
            val params = LinearLayout.LayoutParams(-1, -2)
            params.bottomMargin = (12 * resources.displayMetrics.density).toInt()
            surveyPage.addView(this, params)
        }
        surveyGnssLamp = signal("GNSS  •  CONNECT") { selectPage(2) }
        surveyDepthLamp = signal("DEPTH  •  CONNECT") { selectPage(2) }
        surveyCollectionLamp = signal("COLLECTION  •  NOT READY") { selectPage(2) }
        surveyPrecision = heading("H precision   Unavailable\nV precision   Unavailable", 19f, Color.WHITE).apply {
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(panel)
            setPadding(18, 22, 18, 22)
        }
        surveyPage.addView(surveyPrecision)
        surveyPage.addView(heading("Receiver-reported GST precision (1σ), not independently verified accuracy.", 12f, muted))
        sessionsPage.addView(heading("SESSIONS", 12f, cyan))
        sessionsPage.addView(heading("Review & export", 22f))
        (reviewCard.parent as? android.view.ViewGroup)?.removeView(reviewCard)
        sessionsPage.addView(reviewCard)
        equipmentPage.addView(heading("EQUIPMENT", 12f, cyan))
        equipmentPage.addView(heading("Connect devices in order", 22f))
        equipmentPage.addView(heading("First connect the RS2+; check live fix quality. Next inspect the HydroLite stream. Return to Survey when both are verified.", 14f, muted))
        (gnssCard.parent as? android.view.ViewGroup)?.removeView(gnssCard)
        equipmentPage.addView(gnssCard)
        (sounderCard.parent as? android.view.ViewGroup)?.removeView(sounderCard)
        equipmentPage.addView(sounderCard)
        settingsPage.addView(heading("SETTINGS", 12f, cyan))
        settingsPage.addView(heading("Preferences & tools", 22f))
        (toolsCard.parent as? android.view.ViewGroup)?.removeView(toolsCard)
        settingsPage.addView(toolsCard)
        navHost.addView(top)
        navHost.addView(tabBar)
        navHost.addView(pageScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        selectPage(0)
        setContentView(navHost)
    }
    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
        startPreview()
    }
    override fun onPause() {
        handler.removeCallbacks(refresh)
        if (::locationManager.isInitialized) locationManager.removeUpdates(this)
        super.onPause()
    }
    override fun onLocationChanged(location: Location) { previewFix = location }
    private fun startPreview() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 51)
            return
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        } catch (_: Exception) {}
    }
    private fun updateGnssPreview() {
        val r = getSharedPreferences("rtk_status", MODE_PRIVATE)
        val age = System.currentTimeMillis() - r.getLong("last_fix_ms", 0L)
        val fresh = r.getString("state", "") == "connected" && age in 0..5000
        val quality = if (fresh) r.getString("quality", "") else ""
        val state = when {
            quality == "4" -> "RTK FIX"
            quality == "5" -> "RTK FLOAT"
            fresh -> "RS2+ • NOT FIXED"
            else -> "NOT CONNECTED"
        }
        gpsBar.text = "●  REACH RS2+  •  $state"
        gpsBar.setBackgroundColor(when (state) {
            "RTK FIX" -> Color.rgb(21, 119, 78)
            "RTK FLOAT" -> Color.rgb(172, 109, 19)
            else -> Color.rgb(124, 56, 56)
        })
        val gstFresh = fresh && (System.currentTimeMillis() - r.getLong("gst_received_ms", 0L)) in 0..5000
        val hSigma = if (gstFresh) r.getString("h_sigma_m", null)?.toDoubleOrNull() else null
        val vSigma = if (gstFresh) r.getString("v_sigma_m", null)?.toDoubleOrNull() else null
        val h = if (hSigma != null) String.format(Locale.US, "%.3f m • RS2+ GST 1σ", hSigma)
            else if (fresh) "Unavailable (enable GST output)"
            else "Unavailable"
        val v = if (vSigma != null) String.format(Locale.US, "%.3f m • RS2+ GST 1σ", vSigma)
            else if (fresh) "Unavailable (enable GST output)"
            else "Unavailable"
        gpsMetrics.text = "Receiver horizontal precision: $h\nReceiver vertical precision: $v\n" +
            (if (fresh) "RS2+ satellites: " + r.getString("satellites", "—") + " • HDOP: " + r.getString("hdop", "—") else "Awaiting external RTK")
    }
    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        super.onDestroy()
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
    private fun showWifiRtkSetup() {
        val prefs = getSharedPreferences("wifi_rtk_settings", MODE_PRIVATE)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 12, 28, 12)
        }
        form.addView(TextView(this).apply {
            text = "Connect the Reach to the phone hotspot and configure a TCP NMEA server on the receiver. Enter its hotspot IP and NMEA TCP port."
        })
        val host = EditText(this).apply {
            setSingleLine(); hint = "Receiver IP address"
            setText(prefs.getString("host", ""))
        }
        val port = EditText(this).apply {
            setSingleLine(); hint = "NMEA TCP port"
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
                if (h.isBlank() || p == null || p !in 1..65535) {
                    Toast.makeText(this, "Enter a receiver IP and TCP port", Toast.LENGTH_LONG).show()
                } else {
                    prefs.edit().putString("host", h).putInt("port", p).apply()
                    stopService(Intent(this, RtkService::class.java))
                    startForegroundService(Intent(this, WifiRtkService::class.java)
                        .putExtra("host", h).putExtra("port", p))
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
        val connect = Button(this).apply { text = "Connect paired sounder" }
        val stop = Button(this).apply { text = "Stop diagnostic capture" }
        val share = Button(this).apply { text = "Share raw capture" }
        panel.addView(connect)
        panel.addView(stop)
        panel.addView(share)
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("HydroLite • raw diagnostics")
            .setView(panel).setPositiveButton("Close", null).create()
        val tick = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                info.text = "State: ${prefs.getString("state", "disconnected")}\n" +
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
        dialog.setOnShowListener { handler.post(tick) }
        dialog.setOnDismissListener { handler.removeCallbacks(tick) }
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
    private fun latest(): File? = sessionsRepository.latest(SessionRepository.Kind.LIVE)
    private fun updateDisplay() {
        updateGnssPreview()
        val gnss = getSharedPreferences("rtk_status", MODE_PRIVATE)
        val fixAge = System.currentTimeMillis() - gnss.getLong("last_fix_ms", 0L)
        val live = gnss.getString("state", "") == "connected" && fixAge in 0..5000
        val fixType = when (gnss.getString("quality", "")) {
            "4" -> "RTK FIX"
            "5" -> "RTK FLOAT"
            else -> "NO RTK FIX"
        }
        setupStatus.text = "GNSS: " + (gnss.getString("state", "disconnected") ?: "disconnected") +
            (if (live) "  •  $fixType" else "  •  awaiting live GGA") +
            "\\n" + (gnss.getString("detail", "") ?: "") +
            "\\nGGA: " + gnss.getInt("fixes", 0)
        if (::surveySummary.isInitialized) {
            val diagnostic = getSharedPreferences("sounder_diagnostics", MODE_PRIVATE)
            val depthState = diagnostic.getString("state", "disconnected")
            val operator = OperatorReadiness.evaluate(OperatorReadiness.State(
                receiverState = gnss.getString("state", "disconnected").orEmpty(),
                fixQuality = gnss.getString("quality", null),
                fixAgeMs = if (gnss.getLong("last_fix_ms", 0L) > 0) fixAge else null,
                sounderState = depthState.orEmpty()
            ))
            surveySummary.text = "1  GNSS  •  " + operator.gnss +
                "\\n2  SOUNDER  •  " + operator.sounder +
                "\\n3  RECORDING  •  " + operator.recording
            setupNext.isEnabled = operator.canRecord
        }
        // Clear large-field indicators: red = absent/fault, amber = partial or unverified,
        // green = verified acquisition status only. No simulated data can turn an indicator green.
        val receiverState = gnss.getString("state", "disconnected").orEmpty()
        val qualityCode = gnss.getString("quality", null)
        val precisionFresh = live && (System.currentTimeMillis() - gnss.getLong("gst_received_ms", 0L)) in 0..5000
        val gstCurrent = live && (System.currentTimeMillis() - gnss.getLong("gst_received_ms", 0L)) in 0..5000
        val validH = if (gstCurrent) gnss.getString("h_sigma_m", null)?.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true else false
        val validV = if (gstCurrent) gnss.getString("v_sigma_m", null)?.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true else false
        val gnssGood = live && qualityCode == "4" && validH && validV &&
            precisionFresh && gnss.getString("h_sigma_m", null)?.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true &&
            gnss.getString("v_sigma_m", null)?.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true
        val gnssColor = when {
            gnssGood -> Color.rgb(24, 130, 75)
            receiverState == "connecting" || receiverState == "reconnecting" || live -> Color.rgb(177, 111, 24)
            else -> Color.rgb(154, 45, 45)
        }
        fun styleLamp(button: Button, title: String, color: Int) {
            button.text = title
            button.setBackgroundColor(color)
            button.elevation = if (color == Color.rgb(24, 130, 75)) 14f else 8f
        }
        styleLamp(surveyGnssLamp, "GNSS  •  " +
            when {
                gnssGood -> "RTK FIX"
                live && qualityCode == "4" -> "CHECK H/V PRECISION"
                live && qualityCode == "5" -> "FLOAT — WAIT"
                live && qualityCode == "4" -> "FIX — CHECK PRECISION"
                live -> "CHECK FIX"
                receiverState == "connecting" || receiverState == "reconnecting" -> "CONNECTING"
                else -> "CONNECT"
            }, gnssColor)
        val sounderLink = getSharedPreferences("sounder_diagnostics", MODE_PRIVATE).getString("state", "")
        styleLamp(surveyDepthLamp,
            if (sounderLink == "connected") "DEPTH  •  UNVERIFIED" else "DEPTH  •  CONNECT",
            if (sounderLink == "connected" || sounderLink == "connecting") Color.rgb(177, 111, 24)
            else Color.rgb(154, 45, 45))
        styleLamp(surveyCollectionLamp, "COLLECTION  •  NOT READY", Color.rgb(154, 45, 45))
        val gstFresh = live && (System.currentTimeMillis() - gnss.getLong("gst_received_ms", 0L)) in 0..5000
        val horizontal = if (gstFresh) gnss.getString("h_sigma_m", null)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } else null
        val vertical = if (gstFresh) gnss.getString("v_sigma_m", null)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } else null
        fun precision(v: Double?) = if (v == null) "Unavailable" else String.format(Locale.US, "%.3f m", v)
        surveyPrecision.text = "H PRECISION    " + precision(horizontal) +
            "\nV PRECISION    " + precision(vertical)
        setupSounder.alpha = 1f
        val rt = getSharedPreferences("rtk_status", MODE_PRIVATE)
        val rtAge = System.currentTimeMillis() - rt.getLong("last_fix_ms", 0L)
        val fixed = rt.getString("state", "") == "connected" && rt.getString("quality", "") == "4" && rtAge in 0..5000
        readiness.text = "SURVEY READINESS • NOT READY\n" +
            (if (fixed) "RTK FIX live" else "RTK FIX required") +
            "\nSensor offsets not configured" +
            "\nHydroLite depth unavailable" +
            "\nSynchronized capture not implemented"
        sounderStatus.text = "Disconnected • Depth unavailable"
        val rp = getSharedPreferences("rtk_status", MODE_PRIVATE)
        val rtkAge = (System.currentTimeMillis() - rp.getLong("last_fix_ms", 0L)) / 1000
        val quality = rp.getString("quality", "?") ?: "?"
        val fixLabel = when(quality) { "4" -> "RTK FIX"; "5" -> "RTK FLOAT"; "2" -> "DGPS"; "1" -> "SINGLE"; "0" -> "INVALID"; else -> "UNKNOWN" }
        rtkStatus.text = "RTK: " + rp.getString("state", "disconnected") + " • " + rp.getString("detail", "") +
            "\nFix: " + fixLabel + " • Satellites: " + rp.getString("satellites", "—") +
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
                val age = System.currentTimeMillis() - timestamp
                val lat = p.getString("lat", null)?.toDoubleOrNull()
                val lon = p.getString("lon", null)?.toDoubleOrNull()
                val valid = p.getString("state", "") == "connected" &&
                    age in 0..5000 && lat != null && lon != null &&
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
                    "\\nTrack positions: ${track.size} • Satellites: " +
                    p.getString("satellites", "—") +
                    "\\nLive preview only • not saved as a survey"
                handler.postDelayed(this, 500L)
            }
        }
        dialog.setOnShowListener { handler.post(ticker) }
        dialog.setOnDismissListener { handler.removeCallbacks(ticker) }
        dialog.show()
    }
    private fun showSurveyMap() {
        val sessions = sessionsRepository.list(SessionRepository.Kind.LIVE)
        val tracks = sessions.map { session ->
            try {
                sessionsRepository.read(session).map { Pair(it.latitude, it.longitude) }
            } catch (_: Exception) { emptyList<Pair<Double, Double>>() }
        }
        val view = SurveyMapView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val info = TextView(this).apply {
            text = "All saved tracks: " + sessions.size + "  |  Recorded points: " + tracks.sumOf { it.size } +
                "\nBlue: latest track  •  Gray: earlier tracks  •  Green: phone GPS position"
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
        val dialog = android.app.AlertDialog.Builder(this).setTitle("Phone GPS diagnostic map")
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
        val sessions = sessionsRepository.list(SessionRepository.Kind.LIVE)
        if (sessions.isEmpty()) {
            Toast.makeText(this, "No saved phone GPS sessions", Toast.LENGTH_LONG).show()
            return
        }
        val labels = sessions.map { "${it.file.name}  |  ${it.observations} points" }.toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle("Phone GPS diagnostic sessions")
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
                text = session.file.name + "\nRecorded positions: ${points.size}\nPHONE GPS — NOT RTK OR BATHYMETRY\n\n" +
                    body + if (points.size > 200) "\n…first 200 displayed" else ""
                setTextIsSelectable(true)
            })
            android.app.AlertDialog.Builder(this).setTitle("Recorded phone GPS positions")
                .setView(view).setPositiveButton("Close", null)
                .setNeutralButton("Export CSV") { _, _ -> exportFile(session.file) }.show()
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open CSV: ${e.message}", Toast.LENGTH_LONG).show()
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
        if (code == 51 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startPreview()
        if (code == 41 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) connectRtk()
        if (code == requestCode) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startSurvey()
            else status.text = "Precise location permission required"
        }
    }
}

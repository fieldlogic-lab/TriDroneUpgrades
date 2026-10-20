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
    private val modeController = SurveyModeController()
    private val demoMode get() = modeController.state.mode == SurveyModeController.Mode.DEMO
    private val demoGnss get() = modeController.state.demoGnssConnected
    private val demoSounder get() = modeController.state.demoSounderConnected
    private val demoRecording get() = modeController.state.recording
    private var demoCount = 0
    private val demoRecorder by lazy { DemoSessionRecorder(this) }
    private val sessionsRepository by lazy { SessionRepository(filesDir) }
    private var demoFile: File? = null
    private lateinit var modeLabel: TextView
    private lateinit var demoGnssButton: Button
    private lateinit var demoSounderButton: Button
    private lateinit var demoRecordButton: Button
    private lateinit var demoStatus: TextView
    private lateinit var liveCards: LinearLayout
    private lateinit var demoCards: LinearLayout
    private lateinit var liveGnssActions: LinearLayout
    private lateinit var demoGnssActions: LinearLayout
    private lateinit var liveSounderActions: LinearLayout
    private lateinit var demoSounderActions: LinearLayout
    private val demoTick = object : Runnable {
        override fun run() {
            if (!demoRecording || !demoMode) return
            try {
                demoRecorder.sample()
                demoCount = demoRecorder.count
                updateDemoUi()
                handler.postDelayed(this, 1000)
            } catch (e: Exception) {
                stopDemoRecording()
                Toast.makeText(this@MainActivity, "Demo write failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun stopDemoRecording() {
        modeController.stopDemo()
        handler.removeCallbacks(demoTick)
        try { demoRecorder.stop() } catch (_: Exception) {}
        if (::demoStatus.isInitialized) updateDemoUi()
    }
    private fun updateDemoUi() {
        if (!::demoStatus.isInitialized) return
        demoGnssButton.text = if (demoGnss) "GNSS connected (simulated)" else "Connect demo GNSS"
        demoSounderButton.text = if (demoSounder) "Sounder connected (simulated)" else "Connect demo sounder"
        demoSounderButton.isEnabled = demoGnss && !demoRecording
        demoGnssButton.isEnabled = !demoRecording
        demoRecordButton.isEnabled = demoGnss && demoSounder
        demoRecordButton.text = if (demoRecording) "Stop demo recording" else "Start demo recording"
        if (::setupNext.isInitialized) {
            setupNext.isEnabled = if (demoMode) demoGnss && demoSounder else true
            setupNext.text = if (demoMode) (if (demoRecording) "Stop recording" else "Start recording") else if (HydroLive.recording) "STOP LIVE RECORDING" else "START LIVE RECORDING"
        }
        if (::setupStatus.isInitialized && demoMode) setupStatus.text = if (demoGnss) "SIMULATED RTK FIX" else "Awaiting demo GNSS"
        if (::sounderStatus.isInitialized && demoMode) sounderStatus.text = if (demoSounder) "SIMULATED DEPTH CONNECTED" else "Awaiting demo sounder"
        demoStatus.text = "DEMO ONLY  •  ${demoCount} synthetic observations\n" +
            "GNSS: ${if (demoGnss) "SIMULATED FIX" else "NOT CONNECTED"}  •  " +
            "Depth: ${if (demoSounder) "SIMULATED" else "NOT CONNECTED"}"
    }
    private fun setDemoMode(enabled: Boolean) {
        if (demoMode == enabled) return
        if (HydroLive.recording) {
            Toast.makeText(this, "Stop live recording before switching modes", Toast.LENGTH_LONG).show()
            return
        }
        if (demoRecording) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Stop demo recording?")
                .setMessage("Switching modes will stop the current demo session. Recorded observations will be saved.")
                .setNegativeButton("Keep recording", null)
                .setPositiveButton("Stop and switch") { _, _ -> switchMode(enabled) }
                .show()
            return
        }
        switchMode(enabled)
    }
    private fun switchMode(enabled: Boolean) {
        stopDemoRecording()
        stopSimulation()
        check(modeController.switchMode(if (enabled) SurveyModeController.Mode.DEMO else SurveyModeController.Mode.LIVE))
        demoCount = 0
        liveGnssActions.visibility = if (enabled) android.view.View.GONE else android.view.View.VISIBLE
        demoGnssActions.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
        liveSounderActions.visibility = if (enabled) android.view.View.GONE else android.view.View.VISIBLE
        demoSounderActions.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
        setupNext.isEnabled = if (enabled) demoGnss && demoSounder else true
        setupNext.text = if (enabled) "Start demo recording" else if (HydroLive.recording) "STOP LIVE RECORDING" else "START LIVE RECORDING"
        modeLabel.text = if (enabled) "DEMO MODE  •  SYNTHETIC DATA" else "LIVE SURVEY  •  REAL HARDWARE"
        demoStatus.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
        updateDemoUi()
    }
    private fun startDemoRecording() {
        if (!demoMode || !demoGnss || !demoSounder || demoRecording) return
        try {
            demoFile = demoRecorder.start()
            demoCount = 0
            check(modeController.startDemo())
            updateDemoUi()
            handler.post(demoTick)
        } catch (e: Exception) {
            stopDemoRecording()
            Toast.makeText(this, "Cannot start demo: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    private var simulationActive = false
    private var simulationWriter: java.io.BufferedWriter? = null
    private var simulationFile: File? = null
    private var simulationCount = 0
    private lateinit var simulationStatus: TextView
    private val simulationTick = object : Runnable {
        override fun run() {
            if (!simulationActive) return
            val p = getSharedPreferences("rtk_status", MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val age = now - p.getLong("last_fix_ms", 0L)
            val lat = p.getString("lat", "")?.toDoubleOrNull()
            val lon = p.getString("lon", "")?.toDoubleOrNull()
            val quality = p.getString("quality", "")
            if (p.getString("state", "") == "connected" && age in 0..3000 && lat != null && lon != null) {
                val depth = 2.5 + kotlin.math.sin(simulationCount * 0.12) * 0.4
                val accepted = quality == "4"
                try {
                    simulationWriter?.write(String.format(Locale.US, "%d,%d,%.9f,%.9f,%s,%.3f,%s,SIMULATED,UNVERIFIED_OFFSETS,NO_VERTICAL_DATUM\\n", now, p.getLong("last_fix_ms", 0L), lat, lon, quality, depth, if (accepted) "RTK_FIXED_GNSS" else "GNSS_NOT_FIXED"))
                    simulationWriter?.flush()
                    simulationCount++
                } catch (e: Exception) { stopSimulation(); Toast.makeText(this@MainActivity, "Simulation write failed: ${e.message}", Toast.LENGTH_LONG).show(); return }
            }
            simulationStatus.text = "SIMULATION ONLY • $simulationCount synthetic depth events • GNSS ${if (age in 0..3000) "live" else "stale"} • ${if (quality == "4") "FIX" else "NOT FIXED"}"
            handler.postDelayed(this, 1000)
        }
    }
    private lateinit var liveDepth: TextView
    private lateinit var liveRecording: TextView
    private lateinit var surveySummary: TextView
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
        modeLabel = heading("LIVE SURVEY  •  REAL HARDWARE", 15f, cyan)
        wizard.addView(modeLabel)
        val modeSwitch = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val liveButton = action("LIVE") { setDemoMode(false) }
        val demoButton = action("DEMO") { setDemoMode(true) }
        modeSwitch.addView(liveButton, LinearLayout.LayoutParams(0, -2, 1f))
        modeSwitch.addView(demoButton, LinearLayout.LayoutParams(0, -2, 1f))
        wizard.addView(modeSwitch)
        liveCards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        demoCards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        // Destination sections are mounted into the tab host below.
        val gnssCard = section("01   GNSS RECEIVER", "Reach RS2+  /  Wi-Fi NMEA preferred")
        setupStatus = heading("Waiting for receiver", 15f, muted)
        gnssCard.addView(setupStatus)
        liveGnssActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        liveGnssActions.addView(action("Connect Emlid via Wi-Fi") { showWifiRtkSetup() })
        liveGnssActions.addView(action("Bluetooth backup") { connectRtk() })
        gnssCard.addView(liveGnssActions)
        demoGnssActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        demoGnssButton = action("Connect demo GNSS") { modeController.connectDemoGnss(); updateDemoUi() }
        demoGnssActions.addView(demoGnssButton)
        gnssCard.addView(demoGnssActions)
        val sounderCard = section("02   DEPTH SOUNDER", "HydroLite Plus  /  hardware verification pending")
        setupSounder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setupSounder.addView(heading("Live decoder: field 2 provisionally interpreted as meters", 14f, muted))
        setupSounder.addView(action("Sounder diagnostics • connect / inspect") { showSounderDiagnostics() })
        liveSounderActions = setupSounder
        sounderCard.addView(liveSounderActions)
        demoSounderActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        demoSounderButton = action("Connect demo sounder") { modeController.connectDemoSounder(); updateDemoUi() }
        demoSounderActions.addView(demoSounderButton)
        sounderCard.addView(demoSounderActions)
        val captureCard = section("03   SURVEY COLLECTION", "Continuous GNSS + depth logging")
        setupNext = action("START LIVE RECORDING") {
            if (demoMode) { if (demoRecording) stopDemoRecording() else startDemoRecording(); updateDemoUi() }
            else {
                try {
                    if (HydroLive.recording) HydroLive.stop() else HydroLive.start(this)
                    updateDisplay()
                } catch (e: Exception) { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
            }
        }
        setupNext.isEnabled = true
        setupNext.minHeight = 72
        setupNext.textSize = 20f
        captureCard.addView(setupNext)
        captureCard.addView(heading("Live records provisional soundings and quality flags. NAVD88 elevations require verified vertical control. Demo observations remain separate.", 13f, muted))
        val reviewCard = section("SESSIONS & REVIEW", "Inspect observations after recovery")
        reviewCard.addView(action("Live hydrographic recordings") { showHydroSessions() })
        reviewCard.addView(action("Saved surveys and points") { if (demoMode) showDemoReview() else showSavedSurveys() })
        reviewCard.addView(action("Export latest CSV") {
            if (demoMode) {
                val file = sessionsRepository.latest(SessionRepository.Kind.DEMO)
                if (file != null) exportFile(file) else Toast.makeText(this@MainActivity, "No demo sessions yet", Toast.LENGTH_SHORT).show()
            } else exportLatest()
        })
        val toolsCard = section("SETTINGS & DIAGNOSTICS", "Advanced tools, configuration and tests")
        toolsCard.addView(action("Antenna / transducer vertical offset") { showEquipmentSettings() })
        toolsCard.addView(action("Survey settings / coordinate systems") { showSurveySettings() })
        toolsCard.addView(action("Check for app updates") { AppUpdater.open(this@MainActivity) })
        toolsCard.addView(action("Live GNSS diagnostic map") { showLiveRtkMap() })
        toolsCard.addView(action("Phone GPS diagnostic") {
            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Phone GPS is not survey-grade")
                .setMessage("Phone-only position logging is a diagnostic and cannot substitute for RTK plus synchronized depth. This feature is excluded from the main survey workflow.")
                .setPositiveButton("OK", null).show()
        })
        val testCard = section("DEVELOPMENT ONLY", "Synthetic soundings are never survey observations")
        testCard.visibility = android.view.View.GONE
        simulationStatus = heading("Simulation stopped", 14f, muted)
        testCard.addView(simulationStatus)
        testCard.addView(action("Start simulated depth test") { startSimulation() })
        testCard.addView(action("Stop simulation") { stopSimulation() })
        testCard.addView(action("Export simulation CSV") {
            val file = simulationFile
            if (file == null || !file.exists())
                Toast.makeText(this@MainActivity, "No simulation file yet", Toast.LENGTH_SHORT).show()
            else exportFile(file)
        })
        demoRecordButton = setupNext
        demoStatus = heading("DEMO ONLY  •  Synthetic observations never enter live surveys", 14f, muted)
        wizard.addView(demoStatus)
        demoStatus.visibility = android.view.View.GONE
        toolsCard.addView(action("Developer diagnostics") {
            testCard.visibility = if (testCard.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
        })
        updateDemoUi()
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
        surveyPage.addView(heading("FIELD OPERATIONS", 12f, cyan))
        surveyPage.addView(heading("Ready to collect?", 22f).apply { setTypeface(null, Typeface.BOLD) })
        surveySummary = heading("GNSS  •  Awaiting receiver\nSOUNDER  •  Not verified", 15f, Color.WHITE)
        surveySummary.setPadding(18, 18, 18, 18)
        surveySummary.setBackgroundColor(panel)
        surveyPage.addView(surveySummary)
        for (view in listOf(gpsBar, gpsMetrics)) {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            surveyPage.addView(view)
        }
        liveDepth = heading("SOUNDER • No Reading", 23f)
        surveyPage.addView(liveDepth)
        liveRecording = heading("Not recording • 0 valid soundings", 17f)
        surveyPage.addView(liveRecording)
        surveyPage.addView(action("Equipment vertical offset") { showEquipmentSettings() })
        surveyPage.addView(action("Connect & check equipment  →") { selectPage(2) })
        surveyPage.addView(action("Review previous sessions  →") { selectPage(1) })
        surveyPage.addView(heading("Connect GNSS and sounder before recording. Protocol, sensor latency and survey accuracy require field validation.", 14f, muted))
        (modeLabel.parent as? android.view.ViewGroup)?.removeView(modeLabel)
        surveyPage.addView(modeLabel)
        (modeSwitch.parent as? android.view.ViewGroup)?.removeView(modeSwitch)
        surveyPage.addView(modeSwitch)
        (captureCard.parent as? android.view.ViewGroup)?.removeView(captureCard)
        surveyPage.addView(captureCard)
        (demoStatus.parent as? android.view.ViewGroup)?.removeView(demoStatus)
        surveyPage.addView(demoStatus)
        sessionsPage.addView(heading("SESSIONS", 12f, cyan))
        sessionsPage.addView(heading("Review & export", 22f))
        (reviewCard.parent as? android.view.ViewGroup)?.removeView(reviewCard)
        sessionsPage.addView(reviewCard)
        equipmentPage.addView(heading("EQUIPMENT", 12f, cyan))
        equipmentPage.addView(heading("Connections & diagnostics", 22f))
        (gnssCard.parent as? android.view.ViewGroup)?.removeView(gnssCard)
        equipmentPage.addView(gnssCard)
        (sounderCard.parent as? android.view.ViewGroup)?.removeView(sounderCard)
        equipmentPage.addView(sounderCard)
        settingsPage.addView(heading("SETTINGS", 12f, cyan))
        settingsPage.addView(heading("Preferences & tools", 22f))
        (toolsCard.parent as? android.view.ViewGroup)?.removeView(toolsCard)
        settingsPage.addView(toolsCard)
        (testCard.parent as? android.view.ViewGroup)?.removeView(testCard)
        settingsPage.addView(testCard)
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
            quality == "1" -> "AUTONOMOUS"
            quality == "2" -> "DGPS"
            fresh -> "NO VALID FIX"
            else -> "NOT CONNECTED"
        }
        gpsBar.text = "●  REACH RS2+  •  $state"
        gpsBar.setBackgroundColor(when (state) {
            "RTK FIX" -> Color.rgb(21, 119, 78)
            "RTK FLOAT", "AUTONOMOUS", "DGPS", "NO VALID FIX" -> Color.rgb(172, 109, 19)
            else -> Color.rgb(124, 56, 56)
        })
        val gstFresh = fresh && (System.currentTimeMillis() - r.getLong("gst_received_ms", 0L)) in 0..5000
        val hSigma = if (gstFresh) r.getString("h_sigma_m", null)?.toDoubleOrNull() else null
        val vSigma = if (gstFresh) r.getString("v_sigma_m", null)?.toDoubleOrNull() else null
        val h = if (hSigma != null) String.format(Locale.US, "%.3f US survey ft • RS2+ GST 1σ", SurveyUnits.feet(hSigma))
            else if (fresh) "Unavailable (enable GST output)"
            else "Unavailable"
        val v = if (vSigma != null) String.format(Locale.US, "%.3f US survey ft • RS2+ GST 1σ", SurveyUnits.feet(vSigma))
            else if (fresh) "Unavailable (enable GST output)"
            else "Unavailable"
        gpsMetrics.text = "Receiver horizontal precision: $h\nReceiver vertical precision: $v\n" +
            (if (fresh) "RS2+ satellites: " + r.getString("satellites", "—") + " • HDOP: " + r.getString("hdop", "—") else "Awaiting external RTK")
    }
    private fun startSimulation() {
        if (simulationActive) return
        val p = getSharedPreferences("rtk_status", MODE_PRIVATE)
        if (p.getString("state", "") != "connected" || System.currentTimeMillis() - p.getLong("last_fix_ms", 0L) !in 0..3000) {
            Toast.makeText(this, "Connect live Emlid NMEA first", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val folder = File(filesDir, "surveys").apply { mkdirs() }
            val file = File(folder, "SIMULATED_DEPTH_${System.currentTimeMillis()}.csv")
            simulationWriter = file.bufferedWriter()
            simulationWriter?.write("event_utc_ms,gnss_received_utc_ms,latitude_deg,longitude_deg,gga_quality,simulated_depth_m,gnss_flag,data_source,offset_status,vertical_status\\n")
            simulationWriter?.flush()
            simulationFile = file
            simulationCount = 0
            simulationActive = true
            simulationStatus.text = "SIMULATION RUNNING • NOT SURVEY DATA"
            handler.post(simulationTick)
        } catch (e: Exception) {
            stopSimulation()
            Toast.makeText(this, "Cannot start simulation: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    private fun stopSimulation() {
        simulationActive = false
        handler.removeCallbacks(simulationTick)
        try { simulationWriter?.close() } catch (_: Exception) {}
        simulationWriter = null
        if (::simulationStatus.isInitialized) simulationStatus.text = "Simulation stopped • $simulationCount test events"
    }
    override fun onDestroy() {
        stopSimulation()
        stopDemoRecording()
        handler.removeCallbacks(refresh)
        super.onDestroy()
    }
    private fun showEquipmentSettings() {
        val current = EquipmentSettings.load(HydroLive.equipmentFile(this))
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 20, 28, 20) }
        form.addView(TextView(this).apply { text = "Vertical separation: selected GNSS reference to transducer acoustic reference, positive DOWN, US survey feet. No antenna calibration is automatically added. Match the receiver height reference before calculating elevations." })
        val offset = EditText(this).apply {
            hint = "Vertical offset in US survey feet (unset by default)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(current.offsetUsft?.toString().orEmpty())
        }
        form.addView(offset)
        val reference = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Select antenna reference", "Physical antenna reference point (ARP)", "Configured receiver output reference"))
            setSelection(EquipmentSettings.REFERENCES.indexOf(current.antennaReference) + 1)
        }
        form.addView(reference)
        val confirmed = CheckBox(this).apply { text = "I verified offset and receiver height reference; no duplicate antenna correction"; isChecked = current.confirmed }
        form.addView(confirmed)
        form.addView(TextView(this).apply { text = "Pairing tolerance (milliseconds, 1–5000). Uses device receipt time; sensor latency remains unverified." })
        val tolerance = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(current.pairToleranceMillis.toString()) }
        form.addView(tolerance)
        val dialog = android.app.AlertDialog.Builder(this).setTitle("Equipment geometry")
            .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val distance = offset.text.toString().toDoubleOrNull()
                val ms = tolerance.text.toString().toLongOrNull()
                if ((offset.text.isNotBlank() && (distance == null || !distance.isFinite() || distance < 0)) || ms == null || ms !in 1..5000 ||
                    (confirmed.isChecked && (distance == null || reference.selectedItemPosition == 0))) {
                    Toast.makeText(this, "Enter a nonnegative offset, reference and valid tolerance", Toast.LENGTH_LONG).show()
                } else try {
                    EquipmentSettings(distance, EquipmentSettings.REFERENCES.getOrNull(reference.selectedItemPosition - 1) ?: "UNSET", confirmed.isChecked, ms)
                        .save(HydroLive.equipmentFile(this))
                    Toast.makeText(this, "Saved; active recording retains its original configuration", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                } catch (e: Exception) { Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
        dialog.show()
    }

    private fun showHydroSessions() {
        val folder = File(filesDir, "hydro_surveys")
        val files = folder.listFiles { f -> f.name.startsWith("hydro_") && f.extension == "csv" && !f.name.endsWith(".gnss.csv") }
            ?.sortedByDescending { it.name }.orEmpty()
        if (files.isEmpty()) { Toast.makeText(this, "No live hydrographic sessions", Toast.LENGTH_LONG).show(); return }
        android.app.AlertDialog.Builder(this).setTitle("Live hydrographic recordings")
            .setItems(files.map { it.name }.toTypedArray()) { _, index ->
                val file = files[index]
                val view = ScrollView(this).apply { addView(TextView(this@MainActivity).apply {
                    setPadding(20, 20, 20, 20); setTextIsSelectable(true)
                    text = "PROVISIONAL HYDROLITE DECODER • NAVD88 WITHHELD\nRaw/processed events (first 100):\n" + file.useLines { it.take(101).joinToString("\n") }
                }) }
                android.app.AlertDialog.Builder(this).setTitle(file.name).setView(view)
                    .setPositiveButton("Close", null).setNeutralButton("Export CSV") { _, _ -> exportFile(file) }
                    .setNegativeButton("Export audit ZIP") { _, _ -> exportHydroAudit(file) }.show()
            }.setPositiveButton("Close", null).show()
    }

    private fun exportHydroAudit(file: File) {
        try {
            val metadata = File(file.parentFile, file.nameWithoutExtension + ".metadata.json")
            val gnss = File(file.parentFile, file.nameWithoutExtension + ".gnss.csv")
            val inputs = mutableListOf(file, metadata, gnss, File(file.parentFile, file.nameWithoutExtension + ".sounder.bin"))
            if (metadata.exists()) {
                val path = org.json.JSONObject(metadata.readText()).optString("sounder_raw_bytes_file")
                val raw = File(path)
                if (raw.isFile && raw.parentFile?.canonicalFile == File(filesDir, "sounder_diagnostics").canonicalFile) inputs.add(raw)
            }
            val zip = File(cacheDir, file.nameWithoutExtension + ".zip")
            java.util.zip.ZipOutputStream(zip.outputStream()).use { out -> inputs.filter { it.isFile }.forEach { input ->
                out.putNextEntry(java.util.zip.ZipEntry(input.name)); input.inputStream().use { it.copyTo(out) }; out.closeEntry()
            } }
            exportFile(zip)
        } catch (e: Exception) { Toast.makeText(this, "Audit export failed: ${e.message}", Toast.LENGTH_LONG).show() }
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
        units.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("US survey feet"))
        units.setSelection(0)
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
                    "EPSG:6360", "usft",
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
        if (demoMode) {
            Toast.makeText(this, "Sounder diagnostics are available in Live mode only", Toast.LENGTH_LONG).show()
            return
        }
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
                    "Depth decoder provisional • open Survey to record"
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
                    val intent = Intent(this, RtkService::class.java).putExtra("address", devices[i].address)
                    try { stopService(Intent(this, WifiRtkService::class.java)); startForegroundService(intent) }
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
            "1" -> "AUTONOMOUS"
            "2" -> "DGPS"
            else -> "NO VALID FIX"
        }
        setupStatus.text = "GNSS: " + (gnss.getString("state", "disconnected") ?: "disconnected") +
            (if (live) "  •  $fixType" else "  •  awaiting live GGA") +
            "\n" + (gnss.getString("detail", "") ?: "") +
            "\nGGA: " + gnss.getInt("fixes", 0)
        val reading = HydroLive.depth
        val depthState = reading?.status(HydroLive.sounderConnected, SystemClock.elapsedRealtimeNanos())
            ?: "NO_READING"
        val depthValid = depthState == "VALID_PROVISIONAL"
        if (!demoMode) {
            surveySummary.text = "GNSS • ${gnss.getString("state", "disconnected")} • ${if (live) fixType else "No fresh epoch"}\n" +
                "SOUNDER • ${if (HydroLive.sounderConnected) "Connected" else "Disconnected"}\n" +
                "NAVD88 bottom • withheld: no verified vertical transformation"
            val value = if (depthValid) String.format(Locale.US, "%.2f US survey ft", SurveyUnits.feet(reading!!.meters!!)) else "—"
            liveDepth.text = "DEPTH  $value\n" + when {
                depthValid -> "Valid (provisional field-2 decoder)"
                depthState.startsWith("INVALID") -> "Invalid • $depthState"
                else -> "No Reading • $depthState"
            } + (reading?.let { "\nReceived UTC " + java.time.Instant.ofEpochMilli(it.utcMillis).toString() } ?: "")
            liveDepth.setTextColor(when {
                depthValid -> Color.rgb(89, 220, 145)
                HydroLive.sounderConnected -> Color.rgb(255, 193, 72)
                else -> Color.rgb(255, 110, 110)
            })
            val endNanos = if (HydroLive.recording) SystemClock.elapsedRealtimeNanos() else HydroLive.stoppedNanos
            val seconds = if (HydroLive.startedNanos > 0) ((endNanos - HydroLive.startedNanos) / 1_000_000_000L).coerceAtLeast(0) else 0L
            liveRecording.text = "${if (HydroLive.recording) "RECORDING" else "Stopped"} • " +
                String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) +
                "\n${HydroLive.validCount} valid paired soundings • ${HydroLive.eventCount} raw depth events\n" +
                "Provisional numeric validity; not survey-grade certification" +
                (if (HydroLive.error.isNotBlank()) "\nWrite error: ${HydroLive.error}" else "")
            setupNext.text = if (HydroLive.recording) "STOP LIVE RECORDING" else "START LIVE RECORDING"
            setupNext.isEnabled = true
        } else {
            surveySummary.text = "DEMO • Synthetic observations only"
            liveDepth.text = "DEMO • Live measurements hidden"
            liveRecording.text = "DEMO • Synthetic recording controls below"
            updateDemoUi()
        }
        setupSounder.alpha = if (live) 1f else 0.65f
        readiness.text = "SURVEY READINESS • Provisional acquisition only\nNo verified vertical control"
        sounderStatus.text = depthState
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
    private fun showDemoReview() {
        val sessions = sessionsRepository.list(SessionRepository.Kind.DEMO)
        if (sessions.isEmpty()) {
            Toast.makeText(this, "No demo recordings yet. Start and stop a demo session first.", Toast.LENGTH_LONG).show()
            return
        }
        val labels = sessions.map { "${it.file.name}  •  ${it.observations} observations" }.toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle("DEMO sessions — synthetic only")
            .setItems(labels) { _, index -> showDemoSession(sessions[index]) }
            .setNegativeButton("Close", null).show()
    }

    private fun showDemoSession(session: SessionRepository.Session) {
        val file = session.file
        val samples = try {
            sessionsRepository.read(session).mapNotNull { sample ->
                sample.depthMeters?.let { Triple(sample.latitude, sample.longitude, it) }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid demo session: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
        }
        val minDepth = samples.minOfOrNull { it.third }
        val maxDepth = samples.maxOfOrNull { it.third }
        container.addView(TextView(this).apply {
            text = "DEMO — SYNTHETIC DATA, NOT FOR SURVEY\n" +
                "Observations: ${samples.size}\n" +
                "Depth range: ${if (minDepth != null && maxDepth != null) String.format(Locale.US, "%.2f–%.2f m", minDepth, maxDepth) else "—"}"
            textSize = 16f
            setPadding(12, 12, 12, 12)
        })
        val map = SurveyMapView(this)
        map.tracks = listOf(samples.map { Pair(it.first, it.second) })
        map.current = null
        container.addView(map, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.48f).toInt()))
        container.addView(TextView(this).apply {
            text = "First observations:\n" + samples.take(12).mapIndexed { i, p ->
                String.format(Locale.US, "%d. %.6f, %.6f  •  %.2f m", i + 1, p.first, p.second, p.third)
            }.joinToString("\n")
            setPadding(12, 12, 12, 12)
        })
        android.app.AlertDialog.Builder(this).setTitle("Demo session review")
            .setView(container)
            .setNeutralButton("Export CSV") { _, _ -> exportFile(file) }
            .setPositiveButton("Close", null).show()
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
        val hydro = File(filesDir, "hydro_surveys").listFiles { f -> f.extension == "csv" && !f.name.endsWith(".gnss.csv") }
            ?.maxByOrNull { it.lastModified() }
        val file = listOfNotNull(hydro, latest()).maxByOrNull { it.lastModified() }
        if (file == null) {
            Toast.makeText(this, "No survey CSV to export", Toast.LENGTH_LONG).show()
            return
        }
        exportFile(file)
    }
    private fun exportFile(file: File) {
        try {
            val shareCopy = File(cacheDir, file.name)
            if (file.canonicalFile != shareCopy.canonicalFile) file.inputStream().use { input -> shareCopy.outputStream().use { output -> input.copyTo(output) } }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", shareCopy)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (file.extension == "zip") "application/zip" else "text/csv"
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


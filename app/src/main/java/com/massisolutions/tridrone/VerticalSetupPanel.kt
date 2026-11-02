package com.massisolutions.tridrone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import java.util.Locale

/** Editing a profile is local until Save & use; opening the picker never changes acquisition. */
class VerticalSetupPanel(context: Context, private val profiles: EquipmentProfiles,
    private val onEquipmentChanged: () -> Unit = {}) : LinearLayout(context) {
    private val picker = Spinner(context).apply { id = R.id.profile_picker }
    private val name = EditText(context)
    private val a = EditText(context).apply { id = R.id.geometry_a }
    private val b = EditText(context).apply { id = R.id.geometry_b }
    private val ref = Spinner(context).apply { id = R.id.geometry_reference }
    private val geometryConfirmed = CheckBox(context).apply { id = R.id.geometry_confirmed }
    private val depthReference = Spinner(context).apply { id = R.id.depth_reference }
    private val scale = EditText(context)
    private val correction = EditText(context)
    private val calibrationConfirmed = CheckBox(context).apply { id = R.id.calibration_confirmed }
    private val sounderAddress = EditText(context).apply { id = R.id.profile_sounder_address }
    private val gnssAddress = EditText(context)
    private val gnssTransport = Spinner(context)
    private val host = EditText(context)
    private val port = EditText(context)
    private val baud = EditText(context)
    private val tolerance = EditText(context)
    private val preview = TextView(context).apply { id = R.id.geometry_preview }
    private val equipmentInfo = TextView(context)
    private var entries = emptyList<SounderProfile>()
    private var edited: SounderProfile? = null
    private var loading = false
    private var dirty = false
    private var previousReference = 0
    private var previousDepthReference = 0
    private val referenceCodes = listOf("UNSET") + EquipmentSettings.REFERENCES
    private val depthCodes = listOf("UNKNOWN", "ACOUSTIC_CENTER", "WATERLINE")
    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(20))
        setBackgroundColor(Color.WHITE)
        heading("Vertical setup", 23f)
        label("Two measured separations • US survey feet\nEach installation keeps its own geometry, connections and calibration. Stop recording before changing equipment.")
        addView(picker)
        addView(equipmentInfo)
        edit("Profile name", name, false)
        addView(GeometryDiagram(context), LayoutParams(-1, dp(245)))
        edit("1  GNSS antenna reference → acoustic center (ftUS)", a)
        edit("2  Acoustic center below waterline (ftUS)", b)
        label("GNSS height reference — match the receiver's configured output")
        ref.adapter = adapter(listOf("Select receiver reference", "Physical antenna reference point (ARP)", "Configured receiver output reference"))
        addView(ref)
        geometryConfirmed.text = "I measured both separations and checked the receiver reference"
        addView(geometryConfirmed)
        addView(preview)
        button("Preview geometry", R.id.geometry_preview_button) { updatePreview() }
        heading("Depth calibration", 19f)
        label("Select what the sounder output means. Waterline-referenced depth already includes draft; draft must be applied only once. Confirmation does not establish NAVD88 control.")
        depthReference.adapter = adapter(listOf("Unknown — corrected depths withheld", "Range from acoustic center", "Depth below waterline — draft included"))
        addView(depthReference)
        edit("Depth scale multiplier (1 = unchanged)", scale)
        edit("Depth correction after scaling (ftUS; signed)", correction, signed = true)
        calibrationConfirmed.text = "I checked depth reference, scale and correction against a known depth"
        addView(calibrationConfirmed)
        heading("Profile connections", 19f)
        label("Bluetooth addresses may also be selected from paired-device connection dialogs. Saving disconnects current sensors; reconnect using this profile.")
        edit("Sounder / adapter Bluetooth address", sounderAddress, false)
        label("GNSS connection")
        gnssTransport.adapter = adapter(listOf("BLUETOOTH", "TCP"))
        addView(gnssTransport)
        edit("GNSS Bluetooth address", gnssAddress, false)
        edit("GNSS Wi-Fi host / IP", host, false)
        edit("GNSS TCP port", port, integer = true)
        edit("Adapter serial baud (configure hardware separately)", baud, integer = true)
        edit("GNSS/depth receipt pairing tolerance (1–5000 ms)", tolerance, integer = true)
        button("Save & use this profile", R.id.profile_save) { save() }
        label("Unconfirmed settings may be saved for preparation. Corrected depths remain unavailable until geometry and calibration are confirmed. NAVD88 elevations remain unavailable without independently verified vertical control. Original observations and historical recordings are never recalculated.")
        profiles.seedDefaults()
        entries = profiles.all()
        picker.adapter = adapter(profileLabels())
        val index = entries.indexOfFirst { it.id == profiles.active()?.id }.coerceAtLeast(0)
        picker.setSelection(index)
        load(entries[index])
        for (field in listOf(a, b, scale, correction, name, sounderAddress, gnssAddress, host, port, baud, tolerance)) {
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (loading) return
                    dirty = true
                    if (field == a || field == b) geometryConfirmed.isChecked = false
                    if (field == scale || field == correction) calibrationConfirmed.isChecked = false
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        fun selectionChanged(spinner: Spinner, geometry: Boolean) {
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val previous = if (geometry) previousReference else previousDepthReference
                    if (!loading && previous != position) {
                        dirty = true
                        if (geometry) geometryConfirmed.isChecked = false else calibrationConfirmed.isChecked = false
                    }
                    if (geometry) previousReference = position else previousDepthReference = position
                }
            }
        }
        selectionChanged(ref, true); selectionChanged(depthReference, false)
        gnssTransport.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!loading && gnssTransport.selectedItem.toString() != edited?.connection?.gnssTransport) dirty = true
            }
        }
        geometryConfirmed.setOnCheckedChangeListener { _, _ -> if (!loading) dirty = true }
        calibrationConfirmed.setOnCheckedChangeListener { _, _ -> if (!loading) dirty = true }
        picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (edited?.id == entries[position].id) return
                if (!dirty) load(entries[position]) else android.app.AlertDialog.Builder(context)
                    .setTitle("Discard unsaved profile changes?")
                    .setMessage("Switching the viewed installation leaves the active profile unchanged. Save first to keep your edits.")
                    .setPositiveButton("Discard edits") { _, _ -> load(entries[position]) }
                    .setNegativeButton("Keep editing") { _, _ -> picker.setSelection(entries.indexOfFirst { it.id == edited?.id }) }
                    .setOnCancelListener { picker.setSelection(entries.indexOfFirst { it.id == edited?.id }) }
                    .show()
            }
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun adapter(items: List<String>) = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
    private fun profileLabels() = entries.map { profile ->
        val importedId = Regex(".*_import[0-9]+$")
        if (importedId.matches(profile.id)) {
            // Nested transfers may each end in _import1; rank all copies sharing this name.
            val copies = entries.filter { it.name == profile.name && importedId.matches(it.id) }.sortedBy { it.id }
            "${profile.name} (imported copy ${copies.indexOfFirst { it.id == profile.id } + 1})"
        } else profile.name
    }
    private fun heading(text: String, size: Float) = addView(TextView(context).apply {
        this.text = text; textSize = size; setTextColor(Color.rgb(15, 52, 72)); setPadding(0, dp(14), 0, dp(8))
        setTypeface(null, android.graphics.Typeface.BOLD)
    })
    private fun label(text: String) = addView(TextView(context).apply {
        this.text = text; textSize = 14f; setTextColor(Color.rgb(35, 52, 62)); setPadding(0, dp(5), 0, dp(5))
    })
    private fun edit(label: String, field: EditText, numeric: Boolean = true, integer: Boolean = false, signed: Boolean = false) {
        label(label)
        field.hint = label
        field.minHeight = dp(48)
        field.setTextColor(Color.rgb(20, 35, 45))
        field.setSingleLine()
        field.inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or
            (if (integer) 0 else InputType.TYPE_NUMBER_FLAG_DECIMAL) or
            (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0) else InputType.TYPE_CLASS_TEXT
        addView(field)
    }
    private fun button(text: String, viewId: Int, action: () -> Unit) = addView(Button(context).apply {
        this.text = text; id = viewId; minHeight = dp(52); isAllCaps = false
        setOnClickListener { action() }
    })
    private fun load(p: SounderProfile) {
        loading = true
        edited = p
        name.setText(p.name)
        a.setText(p.geometry.gpsToSounderUsft?.toString().orEmpty())
        b.setText(p.geometry.sounderBelowWaterlineUsft?.toString().orEmpty())
        previousReference = referenceCodes.indexOf(p.geometry.reference).coerceAtLeast(0)
        ref.setSelection(previousReference)
        geometryConfirmed.isChecked = p.geometry.verified
        previousDepthReference = depthCodes.indexOf(p.calibration.depthReference).coerceAtLeast(0)
        depthReference.setSelection(previousDepthReference)
        scale.setText(p.calibration.scale.toString()); correction.setText(p.calibration.offsetUsft.toString())
        calibrationConfirmed.isChecked = p.calibration.confirmed
        sounderAddress.setText(p.connection.sounderAddress); gnssAddress.setText(p.connection.gnssAddress)
        gnssTransport.setSelection(if (p.connection.gnssTransport == "TCP") 1 else 0)
        host.setText(p.connection.gnssHost); port.setText(p.connection.gnssPort.toString())
        baud.setText(p.connection.baud.toString()); tolerance.setText(p.pairToleranceMillis.toString())
        equipmentInfo.text = (if (profiles.active()?.id == p.id) "Active profile\n" else "Viewing only — Save & use to activate\n") + if (p.model == "CEE_LINE")
            "CEE-LINE • Parani SD1000\nSet the SD1000 serial side externally to 9,600 baud and match the CEE-LINE serial format. Android Bluetooth SPP does not configure baud. Protocol unverified: raw diagnostic capture only; no decoded soundings."
            else "TriDrone / HydroLite Plus\nBluetooth SPP • field-2 meters decoder is provisional; verify with hardware."
        updatePreview()
        loading = false
        dirty = false
    }
    private fun distance(field: EditText): Double? {
        if (field.text.isBlank()) return null
        return field.text.toString().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
            ?: throw IllegalArgumentException("Enter finite, nonnegative separations")
    }
    private fun geometry() = VerticalGeometry(distance(a), distance(b), referenceCodes[ref.selectedItemPosition], geometryConfirmed.isChecked)
    private fun save() {
        try {
            val p = edited ?: error("Select an installation")
            val g = geometry()
            require(!geometryConfirmed.isChecked || g.usable) { "Enter both measurements and select the receiver reference before confirming" }
            val cal = DepthCalibration(depthCodes[depthReference.selectedItemPosition],
                scale.text.toString().toDoubleOrNull() ?: error("Enter depth scale"),
                correction.text.toString().toDoubleOrNull() ?: error("Enter depth correction"), calibrationConfirmed.isChecked)
            val connection = ProfileConnection(sounderAddress.text.toString().trim(), gnssAddress.text.toString().trim(),
                gnssTransport.selectedItem.toString(), host.text.toString().trim(),
                port.text.toString().toIntOrNull() ?: error("Enter TCP port"),
                baud.text.toString().toIntOrNull() ?: error("Enter adapter serial baud"))
            val updated = p.copy(name = name.text.toString().trim(), geometry = g, connection = connection,
                calibration = cal, pairToleranceMillis = tolerance.text.toString().toLongOrNull() ?: error("Enter pairing tolerance"),
                checkedUtcMs = if (g.verified || cal.confirmed) System.currentTimeMillis() else 0L)
            require(!cal.confirmed || cal.usable) { "Choose a known depth reference and valid calibration before confirming" }
            profiles.save(updated)
            profiles.select(updated.id)
            onEquipmentChanged()
            dirty = false
            entries = profiles.all()
            val index = entries.indexOfFirst { it.id == updated.id }
            picker.adapter = adapter(profileLabels()); picker.setSelection(index)
            load(updated)
            Toast.makeText(context, "Using ${updated.name}. Reconnect sensors; historical data unchanged.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, e.message ?: "Profile not saved", Toast.LENGTH_LONG).show()
        }
    }
    private fun updatePreview() {
        preview.setTextColor(Color.rgb(30, 60, 70))
        preview.setPadding(0, dp(10), 0, dp(8))
        preview.text = try {
            val first = distance(a); val second = distance(b)
            if (first != null && second != null) String.format(Locale.US,
                "GNSS reference above waterline: %.3f ftUS\nTransducer draft: %.3f ftUS\n%s\nNAVD88 elevations: unavailable without verified vertical control.",
                first - second, second,
                if (second > first) "Check geometry: waterline is above GNSS reference." else "For acoustic-center range, waterline depth = range + draft.")
            else "Measurements incomplete • corrected depths withheld.\nNAVD88 control is a separate field verification."
        } catch (e: IllegalArgumentException) { e.message }
    }
}

/** Vector illustration: reference and measurement endpoints remain clear at phone widths. */
class GeometryDiagram(context: Context) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    init { contentDescription = "Vertical setup illustration. Measurement 1 from GNSS reference down to sounder acoustic center. Measurement 2 from waterline down to acoustic center." }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.save(); c.scale(width / 340f, height / 245f)
        pen.style = Paint.Style.FILL; pen.color = Color.rgb(235, 247, 252)
        c.drawRect(0f, 105f, 340f, 245f, pen)
        pen.color = Color.rgb(20, 91, 125); pen.strokeWidth = 2f
        c.drawLine(8f, 105f, 330f, 105f, pen)
        pen.color = Color.rgb(57, 72, 81); pen.strokeWidth = 7f
        c.drawLine(106f, 32f, 106f, 174f, pen)
        pen.color = Color.rgb(15, 80, 110)
        c.drawOval(85f, 20f, 127f, 40f, pen)
        c.drawRoundRect(88f, 161f, 124f, 184f, 5f, 5f, pen)
        pen.textSize = 14f
        c.drawText("GNSS reference", 140f, 34f, pen)
        c.drawText("Waterline", 230f, 96f, pen)
        c.drawText("Acoustic center", 140f, 178f, pen)
        pen.color = Color.rgb(158, 78, 12); pen.strokeWidth = 2f
        arrow(c, 30f, 31f, 174f); arrow(c, 65f, 105f, 174f)
        pen.textSize = 17f
        c.drawText("1", 12f, 88f, pen); c.drawText("2", 47f, 145f, pen)
        pen.color = Color.rgb(40, 65, 80); pen.textSize = 13f
        c.drawText("Sounder range continues down to bottom ↓", 40f, 222f, pen)
        c.restore()
    }
    private fun arrow(c: Canvas, x: Float, top: Float, bottom: Float) {
        c.drawLine(x, top, x, bottom, pen)
        c.drawLine(x - 5, top + 7, x, top, pen); c.drawLine(x + 5, top + 7, x, top, pen)
        c.drawLine(x - 5, bottom - 7, x, bottom, pen); c.drawLine(x + 5, bottom - 7, x, bottom, pen)
    }
}

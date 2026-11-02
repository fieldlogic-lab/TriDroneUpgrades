package com.massisolutions.tridrone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.*
import android.text.InputType
import java.util.Locale

/** Field geometry diagram and editable calibration panel. Host activity wires navigation. */
class VerticalSetupPanel(context: Context, private val profiles: EquipmentProfiles) : LinearLayout(context) {
    private val picker = Spinner(context)
    private val a = EditText(context)
    private val b = EditText(context)
    private val ref = Spinner(context)
    private val preview = TextView(context)
    private var ids = listOf<String>()
    init {
        orientation = VERTICAL
        setPadding(20, 16, 20, 20)
        addView(TextView(context).apply { text = "VERTICAL SETUP"; textSize = 23f })
        addView(TextView(context).apply {
            text = "Select the instrument installation. Measure the vertical separation between reference points; record sounder submergence below the waterline. Units: US survey feet."
            textSize = 14f
        })
        addView(picker)
        addView(GeometryDiagram(context), LayoutParams(LayoutParams.MATCH_PARENT, 310))
        addView(TextView(context).apply { text = "1  GNSS reference down to sounder acoustic center (ftUS)" })
        a.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        a.hint = "Unmeasured"
        addView(a)
        addView(TextView(context).apply { text = "2  Sounder acoustic center below waterline (ftUS)" })
        b.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        b.hint = "Unmeasured"
        addView(b)
        addView(TextView(context).apply { text = "GNSS height reference (must match receiver output)" })
        ref.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
            listOf("UNSET") + EquipmentSettings.REFERENCES)
        addView(ref)
        addView(preview)
        addView(Button(context).apply {
            text = "PREVIEW GEOMETRY"
            setOnClickListener { updatePreview() }
        })
        addView(Button(context).apply {
            text = "SAVE AND CONFIRM EQUIPMENT CALIBRATION"
            setOnClickListener {
                try {
                    val p = profiles.active() ?: error("Select an equipment profile")
                    // Saving requires both measured values and an explicit receiver reference.
                    val geometry = VerticalGeometry(a.text.toString().toDoubleOrNull(),
                        b.text.toString().toDoubleOrNull(), ref.selectedItem.toString(), true)
                    require(geometry.usable) { "Enter both nonnegative distances and select the GNSS reference" }
                    profiles.save(p.copy(geometry = geometry, checkedUtcMs = System.currentTimeMillis()))
                    updatePreview()
                    Toast.makeText(context, "Calibration saved to ${p.name}", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, e.message ?: "Calibration not saved", Toast.LENGTH_LONG).show()
                }
            }
        })
        reload()
    }
    private fun reload() {
        profiles.seedDefaults()
        val entries = profiles.all()
        ids = entries.map { it.id }
        picker.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, entries.map { it.name })
        picker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                try {
                    profiles.select(ids[position])
                    val g = profiles.active()!!.geometry
                    a.setText(g.gpsToSounderUsft?.toString().orEmpty())
                    b.setText(g.sounderBelowWaterlineUsft?.toString().orEmpty())
                    ref.setSelection((listOf("UNSET") + EquipmentSettings.REFERENCES).indexOf(g.reference).coerceAtLeast(0))
                    updatePreview()
                } catch (e: Exception) {
                    Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                }
            }
        }
        val index = ids.indexOf(profiles.active()?.id).coerceAtLeast(0)
        picker.setSelection(index)
    }
    private fun updatePreview() {
        val g = VerticalGeometry(a.text.toString().toDoubleOrNull(),
            b.text.toString().toDoubleOrNull(), ref.selectedItem?.toString() ?: "UNSET", true)
        preview.text = if (g.usable)
            String.format(Locale.US, "GNSS reference above waterline: %.3f ftUS\n" +
                "Transducer draft: %.3f ftUS\n" +
                "Bottom depth = draft + actual sounder depth\n" +
                "NAVD88 and tidal-datum elevations withheld until verified.",
                g.gpsAboveWaterlineUsft(), g.sounderBelowWaterlineUsft)
            else "Calibration incomplete. No corrected depths may be certified."
    }
}

/** Diagram depicts the *waterline above* a submerged acoustic center. */
class GeometryDiagram(context: Context) : View(context) {
    private val pen = Paint(3).apply { textSize = 28f; strokeWidth = 4f }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val sx = width / 370f; val sy = height / 300f
        c.save(); c.scale(sx, sy)
        pen.color = Color.rgb(20, 80, 105); pen.style = Paint.Style.STROKE
        c.drawLine(115f, 30f, 115f, 260f, pen)
        c.drawLine(20f, 130f, 350f, 130f, pen)
        pen.style = Paint.Style.FILL
        pen.color = Color.rgb(17, 101, 125)
        c.drawCircle(115f, 30f, 9f, pen)
        c.drawCircle(115f, 184f, 9f, pen)
        pen.textSize = 19f
        c.drawText("GNSS reference", 132f, 36f, pen)
        c.drawText("Waterline", 220f, 120f, pen)
        c.drawText("Sounder center", 135f, 190f, pen)
        pen.color = Color.rgb(147, 90, 25)
        c.drawLine(75f, 30f, 75f, 184f, pen)
        c.drawLine(93f, 130f, 93f, 184f, pen)
        pen.textSize = 22f
        c.drawText("1", 54f, 93f, pen)
        c.drawText("2", 96f, 160f, pen)
        pen.color = Color.DKGRAY
        pen.textSize = 18f
        c.drawText("Measured depth ↓", 135f, 260f, pen)
        c.restore()
    }
}

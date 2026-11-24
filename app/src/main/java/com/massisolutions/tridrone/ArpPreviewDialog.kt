package com.massisolutions.tridrone

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale

internal fun showArpPreview(context: Context, settings: EquipmentSettings) {
    val offset = settings.arpToTransducerFaceUsft
    val provenance = settings.verticalProvenance
    if (offset == null || !settings.confirmed || !provenance.declaresGnssHeightAtArp ||
        provenance.gnssHeightReferenceEvidence.isNullOrBlank() || !provenance.declaresDepthFromFace ||
        provenance.sounderAcousticReferenceEvidence.isNullOrBlank()) {
        AlertDialog.Builder(context)
            .setTitle("Saved ARP geometry is incomplete")
            .setMessage("Save a checked physical ARP-to-transducer-face distance, a receiver height reference declaration with evidence, and a raw acoustic depth reference declaration with evidence.")
            .setPositiveButton("Close", null)
            .show()
        return
    }

    val padding = (16 * context.resources.displayMetrics.density).toInt()
    val form = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, padding)
    }
    val unitLabels = listOf("Select unit", "Meters", "International feet", "US survey feet")
    val units = listOf(VerticalUnit.METER, VerticalUnit.INTERNATIONAL_FOOT, VerticalUnit.US_SURVEY_FOOT)
    fun unitPicker(description: String) = Spinner(context).apply {
        contentDescription = description
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, unitLabels)
    }

    val elevation = EditText(context).apply {
        hint = "Elevation already reduced to physical ARP"
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
    }
    val elevationUnit = unitPicker("ARP elevation unit")
    val datum = EditText(context).apply {
        hint = "Documented vertical datum or project reference"
        setSingleLine()
    }
    val depth = EditText(context).apply {
        hint = "Raw acoustic depth below transducer face"
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
    val depthUnit = unitPicker("Acoustic depth unit")
    val heightConfirmed = CheckBox(context).apply {
        text = "I checked the entered height is at physical ARP in this vertical reference"
    }
    listOf(
        TextView(context).apply {
            text = "Saved distance A: $offset US survey feet downward. Enter a normalized height and raw acoustic depth for one provisional check. Values are temporary."
        },
        elevation, elevationUnit, datum, heightConfirmed, depth, depthUnit
    ).forEach(form::addView)

    val preview = AlertDialog.Builder(context)
        .setTitle("Provisional ARP geometry")
        .setView(ScrollView(context).apply { addView(form) })
        .setNegativeButton("Close", null)
        .setPositiveButton("Calculate", null)
        .create()
    preview.setOnShowListener {
        preview.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val heightValue = elevation.text.toString().toDoubleOrNull()
            val heightUnit = units.getOrNull(elevationUnit.selectedItemPosition - 1)
            val reference = datum.text.toString().trim()
            val depthValue = depth.text.toString().toDoubleOrNull()
            val rawDepthUnit = units.getOrNull(depthUnit.selectedItemPosition - 1)
            val result = if (heightValue != null && heightUnit != null && depthValue != null && rawDepthUnit != null) {
                BottomElevation.calculateArpMeters(
                    VerticalMeasurement(heightValue, heightUnit),
                    VerticalMeasurement(offset, VerticalUnit.US_SURVEY_FOOT),
                    VerticalMeasurement(depthValue, rawDepthUnit)
                )
            } else null
            val error = when {
                reference.isBlank() -> "Enter a documented vertical datum or project reference."
                !heightConfirmed.isChecked -> "Confirm the height reference for this check."
                result == null -> "Enter a finite elevation, a positive depth, and a unit for each."
                else -> null
            }
            if (error != null) {
                AlertDialog.Builder(context).setMessage(error).setPositiveButton("OK", null).show()
                return@setOnClickListener
            }
            val geometry = checkNotNull(result)
            val report = String.format(
                Locale.US,
                "Vertical reference: %s\n\nTransducer face: %.3f m\nBottom: %.3f m\n\nBottom uses raw acoustic depth below the face. Submergence is a separate water-depth measurement.",
                reference, geometry.transducerFace, geometry.bottom
            )
            AlertDialog.Builder(context)
                .setTitle("Provisional result")
                .setMessage(report)
                .setPositiveButton("OK", null)
                .show()
        }
    }
    preview.show()
}

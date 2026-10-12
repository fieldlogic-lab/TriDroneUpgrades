package com.massisolutions.tridrone

import java.util.Locale
import java.util.UUID

/** Presentation only. Receiver observations and raw exports always remain meters. */
enum class DistanceUnit(val code: String, val label: String, val symbol: String,
    val metersPerUnit: Double, val definition: String) {
    US_SURVEY_FEET("usft", "US survey feet", "ftUS", 1200.0 / 3937.0, "1 ftUS = 1200/3937 meters (exact)"),
    INTERNATIONAL_FEET("ift", "International feet", "ft", 0.3048, "1 ft = 0.3048 meters (exact)"),
    METERS("m", "Meters", "m", 1.0, "1 m = 1 meter (exact)");

    fun fromMeters(meters: Double): Double = meters / metersPerUnit
    companion object {
        fun fromCode(code: String): DistanceUnit? = entries.firstOrNull { it.code == code } ?:
            if (code == "ft") INTERNATIONAL_FEET else null
    }
}

object DisplayUnits {
    fun label(units: String): String = DistanceUnit.fromCode(units)?.label ?: "Unsupported display units ($units)"
    fun formatPrecision(meters: Double?, units: String): String {
        val unit = DistanceUnit.fromCode(units) ?: return "UNAVAILABLE"
        if (meters == null || !meters.isFinite() || meters < 0) return "UNAVAILABLE"
        val converted = unit.fromMeters(meters)
        return if (converted.isFinite()) String.format(Locale.US, "%.3f %s", converted, unit.symbol) else "UNAVAILABLE"
    }
}

object SessionIdentity {
    /** Human labels never become paths. UUID distinguishes captures within one millisecond. */
    fun newId(utcMillis: Long): String = "${utcMillis}_${UUID.randomUUID()}"
    fun safeLabel(value: String): String = value.filterNot { it.isISOControl() }.trim().take(120)
}

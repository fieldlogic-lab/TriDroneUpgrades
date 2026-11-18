package com.massisolutions.tridrone

import kotlin.math.abs

/** NMEA DBT is below the acoustic transducer, never below the water surface. */
object CeeLineDbt {
    const val PROTOCOL = "CEE_LINE_NMEA_DBT_V1"
    fun decode(raw: String, utc: Long, nanos: Long): HydroDepth {
        fun invalid(reason: String) = HydroDepth(raw, utc, nanos, null, reason, "CEE-LINE DBT", PROTOCOL)
        val line = raw.trim()
        if (!line.startsWith("\$SDDBT,")) return invalid("INVALID_SENTENCE")
        if (!NmeaGga.valid(line)) return invalid("INVALID_CHECKSUM")
        val f = line.substringBefore('*').split(',')
        if (f.size != 7 || f[2] != "f" || f[4] != "M" || f[6] != "F") return invalid("INVALID_MALFORMED")
        val values = listOf(1 to 0.3048, 3 to 1.0, 5 to 1.8288).map { (i, factor) ->
            if (f[i].isEmpty()) null else {
                val value = f[i].toDoubleOrNull()
                if (value == null || !value.isFinite() || value < 0) return invalid("INVALID_RANGE")
                value * factor
            }
        }
        val meters = values[1] ?: values[0] ?: values[2] ?: return invalid("INVALID_MALFORMED")
        if (!SurveyUnits.feet(meters).isFinite()) return invalid("INVALID_RANGE")
        if (meters <= 0 || values.any { it != null && it <= 0 }) return invalid("INVALID_NO_BOTTOM")
        // Two-decimal NMEA representations can differ slightly after conversion.
        if (values.any { it != null && abs(it - meters) > 0.03 }) return invalid("INVALID_CONFLICTING_UNITS")
        return HydroDepth(raw, utc, nanos, meters, "VALID_PROVISIONAL", "Raw DBT below transducer", PROTOCOL)
    }
}

object WaterDepth {
    /** Both terms are lengths. A submerged transducer has a positive waterline offset. */
    fun meters(dbtMeters: Double?, submergedOffsetMeters: Double?): Double? =
        if (dbtMeters != null && dbtMeters.isFinite() && dbtMeters > 0 &&
            submergedOffsetMeters != null && submergedOffsetMeters.isFinite() && submergedOffsetMeters >= 0)
            (dbtMeters + submergedOffsetMeters).takeIf { it.isFinite() && SurveyUnits.feet(it).isFinite() } else null
}

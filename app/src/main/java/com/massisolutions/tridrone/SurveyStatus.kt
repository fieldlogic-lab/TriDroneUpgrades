package com.massisolutions.tridrone

import java.util.Locale

internal enum class PrecisionState { VALID, DEGRADED, UNAVAILABLE, STALE }

internal data class PrecisionIndicator(val state: PrecisionState, val text: String)

internal fun receiverPrecision(
    connected: Boolean, fixAge: Long?, gstAge: Long?, fixQuality: Int?,
    horizontalMeters: Double?, verticalMeters: Double?, fixLimit: Long = 5000L
): PrecisionIndicator {
    if (!connected) return PrecisionIndicator(PrecisionState.UNAVAILABLE, "H: —   V: —  •  Disconnected")
    if ((fixAge != null && fixAge !in 0..fixLimit) || (gstAge != null && gstAge !in 0..3000)) {
        return PrecisionIndicator(PrecisionState.STALE, "H: —   V: —  •  Stale")
    }
    fun format(meters: Double?): String? = meters?.takeIf { it.isFinite() && it >= 0 }
        ?.let { String.format(Locale.US, "%.2f", SurveyUnits.feet(it)) }
    val horizontal = format(horizontalMeters)
    val vertical = format(verticalMeters)
    if (fixAge == null || (fixQuality ?: 0) !in 1..5 || gstAge == null || horizontal == null || vertical == null) {
        return PrecisionIndicator(PrecisionState.UNAVAILABLE, "H: —   V: —  •  Unavailable")
    }
    val state = if (fixQuality == 4) PrecisionState.VALID else PrecisionState.DEGRADED
    val status = if (state == PrecisionState.DEGRADED) " • Degraded fix" else ""
    return PrecisionIndicator(state, "H: $horizontal   V: $vertical US survey ft$status")
}

internal data class SurveyDepthIndicator(val title: String, val status: String?)

internal fun surveyDepth(depthState: String, connected: Boolean, meters: Double?): SurveyDepthIndicator {
    val status = when {
        !connected -> "Disconnected"
        depthState == "NO_READING_STALE" -> "Stale"
        depthState.startsWith("INVALID") -> "Invalid"
        depthState != "VALID_PROVISIONAL" -> "Awaiting depth"
        meters == null || !meters.isFinite() -> "Depth unavailable"
        else -> null
    }
    val value = if (status == null && meters != null) {
        String.format(Locale.US, "%.2f US survey ft", SurveyUnits.feet(meters))
    } else "—"
    return SurveyDepthIndicator("WATER DEPTH  $value", status)
}

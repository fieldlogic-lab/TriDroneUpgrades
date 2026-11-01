package com.massisolutions.tridrone

import kotlin.math.cos
import kotlin.math.max

/** Geographic view state only, independent of recording and Android gestures. */
class SurveyViewport {
    enum class Mode { FREE_PAN, FOLLOW_VESSEL }
    var mode = Mode.FREE_PAN; private set
    var latitude = 0.0; private set
    var longitude = 0.0; private set
    var span = 0.0001; private set
    var initialized = false; private set
    private var initialFramePending = true
    val longitudeScale get() = cos(Math.toRadians(latitude)).coerceAtLeast(0.01)

    fun receive(recorded: List<MapPosition>, current: MapPosition?) {
        if (initialFramePending && recorded.any { it.valid }) {
            fit(recorded); initialFramePending = false
        } else if (!initialized && current?.valid == true) {
            center(current)
        }
        if (mode == Mode.FOLLOW_VESSEL && current?.valid == true) center(current)
    }
    fun fit(recorded: List<MapPosition>): Boolean {
        val points = recorded.filter { it.valid }
        if (points.isEmpty()) return false
        val latMin = points.minOf { it.latitude }; val latMax = points.maxOf { it.latitude }
        val longitudes = points.map { (it.longitude + 360) % 360 }.sorted()
        var gap = -1.0; var arcStart = longitudes.first()
        for (i in longitudes.indices) {
            val next = if (i == longitudes.lastIndex) longitudes.first() + 360 else longitudes[i + 1]
            if (next - longitudes[i] > gap) { gap = next - longitudes[i]; arcStart = next % 360 }
        }
        latitude = (latMin + latMax) / 2
        longitude = wrap(arcStart + (360 - gap) / 2)
        span = max(0.0001, max(latMax - latMin, (360 - gap) * longitudeScale) * 1.28)
        initialized = true; initialFramePending = false; mode = Mode.FREE_PAN
        return true
    }
    fun follow(current: MapPosition?) {
        mode = Mode.FOLLOW_VESSEL; initialFramePending = false
        if (current?.valid == true) center(current)
    }
    fun freePan() { mode = Mode.FREE_PAN; initialFramePending = false }
    fun pan(dxDegrees: Double, dyDegrees: Double) {
        freePan()
        longitude = wrap(longitude + dxDegrees / longitudeScale)
        latitude = (latitude + dyDegrees).coerceIn(-90.0, 90.0)
        initialized = true
    }
    fun zoom(factor: Double) {
        freePan()
        if (factor.isFinite() && factor > 0) {
            span = (span / factor).coerceIn(0.000001, 360.0); initialized = true
        }
    }
    fun x(point: MapPosition) = wrap(point.longitude - longitude) * longitudeScale
    fun y(point: MapPosition) = point.latitude - latitude
    private fun center(point: MapPosition) {
        latitude = point.latitude; longitude = point.longitude; initialized = true
    }
    companion object { fun wrap(value: Double) = ((value + 180) % 360 + 360) % 360 - 180 }
}

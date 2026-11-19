package com.massisolutions.tridrone

data class LiveMapSnapshot(
    val current: Pair<Double, Double>?,
    val track: List<Pair<Double, Double>>,
    val soundings: List<Pair<Double, Double>>
)

/** Keep a bounded preview of the whole run. The acquisition database still retains every sounding. */
internal class SurveyMapTrace(private val limit: Int = 2000) {
    private val points = mutableListOf<Pair<Double, Double>>()

    init { require(limit >= 3) }

    fun add(point: Pair<Double, Double>) {
        if (point == points.lastOrNull()) return
        if (points.size == limit) {
            for (index in points.lastIndex - 1 downTo 1 step 2) points.removeAt(index)
        }
        points.add(point)
    }

    fun clear() = points.clear()
    fun snapshot(): List<Pair<Double, Double>> = points.toList()
}

package com.massisolutions.tridrone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/** Offline survey plot and live GNSS coverage. */
class SurveyMapView(context: Context) : View(context) {
    var tracks: List<List<Pair<Double, Double>>> = emptyList()
        set(value) { field = value; invalidate() }
    var current: Pair<Double, Double>? = null
        set(value) { field = value; invalidate() }
    private var soundings = emptyList<Pair<Double, Double>>()
    private var followsBoat = false

    fun showCoverage(snapshot: LiveMapSnapshot, follow: Boolean) {
        followsBoat = follow
        soundings = snapshot.soundings
        tracks = listOf(snapshot.track)
        current = snapshot.current
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(238, 244, 247))
        val all = tracks.flatten() + soundings + listOfNotNull(current)
        if (all.isEmpty()) {
            paint.color = Color.DKGRAY
            paint.textSize = 14f * resources.displayMetrics.density
            canvas.drawText("Awaiting GNSS positions", 18f, height / 2f, paint)
            return
        }
        val boat = current.takeIf { followsBoat }
        val center = boat ?: (
            (all.minOf { it.first } + all.maxOf { it.first }) / 2 to
                (all.minOf { it.second } + all.maxOf { it.second }) / 2
            )
        val (lat0, lon0) = center
        val lonScale = cos(Math.toRadians(lat0)).coerceAtLeast(0.01)
        fun x(p: Pair<Double, Double>) = (p.second - lon0) * lonScale
        fun y(p: Pair<Double, Double>) = p.first - lat0
        val extent = max(0.00001, 2 * max(all.maxOf { abs(x(it)) }, all.maxOf { abs(y(it)) }))
        val scale = (minOf(width, height) * 0.78 / extent).toFloat()
        fun px(p: Pair<Double, Double>) = width / 2f + (x(p) * scale).toFloat()
        fun py(p: Pair<Double, Double>) = height / 2f - (y(p) * scale).toFloat()
        paint.strokeWidth = 2f
        paint.color = Color.rgb(201, 218, 227)
        for (i in 1..4) {
            canvas.drawLine(width * i / 5f, 0f, width * i / 5f, height.toFloat(), paint)
            canvas.drawLine(0f, height * i / 5f, width.toFloat(), height * i / 5f, paint)
        }
        tracks.forEachIndexed { index, track ->
            paint.color = if (index == 0) Color.rgb(12, 91, 153) else Color.rgb(116, 143, 158)
            paint.strokeWidth = if (index == 0) 4f else 2f
            for (index in 1 until track.size) {
                val a = track[index - 1]
                val b = track[index]
                canvas.drawLine(px(a), py(a), px(b), py(b), paint)
            }
            track.forEach { canvas.drawCircle(px(it), py(it), 3f, paint) }
        }
        paint.color = Color.rgb(150, 68, 19)
        soundings.forEach { canvas.drawCircle(px(it), py(it), 4f, paint) }
        current?.let {
            paint.color = Color.WHITE
            canvas.drawCircle(px(it), py(it), 16f, paint)
            paint.color = Color.rgb(15, 146, 95)
            canvas.drawCircle(px(it), py(it), 11f, paint)
        }
        paint.color = Color.rgb(22, 47, 61)
        paint.textSize = 30f
        canvas.drawText("N ↑", 22f, 42f, paint)
        paint.textSize = 12f * resources.displayMetrics.density
        canvas.drawText(if (soundings.isEmpty()) "Offline coverage" else "Offline • orange soundings",
            18f, height - 12f, paint)
    }
}

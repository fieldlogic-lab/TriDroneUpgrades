package com.massisolutions.tridrone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.cos
import kotlin.math.max

/** Offline survey plot: not a basemap or georeferenced aerial image. */
class SurveyMapView(context: Context) : View(context) {
    var tracks: List<List<Pair<Double, Double>>> = emptyList()
        set(value) { field = value; invalidate() }
    var current: Pair<Double, Double>? = null
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(238, 244, 247))
        val all = tracks.flatten() + listOfNotNull(current)
        if (all.isEmpty()) {
            paint.color = Color.DKGRAY
            paint.textSize = 38f
            canvas.drawText("No recorded positions yet", 32f, height / 2f, paint)
            return
        }
        val lat0 = all.map { it.first }.average()
        val lon0 = all.map { it.second }.average()
        val lonScale = cos(Math.toRadians(lat0)).coerceAtLeast(0.01)
        fun x(p: Pair<Double, Double>) = (p.second - lon0) * lonScale
        fun y(p: Pair<Double, Double>) = p.first - lat0
        val extent = max(0.00001, max(all.maxOf { x(it) } - all.minOf { x(it) }, all.maxOf { y(it) } - all.minOf { y(it) }))
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
            track.zipWithNext().forEach { (a, b) -> canvas.drawLine(px(a), py(a), px(b), py(b), paint) }
            track.forEach { canvas.drawCircle(px(it), py(it), 3f, paint) }
        }
        current?.let {
            paint.color = Color.WHITE
            canvas.drawCircle(px(it), py(it), 16f, paint)
            paint.color = Color.rgb(15, 146, 95)
            canvas.drawCircle(px(it), py(it), 11f, paint)
        }
        paint.color = Color.rgb(22, 47, 61)
        paint.textSize = 30f
        canvas.drawText("N ↑", 22f, 42f, paint)
        paint.textSize = 24f
        canvas.drawText("Offline relative plot • no basemap", 22f, height - 22f, paint)
    }
}

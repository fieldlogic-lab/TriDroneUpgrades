package com.massisolutions.tridrone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs

/** Offline relative geographic plot. No imagery, heading, datum conversion or acquisition ownership. */
class SurveyMapView(context: Context) : View(context) {
    val viewport = SurveyViewport()
    var onModeChanged: (() -> Unit)? = null
    var onSoundingSelected: ((MapSounding) -> Unit)? = null
    var onRenderError: (() -> Unit)? = null
    private var recorded: List<MapPosition> = emptyList()
    private var soundingData: List<MapSounding> = emptyList()
    private var trackPaths: List<Path> = emptyList()
    private var fixPath = Path()
    private var floatPath = Path()
    private var otherPath = Path()
    private var dirty = true
    private var legacyAutoFrame = true
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var legacyTracks: List<List<MapPosition>> = emptyList()
    var tracks: List<List<Pair<Double, Double>>> = emptyList()
        set(value) {
            field = value
            legacyAutoFrame = true
            legacyTracks = value.map { track -> track.map { MapPosition(it.first, it.second) }.filter { it.valid } }
            recorded = legacyTracks.flatten(); refresh()
        }
    var current: Pair<Double, Double>? = null
        set(value) { field = value?.takeIf { MapPosition(it.first, it.second).valid }; refresh() }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!scales.isInProgress) {
                val scale = pixelsPerDegree()
                viewport.pan(distanceX / scale, -distanceY / scale)
                moved()
            }
            return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            performClick()
            selectSounding(e.x, e.y)?.let { onSoundingSelected?.invoke(it) }
            return true
        }
    })
    private val scales = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            viewport.zoom(detector.scaleFactor.toDouble()); moved(); return true
        }
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            viewport.freePan(); moved(); return true
        }
    })

    fun beginSurvey() { viewport.beginSurvey(); dirty = true }
    fun showSurvey(data: HydroMapData, live: MapPosition?) {
        legacyAutoFrame = false
        recorded = data.track
        legacyTracks = listOf(recorded)
        soundingData = data.soundings
        current = live?.takeIf { it.valid }?.pair()
    }
    fun fitSurvey(): Boolean {
        val fitted = viewport.fit(recorded)
        if (fitted) moved()
        return fitted
    }
    fun followVessel() { viewport.follow(live()); moved() }
    fun freePan() { viewport.freePan(); moved() }
    private fun live() = current?.let { MapPosition(it.first, it.second) }
    private fun refresh() {
        if (legacyAutoFrame) viewport.receiveLegacy(recorded, live()) else viewport.receive(recorded, live())
        dirty = true; invalidate()
    }
    private fun moved() { dirty = true; invalidate(); onModeChanged?.invoke() }
    private fun pixelsPerDegree() = minOf(width, height).coerceAtLeast(1).toDouble() / viewport.span
    private fun px(point: MapPosition) = (width / 2.0 + viewport.x(point) * pixelsPerDegree()).toFloat()
    private fun py(point: MapPosition) = (height / 2.0 - viewport.y(point) * pixelsPerDegree()).toFloat()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { dirty = true }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scales.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    /** Hit-test original records, even where display markers are batched into the same screen cell. */
    fun selectSounding(x: Float, y: Float): MapSounding? {
        val radius = 24 * density
        var best = radius * radius
        var selected: MapSounding? = null
        for (sounding in soundingData) {
            val dx = px(sounding.position) - x; val dy = py(sounding.position) - y
            val distance = dx * dx + dy * dy
            if (distance <= best) { best = distance; selected = sounding }
        }
        return selected
    }

    private fun preparePaths() {
        trackPaths = legacyTracks.map { track -> Path().apply {
            if (track.size == 1) addCircle(px(track[0]), py(track[0]), 3 * density, Path.Direction.CW)
            var previous: MapPosition? = null
            track.forEach { point ->
                if (previous == null || abs(viewport.x(point) - viewport.x(previous!!)) > 180 * viewport.longitudeScale) {
                    moveTo(px(point), py(point))
                } else lineTo(px(point), py(point))
                previous = point
            }
        } }
        fixPath.rewind(); floatPath.rewind(); otherPath.rewind()
        val occupied = HashSet<String>()
        val cell = 12 * density
        for (sounding in soundingData) {
            val x = px(sounding.position); val y = py(sounding.position)
            if (x < -cell || y < -cell || x > width + cell || y > height + cell) continue
            // One marker of each quality per cell, preserving every record for inspection and exports.
            if (!occupied.add("${(x / cell).toInt()},${(y / cell).toInt()},${sounding.quality}")) continue
            val radius = 4 * density
            when (sounding.quality) {
                4 -> fixPath.addCircle(x, y, radius, Path.Direction.CW)
                5 -> { floatPath.moveTo(x, y - radius * 1.4f); floatPath.lineTo(x + radius, y + radius)
                    floatPath.lineTo(x - radius, y + radius); floatPath.close() }
                else -> otherPath.addRect(x - radius, y - radius, x + radius, y + radius, Path.Direction.CW)
            }
        }
        dirty = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(238, 244, 247))
        try {
            if (dirty) preparePaths()
            paint.style = Paint.Style.STROKE; paint.strokeWidth = density
            paint.color = Color.rgb(201, 218, 227)
            for (i in 1..4) {
                canvas.drawLine(width * i / 5f, 0f, width * i / 5f, height.toFloat(), paint)
                canvas.drawLine(0f, height * i / 5f, width.toFloat(), height * i / 5f, paint)
            }
            paint.strokeWidth = 2 * density
            trackPaths.forEachIndexed { index, path ->
                paint.color = if (index == 0) Color.rgb(12, 91, 153) else Color.rgb(116, 143, 158)
                canvas.drawPath(path, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(13, 110, 63); canvas.drawPath(fixPath, paint)
            paint.color = Color.rgb(174, 90, 0); canvas.drawPath(floatPath, paint)
            paint.color = Color.rgb(99, 70, 153); canvas.drawPath(otherPath, paint)
            live()?.let {
                paint.color = Color.WHITE; canvas.drawCircle(px(it), py(it), 10 * density, paint)
                paint.color = Color.rgb(15, 96, 190); canvas.drawCircle(px(it), py(it), 7 * density, paint)
                paint.color = Color.WHITE; canvas.drawCircle(px(it), py(it), 2 * density, paint)
            }
            paint.color = Color.rgb(22, 47, 61); paint.textSize = 13 * density
            canvas.drawText("N ↑", 10 * density, 22 * density, paint)
            if (!viewport.initialized) canvas.drawText("Awaiting valid GNSS / recorded coordinates", 10 * density, height / 2f, paint)
            canvas.drawText("Offline relative plot • no basemap", 10 * density, height - 10 * density, paint)
        } catch (_: Exception) {
            paint.style = Paint.Style.FILL; paint.color = Color.DKGRAY; paint.textSize = 14 * density
            canvas.drawText("Map unavailable • recording is independent", 10 * density, height / 2f, paint)
            onRenderError?.invoke()
        }
    }
}

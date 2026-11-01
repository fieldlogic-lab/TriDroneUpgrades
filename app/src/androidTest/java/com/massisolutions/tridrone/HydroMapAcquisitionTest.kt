package com.massisolutions.tridrone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.Manifest
import android.view.MotionEvent
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.rule.GrantPermissionRule
import org.hamcrest.Matchers.*
import org.junit.Rule
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit simulated input exercises the real coordinator; this is not a hardware validation claim. */
@RunWith(AndroidJUnit4::class)
class HydroMapAcquisitionTest {
    @get:Rule val locationPermission: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private fun sentence(quality: Int): String {
        val body = "GPGGA,120000,4048.0000,N,07303.0000,W,$quality,12,0.7,10.2,M,-30.0,M,,"
        return "$" + body + "*" + body.fold(0) { sum, c -> sum xor c.code }.toString(16).padStart(2, '0')
    }

    @Test fun activeCoordinatorStartsWithFloatRecordsFixAndFloatAndSurvivesDisconnect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse("Run on isolated emulator, never an active field survey", HydroLive.recording)
        val prefs = context.getSharedPreferences("hydro_status", 0)
        val oldStatus = prefs.all.toMap()
        val before = File(context.filesDir, "hydro_surveys").listFiles()?.map { it.name }?.toSet().orEmpty()
        val source = "instrumentation-simulated-map-regression"
        try {
            HydroLive.claimGnss(source); HydroLive.gnssConnection(source, true)
            HydroLive.claimSounder(source); HydroLive.sounderConnection(source, true)
            fun feed(quality: Int) {
                val now = SystemClock.elapsedRealtimeNanos()
                val utc = System.currentTimeMillis()
                HydroLive.gnssSentence(source, sentence(quality), utc, now)
                HydroLive.frame(source, "1 0.93 0 0 0 11 100 0", utc, now)
            }
            feed(5)
            val file = HydroLive.start(context) // Actual startup gate must accept FLOAT.
            feed(5); feed(4)
            assertEquals(2, HydroLive.validCount)
            val reader = HydroSurveyMapReader(file)
            assertEquals(listOf(5, 4), reader.refresh().soundings.map { it.quality })
            HydroLive.gnssConnection(source, false)
            assertNull(HydroLive.mapSnapshot(SystemClock.elapsedRealtimeNanos()).position)
            assertTrue(HydroLive.recording)
            HydroLive.frame(source, "1 0.93 0 0 0 11 100 0", System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos())
            assertEquals(3, HydroLive.eventCount)
            assertEquals(2, reader.refresh().soundings.size)
            HydroLive.gnssConnection(source, true); feed(5)
            assertTrue(HydroLive.recording)
            assertEquals(4, HydroLive.eventCount)
            assertEquals(3, HydroLive.validCount)
            val firstResult = reader.refresh()
            assertEquals(listOf(5, 4, 5), firstResult.soundings.map { it.quality })
            assertEquals(5, HydroLive.mapSnapshot(SystemClock.elapsedRealtimeNanos()).quality)
            val prefix = file.readBytes()
            exerciseLiveMapWhileAppending(file, reader, ::feed)
            assertFalse("Map stop control must stop the actual coordinator", HydroLive.recording)
            assertArrayEquals("Existing CSV bytes remain unchanged during map interactions", prefix, file.readBytes().take(prefix.size).toByteArray())
            val result = reader.refresh()
            assertEquals(HydroLive.eventCount, result.decodedEvents)
            assertEquals(HydroLive.validCount, result.soundings.size)
            assertTrue("Frames must keep arriving while the map is open", result.decodedEvents > 4)
            assertEquals(result, HydroSurveyMapReader(file).refresh())
            assertEquals(HydroCsv.columns, file.useLines { parseCsvRecord(it.first()) })
            assertTrue(result.soundings.all { it.fields["bottom_navd88_usft"] == "" && it.depthMeters == 0.93 })
        } finally {
            HydroLive.stop(); HydroLive.gnssConnection(source, false); HydroLive.sounderConnection(source, false)
            File(context.filesDir, "hydro_surveys").listFiles()?.filter { it.name !in before }?.forEach { it.delete() }
            val edit = prefs.edit().clear()
            oldStatus.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value); is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value); is Long -> edit.putLong(key, value)
            } }
            edit.commit()
        }
    }

    private fun exerciseLiveMapWhileAppending(file: File, reader: HydroSurveyMapReader, feed: (Int) -> Unit) {
        val sequence = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val stream = Executors.newSingleThreadScheduledExecutor()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { HydroSurveyMapDialog(it).show() }
            stream.scheduleAtFixedRate({
                try { feed(if (sequence.getAndIncrement() % 2 == 0) 5 else 4) }
                catch (error: Throwable) { failure.set(error) }
            }, 0, 100, TimeUnit.MILLISECONDS)
            try {
                val deadline = SystemClock.elapsedRealtime() + 5000
                var ready = false
                while (!ready && SystemClock.elapsedRealtime() < deadline) {
                    try {
                        onView(isAssignableFrom(SurveyMapView::class.java)).inRoot(isDialog()).check { view, error ->
                            if (error != null) throw error
                            val map = view as SurveyMapView
                            assertNotNull(map.selectSounding(map.width / 2f, map.height / 2f))
                        }
                        ready = true
                    } catch (_: Throwable) { SystemClock.sleep(50) }
                }
                assertTrue("Live map must display recorded soundings during acquisition", ready)
                onView(withText("Follow Vessel")).inRoot(isDialog()).perform(click())
                onView(isAssignableFrom(SurveyMapView::class.java)).inRoot(isDialog()).perform(swipeLeft())
                onView(withText(startsWith("Free Pan •"))).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withText("Fit Survey")).inRoot(isDialog()).perform(click())
                onView(withText("Follow Vessel")).inRoot(isDialog()).perform(click())
                onView(withText(startsWith("Follow Vessel •"))).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(isAssignableFrom(SurveyMapView::class.java)).inRoot(isDialog()).perform(click())
                var detail = ""
                onView(withText(startsWith("Record "))).inRoot(isDialog()).check { view, error ->
                    if (error != null) throw error
                    detail = (view as TextView).text.toString()
                }
                val record = Regex("Record ([0-9]+)").find(detail)!!.groupValues[1].toInt()
                assertEquals(reader.refresh().soundings.single { it.recordNumber == record }.details(), detail)
                assertTrue(HydroLive.recording)
                assertTrue(file.length() > 0)
                onView(withText("Close")).inRoot(isDialog()).perform(click())
                onView(withText("Free Pan")).inRoot(isDialog()).perform(click())
                stream.shutdownNow(); assertTrue(stream.awaitTermination(3, TimeUnit.SECONDS))
                assertNull(failure.get())
                onView(withText("STOP LIVE RECORDING")).inRoot(isDialog()).perform(click())
                assertFalse(HydroLive.recording)
                onView(withText("Close")).inRoot(isDialog()).perform(click())
            } finally { stream.shutdownNow() }
        }
    }

    @Test fun androidViewRenders1001000And10000SoundingsAndSelectsOriginalRecord() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val report = StringBuilder("Simulated Android Canvas view benchmark; managed heap after GC; not device/GPU/native-memory profiling.\n")
        fun heap() = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        fun stabilizedHeap(): Long { System.gc(); System.runFinalization(); System.gc(); return heap() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (count in listOf(100, 1000, 10000)) {
                val fixture = File(instrumentation.targetContext.cacheDir, "map-benchmark-$count.csv")
                fixture.bufferedWriter().use { out ->
                    out.write(HydroCsv.header)
                    repeat(count) { i ->
                        val time = 1000L + i
                        val depth = HydroLiteDecoder.decode("1 0.93 0 0 0 11 100 0", time, time * 1_000_000)
                        val gnss = HydroGnss("simulated benchmark", time, time * 1_000_000,
                            40.8 + i * 0.000001, -73.05 + (i % 100) * 0.000001,
                            if (i % 2 == 0) 4 else 5, 10.0, 0.01, 0.02, "120000")
                        out.write(HydroCsv.row(depth, gnss, EquipmentSettings(), "UNKNOWN"))
                    }
                }
                val before = heap()
                val readStart = SystemClock.elapsedRealtimeNanos()
                val reader = HydroSurveyMapReader(fixture)
                val data = reader.refresh()
                val readMillis = (SystemClock.elapsedRealtimeNanos() - readStart) / 1_000_000
                val parseHeap = heap() - before
                fixture.delete()
                val points = data.track
                assertEquals(count, data.soundings.size); assertEquals(count, points.size)
                repeat(10) { assertSame(data, reader.refresh()) }
                lateinit var map: SurveyMapView
                lateinit var bitmap: Bitmap
                lateinit var canvas: Canvas
                var initialMillis = 0L
                var cachedMillis = 0L
                var updateMillis = 0L
                var maxUpdateMillis = 0L
                var gestureMillis = 0L
                scenario.onActivity { activity ->
                    map = SurveyMapView(activity)
                    map.onRenderError = { throw AssertionError("Benchmark map rendering failed") }
                    map.layout(0, 0, 1080, 1400)
                    map.showSurvey(data, points.last()); map.fitSurvey()
                    bitmap = Bitmap.createBitmap(1080, 1400, Bitmap.Config.ARGB_8888); canvas = Canvas(bitmap)
                    val start = SystemClock.elapsedRealtimeNanos(); map.draw(canvas)
                    initialMillis = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
                }
                val stableBefore = stabilizedHeap()
                try {
                    scenario.onActivity {
                        val cachedStart = SystemClock.elapsedRealtimeNanos()
                        repeat(20) { map.draw(canvas) }
                        cachedMillis = (SystemClock.elapsedRealtimeNanos() - cachedStart) / 1_000_000
                        map.followVessel()
                        val updateStart = SystemClock.elapsedRealtimeNanos()
                        repeat(40) { i ->
                            val frameStart = SystemClock.elapsedRealtimeNanos()
                            map.showSurvey(reader.refresh(), points[(count - 1 - i).coerceAtLeast(0)])
                            map.draw(canvas)
                            maxUpdateMillis = maxOf(maxUpdateMillis, (SystemClock.elapsedRealtimeNanos() - frameStart) / 1_000_000)
                            assertEquals(count, data.soundings.size)
                        }
                        updateMillis = (SystemClock.elapsedRealtimeNanos() - updateStart) / 1_000_000
                        val gestureStart = SystemClock.elapsedRealtimeNanos()
                        repeat(20) {
                            val now = SystemClock.uptimeMillis()
                            for ((action, x, time) in listOf(Triple(MotionEvent.ACTION_DOWN, 540f, now),
                                Triple(MotionEvent.ACTION_MOVE, 400f, now + 20), Triple(MotionEvent.ACTION_UP, 400f, now + 40))) {
                                val event = MotionEvent.obtain(now, time, action, x, 700f, 0)
                                try { map.onTouchEvent(event) } finally { event.recycle() }
                            }
                            map.draw(canvas)
                        }
                        gestureMillis = (SystemClock.elapsedRealtimeNanos() - gestureStart) / 1_000_000
                        assertEquals(SurveyViewport.Mode.FREE_PAN, map.viewport.mode)
                        val center = points[count / 2]; map.viewport.follow(center)
                        val selected = map.selectSounding(540f, 700f)
                        assertNotNull(selected); assertEquals(count / 2 + 1, selected!!.recordNumber)
                        assertEquals(center, selected.position)
                    }
                    val retainedGrowth = stabilizedHeap() - stableBefore
                    report.append("soundings=$count read_ms=$readMillis initial_ms=$initialMillis cached20_ms=$cachedMillis update40_ms=$updateMillis max_update_ms=$maxUpdateMillis gestures20_ms=$gestureMillis parse_heap_delta_bytes=$parseHeap retained_growth_bytes=$retainedGrowth retained_count=${data.soundings.size}\n")
                    assertTrue("Initial draw freeze", initialMillis < 2000)
                    assertTrue("Cached redraw freeze", cachedMillis < 2000)
                    assertTrue("Live update freeze", maxUpdateMillis < 1000)
                    assertTrue("Repeated updates must remain responsive", updateMillis < 10000)
                    assertTrue("Gesture redraws must remain responsive", gestureMillis < 5000)
                    assertTrue("Repeated updates must not retain excessive managed heap", retainedGrowth < 32L * 1024 * 1024)
                    assertEquals(count, reader.refresh().soundings.size)
                } finally { bitmap.recycle() }
                instrumentation.waitForIdleSync()
            }
        }
        android.util.Log.i("MAP_BENCHMARK", report.toString())
        File(instrumentation.targetContext.getExternalFilesDir(null), "map-benchmark.txt").writeText(report.toString())
    }
}

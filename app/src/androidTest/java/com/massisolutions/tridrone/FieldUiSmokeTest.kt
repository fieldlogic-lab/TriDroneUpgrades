package com.massisolutions.tridrone

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.widget.Spinner
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.hamcrest.Matchers.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Android UI exercise. The collection-flow test feeds synthetic sensor frames into the real coordinator. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class FieldUiSmokeTest {
    @get:Rule val locationPermission: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    @Test fun disconnectedHomeAndEquipmentNavigation() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.survey_gnss_status)).check(matches(withText(containsString("Disconnected"))))
            onView(withId(R.id.survey_sounder_status)).check(matches(withText(containsString("Disconnected"))))
            onView(withId(R.id.survey_depth)).check(matches(withText(containsString("—"))))
            onView(withId(R.id.survey_mode)).check(matches(withText(startsWith("LIVE SURVEY"))))
            screenshot("survey-disconnected")
            for (oldControl in listOf("Ready to collect?", "Connect Emlid via Wi-Fi", "Bluetooth backup", "DEMO")) {
                onView(withText(oldControl)).check(doesNotExist())
            }
            for (id in listOf(R.id.survey_precision, R.id.survey_depth)) {
                onView(withId(id)).check { view, error ->
                    if (error != null) throw error
                    val text = view as TextView
                    assertTrue("Survey measurements must retain readable contrast on the navy home background",
                        ColorUtils.calculateContrast(text.currentTextColor, Color.rgb(12, 24, 39)) >= 4.5)
                }
            }
            for (id in listOf(R.id.survey_gnss_status, R.id.survey_sounder_status, R.id.survey_start_stop)) {
                onView(withId(id)).perform(scrollTo()).check { view, error ->
                    if (error != null) throw error
                    assertTrue("Outdoor controls must retain a 48dp minimum touch height", view.height >= 48 * view.resources.displayMetrics.density)
                }
            }
            onView(withId(R.id.survey_start_stop)).check(matches(isDisplayed()))
            onView(withId(R.id.survey_gnss_status)).perform(scrollTo(), click())
            onView(withText("Connections & diagnostics")).check(matches(isDisplayed()))
            screenshot("equipment-disconnected")
            onView(withId(R.id.tab_sessions)).perform(click())
            onView(withText("Review & export")).check(matches(isDisplayed()))
            onView(withId(R.id.tab_survey)).perform(click())
            onView(withId(R.id.survey_sounder_status)).perform(scrollTo(), click())
            onView(withText("Connections & diagnostics")).check(matches(isDisplayed()))
        }
    }

    @Test fun demoRemainsExplicitAndReturnsToLiveWithoutSyntheticDepth() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.tab_settings)).perform(click())
            onView(withText("DEMO")).perform(scrollTo(), click())
            onView(withId(R.id.tab_survey)).perform(click())
            onView(withId(R.id.survey_mode)).check(matches(withText(startsWith("DEMO MODE"))))
            onView(withId(R.id.survey_gnss_status)).check(matches(withText(startsWith("DEMO GNSS"))))
            onView(withId(R.id.survey_depth)).check(matches(withText(containsString("Live measurements hidden"))))
            screenshot("survey-demo-explicit")
            onView(withId(R.id.tab_settings)).perform(click())
            onView(withText("LIVE")).perform(scrollTo(), click())
            onView(withId(R.id.tab_survey)).perform(click())
            onView(withId(R.id.survey_mode)).check(matches(withText(startsWith("LIVE SURVEY"))))
            onView(withId(R.id.survey_depth)).check(matches(withText(containsString("—"))))
        }
    }

    @Test fun equipmentSavedFromDialogSurvivesActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = HydroLive.equipmentFile(context, CeeLineDbt.PROTOCOL)
        val hydroFile = HydroLive.equipmentFile(context)
        val hydroOriginal = if (hydroFile.exists()) hydroFile.readBytes() else null
        val original = if (file.exists()) file.readBytes() else null
        try {
            EquipmentSettings().save(file)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    step("select CEE-LINE") { chooseSounder(cee = true) }
                    step("open equipment") { openEquipmentSettings() }
                    step("enter offset") {
                        onView(withHint("GNSS reference to transducer (+ US survey ft)"))
                            .inRoot(isDialog()).perform(scrollTo(), replaceText("3.25"), closeSoftKeyboard())
                    }
                    step("enter waterline measurement") {
                        onView(withHint("Transducer below waterline (+ US survey ft)"))
                            .inRoot(isDialog()).perform(scrollTo(), replaceText("0.70"), closeSoftKeyboard())
                    }
                    step("open reference dropdown") {
                        onView(isAssignableFrom(Spinner::class.java)).inRoot(isDialog()).perform(scrollTo(), click())
                    }
                    step("select antenna reference") {
                        onData(equalTo("Physical antenna reference point (ARP)"))
                            .inRoot(isPlatformPopup()).perform(click())
                    }
                    step("confirm and save") {
                        onView(withText("I verified offset and receiver height reference; no duplicate antenna correction"))
                            .inRoot(isDialog()).perform(scrollTo(), click())
                        onView(withText("Save")).inRoot(isDialog()).perform(click())
                    }
                    assertEquals(3.25, EquipmentSettings.load(file).offsetUsft!!, 0.0)
                    assertEquals(0.70, EquipmentSettings.load(file).waterlineOffsetUsft!!, 0.0)
                    assertTrue(EquipmentSettings.load(file).confirmed)
                    step("recreate activity") { scenario.recreate() }
                    step("reopen equipment") { openEquipmentSettings() }
                    step("verify restored configuration") {
                        onView(withHint("GNSS reference to transducer (+ US survey ft)"))
                            .inRoot(isDialog()).perform(scrollTo()).check(matches(withText("3.25")))
                        onView(withHint("Transducer below waterline (+ US survey ft)"))
                            .inRoot(isDialog()).perform(scrollTo()).check(matches(withText("0.7")))
                        onView(withText("I verified offset and receiver height reference; no duplicate antenna correction"))
                            .inRoot(isDialog()).perform(scrollTo()).check(matches(isChecked()))
                    }
                    screenshot("equipment-settings-restored")
                    onView(withText("Cancel")).inRoot(isDialog()).perform(click())
                    chooseSounder(cee = false)
                    if (hydroOriginal == null) assertFalse(hydroFile.exists())
                    else assertArrayEquals(hydroOriginal, hydroFile.readBytes())
                } catch (failure: Throwable) {
                    // Capture while the failing dialog is still present, before ActivityScenario closes.
                    runCatching { screenshot("equipment-failure") }
                    throw failure
                }
            }
        } finally {
            if (original == null) file.delete() else file.writeBytes(original)
            context.getSharedPreferences("sounder_profile", android.content.Context.MODE_PRIVATE)
                .edit().putString("profile", "HYDROLITE").commit()
        }
    }

    @Test fun projectCreationAndConsecutiveCollectionsThroughSurveyControls() {
        val projectName = "Project A ${System.currentTimeMillis()}"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectStore = ProjectStore.get(context)
        val gnss = "instrumentation-gnss"
        val hydroSounder = "instrumentation-sounder"
        val ceeSounder = CeeLineUsbService.SOURCE
        val ceeEquipment = HydroLive.equipmentFile(context, CeeLineDbt.PROTOCOL)
        val originalCeeEquipment = if (ceeEquipment.exists()) ceeEquipment.readBytes() else null
        val gga = "GPGGA,123519,4048.0000,N,07303.0000,W,4,12,0.7,10.2,M,-30.0,M,,"
        val checksum = gga.fold(0) { value, char -> value xor char.code }
        val sentence = "$" + gga + "*" + checksum.toString(16).uppercase().padStart(2, '0')
        val dbt = "SDDBT,,f,4.2,M,,F"
        val dbtChecksum = dbt.fold(0) { value, char -> value xor char.code }
        fun feed(sounder: String) {
            val utc = System.currentTimeMillis()
            val nanos = android.os.SystemClock.elapsedRealtimeNanos()
            val depth = if (sounder == ceeSounder) "\$$dbt*%02X".format(dbtChecksum)
                else "1 0.93 0 0 0 11.0 100 0"
            val bytes = "$depth\r\n".toByteArray()
            HydroLive.gnssSentence(gnss, sentence, utc, nanos)
            HydroLive.rawDepth(sounder, bytes, bytes.size)
            HydroLive.frame(sounder, depth, utc, nanos)
        }

        try {
            EquipmentSettings(3.25, "ANTENNA_REFERENCE_POINT", true, 1000L, 0.7).save(ceeEquipment)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onView(withId(R.id.tab_sessions)).perform(click())
                onView(withText("Projects and collection history")).perform(scrollTo(), click())
                awaitText("Create new project")
                screenshot("projects")
                onView(withText("Create new project")).inRoot(isDialog()).perform(click())
                onView(withHint("Project name")).inRoot(isDialog()).perform(replaceText(projectName), closeSoftKeyboard())
                onView(withText("Save")).inRoot(isDialog()).perform(click())
                awaitText("Collection history (0)")
                onView(withText("Projects")).inRoot(isDialog()).perform(click())
                awaitText("Create new project")
                onView(withText("Close")).inRoot(isDialog()).perform(click())
                onView(withId(R.id.tab_survey)).perform(click())
                awaitText("Project • $projectName", displayed = false)

                HydroLive.claimGnss(gnss)
                HydroLive.gnssConnection(gnss, true)
                for ((index, count) in listOf(2, 3, 4).withIndex()) {
                    val cee = index == 1
                    chooseSounder(cee)
                    val sounder = if (cee) ceeSounder else hydroSounder
                    onView(withId(R.id.tab_survey)).perform(click())
                    HydroLive.claimSounder(sounder)
                    HydroLive.sounderConnection(sounder, true)
                    scenario.onActivity { activity ->
                        feed(sounder)
                        activity.findViewById<android.view.View>(R.id.survey_start_stop).performClick()
                        assertTrue("Go must open a collection", HydroLive.recording)
                    }
                    repeat(count) { feed(sounder) }
                    onView(withId(R.id.survey_start_stop)).perform(scrollTo(), click())
                    assertFalse(HydroLive.recording)
                }
                scenario.recreate()
                awaitText("Project • $projectName", displayed = false)
                val project = checkNotNull(projectStore.activeProject())
                assertEquals(projectName, project.name)
                val sessions = projectStore.sessions(project.id).sortedBy { it.number }
                assertEquals(listOf(2L, 3L, 4L), sessions.map { it.observationCount })
                assertEquals(listOf(HydroLiteDecoder.PROTOCOL, CeeLineDbt.PROTOCOL, HydroLiteDecoder.PROTOCOL),
                    sessions.map { it.metadata.getString("protocol") })
                assertEquals(listOf(37, 43, 37), sessions.map { it.csvHeader.split(',').size })
                assertEquals(0.7, sessions[1].metadata.getDouble("transducer_below_waterline_usft"), 0.0)

                onView(withId(R.id.tab_sessions)).perform(click())
                onView(withText("Projects and collection history")).perform(scrollTo(), click())
                awaitText(projectName)
                onView(withText(projectName)).inRoot(isDialog()).perform(click())
                awaitText("Collection history (3)")
                onView(withText("Collection history (3)")).inRoot(isDialog()).perform(click())
                awaitText("$projectName collection history")
                screenshot("collection-history")
            }
        } finally {
            if (HydroLive.recording) HydroLive.stop()
            HydroLive.gnssConnection(gnss, false)
            HydroLive.sounderConnection(hydroSounder, false)
            HydroLive.sounderConnection(ceeSounder, false)
            if (originalCeeEquipment == null) ceeEquipment.delete() else ceeEquipment.writeBytes(originalCeeEquipment)
            context.getSharedPreferences("sounder_profile", android.content.Context.MODE_PRIVATE)
                .edit().putString("profile", "HYDROLITE").commit()
        }
    }

    private fun chooseSounder(cee: Boolean) {
        onView(withId(R.id.tab_survey)).perform(click())
        onView(withId(R.id.survey_sounder_status)).perform(scrollTo(), click())
        onView(withText(startsWith("Sounder profile •"))).perform(scrollTo(), click())
        onView(withText(if (cee) "CEE-LINE USB • NMEA DBT" else "HydroLite Plus (existing provisional decoder)"))
            .inRoot(isDialog()).perform(click())
    }

    private fun awaitText(text: String, displayed: Boolean = true) {
        val deadline = android.os.SystemClock.uptimeMillis() + 8_000
        var failure: Throwable
        do {
            try {
                onView(withText(text)).check(matches(if (displayed) isDisplayed() else withText(text)))
                return
            } catch (error: androidx.test.espresso.NoMatchingViewException) {
                failure = error
            } catch (error: AssertionError) {
                failure = error
            }
            android.os.SystemClock.sleep(100)
        } while (android.os.SystemClock.uptimeMillis() < deadline)
        throw failure
    }

    private fun openEquipmentSettings() {
        onView(withId(R.id.tab_settings)).perform(click())
        onView(withText("Antenna / transducer vertical offset")).perform(scrollTo(), click())
    }

    private fun step(name: String, action: () -> Unit) {
        android.util.Log.i("SMOKE_STEP", name)
        action()
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        awaitCommittedActivityFrame()
        // PixelCopy reads the rendered activity surface, excluding transient OS starting windows.
        val bitmap = if (name == "survey-disconnected") captureActivityWindow()
            else checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot unavailable" }
        try {
            if (name == "survey-disconnected") requireVisibleSurveyFrame(bitmap)
            saveScreenshot(name, bitmap)
            if (name == "survey-disconnected") {
                val width = minOf(540, bitmap.width)
                val height = (bitmap.height.toLong() * width / bitmap.width).toInt()
                val preview = Bitmap.createScaledBitmap(bitmap, width, height, true)
                try { saveScreenshot("survey-disconnected-preview", preview) }
                finally { if (preview !== bitmap) preview.recycle() }
            }
        } finally { bitmap.recycle() }
    }

    private fun requireVisibleSurveyFrame(bitmap: Bitmap) {
        // Reject an OS starting-window screenshot; view-tree assertions alone cannot prove composition.
        // Sample the central content, excluding status/navigation bars and transient bottom toasts.
        var nearWhite = 0
        var samples = 0
        for (xStep in 1..9) for (yStep in 2..7) {
            val pixel = bitmap.getPixel(bitmap.width * xStep / 10, bitmap.height * yStep / 10)
            if (Color.red(pixel) > 235 && Color.green(pixel) > 235 && Color.blue(pixel) > 235) nearWhite++
            samples++
        }
        check(nearWhite * 5 < samples * 4) { "Survey screenshot is a blank white starting-window frame" }
    }

    private fun awaitCommittedActivityFrame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val committed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).single()
            val decor = activity.window.decorView
            if (Build.VERSION.SDK_INT >= 29 && decor.isHardwareAccelerated) {
                decor.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                decor.invalidate()
            } else {
                decor.postOnAnimation { committed.countDown() }
                decor.invalidate()
            }
        }
        check(committed.await(5, TimeUnit.SECONDS)) { "Activity did not commit a screenshot frame within 5 seconds" }
        instrumentation.waitForIdleSync()
    }

    private fun captureActivityWindow(): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val copied = CountDownLatch(1)
        val result = AtomicInteger(PixelCopy.ERROR_UNKNOWN)
        var bitmap: Bitmap? = null
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).single()
            val decor = activity.window.decorView
            val destination = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            bitmap = destination
            PixelCopy.request(activity.window, destination, { status ->
                result.set(status)
                copied.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        if (!copied.await(5, TimeUnit.SECONDS) || result.get() != PixelCopy.SUCCESS) {
            bitmap?.recycle()
            error("Rendered activity-window screenshot failed: PixelCopy status ${result.get()}")
        }
        return checkNotNull(bitmap)
    }

    private fun saveScreenshot(name: String, bitmap: Bitmap) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (Build.VERSION.SDK_INT >= 29) {
            // Public Downloads survives AGP uninstalling the test APK after instrumentation.
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$name.png")
                put(MediaStore.Downloads.MIME_TYPE, "image/png")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TriDroneSmoke")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            try {
                checkNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (failure: Throwable) {
                resolver.delete(uri, null, null)
                throw failure
            }
        } else {
            val folder = File(context.getExternalFilesDir(null), "smoke-screenshots").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
}

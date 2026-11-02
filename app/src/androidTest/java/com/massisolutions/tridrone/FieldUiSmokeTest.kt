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

/** Real Android UI exercise. No GNSS/depth fixtures or fake sensor observations are injected. */
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
        withPreservedProfiles { store ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    openEquipmentSettings()
                    onView(withId(R.id.geometry_a)).inRoot(isDialog())
                        .perform(scrollTo(), replaceText("3.25"), closeSoftKeyboard())
                    onView(withId(R.id.geometry_b)).inRoot(isDialog())
                        .perform(scrollTo(), replaceText("0.75"), closeSoftKeyboard())
                    onView(withId(R.id.geometry_reference)).inRoot(isDialog()).perform(scrollTo(), click())
                    onData(equalTo("Physical antenna reference point (ARP)"))
                        .inRoot(isPlatformPopup()).perform(click())
                    onView(withId(R.id.geometry_confirmed)).inRoot(isDialog()).perform(scrollTo(), click())
                    onView(withId(R.id.profile_sounder_address)).inRoot(isDialog())
                        .perform(scrollTo(), replaceText("AA:BB:CC:DD:EE:01"), closeSoftKeyboard())
                    onView(withId(R.id.profile_save)).inRoot(isDialog()).perform(scrollTo(), click())
                    assertEquals(3.25, store.active()!!.geometry.gpsToSounderUsft!!, 0.0)
                    assertEquals(0.75, store.active()!!.geometry.sounderBelowWaterlineUsft!!, 0.0)
                    assertEquals("AA:BB:CC:DD:EE:01", store.active()!!.connection.sounderAddress)
                    assertTrue(store.active()!!.geometry.usable)
                    onView(withText("Close")).inRoot(isDialog()).perform(click())
                    scenario.recreate()
                    openEquipmentSettings()
                    onView(withId(R.id.geometry_a)).inRoot(isDialog()).perform(scrollTo()).check(matches(withText("3.25")))
                    onView(withId(R.id.geometry_b)).inRoot(isDialog()).perform(scrollTo()).check(matches(withText("0.75")))
                    onView(withId(R.id.geometry_confirmed)).inRoot(isDialog()).perform(scrollTo()).check(matches(isChecked()))
                    onView(withId(R.id.geometry_preview_button)).inRoot(isDialog()).perform(scrollTo(), click())
                    onView(withId(R.id.geometry_preview)).inRoot(isDialog()).perform(scrollTo())
                        .check(matches(withText(containsString("2.500 ftUS"))))
                    onView(withId(R.id.profile_picker)).inRoot(isDialog()).perform(scrollTo())
                    screenshot("vertical-setup-measured")
                    onView(withText("Close")).inRoot(isDialog()).perform(click())
                } catch (failure: Throwable) {
                    runCatching { screenshot("equipment-failure") }
                    throw failure
                }
            }
        }
    }

    @Test fun profilePickerDoesNotActivateUntilSaveAndCeeKeepsOwnSettings() {
        withPreservedProfiles { store ->
            val original = store.active()!!
            ActivityScenario.launch(MainActivity::class.java).use {
                openEquipmentSettings()
                onView(withId(R.id.profile_picker)).inRoot(isDialog()).perform(scrollTo(), click())
                onData(equalTo("Pole / CEE-LINE")).inRoot(isPlatformPopup()).perform(click())
                assertEquals("Viewing a profile must not change active acquisition", original.id, store.active()!!.id)
                onView(withText(containsString("Set the SD1000 serial side externally to 9,600 baud")))
                    .inRoot(isDialog()).check(matches(isDisplayed()))
                screenshot("vertical-setup-cee-line")
                onView(withId(R.id.geometry_a)).inRoot(isDialog()).perform(scrollTo(), replaceText("5.0"), closeSoftKeyboard())
                onView(withId(R.id.geometry_b)).inRoot(isDialog()).perform(scrollTo(), replaceText("0.5"), closeSoftKeyboard())
                onView(withId(R.id.profile_save)).inRoot(isDialog()).perform(scrollTo(), click())
                assertEquals("pole_cee_line", store.active()!!.id)
                assertEquals(9600, store.active()!!.connection.baud)
                assertEquals(5.0, store.active()!!.geometry.gpsToSounderUsft!!, 0.0)
                assertFalse("Unconfirmed preparation cannot certify a calibration", store.active()!!.geometry.usable)
                assertEquals(original, store.all().single { p -> p.id == original.id })
                onView(withText("Close")).inRoot(isDialog()).perform(click())
            }
        }
    }

    @Test fun portableProfileImportAddsCopiesWithoutActivatingOrConfirming() {
        withPreservedProfiles { store ->
            val original = store.active()!!
            val transfer = store.exportProfiles()
            ActivityScenario.launch(MainActivity::class.java).use {
                openEquipmentSettings()
                onView(withText("Transfer profiles")).inRoot(isDialog()).perform(click())
                onView(withText("Import pasted profile text")).inRoot(isDialog()).perform(click())
                onView(withHint("Paste exported profile text")).inRoot(isDialog())
                    .perform(replaceText(transfer), closeSoftKeyboard())
                onView(withText("Import copies")).inRoot(isDialog()).perform(click())
                assertEquals(original, store.active())
                assertEquals(4, store.all().size)
                val copies = store.all().filter { p -> p.id.contains("_import") }
                assertEquals(2, copies.size)
                assertTrue(copies.all { p -> !p.geometry.verified && !p.calibration.confirmed })
                onView(withId(R.id.profile_picker)).inRoot(isDialog()).perform(scrollTo(), click())
                onData(equalTo("TriDrone / HydroLite Plus (imported copy 1)")).inRoot(isPlatformPopup()).perform(click())
                assertEquals("Viewing imported copies must not activate them", original, store.active())
                screenshot("portable-profiles-imported")
                onView(withText("Close")).inRoot(isDialog()).perform(click())
                // Transfer both an original and its imported copy, producing nested import IDs.
                store.importProfiles(store.exportProfiles())
                assertEquals(8, store.all().size)
                openEquipmentSettings()
                onView(withId(R.id.profile_picker)).inRoot(isDialog()).perform(scrollTo(), click())
                onData(equalTo("TriDrone / HydroLite Plus (imported copy 3)")).inRoot(isPlatformPopup()).perform(click())
                assertEquals("Nested imported copies remain inactive until saved", original, store.active())
                onView(withText("Close")).inRoot(isDialog()).perform(click())
            }
        }
    }

    private fun withPreservedProfiles(action: (EquipmentProfiles) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "equipment_profiles.properties")
        val original = if (file.exists()) file.readBytes() else null
        try {
            // This is an isolated instrumentation installation, never a physical survey phone.
            file.delete()
            val store = EquipmentProfiles(context.filesDir) { false }
            store.seedDefaults()
            action(store)
        } finally {
            if (original == null) file.delete() else file.writeBytes(original)
        }
    }
    private fun openEquipmentSettings() {
        onView(withId(R.id.tab_equipment)).perform(click())
        onView(withId(R.id.vertical_setup)).perform(scrollTo(), click())
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

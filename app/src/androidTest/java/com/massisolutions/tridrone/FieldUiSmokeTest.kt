package com.massisolutions.tridrone

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import org.hamcrest.Matchers.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Android UI exercise. No GNSS/depth fixtures or fake sensor observations are injected. */
@RunWith(AndroidJUnit4::class)
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
        val file = HydroLive.equipmentFile(context)
        val original = if (file.exists()) file.readBytes() else null
        try {
            EquipmentSettings().save(file)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    step("open equipment") { openEquipmentSettings() }
                    step("enter offset") {
                        onView(withHint("Vertical offset in US survey feet (unset by default)"))
                            .inRoot(isDialog()).perform(scrollTo(), replaceText("3.25"), closeSoftKeyboard())
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
                    assertTrue(EquipmentSettings.load(file).confirmed)
                    step("recreate activity") { scenario.recreate() }
                    step("reopen equipment") { openEquipmentSettings() }
                    step("verify restored configuration") {
                        onView(withHint("Vertical offset in US survey feet (unset by default)"))
                            .inRoot(isDialog()).perform(scrollTo()).check(matches(withText("3.25")))
                        onView(withText("I verified offset and receiver height reference; no duplicate antenna correction"))
                            .inRoot(isDialog()).perform(scrollTo()).check(matches(isChecked()))
                    }
                    screenshot("equipment-settings-restored")
                    onView(withText("Cancel")).inRoot(isDialog()).perform(click())
                } catch (failure: Throwable) {
                    // Capture while the failing dialog is still present, before ActivityScenario closes.
                    runCatching { screenshot("equipment-failure") }
                    throw failure
                }
            }
        } finally {
            if (original == null) file.delete() else file.writeBytes(original)
        }
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
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot unavailable" }
        try {
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

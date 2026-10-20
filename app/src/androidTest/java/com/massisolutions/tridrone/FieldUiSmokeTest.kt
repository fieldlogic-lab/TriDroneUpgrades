package com.massisolutions.tridrone

import android.Manifest
import android.graphics.Bitmap
import android.widget.Spinner
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
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
            for (id in listOf(R.id.survey_gnss_status, R.id.survey_sounder_status, R.id.survey_start_stop)) {
                onView(withId(id)).perform(scrollTo()).check { view, error ->
                    if (error != null) throw error
                    assertTrue("Outdoor controls must retain a 48dp minimum touch height", view.height >= 48 * view.resources.displayMetrics.density)
                }
            }
            onView(withId(R.id.survey_gnss_status)).perform(scrollTo(), click())
            onView(withText("Connections & diagnostics")).check(matches(isDisplayed()))
            screenshot("equipment-disconnected")
            onView(withId(R.id.tab_sessions)).perform(click())
            onView(withText("Review & export")).check(matches(isDisplayed()))
            onView(withId(R.id.tab_survey)).perform(click())
            screenshot("survey-disconnected")
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
                openEquipmentSettings()
                onView(withHint("Vertical offset in US survey feet (unset by default)"))
                    .perform(scrollTo(), replaceText("3.25"), closeSoftKeyboard())
                onView(isAssignableFrom(Spinner::class.java)).perform(scrollTo(), click())
                onData(equalTo("Physical antenna reference point (ARP)")).perform(click())
                onView(withText("I verified offset and receiver height reference; no duplicate antenna correction")).perform(scrollTo(), click())
                onView(withText("Save")).perform(click())
                assertEquals(3.25, EquipmentSettings.load(file).offsetUsft!!, 0.0)
                assertTrue(EquipmentSettings.load(file).confirmed)
                scenario.recreate()
                openEquipmentSettings()
                onView(withHint("Vertical offset in US survey feet (unset by default)"))
                    .perform(scrollTo()).check(matches(withText("3.25")))
                onView(withText("I verified offset and receiver height reference; no duplicate antenna correction"))
                    .perform(scrollTo()).check(matches(isChecked()))
                screenshot("equipment-settings-restored")
                onView(withText("Cancel")).perform(click())
            }
        } finally {
            if (original == null) file.delete() else file.writeBytes(original)
        }
    }

    private fun openEquipmentSettings() {
        onView(withId(R.id.tab_settings)).perform(click())
        onView(withText("Antenna / transducer vertical offset")).perform(scrollTo(), click())
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "smoke-screenshots").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot unavailable" }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

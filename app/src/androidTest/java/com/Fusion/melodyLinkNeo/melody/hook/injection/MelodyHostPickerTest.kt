package com.fusion.melodyLinkNeo.melody.hook.injection

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M4.4 instrumentation check for the「高级功能」pickers.
 *
 * The app process does not contain the host's `com.coui` classes, so this exercises exactly the
 * fallback leg: a real Activity must still get a single-choice / slider sheet (framework [android.app.AlertDialog]),
 * never a crash. The COUI look itself is only observable inside `com.oplus.melody` (M4.5 device pass).
 */
@RunWith(AndroidJUnit4::class)
class MelodyHostPickerTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun choiceSheet_presentsOnARealActivity() {
        val picker = MelodyHostPicker(requireNotNull(javaClass.classLoader))
        var presented = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            presented = picker.present(composeRule.activity, "Equalizer", listOf("Off", "Bright"), 1) { }
        }

        assertTrue("the choice sheet must present on an Activity", presented)
    }

    @Test
    fun sliderSheet_presentsOnARealActivity() {
        val picker = MelodyHostPicker(requireNotNull(javaClass.classLoader))
        var presented = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            presented = picker.present(composeRule.activity, "Volume", 0.0, 10.0, 1.0, 3.0, null) { }
        }

        assertTrue("the slider sheet must present on an Activity", presented)
    }
}

package com.brewprint.ui

import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.brewprint.data.Appearance
import com.brewprint.data.DialFinish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
class GrinderDialTest {
    @get:Rule val compose = createComposeRule()

    private fun adjust(label: String, changed: Boolean) {
        val action = compose.onNode(hasContentDescription("Grinder adjustment wheel", substring = true))
            .fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
        compose.runOnIdle { assertEquals(changed, action.action()) }
    }

    @Test fun `one drag event advances every crossed detent`() {
        val settings = mutableListOf<Int>()
        compose.setContent {
            BrewprintTheme(Appearance.LIGHT) {
                GrinderDial(24, 24, DialFinish.METAL, { it?.let(settings::add) }, {}, {})
            }
        }
        compose.onNode(hasContentDescription("Grinder adjustment wheel", substring = true)).performTouchInput {
            down(Offset(1f, height / 2f))
            moveTo(Offset(1f, height * .4f), delayMillis = 16)
            moveTo(Offset(width * .4f, height * .1f), delayMillis = 16)
            up()
        }
        compose.runOnIdle {
            assertTrue("The gesture should cross several detents", settings.size > 1)
            settings.zipWithNext().forEach { (before, after) -> assertEquals(1, kotlin.math.abs(after - before)) }
            assertEquals(settings.size, kotlin.math.abs(settings.last() - 24))
        }
    }

    @Test fun `direct entry accepts both endpoints and rejects non whole or out of range values`() {
        val settings = mutableListOf<Int?>()
        compose.setContent {
            BrewprintTheme(Appearance.LIGHT) {
                GrinderDial(24, 24, DialFinish.METAL, settings::add, {}, {})
            }
        }
        val field = compose.onNode(hasText("Enter value, ticks") and hasSetTextAction())
        for (invalid in listOf("-1", "101", "24.0", "24.5", "1e1", "abc", "")) {
            field.performScrollTo().performTextReplacement(invalid)
            field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
            compose.runOnIdle { assertEquals(null, settings.last()) }
        }
        for (valid in listOf(0, 100)) {
            field.performTextReplacement(valid.toString())
            field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error).not())
            compose.runOnIdle { assertEquals(valid, settings.last()) }
        }
    }

    @Test fun `wheel exposes accessible adjustments without visible increase decrease buttons`() {
        val settings = mutableListOf<Int>()
        compose.setContent {
            BrewprintTheme(Appearance.DARK) {
                GrinderDial(24, 24, DialFinish.METAL, { it?.let(settings::add) }, {}, {})
            }
        }
        compose.onNodeWithText("+").assertDoesNotExist()
        compose.onNodeWithText("-").assertDoesNotExist()
        compose.onNodeWithText("\u2212").assertDoesNotExist()
        compose.onNodeWithText("Increase").assertDoesNotExist()
        compose.onNodeWithText("Decrease").assertDoesNotExist()
        compose.onNodeWithContentDescription("Increase grind setting").assertDoesNotExist()
        compose.onNodeWithContentDescription("Decrease grind setting").assertDoesNotExist()
        val wheel = compose.onNode(hasContentDescription("Grinder adjustment wheel", substring = true))
        val range = wheel.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0f..100f, range.range)
        assertEquals(99, range.steps)
        adjust("Increase grind setting", true)
        wheel.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "25 ticks"))
        adjust("Decrease grind setting", true)
        compose.runOnIdle { assertEquals(listOf(25, 24), settings) }
        wheel.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(100f)) }
        adjust("Increase grind setting", false)
        wheel.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(!it(101f)) }
        wheel.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(0f)) }
        adjust("Decrease grind setting", false)
        wheel.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(!it(-1f)) }
        wheel.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(!it(Float.NaN)) }
        compose.runOnIdle { assertEquals(listOf(25, 24, 100, 0), settings) }
    }

    @Test fun `wheel drag stops at upper bound without extra value events`() {
        val settings = mutableListOf<Int>()
        compose.setContent {
            BrewprintTheme(Appearance.LIGHT) {
                GrinderDial(99, 24, DialFinish.METAL, { it?.let(settings::add) }, {}, {})
            }
        }
        val wheel = compose.onNode(hasContentDescription("Grinder adjustment wheel", substring = true))
        repeat(2) {
            wheel.performTouchInput {
                down(Offset(1f, height / 2f))
                moveTo(Offset(1f, height * .4f), delayMillis = 16)
                moveTo(Offset(width * .4f, height * .1f), delayMillis = 16)
                up()
            }
        }
        compose.runOnIdle { assertEquals(listOf(100), settings) }
    }

    @Test fun `unchanged wheel bound emits neither value nor haptic`() {
        val settings = mutableListOf<Int?>()
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            BrewprintTheme(Appearance.LIGHT) {
                GrinderDial(100, 24, DialFinish.METAL, settings::add, {}, {})
            }
        }
        val before = shadowOf(view).lastHapticFeedbackPerformed()
        val wheel = compose.onNode(hasContentDescription("Grinder adjustment wheel", substring = true))
        wheel.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(!it(100f)) }
        adjust("Increase grind setting", false)
        wheel.performTouchInput {
            down(Offset(1f, height / 2f))
            moveTo(Offset(1f, height * .4f), delayMillis = 16)
            moveTo(Offset(width * .4f, height * .1f), delayMillis = 16)
            up()
        }
        compose.runOnIdle {
            assertTrue(settings.isEmpty())
            assertEquals(before, shadowOf(view).lastHapticFeedbackPerformed())
        }
    }
}

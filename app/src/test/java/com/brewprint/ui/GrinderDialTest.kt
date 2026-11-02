package com.brewprint.ui

import androidx.compose.ui.geometry.Offset
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
class GrinderDialTest {
    @get:Rule val compose = createComposeRule()

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
}

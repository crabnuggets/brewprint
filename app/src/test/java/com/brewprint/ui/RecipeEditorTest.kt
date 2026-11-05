package com.brewprint.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.brewprint.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
class RecipeEditorTest {
    @get:Rule val compose = createComposeRule()
    private val recipe = Recipe(name = "Three pulses", dose = 15.0, ratio = 16.0,
        pulses = listOf(Pulse("Bloom", 60.0, 10, 30), Pulse("Middle", 90.0, 20, 20), Pulse("Finish", 90.0, 25)))

    private fun tap(description: String) = compose.onNodeWithContentDescription(description).performScrollTo().performClick()
    private fun save(): Recipe {
        compose.onNodeWithText("Save recipe").performScrollTo().assertIsEnabled().performClick()
        return saved!!
    }
    private var saved: Recipe? = null
    private fun show(initial: Recipe) {
        compose.setContent {
            BrewprintTheme(Appearance.LIGHT) { RecipeEditor(initial, { saved = it; true }, {}) }
        }
    }

    @Test fun `move down preserves pulse values and recalculates water and schedule`() {
        show(recipe)
        tap("Move pulse 1 down")
        compose.onNodeWithText("Planned pours: 240 g / Target: 240 g").performScrollTo().assertExists()
        compose.onNodeWithText("Scheduled pours end at 1:45").assertExists()
        val result = save()
        assertEquals(listOf(recipe.pulses[1], recipe.pulses[0], recipe.pulses[2]), result.pulses)
        assertEquals(listOf(0, 40, 80), result.schedule().map { it.startSeconds })
        assertEquals(listOf(90.0, 150.0, 240.0), result.schedule().map { it.cumulativeWater })
    }

    @Test fun `move up clears new last wait and moving it back starts wait at zero`() {
        show(recipe)
        tap("Move pulse 3 up")
        compose.onNodeWithText("The last pulse has no wait. Its wait has been reset to zero.").performScrollTo().assertExists()
        tap("Move pulse 2 down")
        val result = save()
        assertEquals(recipe.pulses.map { it.copy(waitSeconds = 0) }, result.pulses.map { it.copy(waitSeconds = 0) })
        assertEquals(listOf(30, 0, 0), result.pulses.map { it.waitSeconds })
        assertEquals(listOf(0, 40, 60), result.schedule().map { it.startSeconds })
        assertEquals(240.0, result.plannedWater, 0.0)
    }

    @Test fun `removing pulses retains at least one and hides its unused reorder controls`() {
        show(recipe)
        compose.onNodeWithText("Remove pulse 3").performScrollTo().performClick()
        compose.onNodeWithText("Remove pulse 2").performScrollTo().performClick()
        compose.onNodeWithText("Remove pulse 1").assertDoesNotExist()
        compose.onNodeWithContentDescription("Move pulse 1 up").assertDoesNotExist()
        compose.onNodeWithContentDescription("Move pulse 1 down").assertDoesNotExist()
        compose.onNodeWithContentDescription("Drag pulse 1 to reorder").assertDoesNotExist()
        compose.onNodeWithText("Wait after pouring, seconds").assertDoesNotExist()
        compose.onNode(hasText("Water to add, g") and hasSetTextAction()).performScrollTo().performTextReplacement("240")
        val result = save()
        assertEquals(listOf(recipe.pulses.first().copy(water = 240.0, waitSeconds = 0)), result.pulses)
    }

    @Test fun `adding a pulse copies quantities independently and starts waits at zero`() {
        show(recipe.copy(pulses = listOf(Pulse("Only", 120.0, 10))))
        compose.onNodeWithText("Add pulse").performScrollTo().performClick()
        compose.onAllNodes(hasText("Water to add, g") and hasSetTextAction())[1].performScrollTo().performTextReplacement("130")
        compose.onAllNodes(hasText("Water to add, g") and hasSetTextAction())[0].performScrollTo().performTextReplacement("110")
        val result = save()
        assertEquals(listOf(Pulse("Only", 110.0, 10, 0), Pulse("", 130.0, 10, 0)), result.pulses)
        assertEquals(listOf(0, 10), result.schedule().map { it.startSeconds })
    }

    @Test
    @Config(qualifiers = "w411dp-h1800dp")
    fun `dragging the actual grip changes saved pulse order and preserves its values`() {
        val initial = recipe.copy(pulses = listOf(Pulse("First", 100.0, 11, 17), Pulse("Last", 140.0, 23)))
        show(initial)
        val grip = compose.onNodeWithContentDescription("Drag pulse 1 to reorder").performScrollTo()
        val first = grip.fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithContentDescription("Drag pulse 2 to reorder").fetchSemanticsNode().boundsInRoot
        val distance = second.center.y - first.center.y
        grip.performTouchInput {
            down(center)
            moveBy(Offset(0f, 30f), delayMillis = 50)
            moveBy(Offset(0f, distance + 80f), delayMillis = 500)
            up()
        }
        val result = save()
        assertEquals(listOf(initial.pulses[1], initial.pulses[0].copy(waitSeconds = 0)), result.pulses)
        assertEquals(listOf(0, 23), result.schedule().map { it.startSeconds })
        assertEquals(listOf(140.0, 240.0), result.schedule().map { it.cumulativeWater })
    }
}

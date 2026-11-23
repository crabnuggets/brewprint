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
        compose.onNodeWithContentDescription("Total brew water 240 grams").performScrollTo().assertExists()
        compose.onNode(hasText("Pours end at") and hasText("1:45")).assertExists()
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
        assertEquals(240.0, result.totalWater, 0.0)
    }

    @Test fun `removing pulses retains at least one and hides its unused reorder controls`() {
        show(recipe)
        compose.onNodeWithText("Remove pulse 3").performScrollTo().performClick()
        compose.onNodeWithText("Remove pulse 2").performScrollTo().performClick()
        compose.onNodeWithText("Remove pulse 1").assertDoesNotExist()
        compose.onNodeWithContentDescription("Move pulse 1 up").assertDoesNotExist()
        compose.onNodeWithContentDescription("Move pulse 1 down").assertDoesNotExist()
        compose.onNodeWithContentDescription("Drag pulse 1 to reorder").assertDoesNotExist()
        compose.onNode(hasText("Wait, s") and hasSetTextAction()).assertDoesNotExist()
        compose.onNode(hasText("Water, g") and hasSetTextAction()).performScrollTo().performTextReplacement("240")
        val result = save()
        assertEquals(listOf(recipe.pulses.first().copy(water = 240.0, waitSeconds = 0)), result.pulses)
    }

    @Test fun `adding a pulse copies quantities independently and starts waits at zero`() {
        show(recipe.copy(pulses = listOf(Pulse("Only", 120.0, 10))))
        compose.onNodeWithText("Add pulse").performScrollTo().performClick()
        compose.onAllNodes(hasText("Water, g") and hasSetTextAction())[1].performScrollTo().performTextReplacement("130")
        compose.onAllNodes(hasText("Water, g") and hasSetTextAction())[0].performScrollTo().performTextReplacement("110")
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

    @Test fun `the ratio follows the pours, and a new dose offers to scale them back to it`() {
        show(Recipe(name = "Kurasu Iced", dose = 16.0, drinkStyle = DrinkStyle.ICED, iceGrams = 70.0,
            pulses = listOf(Pulse("Bloom", 40.0, 10, 35, "Stir gently 3 times"), Pulse(water = 60.0, pourSeconds = 15, waitSeconds = 15), Pulse(water = 50.0, pourSeconds = 15))))
        compose.onNodeWithContentDescription("Brew ratio 1 to 9.4, 1 to 13.8 with ice").assertExists()
        compose.onNode(hasText("In the cup") and hasText("220 g")).assertExists()
        compose.onNodeWithText("Stir gently 3 times").assertExists()
        compose.onNode(hasText("Coffee dose, g") and hasSetTextAction()).performTextReplacement("18")
        // Pours stay as they are until the user scales them, so saving now would only change the ratio.
        compose.onNodeWithContentDescription("Brew ratio 1 to 8.3, 1 to 12.2 with ice").assertExists()
        compose.onNodeWithText("Pours become 45 g · 67.5 g · 56.3 g, total 168.8 g. Ice becomes 78.8 g.").performScrollTo().assertExists()
        compose.onNodeWithText("Scale pours to keep 1:9.4").performClick()
        compose.onNodeWithText("Scale pours to keep 1:9.4").assertDoesNotExist()
        val result = save()
        assertEquals(listOf(45.0, 67.5, 56.3), result.pulses.map { it.water })
        assertEquals(78.8, result.iceGrams!!, 0.0)
        assertEquals(9.377778, result.ratio, 0.0)
        assertEquals("Stir gently 3 times", result.pulses.first().notes)
    }

    @Test fun `a hot recipe keeps no ice, and details are checked before saving`() {
        show(recipe)
        compose.onNodeWithText("Ice in server, g").assertDoesNotExist()
        compose.onNode(hasText("Water temp, °C") and hasSetTextAction()).performScrollTo().performTextInput("101")
        compose.onNodeWithText("Save recipe").performScrollTo().assertIsNotEnabled()
        compose.onNode(hasText("Water temp, °C") and hasSetTextAction()).performTextReplacement("93")
        compose.onNode(hasText("Source URL") and hasSetTextAction()).performScrollTo().performTextInput("kurasu.kyoto")
        compose.onNodeWithText("Save recipe").performScrollTo().assertIsNotEnabled()
        compose.onNode(hasText("Source URL") and hasSetTextAction()).performTextReplacement("https://kurasu.kyoto")
        compose.onNode(hasText("Target finish") and hasSetTextAction()).performScrollTo().performTextInput("2:10")
        val result = save()
        assertEquals(listOf(93, 130), listOf(result.waterTempC, result.targetFinishSeconds))
        assertEquals("https://kurasu.kyoto", result.sourceUrl)
        assertEquals(null, result.iceGrams)
    }
}

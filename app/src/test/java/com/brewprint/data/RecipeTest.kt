package com.brewprint.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeTest {
    private val recipe = Recipe(
        name = "Three pulses", dose = 15.0, ratio = 16.0,
        pulses = listOf(Pulse("Bloom", 60.0, 10, 30), Pulse(water = 90.0, pourSeconds = 20, waitSeconds = 20), Pulse(water = 90.0, pourSeconds = 20)),
    )

    @Test fun `schedule includes waits and cumulative scale readings`() {
        assertTrue(recipe.validationErrors().isEmpty())
        assertEquals(240.0, recipe.totalWater, 0.0)
        assertEquals(listOf(0, 40, 80), recipe.schedule().map { it.startSeconds })
        assertEquals(listOf(60.0, 150.0, 240.0), recipe.schedule().map { it.cumulativeWater })
        val last = recipe.schedule().last()
        assertEquals("1:40", formatDuration(last.startSeconds + last.pulse.pourSeconds))
    }

    @Test fun `dose change reports exact mismatch without scaling pours`() {
        val changed = recipe.copy(dose = 16.0)
        assertEquals(256.0, changed.totalWater, 0.0)
        assertEquals(recipe.pulses, changed.pulses)
        assertEquals(listOf("Add 16 g across your pulses to reach 256 g."), changed.validationErrors())
    }

    @Test fun `water target rounds half up to one decimal place`() {
        val rounded = Recipe(name = "Rounded", dose = 15.1, ratio = 16.5, pulses = listOf(Pulse(water = 249.2, pourSeconds = 10)))
        assertEquals(249.2, rounded.totalWater, 0.0)
        assertTrue(rounded.validationErrors().isEmpty())
        assertEquals("249.2", formatAmount(rounded.totalWater))
    }

    @Test fun `invalid precision durations and last wait cannot be saved`() {
        assertTrue(recipe.copy(dose = 15.01).validationErrors().any { it.contains("Coffee dose") })
        assertTrue(recipe.copy(pulses = listOf(Pulse(water = 240.01, pourSeconds = 10))).validationErrors().any { it.contains("Pulse 1: water") })
        assertTrue(recipe.copy(pulses = listOf(Pulse(water = 240.0, pourSeconds = 0))).validationErrors().any { it.contains("pour duration") })
        assertTrue(recipe.copy(pulses = listOf(Pulse(water = 240.0, pourSeconds = 10, waitSeconds = 5))).validationErrors().any { it.contains("last pulse") })
        assertTrue(recipe.copy(pulses = emptyList()).validationErrors().any { it.contains("at least one") })
    }

    @Test fun `decimal operands round the water target without binary multiplication error`() {
        val rounded = Recipe(name = "Rounded", dose = 15.1, ratio = 15.5,
            pulses = listOf(Pulse(water = 234.1, pourSeconds = 10)))
        assertEquals(234.1, rounded.totalWater, 0.0)
        assertTrue(rounded.validationErrors().isEmpty())
    }

    @Test fun `zero wait starts following pour immediately`() {
        val zeroWait = recipe.copy(pulses = recipe.pulses.map { it.copy(waitSeconds = 0) })
        assertEquals(listOf(0, 10, 30), zeroWait.schedule().map { it.startSeconds })
    }
}

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

    @Test fun `the ratio comes from the dose and the pours, so a new dose is valid and changes it`() {
        assertEquals(16.0, recipe.computedRatio, 0.0)
        val changed = recipe.copy(dose = 16.0)
        assertTrue(changed.validationErrors().isEmpty())
        assertEquals(240.0, changed.totalWater, 0.0)
        assertEquals(15.0, changed.computedRatio, 0.0)
        assertEquals("15", formatRatio(changed.computedRatio))
    }

    @Test fun `the stored ratio is the pours' ratio to 6 decimals, which older versions' check accepts`() {
        val kurasu = Recipe(name = "Kurasu Iced", dose = 16.0, drinkStyle = DrinkStyle.ICED, iceGrams = 70.0,
            pulses = listOf(Pulse(water = 40.0, pourSeconds = 10, waitSeconds = 35), Pulse(water = 60.0, pourSeconds = 15, waitSeconds = 15), Pulse(water = 50.0, pourSeconds = 15))).normalized()
        assertEquals(9.375, kurasu.ratio, 0.0)
        assertEquals("9.4", formatRatio(kurasu.computedRatio))
        assertEquals("13.8", formatRatio(kurasu.overallRatio!!))
        val thirds = Recipe(name = "Thirds", dose = 15.0, pulses = listOf(Pulse(water = 250.0, pourSeconds = 50))).normalized()
        assertEquals(16.666667, thirds.ratio, 0.0)
        assertEquals("16.7", formatRatio(thirds.ratio))
        // Older versions check dose × ratio, rounded half up to 0.1 g, against the pours.
        for (stored in listOf(kurasu, thirds, recipe.normalized(), Recipe(name = "x", dose = 0.1, pulses = listOf(Pulse(water = 999.9, pourSeconds = 1))).normalized())) {
            val olderCheck = java.math.BigDecimal.valueOf(stored.dose).multiply(java.math.BigDecimal.valueOf(stored.ratio)).setScale(1, java.math.RoundingMode.HALF_UP).toDouble()
            assertEquals(stored.totalWater, olderCheck, 0.0)
        }
    }

    @Test fun `a hot recipe keeps no ice, and the details are checked`() {
        assertEquals(null, recipe.copy(iceGrams = 70.0).normalized().iceGrams)
        assertEquals(70.0, recipe.copy(drinkStyle = DrinkStyle.ICED, iceGrams = 70.0).normalized().iceGrams!!, 0.0)
        assertTrue(recipe.copy(drinkStyle = DrinkStyle.ICED, iceGrams = 0.0).validationErrors().any { it.startsWith("Ice") })
        assertTrue(recipe.copy(waterTempC = 0).validationErrors().any { it.startsWith("Water temperature") })
        assertTrue(recipe.copy(targetFinishSeconds = 0).validationErrors().any { it.startsWith("Target finish") })
        for (bad in listOf("kurasu.kyoto", "javascript:alert(1)", "ftp://example.com", "https://")) assertTrue(bad, recipe.copy(sourceUrl = bad).validationErrors().any { it.startsWith("Enter a web address") })
        assertTrue(recipe.copy(sourceUrl = "https://kurasu.kyoto/pages/iced").validationErrors().isEmpty())
    }

    @Test fun `ratios read the same in every locale`() {
        val default = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("9.4", formatRatio(9.375))
            assertEquals("13.8", formatRatio(13.75))
        } finally { java.util.Locale.setDefault(default) }
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

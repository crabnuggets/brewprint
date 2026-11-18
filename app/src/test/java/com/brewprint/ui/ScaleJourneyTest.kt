package com.brewprint.ui

import android.content.ContextWrapper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.brewprint.data.*
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
class ScaleJourneyTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var repository: BrewRepository
    private var live by mutableStateOf(ScaleLive(connected = true, grams = 0.0, steady = true))
    private var tares = 0
    private val recipe = Recipe(name = "Five-pulse filter", dose = 15.0, ratio = 16.0, pulses = listOf(
        Pulse("Bloom", 45.0, 10, 35), Pulse(water = 50.0, pourSeconds = 15, waitSeconds = 20), Pulse(water = 50.0, pourSeconds = 15, waitSeconds = 20),
        Pulse(water = 50.0, pourSeconds = 15, waitSeconds = 20), Pulse(water = 45.0, pourSeconds = 15)))

    private fun open(paired: Boolean) {
        val directory = temporary.newFolder("data")
        repository = BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = directory
        })
        val beans = Beans(name = "Ethiopia", roaster = "Sample")
        repository.upsertBeans(beans).getOrThrow()
        repository.upsertGrinder(Grinder(name = "Hand grinder", lastSetting = 24)).getOrThrow()
        repository.upsertRecipe(recipe).getOrThrow()
        if (paired) repository.setScale(PairedScale("AA:BB:CC:DD:EE:FF", "Dotty")).getOrThrow()
        repository.updateDraft(repository.startDraft().getOrThrow().copy(beans = beans, recipe = recipe, stage = DraftStage.SHEET)).getOrThrow()
        compose.setContent {
            val state by repository.state.collectAsState()
            CompositionLocalProvider(LocalScale provides ScaleUi(live) { tares++ }) {
                BrewprintTheme(state.appearance) { BrewprintApp(state, repository) }
            }
        }
        tap("Resume brew")
    }

    private fun tap(text: String) = compose.onNodeWithText(text).apply { if (!isDisplayed()) performScrollTo() }.performClick()
    private fun shows(text: String) = compose.onNodeWithText(text).assertExists()

    @Before fun reset() { tares = 0 }

    @Test fun `without a paired scale the sheet offers pairing once`() {
        open(paired = false)
        shows("Brew with a scale?")
        tap("Not now")
        compose.onNodeWithText("Brew with a scale?").assertDoesNotExist()
        assertTrue(repository.state.value.scaleOfferDismissed)
        shows("Start timer")
    }

    @Test fun `a weighed dose rescales this brew and arms the sheet for the first pour`() {
        open(paired = true)
        shows("Put the brewer on the scale")
        tap("Tare")
        assertEquals(1, tares)
        live = ScaleLive(connected = true, grams = 15.23, steady = true)
        tap("Use 15.2 g")
        assertEquals(2, tares)
        val draft = repository.state.value.draft!!
        assertEquals(15.2, draft.recipe!!.dose, 0.0)
        assertEquals(243.2, draft.recipe.totalWater, 0.0)
        assertEquals(15.2, draft.scale!!.weighedDose!!, 0.0)
        assertEquals(ScaleStep.ARMED, draft.scaleStep)
        assertEquals(15.0, repository.state.value.recipes.single().dose, 0.0)
        shows("Waiting for the first pour")
        shows("Start timer now")
    }

    @Test fun `starting by hand after a tare the scale ignored follows the clock`() {
        open(paired = true)
        tap("Tare")
        live = ScaleLive(connected = true, grams = 15.0, steady = true)
        tap("Skip")
        live = ScaleLive(connected = true, grams = 15.0, steady = true, tareFailed = true)
        shows("The scale didn't tare. Press its tare button.")
        tap("Start timer now")
        val draft = repository.state.value.draft!!
        assertFalse(draft.timer!!.byWeight)
        assertNull(draft.scale)
    }

    @Test fun `brewing without the scale keeps the clock-only sheet`() {
        open(paired = true)
        tap("Brew without the scale")
        compose.onNodeWithText("Put the brewer on the scale").assertDoesNotExist()
        tap("Start timer")
        val draft = repository.state.value.draft!!
        assertNull(draft.scale)
        assertFalse(draft.timer!!.byWeight)
    }
}

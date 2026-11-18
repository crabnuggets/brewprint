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
        // Until the scale reads 0 g after its tare, a pour cannot start the timer and no start is offered.
        live = ScaleLive(connected = true, grams = 15.23, steady = true, taring = true)
        shows("Waiting for the scale to read 0 g")
        compose.onNodeWithText("Start timer now").assertDoesNotExist()
        live = ScaleLive(connected = true, grams = 0.0, steady = true, zeroed = true)
        compose.onNode(hasContentDescription("On scale, tared · Weighed 15.2 g, 0.0 grams")).assertExists()
        shows("Waiting for the first pour")
        shows("Start timer now")
    }

    @Test fun `a reading at zero is not coffee and a dropped scale tares the brewer again`() {
        open(paired = true)
        tap("Tare")
        live = ScaleLive(connected = true, grams = 1.0, steady = true)
        compose.onNodeWithText("Use dose").assertIsNotEnabled()
        live = ScaleLive(connected = false)
        compose.runOnIdle { assertEquals(ScaleStep.BREWER, repository.state.value.draft!!.scaleStep) }
        live = ScaleLive(connected = true, grams = 312.4, steady = true)
        shows("Put the brewer on the scale")
        shows("Tare the brewer again. Take the coffee out first if you've added it.")
        tap("Tare")
        compose.onNodeWithText("Tare the brewer again. Take the coffee out first if you've added it.").assertDoesNotExist()
    }

    @Test fun `with no readings the armed sheet says so instead of waiting`() {
        open(paired = true)
        tap("Tare")
        live = ScaleLive(connected = true, grams = 15.0, steady = true)
        tap("Keep 15 g")
        live = ScaleLive(connected = true, grams = null, silent = true)
        shows("No scale reading")
        compose.onNodeWithText("Waiting for the scale to settle").assertDoesNotExist()
        compose.onNodeWithText("Tare the scale to 0 g").assertDoesNotExist()
        shows("Brew without the scale")
        live = ScaleLive(connected = true, grams = 0.0, steady = true, zeroed = true)
        compose.onNodeWithText("No scale reading").assertDoesNotExist()
        shows("Waiting for the first pour")
    }

    @Test fun `a dropped link after weighing keeps the scale card instead of a clock start`() {
        open(paired = true)
        tap("Tare")
        live = ScaleLive(connected = true, grams = 15.23, steady = true)
        tap("Use 15.2 g")
        live = ScaleLive(connected = false)
        shows("No scale reading")
        compose.onNodeWithText("Start timer").assertDoesNotExist()
        shows("Finish without timing")
        shows("Brew without the scale")
    }

    @Test fun `choosing other beans after weighing restores the recipe's dose and starts the scale steps again`() {
        open(paired = true)
        repository.upsertBeans(Beans(name = "Kenya washed", roaster = "Sample")).getOrThrow()
        tap("Tare")
        live = ScaleLive(connected = true, grams = 15.23, steady = true)
        tap("Use 15.2 g")
        tap("Change setup")
        tap("Ethiopia")
        tap("Kenya washed")
        val draft = repository.state.value.draft!!
        assertEquals("Kenya washed", draft.beans!!.name)
        assertEquals(15.0, draft.recipe!!.dose, 0.0)
        assertNull(draft.scale)
        assertEquals(ScaleStep.BREWER, draft.scaleStep)
    }

    @Test fun `choosing another recipe after weighing starts the scale steps again`() {
        open(paired = true)
        repository.upsertRecipe(Recipe(name = "One pour", dose = 12.0, ratio = 15.0, pulses = listOf(Pulse(water = 180.0, pourSeconds = 30)))).getOrThrow()
        tap("Tare")
        live = ScaleLive(connected = true, grams = 15.23, steady = true)
        tap("Use 15.2 g")
        tap("Change setup")
        tap("Five-pulse filter")
        tap("One pour")
        val draft = repository.state.value.draft!!
        assertEquals(12.0, draft.recipe!!.dose, 0.0)
        assertNull(draft.scale)
        assertEquals(ScaleStep.BREWER, draft.scaleStep)
    }

    @Test fun `a dose cannot be used while the brewer's tare is pending`() {
        open(paired = true)
        tap("Tare")
        live = ScaleLive(connected = true, grams = 312.4, steady = true, taring = true)
        shows("Taring the brewer")
        compose.onNodeWithText("Add your coffee").assertDoesNotExist()
        compose.onNodeWithText("Use dose").assertDoesNotExist()
        live = ScaleLive(connected = true, grams = 312.4, steady = true, tareFailed = true)
        shows("The scale didn't tare")
        tap("Tare")
        assertEquals(2, tares)
    }

    @Test fun `off zero the armed sheet asks for a tare and starts clock-only only through brew without the scale`() {
        open(paired = true)
        tap("Tare")
        live = ScaleLive(connected = true, grams = 15.0, steady = true)
        tap("Keep 15 g")
        live = ScaleLive(connected = true, grams = 15.0, steady = true)
        shows("Tare the scale to 0 g")
        compose.onNodeWithText("Start timer now").assertDoesNotExist()
        // Near 0 g but not yet steady, the scale is settling after a tare, so it is not asked for again.
        live = ScaleLive(connected = true, grams = 0.3, steady = false)
        shows("Waiting for the scale to settle")
        compose.onNodeWithText("Tare the scale to 0 g").assertDoesNotExist()
        live = ScaleLive(connected = true, grams = 15.0, steady = true, tareFailed = true)
        shows("The scale didn't tare")
        // The card leads the retry; the footer adds no waiting status beside it.
        compose.onNodeWithText("Waiting for the scale to settle").assertDoesNotExist()
        compose.onNodeWithText("Tare the scale to 0 g").assertDoesNotExist()
        tap("Brew without the scale")
        tap("Start timer")
        val draft = repository.state.value.draft!!
        assertFalse(draft.timer!!.byWeight)
        assertNull(draft.scale)
    }

    @Test fun `starting by hand once the scale shows its zero is guided by the scale`() {
        open(paired = true)
        tap("Tare")
        live = ScaleLive(connected = true, grams = 15.0, steady = true)
        tap("Keep 15 g")
        live = ScaleLive(connected = true, grams = 0.0, steady = true, zeroed = true)
        tap("Start timer now")
        assertTrue(repository.state.value.draft!!.timer!!.byWeight)
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

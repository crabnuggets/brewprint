package com.brewprint.ui

import android.content.ContextWrapper
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
class BrewJourneyTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var repository: BrewRepository

    @Before fun setUp() {
        val directory = temporary.newFolder("data")
        repository = BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = directory
        })
        compose.setContent {
            val state by repository.state.collectAsState()
            BrewprintTheme(state.appearance) { BrewprintApp(state, repository) }
        }
    }

    private fun tap(text: String) = compose.onNodeWithText(text).apply {
        if (!isDisplayed()) performScrollTo()
    }.performClick()
    private fun enter(label: String, value: String) = compose.onNodeWithText(label).performScrollTo().performTextInput(value)

    private fun seedBrew() {
        compose.runOnIdle {
            val beans = Beans(name = "Ethiopia", roaster = "Sample")
            val grinder = Grinder(name = "Hand grinder", lastSetting = 24)
            val recipe = Recipe(name = "Filter", dose = 15.0, ratio = 16.0,
                pulses = listOf(Pulse(water = 240.0, pourSeconds = 30)))
            repository.upsertBeans(beans).getOrThrow()
            repository.upsertGrinder(grinder).getOrThrow()
            repository.upsertRecipe(recipe).getOrThrow()
            val draft = repository.startDraft().getOrThrow().copy(beans = beans, recipe = recipe)
            repository.updateDraft(draft).getOrThrow()
            repository.saveBrew(draft.id).getOrThrow()
        }
    }

    @Test fun `first brew creates library items returns to setup and saves a repeatable record`() {
        tap("+ New brew")
        tap("Add beans")
        tap("Add beans")
        enter("Name", "Ethiopia natural")
        enter("Roaster", "Sample roaster")
        tap("Save beans")
        compose.runOnIdle { assertEquals("Ethiopia natural", repository.state.value.draft?.beans?.name) }

        tap("Add grinder")
        tap("Add grinder")
        enter("Name", "Hand grinder")
        enter("Last recorded setting, ticks, optional", "24")
        tap("Save grinder")
        compose.runOnIdle {
            assertEquals("Ethiopia natural", repository.state.value.draft?.beans?.name)
            assertEquals(24, repository.state.value.draft?.setting)
        }

        tap("Choose or create recipe")
        tap("Add recipe")
        enter("Name", "One pour")
        enter("Coffee dose, g", "15")
        enter("Brew ratio, 1 :", "16")
        enter("Water to add, g", "240")
        enter("Pour duration, seconds", "30")
        tap("Save recipe")
        tap("Use this plan")
        compose.onNodeWithText("Pour to 240 g total").assertExists()
        tap("Finish brew")
        enter("What did you notice? Everyday words are welcome.", "Cocoa, lighter than expected")
        tap("Save brew")
        compose.onNodeWithText("Brew record").assertExists()
        compose.runOnIdle {
            assertNull(repository.state.value.draft)
            assertEquals(1, repository.state.value.brews.size)
            assertEquals("Cocoa, lighter than expected", repository.state.value.brews.single().journal.impression)
        }
        tap("Repeat")
        compose.runOnIdle {
            val state = repository.state.value
            assertEquals(24, state.draft?.setting)
            assertTrue(state.draft!!.journal.isEmpty)
            assertEquals("Cocoa, lighter than expected", state.brews.single().journal.impression)
        }
    }

    @Test fun `edited recipe target blocks save without silently scaling pulses`() {
        compose.runOnIdle {
            repository.upsertRecipe(Recipe(name = "Filter", dose = 15.0, ratio = 16.0,
                pulses = listOf(Pulse(water = 240.0, pourSeconds = 30)))).getOrThrow()
        }
        tap("Library")
        tap("Filter")
        tap("Edit recipe")
        compose.onNodeWithText("Coffee dose, g").performScrollTo().performTextReplacement("16")
        compose.onNodeWithText("Save recipe").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Add 16 g across your pulses to reach 256 g.").assertExists()
        compose.runOnIdle { assertEquals(240.0, repository.state.value.recipes.single().plannedWater, 0.0) }
    }

    @Test fun `partial date correction requires discard confirmation`() {
        seedBrew()
        tap("View brew")
        tap("Edit brew")
        compose.onNodeWithText("Brew date and time").performScrollTo().performTextReplacement("")
        tap("Ethiopia")
        tap("Back")
        compose.onNodeWithText("Save brew").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard changes?").assertExists()
        tap("Keep editing")
        compose.onNodeWithText("Save brew").performScrollTo().assertIsNotEnabled()
    }

    @Test fun `notes and repeated draft return to their preceding screens`() {
        seedBrew()
        tap("Add tasting notes")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Most recent brew").assertExists()
        tap("View brew")
        tap("Repeat")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Brew record").assertExists()
    }
}

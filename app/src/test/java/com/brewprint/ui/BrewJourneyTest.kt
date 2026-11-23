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

    // Icon actions such as "Change date" are found by their spoken name.
    private fun tap(text: String) = (if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) compose.onNodeWithContentDescription(text) else compose.onNodeWithText(text)).apply {
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
        tap("New brew")
        // With nothing to choose from, each row opens its create form directly.
        tap("Add beans")
        enter("Name", "Ethiopia natural")
        enter("Roaster", "Sample roaster")
        tap("Save beans")
        compose.runOnIdle { assertEquals("Ethiopia natural", repository.state.value.draft?.beans?.name) }

        tap("Add grinder")
        enter("Name", "Hand grinder")
        enter("Last recorded setting, ticks, optional", "24")
        tap("Save grinder")
        compose.runOnIdle {
            assertEquals("Ethiopia natural", repository.state.value.draft?.beans?.name)
            assertEquals(24, repository.state.value.draft?.setting)
        }

        tap("Add recipe")
        enter("Name", "One pour")
        enter("Coffee dose, g", "15")
        enter("Brew ratio", "16")
        enter("Water to add, g", "240")
        enter("Pour duration, seconds", "30")
        tap("Save recipe")
        tap("Use this plan")
        compose.onNodeWithContentDescription("Pulse 1, starts at the first pour. Add 240 grams. Pour for 30 seconds.").assertExists()
        tap("Finish without timing")
        enter("Your notes", "Cocoa, lighter than expected")
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
        compose.openLibrarySection("recipes")
        tap("Filter")
        tap("Edit recipe")
        compose.onNodeWithText("Coffee dose, g").performScrollTo().performTextReplacement("16")
        compose.onNodeWithText("Save recipe").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Add 16 g across your pulses to reach 256 g.").assertExists()
        compose.runOnIdle { assertEquals(240.0, repository.state.value.recipes.single().plannedWater, 0.0) }
    }

    @Test fun `recipe actions sit in the header`() {
        compose.runOnIdle {
            repository.upsertRecipe(Recipe(name = "Filter", dose = 15.0, ratio = 16.0,
                pulses = List(10) { Pulse(water = 24.0, pourSeconds = 5, waitSeconds = if (it < 9) 10 else 0) })).getOrThrow()
        }
        compose.openLibrarySection("recipes")
        tap("Filter")
        // Reachable without scrolling past ten pulses.
        compose.onNodeWithContentDescription("Edit recipe").assertIsDisplayed()
        compose.onNodeWithContentDescription("More actions").assertIsDisplayed().performClick()
        compose.onNodeWithText("Duplicate recipe").assertExists()
        compose.onNodeWithText("Archive recipe").performClick()
        compose.onNodeWithText("Archive").performClick()
        compose.runOnIdle { assertTrue(repository.state.value.recipes.single().archived) }
    }

    @Test fun `partial date correction requires discard confirmation`() {
        seedBrew()
        tap("View brew")
        tap("Edit brew")
        tap("Change date")
        compose.onNodeWithText("Brew date and time").performScrollTo().performTextReplacement("")
        tap("Ethiopia")
        compose.onNodeWithContentDescription("Back").performClick()
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

    @Test fun `leaving a new brew unchanged discards it and a changed one is kept`() {
        seedBrew()
        tap("New brew")
        // The selected setting is spoken as one value.
        compose.onNodeWithContentDescription("Grind setting, 24 ticks").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertNull(repository.state.value.draft) }
        compose.onNodeWithText("Repeat").assertIsEnabled()
        tap("New brew")
        tap("Adjust")
        compose.onNodeWithText("Enter value, ticks").performTextReplacement("25")
        tap("Close")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(25, repository.state.value.draft?.setting) }
        compose.onNodeWithText("Resume brew").assertExists()
    }
}

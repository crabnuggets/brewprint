package com.brewprint.ui

import android.content.ContextWrapper
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
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
class UiRefreshTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var repository: BrewRepository
    private val error = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)

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

    private fun field(label: String) = compose.onNode(hasText(label) and hasSetTextAction())

    private fun openForm(kind: String) {
        tap("Library")
        tap("Add $kind")
    }

    private fun assertUntouched(save: String) {
        compose.onAllNodes(error).assertCountEquals(0)
        assertTrue(compose.onAllNodesWithText("Required", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText(save).performScrollTo().assertIsNotEnabled()
    }

    private fun seedSavedBrew() {
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

    @Test fun `navigation has exactly three named tabs with one selected after every selection`() {
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onAllNodes(tab).assertCountEquals(3)
        for (destination in listOf("Brew", "History", "Library", "Brew")) {
            compose.onNode(tab and hasText(destination)).performClick().assertIsSelected()
            compose.onAllNodes(tab and isSelected()).assertCountEquals(1)
            listOf("Brew", "History", "Library").filter { it != destination }.forEach {
                compose.onNode(tab and hasText(it)).assertIsNotSelected().assertHasClickAction()
            }
        }
    }

    @Test fun `tasting notes keep a named input and visible save action during exploration`() {
        seedSavedBrew()
        tap("Add tasting notes")
        val observations = field("Your notes")
        observations.performTextInput("Sweet and silky")
        observations.assertTextContains("Sweet and silky")
        observations.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        field("Reminder").performScrollTo().performTextInput("Repeat at 24 ticks")
        field("Reminder").assertTextContains("Repeat at 24 ticks")
        field("Reminder").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        tap("Explore the taste")
        observations.assertHasClickAction()
        compose.onNodeWithText("Save notes").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("Sweet and silky", repository.state.value.brews.single().journal.impression)
            assertEquals("Repeat at 24 ticks", repository.state.value.brews.single().journal.nextTime)
        }
    }

    @Test fun `new beans stays neutral until blur and valid fields enable saving`() {
        openForm("beans")
        assertUntouched("Save beans")
        compose.onNodeWithText("Enter a name.").assertDoesNotExist()
        compose.onNodeWithText("Enter a roaster.").assertDoesNotExist()
        field("Name").performScrollTo().performClick()
        field("Roaster").performClick()
        field("Name").assert(error)
        compose.onNodeWithText("Enter a name.").assertExists()
        field("Roaster").assert(error.not())
        field("Name").performTextInput("Ethiopia")
        field("Roaster").performTextInput("Sample")
        compose.onAllNodes(error).assertCountEquals(0)
        compose.onNodeWithText("Save beans").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("Ethiopia", repository.state.value.beans.single().name) }
    }

    @Test fun `new grinder is neutral and library editing enforces inclusive whole ticks`() {
        openForm("grinder")
        assertUntouched("Save grinder")
        compose.onNodeWithText("Enter a grinder name.").assertDoesNotExist()
        field("Name").performScrollTo().performTextInput("Hand grinder")
        val setting = field("Last recorded setting, ticks, optional")
        for (invalid in listOf("-1", "101", "24.0", "24.5", "1e1", "abc")) {
            setting.performScrollTo().performTextReplacement(invalid)
            setting.assert(error)
            compose.onNodeWithText("Enter whole ticks from 0 to 100.").assertExists()
            compose.onNodeWithText("Save grinder").performScrollTo().assertIsNotEnabled()
        }
        setting.performScrollTo().performTextReplacement("0")
        setting.assert(error.not())
        compose.onNodeWithText("Save grinder").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(0, repository.state.value.grinders.single().lastSetting) }
        tap("Edit grinder")
        field("Last recorded setting, ticks, optional").performScrollTo().performTextReplacement("100")
        compose.onNodeWithText("Save grinder").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(100, repository.state.value.grinders.single().lastSetting) }
    }

    // Match Robolectric's floating-window width to avoid its dialog layout loop: robolectric/robolectric#8460.
    @Config(qualifiers = "w320dp-h891dp")
    @Test fun `explicit grinder setting update rejects invalid ticks and saves endpoint`() {
        compose.runOnIdle { repository.upsertGrinder(Grinder(name = "Hand grinder", lastSetting = 100)).getOrThrow() }
        tap("Library")
        tap("Hand grinder")
        tap("Update last recorded setting")
        field("Grind setting, ticks").performTextReplacement("101")
        field("Grind setting, ticks").assert(error)
        compose.onNodeWithText("Update setting").assertIsNotEnabled()
        field("Grind setting, ticks").performTextReplacement("0")
        compose.onNodeWithText("Update setting").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(0, repository.state.value.grinders.single().lastSetting) }
    }

    @Test fun `new recipe stays neutral and edited invalid values show errors before valid save`() {
        openForm("recipe")
        assertUntouched("Save recipe")
        compose.onNodeWithText("Enter a recipe name.").assertDoesNotExist()
        compose.onNodeWithText("Enter a positive amount with up to one decimal place.").assertDoesNotExist()
        field("Name").performScrollTo().performTextInput("One pour")
        field("Coffee dose, g").performScrollTo().performTextInput("0")
        field("Coffee dose, g").assert(error)
        field("Coffee dose, g").performTextReplacement("15")
        field("Brew ratio").performScrollTo().performTextInput("16")
        field("Water to add, g").performScrollTo().performTextInput("240")
        field("Pour duration, seconds").performScrollTo().performTextInput("30")
        compose.onAllNodes(error).assertCountEquals(0)
        compose.onNodeWithText("Save recipe").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(repository.state.value.recipes.single().validationErrors().isEmpty()) }
    }

    @Test fun `icon back shares the title row and returns through create detail and picker screens`() {
        compose.runOnIdle { repository.upsertBeans(Beans(name = "Ethiopia", roaster = "Sample")).getOrThrow() }
        openForm("beans")
        val back = compose.onNodeWithContentDescription("Back")
        back.assertHasClickAction()
        compose.onNodeWithText("Back").assertDoesNotExist()
        val backBounds = back.fetchSemanticsNode().boundsInRoot
        val titleBounds = compose.onNodeWithText("Add beans").fetchSemanticsNode().boundsInRoot
        assertTrue(backBounds.right <= titleBounds.left)
        assertEquals(backBounds.center.y, titleBounds.center.y, 2f)
        back.performClick()
        compose.onAllNodesWithText("Library").assertCountEquals(2)
        tap("Ethiopia")
        compose.onNodeWithText("Roaster").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onAllNodesWithText("Library").assertCountEquals(2)
        tap("Brew")
        tap("New brew")
        tap("Add beans")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("New brew").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Resume brew").assertExists()
    }

    @Test fun `record correction rejects invalid ticks and accepts endpoints without changing grinder`() {
        seedSavedBrew()
        tap("View brew")
        for (endpoint in listOf("0", "100")) {
            tap("Edit brew")
            for (invalid in listOf("-1", "101", "1.0", "1.5", "abc")) {
                field("Grind setting, ticks").performScrollTo().performTextReplacement(invalid)
                field("Grind setting, ticks").assert(error)
                compose.onNodeWithText("Save brew").performScrollTo().assertIsNotEnabled()
            }
            field("Grind setting, ticks").performScrollTo().performTextReplacement(endpoint)
            compose.onNodeWithText("Save brew").performScrollTo().assertIsEnabled().performClick()
            compose.runOnIdle {
                assertEquals(endpoint.toInt(), repository.state.value.brews.single().setting)
                assertEquals(24, repository.state.value.grinders.single().lastSetting)
            }
        }
    }

    @Test fun `dial input gates draft and retains changes until brew save updates grinder`() {
        seedSavedBrew()
        tap("New brew")
        tap("Adjust")
        field("Enter value, ticks").performScrollTo().performTextReplacement("101")
        tap("Close")
        compose.onNodeWithText("Use this plan").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle {
            assertNull(repository.state.value.draft?.setting)
            assertEquals(24, repository.state.value.grinders.single().lastSetting)
        }
        tap("Adjust")
        field("Enter value, ticks").performScrollTo().performTextReplacement("100")
        tap("Close")
        compose.runOnIdle {
            assertEquals(100, repository.state.value.draft?.setting)
            assertEquals(24, repository.state.value.grinders.single().lastSetting)
        }
        tap("Use this plan")
        tap("Finish brew")
        tap("Save brew")
        compose.runOnIdle {
            assertEquals(100, repository.state.value.grinders.single().lastSetting)
            assertEquals(listOf(24, 100), repository.state.value.brews.map { it.setting }.sorted())
        }
    }
}

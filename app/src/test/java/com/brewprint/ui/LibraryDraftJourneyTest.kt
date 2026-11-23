package com.brewprint.ui

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.brewprint.data.*
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
class LibraryDraftJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()

    private val kii = Beans(name = "Kii", roaster = "Tim Wendelboe", origin = "Kenya", expectedNotes = "Blackcurrant, hibiscus")
    private val plan = Recipe(name = "Three pours", dose = 15.0, ratio = 16.0,
        pulses = listOf(Pulse("Bloom", 45.0, 10, 35), Pulse(water = 100.0, pourSeconds = 15, waitSeconds = 20), Pulse(water = 95.0, pourSeconds = 15)))

    private fun repository(): BrewRepository {
        val data = temporary.newFolder("data")
        return BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = data
        }).also { it.applyDrafts(listOf(LibraryDraft(kii.id, beans = kii), LibraryDraft(plan.id, recipe = plan))).getOrThrow() }
    }

    private fun show(repository: BrewRepository) = compose.setContent {
        val state by repository.state.collectAsState()
        BrewprintTheme(Appearance.LIGHT) { BrewprintApp(state, repository) }
    }

    @Test fun `drafts from Claude are reviewed, edited and added from the Library`() {
        val repository = repository()
        show(repository)
        compose.onNodeWithText("Review 2 drafts from Claude").performClick()
        compose.onNodeWithText("Drafts from Claude").assertIsDisplayed()
        compose.onNode(hasText("Kii") and hasText("Beans") and hasText("Tim Wendelboe")).assertIsDisplayed()
        compose.onNode(hasText("Three pours") and hasText("Recipe") and hasText("15 g · 1:16 · 240 g")).assertIsDisplayed()

        compose.onNodeWithText("Kii").performClick()
        compose.onNodeWithText("Review beans").assertIsDisplayed()
        compose.onNodeWithText("Blackcurrant, hibiscus").assertExists()
        compose.onNode(hasText("Processing method, optional") and hasSetTextAction()).performTextInput("Washed")
        compose.onNodeWithText("Add to Library").performScrollTo().performClick()

        // The added beans open as a Library item, and only the recipe is still waiting.
        compose.onNodeWithText("Washed").assertIsDisplayed()
        val state = repository.state.value
        assertEquals(listOf(kii.copy(processing = "Washed")), state.beans)
        assertEquals(listOf(plan.id), state.libraryDrafts.map { it.id })
        assertTrue("drafts/${kii.id}" in state.sync.pending)
    }

    @Test fun `a draft can be left for later or discarded`() {
        val repository = repository()
        show(repository)
        compose.onNode(hasText("Library") and isSelectable()).performClick()
        compose.onNodeWithText("Three pours").performClick()
        compose.onNodeWithText("Review recipe").assertIsDisplayed()
        compose.onNodeWithText("Planned pours: 240 g / Target: 240 g").performScrollTo().assertExists()
        // Back without changes keeps the draft waiting.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        compose.onNodeWithText("Drafts from Claude").assertIsDisplayed()

        compose.onNodeWithText("Three pours").performClick()
        compose.onNodeWithContentDescription("Discard draft").performClick()
        compose.onNodeWithText("Keep draft").performClick()
        compose.onNodeWithText("Review recipe").assertIsDisplayed()
        compose.onNodeWithContentDescription("Discard draft").performClick()
        compose.onNodeWithText("Discard").performClick()

        compose.onNodeWithText("Three pours").assertDoesNotExist()
        val state = repository.state.value
        assertTrue(state.recipes.isEmpty())
        assertEquals(listOf(kii.id), state.libraryDrafts.map { it.id })
        assertTrue("drafts/${plan.id}" in state.sync.pending)
    }

    @Test fun `a correction from Claude reaches an open review until the user edits it`() {
        val repository = repository()
        show(repository)
        compose.onNodeWithText("Review 2 drafts from Claude").performClick()
        compose.onNodeWithText("Three pours").performClick()
        val corrected = plan.copy(name = "Two pours", pulses = listOf(Pulse("Bloom", 40.0, 10, 30), Pulse(water = 200.0, pourSeconds = 40)))
        compose.runOnIdle { repository.applyDrafts(listOf(LibraryDraft(kii.id, beans = kii), LibraryDraft(plan.id, recipe = corrected))).getOrThrow() }
        compose.onNodeWithText("Two pours").assertIsDisplayed()
        compose.onNodeWithText("Planned pours: 240 g / Target: 240 g").assertExists()
        compose.onNodeWithText("Add to Library").performScrollTo().performClick()
        assertEquals(listOf(corrected), repository.state.value.recipes)

        compose.onNodeWithText("Two pours").assertIsDisplayed()
        // Back to the recipes, then to the hub, where the beans draft still waits.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Kii").performClick()
        compose.onNode(hasText("Processing method, optional") and hasSetTextAction()).performTextInput("Washed")
        compose.runOnIdle { repository.applyDrafts(listOf(LibraryDraft(kii.id, beans = kii.copy(origin = "Kenya, Nyeri")))).getOrThrow() }
        // The user's edits stay; adding saves what they see.
        compose.onNodeWithText("Kenya, Nyeri").assertDoesNotExist()
        compose.onNodeWithText("Add to Library").performScrollTo().performClick()
        assertEquals(listOf(kii.copy(processing = "Washed")), repository.state.value.beans)
    }

    @Test fun `a draft added or discarded elsewhere closes its review`() {
        val repository = repository()
        show(repository)
        compose.onNode(hasText("Library") and isSelectable()).performClick()
        compose.onNodeWithText("Kii").performClick()
        compose.onNodeWithText("Review beans").assertIsDisplayed()
        compose.runOnIdle { repository.applyDrafts(listOf(LibraryDraft(plan.id, recipe = plan))).getOrThrow() }
        compose.onNodeWithText("Review beans").assertDoesNotExist()
        compose.onNodeWithText("Drafts from Claude").assertIsDisplayed()
        assertTrue(repository.state.value.beans.isEmpty())
    }

    @Test fun `with no drafts, the Brew screen and Library don't mention them`() {
        val data = temporary.newFolder("empty")
        val repository = BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = data
        })
        show(repository)
        compose.onNodeWithText("Review", substring = true).assertDoesNotExist()
        compose.onNode(hasText("Library") and isSelectable()).performClick()
        compose.onNodeWithText("Drafts from Claude").assertDoesNotExist()
    }
}

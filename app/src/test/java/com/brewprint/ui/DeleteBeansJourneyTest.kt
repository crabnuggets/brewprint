package com.brewprint.ui

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.brewprint.data.*
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class DeleteBeansJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()

    private val kii = Beans(name = "Kii", roaster = "Tim Wendelboe")
    private val gesha = Beans(name = "Gesha", roaster = "Onyx")
    private val grinder = Grinder(name = "Comandante", lastSetting = 24)
    private val plan = Recipe(name = "One pour", dose = 15.0, ratio = 16.0, pulses = listOf(Pulse(water = 240.0, pourSeconds = 30)))

    private fun repository(vararg beans: Beans): BrewRepository {
        val data = temporary.newFolder("data")
        return BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = data
        }).also { repository ->
            beans.forEach { repository.upsertBeans(it).getOrThrow() }
            repository.upsertGrinder(grinder).getOrThrow()
            repository.upsertRecipe(plan).getOrThrow()
        }
    }

    private fun show(repository: BrewRepository) = compose.setContent {
        val state by repository.state.collectAsState()
        BrewprintTheme(Appearance.LIGHT) { BrewprintApp(state, repository) }
    }

    /** A brew draft in setup using [beans]. */
    private fun draftWith(repository: BrewRepository, beans: Beans) {
        val draft = repository.startDraft().getOrThrow()
        repository.updateDraft(draft.copy(beans = beans, recipe = plan, grinder = grinder, setting = 24)).getOrThrow()
    }

    @Test fun `beans are deleted from their Library page after asking`() {
        val repository = repository(kii, gesha)
        show(repository)
        compose.openLibrarySection("beans")
        compose.onNodeWithText("Kii").performClick()
        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Delete beans").performClick()
        compose.onNodeWithText("Delete these beans?").assertIsDisplayed()
        compose.onNodeWithText("Keep beans").performClick()
        assertEquals(2, repository.state.value.beans.size)

        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Delete beans").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Gesha").assertIsDisplayed()
        compose.onNodeWithText("Kii").assertDoesNotExist()
        assertEquals(listOf(gesha), repository.state.value.beans)
    }

    @Test fun `a short swipe archives beans and a long one deletes them, each with Undo`() {
        val repository = repository(kii, gesha)
        show(repository)
        compose.openLibrarySection("beans")
        compose.onNodeWithText("Kii").performTouchInput { swipeLeft(startX = right - 1f, endX = centerX) }
        compose.onNodeWithText("Beans archived").assertExists()
        compose.onNodeWithText("Kii").assertDoesNotExist()
        compose.runOnIdle { assertTrue(repository.state.value.beans.first { it.id == kii.id }.archived) }
        compose.onNodeWithText("Undo").performClick()
        // Undo keeps the beans in their place.
        compose.runOnIdle { assertEquals(listOf(kii, gesha), repository.state.value.beans) }

        compose.onNodeWithText("Gesha").performTouchInput { swipeLeft(startX = right - 1f, endX = left) }
        compose.onNodeWithText("Beans deleted").assertExists()
        compose.runOnIdle { assertEquals(listOf(kii), repository.state.value.beans) }
        compose.onNodeWithText("Undo").performClick()
        compose.runOnIdle { assertEquals(setOf(kii, gesha), repository.state.value.beans.toSet()) }

        // Screen readers get both actions on the row.
        val row = compose.onNodeWithText("Kii").fetchSemanticsNode()
        assertEquals(listOf("Archive", "Delete"), row.config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].map { it.label })
        compose.runOnIdle { row.config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].first { it.label == "Archive" }.action() }
        compose.runOnIdle { assertTrue(repository.state.value.beans.first { it.id == kii.id }.archived) }
    }

    @Test fun `a swipe the system cancels springs back without acting`() {
        val repository = repository(kii, gesha)
        show(repository)
        compose.openLibrarySection("beans")
        compose.onNodeWithText("Kii").performTouchInput { down(Offset(width - 4f, centerY)); moveBy(Offset(-width * .9f, 0f)); cancel() }
        compose.onNodeWithText("Beans deleted").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(kii, gesha), repository.state.value.beans) }
    }

    @Test fun `a newer swipe takes over the Undo bar`() {
        val repository = repository(kii, gesha)
        show(repository)
        compose.openLibrarySection("beans")
        compose.onNodeWithText("Kii").performTouchInput { swipeLeft(startX = right - 1f, endX = centerX) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Gesha").performTouchInput { swipeLeft(startX = right - 1f, endX = left) }
        compose.onNodeWithText("Beans deleted").assertExists()
        compose.onNodeWithText("Undo").performClick()
        compose.runOnIdle {
            assertTrue(repository.state.value.beans.first { it.id == kii.id }.archived)
            assertTrue(repository.state.value.beans.any { it.id == gesha.id })
        }
    }

    @Test fun `Undo leaves beans deleted meanwhile deleted, and an undone delete is remembered again`() {
        val repository = repository(kii, gesha)
        draftWith(repository, kii)
        repository.saveBrew(repository.state.value.draft!!.copy(stage = DraftStage.REVIEW).also { repository.updateDraft(it).getOrThrow() }.id).getOrThrow()
        show(repository)
        compose.openLibrarySection("beans")
        compose.onNodeWithText("Gesha").performTouchInput { swipeLeft(startX = right - 1f, endX = centerX) }
        compose.runOnIdle { repository.deleteBeans(gesha.id).getOrThrow() }
        compose.onNodeWithText("Undo").performClick()
        compose.runOnIdle { assertEquals(listOf(kii.id), repository.state.value.beans.map { it.id }) }

        compose.onNodeWithText("Kii").performTouchInput { swipeLeft(startX = right - 1f, endX = left) }
        compose.runOnIdle { assertNull(repository.state.value.lastBeansId) }
        compose.onNodeWithText("Undo").performClick()
        compose.runOnIdle { assertEquals(kii.id, repository.state.value.lastBeansId) }
    }

    @Test fun `a swipe waits behind a History Undo and then gets its full time`() {
        val repository = repository(kii, gesha)
        draftWith(repository, gesha)
        repository.updateDraft(repository.state.value.draft!!.copy(stage = DraftStage.REVIEW)).getOrThrow()
        repository.saveBrew(repository.state.value.draft!!.id).getOrThrow()
        show(repository)
        compose.onNode(hasText("History") and isSelectable()).performClick()
        compose.onNodeWithText("Gesha").performTouchInput { swipeLeft() }
        compose.openLibrarySection("beans")
        compose.onNodeWithText("Kii").performTouchInput { swipeLeft(startX = right - 1f, endX = centerX) }
        // The History Undo keeps its bar and its time.
        compose.onNodeWithText("Brew deleted").assertExists()
        compose.mainClock.advanceTimeBy(UNDO_MILLIS + 1_000)
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(repository.state.value.brews.isEmpty()) }
        compose.onNodeWithText("Beans archived").assertExists()
        // Its bar showed about when the History one ended, so 3 s on its Undo is still there.
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithText("Undo").performClick()
        compose.runOnIdle { assertTrue(repository.state.value.beans.none { it.archived }) }
    }

    @Test fun `a swipe's Undo survives the screen being recreated, until its time is up`() {
        val repository = repository(kii, gesha)
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            val state by repository.state.collectAsState()
            BrewprintTheme(Appearance.LIGHT) { BrewprintApp(state, repository) }
        }
        compose.openLibrarySection("beans")
        compose.onNodeWithText("Kii").performTouchInput { swipeLeft(startX = right - 1f, endX = left) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Beans deleted").assertExists()
        compose.onNodeWithText("Undo").performClick()
        compose.runOnIdle { assertTrue(repository.state.value.beans.any { it.id == kii.id }) }

        compose.onNodeWithText("Gesha").performTouchInput { swipeLeft(startX = right - 1f, endX = left) }
        compose.mainClock.advanceTimeBy(UNDO_MILLIS + 1_000)
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Beans deleted").assertDoesNotExist()
        compose.runOnIdle { assertTrue(repository.state.value.beans.none { it.id == gesha.id }) }
    }

    @Test fun `Undo of a delete does nothing once the account has changed`() {
        val repository = repository(kii, gesha)
        show(repository)
        compose.openLibrarySection("beans")
        compose.onNodeWithText("Kii").performTouchInput { swipeLeft(startX = right - 1f, endX = left) }
        compose.runOnIdle { repository.syncWith("user-1").getOrThrow() }
        compose.onNodeWithText("Undo").performClick()
        compose.runOnIdle { assertEquals(listOf(gesha), repository.state.value.beans) }
    }

    @Test fun `beans deleted elsewhere close their page and its dialogs`() {
        val repository = repository(kii, gesha)
        show(repository)
        compose.openLibrarySection("beans")
        compose.onNodeWithText("Kii").performClick()
        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Archive beans").performClick()
        compose.onNodeWithText("Archive beans?").assertIsDisplayed()
        compose.runOnIdle { repository.deleteBeans(kii.id).getOrThrow() }
        compose.onNodeWithText("Archive beans?").assertDoesNotExist()
        compose.onNodeWithText("Gesha").performClick()
        compose.onNodeWithText("Archive beans?").assertDoesNotExist()
        compose.onNodeWithText("Delete these beans?").assertDoesNotExist()
    }

    @Test fun `Finish brew with deleted beans saves only after choosing other beans`() {
        val repository = repository(kii, gesha)
        draftWith(repository, kii)
        repository.updateDraft(repository.state.value.draft!!.copy(stage = DraftStage.REVIEW)).getOrThrow()
        repository.deleteBeans(kii.id).getOrThrow()
        show(repository)
        compose.onNodeWithText("Resume brew").performClick()
        compose.onNodeWithText("These beans were deleted.").assertIsDisplayed()
        compose.onNodeWithText("Save brew").assertIsNotEnabled()
        compose.onNodeWithText("Choose other beans").performClick()
        compose.onNodeWithText("Gesha").performClick()
        compose.onNodeWithText("These beans were deleted.").assertDoesNotExist()
        compose.onNodeWithText("Save brew").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(gesha, repository.state.value.brews.single().beans) }
    }

    @Test fun `archived beans are listed apart and can be deleted there`() {
        val repository = repository(kii, gesha)
        repository.archiveBeans(kii.id).getOrThrow()
        show(repository)
        compose.openLibrarySection("beans")
        compose.onNodeWithText("Kii").assertDoesNotExist()
        compose.onNodeWithText("Archived beans").performScrollTo().performClick()
        compose.onNodeWithText("Kii").performClick()
        compose.onNodeWithText("Archived").assertIsDisplayed()
        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Archive beans").assertDoesNotExist()
        compose.onNodeWithText("Delete beans").performClick()
        compose.onNodeWithText("Delete").performClick()
        // Back on the archived list, now empty.
        compose.onNodeWithText("No archived beans.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(gesha), repository.state.value.beans) }
    }

    @Test fun `on the archived list a short swipe unarchives beans and a long one deletes them`() {
        val repository = repository(kii, gesha)
        repository.archiveBeans(kii.id).getOrThrow()
        repository.archiveBeans(gesha.id).getOrThrow()
        show(repository)
        compose.openLibrarySection("beans")
        compose.onNodeWithText("Archived beans").performScrollTo().performClick()
        compose.onNodeWithText("Kii").performTouchInput { swipeLeft(startX = right - 1f, endX = centerX) }
        compose.onNodeWithText("Beans unarchived").assertExists()
        compose.onNodeWithText("Kii").assertDoesNotExist()
        compose.runOnIdle { assertFalse(repository.state.value.beans.first { it.id == kii.id }.archived) }
        compose.onNodeWithText("Undo").performClick()
        compose.runOnIdle { assertTrue(repository.state.value.beans.first { it.id == kii.id }.archived) }

        compose.onNodeWithText("Gesha").performTouchInput { swipeLeft(startX = right - 1f, endX = left) }
        compose.onNodeWithText("Beans deleted").assertExists()
        compose.runOnIdle { assertEquals(listOf(kii.id), repository.state.value.beans.map { it.id }) }
        val row = compose.onNodeWithText("Kii").fetchSemanticsNode()
        assertEquals(listOf("Unarchive", "Delete"), row.config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].map { it.label })
    }

    @Test fun `archived beans are unarchived from their page and return to selection`() {
        val repository = repository(kii, gesha)
        repository.archiveBeans(kii.id).getOrThrow()
        show(repository)
        compose.openLibrarySection("beans")
        compose.onNodeWithText("Archived beans").performScrollTo().performClick()
        compose.onNodeWithText("Kii").performClick()
        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Unarchive beans").performClick()
        compose.onNodeWithText("Archived").assertDoesNotExist()
        compose.runOnIdle { assertFalse(repository.state.value.beans.first { it.id == kii.id }.archived) }
        // Its menu offers archiving again, and Back finds the archived list empty.
        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Archive beans").assertIsDisplayed()
        compose.onNodeWithText("Unarchive beans").assertDoesNotExist()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("No archived beans.").assertIsDisplayed()
    }

    @Test fun `a draft using deleted beans chooses other beans before continuing`() {
        val repository = repository(kii, gesha)
        draftWith(repository, kii)
        repository.deleteBeans(kii.id).getOrThrow()
        show(repository)
        compose.onNodeWithText("Resume brew").performClick()
        compose.onNodeWithText("These beans were deleted.").assertIsDisplayed()
        compose.onNodeWithText("Use this plan").assertIsNotEnabled()

        compose.onNodeWithText("Choose other beans").performClick()
        compose.onNodeWithText("Gesha").performClick()
        compose.onNodeWithText("These beans were deleted.").assertDoesNotExist()
        compose.onNodeWithText("Use this plan").assertIsEnabled()
        assertEquals(gesha, repository.state.value.draft?.beans)
    }

    @Test fun `with no beans left, a draft using deleted beans adds beans or is discarded`() {
        val repository = repository(kii)
        draftWith(repository, kii)
        repository.deleteBeans(kii.id).getOrThrow()
        show(repository)
        compose.onNodeWithText("Resume brew").performClick()
        compose.onNodeWithText("Add beans").assertIsDisplayed()
        compose.onNodeWithText("Discard brew").performClick()
        compose.onNodeWithText("Discard this brew?").assertIsDisplayed()
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("New brew").assertIsDisplayed()
        assertNull(repository.state.value.draft)
        assertTrue(repository.state.value.beans.isEmpty())
    }
}

package com.brewprint.ui

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.brewprint.cloud.CloudState
import com.brewprint.data.*
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class DraftNotificationJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()

    private val guji = Beans(name = "Guji Hambela", roaster = "Onyx Coffee Lab")
    private val summer = Recipe(name = "Summer V60", dose = 15.0, pulses = listOf(Pulse(water = 240.0, pourSeconds = 40)))
    private val grinder = Grinder(name = "Comandante", lastSetting = 24)
    private lateinit var older: BrewRecord

    private fun repository(signedIn: Boolean = true): BrewRepository {
        val data = temporary.newFolder("data")
        val repository = BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = data
        })
        if (signedIn) repository.syncWith("user-1").getOrThrow()
        repository.upsertGrinder(grinder).getOrThrow()
        repository.upsertRecipe(summer.copy(id = "saved-recipe")).getOrThrow()
        repository.upsertBeans(guji.copy(id = "saved-beans")).getOrThrow()
        val now = System.currentTimeMillis()
        fun brew(startedAt: Long): BrewRecord {
            val draft = repository.startDraft().getOrThrow().copy(beans = guji.copy(id = "saved-beans"), grinder = grinder, setting = 24, recipe = summer.copy(id = "saved-recipe"), startedAt = startedAt)
            repository.updateDraft(draft).getOrThrow()
            return repository.saveBrew(draft.id).getOrThrow()
        }
        older = brew(now - 86_400_000L)
        brew(now)
        repository.applyDrafts(listOf(LibraryDraft(summer.id, recipe = summer)), listOf(JournalDraft(older.id, Journal(impression = "Peachy")))).getOrThrow()
        return repository
    }

    private var request by mutableStateOf<ReviewRequest?>(null)

    private fun show(repository: BrewRepository) = compose.setContent {
        val state by repository.state.collectAsState()
        BrewprintTheme(Appearance.LIGHT) { BrewprintApp(state, repository, reviewRequest = request) }
    }

    private fun back() {
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    @Test fun `tapping a recipe's notification opens its review, and Back goes to the Library`() {
        val repository = repository()
        show(repository)
        request = ReviewRequest("recipes", summer.id, 1)
        compose.onNodeWithText("Review recipe").assertIsDisplayed()
        back()
        compose.onNodeWithText("Drafts from Claude").assertIsDisplayed()
        assertEquals(listOf(summer.id), repository.state.value.libraryDrafts.map { it.id })
    }

    @Test fun `tapping tasting notes' notification opens their review, and Back goes to the brew`() {
        val repository = repository()
        show(repository)
        request = ReviewRequest("journals", older.id, 1)
        compose.onNodeWithText("Review tasting notes").assertIsDisplayed()
        compose.onNodeWithText("Peachy").assertExists()
        back()
        compose.onNodeWithText("Review tasting notes from Claude").assertIsDisplayed()
        compose.onNodeWithText("Repeat").assertExists()
    }

    @Test fun `a draft resolved before the tap opens where it was`() {
        val repository = repository()
        show(repository)
        repository.discardLibraryDraft(summer.id).getOrThrow()
        repository.discardJournalDraft(older.id).getOrThrow()
        request = ReviewRequest("recipes", summer.id, 1)
        compose.onNode(hasText("Library") and isSelectable()).assertIsSelected()
        compose.onNodeWithText("Review recipe").assertDoesNotExist()
        request = ReviewRequest("journals", older.id, 2)
        compose.onNodeWithText("Review tasting notes").assertDoesNotExist()
        compose.onNodeWithText("Repeat").assertExists()
    }

    @Test fun `the first draft card offers notifications once, and Not now hides the offer for good`() {
        val repository = repository()
        show(repository)
        compose.onNodeWithText("Get a notification when Claude drafts something?").assertIsDisplayed()
        // Only one card carries it.
        compose.onAllNodesWithText("Turn on").assertCountEquals(1)
        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithText("Get a notification when Claude drafts something?").assertDoesNotExist()
        assertTrue(repository.state.value.draftNotificationOfferDismissed)
        assertFalse(repository.state.value.draftNotifications)
        // The card still opens the drafts.
        compose.onNodeWithText("Review 1 draft from Claude").performClick()
        compose.onNodeWithText("Drafts from Claude").assertIsDisplayed()
    }

    @Test fun `Turn on turns the switch on`() {
        val repository = repository()
        show(repository)
        compose.onNodeWithText("Turn on").performClick()
        compose.onNodeWithText("Get a notification when Claude drafts something?").assertDoesNotExist()
        assertTrue(repository.state.value.draftNotifications)
        assertTrue(repository.state.value.draftNotificationOfferDismissed)
    }

    @Test fun `signed out, nothing is offered`() {
        val repository = repository(signedIn = false)
        show(repository)
        compose.onNodeWithText("Review 1 draft from Claude").assertIsDisplayed()
        compose.onNodeWithText("Get a notification when Claude drafts something?").assertDoesNotExist()
    }

    @Test fun `the Account switch shows only while signed in, with Android's block beneath it`() {
        var on by mutableStateOf(false)
        var cloud by mutableStateOf(CloudState(available = true))
        compose.setContent {
            BrewprintTheme(Appearance.LIGHT) { Column { AccountPanel(cloud, busy = false, onSignIn = {}, onSignOut = {}, draftNotifications = on, onDraftNotifications = { on = it }, blocked = true) } }
        }
        compose.onNodeWithText("Drafts from Claude").assertDoesNotExist()
        cloud = CloudState(available = true, email = "brewer@example.com")
        compose.onNodeWithText("Notifies you when Claude drafts something to review").assertIsDisplayed()
        compose.onNodeWithText("Blocked by Android").assertDoesNotExist()
        compose.onNodeWithText("Drafts from Claude").performClick()
        assertTrue(on)
        compose.onNodeWithText("Blocked by Android").assertIsDisplayed()
        compose.onNodeWithText("Notifications for Brewprint are turned off. Drafts still wait in the app.").assertIsDisplayed()
    }
}

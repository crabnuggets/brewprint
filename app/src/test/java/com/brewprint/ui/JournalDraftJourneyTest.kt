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
class JournalDraftJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()

    private val kii = Beans(name = "Kii", roaster = "Tim Wendelboe", expectedNotes = "Blackcurrant, hibiscus")
    private val gesha = Beans(name = "Gesha Village", roaster = "Sample Roaster")
    private val grinder = Grinder(name = "Comandante", lastSetting = 24)
    private val plan = Recipe(name = "Three pours", dose = 15.0, pulses = listOf(Pulse(water = 240.0, pourSeconds = 40)))
    private val claude = Journal(impression = "Bright but a little thin", aroma = "Like blackcurrant jam", flavorTags = listOf("Fruity"), body = "Light")

    private lateinit var older: BrewRecord
    private lateinit var recent: BrewRecord

    private fun repository(): BrewRepository {
        val data = temporary.newFolder("data")
        val repository = BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = data
        })
        repository.upsertGrinder(grinder).getOrThrow()
        repository.upsertRecipe(plan).getOrThrow()
        fun brew(beans: Beans, startedAt: Long, journal: Journal): BrewRecord {
            repository.upsertBeans(beans).getOrThrow()
            val draft = repository.startDraft().getOrThrow().copy(beans = beans, grinder = grinder, setting = 24, recipe = plan, startedAt = startedAt, journal = journal)
            repository.updateDraft(draft).getOrThrow()
            return repository.saveBrew(draft.id).getOrThrow()
        }
        val now = System.currentTimeMillis()
        older = brew(gesha, now - 86_400_000L, Journal(impression = "Floral"))
        recent = brew(kii, now, Journal())
        // Everything so far is in the account, so only what the test does waits to upload.
        repository.state.value.let { repository.markPushed(it.sync.pending.associateWith(it::syncData)).getOrThrow() }
        repository.applyDrafts(emptyList(), listOf(JournalDraft(recent.id, claude), JournalDraft(older.id, Journal(impression = "Floral", body = "Full")))).getOrThrow()
        return repository
    }

    private fun show(repository: BrewRepository) = compose.setContent {
        val state by repository.state.collectAsState()
        BrewprintTheme(Appearance.LIGHT) { BrewprintApp(state, repository) }
    }

    private fun back() {
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    @Test fun `Claude's tasting notes are reviewed, edited and saved on the brew`() {
        val repository = repository()
        repository.saveNoteEdit(older.id, Journal(impression = "Half-typed")).getOrThrow()
        show(repository)
        // Unsaved edits on a brew with Claude's notes waiting don't get a second way in.
        compose.onNodeWithText("Resume tasting notes · Gesha Village").assertDoesNotExist()
        // The most recent brew's card offers the review; an older brew gets a card of its own.
        compose.onNode(hasText("Review tasting notes from Claude") and hasText("Gesha Village", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("Review tasting notes from Claude") and hasClickAction() and !hasText("Gesha Village", substring = true)).performScrollTo().performClick()

        compose.onNodeWithText("Review tasting notes").assertIsDisplayed()
        compose.onNodeWithText("Claude filled these in from your conversation. Check them, change anything, then save.").assertIsDisplayed()
        compose.onNodeWithText("Bright but a little thin").assertExists()
        // Guided fields Claude filled in show without opening "Explore the taste".
        compose.onNodeWithText("Like blackcurrant jam").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Reminder") and hasSetTextAction()).performScrollTo().performTextInput("Two ticks finer")
        compose.onNodeWithText("Save notes").performClick()

        compose.onNodeWithText("Most recent brew").assertIsDisplayed()
        compose.onNodeWithText("Edit tasting notes").assertIsDisplayed()
        val state = repository.state.value
        assertEquals(claude.copy(nextTime = "Two ticks finer"), state.brews.first { it.id == recent.id }.journal)
        assertEquals(listOf(older.id), state.journalDrafts.map { it.brewId })
        assertTrue("drafts/${recent.id}" in state.sync.pending)
    }

    @Test fun `Claude's tasting notes can be left for later or discarded from the record`() {
        val repository = repository()
        show(repository)
        compose.onNode(hasText("History") and isSelectable()).performClick()
        compose.onNodeWithText("Gesha Village").performClick()
        compose.onNodeWithText("Review tasting notes from Claude").performClick()
        compose.onNodeWithText("Review tasting notes").assertIsDisplayed()
        // Back without changes leaves the draft waiting.
        back()
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        compose.onNodeWithText("Brew record").assertIsDisplayed()

        compose.onNodeWithText("Review tasting notes from Claude").performClick()
        // Back after a change asks first, and still leaves the draft waiting.
        compose.onNode(hasText("Reminder") and hasSetTextAction()).performScrollTo().performTextInput("Coarser")
        back()
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("Review tasting notes from Claude").performClick()
        compose.onNodeWithText("Coarser").assertDoesNotExist()

        compose.onNodeWithContentDescription("Discard draft").performClick()
        compose.onNodeWithText("Discard Claude's notes?").assertIsDisplayed()
        compose.onNodeWithText("Keep draft").performClick()
        compose.onNodeWithContentDescription("Discard draft").performClick()
        compose.onNodeWithText("Discard").performClick()

        compose.onNodeWithText("Brew record").assertIsDisplayed()
        compose.onNodeWithText("Edit tasting notes").assertIsDisplayed()
        val state = repository.state.value
        assertEquals(Journal(impression = "Floral"), state.brews.first { it.id == older.id }.journal)
        assertEquals(listOf(recent.id), state.journalDrafts.map { it.brewId })
        assertTrue("drafts/${older.id}" in state.sync.pending && "brews/${older.id}" !in state.sync.pending)
    }

    @Test fun `a correction from Claude replaces an untouched review, and a draft resolved elsewhere closes it`() {
        val repository = repository()
        show(repository)
        compose.onNode(hasText("Review tasting notes from Claude") and hasClickAction() and !hasText("Gesha Village", substring = true)).performScrollTo().performClick()
        compose.onNodeWithText("Bright but a little thin").assertExists()

        val corrected = claude.copy(impression = "Bright and juicy")
        compose.runOnIdle { repository.applyDrafts(emptyList(), listOf(JournalDraft(recent.id, corrected))).getOrThrow() }
        compose.onNodeWithText("Bright and juicy").assertExists()
        compose.onNodeWithText("Bright but a little thin").assertDoesNotExist()

        // Saved or discarded on another phone: the account no longer has it.
        compose.runOnIdle { repository.applyDrafts(emptyList(), emptyList()).getOrThrow() }
        compose.onNodeWithText("Most recent brew").assertIsDisplayed()
        compose.onNodeWithText("Review tasting notes").assertDoesNotExist()
        assertEquals(Journal(), repository.state.value.brews.first { it.id == recent.id }.journal)
    }
}

package com.brewprint.data

import android.content.Context
import android.content.ContextWrapper
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
@Config(sdk = [28])
class BrewRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var repository: BrewRepository
    private val beans = Beans(name = "Ethiopia", roaster = "Sample", expectedNotes = "Blueberry")
    private val grinder = Grinder(name = "Hand grinder", lastSetting = 24)
    private val recipe = Recipe(name = "Filter", dose = 15.0, ratio = 16.0, pulses = listOf(Pulse(water = 240.0, pourSeconds = 30)))

    @Before fun setUp() {
        val directory = temporary.newFolder("data")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = directory
        }
        repository = BrewRepository(context)
        repository.upsertBeans(beans).getOrThrow()
        repository.upsertGrinder(grinder).getOrThrow()
        repository.upsertRecipe(recipe).getOrThrow()
    }

    private fun preparedDraft(journal: Journal = Journal()): BrewDraft {
        val draft = repository.startDraft().getOrThrow().copy(
            beans = beans, recipe = recipe, grinder = grinder, setting = 24,
            stage = DraftStage.REVIEW, journal = journal,
        )
        repository.updateDraft(draft).getOrThrow()
        return draft
    }

    private fun savedBrew(journal: Journal = Journal()): BrewRecord =
        repository.saveBrew(preparedDraft(journal).id).getOrThrow()

    @Test fun `first grinder becomes default and default selection uses its own setting`() {
        assertEquals(grinder.id, repository.state.value.defaultGrinderId)
        val second = Grinder(name = "Other grinder", lastSetting = 0)
        repository.upsertGrinder(second).getOrThrow()
        repository.setDefaultGrinder(second.id).getOrThrow()
        val draft = repository.startDraft().getOrThrow()
        assertEquals(second.id, draft.grinder?.id)
        assertEquals(0, draft.setting)
    }

    @Test fun `draft and notes survive restart and repeated save produces one record`() {
        val draft = preparedDraft(Journal(impression = "Cocoa", body = "Light"))
        repository = BrewRepository(context)
        assertEquals(draft, repository.state.value.draft)
        val record = repository.saveBrew(draft.id).getOrThrow()
        assertEquals(record, repository.saveBrew(draft.id).getOrThrow())
        assertEquals(listOf(record), BrewRepository(context).state.value.brews)
        assertNull(repository.state.value.draft)
        val next = repository.startDraft().getOrThrow()
        assertEquals(beans, next.beans)
        assertEquals(recipe, next.recipe)
        assertEquals(grinder.id, next.grinder?.id)
        assertEquals(24, next.setting)
        assertTrue(next.journal.isEmpty)
    }

    @Test fun `library changes preserve snapshots and repeat uses historical setting with empty journal`() {
        val record = savedBrew(Journal(impression = "Sweet", mouthfeelTags = listOf("silky")))
        repository.upsertBeans(beans.copy(name = "Renamed beans", expectedNotes = "Cocoa")).getOrThrow()
        repository.upsertRecipe(recipe.copy(name = "Changed recipe", notes = "Swirl")).getOrThrow()
        repository.upsertGrinder(grinder.copy(name = "Renamed grinder", lastSetting = 29)).getOrThrow()
        assertEquals(record, repository.state.value.brews.single())
        val repeated = repository.repeatBrew(record.id).getOrThrow()
        assertEquals(record.beans, repeated.beans)
        assertEquals(record.recipe, repeated.recipe)
        assertEquals(24, repeated.setting)
        assertEquals(29, repeated.grinder?.lastSetting)
        assertTrue(repeated.journal.isEmpty)
        assertEquals(record.journal, repository.state.value.brews.single().journal)
    }

    @Test fun `archived beans require explicit consent and archived grinder must be replaced`() {
        val record = savedBrew()
        repository.archiveBeans(beans.id).getOrThrow()
        val repeated = repository.repeatBrew(record.id).getOrThrow()
        assertTrue(repository.saveBrew(repeated.id).isFailure)
        repository.updateDraft(repeated.copy(allowArchivedBeans = true)).getOrThrow()
        repository.saveBrew(repeated.id).getOrThrow()
        repository.archiveGrinder(grinder.id).getOrThrow()
        val withoutGrinder = repository.repeatBrew(record.id).getOrThrow()
        assertNull(withoutGrinder.grinder)
        assertNull(withoutGrinder.setting)
        assertTrue(repository.saveBrew(withoutGrinder.id).isFailure)
    }

    @Test fun `note edits survive restart and discard preserves saved journal`() {
        val record = savedBrew(Journal(impression = "Original"))
        val edited = Journal(impression = "As it cools", body = "Light", mouthfeelTags = listOf("silky"))
        repository.saveNoteEdit(record.id, edited).getOrThrow()
        repository = BrewRepository(context)
        assertEquals(edited, repository.state.value.noteEdits.single().journal)
        assertEquals(record.journal, repository.state.value.brews.single().journal)
        repository.discardNoteEdit(record.id).getOrThrow()
        assertTrue(repository.state.value.noteEdits.isEmpty())
        assertEquals(record.journal, repository.state.value.brews.single().journal)
        repository.saveNoteEdit(record.id, edited).getOrThrow()
        repository.saveNotes(record.id).getOrThrow()
        assertEquals(edited, BrewRepository(context).state.value.brews.single().journal)
        assertTrue(repository.state.value.noteEdits.isEmpty())
    }

    @Test fun `explicit record correction does not update library or remembered grinder setting`() {
        val record = savedBrew()
        val libraryBefore = repository.state.value.grinders
        repository.updateBrew(record.copy(setting = 31, beans = beans.copy(name = "Corrected record"))).getOrThrow()
        assertEquals(31, repository.state.value.brews.single().setting)
        assertEquals(libraryBefore, repository.state.value.grinders)
        assertEquals(beans, repository.state.value.beans.single())
    }

    @Test fun `archiving default requires active replacement and last archive clears default`() {
        val other = Grinder(name = "Other grinder", lastSetting = 8)
        repository.upsertGrinder(other).getOrThrow()
        assertTrue(repository.archiveGrinder(grinder.id).isFailure)
        assertFalse(repository.state.value.grinders.first { it.id == grinder.id }.archived)
        repository.archiveGrinder(grinder.id, other.id).getOrThrow()
        assertEquals(other.id, repository.state.value.defaultGrinderId)
        repository.archiveGrinder(other.id).getOrThrow()
        assertNull(repository.state.value.defaultGrinderId)
    }

    @Test fun `discard leaves grinder setting and remembered choices untouched`() {
        val draft = preparedDraft().copy(setting = 40)
        repository.updateDraft(draft).getOrThrow()
        repository.discardDraft().getOrThrow()
        assertEquals(24, repository.state.value.grinders.single().lastSetting)
        assertNull(repository.state.value.lastBeansId)
        assertNull(repository.state.value.lastRecipeId)
    }

    @Test fun `failed save preserves draft notes remembered choices and grinder setting`() {
        val draft = preparedDraft(Journal(impression = "Keep my notes")).copy(setting = 27)
        repository.updateDraft(draft).getOrThrow()
        val before = repository.state.value
        val directory = context.filesDir
        val held = File(directory.parentFile, "held-data")
        assertTrue(directory.renameTo(held))
        directory.writeText("Prevent writes to the data directory")
        try {
            assertTrue(repository.saveBrew(draft.id).isFailure)
            assertEquals(before, repository.state.value)
        } finally {
            assertTrue(directory.delete())
            assertTrue(held.renameTo(directory))
        }
        assertEquals(draft, BrewRepository(context).state.value.draft)
        repository.saveBrew(draft.id).getOrThrow()
        assertEquals(1, repository.state.value.brews.size)
    }

    @Test fun `corrupt saved data is protected from subsequent writes`() {
        val storage = File(context.filesDir, "brewprint.json")
        storage.writeText("broken saved data")
        val reopened = BrewRepository(context)
        assertNotNull(reopened.loadError)
        assertTrue(reopened.upsertBeans(beans).isFailure)
        assertEquals("broken saved data", storage.readText())
    }

    @Test fun `both tick endpoints survive grinder draft save record correction and restart`() {
        for (setting in listOf(0, 100)) {
            repository.upsertGrinder(grinder.copy(lastSetting = setting)).getOrThrow()
            assertEquals(setting, repository.startDraft().getOrThrow().setting)
            val draft = preparedDraft().copy(setting = setting)
            repository.updateDraft(draft).getOrThrow()
            val record = repository.saveBrew(draft.id).getOrThrow()
            assertEquals(setting, record.setting)
            assertEquals(setting, repository.state.value.grinders.single().lastSetting)
            repository.updateBrew(record.copy(setting = 100 - setting)).getOrThrow()
            assertEquals(100 - setting, repository.state.value.brews.first { it.id == record.id }.setting)
            assertEquals(setting, repository.state.value.grinders.single().lastSetting)
            repository.updateGrinderSetting(grinder.id, 100 - setting).getOrThrow()
            repository = BrewRepository(context)
            assertEquals(100 - setting, repository.state.value.grinders.single().lastSetting)
            assertEquals(100 - setting, repository.state.value.brews.first { it.id == record.id }.setting)
        }
    }

    @Test fun `invalid tick mutations fail without changing persisted state`() {
        val record = savedBrew()
        val draft = preparedDraft()
        for (setting in listOf(-1, 101, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val before = repository.state.value
            assertTrue(repository.upsertGrinder(grinder.copy(lastSetting = setting)).isFailure)
            assertTrue(repository.updateGrinderSetting(grinder.id, setting).isFailure)
            assertTrue(repository.updateDraft(draft.copy(setting = setting)).isFailure)
            assertTrue(repository.updateBrew(record.copy(setting = setting)).isFailure)
            assertEquals(before, repository.state.value)
            assertEquals(before, BrewRepository(context).state.value)
        }
    }

    @Test fun `legacy invalid draft setting cannot be saved as a brew`() {
        val draft = preparedDraft()
        val storage = File(context.filesDir, "brewprint.json")
        for (setting in listOf(-1, 101)) {
            val original = storage.readText()
            storage.writeText(original.replace("\"setting\":24", "\"setting\":$setting"))
            val reopened = BrewRepository(context)
            assertEquals(setting, reopened.state.value.draft?.setting)
            val before = reopened.state.value
            assertTrue(reopened.saveBrew(draft.id).isFailure)
            assertEquals(before, reopened.state.value)
            storage.writeText(original)
        }
    }
}

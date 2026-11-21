package com.brewprint.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SyncTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val beans = Beans(name = "Kii", roaster = "Tim Wendelboe")
    private val grinder = Grinder(name = "Comandante", lastSetting = 24)

    @Before fun clean() { File(context.filesDir, "brewprint.json").delete() }

    @Test fun `local changes and deletes are queued for upload, drafts are not`() {
        val repository = BrewRepository(context)
        repository.upsertBeans(beans).getOrThrow()
        repository.upsertGrinder(grinder).getOrThrow()
        repository.startDraft().getOrThrow()
        repository.setAppearance(Appearance.DARK).getOrThrow()
        assertEquals(setOf("beans/${beans.id}", "grinders/${grinder.id}"), repository.state.value.sync.pending)

        repository.markPushed(repository.state.value.sync.pending.associateWith { repository.state.value.syncData(it) }).getOrThrow()
        repository.archiveBeans(beans.id).getOrThrow()
        assertEquals(setOf("beans/${beans.id}"), repository.state.value.sync.pending)
    }

    @Test fun `a deleted item uploads as a tombstone`() {
        val state = AppState(beans = listOf(beans))
        val deleted = state.copy(beans = emptyList())
        assertEquals(setOf("beans/${beans.id}"), changedKeys(state, deleted))
        assertNull(deleted.syncData("beans/${beans.id}"))
    }

    @Test fun `first sign-in uploads everything already on the phone`() {
        val state = AppState(beans = listOf(beans), grinders = listOf(grinder)).syncingWith("user-1")
        assertEquals(setOf("beans/${beans.id}", "grinders/${grinder.id}"), state.sync.pending)
        // The same account again keeps its place.
        val pulled = state.copy(sync = state.sync.copy(pending = emptySet(), pulledUpTo = "2026-11-20T00:00Z"))
        assertEquals(pulled, pulled.syncingWith("user-1"))
    }

    @Test fun `another account's data replaces this phone's instead of merging`() {
        val state = AppState(beans = listOf(beans), grinders = listOf(grinder), defaultGrinderId = grinder.id, lastBeansId = beans.id, appearance = Appearance.DARK)
            .syncingWith("user-1").let { it.afterPush(it.sync.pending.associateWith(it::syncData)) }
        val switched = state.syncingWith("user-2")
        assertTrue(switched.syncKeys().isEmpty())
        assertNull(switched.defaultGrinderId)
        assertNull(switched.lastBeansId)
        assertEquals(SyncState(userId = "user-2"), switched.sync)
        assertEquals(Appearance.DARK, switched.appearance)
    }

    @Test fun `signing out removes the account's data but keeps settings`() {
        val repository = BrewRepository(context)
        repository.upsertBeans(beans).getOrThrow()
        repository.upsertGrinder(grinder).getOrThrow()
        repository.setAppearance(Appearance.DARK).getOrThrow()
        repository.syncWith("user-1").getOrThrow()
        repository.startDraft().getOrThrow()
        repository.applyDrafts(listOf(LibraryDraft("draft-1", beans = Beans(id = "draft-1", name = "Kii", roaster = "Tim Wendelboe")))).getOrThrow()
        repository.markPushed(repository.state.value.let { state -> state.sync.pending.associateWith(state::syncData) }).getOrThrow()
        repository.signedOut().getOrThrow()
        val state = repository.state.value
        assertTrue(state.beans.isEmpty() && state.grinders.isEmpty() && state.draft == null && state.libraryDrafts.isEmpty())
        assertEquals(SyncState(), state.sync)
        assertEquals(Appearance.DARK, state.appearance)
        // The next account starts from its own data alone.
        repository.syncWith("user-2").getOrThrow()
        assertTrue(repository.state.value.sync.pending.isEmpty())
    }

    @Test fun `another account can't take over while this one's changes wait`() {
        val repository = BrewRepository(context)
        repository.syncWith("user-1").getOrThrow()
        repository.upsertBeans(beans).getOrThrow()
        assertTrue(repository.syncWith("user-2").isFailure)
        assertTrue(repository.signedOut().isFailure)
        val state = repository.state.value
        assertEquals(listOf(beans), state.beans)
        assertEquals("user-1", state.sync.userId)
        assertEquals(setOf("beans/${beans.id}"), state.sync.pending)
    }

    @Test fun `an item edited while uploading stays queued`() {
        val state = AppState(beans = listOf(beans), sync = SyncState("user-1", setOf("beans/${beans.id}")))
        val pushed = mapOf("beans/${beans.id}" to state.syncData("beans/${beans.id}"))
        val editedMeanwhile = state.copy(beans = listOf(beans.copy(name = "Kii AA")))
        assertEquals(setOf("beans/${beans.id}"), editedMeanwhile.afterPush(pushed).sync.pending)
        assertTrue(state.afterPush(pushed).sync.pending.isEmpty())
    }

    @Test fun `a reinstall restores the account's items`() {
        val source = AppState(beans = listOf(beans), grinders = listOf(grinder))
        val items = source.syncKeys().map { key -> key.split('/').let { RemoteItem(it[0], it[1], source.syncData(key)) } }
        val restored = AppState().syncingWith("user-1").withRemote(items, "2026-11-20T00:00Z")
        assertEquals(listOf(beans), restored.beans)
        assertEquals(listOf(grinder), restored.grinders)
        assertEquals(grinder.id, restored.defaultGrinderId)
        assertEquals("2026-11-20T00:00Z", restored.sync.pulledUpTo)
    }

    @Test fun `server deletes apply, but unsent local edits win`() {
        val other = Beans(name = "Gesha", roaster = "Onyx")
        val record = BrewRecord(beans = beans, grinder = grinder, setting = 24, recipe = Recipe(name = "Daily"))
        val state = AppState(
            beans = listOf(beans, other),
            brews = listOf(record),
            noteEdits = listOf(NoteEdit(record.id, Journal(impression = "Bright"))),
            sync = SyncState("user-1", pending = setOf("beans/${beans.id}")),
        )
        val merged = state.withRemote(listOf(
            RemoteItem("beans", beans.id, null),
            RemoteItem("beans", other.id, null),
            RemoteItem("brews", record.id, null),
        ), null)
        assertEquals(listOf(beans), merged.beans)
        assertTrue(merged.brews.isEmpty())
        assertTrue(merged.noteEdits.isEmpty())
    }

    @Test fun `adding or discarding a library draft deletes it from the account, once`() {
        val repository = BrewRepository(context)
        val kii = Beans(name = "Kii", roaster = "Tim Wendelboe")
        val plan = Recipe(name = "Three pours", dose = 15.0, ratio = 16.0, pulses = listOf(Pulse(water = 240.0, pourSeconds = 40)))
        val drafts = listOf(LibraryDraft(kii.id, beans = kii), LibraryDraft(plan.id, recipe = plan))
        repository.applyDrafts(drafts).getOrThrow()
        assertEquals(drafts, repository.state.value.libraryDrafts)
        assertTrue(repository.state.value.sync.pending.isEmpty())

        // Adding saves the user's edited copy as beans with the draft's id; discarding saves nothing.
        repository.upsertBeans(kii.copy(processing = "Washed")).getOrThrow()
        repository.discardLibraryDraft(plan.id).getOrThrow()
        val state = repository.state.value
        assertEquals(listOf(kii.copy(processing = "Washed")), state.beans)
        assertTrue(state.recipes.isEmpty() && state.libraryDrafts.isEmpty())
        assertEquals(setOf("beans/${kii.id}", "drafts/${kii.id}", "drafts/${plan.id}"), state.sync.pending)
        assertNull(state.syncData("drafts/${kii.id}"))

        // A fetch made before the deletions reach the account doesn't bring the drafts back.
        repository.applyDrafts(drafts).getOrThrow()
        assertTrue(repository.state.value.libraryDrafts.isEmpty())
        repository.markPushed(state.sync.pending.associateWith(state::syncData)).getOrThrow()
        assertTrue(repository.state.value.sync.pending.isEmpty())
        repository.applyDrafts(listOf(drafts[0])).getOrThrow()
        assertTrue(repository.state.value.libraryDrafts.isEmpty())
    }

    @Test fun `editing beans that aren't a draft deletes no draft`() {
        val repository = BrewRepository(context)
        repository.upsertBeans(beans).getOrThrow()
        assertEquals(setOf("beans/${beans.id}"), repository.state.value.sync.pending)
    }

    @Test fun `a draft takes its id from the account and unreadable drafts are left out`() {
        val data = syncJson.parseToJsonElement("""{"id":"other","name":"Kii","roaster":"Tim Wendelboe"}""")
        assertEquals(LibraryDraft("draft-1", beans = Beans(id = "draft-1", name = "Kii", roaster = "Tim Wendelboe")), libraryDraft("beans", "draft-1", data))
        assertNull(libraryDraft("grinders", "draft-2", data))
        assertNull(libraryDraft("recipes", "draft-3", syncJson.parseToJsonElement("""{"pulses":"none"}""")))
    }
}

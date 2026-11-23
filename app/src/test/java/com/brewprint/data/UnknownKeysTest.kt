package com.brewprint.data

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** A newer version of the app adds fields; this version keeps them when it edits and uploads the item. */
@RunWith(RobolectricTestRunner::class)
class UnknownKeysTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val beans = Beans(name = "Kii", roaster = "Tim Wendelboe")
    // A field this version doesn't have, standing in for whatever a later version adds.
    private val batches = JsonArray(listOf(JsonObject(mapOf("score" to JsonPrimitive(4), "by" to JsonPrimitive("Alvin")))))

    /** The beans as a newer version uploads them: this version's fields, a new top-level field, and one in a nested object. */
    private fun newer(name: String = beans.name) = JsonObject(syncJson.encodeToJsonElement(Beans.serializer(), beans.copy(name = name)).jsonObject + ("ratings" to batches))

    @Before fun clean() { File(context.filesDir, "brewprint.json").delete() }

    @Test fun `an edit made here keeps fields a newer version added`() {
        val repository = BrewRepository(context)
        repository.applyRemote(listOf(RemoteItem("beans", beans.id, newer())), "2026-11-23T00:00Z").getOrThrow()
        assertEquals(beans, repository.state.value.beans.single())
        repository.upsertBeans(beans.copy(name = "Kii AA")).getOrThrow()
        val upload = repository.state.value.syncData("beans/${beans.id}")!!.jsonObject
        assertEquals(JsonPrimitive("Kii AA"), upload["name"])
        assertEquals(batches, upload["ratings"])
        // Uploading it settles the change, as before.
        repository.markPushed(mapOf("beans/${beans.id}" to upload)).getOrThrow()
        assertEquals(emptySet<String>(), repository.state.value.sync.pending)
    }

    @Test fun `an item without new fields keeps nothing extra, and a deleted one is forgotten`() {
        val repository = BrewRepository(context)
        repository.applyRemote(listOf(RemoteItem("beans", beans.id, syncJson.encodeToJsonElement(Beans.serializer(), beans))), null).getOrThrow()
        assertEquals(emptyMap<String, JsonObject>(), repository.state.value.sync.raw)
        repository.applyRemote(listOf(RemoteItem("beans", beans.id, newer())), null).getOrThrow()
        assertEquals(setOf("beans/${beans.id}"), repository.state.value.sync.raw.keys)
        repository.applyRemote(listOf(RemoteItem("beans", beans.id, null)), null).getOrThrow()
        assertEquals(emptyMap<String, JsonObject>(), repository.state.value.sync.raw)
    }

    @Test fun `deleting beans forgets their new fields, and Undo brings them back`() {
        val repository = BrewRepository(context)
        repository.applyRemote(listOf(RemoteItem("beans", beans.id, newer())), null).getOrThrow()
        val raw = repository.state.value.sync.raw["beans/${beans.id}"]
        repository.deleteBeans(beans.id).getOrThrow()
        assertNull(repository.state.value.sync.raw["beans/${beans.id}"])
        repository.restoreBeans(beans, remembered = false, account = null, raw = raw).getOrThrow()
        assertEquals(batches, repository.state.value.syncData("beans/${beans.id}")!!.jsonObject["ratings"])
    }

    @Test fun `a draft's new fields reach the item it becomes once added`() {
        val repository = BrewRepository(context)
        repository.applyDrafts(listOfNotNull(libraryDraft("beans", beans.id, newer()))).getOrThrow()
        repository.upsertBeans(beans).getOrThrow()
        assertEquals(batches, repository.state.value.syncData("beans/${beans.id}")!!.jsonObject["ratings"])
    }
}

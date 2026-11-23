package com.brewprint.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What this device knows about syncing with a signed-in account. Beans, grinders, recipes and
 * brew records sync, one item at a time; the brew draft, unsaved note edits and settings stay on the device.
 * Library drafts come from the account, and the account forgets each one once it is added or discarded here.
 */
@Serializable
data class SyncState(
    /** The account this device's data was last synced with; null before the first sign-in. */
    val userId: String? = null,
    /** Items changed or deleted on this device and not yet uploaded, as "kind/id"; "drafts/id" is a library draft to delete. */
    val pending: Set<String> = emptySet(),
    /** The server time of the newest change already merged; null to fetch everything. */
    val pulledUpTo: String? = null,
    /**
     * An item's JSON as the account last sent it, by "kind/id", kept only when it holds fields this version doesn't
     * know. Uploads lay this version's fields over it, so a newer version's fields survive an edit made here.
     */
    val raw: Map<String, JsonObject> = emptyMap(),
)

/** One synced item as the server holds it. A null [data] means the item was deleted. */
data class RemoteItem(val kind: String, val id: String, val data: JsonElement?)

internal val syncJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private class SyncKind<T>(val name: String, val serializer: KSerializer<T>, val list: (AppState) -> List<T>, val id: (T) -> String) {
    fun ids(state: AppState): List<String> = list(state).map(id)
}

private val kinds = listOf(
    SyncKind("beans", Beans.serializer(), { it.beans }, { it.id }),
    SyncKind("grinders", Grinder.serializer(), { it.grinders }, { it.id }),
    SyncKind("recipes", Recipe.serializer(), { it.recipes }, { it.id }),
    SyncKind("brews", BrewRecord.serializer(), { it.brews }, { it.id }),
)

private fun key(kind: String, id: String) = "$kind/$id"

/** The pending key that deletes a library draft from the account. */
const val DRAFTS = "drafts"

/**
 * Takes the library draft with [id] out of the Library, if it is there, and queues its deletion from the account.
 * Adding a draft saves it as beans or a recipe with the same id; discarding it saves nothing else.
 */
fun AppState.resolvingDraft(id: String): AppState {
    val draft = libraryDrafts.find { it.id == id } ?: return this
    // A draft that was added becomes beans or a recipe with its id, keeping any fields this version can't show.
    val added = draft.raw?.let { raw -> listOfNotNull(key("beans", id).takeIf { draft.beans != null && beans.any { it.id == id } }, key("recipes", id).takeIf { draft.recipe != null && recipes.any { it.id == id } }).firstOrNull()?.let { it to raw } }
    return copy(libraryDrafts = libraryDrafts.filterNot { it.id == id }, sync = sync.copy(pending = sync.pending + key(DRAFTS, id), raw = sync.raw + listOfNotNull(added)))
}

/** A draft as the account holds it, or null when this version can't read it. */
fun libraryDraft(kind: String, id: String, data: JsonElement): LibraryDraft? = runCatching {
    when (kind) {
        "beans" -> syncJson.decodeFromJsonElement(Beans.serializer(), data).copy(id = id).let { LibraryDraft(id, beans = it, raw = unknownKeysOf(data, syncJson.encodeToJsonElement(Beans.serializer(), it))) }
        "recipes" -> syncJson.decodeFromJsonElement(Recipe.serializer(), data).copy(id = id).let { LibraryDraft(id, recipe = it, raw = unknownKeysOf(data, syncJson.encodeToJsonElement(Recipe.serializer(), it))) }
        else -> null
    }
}.getOrNull()

/** [remote] when it has a field, at any depth of nested objects, that [known] lacks; null otherwise. */
private fun unknownKeysOf(remote: JsonElement, known: JsonElement): JsonObject? = (remote as? JsonObject)?.takeIf { hasUnknownKeys(it, known) }

private fun hasUnknownKeys(remote: JsonObject, known: JsonElement): Boolean {
    if (known !is JsonObject) return false
    return remote.any { (name, value) -> name !in known || (value is JsonObject && hasUnknownKeys(value, known.getValue(name))) }
}

/** This version's [known] fields laid over [raw]: nested objects merge, anything else, arrays included, is replaced. */
private fun overlay(raw: JsonObject, known: JsonObject): JsonObject = JsonObject(raw + known.mapValues { (name, value) ->
    val under = raw[name]
    if (under is JsonObject && value is JsonObject) overlay(under, value) else value
})

/** Replaces the Library's drafts with the account's, leaving out ones this phone already added or discarded. */
fun AppState.withDrafts(drafts: List<LibraryDraft>): AppState = copy(libraryDrafts = drafts.filter { draft ->
    key(DRAFTS, draft.id) !in sync.pending && beans.none { it.id == draft.id } && recipes.none { it.id == draft.id }
})

/** The synced items that differ between [old] and [new], including ones added or removed. */
fun changedKeys(old: AppState, new: AppState): Set<String> = buildSet {
    kinds.forEach { kind -> addAll(changedIds(kind, old, new).map { key(kind.name, it) }) }
}

private fun <T> changedIds(kind: SyncKind<T>, old: AppState, new: AppState): Set<String> {
    val before = kind.list(old)
    val after = kind.list(new)
    if (before === after) return emptySet()
    val beforeById = before.associateBy(kind.id)
    val afterById = after.associateBy(kind.id)
    return (beforeById.keys + afterById.keys).filterTo(mutableSetOf()) { beforeById[it] != afterById[it] }
}

/** Every synced item on this device. */
fun AppState.syncKeys(): Set<String> = buildSet {
    kinds.forEach { kind -> kind.ids(this@syncKeys).forEach { add(key(kind.name, it)) } }
}

/** The item's current content for upload, or null when it no longer exists here. */
fun AppState.syncData(key: String): JsonElement? {
    val (name, id) = key.split('/', limit = 2)
    val known = kinds.firstOrNull { it.name == name }?.encode(this, id) ?: return null
    val raw = sync.raw[key]
    return if (raw != null && known is JsonObject) overlay(raw, known) else known
}

private fun <T> SyncKind<T>.encode(state: AppState, id: String): JsonElement? =
    list(state).firstOrNull { this.id(it) == id }?.let { syncJson.encodeToJsonElement(serializer, it) }

/**
 * Starts syncing with [userId]. Data made while signed out joins the account.
 * Another account's data is never merged in: it is replaced by this account's, and only once all of it was uploaded.
 */
fun AppState.syncingWith(userId: String): AppState = when (sync.userId) {
    userId -> this
    null -> copy(sync = SyncState(userId = userId, pending = sync.pending + syncKeys()))
    else -> {
        check(sync.pending.isEmpty()) { "This phone has changes that haven't reached the Google account you used before. Sign in with that account first." }
        withoutAccountData().copy(sync = SyncState(userId = userId))
    }
}

/** Removes the account's beans, grinders, recipes, brews and drafts from this phone. Settings and the paired scale stay. */
fun AppState.withoutAccountData(): AppState = copy(
    beans = emptyList(),
    grinders = emptyList(),
    recipes = emptyList(),
    brews = emptyList(),
    draft = null,
    noteEdits = emptyList(),
    libraryDrafts = emptyList(),
    defaultGrinderId = null,
    lastBeansId = null,
    lastRecipeId = null,
    sync = SyncState(),
)

/** Forgets uploads that went through, unless the item changed again while uploading. */
fun AppState.afterPush(pushed: Map<String, JsonElement?>): AppState =
    copy(sync = sync.copy(pending = sync.pending.filterTo(mutableSetOf()) { it !in pushed || syncData(it) != pushed[it] }))

/** Merges items from the server. A local change not yet uploaded wins over the server's copy. */
fun AppState.withRemote(items: List<RemoteItem>, pulledUpTo: String?): AppState {
    var next = this
    val merging = items.filter { item -> kinds.any { it.name == item.kind } && key(item.kind, item.id) !in sync.pending }
    kinds.forEach { kind -> next = kind.merge(next, merging.filter { it.kind == kind.name }) }
    // Keep what the account sent where it holds fields this version doesn't know; forget it once deleted or caught up.
    var raw = next.sync.raw
    merging.forEach { item ->
        val itemKey = key(item.kind, item.id)
        val known = kinds.first { it.name == item.kind }.encode(next, item.id)
        val unknown = if (item.data != null && known != null) unknownKeysOf(item.data, known) else null
        raw = if (unknown != null) raw + (itemKey to unknown) else raw - itemKey
    }
    val activeGrinders = next.grinders.filter { !it.archived }
    return next.copy(
        brews = next.brews.sortedByDescending { it.startedAt },
        noteEdits = next.noteEdits.filter { edit -> next.brews.any { it.id == edit.brewId } },
        defaultGrinderId = next.defaultGrinderId?.takeIf { id -> activeGrinders.any { it.id == id } } ?: activeGrinders.firstOrNull()?.id,
        lastBeansId = next.lastBeansId?.takeIf { id -> next.beans.any { it.id == id } },
        sync = next.sync.copy(pulledUpTo = pulledUpTo ?: sync.pulledUpTo, raw = raw),
    )
}

private fun <T> SyncKind<T>.merge(state: AppState, items: List<RemoteItem>): AppState {
    if (items.isEmpty()) return state
    val byId = list(state).associateByTo(LinkedHashMap(), id)
    items.forEach { item ->
        if (item.data == null) byId.remove(item.id)
        // An item this version can't read is left as it is rather than lost.
        else runCatching { syncJson.decodeFromJsonElement(serializer, item.data) }.onSuccess { byId[item.id] = it }
    }
    val merged = byId.values.toList()
    @Suppress("UNCHECKED_CAST")
    return when (name) {
        "beans" -> state.copy(beans = merged as List<Beans>)
        "grinders" -> state.copy(grinders = merged as List<Grinder>)
        "recipes" -> state.copy(recipes = merged as List<Recipe>)
        else -> state.copy(brews = merged as List<BrewRecord>)
    }
}

package com.brewprint.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * What this device knows about syncing with a signed-in account. Beans, grinders, recipes and
 * brew records sync, one item at a time; drafts, unsaved note edits and settings stay on the device.
 */
@Serializable
data class SyncState(
    /** The account this device's data was last synced with; null before the first sign-in. */
    val userId: String? = null,
    /** Items changed or deleted on this device and not yet uploaded, as "kind/id". */
    val pending: Set<String> = emptySet(),
    /** The server time of the newest change already merged; null to fetch everything. */
    val pulledUpTo: String? = null,
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
    return kinds.firstOrNull { it.name == name }?.encode(this, id)
}

private fun <T> SyncKind<T>.encode(state: AppState, id: String): JsonElement? =
    list(state).firstOrNull { this.id(it) == id }?.let { syncJson.encodeToJsonElement(serializer, it) }

/**
 * Starts syncing with [userId]. Data made before the phone's first sign-in joins the account.
 * Another account's data is never merged in: it is replaced by this account's.
 */
fun AppState.syncingWith(userId: String): AppState = when (sync.userId) {
    userId -> this
    null -> copy(sync = SyncState(userId = userId, pending = sync.pending + syncKeys()))
    else -> withoutAccountData().copy(sync = SyncState(userId = userId))
}

/** Removes the account's beans, grinders, recipes and brews from this phone. Settings and the paired scale stay. */
fun AppState.withoutAccountData(): AppState = copy(
    beans = emptyList(),
    grinders = emptyList(),
    recipes = emptyList(),
    brews = emptyList(),
    draft = null,
    noteEdits = emptyList(),
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
    kinds.forEach { kind -> next = kind.merge(next, items.filter { it.kind == kind.name && key(kind.name, it.id) !in sync.pending }) }
    val activeGrinders = next.grinders.filter { !it.archived }
    return next.copy(
        brews = next.brews.sortedByDescending { it.startedAt },
        noteEdits = next.noteEdits.filter { edit -> next.brews.any { it.id == edit.brewId } },
        defaultGrinderId = next.defaultGrinderId?.takeIf { id -> activeGrinders.any { it.id == id } } ?: activeGrinders.firstOrNull()?.id,
        sync = next.sync.copy(pulledUpTo = pulledUpTo ?: sync.pulledUpTo),
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

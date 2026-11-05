package com.brewprint.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BrewRepository(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "brewprint.json"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutableState: MutableStateFlow<AppState>
    val state: StateFlow<AppState>
    val loadError: String?

    init {
        val loaded = runCatching {
            try {
                file.openRead().bufferedReader().use { json.decodeFromString<AppState>(it.readText()) }
            } catch (error: FileNotFoundException) {
                if (file.baseFile.exists()) throw error
                AppState()
            }
        }
        loadError = loaded.exceptionOrNull()?.let { "Couldn't read saved data. Your data has been kept on this device." }
        mutableState = MutableStateFlow(loaded.getOrDefault(AppState()))
        state = mutableState.asStateFlow()
    }

    @Synchronized
    private fun <T> change(transform: (AppState) -> Pair<AppState, T>): Result<T> = runCatching {
        check(loadError == null) { loadError.orEmpty() }
        val (next, result) = transform(mutableState.value)
        val bytes = json.encodeToString(next).toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        mutableState.value = next
        result
    }

    fun upsertBeans(beans: Beans): Result<Unit> = change { current ->
        require(beans.name.isNotBlank()) { "Enter a name." }
        require(beans.roaster.isNotBlank()) { "Enter a roaster." }
        current.copy(beans = current.beans.filterNot { it.id == beans.id } + beans) to Unit
    }

    fun upsertGrinder(grinder: Grinder): Result<Unit> = change { current ->
        require(grinder.name.isNotBlank()) { "Enter a name." }
        require(grinder.lastSetting == null || grinder.lastSetting in MIN_GRIND_SETTING..MAX_GRIND_SETTING) { "Enter whole ticks from 0 to 100." }
        val grinders = current.grinders.filterNot { it.id == grinder.id } + grinder
        val default = current.defaultGrinderId ?: grinders.firstOrNull { !it.archived }?.id
        current.copy(grinders = grinders, defaultGrinderId = default) to Unit
    }

    fun upsertRecipe(recipe: Recipe): Result<Unit> = change { current ->
        require(recipe.validationErrors().isEmpty()) { recipe.validationErrors().first() }
        current.copy(recipes = current.recipes.filterNot { it.id == recipe.id } + recipe) to Unit
    }

    fun archiveBeans(id: String): Result<Unit> = change { current ->
        require(current.beans.any { it.id == id }) { "Beans not found." }
        current.copy(beans = current.beans.map { if (it.id == id) it.copy(archived = true) else it }) to Unit
    }

    fun archiveRecipe(id: String): Result<Unit> = change { current ->
        require(current.recipes.any { it.id == id }) { "Recipe not found." }
        current.copy(recipes = current.recipes.map { if (it.id == id) it.copy(archived = true) else it }) to Unit
    }

    fun archiveGrinder(id: String, replacementDefaultId: String? = null): Result<Unit> = change { current ->
        require(current.grinders.any { it.id == id }) { "Grinder not found." }
        val remaining = current.grinders.filter { it.id != id && !it.archived }
        val default = if (current.defaultGrinderId == id) {
            if (remaining.isEmpty()) null else {
                require(remaining.any { it.id == replacementDefaultId }) { "Choose another default grinder." }
                replacementDefaultId
            }
        } else current.defaultGrinderId
        current.copy(
            grinders = current.grinders.map { if (it.id == id) it.copy(archived = true) else it },
            defaultGrinderId = default,
        ) to Unit
    }

    fun setDefaultGrinder(id: String): Result<Unit> = change { current ->
        require(current.grinders.any { it.id == id && !it.archived }) { "Choose an active grinder." }
        current.copy(defaultGrinderId = id) to Unit
    }

    fun updateGrinderSetting(id: String, setting: Int): Result<Unit> = change { current ->
        require(current.grinders.any { it.id == id && !it.archived }) { "Choose an active grinder." }
        require(setting in MIN_GRIND_SETTING..MAX_GRIND_SETTING) { "Enter whole ticks from 0 to 100." }
        current.copy(grinders = current.grinders.map { if (it.id == id) it.copy(lastSetting = setting) else it }) to Unit
    }

    fun setAppearance(appearance: Appearance): Result<Unit> = change { current ->
        current.copy(appearance = appearance) to Unit
    }

    fun startDraft(): Result<BrewDraft> = change { current ->
        val grinder = current.grinders.firstOrNull { it.id == current.defaultGrinderId && !it.archived }
            ?: current.grinders.singleOrNull { !it.archived }
        val draft = current.draft ?: BrewDraft(
            beans = current.beans.firstOrNull { it.id == current.lastBeansId && !it.archived },
            recipe = current.recipes.firstOrNull { it.id == current.lastRecipeId && !it.archived },
            grinder = grinder,
            setting = grinder?.lastSetting?.takeIf { it in MIN_GRIND_SETTING..MAX_GRIND_SETTING },
        )
        current.copy(draft = draft) to draft
    }

    fun repeatBrew(id: String): Result<BrewDraft> = change { current ->
        check(current.draft == null) { "Resume or discard the current brew first." }
        val original = current.brews.first { it.id == id }
        val grinder = current.grinders.firstOrNull { it.id == original.grinder.id && !it.archived }
            ?.let { original.grinder.copy(lastSetting = it.lastSetting, finish = it.finish) }
        val draft = BrewDraft(
            beans = original.beans,
            grinder = grinder,
            setting = if (grinder == null) null else original.setting.takeIf { it in MIN_GRIND_SETTING..MAX_GRIND_SETTING },
            recipe = original.recipe,
        )
        current.copy(draft = draft) to draft
    }

    fun updateDraft(draft: BrewDraft): Result<Unit> = change { current ->
        require(current.draft?.id == draft.id) { "This brew draft is no longer active." }
        require(draft.setting == null || draft.setting in MIN_GRIND_SETTING..MAX_GRIND_SETTING) { "Enter whole ticks from 0 to 100." }
        current.copy(draft = draft) to Unit
    }

    fun discardDraft(): Result<Unit> = change { current -> current.copy(draft = null) to Unit }

    fun saveBrew(draftId: String): Result<BrewRecord> = change { current ->
        val existing = current.brews.firstOrNull { it.id == draftId }
        if (existing != null) return@change current to existing
        val draft = current.draft?.takeIf { it.id == draftId } ?: error("This brew draft is no longer active.")
        val beans = requireNotNull(draft.beans) { "Choose beans." }
        val grinder = requireNotNull(draft.grinder) { "Choose a grinder." }
        val setting = requireNotNull(draft.setting) { "Enter a grind setting." }
        require(setting in MIN_GRIND_SETTING..MAX_GRIND_SETTING) { "Enter whole ticks from 0 to 100." }
        val recipe = requireNotNull(draft.recipe) { "Choose a recipe." }
        require(recipe.validationErrors().isEmpty()) { "Check the recipe plan." }
        require(current.grinders.any { it.id == grinder.id && !it.archived }) { "Choose an active grinder." }
        val beansArchived = current.beans.firstOrNull { it.id == beans.id }?.archived ?: beans.archived
        require(!beansArchived || draft.allowArchivedBeans) { "Confirm archived beans or choose other beans." }
        val record = BrewRecord(
            id = draft.id,
            startedAt = draft.startedAt,
            beans = beans,
            grinder = grinder,
            setting = setting,
            recipe = recipe,
            journal = draft.journal,
        )
        current.copy(
            brews = (current.brews + record).sortedByDescending { it.startedAt },
            grinders = current.grinders.map { if (it.id == grinder.id) it.copy(lastSetting = setting) else it },
            draft = null,
            lastBeansId = beans.id,
            lastRecipeId = recipe.id,
        ) to record
    }

    fun updateBrew(brew: BrewRecord): Result<Unit> = change { current ->
        require(current.brews.any { it.id == brew.id }) { "Brew not found." }
        require(brew.setting in MIN_GRIND_SETTING..MAX_GRIND_SETTING) { "Enter whole ticks from 0 to 100." }
        require(brew.beans.name.isNotBlank() && brew.beans.roaster.isNotBlank()) { "Enter beans and roaster." }
        require(brew.grinder.name.isNotBlank()) { "Enter a grinder name." }
        require(brew.recipe.validationErrors().isEmpty()) { "Check the recipe plan." }
        current.copy(brews = current.brews.map { if (it.id == brew.id) brew else it }.sortedByDescending { it.startedAt }) to Unit
    }

    fun deleteBrew(id: String): Result<Unit> = change { current ->
        current.copy(brews = current.brews.filterNot { it.id == id }, noteEdits = current.noteEdits.filterNot { it.brewId == id }) to Unit
    }

    fun saveNoteEdit(brewId: String, journal: Journal): Result<Unit> = change { current ->
        require(current.brews.any { it.id == brewId }) { "Brew not found." }
        current.copy(noteEdits = current.noteEdits.filterNot { it.brewId == brewId } + NoteEdit(brewId, journal)) to Unit
    }

    fun discardNoteEdit(brewId: String): Result<Unit> = change { current ->
        current.copy(noteEdits = current.noteEdits.filterNot { it.brewId == brewId }) to Unit
    }

    fun saveNotes(brewId: String): Result<Unit> = change { current ->
        val edit = current.noteEdits.firstOrNull { it.brewId == brewId } ?: error("No note edits to save.")
        require(current.brews.any { it.id == brewId }) { "Brew not found." }
        current.copy(
            brews = current.brews.map { if (it.id == brewId) it.copy(journal = edit.journal) else it },
            noteEdits = current.noteEdits.filterNot { it.brewId == brewId },
        ) to Unit
    }
}

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
import kotlinx.serialization.json.JsonElement

class BrewRepository(context: Context) {
    companion object {
        @Volatile private var shared: BrewRepository? = null

        /** The one repository for the app process, shared by the activity and the timer service. */
        fun get(context: Context): BrewRepository = shared ?: synchronized(this) {
            shared ?: BrewRepository(context.applicationContext).also { shared = it }
        }
    }

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

    /** Saves a change. Changed beans, grinders, recipes and brews are queued for upload unless [track] is off. */
    @Synchronized
    private fun <T> change(track: Boolean = true, transform: (AppState) -> Pair<AppState, T>): Result<T> = runCatching {
        check(loadError == null) { loadError.orEmpty() }
        val current = mutableState.value
        val (changed, result) = transform(current)
        val keys = if (track) changedKeys(current, changed) else emptySet()
        val next = if (keys.isEmpty()) changed else changed.copy(sync = changed.sync.copy(pending = changed.sync.pending + keys))
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
        // Saving a library draft's beans adds it to the Library, so the draft is done.
        current.copy(beans = current.beans.filterNot { it.id == beans.id } + beans).resolvingDraft(beans.id) to Unit
    }

    /**
     * Saves beans from the beans form. Their batches are the live ones, so a batch synced from another phone while the
     * form was open is kept. With at most one batch, [roastDate] sets that batch's date, adding the batch if needed.
     */
    fun saveBeans(beans: Beans, roastDate: String?): Result<Unit> = change { current ->
        require(beans.name.isNotBlank()) { "Enter a name." }
        require(beans.roaster.isNotBlank()) { "Enter a roaster." }
        require(roastDateValid(roastDate)) { "Choose a roast date that isn't in the future." }
        val live = current.beans.find { it.id == beans.id }
        val batches = live?.batches ?: beans.batches
        val saved = beans.copy(archived = live?.archived ?: beans.archived, batches = when {
            batches.size > 1 -> batches
            batches.size == 1 -> listOf(batches.single().copy(roastDate = roastDate))
            roastDate != null -> listOf(BeanBatch.create(beans.copy(batches = emptyList()), roastDate))
            else -> emptyList()
        })
        current.copy(beans = current.beans.filterNot { it.id == beans.id } + saved).resolvingDraft(beans.id) to Unit
    }

    /**
     * Another bag of the same beans. Beans without batches gain their first bag as "Original bag", so it stays
     * apart from the new one. Archived beans come back, since they're being bought again.
     */
    fun addBeanBatch(beansId: String, roastDate: String?): Result<BeanBatch> = change { current ->
        require(roastDateValid(roastDate)) { "Choose a roast date that isn't in the future." }
        val beans = requireNotNull(current.beans.find { it.id == beansId }) { "Beans not found." }
        val existing = beans.batches.ifEmpty { listOf(BeanBatch(notes = "Original bag", addedAt = 0L)) }
        val batch = BeanBatch.create(beans.copy(batches = existing), roastDate)
        current.copy(beans = current.beans.map { if (it.id == beansId) it.copy(batches = existing + batch, archived = false) else it }) to batch
    }

    /**
     * Removes one batch of beans that have others. Brew records keep theirs. A brew being set up moves to the newest
     * batch left; one already brewing keeps the roast date it had.
     */
    fun deleteBeanBatch(beansId: String, batchId: String): Result<Unit> = change { current ->
        val beans = requireNotNull(current.beans.find { it.id == beansId }) { "Beans not found." }
        require(beans.batches.size > 1 && beans.batches.any { it.id == batchId }) { "Beans keep at least one batch." }
        val remaining = beans.copy(batches = beans.batches.filterNot { it.id == batchId })
        val draft = current.draft?.takeIf { it.beans?.id == beansId && it.batchId == batchId && it.stage == DraftStage.SETUP }
            ?.let { it.withBeans(remaining).copy(beans = it.beans) } ?: current.draft
        current.copy(
            beans = current.beans.map { if (it.id == beansId) remaining else it },
            draft = draft,
            lastBatchId = current.lastBatchId?.takeIf { it != batchId },
        ) to Unit
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
        val saved = recipe.normalized()
        current.copy(recipes = current.recipes.filterNot { it.id == recipe.id } + saved).resolvingDraft(recipe.id) to Unit
    }

    /** Discards a library draft without adding it. */
    fun discardLibraryDraft(id: String): Result<Unit> = change { it.resolvingDraft(id) to Unit }

    fun archiveBeans(id: String): Result<Unit> = change { current ->
        require(current.beans.any { it.id == id }) { "Beans not found." }
        current.copy(beans = current.beans.map { if (it.id == id) it.copy(archived = true) else it }) to Unit
    }

    fun unarchiveBeans(id: String): Result<Unit> = change { current ->
        require(current.beans.any { it.id == id }) { "Beans not found." }
        current.copy(beans = current.beans.map { if (it.id == id) it.copy(archived = false) else it }) to Unit
    }

    /** Removes beans from the Library. Brew records keep their own copy; a brew draft using them must choose other beans. */
    fun deleteBeans(id: String): Result<Unit> = change { current ->
        require(current.beans.any { it.id == id }) { "Beans not found." }
        val batches = current.beans.find { it.id == id }?.batches.orEmpty().map { it.id }
        current.copy(beans = current.beans.filterNot { it.id == id }, lastBeansId = current.lastBeansId?.takeIf { it != id },
            lastBatchId = current.lastBatchId?.takeIf { current.lastBeansId != id && it !in batches }, sync = current.sync.copy(raw = current.sync.raw - "beans/$id")) to Unit
    }

    /**
     * Undoes [deleteBeans]: the beans return, and are remembered for the next brew again if they were and nothing has been since.
     * Nothing changes once the phone has left [account], whose beans these are.
     */
    fun restoreBeans(beans: Beans, remembered: Boolean, account: String?, raw: kotlinx.serialization.json.JsonObject? = null, rememberedBatchId: String? = null): Result<Unit> = change { current ->
        if (current.sync.userId != account) return@change current to Unit
        val again = remembered && current.lastBeansId == null
        current.copy(
            beans = current.beans.filterNot { it.id == beans.id } + beans,
            lastBeansId = if (again) beans.id else current.lastBeansId,
            lastBatchId = if (again) rememberedBatchId else current.lastBatchId,
            sync = if (raw != null) current.sync.copy(raw = current.sync.raw + ("beans/${beans.id}" to raw)) else current.sync,
        ) to Unit
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

    fun setExperiments(experiments: Experiments): Result<Unit> = change { current ->
        current.copy(experiments = experiments) to Unit
    }

    fun setTimerSettings(settings: TimerSettings): Result<Unit> = change { current ->
        current.copy(timerSettings = settings) to Unit
    }

    fun setScale(scale: PairedScale?): Result<Unit> = change { current ->
        current.copy(scale = scale) to Unit
    }

    fun dismissScaleOffer(): Result<Unit> = change { current ->
        current.copy(scaleOfferDismissed = true) to Unit
    }

    /** Applies a scale event to the active draft. Nothing changes without a draft. */
    fun changeDraft(transform: (BrewDraft) -> BrewDraft): Result<Unit> = change { current ->
        current.copy(draft = current.draft?.let(transform)) to Unit
    }

    /** Applies a timer action to the active draft's timer, from the sheet or the live notification. */
    fun updateTimer(transform: (BrewDraft, BrewTimer, Recipe) -> BrewDraft): Result<Unit> = change { current ->
        val draft = current.draft
        val timer = draft?.timer
        val recipe = draft?.recipe
        if (draft == null || timer == null || recipe == null) current to Unit
        else current.copy(draft = transform(draft, timer, recipe)) to Unit
    }

    fun startDraft(): Result<BrewDraft> = change { current ->
        val draft = current.draft ?: freshDraft(current)
        current.copy(draft = draft) to draft
    }

    /** A new brew with [beans] and [recipe], or the remembered ones, and the default grinder at its recorded setting. */
    private fun freshDraft(current: AppState, beans: Beans? = null, recipe: Recipe? = null, batchId: String? = null): BrewDraft {
        val grinder = current.grinders.firstOrNull { it.id == current.defaultGrinderId && !it.archived }
            ?: current.grinders.singleOrNull { !it.archived }
        val draft = BrewDraft(
            recipe = recipe ?: current.recipes.firstOrNull { it.id == current.lastRecipeId && !it.archived },
            grinder = grinder,
            setting = grinder?.lastSetting?.takeIf { it in MIN_GRIND_SETTING..MAX_GRIND_SETTING },
        )
        val chosen = beans ?: current.beans.firstOrNull { it.id == current.lastBeansId && !it.archived }
        return chosen?.let { draft.withBeans(it, current.lastBatchId, batchId) } ?: draft
    }

    /**
     * Starts a brew from a Library item: these [beansId] or this [recipeId], with the remembered choices for the rest.
     * Only one brew is in progress at a time, and archived items are not offered for a new brew.
     */
    fun startDraftWith(beansId: String? = null, recipeId: String? = null, batchId: String? = null): Result<BrewDraft> = change { current ->
        check(current.draft == null) { "A brew is already in progress. Resume or discard it first." }
        val beans = beansId?.let { id -> requireNotNull(current.beans.find { it.id == id && !it.archived }) { "These beans aren't available for a new brew." } }
        val recipe = recipeId?.let { id -> requireNotNull(current.recipes.find { it.id == id && !it.archived }) { "This recipe isn't available for a new brew." } }
        val draft = freshDraft(current, beans, recipe, batchId)
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
            batchId = original.batchId,
            roastDate = original.roastDate,
        ).let { draft ->
            // Beans bought again since: the repeat uses the newest bag, and says which bag the original used.
            val live = current.beans.find { it.id == original.beans.id && !it.archived }
            val newest = live?.newestBatch
            if (newest != null && newest.id != original.batchId) draft.copy(batchId = newest.id, roastDate = newest.roastDate, repeatedFromRoastDate = original.roastDate.orEmpty()) else draft
        }
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
        val recipe = requireNotNull(draft.recipe) { "Choose a recipe." }.normalized()
        require(recipe.validationErrors().isEmpty()) { "Check the recipe plan." }
        require(current.grinders.any { it.id == grinder.id && !it.archived }) { "Choose an active grinder." }
        val library = requireNotNull(current.beans.firstOrNull { it.id == beans.id }) { "These beans were deleted. Choose other beans." }
        require(!library.archived || draft.allowArchivedBeans) { "Confirm archived beans or choose other beans." }
        val record = BrewRecord(
            id = draft.id,
            startedAt = draft.startedAt,
            beans = beans.copy(batches = emptyList()),
            grinder = grinder,
            setting = setting,
            recipe = recipe,
            journal = draft.journal,
            timing = draft.timer?.timing(recipe),
            scale = draft.scale?.takeIf { draft.timer != null },
            batchId = draft.batchId,
            roastDate = draft.roastDate,
        )
        current.copy(
            brews = (current.brews + record).sortedByDescending { it.startedAt },
            grinders = current.grinders.map { if (it.id == grinder.id) it.copy(lastSetting = setting) else it },
            draft = null,
            lastBeansId = beans.id,
            lastBatchId = draft.batchId,
            lastRecipeId = recipe.id,
        ) to record
    }

    fun updateBrew(brew: BrewRecord): Result<Unit> = change { current ->
        require(current.brews.any { it.id == brew.id }) { "Brew not found." }
        require(brew.setting in MIN_GRIND_SETTING..MAX_GRIND_SETTING) { "Enter whole ticks from 0 to 100." }
        require(brew.beans.name.isNotBlank() && brew.beans.roaster.isNotBlank()) { "Enter beans and roaster." }
        require(brew.grinder.name.isNotBlank()) { "Enter a grinder name." }
        require(brew.recipe.validationErrors().isEmpty()) { "Check the recipe plan." }
        val saved = brew.copy(recipe = brew.recipe.normalized(), beans = brew.beans.copy(batches = emptyList()))
        current.copy(brews = current.brews.map { if (it.id == brew.id) saved else it }.sortedByDescending { it.startedAt }) to Unit
    }

    fun deleteBrew(id: String): Result<Unit> = change { current ->
        current.copy(brews = current.brews.filterNot { it.id == id }, noteEdits = current.noteEdits.filterNot { it.brewId == id }, sync = current.sync.copy(raw = current.sync.raw - "brews/$id")) to Unit
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

    /** Starts syncing with a signed-in account. */
    fun syncWith(userId: String): Result<Unit> = change(track = false) { it.syncingWith(userId) to Unit }

    /** Signing out leaves no account data on the phone, so the next account starts clean. */
    fun signedOut(): Result<Unit> = change(track = false) {
        check(it.sync.pending.isEmpty()) { "Couldn't upload your latest changes. Connect to the internet and try again." }
        it.withoutAccountData() to Unit
    }

    fun markPushed(pushed: Map<String, JsonElement?>): Result<Unit> = change(track = false) { it.afterPush(pushed) to Unit }

    fun applyRemote(items: List<RemoteItem>, pulledUpTo: String?): Result<Unit> = change(track = false) { it.withRemote(items, pulledUpTo) to Unit }

    fun applyDrafts(drafts: List<LibraryDraft>): Result<Unit> = change(track = false) { it.withDrafts(drafts) to Unit }
}

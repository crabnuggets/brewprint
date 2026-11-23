@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.brewprint.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.brewprint.data.*

/**
 * Where the Library is: its hub ([section] null), one section's list ("recipes", "beans", "grinders" or "archived"),
 * or one item ([detailId]) in that section. The Library tab keeps it above the screen, so starting a brew from an
 * item and coming back lands on that item again.
 */
@Stable
class LibraryNav(section: String? = null, detailId: String? = null) {
    var section by mutableStateOf(section)
    var detailId by mutableStateOf(detailId)

    /** Back to the hub. */
    fun reset() { section = null; detailId = null }

    companion object {
        val Saver = androidx.compose.runtime.saveable.listSaver<LibraryNav, String?>({ listOf(it.section, it.detailId) }, { LibraryNav(it[0], it[1]) })
    }
}

@Composable
fun LibraryScreen(
    state: AppState,
    repository: BrewRepository,
    onError: (String) -> Unit,
    requestedKind: String? = null,
    onPicked: ((String, String) -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    onEditingChanged: (Boolean) -> Unit = {},
    /** Shows Undo for an action a swipe has just taken. */
    onUndoable: (BeansUndo) -> Unit = {},
    /** The Library tab's place, kept by the app; a picker keeps its own. */
    nav: LibraryNav? = null,
    /** Starts a brew with these beans or this recipe; null where the Library picks for a brew already open. */
    onBrewWith: ((beansId: String?, recipeId: String?) -> Unit)? = null,
    onResumeBrew: () -> Unit = {},
) {
    val local = rememberSaveable(requestedKind, saver = LibraryNav.Saver) { LibraryNav(requestedKind) }
    val place = nav ?: local
    // The kind of item the open list or item holds; the archived list holds beans.
    val kind = when (val section = place.section) { null -> ""; "archived" -> "beans"; else -> section }
    val detailId = place.detailId
    // A library draft open for review: its editor adds it to the Library.
    var draftId by rememberSaveable { mutableStateOf<String?>(null) }
    // Picking from an empty list can only mean adding one, so the form opens directly and Back returns to the caller.
    val pickingFromEmpty = onPicked != null && when (requestedKind) {
        "beans" -> state.beans.none { !it.archived }
        "grinders" -> state.grinders.none { !it.archived }
        "recipes" -> state.recipes.none { !it.archived }
        else -> false
    }
    var editing by rememberSaveable { mutableStateOf(pickingFromEmpty) }
    val leaveEditor = { editing = false; draftId = null; if (pickingFromEmpty) onClose?.invoke(); Unit }
    var archive by rememberSaveable { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    var replacementId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingDialog by rememberSaveable { mutableStateOf(false) }
    var settingText by rememberSaveable { mutableStateOf("") }
    val beans = state.beans.find { it.id == detailId }
    val grinder = state.grinders.find { it.id == detailId }
    val recipe = state.recipes.find { it.id == detailId }
    val libraryDraft = state.libraryDrafts.find { it.id == draftId }
    // A draft under review is beans or a recipe, whichever list is open.
    val editKind = libraryDraft?.let { if (it.beans != null) "beans" else "recipes" } ?: kind
    // Added on another phone or discarded by Claude while under review: there is nothing left to add.
    if (draftId != null && libraryDraft == null) LaunchedEffect(Unit) { leaveEditor() }
    val closeDetail = { place.detailId = null; editing = false; archive = false; delete = false; Unit }
    // Beans deleted on another phone while open here: there is nothing left to show or edit.
    if (kind == "beans" && detailId != null && beans == null) LaunchedEffect(Unit) { closeDetail() }
    fun save(result: Result<Unit>): Boolean {
        result.exceptionOrNull()?.let { onError(it.message ?: "Couldn't save changes. Try again.") }
        return result.isSuccess
    }
    fun saved(id: String) {
        val savedKind = editKind
        editing = false
        draftId = null
        if (onPicked != null) onPicked(savedKind, id) else { place.section = savedKind; place.detailId = id }
    }
    // A swipe acts at once and offers Undo.
    fun swipeArchive(item: Beans) { if (save(repository.archiveBeans(item.id))) onUndoable(BeansUndo(BeansUndo.Action.ARCHIVED, item)) }
    fun swipeUnarchive(item: Beans) { if (save(repository.unarchiveBeans(item.id))) onUndoable(BeansUndo(BeansUndo.Action.UNARCHIVED, item)) }
    fun swipeDelete(item: Beans) {
        val undo = BeansUndo(BeansUndo.Action.DELETED, item, remembered = state.lastBeansId == item.id, account = state.sync.userId)
        if (save(repository.deleteBeans(item.id))) onUndoable(undo)
    }
    LaunchedEffect(editing) { onEditingChanged(editing) }
    DisposableEffect(Unit) { onDispose { onEditingChanged(false) } }

    if (editing) {
        when (editKind) {
            "beans" -> BeansEditor(libraryDraft?.beans ?: beans, onSave = { item ->
                save(repository.upsertBeans(item)).also { if (it) saved(item.id) }
            }, onBack = leaveEditor, onDiscardDraft = libraryDraft?.let { draft -> { if (save(repository.discardLibraryDraft(draft.id))) leaveEditor() } })
            "grinders" -> GrinderEditor(grinder, onSave = { item ->
                save(repository.upsertGrinder(item)).also { if (it) saved(item.id) }
            }, onBack = leaveEditor)
            "recipes" -> RecipeEditor(libraryDraft?.recipe ?: recipe, onSave = { item ->
                save(repository.upsertRecipe(item)).also { if (it) saved(item.id) }
            }, onBack = leaveEditor, onDiscardDraft = libraryDraft?.let { draft -> { if (save(repository.discardLibraryDraft(draft.id))) leaveEditor() } })
        }
        return
    }
    if (detailId != null) {
        BackHandler(LocalScreenActive.current, closeDetail)
        var menu by remember { mutableStateOf(false) }
        // The item's actions sit in the header, so a long recipe never hides them at the end of the page.
        LibraryPage(when (kind) { "beans" -> beans?.name; "grinders" -> grinder?.name; else -> recipe?.name } ?: "Library", closeDetail, actions = {
            IconAction(Icons.Outlined.Edit, "Edit ${kindLabel(kind)}") { editing = true }
            Spacer(Modifier.width(8.dp))
            Box {
                IconAction(Icons.Outlined.MoreVert, "More actions") { menu = true }
                DropdownMenu(menu, { menu = false }, shape = MaterialTheme.shapes.small, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    if (recipe != null && kind == "recipes") DropdownMenuItem(text = { Text("Duplicate recipe") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = {
                        menu = false
                        val duplicate = recipe.copy(id = newId(), name = "${recipe.name} copy", archived = false)
                        if (save(repository.upsertRecipe(duplicate))) { place.detailId = duplicate.id; editing = true }
                    })
                    if (beans != null && beans.archived) DropdownMenuItem(text = { Text("Unarchive beans") }, leadingIcon = { Icon(Icons.Outlined.Unarchive, null) }, onClick = { menu = false; save(repository.unarchiveBeans(beans.id)) })
                    else DropdownMenuItem(text = { Text("Archive ${kindLabel(kind)}") }, leadingIcon = { Icon(Icons.Outlined.Archive, null) }, onClick = { menu = false; replacementId = null; archive = true })
                    if (beans != null && kind == "beans") DropdownMenuItem(text = { Text("Delete beans") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; delete = true },
                        colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error, leadingIconColor = MaterialTheme.colorScheme.error))
                }
            }
        }) {
            if (beans != null && kind == "beans") {
                if (beans.archived) Text("Archived", style = MaterialTheme.typography.labelLarge)
                DetailLine("Roaster", beans.roaster)
                // Archived beans are not offered for a new brew.
                if (onBrewWith != null && !beans.archived) BrewWithAction("Brew with these beans", state.draft != null, onResumeBrew) { onBrewWith(beans.id, null) }
                DetailLine("Origin", beans.origin)
                DetailLine("Variety / cultivar", beans.variety)
                DetailLine("Processing method", beans.processing)
                DetailLine("Expected tasting notes", beans.expectedNotes)
                DetailLine("General notes", beans.notes)
            }
            if (grinder != null && kind == "grinders") {
                DetailLine("Last recorded setting", grinder.lastSetting?.let { "$it ticks" } ?: "Not recorded")
                DetailLine("Reference notes", grinder.referenceNotes)
                DetailLine("Dial finish", finishLabel(grinder.finish))
                if (state.defaultGrinderId == grinder.id) Text("Default grinder", style = MaterialTheme.typography.labelLarge)
                else SoftButton(shape = MaterialTheme.shapes.small, onClick = { save(repository.setDefaultGrinder(grinder.id)) }, modifier = Modifier.fillMaxWidth()) { Text("Make default grinder") }
                SoftButton(shape = MaterialTheme.shapes.small, onClick = { settingText = grinder.lastSetting?.toString() ?: ""; settingDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Update last recorded setting") }
            }
            if (recipe != null && kind == "recipes") {
                Text("${formatAmount(recipe.dose)} g · 1:${formatAmount(recipe.ratio)} · ${formatAmount(recipe.totalWater)} g", style = MaterialTheme.typography.titleLarge)
                if (onBrewWith != null && !recipe.archived) BrewWithAction("Brew with this recipe", state.draft != null, onResumeBrew) { onBrewWith(null, recipe.id) }
                recipe.schedule().forEachIndexed { index, scheduled ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${formatDuration(scheduled.startSeconds)} · ${scheduled.pulse.label.ifBlank { "Pulse ${index + 1}" }}", style = MaterialTheme.typography.titleMedium)
                            Text("Add ${formatAmount(scheduled.pulse.water)} g · Pour to ${formatAmount(scheduled.cumulativeWater)} g total")
                            Text("Pour ${scheduled.pulse.pourSeconds} s" + if (index < recipe.pulses.lastIndex) " · Wait ${scheduled.pulse.waitSeconds} s" else "")
                        }
                    }
                }
                val last = recipe.schedule().lastOrNull()
                last?.let { Text("Scheduled pours end at ${formatDuration(it.startSeconds + it.pulse.pourSeconds)}. Allow for drawdown.") }
                DetailLine("Preparation notes", recipe.notes)
            }
        }
    } else if (place.section == "archived") {
        val back = { place.section = "beans" }
        BackHandler(LocalScreenActive.current, back)
        LibraryPage("Archived beans", back) {
            val archived = state.beans.filter { it.archived }
            if (archived.isEmpty()) Text("No archived beans.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            archived.forEach { item -> key(item.id) { SwipeRow({ place.detailId = item.id }, SwipeAction("Unarchive", Icons.Outlined.Unarchive) { swipeUnarchive(item) }, onDelete = { swipeDelete(item) }) { BeansRowContent(item) } } }
        }
    } else if (place.section != null) {
        // One kind's list, from the hub or, while picking for a brew, opened directly.
        val back = onClose ?: { place.section = null }
        BackHandler(LocalScreenActive.current, back)
        LibraryPage(if (onPicked == null) kind.replaceFirstChar { it.uppercase() } else "Choose ${kindLabel(kind)}", back, actions = {
            SoftTextButton(onClick = { place.detailId = null; editing = true }) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add ${kindLabel(kind)}") }
        }) {
            val open: (String) -> Unit = { id -> if (onPicked != null) onPicked(kind, id) else place.detailId = id }
            when (kind) {
                "beans" -> {
                    val active = state.beans.filterNot { it.archived }
                    if (active.isEmpty()) Text("No beans yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    active.forEach { item ->
                        // Beans swipe to archive or delete in the Library, not while picking for a brew.
                        if (onPicked == null) key(item.id) { SwipeRow({ open(item.id) }, SwipeAction("Archive", Icons.Outlined.Archive) { swipeArchive(item) }, onDelete = { swipeDelete(item) }) { BeansRowContent(item) } }
                        else LibraryCard({ open(item.id) }) { BeansRowContent(item) }
                    }
                    val archived = state.beans.count { it.archived }
                    if (onPicked == null && archived > 0) SoftButton(onClick = { place.section = "archived" }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text("Archived beans", Modifier.weight(1f))
                        Text("$archived", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(Icons.Outlined.ChevronRight, null, Modifier.padding(start = 8.dp))
                    }
                }
                "grinders" -> {
                    val active = state.grinders.filterNot { it.archived }
                    if (active.isEmpty()) Text("No grinders yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    active.forEach { item -> LibraryCard({ open(item.id) }) { GrinderRowContent(item, item.id == state.defaultGrinderId) } }
                }
                else -> {
                    val active = state.recipes.filterNot { it.archived }
                    if (active.isEmpty()) Text("No recipes yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    active.forEach { item -> LibraryCard({ open(item.id) }) { RecipeRowContent(item) } }
                }
            }
        }
    } else {
        LibraryPage("Library", null) {
            LibraryHub(state, onDraft = { draft -> draftId = draft.id; place.detailId = null; editing = true }, onSection = { place.section = it })
        }
    }
    if (archive) {
        val replacements = state.grinders.filter { !it.archived && it.id != detailId }
        val needsReplacement = kind == "grinders" && detailId == state.defaultGrinderId && replacements.isNotEmpty()
        AlertDialog(onDismissRequest = { archive = false }, title = { Text("Archive ${kindLabel(kind)}?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Existing brew records keep their saved details.")
                if (needsReplacement) {
                    Text("Choose a new default grinder.")
                    replacements.forEach { option ->
                        SoftButton(shape = MaterialTheme.shapes.small, onClick = { replacementId = option.id }, modifier = Modifier.fillMaxWidth()) {
                            Text(option.name + if (replacementId == option.id) " · Selected" else "")
                        }
                    }
                }
            }
        }, confirmButton = {
            SoftTextButton(enabled = !needsReplacement || replacementId != null, onClick = {
                val result = when (kind) {
                    "beans" -> repository.archiveBeans(detailId!!)
                    "grinders" -> repository.archiveGrinder(detailId!!, replacementId)
                    else -> repository.archiveRecipe(detailId!!)
                }
                if (save(result)) { archive = false; place.detailId = null }
            }) { Text("Archive") }
        }, dismissButton = { SoftTextButton(onClick = { archive = false }) { Text("Keep ${kindLabel(kind)}") } })
    }
    if (delete && beans != null) AlertDialog(onDismissRequest = { delete = false }, title = { Text("Delete these beans?") },
        text = { Text("Existing brew records keep their saved details. You can't undo this.") },
        confirmButton = { SoftTextButton(onClick = { if (save(repository.deleteBeans(beans.id))) { delete = false; place.detailId = null } }, danger = true) { Text("Delete") } },
        dismissButton = { SoftTextButton(onClick = { delete = false }) { Text("Keep beans") } })
    if (settingDialog && grinder != null) AlertDialog(onDismissRequest = { settingDialog = false }, title = { Text("Update last recorded setting") }, text = {
        LibraryField(settingText, { settingText = it }, "Grind setting, ticks", KeyboardType.Number, error = if (parseGrindSetting(settingText) == null) "Enter whole ticks from $MIN_GRIND_SETTING to $MAX_GRIND_SETTING." else null, helper = "$MIN_GRIND_SETTING-$MAX_GRIND_SETTING ticks", required = true)
    }, confirmButton = { SoftTextButton(enabled = parseGrindSetting(settingText) != null, onClick = {
        if (save(repository.updateGrinderSetting(grinder.id, parseGrindSetting(settingText)!!))) settingDialog = false
    }) { Text("Update setting") } }, dismissButton = { SoftTextButton(onClick = { settingDialog = false }) { Text("Cancel") } })
}

/** "Brew with this recipe" or "Brew with these beans"; while a brew is in progress, the way back to it instead. */
@Composable
private fun BrewWithAction(label: String, inProgress: Boolean, onResume: () -> Unit, onStart: () -> Unit) {
    PrimaryAction(label, enabled = !inProgress, icon = Icons.Outlined.PlayArrow, onClick = onStart)
    if (inProgress) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("A brew is already in progress.", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        SoftTextButton(onClick = onResume) { Text("Resume brew") }
    }
}

/** The Library's front page: drafts from Claude waiting, then one card for each kind of item. */
@Composable
private fun LibraryHub(state: AppState, onDraft: (LibraryDraft) -> Unit, onSection: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val drafts = state.libraryDrafts
    if (drafts.isNotEmpty()) Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = colors.surface, border = BorderStroke(1.5.dp, colors.onSurface)) {
        Column {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Outlined.Inbox, null, Modifier.size(40.dp).background(colors.primary, MaterialTheme.shapes.small).padding(10.dp), tint = colors.onPrimary)
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                    Text("Drafts from Claude", style = MaterialTheme.typography.titleMedium)
                    Text(if (drafts.size == 1) "1 draft waiting" else "${drafts.size} drafts waiting", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
            drafts.forEach { draft ->
                HorizontalDivider(color = colors.outlineVariant)
                val (tag, title, detail) = draft.beans?.let { Triple("Beans", it.name, it.roaster) } ?: draft.recipe!!.let { Triple("Recipe", it.name, recipeSummary(it)) }
                Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Review draft") { onDraft(draft) }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.titleMedium)
                            Tag(tag, Modifier.align(Alignment.CenterVertically))
                        }
                        Text(detail, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = colors.onSurfaceVariant)
                }
            }
        }
    }
    val activeRecipes = state.recipes.filterNot { it.archived }
    // The active recipe brewed most often, with the most recent brew breaking a tie.
    val mostUsed = state.brews.sortedByDescending { it.startedAt }.groupingBy { it.recipe.id }.eachCount().entries
        .sortedByDescending { it.value }.firstNotNullOfOrNull { (id, _) -> activeRecipes.find { it.id == id } }
    SectionCard(Icons.AutoMirrored.Outlined.MenuBook, "Recipes", count(activeRecipes.size, "recipe", "recipes"), { onSection("recipes") }, mostUsed?.let { recipe -> {
        Text("Most used", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(recipe.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    } })
    val activeBeans = state.beans.filterNot { it.archived }
    val archived = state.beans.size - activeBeans.size
    val lastBrewed = state.brews.sortedByDescending { it.startedAt }.firstNotNullOfOrNull { brew -> activeBeans.find { it.id == brew.beans.id } }
    SectionCard(Icons.Outlined.Grain, "Beans", count(activeBeans.size, "active", "active") + if (archived > 0) " · $archived archived" else "", { onSection("beans") }, lastBrewed?.let { beans -> {
        Text("Last brewed", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text("${beans.name} · ${beans.roaster}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    } })
    val activeGrinders = state.grinders.filterNot { it.archived }
    val default = activeGrinders.find { it.id == state.defaultGrinderId }
    SectionCard(Icons.Outlined.Album, "Grinders", count(activeGrinders.size, "grinder", "grinders"), { onSection("grinders") }, default?.let { grinder -> {
        Row(verticalAlignment = Alignment.CenterVertically) { GrinderRowContent(grinder, isDefault = true) }
    } })
}

private fun count(size: Int, one: String, many: String) = if (size == 0) "None yet" else if (size == 1) "1 $one" else "$size $many"

/** A hub card for one kind of item: its icon, name and count, and at most one useful value beneath. */
@Composable
private fun SectionCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, summary: String, onClick: () -> Unit, preview: (@Composable ColumnScope.() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    LibraryCard(onClick, Modifier.testTag("library-${title.lowercase()}")) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(icon, null, Modifier.size(44.dp).background(colors.soft, MaterialTheme.shapes.small).padding(11.dp), tint = colors.onSecondaryContainer)
                Column(Modifier.weight(1f)) {
                    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    Text(summary, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = colors.onSurfaceVariant)
            }
            if (preview != null) {
                HorizontalDivider(color = colors.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = preview)
            }
        }
    }
}

/** A tappable card in the Library's lists. */
@Composable
private fun LibraryCard(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** A small label such as "Beans" or "Default": soft, or [strong] in the primary fill. */
@Composable
private fun Tag(text: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Text(text, modifier.background(if (strong) colors.primary else colors.soft, MaterialTheme.shapes.extraSmall).padding(horizontal = 8.dp, vertical = 2.dp),
        color = if (strong) colors.onPrimary else colors.onSecondaryContainer, style = MaterialTheme.typography.labelMedium)
}

/** A recipe in a list: its name, its dose, ratio and water, and how its pours run. */
@Composable
private fun RowScope.RecipeRowContent(recipe: Recipe) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(recipe.name, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(Triple("Dose", "${formatAmount(recipe.dose)} g", "Coffee dose ${formatAmount(recipe.dose)} grams"), Triple("Ratio", "1:${formatAmount(recipe.ratio)}", "Brew ratio 1 to ${formatAmount(recipe.ratio)}"),
                Triple("Water", "${formatAmount(recipe.totalWater)} g", "Total brew water ${formatAmount(recipe.totalWater)} grams")).forEach { (label, value, spoken) ->
                Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken }) {
                    Text(label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text(value, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                }
            }
        }
        val pours = recipe.pulses.size
        val end = recipe.schedule().lastOrNull()?.let { it.startSeconds + it.pulse.pourSeconds } ?: 0
        Text("${if (pours == 1) "1 pour" else "$pours pours"} · ends ${formatDuration(end)}", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Beans in a list: name, roaster, and where they're from. */
@Composable
private fun RowScope.BeansRowContent(beans: Beans) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(beans.name, style = MaterialTheme.typography.titleMedium)
        Text(beans.roaster, style = MaterialTheme.typography.bodyMedium)
        listOf(beans.origin, beans.processing).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A grinder: its name, "Default" or its reference notes, and its last recorded setting large at the end. */
@Composable
private fun RowScope.GrinderRowContent(grinder: Grinder, isDefault: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(grinder.name, style = MaterialTheme.typography.titleMedium)
        if (isDefault) Tag("Default", strong = true)
        else if (grinder.referenceNotes.isNotBlank()) Text(grinder.referenceNotes, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
    val setting = grinder.lastSetting
    Column(Modifier.clearAndSetSemantics { contentDescription = "Last recorded setting, " + (setting?.let { "$it ticks" } ?: "not recorded") }, horizontalAlignment = Alignment.End) {
        Text("Last recorded", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (setting == null) Text("Not recorded", style = MaterialTheme.typography.bodyLarge)
        else Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$setting", style = MaterialTheme.typography.headlineLarge.copy(letterSpacing = (-1).sp).tabular())
            Text("ticks", Modifier.padding(bottom = 5.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private const val ARCHIVE_REACH = .3f
private const val DELETE_REACH = .65f

/**
 * What a Library swipe did, kept as data so its Undo is offered again if the screen is recreated.
 * [shownUntil] is when the Undo window ends, once its bar has shown.
 */
@kotlinx.serialization.Serializable
data class BeansUndo(val action: Action, val beans: Beans, val remembered: Boolean = false, val account: String? = null, val shownUntil: Long? = null) {
    enum class Action(val message: String) { ARCHIVED("Beans archived"), UNARCHIVED("Beans unarchived"), DELETED("Beans deleted") }

    /** Reverses only what the swipe did: the archive flag on beans still in the Library, or deleted beans while the phone is in the same account. */
    fun undo(repository: BrewRepository): Result<Unit> {
        val present = repository.state.value.beans.any { it.id == beans.id }
        return when (action) {
            Action.ARCHIVED -> if (present) repository.unarchiveBeans(beans.id) else Result.success(Unit)
            Action.UNARCHIVED -> if (present) repository.archiveBeans(beans.id) else Result.success(Unit)
            Action.DELETED -> repository.restoreBeans(beans, remembered, account)
        }
    }
}

/** What a short swipe does: archive, or for archived beans, unarchive. */
private class SwipeAction(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val act: () -> Unit)

/**
 * A Library row that swipes left: a short swipe, past 30% of the row, takes [short] in amber; a long one, past 65%, deletes it in red.
 * Crossing each point gives a haptic, and screen readers get both actions on the row.
 */
@Composable
private fun SwipeRow(onOpen: () -> Unit, short: SwipeAction, onDelete: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    var width by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    // 0 springs back, 1 archives, 2 deletes.
    fun stageAt(at: Float): Int {
        val reach = if (width == 0) 0f else -at / width
        return if (reach >= DELETE_REACH) 2 else if (reach >= ARCHIVE_REACH) 1 else 0
    }
    val stage = stageAt(offset)
    var felt by remember { mutableIntStateOf(0) }
    LaunchedEffect(stage) { if (stage != felt) { view.thresholdHaptic(armed = stage > felt); felt = stage } }
    val fade = androidx.compose.animation.core.tween<androidx.compose.ui.graphics.Color>(120)
    val fill by androidx.compose.animation.animateColorAsState(when {
        stage == 2 -> colors.error
        stage == 1 -> colors.archive
        offset < 0f -> colors.soft
        else -> androidx.compose.ui.graphics.Color.Transparent
    }, fade, label = "swipe-fill")
    val ink by androidx.compose.animation.animateColorAsState(when (stage) { 2 -> colors.onError; 1 -> colors.onArchive; else -> colors.onSurfaceVariant }, fade, label = "swipe-ink")
    val deleting = stage == 2
    val scope = rememberCoroutineScope()
    val shortAction by rememberUpdatedState(short)
    val deleteAction by rememberUpdatedState(onDelete)
    // Every action comes from one whole gesture: a new drag stops any spring back and starts from rest.
    var settling by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun settle() { settling = scope.launch { animate(offset, 0f, animationSpec = androidx.compose.animation.core.tween(250)) { value, _ -> offset = value } } }
    Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).onSizeChanged { width = it.width }) {
        Row(Modifier.matchParentSize().background(fill).padding(horizontal = 22.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (offset < 0f) {
                Text(if (deleting) "Delete" else short.label, color = ink, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Icon(if (deleting) Icons.Outlined.Delete else short.icon, null, tint = ink)
            }
        }
        LibraryCard(onOpen, Modifier
            .offset { IntOffset(offset.roundToInt(), 0) }
            // Only letting go acts; a cancelled drag, such as one the system's back gesture takes over, springs back.
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { when (stageAt(offset)) { 2 -> deleteAction(); 1 -> shortAction.act() }; settle() },
                    onDragStart = { settling?.cancel(); offset = 0f },
                    onDragCancel = ::settle,
                ) { change, delta -> change.consume(); offset = (offset + delta).coerceIn(-width.toFloat(), 0f) }
            }
            .semantics { customActions = listOf(CustomAccessibilityAction(short.label) { short.act(); true }, CustomAccessibilityAction("Delete") { onDelete(); true }) },
            content = content)
    }
}

private fun recipeSummary(recipe: Recipe) = "${formatAmount(recipe.dose)} g · 1:${formatAmount(recipe.ratio)} · ${formatAmount(recipe.totalWater)} g"

/** The header action that discards a library draft under review, after asking. */
@Composable
internal fun DiscardDraftAction(onDiscard: () -> Unit) {
    var asking by rememberSaveable { mutableStateOf(false) }
    IconAction(Icons.Outlined.Delete, "Discard draft", danger = true) { asking = true }
    if (asking) AlertDialog(onDismissRequest = { asking = false }, title = { Text("Discard this draft?") }, text = { Text("It won't be added to your Library.") },
        confirmButton = { SoftTextButton(onClick = { asking = false; onDiscard() }, danger = true) { Text("Discard") } }, dismissButton = { SoftTextButton(onClick = { asking = false }) { Text("Keep draft") } })
}

/** Adds or edits beans, or with [onDiscardDraft], reviews a library draft before adding it. */
@Composable
private fun BeansEditor(initial: Beans?, onSave: (Beans) -> Boolean, onBack: () -> Unit, onDiscardDraft: (() -> Unit)? = null) {
    var original by remember(initial?.id) { mutableStateOf(initial ?: Beans()) }
    val id = rememberSaveable { original.id }
    var name by rememberSaveable { mutableStateOf(original.name) }
    var roaster by rememberSaveable { mutableStateOf(original.roaster) }
    var origin by rememberSaveable { mutableStateOf(original.origin) }
    var variety by rememberSaveable { mutableStateOf(original.variety) }
    var processing by rememberSaveable { mutableStateOf(original.processing) }
    var expectedNotes by rememberSaveable { mutableStateOf(original.expectedNotes) }
    var notes by rememberSaveable { mutableStateOf(original.notes) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val dirty = listOf(name, roaster, origin, variety, processing, expectedNotes, notes) != listOf(original.name, original.roaster, original.origin, original.variety, original.processing, original.expectedNotes, original.notes)
    // A draft Claude corrects while it's open shows the correction, unless the user has started editing.
    LaunchedEffect(initial) {
        if (initial != null && initial != original && !dirty) {
            original = initial
            name = initial.name; roaster = initial.roaster; origin = initial.origin; variety = initial.variety
            processing = initial.processing; expectedNotes = initial.expectedNotes; notes = initial.notes
        }
    }
    val back = { if (dirty) discard = true else onBack() }
    BackHandler(LocalScreenActive.current, back)
    LibraryPage(if (onDiscardDraft != null) "Review beans" else if (initial == null) "Add beans" else "Edit beans", back, actions = { onDiscardDraft?.let { DiscardDraftAction(it) } }) {
        LibraryField(name, { name = it }, "Name", error = if (name.isBlank()) "Enter a name." else null, required = true)
        LibraryField(roaster, { roaster = it }, "Roaster", error = if (roaster.isBlank()) "Enter a roaster." else null, required = true)
        LibraryField(origin, { origin = it }, "Origin, optional", helper = "Country, region, or farm if known")
        LibraryField(variety, { variety = it }, "Variety / cultivar, optional")
        LibraryField(processing, { processing = it }, "Processing method, optional")
        LibraryField(expectedNotes, { expectedNotes = it }, "Expected tasting notes, optional", multiline = true)
        LibraryField(notes, { notes = it }, "General notes, optional", multiline = true)
        PrimaryAction(if (onDiscardDraft != null) "Add to Library" else "Save beans", enabled = name.isNotBlank() && roaster.isNotBlank(), icon = Icons.Outlined.Check) {
            if (onSave(original.copy(id = id, name = name.trim(), roaster = roaster.trim(), origin = origin, variety = variety, processing = processing, expectedNotes = expectedNotes, notes = notes))) onBack()
        }
    }
    DiscardLibraryDialog(discard, { discard = false }, onBack)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GrinderEditor(initial: Grinder?, onSave: (Grinder) -> Boolean, onBack: () -> Unit) {
    val original = remember(initial?.id) { initial ?: Grinder() }
    val id = rememberSaveable { original.id }
    var name by rememberSaveable { mutableStateOf(original.name) }
    var setting by rememberSaveable { mutableStateOf(original.lastSetting?.toString() ?: "") }
    var reference by rememberSaveable { mutableStateOf(original.referenceNotes) }
    var finishName by rememberSaveable { mutableStateOf(original.finish.name) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val dirty = name != original.name || setting != (original.lastSetting?.toString() ?: "") || reference != original.referenceNotes || finishName != original.finish.name
    val back = { if (dirty) discard = true else onBack() }
    BackHandler(LocalScreenActive.current, back)
    LibraryPage(if (initial == null) "Add grinder" else "Edit grinder", back) {
        LibraryField(name, { name = it }, "Name", error = if (name.isBlank()) "Enter a grinder name." else null, required = true)
        LibraryField(setting, { setting = it }, "Last recorded setting, ticks, optional", KeyboardType.Number, error = if (setting.isNotBlank() && parseGrindSetting(setting) == null) "Enter whole ticks from $MIN_GRIND_SETTING to $MAX_GRIND_SETTING." else null, helper = "$MIN_GRIND_SETTING-$MAX_GRIND_SETTING ticks")
        LibraryField(reference, { reference = it }, "Reference notes, optional", multiline = true, helper = "Describe your own reference point for counting ticks.")
        Text("Dial finish", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialFinish.entries.forEach { finish -> FilterChip(selected = finishName == finish.name, onClick = { finishName = finish.name }, label = { Text(finishLabel(finish)) }) }
        }
        PrimaryAction("Save grinder", enabled = name.isNotBlank() && (setting.isBlank() || parseGrindSetting(setting) != null), icon = Icons.Outlined.Check) {
            if (onSave(original.copy(id = id, name = name.trim(), lastSetting = parseGrindSetting(setting), referenceNotes = reference, finish = DialFinish.valueOf(finishName)))) onBack()
        }
    }
    DiscardLibraryDialog(discard, { discard = false }, onBack)
}

@Composable
internal fun LibraryPage(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Page(title, back = onBack, actions = actions, content = content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibraryField(value: String, onChange: (String) -> Unit, label: String, keyboard: KeyboardType = KeyboardType.Text, error: String? = null, multiline: Boolean = false, helper: String? = null, required: Boolean = false, prefix: (@Composable () -> Unit)? = null, modifier: Modifier = Modifier) {
    var interacted by rememberSaveable { mutableStateOf(false) }
    var wasFocused by remember { mutableStateOf(false) }
    val visibleError = error.takeIf { interacted }
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            if (input != value && (input.isNotBlank() || value.isNotBlank())) interacted = true
            onChange(input)
        },
        label = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label)
                if (required) Text("Required", style = MaterialTheme.typography.labelSmall)
            }
        },
        modifier = modifier.fillMaxWidth().onFocusChanged { focus ->
            if (focus.isFocused) wasFocused = true
            else if (wasFocused) interacted = true
        }.semantics { if (visibleError != null) this.error(visibleError) },
        shape = MaterialTheme.shapes.medium,
        colors = softFieldColors(),
        prefix = prefix,
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        isError = visibleError != null,
        supportingText = if (visibleError != null || helper != null) ({ Text(visibleError ?: helper.orEmpty()) }) else null,
    )
}

@Composable
internal fun DiscardLibraryDialog(visible: Boolean, keepEditing: () -> Unit, discard: () -> Unit) {
    if (visible) AlertDialog(onDismissRequest = keepEditing, title = { Text("Discard changes?") }, confirmButton = { SoftTextButton(onClick = discard) { Text("Discard") } }, dismissButton = { SoftTextButton(onClick = keepEditing) { Text("Keep editing") } })
}

@Composable
private fun DetailLine(label: String, value: String) {
    if (value.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun kindLabel(kind: String): String = when (kind) { "grinders" -> "grinder"; "recipes" -> "recipe"; else -> "beans" }
private fun finishLabel(finish: DialFinish): String = when (finish) { DialFinish.METAL -> "Brushed metal"; DialFinish.MATTE -> "Matte black"; DialFinish.GLASS -> "Frosted glass" }

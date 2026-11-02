package com.brewprint.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brewprint.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Composable
fun BrewprintApp(state: AppState, repository: BrewRepository) {
    var tab by rememberSaveable { mutableStateOf("Brew") }
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    var recordId by rememberSaveable { mutableStateOf<String?>(null) }
    var notesReturn by rememberSaveable { mutableStateOf<String?>(null) }
    var draftReturn by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(repository.loadError) }
    var libraryEditing by remember { mutableStateOf(false) }
    val onError: (String) -> Unit = { error = it }
    fun openRecord(id: String) { recordId = id; route = "record" }
    fun repeat(id: String) {
        repository.repeatBrew(id).onSuccess { draftReturn = route; route = "draft" }.onFailure { error = it.message ?: "Couldn't repeat brew." }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (route == null && !libraryEditing) Surface(Modifier.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), shadowElevation = 8.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.fillMaxWidth().padding(6.dp)) {
                    listOf("Brew", "History", "Library").forEach { destination ->
                        NavigationBarItem(selected = tab == destination, onClick = { tab = destination }, icon = { Icon(when (destination) { "Brew" -> Icons.Outlined.Coffee; "History" -> Icons.AutoMirrored.Outlined.FormatListBulleted; else -> Icons.AutoMirrored.Outlined.MenuBook }, destination) }, label = { Text(destination) }, colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.surfaceVariant))
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (route) {
                "draft" -> state.draft?.let { draft -> DraftFlow(draft, state, repository, onError, { route = draftReturn }, { openRecord(it) }) }
                    ?: LaunchedEffect(Unit) { route = null }
                "record" -> state.brews.find { it.id == recordId }?.let { brew -> RecordDetail(brew, state.noteEdits.any { it.brewId == brew.id }, { route = null }, { repeat(brew.id) }, { notesReturn = "record"; route = "notes" }, { route = "edit" }, { repository.deleteBrew(brew.id).onSuccess { route = null }.onFailure { onError(it.message ?: "Couldn't delete brew.") } }) }
                "notes" -> state.brews.find { it.id == recordId }?.let { brew -> NotesScreen(brew, state.noteEdits.find { it.brewId == brew.id }?.journal, repository, onError) { route = notesReturn } }
                "edit" -> state.brews.find { it.id == recordId }?.let { brew -> EditRecord(brew, state, repository, onError) { route = "record" } }
                "settings" -> AppearanceScreen(state.appearance, { repository.setAppearance(it).onFailure { failure -> onError(failure.message ?: "Couldn't save appearance.") } }) { route = null }
                else -> when (tab) {
                    "Library" -> LibraryScreen(state, repository, onError, onEditingChanged = { libraryEditing = it })
                    "History" -> HistoryScreen(state.brews) { openRecord(it) }
                    else -> BrewHome(state, { repository.startDraft().onSuccess { draftReturn = null; route = "draft" }.onFailure { onError(it.message ?: "Couldn't start brew.") } }, { draftReturn = null; route = "draft" }, { openRecord(it) }, { repeat(it) }, { recordId = it; notesReturn = null; route = "notes" }, { route = "settings" })
                }
            }
        }
    }
    if (error != null) AlertDialog(onDismissRequest = { error = null }, title = { Text("Couldn't complete action") }, text = { Text(error.orEmpty()) }, confirmButton = { TextButton(onClick = { error = null }) { Text("Close") } })
}

@Composable
private fun BrewHome(state: AppState, onNew: () -> Unit, onResume: () -> Unit, onRecord: (String) -> Unit, onRepeat: (String) -> Unit, onNotes: (String) -> Unit, onSettings: () -> Unit) {
    Page("Brew", actions = { IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Appearance settings") } }) {
        if (state.draft != null) {
            PrimaryAction("Resume brew", onClick = onResume)
            Text("One brew is in progress.", style = MaterialTheme.typography.bodyMedium)
        } else PrimaryAction("+ New brew", onClick = onNew)
        val grinder = state.grinders.find { it.id == state.defaultGrinderId && !it.archived }
        if (grinder != null) Panel {
            Text("Last recorded setting", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(grinder.lastSetting?.let { "$it ticks" } ?: "Not recorded", style = MaterialTheme.typography.headlineMedium)
            Text(grinder.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val recent = state.brews.maxByOrNull { it.startedAt }
        if (recent == null) {
            Spacer(Modifier.height(20.dp))
            Text("Record your first brew", style = MaterialTheme.typography.titleLarge)
            Text("Save the beans, recipe, grinder setting, and your tasting notes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Most recent brew", style = MaterialTheme.typography.titleMedium)
            Panel {
                Text(dateTime(recent.startedAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(recent.beans.name, Modifier.clickable { onRecord(recent.id) }.padding(vertical = 6.dp), style = MaterialTheme.typography.titleLarge)
                Text(recent.recipe.name)
                Text("${formatAmount(recent.recipe.dose)} g · 1:${formatAmount(recent.recipe.ratio)} · ${recent.setting} ticks", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { onRecord(recent.id) }, modifier = Modifier.fillMaxWidth()) { Text("View brew") }
                OutlinedButton(onClick = { onRepeat(recent.id) }, enabled = state.draft == null, modifier = Modifier.fillMaxWidth()) { Text("Repeat") }
                OutlinedButton(onClick = { onNotes(recent.id) }, modifier = Modifier.fillMaxWidth()) { Text(if (state.noteEdits.any { it.brewId == recent.id }) "Resume tasting notes" else if (recent.journal.isEmpty) "Add tasting notes" else "Edit tasting notes") }
            }
        }
        state.noteEdits.filter { it.brewId != recent?.id }.forEach { edit ->
            state.brews.find { it.id == edit.brewId }?.let { brew -> OutlinedButton(onClick = { onNotes(brew.id) }, modifier = Modifier.fillMaxWidth()) { Text("Resume tasting notes · ${brew.beans.name}") } }
        }
    }
}

@Composable
private fun HistoryScreen(brews: List<BrewRecord>, onRecord: (String) -> Unit) {
    Page("History") {
        if (brews.isEmpty()) Text("Your saved brews will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        brews.sortedByDescending { it.startedAt }.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(ZoneId.systemDefault()).toLocalDate() }.forEach { (day, records) ->
            Text(when (day) { LocalDate.now() -> "Today"; LocalDate.now().minusDays(1) -> "Yesterday"; else -> day.format(DateTimeFormatter.ofPattern("d MMM uuuu")) }, style = MaterialTheme.typography.titleMedium)
            records.forEach { brew -> Surface(onClick = { onRecord(brew.id) }, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Instant.ofEpochMilli(brew.startedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(brew.beans.name, style = MaterialTheme.typography.titleMedium)
                    Text("${brew.recipe.name} · ${brew.setting} ticks")
                    Text(brew.journal.impression.ifBlank { if (brew.journal.isEmpty) "Add tasting notes" else "View tasting notes" }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } }
        }
    }
}

@Composable
private fun DraftFlow(initial: BrewDraft, state: AppState, repository: BrewRepository, onError: (String) -> Unit, onClose: () -> Unit, onSaved: (String) -> Unit) {
    var encoded by rememberSaveable(initial.id) { mutableStateOf(Json.encodeToString(initial)) }
    val draft = remember(encoded) { Json.decodeFromString<BrewDraft>(encoded) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var dial by rememberSaveable { mutableStateOf(false) }
    var validDate by rememberSaveable { mutableStateOf(true) }
    var dateText by rememberSaveable(initial.id) { mutableStateOf(dateInput(initial.startedAt)) }
    var discard by remember { mutableStateOf(false) }
    fun change(updated: BrewDraft) {
        encoded = Json.encodeToString(updated)
        repository.updateDraft(updated).onFailure { onError(it.message ?: "Couldn't save draft. Try again.") }
    }
    fun back() {
        when (draft.stage) {
            DraftStage.REVIEW -> change(draft.copy(stage = DraftStage.SHEET))
            DraftStage.SHEET -> change(draft.copy(stage = DraftStage.SETUP))
            DraftStage.SETUP -> repository.updateDraft(draft).onSuccess { onClose() }.onFailure { onError(it.message ?: "Couldn't save draft. Try again.") }
        }
    }
    BackHandler(enabled = picker == null && !dial) { back() }
    if (picker != null) {
        LibraryScreen(state, repository, onError, requestedKind = picker, onPicked = { kind, id ->
            change(selectItem(draft, repository.state.value, kind, id))
            picker = null
        }, onClose = { picker = null })
    } else Page(when (draft.stage) { DraftStage.SETUP -> "New brew"; DraftStage.SHEET -> "Brew sheet"; DraftStage.REVIEW -> "Finish brew" }, ::back) {
        when (draft.stage) {
            DraftStage.SETUP -> {
                SetupFields(draft, state, { picker = it }, { dial = true }, ::change)
                PrimaryAction("Use this plan", enabled = ready(draft, state)) { change(draft.copy(stage = DraftStage.SHEET)) }
                TextButton(onClick = { discard = true }, modifier = Modifier.fillMaxWidth()) { Text("Discard brew") }
            }
            DraftStage.SHEET -> {
                PlanSummary(draft.beans!!, draft.grinder!!, draft.setting!!, draft.recipe!!)
                PlanSchedule(draft.recipe)
                PrimaryAction("Finish brew") { change(draft.copy(stage = DraftStage.REVIEW)) }
                OutlinedButton(onClick = { change(draft.copy(stage = DraftStage.SETUP)) }, modifier = Modifier.fillMaxWidth()) { Text("Correct setup") }
            }
            DraftStage.REVIEW -> {
                PlanSummary(draft.beans!!, draft.grinder!!, draft.setting!!, draft.recipe!!)
                DateEntry(dateText, { dateText = it }, { validDate = it }) { change(draft.copy(startedAt = it)) }
                OutlinedButton(onClick = { change(draft.copy(stage = DraftStage.SETUP)) }, modifier = Modifier.fillMaxWidth()) { Text("Correct setup") }
                JournalEditor(draft.journal, draft.beans.expectedNotes) { change(draft.copy(journal = it)) }
                PrimaryAction("Save brew", enabled = validDate) {
                    repository.updateDraft(draft).fold(
                        onSuccess = { repository.saveBrew(draft.id).onSuccess { onSaved(it.id) }.onFailure { onError("Couldn't save brew. Try again.") } },
                        onFailure = { onError("Couldn't save brew. Try again.") },
                    )
                }
            }
        }
    }
    if (dial && draft.grinder != null) GrinderDial(draft.setting, state.grinders.find { it.id == draft.grinder.id }?.lastSetting, state.grinders.find { it.id == draft.grinder.id }?.finish ?: draft.grinder.finish, { change(draft.copy(setting = it)) }, { finish ->
        val grinder = state.grinders.find { it.id == draft.grinder.id }
        if (grinder != null) repository.upsertGrinder(grinder.copy(finish = finish)).onFailure { onError(it.message ?: "Couldn't save finish.") }
        change(draft.copy(grinder = draft.grinder.copy(finish = finish)))
    }, { dial = false })
    if (discard) DiscardDialog({ discard = false }, { repository.discardDraft().onSuccess { onClose() }.onFailure { onError(it.message ?: "Couldn't discard brew.") }; discard = false })
}

private fun selectItem(draft: BrewDraft, state: AppState, kind: String, id: String): BrewDraft = when (kind) {
    "beans" -> draft.copy(beans = state.beans.first { it.id == id }, allowArchivedBeans = false)
    "grinders" -> state.grinders.first { it.id == id }.let { draft.copy(grinder = it, setting = it.lastSetting) }
    else -> draft.copy(recipe = state.recipes.first { it.id == id })
}

private fun ready(draft: BrewDraft, state: AppState): Boolean = draft.beans != null && draft.recipe != null && draft.grinder != null && draft.setting != null && draft.recipe.validationErrors().isEmpty() && state.grinders.any { it.id == draft.grinder.id && !it.archived } && (state.beans.none { it.id == draft.beans.id && it.archived } || draft.allowArchivedBeans)

@Composable
private fun SetupFields(draft: BrewDraft, state: AppState, onPick: (String) -> Unit, onDial: () -> Unit, onChange: (BrewDraft) -> Unit) {
    SelectionCard("Beans", draft.beans?.name ?: "Add beans", draft.beans?.roaster, { onPick("beans") })
    val archivedBeans = draft.beans != null && state.beans.any { it.id == draft.beans.id && it.archived }
    if (archivedBeans && !draft.allowArchivedBeans) Panel {
        Text("These beans are archived.")
        OutlinedButton(onClick = { onChange(draft.copy(allowArchivedBeans = true)) }) { Text("Use anyway") }
        TextButton(onClick = { onPick("beans") }) { Text("Choose other beans") }
    } else if (archivedBeans) Text("Using archived beans", color = MaterialTheme.colorScheme.onSurfaceVariant)
    SelectionCard("Recipe", draft.recipe?.name ?: "Choose or create recipe", null, { onPick("recipes") })
    SelectionCard("Grinder", draft.grinder?.name ?: "Add grinder", null, { onPick("grinders") })
    val activeGrinder = state.grinders.find { it.id == draft.grinder?.id && !it.archived }
    if (draft.grinder != null && activeGrinder == null) Text("This grinder is archived. Choose an active grinder to continue.", color = MaterialTheme.colorScheme.error)
    if (draft.grinder != null) Panel {
        Text("Grind setting, ticks", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(draft.setting?.toString() ?: "Not set", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
            OutlinedButton(onClick = onDial) { Text("Adjust") }
        }
        Text("Last recorded setting: ${activeGrinder?.lastSetting?.let { "$it ticks" } ?: "Not recorded"}", style = MaterialTheme.typography.bodyMedium)
        if (draft.setting != null && activeGrinder?.lastSetting != null && draft.setting != activeGrinder.lastSetting) Text("This brew: ${draft.setting} ticks. Last recorded: ${activeGrinder.lastSetting} ticks.", style = MaterialTheme.typography.bodyMedium)
    }
    draft.recipe?.let { recipe -> RecipeNumbers(recipe) }
}

@Composable
private fun SelectionCard(label: String, value: String, detail: String?, onClick: () -> Unit) {
    Surface(onClick, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Text(value, style = MaterialTheme.typography.titleMedium)
                if (!detail.isNullOrBlank()) Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, "Choose $label")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipeNumbers(recipe: Recipe) {
    Panel {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf("Coffee dose" to "${formatAmount(recipe.dose)} g", "Brew ratio" to "1:${formatAmount(recipe.ratio)}", "Total brew water" to "${formatAmount(recipe.totalWater)} g").forEach { (label, value) -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.titleLarge) } }
        }
    }
}

@Composable
private fun PlanSummary(beans: Beans, grinder: Grinder, setting: Int, recipe: Recipe) {
    Panel {
        Text(beans.name, style = MaterialTheme.typography.titleLarge)
        Text(recipe.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${formatAmount(recipe.dose)} g · 1:${formatAmount(recipe.ratio)} · ${formatAmount(recipe.totalWater)} g", style = MaterialTheme.typography.titleMedium)
        HorizontalDivider()
        Text("${grinder.name} · $setting ticks")
    }
}

@Composable
private fun PlanSchedule(recipe: Recipe) {
    Text("Planned pours", style = MaterialTheme.typography.titleLarge)
    recipe.schedule().forEachIndexed { index, scheduled -> Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(formatDuration(scheduled.startSeconds), style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(scheduled.pulse.label.ifBlank { "Pulse ${index + 1}" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Add ${formatAmount(scheduled.pulse.water)} g", style = MaterialTheme.typography.titleMedium)
                Text("Pour to ${formatAmount(scheduled.cumulativeWater)} g total")
                Text("Pour ${scheduled.pulse.pourSeconds} s" + if (index < recipe.pulses.lastIndex) " · Wait ${scheduled.pulse.waitSeconds} s" else "", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } }
    val end = recipe.schedule().lastOrNull()?.let { it.startSeconds + it.pulse.pourSeconds } ?: 0
    Text("Scheduled pours end at ${formatDuration(end)}. Allow for drawdown.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (recipe.notes.isNotBlank()) Panel { Text("Preparation notes", style = MaterialTheme.typography.titleMedium); Text(recipe.notes) }
}

private val entryFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
private fun dateInput(value: Long) = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(entryFormat)
private fun dateTime(value: Long) = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm"))

@Composable
private fun DateEntry(text: String, onTextChange: (String) -> Unit, onValidity: (Boolean) -> Unit, onChange: (Long) -> Unit) {
    val parsed = runCatching { LocalDateTime.parse(text, entryFormat).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    LaunchedEffect(parsed) { onValidity(parsed != null) }
    OutlinedTextField(text, { input -> onTextChange(input); runCatching { LocalDateTime.parse(input, entryFormat).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()?.let(onChange) }, Modifier.fillMaxWidth(), label = { Text("Brew date and time") }, supportingText = { Text(if (parsed == null) "Use yyyy-MM-dd HH:mm, for example 2026-11-01 08:30." else "yyyy-MM-dd HH:mm") }, isError = parsed == null, singleLine = true)
}

@Composable
private fun RecordDetail(brew: BrewRecord, hasNoteEdit: Boolean, onClose: () -> Unit, onRepeat: () -> Unit, onNotes: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler { onClose() }
    Page("Brew record", onClose) {
        Text(brew.beans.name, style = MaterialTheme.typography.headlineMedium)
        Text(dateTime(brew.startedAt), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!brew.journal.isEmpty) Panel { JournalSummary(brew.journal) }
        PrimaryAction(if (hasNoteEdit) "Resume tasting notes" else if (brew.journal.isEmpty) "Add tasting notes" else "Edit tasting notes", onClick = onNotes)
        OutlinedButton(onClick = onRepeat, modifier = Modifier.fillMaxWidth()) { Text("Repeat") }
        Text("Preparation", style = MaterialTheme.typography.titleLarge)
        PlanSummary(brew.beans, brew.grinder, brew.setting, brew.recipe)
        PlanSchedule(brew.recipe)
        Panel {
            Text("Beans", style = MaterialTheme.typography.titleMedium)
            Text(brew.beans.roaster)
            listOf("Origin" to brew.beans.origin, "Variety / cultivar" to brew.beans.variety, "Processing method" to brew.beans.processing, "Expected tasting notes" to brew.beans.expectedNotes, "Bean notes" to brew.beans.notes, "Grinder reference" to brew.grinder.referenceNotes).filter { it.second.isNotBlank() }.forEach { (label, value) -> Text("$label: $value") }
        }
        OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("Edit brew") }
        TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete brew") }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete brew?") }, text = { Text("This removes only this brew record.") }, confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep brew") } })
}

@Composable
private fun NotesScreen(brew: BrewRecord, pending: Journal?, repository: BrewRepository, onError: (String) -> Unit, onClose: () -> Unit) {
    var encoded by rememberSaveable(brew.id) { mutableStateOf(Json.encodeToString(pending ?: brew.journal)) }
    val journal = remember(encoded) { Json.decodeFromString<Journal>(encoded) }
    var discard by remember { mutableStateOf(false) }
    fun back() { if (journal != brew.journal) discard = true else onClose() }
    BackHandler { back() }
    Page("Tasting notes", ::back) {
        Text(brew.beans.name, style = MaterialTheme.typography.titleMedium)
        JournalEditor(journal, brew.beans.expectedNotes) { updated ->
            encoded = Json.encodeToString(updated)
            repository.saveNoteEdit(brew.id, updated).onFailure { onError(it.message ?: "Couldn't retain note edits. Try again.") }
        }
        PrimaryAction("Save notes") {
            repository.saveNoteEdit(brew.id, journal).fold(onSuccess = { repository.saveNotes(brew.id).onSuccess { onClose() }.onFailure { onError("Couldn't save notes. Try again.") } }, onFailure = { onError("Couldn't save notes. Try again.") })
        }
        OutlinedButton(onClick = { repository.saveNoteEdit(brew.id, journal).onSuccess { onClose() }.onFailure { onError(it.message ?: "Couldn't retain note edits. Try again.") } }, modifier = Modifier.fillMaxWidth()) { Text("Continue later") }
    }
    if (discard) DiscardDialog({ discard = false }, { repository.discardNoteEdit(brew.id).onSuccess { onClose() }.onFailure { onError(it.message ?: "Couldn't discard notes.") }; discard = false })
}

@Composable
private fun EditRecord(brew: BrewRecord, state: AppState, repository: BrewRepository, onError: (String) -> Unit, onClose: () -> Unit) {
    var encoded by rememberSaveable(brew.id) { mutableStateOf(Json.encodeToString(BrewDraft(id = brew.id, startedAt = brew.startedAt, beans = brew.beans, grinder = brew.grinder, setting = brew.setting, recipe = brew.recipe, journal = brew.journal, allowArchivedBeans = true))) }
    val draft = remember(encoded) { Json.decodeFromString<BrewDraft>(encoded) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var settingText by rememberSaveable { mutableStateOf(brew.setting.toString()) }
    var validDate by rememberSaveable { mutableStateOf(true) }
    var dateText by rememberSaveable(brew.id) { mutableStateOf(dateInput(brew.startedAt)) }
    val original = BrewDraft(id = brew.id, startedAt = brew.startedAt, beans = brew.beans, grinder = brew.grinder, setting = brew.setting, recipe = brew.recipe, journal = brew.journal, allowArchivedBeans = true)
    fun back() { if (draft != original || settingText != brew.setting.toString() || dateText != dateInput(brew.startedAt)) discard = true else onClose() }
    BackHandler(enabled = picker == null) { back() }
    if (picker != null) LibraryScreen(state, repository, onError, requestedKind = picker, onPicked = { kind, id ->
        val selected = selectItem(draft, repository.state.value, kind, id)
        encoded = Json.encodeToString(selected)
        if (kind == "grinders") settingText = selected.setting?.toString().orEmpty()
        picker = null
    }, onClose = { picker = null }) else Page("Edit brew", ::back) {
        Text("Corrections apply to this record.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionCard("Beans", draft.beans!!.name, draft.beans.roaster) { picker = "beans" }
        SelectionCard("Recipe", draft.recipe!!.name, null) { picker = "recipes" }
        SelectionCard("Grinder", draft.grinder!!.name, null) { picker = "grinders" }
        OutlinedTextField(settingText, { settingText = it; encoded = Json.encodeToString(draft.copy(setting = it.toIntOrNull())) }, Modifier.fillMaxWidth(), label = { Text("Grind setting, ticks") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = settingText.toIntOrNull() == null, supportingText = { if (settingText.toIntOrNull() == null) Text("Enter whole ticks.") })
        DateEntry(dateText, { dateText = it }, { validDate = it }) { encoded = Json.encodeToString(draft.copy(startedAt = it)) }
        PrimaryAction("Save brew", enabled = draft.setting != null && validDate) {
            repository.updateBrew(brew.copy(startedAt = draft.startedAt, beans = draft.beans, grinder = draft.grinder, setting = draft.setting!!, recipe = draft.recipe)).onSuccess { onClose() }.onFailure { onError(it.message ?: "Couldn't save brew. Try again.") }
        }
    }
    if (discard) DiscardDialog({ discard = false }, onClose)
}

@Composable
private fun AppearanceScreen(appearance: Appearance, onChange: (Appearance) -> Unit, onClose: () -> Unit) {
    BackHandler { onClose() }
    Page("Appearance", onClose) {
        Appearance.entries.forEach { option -> Surface(onClick = { onChange(option) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = option == appearance, onClick = { onChange(option) })
                Text(option.name.lowercase().replaceFirstChar { it.uppercase() }, Modifier.padding(start = 12.dp))
            }
        } }
    }
}

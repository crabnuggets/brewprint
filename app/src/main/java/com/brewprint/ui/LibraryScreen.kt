package com.brewprint.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.brewprint.data.*

@Composable
fun LibraryScreen(
    state: AppState,
    repository: BrewRepository,
    onError: (String) -> Unit,
    requestedKind: String? = null,
    onPicked: ((String, String) -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    onEditingChanged: (Boolean) -> Unit = {},
) {
    var kind by rememberSaveable(requestedKind) { mutableStateOf(requestedKind ?: "") }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var archive by rememberSaveable { mutableStateOf(false) }
    var replacementId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingDialog by rememberSaveable { mutableStateOf(false) }
    var settingText by rememberSaveable { mutableStateOf("") }
    val beans = state.beans.find { it.id == detailId }
    val grinder = state.grinders.find { it.id == detailId }
    val recipe = state.recipes.find { it.id == detailId }
    val closeDetail = { detailId = null; editing = false; Unit }
    fun save(result: Result<Unit>): Boolean {
        result.exceptionOrNull()?.let { onError(it.message ?: "Couldn't save changes. Try again.") }
        return result.isSuccess
    }
    fun saved(id: String) {
        editing = false
        if (onPicked != null) onPicked(kind, id) else detailId = id
    }
    LaunchedEffect(editing) { onEditingChanged(editing) }
    DisposableEffect(Unit) { onDispose { onEditingChanged(false) } }

    if (editing) {
        when (kind) {
            "beans" -> BeansEditor(beans, onSave = { item ->
                save(repository.upsertBeans(item)).also { if (it) saved(item.id) }
            }, onBack = { editing = false })
            "grinders" -> GrinderEditor(grinder, onSave = { item ->
                save(repository.upsertGrinder(item)).also { if (it) saved(item.id) }
            }, onBack = { editing = false })
            "recipes" -> RecipeEditor(recipe, onSave = { item ->
                save(repository.upsertRecipe(item)).also { if (it) saved(item.id) }
            }, onBack = { editing = false })
        }
        return
    }
    if (detailId != null) {
        BackHandler(onBack = closeDetail)
        LibraryPage(when (kind) { "beans" -> beans?.name; "grinders" -> grinder?.name; else -> recipe?.name } ?: "Library", closeDetail) {
            if (beans != null && kind == "beans") {
                DetailLine("Roaster", beans.roaster)
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
                else OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { save(repository.setDefaultGrinder(grinder.id)) }, modifier = Modifier.fillMaxWidth()) { Text("Make default grinder") }
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { settingText = grinder.lastSetting?.toString() ?: ""; settingDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Update last recorded setting") }
            }
            if (recipe != null && kind == "recipes") {
                Text("${formatAmount(recipe.dose)} g · 1:${formatAmount(recipe.ratio)} · ${formatAmount(recipe.totalWater)} g", style = MaterialTheme.typography.titleLarge)
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
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = {
                    val duplicate = recipe.copy(id = newId(), name = "${recipe.name} copy", archived = false)
                    if (save(repository.upsertRecipe(duplicate))) { detailId = duplicate.id; editing = true }
                }, modifier = Modifier.fillMaxWidth()) { Text("Duplicate recipe") }
            }
            Button(shape = MaterialTheme.shapes.small, onClick = { editing = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Edit ${kindLabel(kind)}") }
            TextButton(onClick = { replacementId = null; archive = true }, modifier = Modifier.fillMaxWidth()) { Text("Archive ${kindLabel(kind)}") }
        }
    } else {
        if (onClose != null) BackHandler(onBack = onClose)
        LibraryPage(if (requestedKind == null) "Library" else "Choose ${kindLabel(requestedKind)}", onClose) {
            val kinds = requestedKind?.let(::listOf) ?: listOf("recipes", "beans", "grinders")
            kinds.forEach { currentKind ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(currentKind.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { kind = currentKind; detailId = null; editing = true }) { Text("Add ${kindLabel(currentKind)}") }
                }
                val items: List<Pair<String, Pair<String, String>>> = when (currentKind) {
                    "beans" -> state.beans.filterNot { it.archived }.map { it.id to (it.name to it.roaster) }
                    "grinders" -> state.grinders.filterNot { it.archived }.map { it.id to (it.name to buildString {
                        if (it.id == state.defaultGrinderId) append("Default · ")
                        append(it.lastSetting?.let { setting -> "Last recorded: $setting ticks" } ?: "No recorded setting")
                    }) }
                    else -> state.recipes.filterNot { it.archived }.map { it.id to (it.name to "${formatAmount(it.dose)} g · 1:${formatAmount(it.ratio)} · ${formatAmount(it.totalWater)} g") }
                }
                if (items.isEmpty()) Text("No $currentKind yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                items.forEach { (id, content) ->
                    Surface(onClick = {
                        kind = currentKind
                        if (onPicked != null) onPicked(currentKind, id) else detailId = id
                    }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(content.first, style = MaterialTheme.typography.titleMedium)
                                Text(content.second, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            }
                            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (currentKind == "grinders" && id != state.defaultGrinderId && onPicked == null) TextButton(onClick = { save(repository.setDefaultGrinder(id)) }) { Text("Make ${content.first} default") }
                }
                Spacer(Modifier.height(12.dp))
            }
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
                        OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { replacementId = option.id }, modifier = Modifier.fillMaxWidth()) {
                            Text(option.name + if (replacementId == option.id) " · Selected" else "")
                        }
                    }
                }
            }
        }, confirmButton = {
            TextButton(enabled = !needsReplacement || replacementId != null, onClick = {
                val result = when (kind) {
                    "beans" -> repository.archiveBeans(detailId!!)
                    "grinders" -> repository.archiveGrinder(detailId!!, replacementId)
                    else -> repository.archiveRecipe(detailId!!)
                }
                if (save(result)) { archive = false; detailId = null }
            }) { Text("Archive") }
        }, dismissButton = { TextButton(onClick = { archive = false }) { Text("Keep ${kindLabel(kind)}") } })
    }
    if (settingDialog && grinder != null) AlertDialog(onDismissRequest = { settingDialog = false }, title = { Text("Update last recorded setting") }, text = {
        LibraryField(settingText, { settingText = it }, "Grind setting, ticks", KeyboardType.Number, error = if (parseGrindSetting(settingText) == null) "Enter whole ticks from $MIN_GRIND_SETTING to $MAX_GRIND_SETTING." else null, helper = "$MIN_GRIND_SETTING-$MAX_GRIND_SETTING ticks", required = true)
    }, confirmButton = { TextButton(enabled = parseGrindSetting(settingText) != null, onClick = {
        if (save(repository.updateGrinderSetting(grinder.id, parseGrindSetting(settingText)!!))) settingDialog = false
    }) { Text("Update setting") } }, dismissButton = { TextButton(onClick = { settingDialog = false }) { Text("Cancel") } })
}

@Composable
private fun BeansEditor(initial: Beans?, onSave: (Beans) -> Boolean, onBack: () -> Unit) {
    val original = remember(initial?.id) { initial ?: Beans() }
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
    val back = { if (dirty) discard = true else onBack() }
    BackHandler(onBack = back)
    LibraryPage(if (initial == null) "Add beans" else "Edit beans", back) {
        LibraryField(name, { name = it }, "Name", error = if (name.isBlank()) "Enter a name." else null, required = true)
        LibraryField(roaster, { roaster = it }, "Roaster", error = if (roaster.isBlank()) "Enter a roaster." else null, required = true)
        LibraryField(origin, { origin = it }, "Origin, optional", helper = "Country, region, or farm if known")
        LibraryField(variety, { variety = it }, "Variety / cultivar, optional")
        LibraryField(processing, { processing = it }, "Processing method, optional")
        LibraryField(expectedNotes, { expectedNotes = it }, "Expected tasting notes, optional", multiline = true)
        LibraryField(notes, { notes = it }, "General notes, optional", multiline = true)
        Button(shape = MaterialTheme.shapes.small, enabled = name.isNotBlank() && roaster.isNotBlank(), onClick = {
            if (onSave(original.copy(id = id, name = name.trim(), roaster = roaster.trim(), origin = origin, variety = variety, processing = processing, expectedNotes = expectedNotes, notes = notes))) onBack()
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Save beans") }
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
    BackHandler(onBack = back)
    LibraryPage(if (initial == null) "Add grinder" else "Edit grinder", back) {
        LibraryField(name, { name = it }, "Name", error = if (name.isBlank()) "Enter a grinder name." else null, required = true)
        LibraryField(setting, { setting = it }, "Last recorded setting, ticks, optional", KeyboardType.Number, error = if (setting.isNotBlank() && parseGrindSetting(setting) == null) "Enter whole ticks from $MIN_GRIND_SETTING to $MAX_GRIND_SETTING." else null, helper = "$MIN_GRIND_SETTING-$MAX_GRIND_SETTING ticks")
        LibraryField(reference, { reference = it }, "Reference notes, optional", multiline = true, helper = "Describe your own reference point for counting ticks.")
        Text("Dial finish", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialFinish.entries.forEach { finish -> FilterChip(selected = finishName == finish.name, onClick = { finishName = finish.name }, label = { Text(finishLabel(finish)) }) }
        }
        Button(shape = MaterialTheme.shapes.small, enabled = name.isNotBlank() && (setting.isBlank() || parseGrindSetting(setting) != null), onClick = {
            if (onSave(original.copy(id = id, name = name.trim(), lastSetting = parseGrindSetting(setting), referenceNotes = reference, finish = DialFinish.valueOf(finishName)))) onBack()
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Save grinder") }
    }
    DiscardLibraryDialog(discard, { discard = false }, onBack)
}

@Composable
internal fun LibraryPage(title: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Page(title, back = onBack, content = content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibraryField(value: String, onChange: (String) -> Unit, label: String, keyboard: KeyboardType = KeyboardType.Text, error: String? = null, multiline: Boolean = false, helper: String? = null, required: Boolean = false) {
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
        modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
            if (focus.isFocused) wasFocused = true
            else if (wasFocused) interacted = true
        }.semantics { if (visibleError != null) this.error(visibleError) },
        shape = MaterialTheme.shapes.medium,
        colors = softFieldColors(),
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        isError = visibleError != null,
        supportingText = if (visibleError != null || helper != null) ({ Text(visibleError ?: helper.orEmpty()) }) else null,
    )
}

@Composable
internal fun DiscardLibraryDialog(visible: Boolean, keepEditing: () -> Unit, discard: () -> Unit) {
    if (visible) AlertDialog(onDismissRequest = keepEditing, title = { Text("Discard changes?") }, confirmButton = { TextButton(onClick = discard) { Text("Discard") } }, dismissButton = { TextButton(onClick = keepEditing) { Text("Keep editing") } })
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

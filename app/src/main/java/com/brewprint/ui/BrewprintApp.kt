@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.brewprint.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
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
fun BrewprintApp(state: AppState, repository: BrewRepository, openDraftRequest: Int = 0) {
    var tab by rememberSaveable { mutableStateOf("Brew") }
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    var recordId by rememberSaveable { mutableStateOf<String?>(null) }
    var notesReturn by rememberSaveable { mutableStateOf<String?>(null) }
    var draftReturn by rememberSaveable { mutableStateOf<String?>(null) }
    // True when the draft was just made by New brew or Repeat, so leaving it unchanged discards it.
    var draftFresh by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf(repository.loadError) }
    var libraryEditing by remember { mutableStateOf(false) }
    val onError: (String) -> Unit = { error = it }
    val clock = LocalClock.current
    // The live notification asks for the sheet or the review; show the draft it belongs to.
    LaunchedEffect(openDraftRequest) {
        val draft = state.draft
        if (openDraftRequest > 0 && draft != null) {
            // Going Back to the Brew screen leaves a timed draft on setup; the notification should open its sheet.
            if (draft.timer != null && draft.stage == DraftStage.SETUP) repository.updateDraft(draft.copy(stage = DraftStage.SHEET))
            if (route != "draft") { draftReturn = null; draftFresh = false; route = "draft" }
        }
    }
    state.draft?.let { draft -> if (draft.timer != null && draft.recipe != null && (state.timerSettings.vibrate || state.timerSettings.sound)) StepCue(draft.recipe, draft.timer, state.timerSettings) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val pairScale = rememberScalePairing(onFailed = { error = it }) { scale ->
        repository.setScale(scale).onSuccess { com.brewprint.scale.ScaleLink.sync(context) }.onFailure { error = it.message ?: "Couldn't save the scale." }
    }
    fun resumeDraft() {
        // A brew that was left alone goes straight to the review, where the total can be entered.
        val draft = state.draft
        if (draft?.timer != null && draft.recipe != null && draft.stage != DraftStage.REVIEW && draft.timer.stopped(draft.recipe, clock())) {
            repository.updateDraft(draft.copy(stage = DraftStage.REVIEW)).onFailure { error = it.message ?: "Couldn't open brew." }
        }
        draftReturn = null; draftFresh = false; route = "draft"
    }
    fun openRecord(id: String) { recordId = id; route = "record" }
    fun repeat(id: String) {
        repository.repeatBrew(id).onSuccess { draftReturn = route; draftFresh = true; route = "draft" }.onFailure { error = it.message ?: "Couldn't repeat brew." }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (route == null && !libraryEditing) FloatingNavigation(tab) { tab = it }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when (route) {
                "draft" -> state.draft?.let { draft -> DraftFlow(draft, state, repository, onError, { route = draftReturn }, { openRecord(it) }, pairScale, draftFresh) }
                    ?: LaunchedEffect(Unit) { route = null }
                "record" -> state.brews.find { it.id == recordId }?.let { brew -> RecordDetail(brew, state.noteEdits.any { it.brewId == brew.id }, { route = null }, { repeat(brew.id) }, { notesReturn = "record"; route = "notes" }, { route = "edit" }, { repository.deleteBrew(brew.id).onSuccess { route = null }.onFailure { onError(it.message ?: "Couldn't delete brew.") } }) }
                "notes" -> state.brews.find { it.id == recordId }?.let { brew -> NotesScreen(brew, state.noteEdits.find { it.brewId == brew.id }?.journal, repository, onError) { route = notesReturn } }
                "edit" -> state.brews.find { it.id == recordId }?.let { brew -> EditRecord(brew, state, repository, onError) { route = "record" } }
                "settings" -> SettingsScreen(state.appearance, state.timerSettings, { repository.setAppearance(it).onFailure { failure -> onError(failure.message ?: "Couldn't save appearance.") } }, { repository.setTimerSettings(it).onFailure { failure -> onError(failure.message ?: "Couldn't save settings.") } }, onClose = { route = null }) {
                    ScaleSettings(state.scale, LocalScale.current.live, pairScale) {
                        state.scale?.let { forgetScale(context, it) }
                        repository.setScale(null).onSuccess { com.brewprint.scale.ScaleLink.sync(context) }.onFailure { failure -> onError(failure.message ?: "Couldn't forget the scale.") }
                    }
                }
                else -> when (tab) {
                    "Library" -> LibraryScreen(state, repository, onError, onEditingChanged = { libraryEditing = it })
                    "History" -> HistoryScreen(state.brews) { openRecord(it) }
                    else -> BrewHome(state, { repository.startDraft().onSuccess { draftReturn = null; draftFresh = true; route = "draft" }.onFailure { onError(it.message ?: "Couldn't start brew.") } }, { resumeDraft() }, { openRecord(it) }, { repeat(it) }, { recordId = it; notesReturn = null; route = "notes" }, { route = "settings" })
                }
            }
        }
    }
    if (error != null) AlertDialog(onDismissRequest = { error = null }, title = { Text("Couldn't complete action") }, text = { Text(error.orEmpty()) }, confirmButton = { TextButton(onClick = { error = null }) { Text("Close") } })
}

@Composable
private fun BrewHome(state: AppState, onNew: () -> Unit, onResume: () -> Unit, onRecord: (String) -> Unit, onRepeat: (String) -> Unit, onNotes: (String) -> Unit, onSettings: () -> Unit) {
    Page("Brew", actions = { IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") } }) {
        val draft = state.draft
        if (draft?.timer != null && draft.recipe != null) {
            val view = TimerNow(draft.recipe, draft.timer, rememberNow())
            Panel {
                Text("Brew in progress", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Text(draft.beans?.name ?: draft.recipe.name, style = MaterialTheme.typography.titleLarge)
                Text("${draft.recipe.name} · ${draft.setting} ticks", color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                val brewTime = "Brew time ${formatDuration(view.brewSeconds)}"
                Text(when {
                    view.phase == TimerPhase.STOPPED -> stoppedText(view)
                    view.phase == TimerPhase.FINISHED -> "Finished. $brewTime"
                    view.paused -> "Paused. $brewTime"
                    view.phase == TimerPhase.DRAWDOWN -> "Drawdown. $brewTime"
                    else -> brewTime
                })
                PrimaryAction("Resume brew", onClick = onResume)
            }
        } else if (draft != null) {
            PrimaryAction("Resume brew", onClick = onResume)
            Text("One brew is in progress.", style = MaterialTheme.typography.bodyMedium)
        } else PrimaryAction("New brew", icon = Icons.Outlined.Add, onClick = onNew)
        val grinder = state.grinders.find { it.id == state.defaultGrinderId && !it.archived }
        if (grinder != null) Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = "Last recorded setting, " + (grinder.lastSetting?.let { "$it ticks" } ?: "not recorded") + ", ${grinder.name}" }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Last recorded setting", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(grinder.lastSetting?.toString() ?: "Not recorded", style = if (grinder.lastSetting != null) MaterialTheme.typography.headlineLarge.copy(fontSize = 42.sp, lineHeight = 46.sp) else MaterialTheme.typography.titleLarge)
                        if (grinder.lastSetting != null) Text("ticks", Modifier.padding(bottom = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(grinder.name, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        val recent = state.brews.maxByOrNull { it.startedAt }
        if (recent == null) {
            Column(Modifier.fillMaxWidth().padding(top = 52.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.Coffee, null, Modifier.size(72.dp).background(MaterialTheme.colorScheme.surface, CircleShape).padding(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Record your first brew", style = MaterialTheme.typography.titleLarge)
                Text("Save the beans, recipe, grinder setting, and your tasting notes.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            Text("Most recent brew", style = MaterialTheme.typography.titleMedium)
            Panel {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                        Text(dateTime(recent.startedAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onRecord(recent.id) }, contentPadding = PaddingValues(vertical = 8.dp)) { Text("View brew"); Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp)) }
                }
                Text(recent.beans.name, style = MaterialTheme.typography.titleLarge)
                Text(recent.recipe.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${formatAmount(recent.recipe.dose)} g · 1:${formatAmount(recent.recipe.ratio)} · ${recent.setting} ticks", Modifier.clearAndSetSemantics { contentDescription = "Coffee dose ${formatAmount(recent.recipe.dose)} grams, brew ratio 1 to ${formatAmount(recent.recipe.ratio)}, ${recent.setting} ticks" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SecondaryAction("Repeat", enabled = state.draft == null) { onRepeat(recent.id) }
                TextButton(onClick = { onNotes(recent.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Outlined.EditNote, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(if (state.noteEdits.any { it.brewId == recent.id }) "Resume tasting notes" else if (recent.journal.isEmpty) "Add tasting notes" else "Edit tasting notes") }
            }
        }
        state.noteEdits.filter { it.brewId != recent?.id }.forEach { edit ->
            state.brews.find { it.id == edit.brewId }?.let { brew -> OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { onNotes(brew.id) }, modifier = Modifier.fillMaxWidth()) { Text("Resume tasting notes · ${brew.beans.name}") } }
        }
    }
}

@Composable
private fun HistoryScreen(brews: List<BrewRecord>, onRecord: (String) -> Unit) {
    Page("History") {
        if (brews.isEmpty()) Column(Modifier.fillMaxWidth().padding(top = 52.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.History, null, Modifier.size(64.dp).background(MaterialTheme.colorScheme.surface, CircleShape).padding(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Your saved brews will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        brews.sortedByDescending { it.startedAt }.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(ZoneId.systemDefault()).toLocalDate() }.forEach { (day, records) ->
            Text(when (day) { LocalDate.now() -> "Today"; LocalDate.now().minusDays(1) -> "Yesterday"; else -> day.format(DateTimeFormatter.ofPattern("d MMM uuuu")) }, style = MaterialTheme.typography.titleMedium)
            Surface(shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
                Column {
                    records.forEachIndexed { index, brew ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().clickable { onRecord(brew.id) }.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(Instant.ofEpochMilli(brew.startedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")), Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(brew.beans.name, style = MaterialTheme.typography.titleMedium)
                                Text("${brew.recipe.name} · ${brew.setting} ticks", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(brew.journal.impression.ifBlank { if (brew.journal.isEmpty) "Add tasting notes" else "View tasting notes" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp).align(Alignment.CenterVertically), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DraftFlow(initial: BrewDraft, state: AppState, repository: BrewRepository, onError: (String) -> Unit, onClose: () -> Unit, onSaved: (String) -> Unit, onPairScale: () -> Unit, fresh: Boolean = false) {
    var encoded by rememberSaveable(initial.id) { mutableStateOf(Json.encodeToString(initial)) }
    val draft = remember(encoded) { Json.decodeFromString<BrewDraft>(encoded) }
    // The draft as New brew or Repeat made it; null for a resumed draft, which is kept on Back.
    val opened by rememberSaveable(initial.id) { mutableStateOf(if (fresh) Json.encodeToString(initial) else null) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var dial by rememberSaveable { mutableStateOf(false) }
    var validDate by rememberSaveable { mutableStateOf(true) }
    var dateText by rememberSaveable(initial.id) { mutableStateOf(dateInput(initial.startedAt)) }
    var discard by remember { mutableStateOf(false) }
    var showPlan by rememberSaveable { mutableStateOf(false) }
    val clock = LocalClock.current
    // The live notification and the scale change the timer, stage and scale data behind this screen; follow them.
    LaunchedEffect(initial.timer, initial.stage, initial.scale, initial.scaleStep) {
        if (draft.timer != initial.timer || draft.stage != initial.stage || draft.scale != initial.scale || draft.scaleStep != initial.scaleStep) {
            encoded = Json.encodeToString(draft.copy(timer = initial.timer, stage = initial.stage, scale = initial.scale, scaleStep = initial.scaleStep))
        }
    }
    val view = if (draft.stage == DraftStage.SHEET && draft.timer != null && draft.recipe != null) TimerNow(draft.recipe, draft.timer, rememberNow()) else null
    val scale = LocalScale.current
    // The scale guides this brew while it is paired, connected, and not set aside for this brew.
    val scaleOn = state.scale != null && !draft.withoutScale && scale.live.connected
    // A link that drops after the scale steps began keeps the scale card, saying there is no reading, so the timing mode never changes unannounced.
    val scaleLost = state.scale != null && !draft.withoutScale && !scale.live.connected && draft.scale != null && draft.timer == null && draft.stage == DraftStage.SHEET
    val armed = scaleOn && view == null && draft.stage == DraftStage.SHEET && draft.scale != null && draft.scaleStep == ScaleStep.ARMED
    KeepScreenOn((view?.active == true || armed) && state.timerSettings.keepScreenOn)
    var validTotal by rememberSaveable { mutableStateOf(true) }
    // The scale and the live notification write the timer and scale data directly, and this screen's copy
    // can lag them by a frame. An action keeps their latest values unless it changes them itself.
    val latest = { repository.state.value.draft?.takeIf { it.id == draft.id } ?: draft }
    fun merged(updated: BrewDraft): BrewDraft {
        val newest = latest()
        return updated.copy(
            timer = if (updated.timer == draft.timer) newest.timer else updated.timer,
            scale = if (updated.scale == draft.scale) newest.scale else updated.scale,
            stage = if (updated.stage == draft.stage) newest.stage else updated.stage,
            scaleStep = if (updated.scaleStep == draft.scaleStep) newest.scaleStep else updated.scaleStep,
        )
    }
    fun change(updated: BrewDraft) {
        val next = merged(updated)
        encoded = Json.encodeToString(next)
        repository.updateDraft(next).onFailure { onError(it.message ?: "Couldn't save draft. Try again.") }
    }
    fun timer(action: BrewTimer.(Recipe, Long) -> BrewTimer) {
        val recipe = draft.recipe ?: return
        change(draft.copy(timer = latest().timer?.action(recipe, clock())))
    }
    fun finish() = change(draft.copy(stage = DraftStage.REVIEW, timer = latest().timer?.finish(draft.recipe!!, clock())))
    fun back() {
        when (draft.stage) {
            DraftStage.REVIEW -> { validTotal = true; change(draft.copy(stage = DraftStage.SHEET, timer = draft.timer?.unfinished())) }
            DraftStage.SHEET -> change(draft.copy(stage = DraftStage.SETUP))
            // A setup with nothing chosen, or a new one left as it opened, is not worth resuming: leaving it discards it,
            // so it neither blocks Repeat nor keeps a stale start time. Opening it again costs one tap.
            DraftStage.SETUP -> if (draft.beans == null && draft.recipe == null && draft.grinder == null || opened?.let { Json.decodeFromString<BrewDraft>(it) } == draft) repository.discardDraft().onSuccess { onClose() }.onFailure { onError(it.message ?: "Couldn't discard brew.") }
            else repository.updateDraft(draft).onSuccess { onClose() }.onFailure { onError(it.message ?: "Couldn't save draft. Try again.") }
        }
    }
    // A scale that drops while the coffee is weighed may have lost its tare, so the brewer is tared again on return.
    // Scale data on the brewer step comes only from this reset, so the saved draft itself carries the reconnect note.
    val reconnected = draft.scale != null && draft.scaleStep == ScaleStep.BREWER
    LaunchedEffect(scale.live.connected) {
        if (!scale.live.connected && draft.afterScaleLost() != draft) change(draft.afterScaleLost())
    }
    BackHandler(enabled = picker == null && !dial) { back() }
    if (picker != null) {
        LibraryScreen(state, repository, onError, requestedKind = picker, onPicked = { kind, id ->
            change(selectItem(draft, repository.state.value, kind, id))
            picker = null
        }, onClose = { picker = null })
    } else Page(when (draft.stage) { DraftStage.SETUP -> "New brew"; DraftStage.SHEET -> "Brew sheet"; DraftStage.REVIEW -> "Finish brew" }, ::back, actions = {
        val paired = state.scale
        if (draft.stage == DraftStage.SHEET && paired != null && !draft.withoutScale) { ScaleChip(paired.name, scale.live.connected, reading = !scale.live.silent); Spacer(Modifier.width(8.dp)) }
    }, footer = {
        when (draft.stage) {
            DraftStage.SETUP -> PrimaryAction("Use this plan", enabled = ready(draft, state)) { change(draft.copy(stage = DraftStage.SHEET)) }
            DraftStage.SHEET -> if (view == null && armed) ArmedFooter(
                scale.live,
                // Only a scale seen steady at 0 g since its tare can measure toward the targets; any other start follows the clock.
                onStartNow = { showPlan = false; change(if (scale.live.zeroed) draft.copy(timer = BrewTimer(startedAt = clock(), byWeight = true)) else draft.copy(timer = BrewTimer(startedAt = clock()), scale = null)) },
                onFinishWithout = ::finish,
            ) else if (view == null && (scaleOn || scaleLost)) {
                // While the brewer and coffee are weighed, the card's own step leads; "Brew without the scale" is the way to a clock-only start.
                TextButton(onClick = ::finish, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Finish without timing") }
            } else if (view == null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tap at the first pour.", Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                // Before the scale is armed, the timer runs by the clock alone.
                PrimaryAction("Start timer") { showPlan = false; change(draft.copy(timer = BrewTimer(startedAt = clock()), scale = null)) }
                TextButton(onClick = ::finish, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Finish without timing") }
            } else {
                // The scale started this timer moments ago; Undo puts the sheet back to waiting for the first pour.
                val justStarted = draft.scale != null && view.timer.byWeight && view.step == 0 && clock() - view.timer.startedAt < UNDO_MILLIS
                TimerFooter(view, { timer { recipe, now -> pause(recipe, now) } }, { timer { recipe, now -> resume(recipe, now) } }, { timer { recipe, now -> skip(recipe, now) } }, { timer { _, now -> undone(now) } }, ::finish,
                    onUndoStart = if (justStarted) ({ change(draft.copy(timer = null)) }) else null)
            }
            DraftStage.REVIEW -> PrimaryAction("Save brew", enabled = validDate && validTotal) {
                repository.updateDraft(merged(draft)).fold(
                    onSuccess = { repository.saveBrew(draft.id).onSuccess { onSaved(it.id) }.onFailure { onError("Couldn't save brew. Try again.") } },
                    onFailure = { onError("Couldn't save brew. Try again.") },
                )
            }
        }
    }) {
        when (draft.stage) {
            DraftStage.SETUP -> {
                SetupFields(draft, state, { picker = it }, { dial = true }, ::change)
                if (draft.beans != null || draft.recipe != null || draft.grinder != null) TextButton(onClick = { discard = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Discard brew") }
            }
            DraftStage.SHEET -> if (view == null) {
                PlanSummary(draft.beans!!, draft.grinder!!, draft.setting!!, draft.recipe!!, onCorrect = { change(draft.copy(stage = DraftStage.SETUP)) })
                val offer: @Composable () -> Unit = {
                    if (state.scale == null && !state.scaleOfferDismissed) ScaleOffer(onPairScale) { repository.dismissScaleOffer().onFailure { onError(it.message ?: "Couldn't save settings.") } }
                }
                // On a narrow screen or with large text, the optional offer would push the first pour off screen, so it follows the plan.
                val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                val roomy = configuration.screenWidthDp / configuration.fontScale >= 320
                if (roomy) offer()
                if (scaleOn || scaleLost) {
                    val data = draft.scale ?: ScaleData()
                    ScalePrep(
                        if (draft.scale == null) ScaleStep.BREWER else draft.scaleStep, if (scaleLost) scale.live.copy(silent = true) else scale.live, draft.recipe.dose, draft.recipe.ratio, data.weighedDose, reconnected,
                        onTare = { scale.tare(); if (draft.scale == null || draft.scaleStep == ScaleStep.BREWER) change(draft.copy(scale = data, scaleStep = ScaleStep.COFFEE)) },
                        // The weighed dose sets this brew's plan; the recipe in the Library keeps its own.
                        onUse = { dose -> change(draft.copy(recipe = draft.recipe.scaledTo(dose), scale = data.copy(weighedDose = dose), scaleStep = ScaleStep.ARMED)); scale.tare() },
                        onSkip = { change(draft.copy(scale = data, scaleStep = ScaleStep.ARMED)); scale.tare() },
                        onWithout = { change(draft.copy(withoutScale = true, scale = null, scaleStep = ScaleStep.BREWER)) },
                    )
                }
                PlanSchedule(draft.recipe)
                if (!roomy) offer()
            } else TimerSheet(view, showPlan, { showPlan = it }, scale.live.takeIf { draft.scale != null }, draft.scale)
            DraftStage.REVIEW -> {
                Panel {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(draft.beans!!.name, style = MaterialTheme.typography.titleMedium)
                            Text("${draft.recipe!!.name} · ${draft.setting} ticks", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onClick = { change(draft.copy(stage = DraftStage.SETUP)) }, modifier = Modifier.align(Alignment.CenterVertically).heightIn(min = 48.dp), contentPadding = PaddingValues(vertical = 8.dp)) { Text("Change setup") }
                    }
                }
                DateEntry(dateText, { dateText = it }, { validDate = it }) { change(draft.copy(startedAt = it)) }
                draft.timer?.let { timer ->
                    TotalBrewTime(timer, { validTotal = it }) { change(draft.copy(timer = timer.copy(totalOverride = it))) }
                    draft.scale?.let { data ->
                        LiftOffer(timer, data) { change(draft.copy(timer = timer.copy(totalOverride = it))) }
                        Panel { MeasuredRows(draft.recipe!!, data) }
                    }
                }
                JournalEditor(draft.journal, draft.beans!!.expectedNotes) { change(draft.copy(journal = it)) }
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
    "beans" -> state.beans.first { it.id == id }.let { beans ->
        // Other coffee after the scale steps began: the tare and any weighed dose belong to the old coffee,
        // so the steps start again and the plan goes back to the recipe's own dose.
        val restart = beans.id != draft.beans?.id && draft.timer == null && draft.scale != null
        val recipe = if (restart && draft.scale?.weighedDose != null) state.recipes.firstOrNull { it.id == draft.recipe?.id } ?: draft.recipe else draft.recipe
        draft.copy(beans = beans, allowArchivedBeans = false, recipe = recipe).let { if (restart) it.copy(scale = null, scaleStep = ScaleStep.BREWER) else it }
    }
    "grinders" -> state.grinders.first { it.id == id }.let { draft.copy(grinder = it, setting = it.lastSetting) }
    // The timer and any weighed dose follow one recipe; choosing another clears them and starts the scale steps again.
    else -> state.recipes.first { it.id == id }.let { recipe ->
        if (recipe == draft.recipe) draft else draft.copy(recipe = recipe, timer = null, scale = null, scaleStep = ScaleStep.BREWER)
    }
}

internal fun ready(draft: BrewDraft, state: AppState): Boolean = draft.beans != null && draft.recipe != null && draft.grinder != null && draft.setting?.let { parseGrindSetting(it.toString()) } != null && draft.recipe.validationErrors().isEmpty() && state.grinders.any { it.id == draft.grinder.id && !it.archived } && (state.beans.none { it.id == draft.beans.id && it.archived } || draft.allowArchivedBeans)

@Composable
private fun SetupFields(draft: BrewDraft, state: AppState, onPick: (String) -> Unit, onDial: () -> Unit, onChange: (BrewDraft) -> Unit) {
    SelectionCard("Beans", draft.beans?.name ?: if (state.beans.none { !it.archived }) "Add beans" else "Choose beans", draft.beans?.roaster, { onPick("beans") })
    val archivedBeans = draft.beans != null && state.beans.any { it.id == draft.beans.id && it.archived }
    if (archivedBeans && !draft.allowArchivedBeans) Panel {
        Text("These beans are archived.")
        OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { onChange(draft.copy(allowArchivedBeans = true)) }) { Text("Use anyway") }
        TextButton(onClick = { onPick("beans") }) { Text("Choose other beans") }
    } else if (archivedBeans) Text("Using archived beans", color = MaterialTheme.colorScheme.onSurfaceVariant)
    SelectionCard("Recipe", draft.recipe?.name ?: if (state.recipes.none { !it.archived }) "Add recipe" else "Choose recipe", null, { onPick("recipes") })
    SelectionCard("Grinder", draft.grinder?.name ?: if (state.grinders.none { !it.archived }) "Add grinder" else "Choose grinder", null, { onPick("grinders") })
    val activeGrinder = state.grinders.find { it.id == draft.grinder?.id && !it.archived }
    if (draft.grinder != null && activeGrinder == null) Text("This grinder is archived. Choose an active grinder to continue.", color = MaterialTheme.colorScheme.error)
    if (draft.grinder != null) Panel {
        // The label and the selected value are spoken together, apart from the recorded setting below.
        Text("Grind setting, ticks", Modifier.clearAndSetSemantics { }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(draft.setting?.toString() ?: "Not set", Modifier.weight(1f).clearAndSetSemantics { contentDescription = "Grind setting, " + (draft.setting?.let { "$it ticks" } ?: "not set") }, style = MaterialTheme.typography.headlineLarge)
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onDial) { Text("Adjust") }
        }
        val recorded = activeGrinder?.lastSetting
        // A repeated brew can carry an older setting; compare the two once and offer the recorded one in a tap.
        if (draft.setting != null && recorded != null && draft.setting != recorded) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Last recorded: $recorded ticks", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onChange(draft.copy(setting = recorded)) }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(vertical = 8.dp)) { Text("Use $recorded ticks") }
        } else Text("Last recorded setting: ${recorded?.let { "$it ticks" } ?: "Not recorded"}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
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
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipeNumbers(recipe: Recipe) {
    Panel {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Each metric is one spoken node, with the ratio read as "1 to 16".
            listOf(Triple("Coffee dose", "${formatAmount(recipe.dose)} g", "Coffee dose ${formatAmount(recipe.dose)} grams"), Triple("Brew ratio", "1:${formatAmount(recipe.ratio)}", "Brew ratio 1 to ${formatAmount(recipe.ratio)}"), Triple("Total brew water", "${formatAmount(recipe.totalWater)} g", "Total brew water ${formatAmount(recipe.totalWater)} grams")).forEach { (label, value, spoken) -> Column(Modifier.clearAndSetSemantics { contentDescription = spoken }, verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.titleLarge) } }
        }
    }
}

@Composable
private fun PlanSummary(beans: Beans, grinder: Grinder, setting: Int, recipe: Recipe, onCorrect: (() -> Unit)? = null) {
    Panel {
        Text(beans.name, style = MaterialTheme.typography.titleLarge)
        Text(recipe.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val dose = formatAmount(recipe.dose)
            val ratio = formatAmount(recipe.ratio)
            val water = formatAmount(recipe.totalWater)
            val spoken = "Coffee dose $dose grams, brew ratio 1 to $ratio, total brew water $water grams. ${grinder.name}, $setting ticks."
            Column(Modifier.align(Alignment.CenterVertically).clearAndSetSemantics { contentDescription = spoken }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("$dose g · 1:$ratio · $water g", style = MaterialTheme.typography.titleMedium)
                Text("${grinder.name} · $setting ticks", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            if (onCorrect != null) TextButton(onClick = onCorrect, modifier = Modifier.align(Alignment.CenterVertically).heightIn(min = 48.dp), contentPadding = PaddingValues(vertical = 8.dp)) { Text("Change setup") }
        }
    }
}

/** The planned pours in the same row format as the running timer: start time, then what to pour, then the detail. */
@Composable
private fun PlanSchedule(recipe: Recipe) {
    val colors = MaterialTheme.colorScheme
    if (recipe.notes.isNotBlank()) Panel { Text("Preparation notes", style = MaterialTheme.typography.titleMedium); Text(recipe.notes) }
    Text("Planned pours", style = MaterialTheme.typography.titleMedium)
    // The time and dot sit on the title's first line at any text size.
    val line = with(androidx.compose.ui.platform.LocalDensity.current) { MaterialTheme.typography.titleMedium.lineHeight.toDp() }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        recipe.schedule().forEachIndexed { index, scheduled ->
            val name = scheduled.pulse.label.ifBlank { "Pulse ${index + 1}" }
            val added = formatAmount(scheduled.pulse.water)
            val total = formatAmount(scheduled.cumulativeWater)
            val wait = scheduled.pulse.waitSeconds.takeIf { index < recipe.pulses.lastIndex }
            // The first pulse's total is its own amount, so it is not repeated.
            val detail = listOfNotNull("To $total g total".takeIf { index > 0 }, "Pour ${scheduled.pulse.pourSeconds} s" + (wait?.let { ", wait $it s" } ?: "")).joinToString(". ")
            val spoken = "$name, ${spokenStart(scheduled.startSeconds)}. Add $added grams" + (if (index > 0) ", pour to $total grams total" else "") +
                ". Pour for ${scheduled.pulse.pourSeconds} seconds" + (wait?.let { ", then wait $it seconds" } ?: "") + "."
            Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.widthIn(min = 44.dp).height(line), contentAlignment = Alignment.CenterStart) {
                    Text(formatDuration(scheduled.startSeconds), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                Box(Modifier.height(line), contentAlignment = Alignment.Center) { Box(Modifier.size(9.dp).border(1.5.dp, colors.outline, CircleShape)) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("$name, add $added g", style = MaterialTheme.typography.titleMedium)
                    Text(detail, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    val end = recipe.schedule().lastOrNull()?.let { it.startSeconds + it.pulse.pourSeconds } ?: 0
    Text("Scheduled pours end at ${formatDuration(end)}. Allow for drawdown.", Modifier.clearAndSetSemantics { contentDescription = "Scheduled pours end ${spokenStart(end).removePrefix("starts ")}. Allow for drawdown." }, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

private val entryFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
private fun dateInput(value: Long) = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(entryFormat)
private fun dateTime(value: Long): String {
    val date = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault())
    val day = when (date.toLocalDate()) {
        LocalDate.now() -> "Today"
        LocalDate.now().minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("d MMM uuuu"))
    }
    return "$day, ${date.format(DateTimeFormatter.ofPattern("HH:mm"))}"
}

@Composable
private fun DateEntry(text: String, onTextChange: (String) -> Unit, onValidity: (Boolean) -> Unit, onChange: (Long) -> Unit) {
    val parsed = runCatching { LocalDateTime.parse(text, entryFormat).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    var editing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(parsed) { onValidity(parsed != null) }
    if (editing || parsed == null) {
        LibraryField(text, { input -> onTextChange(input); runCatching { LocalDateTime.parse(input, entryFormat).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()?.let(onChange) }, "Brew date and time", error = if (parsed == null) "Use yyyy-MM-dd HH:mm, for example 2026-11-01 08:30." else null, helper = "yyyy-MM-dd HH:mm")
    } else Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.CalendarToday, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(dateTime(parsed), style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { editing = true }, contentPadding = PaddingValues(vertical = 8.dp)) { Text("Change date") }
        }
    }
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
        SecondaryAction("Repeat", onClick = onRepeat)
        Text("Preparation", style = MaterialTheme.typography.titleLarge)
        PlanSummary(brew.beans, brew.grinder, brew.setting, brew.recipe)
        brew.timing?.let { TimingPanel(brew.recipe, it, brew.scale) }
        PlanSchedule(brew.recipe)
        Panel {
            Text("Beans", style = MaterialTheme.typography.titleMedium)
            Text(brew.beans.roaster)
            listOf("Origin" to brew.beans.origin, "Variety / cultivar" to brew.beans.variety, "Processing method" to brew.beans.processing, "Expected tasting notes" to brew.beans.expectedNotes, "Bean notes" to brew.beans.notes, "Grinder reference" to brew.grinder.referenceNotes).filter { it.second.isNotBlank() }.forEach { (label, value) -> Text("$label: $value") }
        }
        SecondaryAction("Edit brew", onClick = onEdit)
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
    Page("Tasting notes", ::back, footer = {
        PrimaryAction("Save notes") {
            repository.saveNoteEdit(brew.id, journal).fold(onSuccess = { repository.saveNotes(brew.id).onSuccess { onClose() }.onFailure { onError("Couldn't save notes. Try again.") } }, onFailure = { onError("Couldn't save notes. Try again.") })
        }
    }) {
        Text(brew.beans.name, style = MaterialTheme.typography.titleMedium)
        JournalEditor(journal, brew.beans.expectedNotes) { updated ->
            encoded = Json.encodeToString(updated)
            repository.saveNoteEdit(brew.id, updated).onFailure { onError(it.message ?: "Couldn't retain note edits. Try again.") }
        }
        SecondaryAction("Continue later") { repository.saveNoteEdit(brew.id, journal).onSuccess { onClose() }.onFailure { onError(it.message ?: "Couldn't retain note edits. Try again.") } }
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
        LibraryField(settingText, { settingText = it; encoded = Json.encodeToString(draft.copy(setting = parseGrindSetting(it))) }, "Grind setting, ticks", KeyboardType.Number, error = if (parseGrindSetting(settingText) == null) "Enter whole ticks from $MIN_GRIND_SETTING to $MAX_GRIND_SETTING." else null, helper = "$MIN_GRIND_SETTING-$MAX_GRIND_SETTING ticks", required = true)
        DateEntry(dateText, { dateText = it }, { validDate = it }) { encoded = Json.encodeToString(draft.copy(startedAt = it)) }
        PrimaryAction("Save brew", enabled = parseGrindSetting(settingText) != null && validDate) {
            repository.updateBrew(brew.copy(startedAt = draft.startedAt, beans = draft.beans, grinder = draft.grinder, setting = draft.setting!!, recipe = draft.recipe)).onSuccess { onClose() }.onFailure { onError(it.message ?: "Couldn't save brew. Try again.") }
        }
    }
    if (discard) DiscardDialog({ discard = false }, onClose)
}


/** A planned start read as elapsed time, since "0:40" is not spoken as a time into the brew. */
internal fun spokenStart(seconds: Int): String {
    if (seconds == 0) return "starts at the first pour"
    val minutes = seconds / 60
    val rest = seconds % 60
    val parts = listOfNotNull(minutes.takeIf { it > 0 }?.let { if (it == 1) "1 minute" else "$it minutes" }, rest.takeIf { it > 0 }?.let { if (it == 1) "1 second" else "$it seconds" })
    return "starts ${parts.joinToString(" ")} into the brew"
}

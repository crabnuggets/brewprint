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
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.focus.focusRequester

/** How deep a screen sits: destinations, then screens opened from them, then screens opened from those. */
private fun screenDepth(screen: String) = when (screen) {
    "notes", "edit" -> 2
    "draft", "record", "settings" -> 1
    else -> 0
}

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
    val chrome = remember { Chrome() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // A deleted brew is hidden at once and removed from storage only when the Undo window ends.
    val pendingDeletes = rememberSaveable(saver = androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<String>, String>({ it.toList() }, { it.toMutableStateList() })) { mutableStateListOf() }
    var undoDeadline by rememberSaveable { mutableStateOf(0L) }
    var undoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val accessibility = androidx.compose.ui.platform.LocalAccessibilityManager.current
    fun offerUndo() {
        undoJob?.cancel()
        undoJob = scope.launch {
            val wait = undoDeadline - clock()
            val message = if (pendingDeletes.size == 1) "Brew deleted" else "${pendingDeletes.size} brews deleted"
            val result = if (wait > 0) withTimeoutOrNull(wait) { snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Indefinite) } else null
            if (result != SnackbarResult.ActionPerformed) pendingDeletes.forEach { brew -> repository.deleteBrew(brew).onFailure { onError(it.message ?: "Couldn't delete brew.") } }
            pendingDeletes.clear()
        }
    }
    fun delete(id: String) {
        if (route == "record") route = null
        pendingDeletes += id
        undoDeadline = clock() + (accessibility?.calculateRecommendedTimeoutMillis(UNDO_MILLIS, containsIcons = false, containsText = true, containsControls = true) ?: UNDO_MILLIS)
        offerUndo()
    }
    // After recreation, a window still open is offered again for the time left; one that has ended is written.
    LaunchedEffect(Unit) { if (pendingDeletes.isNotEmpty()) offerUndo() }
    val shown = if (pendingDeletes.isEmpty()) state else state.copy(brews = state.brews.filterNot { it.id in pendingDeletes })
    val navVisible = route == null && !libraryEditing
    chrome.navSpace = if (navVisible) 78.dp else 0.dp
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
          CompositionLocalProvider(LocalChrome provides chrome) {
           // Opening a screen slides it in a short way while it fades; Back reverses it. Destinations cross-fade.
           AnimatedContent(route ?: "tab:$tab", label = "screen", transitionSpec = {
               val from = screenDepth(initialState)
               val to = screenDepth(targetState)
               when {
                   to == from -> fadeIn(tween(300, delayMillis = 60)) togetherWith fadeOut(tween(160))
                   to > from -> (slideInHorizontally(tween(340)) { it / 8 } + fadeIn(tween(300))) togetherWith (slideOutHorizontally(tween(340)) { -it / 16 } + fadeOut(tween(200)))
                   else -> (slideInHorizontally(tween(340)) { -it / 16 } + fadeIn(tween(300))) togetherWith (slideOutHorizontally(tween(340)) { it / 8 } + fadeOut(tween(200)))
               }
           }) { screen ->
            val leaving = transition.targetState == androidx.compose.animation.EnterExitState.PostExit
            Box(Modifier.fillMaxSize().inert(leaving)) { CompositionLocalProvider(LocalScreenActive provides !leaving) {
            when (screen) {
                "draft" -> state.draft?.let { draft -> DraftFlow(draft, state, repository, onError, { route = draftReturn }, { openRecord(it) }, pairScale, draftFresh) }
                    // Only the screen being opened may close itself; the one animating out after a save has no draft by design.
                    ?: if (route == "draft") LaunchedEffect(Unit) { route = null } else Unit
                "record" -> state.brews.find { it.id == recordId }?.let { brew -> RecordDetail(brew, state.noteEdits.any { it.brewId == brew.id }, { route = null }, { repeat(brew.id) }, { notesReturn = "record"; route = "notes" }, { route = "edit" }, { delete(brew.id) }) }
                "notes" -> state.brews.find { it.id == recordId }?.let { brew -> NotesScreen(brew, state.noteEdits.find { it.brewId == brew.id }?.journal, repository, onError) { route = notesReturn } }
                "edit" -> state.brews.find { it.id == recordId }?.let { brew -> EditRecord(brew, state, repository, onError) { route = "record" } }
                "settings" -> SettingsScreen(state.appearance, state.timerSettings, { repository.setAppearance(it).onFailure { failure -> onError(failure.message ?: "Couldn't save appearance.") } }, { repository.setTimerSettings(it).onFailure { failure -> onError(failure.message ?: "Couldn't save settings.") } }, onClose = { route = null }, account = { AccountSettings(onError) },
                    experiments = state.experiments, onExperiments = { repository.setExperiments(it).onFailure { failure -> onError(failure.message ?: "Couldn't save settings.") } }) {
                    ScaleSettings(state.scale, LocalScale.current.live, pairScale) {
                        state.scale?.let { forgetScale(context, it) }
                        repository.setScale(null).onSuccess { com.brewprint.scale.ScaleLink.sync(context) }.onFailure { failure -> onError(failure.message ?: "Couldn't forget the scale.") }
                    }
                }
                else -> when (screen.removePrefix("tab:")) {
                    "Library" -> LibraryScreen(state, repository, onError, onEditingChanged = { libraryEditing = it })
                    "History" -> HistoryScreen(shown.brews, state.draft == null, { openRecord(it) }, { repeat(it) }, { delete(it) })
                    else -> BrewHome(shown, { repository.startDraft().onSuccess { draftReturn = null; draftFresh = true; route = "draft" }.onFailure { onError(it.message ?: "Couldn't start brew.") } }, { resumeDraft() }, { openRecord(it) }, { repeat(it) }, { recordId = it; notesReturn = null; route = "notes" }, { route = "settings" }, { tab = "Library" })
                }
            }
            } }
           }
          }
            val navShown = navVisible && (chrome.navShown || rememberTouchExploration())
            if (navVisible) FloatingNavigation(tab, navShown, Modifier.align(Alignment.BottomCenter)) { tab = it }
            // The Undo bar moves with the navigation instead of jumping.
            val lift by androidx.compose.animation.core.animateDpAsState(if (navShown) 78.dp else if (route != null) chrome.footerSpace + 8.dp else 8.dp, tween(280), label = "snackbar")
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = lift).padding(horizontal = 8.dp)) {
                // Monochrome: the action reads in the bar's own text color, set apart by weight.
                Snackbar(it, shape = MaterialTheme.shapes.small, actionColor = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
    }
    if (error != null) AlertDialog(onDismissRequest = { error = null }, title = { Text("Couldn't complete action") }, text = { Text(error.orEmpty()) }, confirmButton = { SoftTextButton(onClick = { error = null }) { Text("Close") } })
}

@Composable
private fun BrewHome(state: AppState, onNew: () -> Unit, onResume: () -> Unit, onRecord: (String) -> Unit, onRepeat: (String) -> Unit, onNotes: (String) -> Unit, onSettings: () -> Unit, onDrafts: () -> Unit) {
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
        val drafts = state.libraryDrafts.size
        if (drafts > 0) SoftButton(onClick = onDrafts, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(if (drafts == 1) "Review 1 draft from Claude" else "Review $drafts drafts from Claude")
        }
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
            SectionTitle("Most recent brew")
            val look = LocalLook.current
            Taped { Panel {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                        Text(dateTime(recent.startedAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SoftTextButton(onClick = { onRecord(recent.id) }) { Text("View brew"); Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp)) }
                }
                Text(recent.beans.name, style = MaterialTheme.typography.titleLarge)
                if (look.blueprint) {
                    // The user's own notes stay in pencil above the setup; the setup moves to the strip below.
                    if (recent.journal.impression.isNotBlank()) Text(recent.journal.impression, maxLines = 3, overflow = TextOverflow.Ellipsis, style = look.hand(MaterialTheme.typography.bodyLarge))
                    if (recent.journal.nextTime.isNotBlank()) Text("Next time: ${recent.journal.nextTime}", maxLines = 2, overflow = TextOverflow.Ellipsis, color = look.accent, style = look.hand(MaterialTheme.typography.bodyLarge))
                    Spacer(Modifier.height(4.dp))
                } else {
                    Text(recent.recipe.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${formatAmount(recent.recipe.dose)} g · 1:${formatAmount(recent.recipe.ratio)} · ${recent.setting} ticks", Modifier.clearAndSetSemantics { contentDescription = "Coffee dose ${formatAmount(recent.recipe.dose)} grams, brew ratio 1 to ${formatAmount(recent.recipe.ratio)}, ${recent.setting} ticks" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
                SecondaryAction("Repeat", enabled = state.draft == null, icon = Icons.Outlined.Repeat) { onRepeat(recent.id) }
                SoftButton(onClick = { onNotes(recent.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Icon(Icons.Outlined.EditNote, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(if (state.noteEdits.any { it.brewId == recent.id }) "Resume tasting notes" else if (recent.journal.isEmpty) "Add tasting notes" else "Edit tasting notes") }
                if (look.blueprint) Column(Modifier.padding(top = 6.dp).bleed(20.dp)) {
                    DoubleRule()
                    CellGrid(stripCells(recent.recipe, recent.setting), columns = 4, weights = listOf(1.35f, .9f, .8f, 1f))
                }
            } }
        }
        state.noteEdits.filter { it.brewId != recent?.id }.forEach { edit ->
            state.brews.find { it.id == edit.brewId }?.let { brew -> SoftButton(onClick = { onNotes(brew.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Resume tasting notes · ${brew.beans.name}") } }
        }
    }
}

@Composable
private fun HistoryScreen(brews: List<BrewRecord>, canRepeat: Boolean, onRecord: (String) -> Unit, onRepeat: (String) -> Unit, onDelete: (String) -> Unit) {
    Page("History") {
        if (brews.isEmpty()) Column(Modifier.fillMaxWidth().padding(top = 52.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.History, null, Modifier.size(64.dp).background(MaterialTheme.colorScheme.surface, CircleShape).padding(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Your saved brews will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        brews.sortedByDescending { it.startedAt }.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(ZoneId.systemDefault()).toLocalDate() }.forEach { (day, records) ->
            Text(when (day) { LocalDate.now() -> "Today"; LocalDate.now().minusDays(1) -> "Yesterday"; else -> day.format(DateTimeFormatter.ofPattern("d MMM uuuu")) }, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Surface(shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.animateContentSize(tween(300))) {
                    records.forEachIndexed { index, brew ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        key(brew.id) { HistoryRow(brew, canRepeat, { onRecord(brew.id) }, { onRepeat(brew.id) }, { onDelete(brew.id) }) }
                    }
                }
            }
        }
    }
}

/** A haptic as a swipe crosses the point where letting go acts ([armed]), and a lighter one as it pulls back. */
private fun android.view.View.thresholdHaptic(armed: Boolean) {
    performHapticFeedback(when {
        android.os.Build.VERSION.SDK_INT >= 34 -> if (armed) android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
        !armed -> android.view.HapticFeedbackConstants.CLOCK_TICK
        android.os.Build.VERSION.SDK_INT >= 30 -> android.view.HapticFeedbackConstants.CONFIRM
        else -> android.view.HapticFeedbackConstants.LONG_PRESS
    })
}

/**
 * Swipe left deletes (with Undo), swipe right repeats. Screen readers get both as actions on the row.
 * Before the threshold the action shows in soft gray; past it, with a haptic, delete turns red and repeat a solid fill.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryRow(brew: BrewRecord, canRepeat: Boolean, onOpen: () -> Unit, onRepeat: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val view = androidx.compose.ui.platform.LocalView.current
    // Repeat lets the row spring back; that return is not the user pulling back, so it has no haptic.
    var repeated by remember { mutableStateOf(false) }
    val swipe = rememberSwipeToDismissBoxState(positionalThreshold = { it * .4f }, confirmValueChange = { value ->
        when (value) {
            SwipeToDismissBoxValue.EndToStart -> { onDelete(); true }
            // Repeat opens a new brew; the row springs back.
            SwipeToDismissBoxValue.StartToEnd -> { repeated = true; onRepeat(); false }
            SwipeToDismissBoxValue.Settled -> true
        }
    })
    LaunchedEffect(swipe) {
        var last = swipe.targetValue
        snapshotFlow { swipe.targetValue }.collect { target ->
            if (target != last) {
                if (target != SwipeToDismissBoxValue.Settled) view.thresholdHaptic(armed = true)
                else if (!repeated) view.thresholdHaptic(armed = false)
                repeated = false
                last = target
            }
        }
    }
    SwipeToDismissBox(swipe, enableDismissFromStartToEnd = canRepeat, backgroundContent = {
        val deleting = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
        val repeating = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
        val armed = swipe.targetValue != SwipeToDismissBoxValue.Settled && swipe.targetValue == swipe.dismissDirection
        val fade = tween<androidx.compose.ui.graphics.Color>(120)
        val fill by androidx.compose.animation.animateColorAsState(when {
            armed && deleting -> colors.error
            armed && repeating -> colors.primary
            deleting || repeating -> colors.soft
            else -> androidx.compose.ui.graphics.Color.Transparent
        }, fade, label = "swipe-fill")
        val ink by androidx.compose.animation.animateColorAsState(when {
            armed && deleting -> colors.onError
            armed && repeating -> colors.onPrimary
            else -> colors.onSurfaceVariant
        }, fade, label = "swipe-ink")
        val grow by androidx.compose.animation.core.animateFloatAsState(if (armed) 1.15f else 1f, tween(120), label = "swipe-icon")
        val icon = Modifier.graphicsLayer { scaleX = grow; scaleY = grow }
        Row(
            Modifier.fillMaxSize().background(fill).padding(horizontal = 22.dp),
            horizontalArrangement = if (deleting) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.CenterVertically,
        ) {
            if (deleting) { Text("Delete", color = ink, style = MaterialTheme.typography.labelLarge); Spacer(Modifier.width(8.dp)); Icon(Icons.Outlined.Delete, null, icon, tint = ink) }
            if (repeating) { Icon(Icons.Outlined.Repeat, null, icon, tint = ink); Spacer(Modifier.width(8.dp)); Text("Repeat", color = ink, style = MaterialTheme.typography.labelLarge) }
        }
    }) {
        Row(
            Modifier.fillMaxWidth().background(colors.surface).clickable(onClickLabel = "Open brew") { onOpen() }
                .semantics { customActions = listOfNotNull(CustomAccessibilityAction("Repeat") { onRepeat(); true }.takeIf { canRepeat }, CustomAccessibilityAction("Delete") { onDelete(); true }) }
                .padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(Instant.ofEpochMilli(brew.startedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")), Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(brew.beans.name, style = MaterialTheme.typography.titleMedium)
                Text("${brew.recipe.name} · ${brew.setting} ticks", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Text(brew.journal.impression.ifBlank { if (brew.journal.isEmpty) "Add tasting notes" else "View tasting notes" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = if (brew.journal.impression.isBlank()) MaterialTheme.typography.bodyMedium else LocalLook.current.hand(MaterialTheme.typography.bodyMedium), color = if (brew.journal.impression.isBlank()) colors.onSurfaceVariant else colors.onSurface)
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp).align(Alignment.CenterVertically), tint = colors.onSurfaceVariant)
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
    // A finer tick while a timer exists keeps the step swap close to the step cue.
    val timerView = if (draft.timer != null && draft.recipe != null) TimerNow(draft.recipe, draft.timer, rememberNow(100)) else null
    val view = timerView.takeIf { draft.stage == DraftStage.SHEET }
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
            // Back from the full plan returns to the current step, not to setup.
            DraftStage.SHEET -> if (showPlan && timerView != null) showPlan = false else change(draft.copy(stage = DraftStage.SETUP))
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
    BackHandler(enabled = LocalScreenActive.current && picker == null && !dial) { back() }
    if (picker != null) {
        LibraryScreen(state, repository, onError, requestedKind = picker, onPicked = { kind, id ->
            change(selectItem(draft, repository.state.value, kind, id))
            picker = null
        }, onClose = { picker = null })
    } else AnimatedContent(draft.stage, label = "stage", transitionSpec = {
        if (targetState > initialState) (slideInHorizontally(tween(340)) { it / 8 } + fadeIn(tween(300))) togetherWith (slideOutHorizontally(tween(340)) { -it / 16 } + fadeOut(tween(200)))
        else (slideInHorizontally(tween(340)) { -it / 16 } + fadeIn(tween(300))) togetherWith (slideOutHorizontally(tween(340)) { it / 8 } + fadeOut(tween(200)))
    }) { stage ->
    // A stage animating out keeps drawing itself, so the sheet uses its own timer view rather than the current stage's.
    val lastSheet = remember { mutableStateOf(timerView) }
    SideEffect { if (stage == draft.stage) lastSheet.value = timerView }
    val stageView = (if (stage == draft.stage) timerView else lastSheet.value).takeIf { stage == DraftStage.SHEET }
    Box(Modifier.fillMaxSize().inert(transition.targetState == androidx.compose.animation.EnterExitState.PostExit)) {
    // Opening or closing the full plan starts the sheet from the top, so the focus card is in view.
    Page(when (stage) { DraftStage.SETUP -> "New brew"; DraftStage.SHEET -> "Brew sheet"; DraftStage.REVIEW -> "Finish brew" }, ::back, pinHeader = stageView != null, scrollKey = showPlan.takeIf { stage == DraftStage.SHEET }, actions = {
        val paired = state.scale
        if (stage == DraftStage.SHEET && paired != null && !draft.withoutScale) { ScaleChip(paired.name, scale.live.connected, reading = !scale.live.silent); Spacer(Modifier.width(8.dp)) }
        val discardable = stage == DraftStage.REVIEW || stage == DraftStage.SETUP && (draft.beans != null || draft.recipe != null || draft.grinder != null)
        if (discardable) IconAction(Icons.Outlined.Delete, "Discard brew", danger = true) { discard = true }
    }, footer = {
        when (stage) {
            DraftStage.SETUP -> PrimaryAction("Use this plan", enabled = ready(draft, state)) { change(draft.copy(stage = DraftStage.SHEET)) }
            DraftStage.SHEET -> if (stageView == null && armed) ArmedFooter(
                scale.live,
                // Only a scale seen steady at 0 g since its tare can measure toward the targets; any other start follows the clock.
                onStartNow = { showPlan = false; change(if (scale.live.zeroed) draft.copy(timer = BrewTimer(startedAt = clock(), byWeight = true)) else draft.copy(timer = BrewTimer(startedAt = clock()), scale = null)) },
                onFinishWithout = ::finish,
            ) else if (stageView == null && (scaleOn || scaleLost)) {
                // While the brewer and coffee are weighed, the card's own step leads; "Brew without the scale" is the way to a clock-only start.
                SoftTextButton(onClick = ::finish, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Finish without timing") }
            } else if (stageView == null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tap at the first pour.", Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                // Before the scale is armed, the timer runs by the clock alone.
                PrimaryAction("Start timer") { showPlan = false; change(draft.copy(timer = BrewTimer(startedAt = clock()), scale = null)) }
                SoftTextButton(onClick = ::finish, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Finish without timing") }
            } else {
                // The scale started this timer moments ago; Undo puts the sheet back to waiting for the first pour.
                val justStarted = draft.scale != null && stageView.timer.byWeight && stageView.step == 0 && clock() - stageView.timer.startedAt < UNDO_MILLIS
                TimerFooter(stageView, { timer { recipe, now -> pause(recipe, now) } }, { timer { recipe, now -> resume(recipe, now) } }, { timer { recipe, now -> skip(recipe, now) } }, { timer { _, now -> undone(now) } }, ::finish,
                    onUndoStart = if (justStarted) ({ change(draft.copy(timer = null)) }) else null)
            }
            DraftStage.REVIEW -> PrimaryAction("Save brew", enabled = validDate && validTotal, icon = Icons.Outlined.Check) {
                repository.updateDraft(merged(draft)).fold(
                    onSuccess = { repository.saveBrew(draft.id).onSuccess { onSaved(it.id) }.onFailure { onError("Couldn't save brew. Try again.") } },
                    onFailure = { onError("Couldn't save brew. Try again.") },
                )
            }
        }
    }) {
        when (stage) {
            DraftStage.SETUP -> {
                SetupFields(draft, state, { picker = it }, { dial = true }, ::change)
            }
            DraftStage.SHEET -> if (stageView == null) {
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
            } else TimerSheet(stageView, showPlan, { showPlan = it }, scale.live.takeIf { draft.scale != null }, draft.scale)
            DraftStage.REVIEW -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(draft.beans!!.name, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                        Text("${draft.recipe!!.name} · ${draft.setting} ticks", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    IconAction(Icons.Outlined.Tune, "Change setup") { change(draft.copy(stage = DraftStage.SETUP)) }
                }
                DateEntry(dateText, { dateText = it }, { validDate = it }) { change(draft.copy(startedAt = it)) }
                val timer = draft.timer
                val data = draft.scale
                if (timer != null || data != null) Panel {
                    if (timer != null) {
                        TotalBrewTime(timer, { validTotal = it }) { change(draft.copy(timer = timer.copy(totalOverride = it))) }
                        if (data != null) LiftOffer(timer, data) { change(draft.copy(timer = timer.copy(totalOverride = it))) }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    MeasuredTrio(draft.recipe!!, data)
                }
                JournalEditor(draft.journal, draft.beans!!.expectedNotes) { change(draft.copy(journal = it)) }
            }
        }
    }
    }
    }
    if (dial && draft.grinder != null) GrinderDial(draft.setting, state.grinders.find { it.id == draft.grinder.id }?.lastSetting, state.grinders.find { it.id == draft.grinder.id }?.finish ?: draft.grinder.finish, { change(draft.copy(setting = it)) }, { finish ->
        val grinder = state.grinders.find { it.id == draft.grinder.id }
        if (grinder != null) repository.upsertGrinder(grinder.copy(finish = finish)).onFailure { onError(it.message ?: "Couldn't save finish.") }
        change(draft.copy(grinder = draft.grinder.copy(finish = finish)))
    }, { dial = false })
    if (discard) DiscardBrewDialog({ discard = false }, { repository.discardDraft().onSuccess { onClose() }.onFailure { onError(it.message ?: "Couldn't discard brew.") }; discard = false })
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
        SoftButton(onClick = { onChange(draft.copy(allowArchivedBeans = true)) }) { Text("Use anyway") }
        SoftTextButton(onClick = { onPick("beans") }) { Text("Choose other beans") }
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
            SoftButton(onClick = onDial) { Text("Adjust") }
        }
        val recorded = activeGrinder?.lastSetting
        // A repeated brew can carry an older setting; compare the two once and offer the recorded one in a tap.
        if (draft.setting != null && recorded != null && draft.setting != recorded) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Last recorded: $recorded ticks", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodyMedium)
            SoftTextButton(onClick = { onChange(draft.copy(setting = recorded)) }) { Text("Use $recorded ticks") }
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
private fun PlanSummary(beans: Beans, grinder: Grinder, setting: Int, recipe: Recipe, onCorrect: (() -> Unit)? = null, showBeans: Boolean = true) {
    Panel {
        if (showBeans) {
            Text(beans.name, style = MaterialTheme.typography.titleLarge)
            Text(recipe.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Text(recipe.name, style = MaterialTheme.typography.titleMedium)
        if (showBeans) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val dose = formatAmount(recipe.dose)
            val ratio = formatAmount(recipe.ratio)
            val water = formatAmount(recipe.totalWater)
            val spoken = "Coffee dose $dose grams, brew ratio 1 to $ratio, total brew water $water grams. ${grinder.name}, $setting ticks."
            Column(Modifier.align(Alignment.CenterVertically).clearAndSetSemantics { contentDescription = spoken }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("$dose g · 1:$ratio · $water g", style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
                Text("${grinder.name} · $setting ticks", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            if (onCorrect != null) IconAction(Icons.Outlined.Tune, "Change setup", Modifier.align(Alignment.CenterVertically), onClick = onCorrect)
        }
    }
}

/** The planned pours in the same row format as the running timer: start time, then what to pour, then the detail. */
@Composable
private fun PlanSchedule(recipe: Recipe) {
    val colors = MaterialTheme.colorScheme
    if (recipe.notes.isNotBlank()) Panel { Text("Preparation notes", style = MaterialTheme.typography.titleMedium); Text(recipe.notes) }
    SectionTitle("Planned pours")
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
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(editing) { if (editing) focus.requestFocus() }
        LibraryField(text, { input -> onTextChange(input); runCatching { LocalDateTime.parse(input, entryFormat).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()?.let(onChange) }, "Brew date and time", error = if (parsed == null) "Use yyyy-MM-dd HH:mm, for example 2026-11-01 08:30." else null, helper = "yyyy-MM-dd HH:mm", modifier = Modifier.focusRequester(focus))
    } else Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.CalendarToday, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(dateTime(parsed), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        IconAction(Icons.Outlined.Edit, "Change date") { editing = true }
    }
}

/** The four-cell strip on Brew: recipe, dose, ratio, and grind. */
private fun stripCells(recipe: Recipe, setting: Int): List<Cell> = listOf(
    Cell("Recipe", recipe.name),
    Cell("Dose", formatAmount(recipe.dose), "g", "Coffee dose ${formatAmount(recipe.dose)} grams"),
    Cell("Ratio", "1:${formatAmount(recipe.ratio)}", spoken = "Brew ratio 1 to ${formatAmount(recipe.ratio)}"),
    Cell("Grind", "$setting", "ticks", "Grind setting, $setting ticks"),
)

/** A record's title block: its setup, then its results beside the plan where the scale measured them. */
private fun recordCells(brew: BrewRecord): List<Cell> {
    val recipe = brew.recipe
    val dose = formatAmount(recipe.dose)
    val planned = formatAmount(recipe.totalWater)
    val ratio = formatAmount(recipe.ratio)
    val weighed = brew.scale?.weighedDose != null
    val water = brew.scale?.water
    val measuredRatio = water?.let { "1:" + "%.1f".format(it / recipe.dose) }
    // A scale brew whose final water was not seen says so, as the timing section does.
    val unmeasured = brew.scale != null && water == null
    return listOfNotNull(
        Cell("Recipe", recipe.name),
        Cell("Grinder", brew.grinder.name),
        Cell("Dose", dose, if (weighed) "g, weighed" else "g", "Coffee dose $dose grams" + if (weighed) ", weighed" else ""),
        Cell("Grind setting", "${brew.setting}", "ticks", "Grind setting, ${brew.setting} ticks"),
        if (water != null) Cell("Water", tenths(water), "of $planned g", "Water ${tenths(water)} grams, planned $planned grams")
        else if (unmeasured) Cell("Water", "\u2014", "planned $planned g", "Water not recorded, planned $planned grams")
        else Cell("Water", planned, "g", "Total brew water $planned grams"),
        if (measuredRatio != null) Cell("Ratio", measuredRatio, "of 1:$ratio", "Brew ratio ${measuredRatio.replace("1:", "1 to ")}, planned 1 to $ratio")
        else if (unmeasured) Cell("Ratio", "\u2014", "planned 1:$ratio", "Brew ratio not recorded, planned 1 to $ratio")
        else Cell("Ratio", "1:$ratio", spoken = "Brew ratio 1 to $ratio"),
        brew.timing?.let { Cell("Total brew time", it.totalSeconds?.let(::formatDuration) ?: "Not recorded") },
        Cell("Brewed", dateTime(brew.startedAt)),
    )
}

@Composable
private fun RecordDetail(brew: BrewRecord, hasNoteEdit: Boolean, onClose: () -> Unit, onRepeat: () -> Unit, onNotes: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    BackHandler(LocalScreenActive.current) { onClose() }
    Page("Brew record", onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(brew.beans.name, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
            Text(dateTime(brew.startedAt), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        val look = LocalLook.current
        // The blueprint moves "Next time" into the title block's revision row.
        val journal = if (look.blueprint) brew.journal.copy(nextTime = "") else brew.journal
        if (!journal.isEmpty) Taped { Panel { JournalSummary(journal) } }
        PrimaryAction(if (hasNoteEdit) "Resume tasting notes" else if (brew.journal.isEmpty) "Add tasting notes" else "Edit tasting notes", icon = Icons.Outlined.EditNote, onClick = onNotes)
        SecondaryAction("Repeat", icon = Icons.Outlined.Repeat, onClick = onRepeat)
        SectionTitle("Preparation")
        if (look.blueprint) TitleBlock(recordCells(brew),
            revision = brew.journal.nextTime.takeIf { it.isNotBlank() }?.let { next -> {
                Text("Next time", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text(next, Modifier.weight(1f), color = look.accent, style = look.hand(MaterialTheme.typography.bodyLarge))
            } })
        else PlanSummary(brew.beans, brew.grinder, brew.setting, brew.recipe, showBeans = false)
        brew.timing?.let { TimingPanel(brew.recipe, it, brew.scale) }
        PlanSchedule(brew.recipe)
        Panel {
            Text("Beans", style = MaterialTheme.typography.titleMedium)
            Text(brew.beans.roaster)
            listOf("Origin" to brew.beans.origin, "Variety / cultivar" to brew.beans.variety, "Processing method" to brew.beans.processing, "Expected tasting notes" to brew.beans.expectedNotes, "Bean notes" to brew.beans.notes, "Grinder reference" to brew.grinder.referenceNotes).filter { it.second.isNotBlank() }.forEach { (label, value) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text(value, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        SecondaryAction("Edit brew", icon = Icons.Outlined.Edit, onClick = onEdit)
        SoftTextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), danger = true) { Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Delete brew") }
    }
}

@Composable
private fun NotesScreen(brew: BrewRecord, pending: Journal?, repository: BrewRepository, onError: (String) -> Unit, onClose: () -> Unit) {
    var encoded by rememberSaveable(brew.id) { mutableStateOf(Json.encodeToString(pending ?: brew.journal)) }
    val journal = remember(encoded) { Json.decodeFromString<Journal>(encoded) }
    var discard by remember { mutableStateOf(false) }
    fun back() { if (journal != brew.journal) discard = true else onClose() }
    BackHandler(LocalScreenActive.current) { back() }
    Page("Tasting notes", ::back, footer = {
        PrimaryAction("Save notes", icon = Icons.Outlined.Check) {
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
    BackHandler(enabled = LocalScreenActive.current && picker == null) { back() }
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
        PrimaryAction("Save brew", enabled = parseGrindSetting(settingText) != null && validDate, icon = Icons.Outlined.Check) {
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

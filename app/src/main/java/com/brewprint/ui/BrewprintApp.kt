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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester

/** How deep a screen sits: destinations, then screens opened from them, then screens opened from those. */
private fun screenDepth(screen: String) = when (screen) {
    "notes", "edit" -> 2
    "draft", "record" -> 1
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
    // The Library tab's place, kept here so a brew started from an item returns to that item.
    val libraryNav = rememberSaveable(saver = LibraryNav.Saver) { LibraryNav() }
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
    var libraryUndoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // The Library swipe whose Undo is on offer, saved so a recreated screen offers it again for the time left.
    var libraryUndo by rememberSaveable { mutableStateOf<String?>(null) }
    val accessibility = androidx.compose.ui.platform.LocalAccessibilityManager.current
    fun offerUndo() {
        undoJob?.cancel()
        // One Undo bar at a time, the newest: a Library swipe's ends, so this one isn't kept waiting behind it.
        libraryUndoJob?.cancel()
        libraryUndo = null
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
    // A Library swipe acts at once, and a newer one ends the previous one's Undo. Behind a History Undo it waits its turn:
    // its time starts once its own bar shows.
    fun offerLibraryUndo(offer: BeansUndo) {
        libraryUndoJob?.cancel()
        libraryUndo = Json.encodeToString(offer)
        libraryUndoJob = scope.launch {
            val wait = offer.shownUntil?.let { it - clock() }
                ?: accessibility?.calculateRecommendedTimeoutMillis(UNDO_MILLIS, containsIcons = false, containsText = true, containsControls = true) ?: UNDO_MILLIS
            if (wait <= 0) { libraryUndo = null; return@launch }
            val visuals = object : SnackbarVisuals {
                override val message = offer.action.message
                override val actionLabel = "Undo"
                override val withDismissAction = false
                override val duration = SnackbarDuration.Indefinite
            }
            val timer = launch {
                val shown = snapshotFlow { snackbar.currentSnackbarData }.first { it?.visuals === visuals }
                libraryUndo = Json.encodeToString(offer.copy(shownUntil = clock() + wait))
                kotlinx.coroutines.delay(wait)
                shown?.dismiss()
            }
            val result = snackbar.showSnackbar(visuals)
            timer.cancel()
            libraryUndo = null
            if (result == SnackbarResult.ActionPerformed) offer.undo(repository).onFailure { onError(it.message ?: "Couldn't undo. Try again.") }
        }
    }
    // After recreation, a window still open is offered again for the time left; one that has ended is written.
    LaunchedEffect(Unit) {
        if (pendingDeletes.isNotEmpty()) offerUndo()
        libraryUndo?.let { offerLibraryUndo(Json.decodeFromString<BeansUndo>(it)) }
    }
    val shown = if (pendingDeletes.isEmpty()) state else state.copy(brews = state.brews.filterNot { it.id in pendingDeletes })
    // An item's own screen in the Library hides the navigation, as other screens opened from a destination do.
    val navVisible = route == null && !libraryEditing && !(tab == "Library" && libraryNav.detailId != null)
    chrome.navSpace = if (navVisible) 78.dp else 0.dp
    val cloud by com.brewprint.cloud.CloudSync.state.collectAsState()
    // Back on History, Library or Settings goes to Brew, the starting point; Library's own screens handle Back first.
    BackHandler(enabled = route == null && tab != "Brew" && !libraryEditing) { tab = "Brew" }
    fun startBrew() {
        repository.startDraft().onSuccess { draftReturn = null; draftFresh = true; route = "draft" }.onFailure { onError(it.message ?: "Couldn't start brew.") }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).semantics { isTraversalGroup = true }) {
          CompositionLocalProvider(LocalChrome provides chrome) {
           // Opening a screen slides it in a short way while it fades; Back reverses it. Destinations cross-fade.
           AnimatedContent(route ?: "tab:$tab", label = "screen", transitionSpec = { screenChange(screenDepth(initialState), screenDepth(targetState)) }) { screen ->
            val leaving = transition.targetState == androidx.compose.animation.EnterExitState.PostExit
            // A destination on its way out keeps what it last showed, so the change that opened the next screen,
            // such as a new brew's draft, doesn't redraw it while it fades.
            val held = remember { arrayOf(state, shown) }
            if (!leaving) { held[0] = state; held[1] = shown }
            Box(Modifier.fillMaxSize().inert(leaving)) { CompositionLocalProvider(LocalScreenActive provides !leaving) {
            when (screen) {
                "draft" -> state.draft?.let { draft -> DraftFlow(draft, state, repository, onError, { route = draftReturn }, { openRecord(it) }, pairScale, draftFresh) }
                    // Only the screen being opened may close itself; the one animating out after a save has no draft by design.
                    ?: if (route == "draft") LaunchedEffect(Unit) { route = null } else Unit
                "record" -> state.brews.find { it.id == recordId }?.let { brew -> RecordDetail(brew, state.noteEdits.any { it.brewId == brew.id }, state.journalDrafts.any { it.brewId == brew.id }, { route = null }, { repeat(brew.id) }, { notesReturn = "record"; route = "notes" }, { route = "edit" }, { delete(brew.id) }) }
                "notes" -> state.brews.find { it.id == recordId }?.let { brew -> NotesScreen(brew, state.noteEdits.find { it.brewId == brew.id }?.journal, state.journalDrafts.find { it.brewId == brew.id }?.journal, repository, onError) { route = notesReturn } }
                "edit" -> state.brews.find { it.id == recordId }?.let { brew -> EditRecord(brew, state, repository, onError) { route = "record" } }
                else -> { val state = held[0]; val shown = held[1]; when (screen.removePrefix("tab:")) {
                    "Library" -> LibraryScreen(state, repository, onError, onEditingChanged = { libraryEditing = it }, onUndoable = ::offerLibraryUndo, nav = libraryNav,
                        onBrewWith = { beansId, recipeId ->
                            repository.startDraftWith(beansId, recipeId).onSuccess { draftReturn = null; draftFresh = true; route = "draft" }.onFailure { onError(it.message ?: "Couldn't start brew.") }
                        }, onResumeBrew = { resumeDraft() })
                    "History" -> HistoryScreen(shown.brews, state.draft == null, { openRecord(it) }, { repeat(it) }, { delete(it) })
                    "Settings" -> SettingsScreen(state.appearance, state.timerSettings, { repository.setAppearance(it).onFailure { failure -> onError(failure.message ?: "Couldn't save appearance.") } }, { repository.setTimerSettings(it).onFailure { failure -> onError(failure.message ?: "Couldn't save settings.") } }, account = { AccountSettings(onError) },
                        experiments = state.experiments, onExperiments = { repository.setExperiments(it).onFailure { failure -> onError(failure.message ?: "Couldn't save settings.") } }) {
                        ScaleSettings(state.scale, LocalScale.current.live, pairScale) {
                            state.scale?.let { forgetScale(context, it) }
                            repository.setScale(null).onSuccess { com.brewprint.scale.ScaleLink.sync(context) }.onFailure { failure -> onError(failure.message ?: "Couldn't forget the scale.") }
                        }
                    }
                    else -> BrewHome(shown, { resumeDraft() }, { openRecord(it) }, { repeat(it) }, { recordId = it; notesReturn = null; route = "notes" }, { libraryNav.reset(); tab = "Library" })
                } }
            }
            } }
           }
          }
            val navShown = navVisible && (chrome.navShown || rememberTouchExploration())
            // "New brew", or "Resume brew" while one is in progress, floats over Brew above the navigation,
            // and drops to the corner while the navigation is hidden. Screen readers reach it after the screen's content.
            val onBrew = route == null && tab == "Brew"
            val fabTarget = if (navShown) 86.dp else 16.dp
            // It fades out with Brew rather than vanishing as another screen opens, keeping its label and place,
            // and takes no taps on its way out.
            AnimatedVisibility(onBrew, Modifier.align(Alignment.BottomEnd).semantics { traversalIndex = 1f },
                enter = fadeIn(tween(200, delayMillis = 60)) + scaleIn(tween(200, delayMillis = 60), initialScale = 0.92f),
                exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.92f)) {
                val leaving = transition.targetState == androidx.compose.animation.EnterExitState.PostExit
                val held = remember { arrayOf<Any>(fabTarget, state.draft != null) }
                if (!leaving) { held[0] = fabTarget; held[1] = state.draft != null }
                val fabBottom by androidx.compose.animation.core.animateDpAsState(held[0] as androidx.compose.ui.unit.Dp, tween(280), label = "fab")
                BrewAction(held[1] as Boolean, Modifier.padding(end = 16.dp, bottom = fabBottom)) { if (!leaving) { if (state.draft != null) resumeDraft() else startBrew() } }
            }
            // Always composed, so leaving a destination slides it away instead of removing it.
            FloatingNavigation(tab, navShown, Modifier.align(Alignment.BottomCenter).semantics { traversalIndex = 2f }, syncFailed = cloud.failed) {
                // Tapping Library again returns to its hub.
                if (it == "Library" && tab == "Library") libraryNav.reset()
                tab = it
            }
            // The Undo bar moves with the navigation instead of jumping, and sits above the floating action on Brew.
            val lift by androidx.compose.animation.core.animateDpAsState(when {
                onBrew -> fabTarget + 64.dp
                navShown -> 78.dp
                route != null -> chrome.footerSpace + 8.dp
                else -> 8.dp
            }, tween(280), label = "snackbar")
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = lift).padding(horizontal = 8.dp)) {
                // Monochrome: the action reads in the bar's own text color, set apart by weight.
                Snackbar(it, shape = MaterialTheme.shapes.small, actionColor = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
    }
    if (error != null) AlertDialog(onDismissRequest = { error = null }, title = { Text("Couldn't complete action") }, text = { Text(error.orEmpty()) }, confirmButton = { SoftTextButton(onClick = { error = null }) { Text("Close") } })
}

/**
 * Brew: what is happening now, then the most recent brew. "New brew", or "Resume brew" while one is in progress,
 * is the floating action over this screen. Every theme shows the same things in the same order.
 */
@Composable
private fun BrewHome(state: AppState, onResume: () -> Unit, onRecord: (String) -> Unit, onRepeat: (String) -> Unit, onNotes: (String) -> Unit, onDrafts: () -> Unit) {
    Page("Brew") {
        state.draft?.let { draft -> InProgressCard(draft, onResume) }
        val drafts = state.libraryDrafts
        if (drafts.isNotEmpty()) HomeBanner(
            Icons.Outlined.Inbox,
            if (drafts.size == 1) "Review 1 draft from Claude" else "Review ${drafts.size} drafts from Claude",
            drafts.joinToString(" · ") { draft -> draft.beans?.let { "${it.name} beans" } ?: "${draft.recipe?.name} recipe" },
            onClick = onDrafts,
        )
        val recent = state.brews.maxByOrNull { it.startedAt }
        // Claude's notes for the most recent brew are reviewed from its card; older brews get a card each.
        state.journalDrafts.forEach { draft ->
            state.brews.find { it.id == draft.brewId && it.id != recent?.id }?.let { brew ->
                HomeBanner(Icons.Outlined.EditNote, "Review tasting notes from Claude", "${brew.beans.name} · ${dateTime(brew.startedAt)}") { onNotes(brew.id) }
            }
        }
        if (recent == null) {
            Column(Modifier.fillMaxWidth().padding(top = 52.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.Coffee, null, Modifier.size(72.dp).background(MaterialTheme.colorScheme.surface, CircleShape).padding(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Record your first brew", style = MaterialTheme.typography.titleLarge)
                Text("Save the beans, recipe, grinder setting, and your tasting notes.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            SectionTitle("Most recent brew")
            Taped { Panel {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                        Text(dateTime(recent.startedAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SoftTextButton(onClick = { onRecord(recent.id) }) { Text("View brew"); Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp)) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(recent.beans.name, style = MaterialTheme.typography.titleLarge)
                    roastLine(recent.roastDate)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
                }
                SetupStrip(stripCells(recent.recipe, recent.setting), weights = listOf(1.35f, .9f, .8f, 1f))
                val journal = recent.journal
                if (journal.impression.isNotBlank()) Text(journal.impression, maxLines = 3, overflow = TextOverflow.Ellipsis, style = LocalLook.current.hand(MaterialTheme.typography.bodyLarge))
                if (journal.nextTime.isNotBlank()) NextTimeLine(journal.nextTime, maxLines = 2)
                val notes = notesAction(recent, state.noteEdits.any { it.brewId == recent.id }, state.journalDrafts.any { it.brewId == recent.id })
                // Side by side where they fit; stacked on a narrow screen or with large text.
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    val repeat: @Composable (Modifier) -> Unit = { modifier -> SoftButton(onClick = { onRepeat(recent.id) }, modifier = modifier.heightIn(min = 56.dp).fillMaxHeight(), enabled = state.draft == null, contentPadding = padding) { Icon(Icons.Outlined.Repeat, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Repeat") } }
                    val edit: @Composable (Modifier) -> Unit = { modifier -> SoftButton(onClick = { onNotes(recent.id) }, modifier = modifier.heightIn(min = 56.dp).fillMaxHeight(), contentPadding = padding) { Text(notes, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
                    if (maxWidth / androidx.compose.ui.platform.LocalDensity.current.fontScale >= 300.dp) Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) { repeat(Modifier); edit(Modifier.weight(1f)) }
                    else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { repeat(Modifier.fillMaxWidth()); edit(Modifier.fillMaxWidth()) }
                }
            } }
        }
        // A brew with Claude's notes waiting has its card above, which opens the review.
        state.noteEdits.filter { edit -> edit.brewId != recent?.id && state.journalDrafts.none { it.brewId == edit.brewId } }.forEach { edit ->
            state.brews.find { it.id == edit.brewId }?.let { brew -> SoftButton(onClick = { onNotes(brew.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Resume tasting notes · ${brew.beans.name}") } }
        }
        // Room for the floating action, so it never covers the last card's buttons.
        Spacer(Modifier.height(72.dp))
    }
}

/** Brew's floating action: "New brew", or "Resume brew" while a brew is in progress. */
@Composable
private fun BrewAction(inProgress: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    ExtendedFloatingActionButton(onClick, modifier.heightIn(min = 56.dp).pressScale(source), shape = MaterialTheme.shapes.small,
        containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, interactionSource = source) {
        AnimatedContent(inProgress, label = "brew-action", transitionSpec = { fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(120)) }) { resume ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (resume) Icons.Outlined.PlayArrow else Icons.Outlined.Add, null, Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(if (resume) "Resume brew" else "New brew", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** A brew in progress, timed or not. Tapping it resumes the brew, as the floating action does. */
@Composable
private fun InProgressCard(draft: BrewDraft, onResume: () -> Unit) {
    val recipe = draft.recipe
    val view = if (draft.timer != null && recipe != null) TimerNow(recipe, draft.timer, rememberNow()) else null
    val detail = listOfNotNull(draft.beans?.name, recipe?.name).joinToString(" · ").ifBlank { "Setup in progress" }
    val phase = view?.let {
        when {
            it.phase == TimerPhase.STOPPED -> null
            it.phase == TimerPhase.FINISHED -> "Finished"
            it.paused -> "Paused"
            it.phase == TimerPhase.DRAWDOWN -> "Drawdown"
            it.phase == TimerPhase.WAIT -> "Wait"
            else -> "Pour"
        }
    }
    HomeBanner(Icons.Outlined.Timer, "Brew in progress", detail, emphasized = true, onClickLabel = "Resume brew", onClick = onResume,
        below = view?.takeIf { it.phase == TimerPhase.STOPPED }?.let { stoppedText(it) },
        trailing = if (view != null && phase != null) ({
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(phase, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Text(formatDuration(view.brewSeconds), Modifier.semantics { contentDescription = "Brew time ${formatDuration(view.brewSeconds)}" }, style = MaterialTheme.typography.titleMedium.tabular())
            }
        }) else null)
}

/** A card on Brew that opens something waiting: a brew in progress, or drafts from Claude. */
@Composable
private fun HomeBanner(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, emphasized: Boolean = false, onClickLabel: String? = null, below: String? = null, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClickLabel = onClickLabel, onClick = onClick), shape = MaterialTheme.shapes.medium, color = colors.surface,
        border = BorderStroke(if (emphasized) 1.5.dp else 1.dp, if (emphasized) colors.onSurface else colors.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(icon, null, Modifier.size(40.dp).background(if (emphasized) colors.primary else colors.soft, CircleShape).padding(10.dp), tint = if (emphasized) colors.onPrimary else colors.onSecondaryContainer)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(detail, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (trailing != null) trailing() else Icon(Icons.Outlined.ChevronRight, null, tint = colors.onSurfaceVariant)
            }
            if (below != null) Text(below, style = MaterialTheme.typography.bodyMedium)
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
internal fun android.view.View.thresholdHaptic(armed: Boolean) {
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
            DraftStage.REVIEW -> PrimaryAction("Save brew", enabled = validDate && validTotal && !beansDeleted(draft, state), icon = Icons.Outlined.Check) {
                repository.updateDraft(merged(draft)).fold(
                    onSuccess = { repository.saveBrew(draft.id).onSuccess { onSaved(it.id) }.onFailure { onError("Couldn't save brew. Try again.") } },
                    onFailure = { onError("Couldn't save brew. Try again.") },
                )
            }
        }
    }) {
        when (stage) {
            DraftStage.SETUP -> {
                SetupFields(draft, state, { picker = it }, { dial = true }, { discard = true }, ::change)
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
                        if (draft.scale == null) ScaleStep.BREWER else draft.scaleStep, if (scaleLost) scale.live.copy(silent = true) else scale.live, draft.recipe.dose, draft.recipe.computedRatio, data.weighedDose, reconnected, ice = draft.recipe.iceGrams?.takeIf { draft.recipe.drinkStyle == DrinkStyle.ICED },
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
                if (beansDeleted(draft, state)) DeletedBeansPanel(state, { picker = "beans" }) { discard = true }
                DateEntry(dateText, { dateText = it }, { validDate = it }) { change(draft.copy(startedAt = it)) }
                val timer = draft.timer
                val data = draft.scale
                if (timer != null || data != null) Panel {
                    if (timer != null) {
                        TotalBrewTime(timer, { validTotal = it }) { change(draft.copy(timer = timer.copy(totalOverride = it))) }
                        draft.recipe?.targetFinishSeconds?.let { Text("Target finish ${formatDuration(it)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
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
        draft.withBeans(beans, state.lastBatchId).copy(allowArchivedBeans = false, recipe = recipe).let { if (restart) it.copy(scale = null, scaleStep = ScaleStep.BREWER) else it }
    }
    "grinders" -> state.grinders.first { it.id == id }.let { draft.copy(grinder = it, setting = it.lastSetting) }
    // The timer and any weighed dose follow one recipe; choosing another clears them and starts the scale steps again.
    else -> state.recipes.first { it.id == id }.let { recipe ->
        if (recipe == draft.recipe) draft else draft.copy(recipe = recipe, timer = null, scale = null, scaleStep = ScaleStep.BREWER)
    }
}

/** Beans the draft uses that are no longer in the Library: the brew needs other beans or is discarded. */
internal fun beansDeleted(draft: BrewDraft, state: AppState): Boolean = draft.beans != null && state.beans.none { it.id == draft.beans.id }

internal fun ready(draft: BrewDraft, state: AppState): Boolean = draft.beans != null && !beansDeleted(draft, state) && draft.recipe != null && draft.grinder != null && draft.setting?.let { parseGrindSetting(it.toString()) } != null && draft.recipe.validationErrors().isEmpty() && state.grinders.any { it.id == draft.grinder.id && !it.archived } && (state.beans.none { it.id == draft.beans.id && it.archived } || draft.allowArchivedBeans)

@Composable
private fun SetupFields(draft: BrewDraft, state: AppState, onPick: (String) -> Unit, onDial: () -> Unit, onDiscard: () -> Unit, onChange: (BrewDraft) -> Unit) {
    SelectionCard("Beans", draft.beans?.name ?: if (state.beans.none { !it.archived }) "Add beans" else "Choose beans", listOfNotNull(draft.beans?.roaster, roastLine(draft.roastDate)).joinToString(" · "), { onPick("beans") })
    BatchChoice(draft, state, onChange)
    if (beansDeleted(draft, state)) DeletedBeansPanel(state, { onPick("beans") }, onDiscard)
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
        draft.recipe?.grindDescription?.takeIf { it.isNotBlank() }?.let { Text("Grind size: $it", style = MaterialTheme.typography.bodyMedium) }
        val recorded = activeGrinder?.lastSetting
        // A repeated brew can carry an older setting; compare the two once and offer the recorded one in a tap.
        if (draft.setting != null && recorded != null && draft.setting != recorded) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Last recorded: $recorded ticks", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodyMedium)
            SoftTextButton(onClick = { onChange(draft.copy(setting = recorded)) }) { Text("Use $recorded ticks") }
        } else Text("Last recorded setting: ${recorded?.let { "$it ticks" } ?: "Not recorded"}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
    draft.recipe?.let { recipe -> RecipeNumbers(recipe) }
}

/**
 * With two or more bags of the chosen beans, "Change batch". After a repeat moved to the newest bag, says so.
 * Changing bag before the timer starts begins the scale steps again, as other beans would.
 */
@Composable
private fun BatchChoice(draft: BrewDraft, state: AppState, onChange: (BrewDraft) -> Unit) {
    val live = state.beans.find { it.id == draft.beans?.id } ?: return
    var choosing by rememberSaveable { mutableStateOf(false) }
    draft.repeatedFromRoastDate?.let { original ->
        Text("Using newest batch" + (draft.roastDate?.let { " (${roastedLabel(it)})" } ?: "") + (original.takeIf { it.isNotEmpty() }?.let { " · Original brew used ${roastedLabel(it).removePrefix("Roasted ")} batch" } ?: ""),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
    if (live.batches.size >= 2) SoftTextButton(onClick = { choosing = true }) { Text("Change batch") }
    if (choosing) BatchPickerDialog(live, draft.batchId, { id ->
        choosing = false
        if (id != draft.batchId) onChange(draft.withBeans(live, batchId = id).copy(beans = draft.beans, allowArchivedBeans = draft.allowArchivedBeans).let {
            if (it.timer == null && it.scale != null) it.copy(scale = null, scaleStep = ScaleStep.BREWER,
                recipe = if (it.scale.weighedDose != null) state.recipes.firstOrNull { recipe -> recipe.id == it.recipe?.id } ?: it.recipe else it.recipe)
            else it
        })
    }) { choosing = false }
}

/** Choose other beans, or add some when none are left, or discard the brew. */
@Composable
private fun DeletedBeansPanel(state: AppState, onChoose: () -> Unit, onDiscard: () -> Unit) {
    Panel {
        Text("These beans were deleted.")
        SoftButton(onClick = onChoose) { Text(if (state.beans.none { !it.archived }) "Add beans" else "Choose other beans") }
        SoftTextButton(onClick = onDiscard, danger = true) { Text("Discard brew") }
    }
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
            val ratio = ratioCell(recipe)
            val iced = recipe.drinkStyle == DrinkStyle.ICED
            listOfNotNull(Triple("Coffee dose", "${formatAmount(recipe.dose)} g", "Coffee dose ${formatAmount(recipe.dose)} grams"), Triple("Brew ratio", ratio.value + ratio.unit.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty(), ratio.spoken),
                Triple("Total brew water", "${formatAmount(recipe.totalWater)} g", "Total brew water ${formatAmount(recipe.totalWater)} grams"),
                if (iced) Triple("Style", recipe.iceGrams?.let { "Iced, ${formatAmount(it)} g ice" } ?: "Iced", "Iced" + (recipe.iceGrams?.let { ", ${formatAmount(it)} grams of ice" } ?: "")) else null,
                recipe.waterTempC?.let { Triple("Water temp", "$it °C", "Water temperature $it degrees Celsius") }).forEach { (label, value, spoken) -> Column(Modifier.clearAndSetSemantics { contentDescription = spoken }, verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.titleLarge) } }
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
            val ratio = formatRatio(recipe.computedRatio)
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
private fun PlanSchedule(recipe: Recipe, facts: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val details = recipeFacts(recipe).takeIf { facts && it.isNotEmpty() }
    if (details != null || recipe.notes.isNotBlank()) Panel {
        if (details != null) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
        if (details != null && recipe.notes.isNotBlank()) HorizontalDivider(color = colors.outlineVariant)
        if (recipe.notes.isNotBlank()) { Text("Preparation notes", style = MaterialTheme.typography.titleMedium); Text(recipe.notes) }
    }
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
            val note = scheduled.pulse.notes.takeIf { it.isNotBlank() }
            val spoken = "$name, ${spokenStart(scheduled.startSeconds)}. Add $added grams" + (if (index > 0) ", pour to $total grams total" else "") +
                ". Pour for ${scheduled.pulse.pourSeconds} seconds" + (wait?.let { ", then wait $it seconds" } ?: "") + "." + (note?.let { " $it." } ?: "")
            Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.widthIn(min = 44.dp).height(line), contentAlignment = Alignment.CenterStart) {
                    Text(formatDuration(scheduled.startSeconds), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                Box(Modifier.height(line), contentAlignment = Alignment.Center) { Box(Modifier.size(9.dp).border(1.5.dp, colors.outline, CircleShape)) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("$name, add $added g", style = MaterialTheme.typography.titleMedium)
                    Text(detail, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    if (note != null) StepNote(note, Modifier.padding(top = 2.dp))
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
    Cell("Ratio", "1:${formatRatio(recipe.computedRatio)}", spoken = "Brew ratio 1 to ${formatRatio(recipe.computedRatio)}"),
    Cell("Grind", "$setting", "ticks", "Grind setting, $setting ticks"),
)

/**
 * A record's setup as it was planned. What the scale measured belongs to "Timing and water", so it is not repeated here.
 */
private fun recordCells(brew: BrewRecord): List<Cell> {
    val recipe = brew.recipe
    val dose = formatAmount(recipe.dose)
    val weighed = brew.scale?.weighedDose != null
    return listOf(
        Cell("Recipe", recipe.name, wide = true),
        Cell("Grinder", brew.grinder.name),
        Cell("Grind setting", "${brew.setting}", "ticks", "Grind setting, ${brew.setting} ticks"),
        Cell("Dose", dose, if (weighed) "g, weighed" else "g", "Coffee dose $dose grams" + if (weighed) ", weighed" else ""),
        waterCell(recipe),
        ratioCell(recipe),
    ) + recipeDetailCells(recipe)
}

/** The action that opens a brew's tasting notes: Claude's to review first, then unsaved edits, then the notes. */
private fun notesAction(brew: BrewRecord, hasNoteEdit: Boolean, hasClaudeNotes: Boolean) = when {
    hasClaudeNotes -> "Review tasting notes from Claude"
    hasNoteEdit -> "Resume tasting notes"
    brew.journal.isEmpty -> "Add tasting notes"
    else -> "Edit tasting notes"
}

@Composable
private fun RecordDetail(brew: BrewRecord, hasNoteEdit: Boolean, hasClaudeNotes: Boolean, onClose: () -> Unit, onRepeat: () -> Unit, onNotes: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    BackHandler(LocalScreenActive.current) { onClose() }
    Page("Brew record", onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(brew.beans.name, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
            Text(listOfNotNull(dateTime(brew.startedAt), offRoast(brew.roastDate, brew.startedAt)).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        // The tasting journal comes first, "Next time" included, in every theme.
        if (!brew.journal.isEmpty) Taped { Panel { JournalSummary(brew.journal) } }
        PrimaryAction(notesAction(brew, hasNoteEdit, hasClaudeNotes), icon = Icons.Outlined.EditNote, onClick = onNotes)
        SecondaryAction("Repeat", icon = Icons.Outlined.Repeat, onClick = onRepeat)
        SectionTitle("Preparation")
        SetupBlock(recordCells(brew))
        SourceLink(brew.recipe.sourceUrl)
        brew.timing?.let { TimingPanel(brew.recipe, it, brew.scale) }
        PlanSchedule(brew.recipe, facts = false)
        Panel {
            Text("Beans", style = MaterialTheme.typography.titleMedium)
            Text(brew.beans.roaster)
            listOf("Roast date" to brew.roastDate?.let { roastedLabel(it).removePrefix("Roasted ") }.orEmpty(), "Origin" to brew.beans.origin, "Variety / cultivar" to brew.beans.variety, "Processing method" to brew.beans.processing, "Expected tasting notes" to brew.beans.expectedNotes, "Bean notes" to brew.beans.notes, "Grinder reference" to brew.grinder.referenceNotes).filter { it.second.isNotBlank() }.forEach { (label, value) ->
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

/**
 * Edits a brew's tasting notes, or with [claude], reviews the notes Claude drafted for it before saving them. A review
 * starts from Claude's notes, keeps no edits after Back, and leaves the draft waiting until it is saved or discarded.
 */
@Composable
private fun NotesScreen(brew: BrewRecord, pending: Journal?, claude: Journal?, repository: BrewRepository, onError: (String) -> Unit, onClose: () -> Unit) {
    // Whether this opened as a review is fixed while it's open, so a draft arriving mid-edit doesn't change the screen.
    val reviewing = rememberSaveable(brew.id) { claude != null }
    // What Back compares with: the brew's notes, or in a review, Claude's notes as last shown.
    var originalEncoded by rememberSaveable(brew.id) { mutableStateOf(Json.encodeToString(if (reviewing) claude ?: Journal() else brew.journal)) }
    val original = remember(originalEncoded) { Json.decodeFromString<Journal>(originalEncoded) }
    var encoded by rememberSaveable(brew.id) { mutableStateOf(Json.encodeToString(if (reviewing) original else pending ?: brew.journal)) }
    val journal = remember(encoded) { Json.decodeFromString<Journal>(encoded) }
    var discard by remember { mutableStateOf(false) }
    val active = LocalScreenActive.current
    if (reviewing) {
        // A correction from Claude replaces the review unless the user has started editing; a draft saved or
        // discarded elsewhere ends it.
        LaunchedEffect(claude) {
            if (claude == null) { if (active) onClose() }
            else if (claude != original && journal == original) { originalEncoded = Json.encodeToString(claude); encoded = originalEncoded }
        }
    }
    fun back() { if (journal != original) discard = true else onClose() }
    BackHandler(active) { back() }
    Page(if (reviewing) "Review tasting notes" else "Tasting notes", ::back, actions = { if (reviewing) DiscardDraftAction("Discard Claude's notes?", "Your brew keeps the notes it has.") { repository.discardJournalDraft(brew.id).onSuccess { onClose() }.onFailure { onError(it.message ?: "Couldn't discard Claude's notes.") } } }, footer = {
        PrimaryAction("Save notes", icon = Icons.Outlined.Check) {
            if (reviewing) repository.saveDraftedNotes(brew.id, journal).onSuccess { onClose() }.onFailure { onError("Couldn't save notes. Try again.") }
            else repository.saveNoteEdit(brew.id, journal).fold(onSuccess = { repository.saveNotes(brew.id).onSuccess { onClose() }.onFailure { onError("Couldn't save notes. Try again.") } }, onFailure = { onError("Couldn't save notes. Try again.") })
        }
    }) {
        Text(brew.beans.name, style = MaterialTheme.typography.titleMedium)
        if (reviewing) Text("Claude filled these in from your conversation. Check them, change anything, then save.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // A review shows every guided field Claude filled in, not only the written impression.
        JournalEditor(journal, brew.beans.expectedNotes, exploring = reviewing && journal.copy(impression = "", nextTime = "", asItCools = "") != Journal()) { updated ->
            encoded = Json.encodeToString(updated)
            if (!reviewing) repository.saveNoteEdit(brew.id, updated).onFailure { onError(it.message ?: "Couldn't retain note edits. Try again.") }
        }
        if (!reviewing) SecondaryAction("Continue later") { repository.saveNoteEdit(brew.id, journal).onSuccess { onClose() }.onFailure { onError(it.message ?: "Couldn't retain note edits. Try again.") } }
    }
    if (discard) DiscardDialog({ discard = false }, {
        discard = false
        if (reviewing) onClose() else repository.discardNoteEdit(brew.id).onSuccess { onClose() }.onFailure { onError(it.message ?: "Couldn't discard notes.") }
    })
}

@Composable
private fun EditRecord(brew: BrewRecord, state: AppState, repository: BrewRepository, onError: (String) -> Unit, onClose: () -> Unit) {
    var encoded by rememberSaveable(brew.id) { mutableStateOf(Json.encodeToString(BrewDraft(id = brew.id, startedAt = brew.startedAt, beans = brew.beans, grinder = brew.grinder, setting = brew.setting, recipe = brew.recipe, journal = brew.journal, allowArchivedBeans = true, batchId = brew.batchId, roastDate = brew.roastDate))) }
    val draft = remember(encoded) { Json.decodeFromString<BrewDraft>(encoded) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var settingText by rememberSaveable { mutableStateOf(brew.setting.toString()) }
    var validDate by rememberSaveable { mutableStateOf(true) }
    var dateText by rememberSaveable(brew.id) { mutableStateOf(dateInput(brew.startedAt)) }
    val original = BrewDraft(id = brew.id, startedAt = brew.startedAt, beans = brew.beans, grinder = brew.grinder, setting = brew.setting, recipe = brew.recipe, journal = brew.journal, allowArchivedBeans = true, batchId = brew.batchId, roastDate = brew.roastDate)
    fun back() { if (draft != original || settingText != brew.setting.toString() || dateText != dateInput(brew.startedAt)) discard = true else onClose() }
    BackHandler(enabled = LocalScreenActive.current && picker == null) { back() }
    if (picker != null) LibraryScreen(state, repository, onError, requestedKind = picker, onPicked = { kind, id ->
        val selected = selectItem(draft, repository.state.value, kind, id)
        encoded = Json.encodeToString(selected)
        if (kind == "grinders") settingText = selected.setting?.toString().orEmpty()
        picker = null
    }, onClose = { picker = null }) else Page("Edit brew", ::back) {
        Text("Corrections apply to this record.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionCard("Beans", draft.beans!!.name, listOfNotNull(draft.beans.roaster, draft.roastDate?.let { roastedLabel(it) }).joinToString(" · ")) { picker = "beans" }
        BatchChoice(draft, state) { encoded = Json.encodeToString(it) }
        SelectionCard("Recipe", draft.recipe!!.name, null) { picker = "recipes" }
        SelectionCard("Grinder", draft.grinder!!.name, null) { picker = "grinders" }
        LibraryField(settingText, { settingText = it; encoded = Json.encodeToString(draft.copy(setting = parseGrindSetting(it))) }, "Grind setting, ticks", KeyboardType.Number, error = if (parseGrindSetting(settingText) == null) "Enter whole ticks from $MIN_GRIND_SETTING to $MAX_GRIND_SETTING." else null, helper = "$MIN_GRIND_SETTING-$MAX_GRIND_SETTING ticks", required = true)
        DateEntry(dateText, { dateText = it }, { validDate = it }) { encoded = Json.encodeToString(draft.copy(startedAt = it)) }
        PrimaryAction("Save brew", enabled = parseGrindSetting(settingText) != null && validDate, icon = Icons.Outlined.Check) {
            repository.updateBrew(brew.copy(startedAt = draft.startedAt, beans = draft.beans, grinder = draft.grinder, setting = draft.setting!!, recipe = draft.recipe, batchId = draft.batchId, roastDate = draft.roastDate)).onSuccess { onClose() }.onFailure { onError(it.message ?: "Couldn't save brew. Try again.") }
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

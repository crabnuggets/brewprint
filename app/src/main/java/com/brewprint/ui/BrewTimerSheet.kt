@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.brewprint.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.brewprint.timer.BrewTimerService
import com.brewprint.timer.stepCue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brewprint.data.*
import kotlinx.coroutines.delay

/** The wall clock the timer reads. Tests replace it. */
val LocalClock = staticCompositionLocalOf<() -> Long> { { System.currentTimeMillis() } }

@Composable
internal fun rememberNow(interval: Long = 200): Long {
    val clock = LocalClock.current
    val now by produceState(clock(), clock, interval) { while (true) { delay(interval); value = clock() } }
    return now
}

internal fun stoppedText(view: TimerNow): String =
    "Timer stopped at ${formatDuration(view.brewSeconds)}, 15 min after ${if (view.timer.paused) "pausing" else "the last pour"}"

private fun grams(value: Double) = "${formatAmount(value)} g"

private fun pulseDetail(view: TimerNow, index: Int): String {
    val scheduled = view.schedule[index]
    val wait = scheduled.pulse.waitSeconds.takeIf { index < view.pulseCount - 1 && it > 0 }
    return "To ${grams(scheduled.cumulativeWater)} total. Pour ${scheduled.pulse.pourSeconds} s" + (wait?.let { ", wait $it s" } ?: "")
}

/**
 * The brew sheet while a timer exists: the focus card, the plan strip, and what comes next.
 * [scale] and [data] are given for a scale brew.
 */
@Composable
internal fun TimerSheet(view: TimerNow, showPlan: Boolean, onShowPlan: (Boolean) -> Unit, scale: ScaleLive? = null, data: ScaleData? = null) {
    Crossfade(showPlan, animationSpec = tween(300), label = "plan") { plan ->
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (plan) {
                PlanHeader(view)
                FullPlan(view)
                SecondaryAction("Back to current step") { onShowPlan(false) }
            } else CurrentStep(view, onShowPlan, scale, data)
        }
    }
}

@Composable
private fun ColumnScope.CurrentStep(view: TimerNow, onShowPlan: (Boolean) -> Unit, scale: ScaleLive?, data: ScaleData?) {
    val following = scale?.following?.takeIf { view.active }
    if (following != null) ScaleBanner(following)
    // While the scale guides the brew, its reading leads the pour.
    FocusCard(view, scale?.takeIf { following == null && it.connected && view.timer.byWeight }, data)
    PlanStrip(view)
    if (scale != null && (view.phase == TimerPhase.POUR || view.phase == TimerPhase.WAIT)) LiveCurveCard(view, scale)
    val done = view.pulsesDone
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.Center) {
        val allDone = view.phase != TimerPhase.POUR && view.phase != TimerPhase.WAIT
        Text(
            if (!allDone) "Next" else if (done == view.pulseCount) "All ${view.pulseCount} pulses done" else "$done of ${view.pulseCount} pulses done",
            Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.titleMedium,
        )
        SoftTextButton(onClick = { onShowPlan(true) }) {
            Text(if (allDone || done == 0) "Show plan" else "$done done, show plan")
        }
    }
    if (view.phase == TimerPhase.POUR || view.phase == TimerPhase.WAIT) NextRows(view, compact = scale != null)
}

/** The state word and whose turn it is. A new step swaps it with a blur, starting on the same step change as the cue. */
private data class FocusTop(val step: Any, val state: String, val who: String?, val paused: Boolean)

/** The two large numbers. Within a step they update in place; a new step swaps them with a blur. */
private data class FocusNumbers(val step: Any, val leftLabel: String, val left: String, val leftUnit: String, val leftSoft: Boolean, val leftTarget: Boolean = false, val rightLabel: String? = null, val right: String = "", val rightUnit: String = "")

@Composable
private fun FocusCard(view: TimerNow, scale: ScaleLive?, data: ScaleData?) {
    val colors = MaterialTheme.colorScheme
    val pulseOf = "${view.pulse + 1} of ${view.pulseCount}"
    val pouring = view.phase == TimerPhase.POUR
    val step = Triple(view.phase, view.pulse, view.paused)
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.animateContentSize(tween(300)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val state = when {
                    view.paused -> "Paused"
                    pouring -> "Pour"
                    view.phase == TimerPhase.WAIT -> "Wait"
                    view.phase == TimerPhase.DRAWDOWN -> "Drawdown"
                    else -> "Timer stopped"
                }
                val who = when {
                    view.paused && pouring -> "Pour, pulse $pulseOf"
                    view.paused -> "Wait after pulse $pulseOf"
                    pouring -> "Pulse $pulseOf"
                    view.phase == TimerPhase.WAIT -> "After pulse $pulseOf"
                    else -> null
                }
                BlurSwap(FocusTop(step, state, who, view.paused), { it.step }, Modifier.weight(1f)) { top ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (top.paused) Icon(Icons.Outlined.Pause, null, Modifier.size(28.dp))
                            Text(top.state, style = MaterialTheme.typography.headlineLarge)
                        }
                        if (top.who != null) Text(top.who, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (pouring || view.phase == TimerPhase.WAIT) Column(Modifier.semantics(mergeDescendants = true) { }, horizontalAlignment = Alignment.End) {
                    Text("Brew time", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    Text(formatDuration(view.brewSeconds), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold).tabular())
                }
            }
            val waiting = view.phase == TimerPhase.WAIT
            val target = if (pouring || waiting) view.schedule[if (pouring) view.pulse else view.pulse + 1].cumulativeWater else 0.0
            val onScale = scale?.grams?.takeIf { pouring && !view.paused }
            val numbers = when {
                pouring || waiting -> FocusNumbers(
                    step, if (pouring) "Pour to" else "Then pour to", formatAmount(target), "g", !pouring || onScale != null, pouring,
                    if (onScale != null) "On scale" else if (!view.paused) "Time left" else if (pouring) "Pour held at" else "Wait held at",
                    onScale?.let(::tenths) ?: view.leftSeconds.toString(), if (onScale != null) "g" else "s",
                )
                view.phase == TimerPhase.DRAWDOWN -> FocusNumbers(step, "Brew time", formatDuration(view.brewSeconds), "", false)
                else -> null
            }
            if (numbers != null) BlurSwap(numbers, { it.step }, Modifier.fillMaxWidth()) { shown ->
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Numeral(shown.leftLabel, shown.left, shown.leftUnit, soft = shown.leftSoft, target = shown.leftTarget)
                    if (shown.rightLabel != null) Numeral(shown.rightLabel, shown.right, shown.rightUnit, alignEnd = true)
                }
            }
            if (pouring || waiting) {
                val pulse = view.recipe.pulses[view.pulse]
                if (onScale != null) {
                    // The bar and the line under it follow grams; seconds stay as a pacing hint.
                    val from = view.schedule[view.pulse].cumulativeWater - pulse.water
                    Bar(((onScale - from) / pulse.water).toFloat().coerceIn(0f, 1f))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${tenths((target - onScale).coerceAtLeast(0.0))} g to go · ${tenths(scale?.flow)} g/s", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        Text(if (view.overSeconds > 0) "+${view.overSeconds} s" else "${view.leftSeconds} s left", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Bar(view.stepFraction)
                    val detail = when {
                        view.paused -> "Paused for ${formatDuration(((view.now - view.timer.pausedAt!!) / 1000).toInt())}. Brew time is still running."
                        pouring -> "Add ${grams(pulse.water)} over ${pulse.pourSeconds} s" + if (view.pulse < view.pulseCount - 1 && pulse.waitSeconds > 0) ", then wait ${pulse.waitSeconds} s" else ""
                        else -> "${view.pulseName(view.pulse + 1)} starts at ${formatDuration(view.startSeconds(view.pulse + 1))}"
                    }
                    Text(detail, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            } else if (view.phase == TimerPhase.DRAWDOWN) {
                Text("Scheduled pours ended at ${formatDuration(view.poursEndSeconds)}", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                if (data != null) {
                    HorizontalDivider(color = colors.outlineVariant)
                    MeasuredTrio(view.recipe, data)
                }
            } else {
                Text(stoppedText(view) + ".", style = MaterialTheme.typography.bodyLarge)
                Text("Finish brew to save it. The total is not recorded unless you enter one.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Numeral(label: String, value: String, unit: String, soft: Boolean = false, alignEnd: Boolean = false, target: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    // The blueprint marks the grams to pour to now in red pencil.
    val ink = if (target && LocalLook.current.blueprint) targetColor() else if (soft) colors.onSurfaceVariant else colors.onSurface
    Column(Modifier.semantics(mergeDescendants = true) { }, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Counting numbers and scale readings update in place; only a new step animates them.
            Text(value, color = ink, style = MaterialTheme.typography.headlineLarge.copy(fontSize = 46.sp, lineHeight = 50.sp, fontWeight = if (soft) FontWeight.Medium else FontWeight.SemiBold).tabular())
            if (unit.isNotEmpty()) Text(unit, Modifier.padding(bottom = 7.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium))
        }
    }
}

/** The clock ticks five times a second; gliding between ticks makes the bars move continuously. */
@Composable
private fun glide(fraction: Float): Float = animateFloatAsState(fraction, tween(200, easing = LinearEasing), label = "progress").value

@Composable
private fun Bar(fraction: Float) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(4.dp).background(colors.outlineVariant, CircleShape).clearAndSetSemantics { }) {
        Box(Modifier.fillMaxWidth(glide(fraction)).fillMaxHeight().background(colors.onSurface, CircleShape))
    }
}

/** The whole recipe at a glance: tall blocks are pours, thin lines are waits. Decorative; the count is in the text. */
@Composable
private fun PlanStrip(view: TimerNow) {
    val colors = MaterialTheme.colorScheme
    val steps = view.recipe.timerSteps()
    Row(Modifier.fillMaxWidth().height(14.dp).clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { index, duration ->
            if (duration > 0) {
                val fraction = if (index < view.step) 1f else if (index == view.step) view.stepFraction else 0f
                Box(Modifier.weight(duration.toFloat()).height(if (index % 2 == 0) 14.dp else 4.dp).background(colors.outlineVariant, RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxWidth(glide(fraction)).fillMaxHeight().background(colors.onSurface, RoundedCornerShape(3.dp)))
                }
            }
        }
    }
}

/** Up to two upcoming rows, or one when [compact] makes room for the scale's curve. */
@Composable
private fun NextRows(view: TimerNow, compact: Boolean) {
    val colors = MaterialTheme.colorScheme
    val pouring = view.phase == TimerPhase.POUR
    val wait = view.recipe.pulses[view.pulse].waitSeconds.takeIf { pouring && view.pulse < view.pulseCount - 1 && it > 0 }
    val upcoming = (view.pulse + 1 until view.pulseCount).toList()
    val shown = upcoming.take(if (compact) 1 else if (wait != null) 1 else 2)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (wait != null && !compact) NextRow(if (view.paused) "Held" else formatDuration(((view.timer.stepAt + view.stepDuration - view.timer.startedAt + 500) / 1000).toInt()), "Wait $wait s", null)
        shown.forEach { index ->
            val predicted = view.startSeconds(index)
            val planned = view.schedule[index].startSeconds
            NextRow(
                if (view.paused) "Held" else formatDuration(predicted),
                "${view.pulseName(index)}, add ${grams(view.recipe.pulses[index].water)}",
                pulseDetail(view, index) + if (!view.paused && predicted != planned) ". Was ${formatDuration(planned)}" else "",
            )
        }
        if (upcoming.isEmpty()) NextRow(if (view.paused) "Held" else formatDuration(view.poursEndSeconds), "Drawdown", "Brew time keeps counting until Finish brew")
        val more = upcoming.size - shown.size
        if (more > 0) Text(if (more == 1) "1 more pulse" else "$more more pulses", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NextRow(time: String, title: String, detail: String?) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(time, Modifier.widthIn(min = 44.dp).padding(top = 2.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) Text(detail, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** One-line state shown above the full plan, so the countdown stays visible while reading it. */
@Composable
private fun PlanHeader(view: TimerNow) {
    val pulseOf = "pulse ${view.pulse + 1} of ${view.pulseCount}"
    val pouring = view.phase == TimerPhase.POUR
    Panel {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(when {
                view.paused -> "Paused, $pulseOf"
                pouring -> "Pour, $pulseOf"
                view.phase == TimerPhase.WAIT -> "Wait after $pulseOf"
                view.phase == TimerPhase.DRAWDOWN -> "Drawdown"
                else -> "Timer stopped"
            }, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleMedium)
            Text("Brew time ${formatDuration(view.brewSeconds)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (pouring || view.phase == TimerPhase.WAIT) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val target = view.schedule[if (pouring) view.pulse else view.pulse + 1].cumulativeWater
            Text((if (pouring) "Pour to " else "Then pour to ") + grams(target), style = MaterialTheme.typography.bodyLarge)
            Text("${view.leftSeconds} s ${if (view.paused) "held" else "left"}", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun FullPlan(view: TimerNow) {
    val colors = MaterialTheme.colorScheme
    SectionTitle("Planned pours")
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        view.schedule.forEachIndexed { index, scheduled ->
            val started = view.started(index)
            val current = started && index == view.pulse && view.phase == TimerPhase.POUR
            val actual = view.startSeconds(index)
            val difference = if (started) formatStartDifference(actual, scheduled.startSeconds) else null
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (!started && view.timer.paused) "Held" else formatDuration(actual), Modifier.widthIn(min = 44.dp).padding(top = 2.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Box(Modifier.padding(top = 6.dp).size(10.dp).background(if (started) colors.onSurface else colors.outlineVariant, CircleShape))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val name = view.pulseName(index)
                    Text(
                        if (started && !current) "$name, to ${grams(scheduled.cumulativeWater)}" else "$name, add ${grams(scheduled.pulse.water)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (started && !current) colors.onSurfaceVariant else colors.onSurface,
                    )
                    val lines = listOfNotNull(
                        if (started && !current) "Done" else pulseDetail(view, index),
                        difference?.let { "Started $it. Planned for ${formatDuration(scheduled.startSeconds)}" },
                    )
                    Text(lines.joinToString(". "), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Footer actions for a running timer: one row of buttons, with Finish brew smaller beneath. Buttons stack at large text sizes. [onUndoStart] is given just after the scale started the timer. */
@Composable
internal fun TimerFooter(view: TimerNow, onPause: () -> Unit, onResume: () -> Unit, onSkip: () -> Unit, onUndo: () -> Unit, onFinish: () -> Unit, onUndoStart: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val summary = view.skipSummary ?: "Timer started at the first pour".takeIf { onUndoStart != null }
        if (summary != null) Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = colors.inverseSurface, contentColor = colors.inverseOnSurface) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(summary, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = if (view.skipSummary == null && onUndoStart != null) onUndoStart else onUndo, colors = ButtonDefaults.textButtonColors(contentColor = colors.inverseOnSurface)) { Text("Undo") }
            }
        }
        val skip = view.skipLabel
        if (skip == null) PrimaryAction("Finish brew", icon = Icons.Outlined.Flag, onClick = onFinish) else {
            // Pause and Resume, and the skip label, cross-fade in place.
            val buttons: @Composable (Modifier) -> Unit = { modifier ->
                FooterButton(if (view.paused) "Resume" else "Pause", if (view.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, modifier, filled = view.paused, onClick = if (view.paused) onResume else onPause)
                FooterButton(skip, Icons.Outlined.SkipNext, modifier, onClick = onSkip)
            }
            if (LocalDensity.current.fontScale >= 1.6f) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons(Modifier.fillMaxWidth()) }
            else Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) { buttons(Modifier.weight(1f).fillMaxHeight()) }
            SoftTextButton(onClick = onFinish, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Outlined.Flag, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Finish brew")
            }
        }
    }
}

@Composable
private fun FooterButton(text: String, icon: ImageVector, modifier: Modifier, filled: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // Pause becoming Resume keeps the same button: its fill and label cross-fade instead of swapping controls.
    val container = if (filled) colors.primary else colors.soft
    val ink = if (filled) colors.onPrimary else colors.onSecondaryContainer
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Button(onClick, modifier.heightIn(min = 56.dp).pressScale(source), shape = MaterialTheme.shapes.small, colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = ink),
        elevation = null, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), interactionSource = source) {
        AnimatedContent(text to icon, label = "label", transitionSpec = { fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false) }) { (label, glyph) ->
            val leaving = transition.targetState == androidx.compose.animation.EnterExitState.PostExit
            Row(if (leaving) Modifier.clearAndSetSemantics { } else Modifier, verticalAlignment = Alignment.CenterVertically) {
                Icon(glyph, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, maxLines = 2)
            }
        }
    }
}

/** Timing saved with a brew record: the total, and each pulse's planned start and how far its actual start was from it, one line a pulse. A scale brew adds what the scale measured. */
@Composable
internal fun TimingPanel(recipe: Recipe, timing: BrewTiming, scale: ScaleData? = null) {
    val colors = MaterialTheme.colorScheme
    SectionTitle(if (scale != null) "Timing and water" else "Timing")
    // The blueprint draws the pulse table on its own, ruled like a schedule on a drawing.
    val ruled = LocalLook.current.blueprint
    val schedule = recipe.schedule()
    val unstarted = schedule.indices.any { timing.pulseStarts.getOrNull(it) == null }
    val key = "Started shows how far each pulse was from the plan: + late, \u2212 early" + (if (unstarted) "; \u2014 means it did not start" else "") + ". " + if (scale != null) "Reached is the scale's steady reading after each pulse; \u2014 means none was recorded." else "Pours are not measured."
    Panel {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Total brew time", Modifier.align(Alignment.CenterVertically))
            Text(timing.totalSeconds?.let(::formatDuration) ?: "Not recorded", style = MaterialTheme.typography.titleLarge)
        }
        val paused = if (timing.pausedSeconds > 0) " Includes ${formatDuration(timing.pausedSeconds)} paused." else ""
        Text(when {
            timing.totalSeconds == null -> "The timer stopped itself before Finish brew.$paused"
            timing.totalEdited -> "Edited.$paused"
            else -> "First pour to Finish brew.$paused"
        }, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (scale != null) {
            HorizontalDivider(color = colors.outlineVariant)
            MeasuredTrio(recipe, scale)
            HorizontalDivider(color = colors.outlineVariant)
            CurveLegend()
            val latest = schedule.indices.mapNotNull { index -> timing.pulseStarts.getOrNull(index)?.let { it - schedule[index].startSeconds } }.maxOrNull() ?: 0
            val summary = "Water over brew time against the plan. " + (scale.water?.let { "Ended at ${tenths(it)} grams" } ?: "Final water not recorded") +
                ", planned ${formatAmount(recipe.totalWater)} grams." + if (latest > 0) " Pulses started up to ${spokenDifference(latest)}." else ""
            WaterCurve(scale.curve, maxOf(timing.totalSeconds ?: 0, scale.curve.size), recipe.totalWater, plan = planLine(recipe), description = summary)
        }
        if (!ruled) {
            HorizontalDivider(color = colors.outlineVariant)
            TimingRows(schedule, timing, scale)
            Text(key, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (ruled) {
        Column(Modifier.fillMaxWidth().ruled()) { TimingRows(schedule, timing, scale) }
        Text(key, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

private class TimingRow(val name: String, val plan: String, val started: String, val late: Boolean, val reached: String?, val spoken: String)

@Composable
private fun TimingRows(schedule: List<ScheduledPulse>, timing: BrewTiming, scale: ScaleData?) {
    val colors = MaterialTheme.colorScheme
    val rows = schedule.mapIndexed { index, scheduled ->
        val actual = timing.pulseStarts.getOrNull(index)
        val offset = actual?.let { formatStartOffset(it, scheduled.startSeconds) }
        val name = scheduled.pulse.label.ifBlank { "Pulse ${index + 1}" }
        val reached = scale?.reached?.getOrNull(index)
        val spoken = "$name, ${spokenStart(scheduled.startSeconds)}, " + when {
            actual == null -> "not started"
            offset == null -> "started on time"
            else -> "started " + spokenDifference(actual - scheduled.startSeconds)
        } + if (scale != null) ", " + (reached?.let { "reached ${tenths(it)} grams" } ?: "no steady reading") else ""
        TimingRow(name, formatDuration(scheduled.startSeconds), when { actual == null -> "\u2014"; offset == null -> "On time"; else -> offset }, offset != null,
            if (scale != null) reached?.let { "${tenths(it)} g" } ?: "\u2014" else null, spoken)
    }
    val body = MaterialTheme.typography.bodyLarge
    val small = MaterialTheme.typography.bodySmall
    // The blueprint rules the table: light rules between rows and columns, and a double rule under the headings.
    val ruled = LocalLook.current.blueprint
    val cell = if (ruled) Modifier.padding(horizontal = 10.dp, vertical = 9.dp) else Modifier
    // Large text: the name on its own line, then the three values with their labels.
    val stacked: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(if (ruled) 0.dp else 12.dp)) {
            rows.forEachIndexed { index, row ->
                if (ruled && index > 0) CellRule()
                Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = row.spoken }.then(cell), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(row.name, style = body)
                    Text(listOfNotNull("Plan ${row.plan}", "Started ${row.started}", row.reached?.let { "Reached $it" }).joinToString(" \u00B7 "), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    if (LocalDensity.current.fontScale >= 1.3f) return stacked()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val strong = body.copy(fontWeight = FontWeight.SemiBold)
    fun widest(values: List<String>, header: String) = with(density) { maxOf(values.maxOf { measurer.measure(it, strong).size.width }, measurer.measure(header, small).size.width).toDp() + 1.dp }
    val planWidth = widest(rows.map { it.plan }, "Plan")
    val startedWidth = widest(rows.map { it.started }, "Started")
    val reachedWidth = if (scale != null) widest(rows.map { it.reached.orEmpty() }, "Reached") else 0.dp
    @Composable fun line(name: String, plan: String, started: String, reached: String?, style: androidx.compose.ui.text.TextStyle, header: Boolean, late: Boolean = false, modifier: Modifier) =
        Row(modifier.fillMaxWidth().then(if (ruled) Modifier.height(IntrinsicSize.Min) else Modifier), horizontalArrangement = Arrangement.spacedBy(if (ruled) 0.dp else 12.dp)) {
            val dim = colors.onSurfaceVariant
            val divide: @Composable () -> Unit = { if (ruled) CellRule(vertical = true) }
            Text(name, Modifier.weight(1f).then(cell), color = if (header) dim else colors.onSurface, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            divide()
            Text(plan, cell.width(planWidth), color = dim, style = style, textAlign = TextAlign.End, maxLines = 1)
            divide()
            Text(started, cell.width(startedWidth), color = if (late) colors.onSurface else dim, fontWeight = if (late) FontWeight.SemiBold else null, style = style, textAlign = TextAlign.End, maxLines = 1)
            if (reached != null) { divide(); Text(reached, cell.width(reachedWidth), color = if (header || reached == "\u2014") dim else colors.onSurface, style = style, textAlign = TextAlign.End, maxLines = 1) }
        }
    val table: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(if (ruled) 0.dp else 10.dp)) {
            line("Pulse", "Plan", "Started", if (scale != null) "Reached" else null, small, header = true, modifier = Modifier.clearAndSetSemantics { })
            if (ruled) DoubleRule()
            rows.forEachIndexed { index, row -> if (ruled && index > 0) CellRule(); line(row.name, row.plan, row.started, row.reached, body, header = false, late = row.late, modifier = Modifier.clearAndSetSemantics { contentDescription = row.spoken }) }
        }
    }
    if (!ruled) return table()
    // Ruled cells are padded, so a narrow screen can leave too little room for the pulse names; then the rows stack.
    val columns = if (scale != null) 3 else 2
    val fixed = planWidth + startedWidth + reachedWidth + 20.dp * (columns + 1) + 1.dp * columns
    val nameWidth = widest(rows.map { it.name }, "Pulse")
    BoxWithConstraints { if (maxWidth - fixed < nameWidth) stacked() else table() }
}

/** "9 seconds late", "1 minute 6 seconds early". */
private fun spokenDifference(seconds: Int): String {
    val size = kotlin.math.abs(seconds)
    val minutes = size / 60
    val rest = size % 60
    val amount = listOfNotNull(minutes.takeIf { it > 0 }?.let { if (it == 1) "1 minute" else "$it minutes" }, rest.takeIf { it > 0 || minutes == 0 }?.let { if (it == 1) "1 second" else "$it seconds" }).joinToString(" ")
    return amount + if (seconds < 0) " early" else " late"
}

/** The plan as written: water rises during each planned pour and holds during each wait. */
internal fun planLine(recipe: Recipe): List<Pair<Int, Double>> =
    listOf(0 to 0.0) + recipe.schedule().flatMapIndexed { index, scheduled ->
        listOf(scheduled.startSeconds to scheduled.cumulativeWater - scheduled.pulse.water, scheduled.startSeconds + scheduled.pulse.pourSeconds to scheduled.cumulativeWater)
    }

/** Keeps the screen on while the sheet shows a running timer. */
@Composable
internal fun KeepScreenOn(on: Boolean) {
    val host = LocalView.current
    DisposableEffect(host, on) {
        host.keepScreenOn = on
        onDispose { host.keepScreenOn = false }
    }
}

/** Vibrates and sounds at each step change on any screen of the app, as Settings allow. Draws nothing. */
@Composable
internal fun StepCue(recipe: Recipe, timer: BrewTimer, settings: TimerSettings) {
    val context = LocalContext.current
    val step = TimerNow(recipe, timer, rememberNow(100)).takeIf { it.active }?.step
    var last by remember { mutableStateOf(step) }
    LaunchedEffect(step) {
        // The timer's service cues the step when it is running; this covers the timer without it.
        if (step != null && last != null && step != last && !BrewTimerService.running) stepCue(context, settings)
        last = step
    }
}

/** Total brew time in the Finish brew review. It can be corrected, and must be entered after an automatic stop. */
@Composable
internal fun TotalBrewTime(timer: BrewTimer, onValid: (Boolean) -> Unit, onChange: (Int?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val measured = timer.finishedAt?.let { ((it - timer.startedAt + 500) / 1000).toInt() }
    val total = timer.totalOverride ?: measured
    var text by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = text
    // Validity belongs to this editor; leaving the review by any route must not keep Save disabled.
    DisposableEffect(Unit) { onDispose { onValid(true) } }
    // A total set elsewhere (the dripper-lift offer) replaces whatever the open field holds.
    LaunchedEffect(timer.totalOverride) {
        val open = text
        if (open != null && parseDuration(open) != timer.totalOverride && !(open.isBlank() && timer.totalOverride == null)) { text = null; onValid(true) }
    }
    if (editing != null) {
        // What is saved always matches what is shown: a blank field removes the correction, and an invalid one blocks saving.
        val valid = editing.isBlank() || parseDuration(editing) != null
        LaunchedEffect(valid) { onValid(valid) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        LibraryField(editing, { text = it; if (it.isBlank() || parseDuration(it) != null) onChange(parseDuration(it)) }, "Total brew time", error = if (!valid) "Use minutes and seconds, for example 3:24." else null, helper = "Minutes and seconds, for example 3:24. Leave blank to keep the timer's total.", modifier = Modifier.focusRequester(focus))
    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Total brew time", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Text(total?.let(::formatDuration) ?: "Not recorded", style = if (total != null) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge)
            if (total != null && total != measured) Text("Edited", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        IconAction(Icons.Outlined.Edit, if (total == null) "Enter total" else "Change total") { text = total?.let(::formatDuration).orEmpty() }
    }
    if (measured == null) Text("The timer stopped itself. Enter the total if you know it.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

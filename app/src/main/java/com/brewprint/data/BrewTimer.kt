package com.brewprint.data

import kotlinx.serialization.Serializable

const val FORGOTTEN_BREW_MILLIS = 15 * 60_000L
const val UNDO_MILLIS = 5_000L
const val SKIP_GUARD_MILLIS = 1_000L

@Serializable
data class TimerSettings(
    val liveNotification: Boolean = false,
    val vibrate: Boolean = true,
    val sound: Boolean = true,
    val keepScreenOn: Boolean = true,
)

/** What the timer measured for a saved brew. Times are seconds after the first pour began. */
@Serializable
data class BrewTiming(
    val totalSeconds: Int? = null,
    val totalEdited: Boolean = false,
    val pausedSeconds: Int = 0,
    val pulseStarts: List<Int?> = emptyList(),
)

/** Step durations in milliseconds: pour, wait, pour, wait ... pour. Even steps are pours. */
fun Recipe.timerSteps(): List<Long> = pulses.flatMapIndexed { index, pulse ->
    if (index < pulses.lastIndex) listOf(pulse.pourSeconds * 1000L, pulse.waitSeconds * 1000L) else listOf(pulse.pourSeconds * 1000L)
}

/**
 * A running brew timer. Everything shown is derived from these timestamps and the recipe,
 * so the timer keeps time while the app is closed. [step] equal to the step count means drawdown.
 */
@Serializable
data class BrewTimer(
    val startedAt: Long,
    val step: Int = 0,
    /** When [step] began, moved later by any time spent paused within it. */
    val stepAt: Long = startedAt,
    val pausedAt: Long? = null,
    val pausedMillis: Long = 0,
    /** Milliseconds after [startedAt] at which each pulse that has begun started. */
    val pulseStarts: List<Long> = listOf(0),
    val finishedAt: Long? = null,
    /** A total entered by hand in the Finish brew review, in seconds. */
    val totalOverride: Int? = null,
    /** The timer as it was just before the last skip, offered back until [undoUntil]. */
    val undo: BrewTimer? = null,
    val undoUntil: Long = 0,
    /** The scale ends pours: a pour is held until it reaches its target, or "Pour done". */
    val byWeight: Boolean = false,
    /** Added to the current step's planned duration: a pour that reached its target early lengthens the wait after it. */
    val stepExtra: Long = 0,
) {
    val paused: Boolean get() = pausedAt != null
    val finished: Boolean get() = finishedAt != null

    /** Moves [step] forward to where the schedule stands at [now], recording pulse starts on the way. */
    fun advanced(recipe: Recipe, now: Long): BrewTimer {
        val steps = recipe.timerSteps()
        val time = pausedAt ?: finishedAt ?: now
        var current = step
        var at = stepAt
        var extra = stepExtra
        val starts = pulseStarts.toMutableList()
        while (current < steps.size && !(byWeight && current % 2 == 0) && time - at >= steps[current] + extra) {
            at += steps[current] + extra
            extra = 0
            current++
            if (current < steps.size && current % 2 == 0) starts += at - startedAt
        }
        return if (current == step) this else copy(step = current, stepAt = at, stepExtra = extra, pulseStarts = starts)
    }

    /** When the timer stops itself: 15 minutes after the planned end of the pours, or after a pause began. */
    fun stopsAt(recipe: Recipe): Long =
        (pausedAt ?: (stepAt + stepExtra + recipe.timerSteps().drop(step).sum())) + FORGOTTEN_BREW_MILLIS

    /** True once a brew left alone has stopped itself. */
    fun stopped(recipe: Recipe, now: Long): Boolean = !finished && now >= stopsAt(recipe)

    fun brewMillis(recipe: Recipe, now: Long): Long = (finishedAt ?: minOf(now, stopsAt(recipe))) - startedAt

    /** Time already spent in the current step. */
    fun stepMillis(now: Long): Long = (pausedAt ?: finishedAt ?: now) - stepAt

    fun canSkip(recipe: Recipe, now: Long): Boolean {
        val current = advanced(recipe, now)
        return !current.finished && !current.stopped(recipe, now) && current.step < recipe.timerSteps().size &&
            (current.paused || now - current.stepAt >= SKIP_GUARD_MILLIS)
    }

    fun canUndo(now: Long): Boolean = undo != null && !finished && now < undoUntil

    fun pause(recipe: Recipe, now: Long): BrewTimer {
        val current = advanced(recipe, now)
        val drawdown = current.step >= recipe.timerSteps().size
        return if (current.paused || current.finished || drawdown || current.stopped(recipe, now)) current else current.copy(pausedAt = now)
    }

    fun resume(recipe: Recipe, now: Long): BrewTimer {
        val since = pausedAt ?: return this
        if (finished || stopped(recipe, now)) return this
        return copy(stepAt = stepAt + (now - since), pausedMillis = pausedMillis + (now - since), pausedAt = null)
    }

    /** Ends the current step now. Later steps keep their durations, so they all move earlier. */
    fun skip(recipe: Recipe, now: Long): BrewTimer {
        if (!canSkip(recipe, now)) return this
        val before = advanced(recipe, now)
        val running = before.resume(recipe, now)
        val next = running.step + 1
        val startsPulse = next < recipe.timerSteps().size && next % 2 == 0
        return running.copy(
            step = next,
            stepAt = now,
            stepExtra = 0,
            pulseStarts = if (startsPulse) running.pulseStarts + (now - startedAt) else running.pulseStarts,
            undo = before.copy(undo = null),
            undoUntil = now + UNDO_MILLIS,
        ).advanced(recipe, now)
    }

    /**
     * The scale reached the current pour's target. Before the planned pour time, the wait after it runs
     * longer so the next pulse keeps its scheduled start; after it, later steps move later by the overrun.
     */
    fun reachTarget(recipe: Recipe, now: Long): BrewTimer {
        val current = advanced(recipe, now)
        val steps = recipe.timerSteps()
        if (current.paused || current.finished || current.step >= steps.size || current.step % 2 != 0) return current
        val next = current.step + 1
        val early = (current.stepAt + steps[current.step] - now).coerceAtLeast(0)
        return current.copy(step = next, stepAt = now, stepExtra = if (next < steps.size) early else 0)
    }

    /** The scale stopped guiding: pours follow the clock again, and a pour already past its planned time ends now. */
    fun followClock(recipe: Recipe, now: Long): BrewTimer {
        if (!byWeight) return this
        val current = advanced(recipe, now)
        val steps = recipe.timerSteps()
        val overrun = !current.paused && !current.finished && current.step < steps.size && current.step % 2 == 0 && now - current.stepAt >= steps[current.step]
        return if (overrun) current.copy(byWeight = false, step = current.step + 1, stepAt = now, stepExtra = 0).advanced(recipe, now)
        else current.copy(byWeight = false)
    }

    /** Puts back the timer as it was before the last skip. The schedule is then recomputed from the clock. */
    fun undone(now: Long): BrewTimer = if (canUndo(now)) undo!!.copy(byWeight = byWeight) else this

    fun finish(recipe: Recipe, now: Long): BrewTimer =
        if (finished || stopped(recipe, now)) this else advanced(recipe, now).copy(finishedAt = now, undo = null)

    /** Reverses [finish], restoring the schedule and any pause exactly as they were. */
    fun unfinished(): BrewTimer = if (finished) copy(finishedAt = null, totalOverride = null) else this

    fun timing(recipe: Recipe): BrewTiming {
        val end = finishedAt ?: stopsAt(recipe)
        val settled = advanced(recipe, end)
        val measured = finishedAt?.let { seconds(it - startedAt) }
        return BrewTiming(
            totalSeconds = totalOverride ?: measured,
            totalEdited = totalOverride != null && totalOverride != measured,
            pausedSeconds = seconds(pausedMillis + (pausedAt?.let { end - it } ?: 0)),
            pulseStarts = recipe.pulses.indices.map { index -> settled.pulseStarts.getOrNull(index)?.let(::seconds) },
        )
    }
}

private fun seconds(millis: Long): Int = ((millis + 500) / 1000).toInt()

/** "12 s early", "1:05 late", or null when the start matches the plan. */
fun formatStartDifference(actualSeconds: Int, plannedSeconds: Int): String? {
    val difference = actualSeconds - plannedSeconds
    if (difference == 0) return null
    val size = kotlin.math.abs(difference)
    return (if (size < 60) "$size s" else formatDuration(size)) + if (difference < 0) " early" else " late"
}

/** How far a start was from its plan, signed: "+9 s" late, "−12 s" early, "+1:06"; null when on time. */
fun formatStartOffset(actualSeconds: Int, plannedSeconds: Int): String? {
    val difference = actualSeconds - plannedSeconds
    if (difference == 0) return null
    val size = kotlin.math.abs(difference)
    return (if (difference < 0) "−" else "+") + if (size < 60) "$size s" else formatDuration(size)
}

/** Parses "3:24" or "204" into seconds. */
fun parseDuration(text: String): Int? {
    val parts = text.trim().split(":")
    return when (parts.size) {
        1 -> parts[0].toIntOrNull()?.takeIf { it > 0 }
        2 -> {
            val minutes = parts[0].toIntOrNull() ?: return null
            val seconds = parts[1].toIntOrNull() ?: return null
            if (minutes < 0 || seconds !in 0..59 || parts[1].length != 2) null else (minutes * 60 + seconds).takeIf { it > 0 }
        }
        else -> null
    }
}

enum class TimerPhase { POUR, WAIT, DRAWDOWN, FINISHED, STOPPED }

/** A brew's timer is running: started, and neither finished nor stopped after being left alone. */
fun AppState.timerRunning(now: Long): Boolean {
    val timer = draft?.timer ?: return false
    val recipe = draft.recipe ?: return false
    return TimerNow(recipe, timer, now).active
}

/** The timer as it stands at one moment, with the wording shared by the brew sheet and the live notification. */
class TimerNow(val recipe: Recipe, initial: BrewTimer, val now: Long) {
    val timer: BrewTimer = initial.advanced(recipe, now)
    val schedule: List<ScheduledPulse> = recipe.schedule()
    private val steps = recipe.timerSteps()
    val step: Int get() = timer.step
    val pulseCount: Int get() = recipe.pulses.size
    val phase: TimerPhase = when {
        this.timer.finished -> TimerPhase.FINISHED
        this.timer.stopped(recipe, now) -> TimerPhase.STOPPED
        step >= steps.size -> TimerPhase.DRAWDOWN
        step % 2 == 0 -> TimerPhase.POUR
        else -> TimerPhase.WAIT
    }
    val active: Boolean get() = phase != TimerPhase.FINISHED && phase != TimerPhase.STOPPED
    val paused: Boolean get() = active && timer.paused
    /** The pulse being poured, or the one just poured during a wait or drawdown. */
    val pulse: Int = minOf(step / 2, recipe.pulses.lastIndex)
    val pulsesDone: Int = if (step < steps.size && step % 2 == 0) pulse else pulse + 1
    val stepDuration: Long = steps.getOrElse(step) { 0 } + timer.stepExtra
    val leftSeconds: Int = ((stepDuration - timer.stepMillis(now) + 999) / 1000).toInt().coerceAtLeast(0)
    /** Seconds a pour held for the scale has run past its planned time. */
    val overSeconds: Int = if (phase == TimerPhase.POUR && timer.byWeight) ((timer.stepMillis(now) - stepDuration) / 1000).toInt().coerceAtLeast(0) else 0
    val stepFraction: Float = if (stepDuration > 0) (timer.stepMillis(now).toFloat() / stepDuration).coerceIn(0f, 1f) else 1f
    val brewSeconds: Int = (timer.brewMillis(recipe, now) / 1000).toInt()
    val poursEndSeconds: Int = ((timer.stepAt + timer.stepExtra + steps.drop(step).sum() - timer.startedAt + 500) / 1000).toInt()
    /** Schedule position in planned time, for progress bars. */
    val plannedMillis: Long = steps.take(step).sum() + timer.stepMillis(now).coerceIn(0, stepDuration)
    val plannedTotalMillis: Long = steps.sum()

    fun pulseName(index: Int): String = recipe.pulses[index].label.ifBlank { "Pulse ${index + 1}" }
    fun started(index: Int): Boolean = index < timer.pulseStarts.size

    /** Actual start of a pulse that has begun, otherwise where the schedule now puts it. */
    fun startSeconds(index: Int): Int {
        val millis = timer.pulseStarts.getOrNull(index) ?: (timer.stepAt + timer.stepExtra + steps.subList(step, index * 2).sum() - timer.startedAt)
        return ((millis + 500) / 1000).toInt()
    }

    val skipLabel: String? = when (phase) {
        TimerPhase.POUR -> "Pour done"
        TimerPhase.WAIT -> "Start pulse ${pulse + 2}"
        else -> null
    }
    val canSkip: Boolean get() = timer.canSkip(recipe, now)
    val canUndo: Boolean get() = timer.canUndo(now)

    /** What the last skip did, shown beside Undo. */
    val skipSummary: String? = this.timer.undo?.takeIf { canUndo }?.let { before ->
        if (this.timer.pulseStarts.size > before.pulseStarts.size) {
            val index = before.pulseStarts.size
            "${pulseName(index)} started ${formatStartDifference(startSeconds(index), schedule[index].startSeconds) ?: "on schedule"}"
        } else {
            val saved = ((steps[before.step] - before.stepMillis(this.timer.undoUntil - UNDO_MILLIS) + 500) / 1000).toInt()
            if (saved > 0) "Pour ended $saved s early" else "Pour ended"
        }
    }
}

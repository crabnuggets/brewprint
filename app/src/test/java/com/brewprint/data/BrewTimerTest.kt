package com.brewprint.data

import com.brewprint.timer.ACTION_FINISH
import com.brewprint.timer.ACTION_SKIP
import com.brewprint.timer.ACTION_UNDO
import com.brewprint.timer.ACTION_UNFINISH
import com.brewprint.timer.applyTimerAction
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BrewTimerTest {
    // Pours at 0:00, 0:45, 1:20, 1:55 and 2:30; scheduled pours end at 2:45.
    private val recipe = Recipe(name = "Five-pulse filter", dose = 15.0, pulses = listOf(
        Pulse("Bloom", 45.0, 10, 35), Pulse(water = 50.0, pourSeconds = 15, waitSeconds = 20), Pulse(water = 50.0, pourSeconds = 15, waitSeconds = 20),
        Pulse(water = 50.0, pourSeconds = 15, waitSeconds = 20), Pulse(water = 45.0, pourSeconds = 15)))
    private val start = 1_000_000L
    private fun at(seconds: Double) = start + (seconds * 1000).toLong()
    private fun at(seconds: Int) = at(seconds.toDouble())
    private val timer = BrewTimer(startedAt = start)

    @Test fun `an untouched brew records every pulse on schedule`() {
        val view = TimerNow(recipe, timer, at(88))
        assertEquals(TimerPhase.POUR, view.phase)
        assertEquals(2, view.pulse)
        assertEquals(7, view.leftSeconds)
        assertEquals(88, view.brewSeconds)
        assertEquals("Pour done", view.skipLabel)

        assertEquals(TimerPhase.DRAWDOWN, TimerNow(recipe, timer, at(170)).phase)
        val timing = timer.finish(recipe, at(204)).timing(recipe)
        assertEquals(BrewTiming(totalSeconds = 204, pulseStarts = listOf(0, 45, 80, 115, 150)), timing)
    }

    @Test fun `skipping a wait starts the pulse early and moves later pulses earlier`() {
        val skipped = timer.skip(recipe, at(103))
        val view = TimerNow(recipe, skipped, at(105))
        assertEquals(TimerPhase.POUR, view.phase)
        assertEquals(3, view.pulse)
        assertEquals("Pulse 4 started 12 s early", view.skipSummary)
        assertEquals(138, view.startSeconds(4))
        assertEquals(listOf(0, 45, 80, 103, 138), skipped.finish(recipe, at(200)).timing(recipe).pulseStarts)
    }

    @Test fun `undo within five seconds restores the schedule and expires after`() {
        val skipped = timer.skip(recipe, at(103))
        val undone = skipped.undone(at(107))
        assertEquals(TimerPhase.WAIT, TimerNow(recipe, undone, at(107)).phase)
        assertEquals(listOf(0, 45, 80, 115, 150), undone.finish(recipe, at(200)).timing(recipe).pulseStarts)
        assertSame(skipped, skipped.undone(at(108)))
    }

    @Test fun `undo still works after a step changes on its own`() {
        val short = Recipe(name = "Short waits", dose = 10.0, pulses = listOf(
            Pulse(water = 40.0, pourSeconds = 10, waitSeconds = 3), Pulse(water = 30.0, pourSeconds = 10, waitSeconds = 3), Pulse(water = 30.0, pourSeconds = 10)))
        // Pour done at 0:05 starts a 3 s wait, so pulse 2 begins by itself at 0:08, inside the Undo window.
        val skipped = timer.skip(short, at(5))
        val moved = TimerNow(short, skipped, at(9))
        assertEquals(TimerPhase.POUR, moved.phase)
        assertEquals(1, moved.pulse)
        assertEquals("Pulse 2 started 5 s early", moved.skipSummary)
        val restored = TimerNow(short, moved.timer.undone(at(9)), at(9))
        assertEquals(TimerPhase.POUR, restored.phase)
        assertEquals(0, restored.pulse)
        assertEquals(13, restored.startSeconds(1))
    }

    @Test fun `skip ignores taps for one second after a step change`() {
        assertSame(timer, timer.skip(recipe, at(0.5)))
        assertSame(timer, timer.skip(recipe, at(10.5)))
        assertEquals(2, timer.skip(recipe, at(11)).step)
    }

    @Test fun `pause holds the schedule while brew time keeps running`() {
        val paused = timer.pause(recipe, at(137))
        val held = TimerNow(recipe, paused, at(150))
        assertTrue(held.paused)
        assertEquals(TimerPhase.WAIT, held.phase)
        assertEquals(13, held.leftSeconds)
        assertEquals(150, held.brewSeconds)
        assertEquals("Start pulse 5", held.skipLabel)

        val timing = paused.resume(recipe, at(152)).finish(recipe, at(219)).timing(recipe)
        assertEquals(165, timing.pulseStarts.last())
        assertEquals(15, timing.pausedSeconds)
        assertEquals(219, timing.totalSeconds)
    }

    @Test fun `skipping while paused resumes at the next pulse and undo returns to the pause`() {
        val paused = timer.pause(recipe, at(137))
        val skipped = paused.skip(recipe, at(140))
        assertFalse(skipped.paused)
        assertEquals(listOf(0L, 45_000L, 80_000L, 115_000L, 140_000L), skipped.pulseStarts)
        assertEquals(paused.advanced(recipe, at(137)), skipped.undone(at(142)))
    }

    @Test fun `pause is not offered in drawdown`() {
        assertFalse(timer.pause(recipe, at(170)).paused)
    }

    @Test fun `back from the review restores a pause exactly`() {
        val paused = timer.pause(recipe, at(137))
        val finished = paused.finish(recipe, at(160)).copy(totalOverride = 170)
        assertEquals(160, TimerNow(recipe, finished, at(500)).brewSeconds)
        assertEquals(paused, finished.unfinished())
    }

    @Test fun `a forgotten brew stops 15 minutes after the last pour with no total`() {
        val stopAt = at(165 + 900)
        assertFalse(timer.stopped(recipe, stopAt - 1))
        val view = TimerNow(recipe, timer, stopAt + 60_000)
        assertEquals(TimerPhase.STOPPED, view.phase)
        assertEquals(165 + 900, view.brewSeconds)
        assertSame(timer, timer.finish(recipe, stopAt + 60_000))
        assertEquals(BrewTiming(totalSeconds = null, pulseStarts = listOf(0, 45, 80, 115, 150)), timer.timing(recipe))
        assertEquals(BrewTiming(totalSeconds = 200, totalEdited = true, pulseStarts = listOf(0, 45, 80, 115, 150)), timer.copy(totalOverride = 200).timing(recipe))
    }

    @Test fun `a brew forgotten while paused stops 15 minutes after the pause began`() {
        val paused = timer.pause(recipe, at(137))
        assertFalse(paused.stopped(recipe, at(137 + 899)))
        assertTrue(paused.stopped(recipe, at(137 + 900)))
        assertSame(paused, paused.resume(recipe, at(137 + 901)))
        val timing = paused.timing(recipe)
        assertNull(timing.totalSeconds)
        assertEquals(listOf(0, 45, 80, 115, null), timing.pulseStarts)
        assertEquals(900, timing.pausedSeconds)
    }

    @Test fun `a zero wait starts the next pour immediately`() {
        val quick = Recipe(name = "Quick", dose = 10.0, pulses = listOf(Pulse(water = 50.0, pourSeconds = 10), Pulse(water = 50.0, pourSeconds = 10)))
        val view = TimerNow(quick, timer, at(12))
        assertEquals(TimerPhase.POUR, view.phase)
        assertEquals(1, view.pulse)
        assertEquals("Pour done", view.skipLabel)
        assertEquals(listOf(0L, 5_000L), timer.skip(quick, at(5)).pulseStarts)
    }

    @Test fun `an edited total is marked`() {
        val finished = timer.finish(recipe, at(204))
        assertFalse(finished.copy(totalOverride = 204).timing(recipe).totalEdited)
        assertEquals(BrewTiming(210, true, 0, listOf(0, 45, 80, 115, 150)), finished.copy(totalOverride = 210).timing(recipe))
    }

    @Test fun `notification actions ignore stale taps`() {
        val draft = BrewDraft(recipe = recipe, stage = DraftStage.SHEET, timer = timer)
        val skipped = applyTimerAction(ACTION_SKIP, draft, timer, recipe, at(103))
        // Undo has just turned back into the skip action: a tap meant for Undo must not skip again.
        assertEquals(skipped, applyTimerAction(ACTION_SKIP, skipped, skipped.timer!!, recipe, at(108.5)))
        assertEquals(skipped, applyTimerAction(ACTION_UNDO, skipped, skipped.timer, recipe, at(108.5)))
        assertEquals(5, applyTimerAction(ACTION_UNDO, skipped, skipped.timer, recipe, at(104)).timer!!.step)

        // Back to the Brew screen leaves the draft on setup; Finish brew from the notification must still work.
        assertEquals(DraftStage.REVIEW, applyTimerAction(ACTION_FINISH, draft.copy(stage = DraftStage.SETUP), timer, recipe, at(200)).stage)
        val finished = applyTimerAction(ACTION_FINISH, draft, timer, recipe, at(200))
        assertEquals(DraftStage.REVIEW, finished.stage)
        assertEquals(finished, applyTimerAction(ACTION_UNFINISH, finished, finished.timer!!, recipe, at(206)))
        val reopened = applyTimerAction(ACTION_UNFINISH, finished, finished.timer, recipe, at(203))
        assertEquals(DraftStage.SHEET, reopened.stage)
        assertEquals(timer.advanced(recipe, at(200)), reopened.timer)
    }

    @Test fun `durations and differences read plainly`() {
        assertEquals(204, parseDuration("3:24"))
        assertEquals(90, parseDuration("90"))
        assertNull(parseDuration("3:7"))
        assertNull(parseDuration("3:60"))
        assertNull(parseDuration(""))
        assertEquals("12 s early", formatStartDifference(103, 115))
        assertEquals("1:05 late", formatStartDifference(180, 115))
        assertNull(formatStartDifference(115, 115))
        // The record's one-line form: signed, so a column of offsets stays narrow.
        assertEquals("+9 s", formatStartOffset(99, 90))
        assertEquals("−12 s", formatStartOffset(103, 115))
        assertEquals("+1:06", formatStartOffset(216, 150))
        assertNull(formatStartOffset(115, 115))
    }

    @Test fun `data saved before the timer loads unchanged`() {
        val json = Json { ignoreUnknownKeys = true }
        assertNull(json.decodeFromString<BrewDraft>("""{"id":"a","startedAt":1}""").timer)
        assertEquals(TimerSettings(), json.decodeFromString<AppState>("{}").timerSettings)
    }
}

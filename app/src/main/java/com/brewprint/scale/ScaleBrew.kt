package com.brewprint.scale

import com.brewprint.data.*
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// The thresholds of "Brewing with a scale" in docs/ux.md. Grams, grams a second, and milliseconds.
private const val QUIET_FLOW = 0.5
private const val STEADY_MILLIS = 1_000L
private const val STEADY_SPREAD = 1.0
private const val RISE_GRAMS = 2.0
private const val RISE_MILLIS = 300L
private const val TARGET_SLACK = 1.0
private const val DROP_GRAMS = 5.0
private const val RETURN_GRAMS = 3.0
/** A dripper weighs far more than this, and nothing else takes so much off the scale in drawdown. */
private const val LIFT_GRAMS = 20.0
private const val ZERO_GRAMS = 1.0
private const val TARE_MILLIS = 1_500L

/**
 * Reads the scale into the active brew, as "Brewing with a scale" in docs/ux.md describes. Readings
 * arrive ten times a second; the draft is written only when something happens.
 */
class ScaleBrew(private val repository: BrewRepository, private val send: (ByteArray) -> Unit) {
    private val mutableLive = MutableStateFlow(ScaleLive())
    val live: StateFlow<ScaleLive> = mutableLive.asStateFlow()

    private var tareAt: Long? = null
    /** The scale did not zero after a tare command; its own tare button is needed. */
    private var tareFailed = false
    private var quietSince: Long? = null
    private var quietGrams = 0.0
    private var riseAt: Long? = null
    /** The last steady reading. Kept while the dripper is off, so its return can be recognised. */
    private var steady: Double? = null
    private var zeroed = false
    /** A wait may start the next pulse early: flow has stopped since the step began. */
    private var detect = false
    private var dripperOff = false
    private var lost = false
    private var lostForGood = false
    private var lastStep = -1
    /** The highest reading since the current step began. */
    private var high = 0.0
    private var brewId: String? = null
    private val curve = mutableListOf<Double?>()
    /** Flow for each second of [curve], shown live and not saved. */
    private val flows = mutableListOf<Double?>()

    fun tare(now: Long) {
        tareAt = now
        zeroed = false
        ScaleProtocol.tareFrames().forEach(send)
    }

    fun connected(on: Boolean, now: Long) {
        if (!on) {
            lose(now)
            flush()
            quietSince = null
            riseAt = null
            zeroed = false
        }
        mutableLive.update { it.copy(connected = on, grams = if (on) it.grams else null, flow = 0.0, steady = false, zeroed = false, following = following()) }
    }

    fun battery(percent: Int) = mutableLive.update { it.copy(battery = percent) }

    /**
     * The scale went silent, disconnected, overloaded or was forgotten: a running scale brew follows the clock.
     * This does not depend on the pairing, so forgetting the scale mid-brew still hands the brew to the clock.
     */
    fun lose(now: Long) {
        val draft = repository.state.value.draft ?: return
        val timer = draft.timer ?: return
        val recipe = draft.recipe ?: return
        if (draft.scale == null || !TimerNow(recipe, timer, now).active) return
        if (draft.id != brewId) attach(draft)
        if (lost) return
        lost = true
        repository.updateTimer { d, timer, recipe -> d.copy(timer = timer.followClock(recipe, now), scale = d.scale?.copy(curve = curve.toList())) }
        mutableLive.update { it.copy(following = following()) }
    }

    fun reading(now: Long, grams: Double, flow: Double, overload: Boolean) {
        if (overload) return lose(now)
        // Only the scale's own zero makes its display match the targets, so a reading near 0 g is the proof of a tare.
        if (abs(grams) <= ZERO_GRAMS / 2) { tareAt = null; tareFailed = false }
        tareAt?.let { if (now - it >= TARE_MILLIS) { tareAt = null; tareFailed = true } }
        if (abs(flow) < QUIET_FLOW && (quietSince == null || abs(grams - quietGrams) <= STEADY_SPREAD)) {
            if (quietSince == null) { quietSince = now; quietGrams = grams }
        } else quietSince = null
        if (flow >= QUIET_FLOW) { if (riseAt == null) riseAt = now } else riseAt = null
        val quiet = quietSince?.let { now - it >= STEADY_MILLIS } == true
        val draft = repository.state.value.draft
        if (draft?.id != brewId) attach(draft)
        // An untared scale cannot arm the start; a running brew keeps reading it either way.
        if (draft != null && (draft.timer != null || tareAt == null && !tareFailed)) follow(draft, now, grams, flow, quiet)
        mutableLive.update {
            it.copy(connected = true, grams = grams, flow = flow.coerceAtLeast(0.0), steady = quiet, zeroed = zeroed, tareFailed = tareFailed,
                following = following(), curve = curve.toList(), flows = flows.toList())
        }
    }

    /** Starts following [draft], picking up any curve it already saved. */
    private fun attach(draft: BrewDraft?) {
        brewId = draft?.id
        steady = null
        zeroed = false
        detect = false
        dripperOff = false
        lost = false
        lostForGood = false
        lastStep = -1
        curve.clear()
        curve.addAll(draft?.scale?.curve.orEmpty())
        flows.clear()
        flows.addAll(List(curve.size) { null })
    }

    private fun following(): String? = when {
        lostForGood -> "Scale reading changed"
        lost -> "Scale disconnected"
        dripperOff -> "Dripper off"
        else -> null
    }

    private fun follow(draft: BrewDraft, now: Long, grams: Double, flow: Double, quiet: Boolean) {
        val recipe = draft.recipe ?: return
        val data = draft.scale ?: return
        val timer = draft.timer ?: return armed(draft, now, grams, quiet)
        val view = TimerNow(recipe, timer, now)
        if (!view.active) return flush()
        record(timer, now, grams, flow)
        // Drawdown keeps the last pour's peak, so a dripper already off at its first reading still shows as a fall.
        // The peak is capped at the final target, so a brief press on the brewer cannot become a lift.
        if (view.step != lastStep) {
            lastStep = view.step
            high = if (view.phase == TimerPhase.DRAWDOWN) maxOf(minOf(high, view.schedule.last().cumulativeWater), grams) else grams
            detect = false
        }
        if (view.paused) detect = false
        high = maxOf(high, grams)
        val last = steady
        // Water only accumulates, so in drawdown the dripper coming off shows as a fall from the highest reading,
        // even before the water has settled.
        val dropped = if (view.phase == TimerPhase.DRAWDOWN) grams < high - LIFT_GRAMS else last != null && grams < last - DROP_GRAMS
        when {
            lostForGood -> return
            lost -> {
                lost = false
                if (last != null && grams >= last - RETURN_GRAMS) { detect = false; guide(now) } else lostForGood = true
                return
            }
            dripperOff -> {
                if (last != null && quiet && abs(grams - last) <= RETURN_GRAMS) { dripperOff = false; detect = false; guide(now) }
                return
            }
            dropped -> {
                // After the last pour a drop is the dripper coming off; before it, guidance pauses until it is back.
                if (view.phase == TimerPhase.DRAWDOWN) {
                    if (data.liftSeconds == null) save { it.copy(liftSeconds = view.brewSeconds) }
                } else {
                    dripperOff = true
                    repository.updateTimer { d, t, r -> d.copy(timer = t.followClock(r, now)) }
                }
                return
            }
        }
        if (quiet) steady = grams
        when (view.phase) {
            TimerPhase.POUR -> if (timer.byWeight && !view.paused && grams >= view.schedule[view.pulse].cumulativeWater - TARGET_SLACK) {
                repository.updateTimer { d, t, r -> d.copy(timer = t.reachTarget(r, now)) }
            }
            TimerPhase.WAIT -> {
                if (quiet && !view.paused && !detect) {
                    detect = true
                    if (data.reached.getOrNull(view.pulse) == null) save { it.copy(reached = it.reached.with(view.pulse, grams)) }
                }
                val rise = riseAt
                if (detect && timer.byWeight && last != null && rise != null && now - rise >= RISE_MILLIS && grams >= last + RISE_GRAMS) {
                    detect = false
                    repository.updateTimer { d, t, r -> d.copy(timer = t.skip(r, now)) }
                }
            }
            TimerPhase.DRAWDOWN -> if (quiet && data.water == null && data.liftSeconds == null) {
                save { it.copy(water = grams, reached = it.reached.with(recipe.pulses.lastIndex, grams)) }
            }
            else -> {}
        }
    }

    /** Before the first pour: a steady reading near 0 g arms the start; a rise from there starts the timer. */
    private fun armed(draft: BrewDraft, now: Long, grams: Double, quiet: Boolean) {
        if (draft.scaleStep != ScaleStep.ARMED || draft.stage != DraftStage.SHEET) { zeroed = false; return }
        // Lifting the brewer disarms until it is back and steady at 0 g, so putting it down cannot start the timer.
        if (grams < -ZERO_GRAMS) zeroed = false
        if (quiet) { zeroed = abs(grams) <= ZERO_GRAMS; steady = grams }
        val rise = riseAt
        if (zeroed && rise != null && now - rise >= RISE_MILLIS && grams >= RISE_GRAMS) {
            zeroed = false
            curve.clear()
            flows.clear()
            repository.changeDraft { it.copy(timer = BrewTimer(startedAt = rise, byWeight = true)) }
        }
    }

    /** One reading a second from the first pour, with a gap for each second the scale was lost. */
    private fun record(timer: BrewTimer, now: Long, grams: Double, flow: Double) {
        val second = ((now - timer.startedAt) / 1000).toInt()
        if (second < 0) return
        while (curve.size < second) { curve.add(null); flows.add(null) }
        if (curve.size == second) {
            val seen = !lost && !lostForGood
            curve.add(grams.takeIf { seen })
            flows.add(flow.coerceAtLeast(0.0).takeIf { seen })
        }
    }

    /** Weight guides the brew again, from where the clock has taken it. */
    private fun guide(now: Long) = repository.updateTimer { d, t, r -> d.copy(timer = t.advanced(r, now).copy(byWeight = true)) }

    /**
     * Saves the curve samples still held in memory. Runs after the timer stops, at the next reading or when the scale
     * disconnects or is released, so leaving the app or switching the scale off cannot lose them.
     */
    private fun flush() {
        val draft = repository.state.value.draft ?: return
        if (draft.id == brewId && draft.scale != null && draft.scale.curve != curve) save { it }
    }

    private fun save(transform: (ScaleData) -> ScaleData) = repository.changeDraft { d ->
        d.copy(scale = d.scale?.let(transform)?.copy(curve = curve.toList()))
    }
}

private fun List<Double?>.with(index: Int, value: Double): List<Double?> =
    (0 until maxOf(size, index + 1)).map { if (it == index) value else getOrNull(it) }

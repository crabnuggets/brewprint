package com.brewprint.scale

import android.content.ContextWrapper
import com.brewprint.data.*
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScaleBrewTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var repository: BrewRepository
    private lateinit var brew: ScaleBrew
    private val sent = mutableListOf<ByteArray>()
    private var now = 1_000_000L
    private var grams = 0.0
    /** The scale's own flow, which trails the stream. */
    private var reported = 0.0
    // Pours at 0:00 and 0:30, to 40 g and 100 g.
    private val recipe = Recipe(name = "Two-pulse", dose = 10.0, ratio = 10.0, pulses = listOf(Pulse("Bloom", 40.0, 10, 20), Pulse(water = 60.0, pourSeconds = 10)))

    @Before fun setUp() {
        val directory = temporary.newFolder("data")
        repository = BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = directory
        })
        val beans = Beans(name = "Ethiopia", roaster = "Sample")
        repository.upsertBeans(beans).getOrThrow()
        repository.upsertGrinder(Grinder(name = "Hand grinder", lastSetting = 24)).getOrThrow()
        repository.upsertRecipe(recipe).getOrThrow()
        repository.setScale(PairedScale("AA:BB:CC:DD:EE:FF", "Dotty")).getOrThrow()
        repository.updateDraft(repository.startDraft().getOrThrow().copy(beans = beans, recipe = recipe, stage = DraftStage.SHEET, scale = ScaleData(), scaleStep = ScaleStep.ARMED)).getOrThrow()
        brew = ScaleBrew(repository) { sent += it }
    }

    private val draft get() = repository.state.value.draft!!
    private val timer get() = draft.timer!!

    /** Readings every 100 ms for [millis], moving towards [to] at [flow] grams a second. */
    private fun feed(millis: Long, flow: Double = 0.0, to: Double = grams) {
        repeat((millis / 100).toInt()) {
            now += 100
            grams = if (flow > 0) minOf(to, grams + flow / 10) else if (flow < 0) maxOf(to, grams + flow / 10) else to
            brew.reading(now, grams, flow, overload = false)
        }
    }

    private fun startBrew() {
        feed(1200)
        feed(1000, flow = 5.0, to = 100.0)
        assertNotNull(draft.timer)
    }

    /**
     * Readings every 100 ms for [millis] while water lands at [rate] grams a second, as a real scale reports them:
     * its flow trails the stream, a drip keeps it just above 0 g/s, and the weight wobbles by 0.1 g.
     */
    private fun pourLike(millis: Long, rate: Double) {
        repeat((millis / 100).toInt()) { i ->
            now += 100
            grams += rate / 10
            reported = reported * 0.8 + rate * 0.2
            brew.reading(now, grams + if (i % 2 == 0) 0.1 else -0.1, reported + 0.25, overload = false)
        }
    }

    /** Pours of 30 g planned at 10 s each, with [waitSeconds] between them; armed at a steady zero. */
    private fun armPulses(waitSeconds: Int): Recipe {
        val pulses = Recipe(name = "Pulses", dose = 9.0, ratio = 10.0, pulses = List(3) { Pulse(water = 30.0, pourSeconds = 10, waitSeconds = if (it < 2) waitSeconds else 0) })
        repository.updateDraft(draft.copy(recipe = pulses)).getOrThrow()
        feed(1200)
        return pulses
    }

    @Test fun `a pour that ran late still records reached when the reading settles after the clock starts the next pulse`() {
        val pulses = armPulses(waitSeconds = 3)
        // A slow pour reaches 29 g about 4.5 s past its planned time, so the wait gets only its planned 3 s.
        // The pour runs on to 34 g, and as the scale's flow trails off, the reading settles after the clock has started pulse 2.
        pourLike(17_000, 2.0)
        val poured = grams
        pourLike(2_500, 0.0)
        val view = TimerNow(pulses, timer, now)
        assertEquals(TimerPhase.POUR, view.phase)
        assertEquals(1, view.pulse)
        pourLike(16_000, 2.0)
        assertEquals(poured, draft.scale!!.reached[0]!!, 0.11)
    }

    @Test fun `with no wait, a steady reading before the next pulse's water still counts`() {
        val pulses = armPulses(waitSeconds = 0)
        // The clock starts pulse 2 at 29 g, and the pour runs on 1 g past the target, to 31 g.
        pourLike(15_500, 2.0)
        assertEquals(1, TimerNow(pulses, timer, now).pulse)
        val poured = grams
        pourLike(2_500, 0.0)
        pourLike(16_000, 2.0)
        assertEquals(poured, draft.scale!!.reached[0]!!, 0.11)
    }

    @Test fun `starting the next pulse before the reading settles still records reached`() {
        val pulses = armPulses(waitSeconds = 10)
        pourLike(16_000, 2.0)
        val poured = grams
        pourLike(1_000, 0.0)
        repository.updateTimer { d, t, r -> d.copy(timer = t.skip(r, now)) }
        assertEquals(TimerPhase.POUR, TimerNow(pulses, timer, now).phase)
        pourLike(2_500, 0.0)
        pourLike(16_000, 2.0)
        assertEquals(poured, draft.scale!!.reached[0]!!, 0.11)
    }

    @Test fun `pouring straight on into the next pulse leaves reached not recorded, even if the pour stops briefly`() {
        armPulses(waitSeconds = 3)
        pourLike(22_000, 2.0)
        pourLike(2_500, 0.0)
        pourLike(10_000, 2.0)
        assertNull(draft.scale!!.reached.getOrNull(0))
    }

    @Test fun `pour done well short of the target does not stretch reached into the next pulse's water`() {
        val pulses = armPulses(waitSeconds = 0)
        pourLike(11_000, 2.0)
        repository.updateTimer { d, t, r -> d.copy(timer = t.skip(r, now)) }
        pourLike(2_500, 2.0)
        assertEquals(1, TimerNow(pulses, timer, now).pulse)
        pourLike(2_500, 0.0)
        assertNull(draft.scale!!.reached.getOrNull(0))
    }

    @Test fun `undoing pour done does not record the next pulse's water as reached`() {
        armPulses(waitSeconds = 3)
        pourLike(22_000, 2.0)
        repository.updateTimer { d, t, r -> d.copy(timer = t.skip(r, now)) }
        pourLike(1_500, 0.0)
        repository.changeDraft { it.copy(timer = it.timer!!.undone(now)) }
        assertEquals(2, timer.step)
        pourLike(2_500, 0.0)
        assertNull(draft.scale!!.reached.getOrNull(0))
    }

    @Test fun `a rise from a steady zero starts the timer at the first rising reading`() {
        feed(1200)
        assertTrue(brew.live.value.zeroed)
        val firstRise = now + 100
        feed(500, flow = 5.0, to = 100.0)
        assertEquals(firstRise, timer.startedAt)
        assertTrue(timer.byWeight)
    }

    @Test fun `lifting and replacing the brewer while armed does not start the timer`() {
        feed(1200)
        feed(500, flow = -40.0, to = -300.0)
        feed(800, flow = 40.0, to = 0.5)
        feed(1200)
        assertNull(draft.timer)
    }

    @Test fun `reaching the target ends the pour and a new rise after the flow stops starts the next pulse early`() {
        startBrew()
        feed(7000, flow = 5.0, to = 40.5)
        assertEquals(1, timer.step)
        feed(1500)
        assertEquals(grams, draft.scale!!.reached[0]!!, 0.01)
        val early = now - timer.startedAt
        feed(1000, flow = 5.0, to = 100.0)
        assertEquals(2, timer.step)
        assertTrue(timer.pulseStarts[1] in early..early + 500)
        assertNotNull(timer.undo)
    }

    @Test fun `swirling during a wait follows the clock until the dripper is back, without starting a pulse`() {
        startBrew()
        feed(7000, flow = 5.0, to = 40.5)
        feed(1500)
        feed(300, flow = -100.0, to = -200.0)
        assertFalse(timer.byWeight)
        assertEquals("Dripper off", brew.live.value.following)
        feed(400, flow = 100.0, to = 40.6)
        feed(1500)
        assertTrue(timer.byWeight)
        assertNull(brew.live.value.following)
        assertEquals(1, timer.step)
    }

    @Test fun `a lost scale follows the clock and resumes only if its reading still fits`() {
        startBrew()
        feed(1000, flow = 5.0, to = 30.0)
        feed(1200)
        brew.lose(now)
        assertFalse(timer.byWeight)
        assertEquals("Scale disconnected", brew.live.value.following)
        feed(100)
        assertTrue(timer.byWeight)

        brew.lose(now)
        feed(100, to = 0.0)
        assertFalse(timer.byWeight)
        assertEquals("Scale reading changed", brew.live.value.following)
        feed(1000, flow = 5.0, to = 40.0)
        assertFalse(timer.byWeight)
    }

    @Test fun `water is the steady reading after the last pour and a lift after it is offered as the total`() {
        startBrew()
        feed(7000, flow = 5.0, to = 40.5)
        feed(1500)
        feed(21000)
        assertEquals(TimerPhase.POUR, TimerNow(recipe, timer, now).phase)
        feed(13000, flow = 5.0, to = 100.8)
        assertEquals(TimerPhase.DRAWDOWN, TimerNow(recipe, timer, now).phase)
        feed(1500)
        assertEquals(100.8, draft.scale!!.water!!, 0.01)
        assertEquals(100.8, draft.scale!!.reached[1]!!, 0.01)
        feed(300, flow = -100.0, to = 0.0)
        assertEquals((now - timer.startedAt) / 1000.0, draft.scale!!.liftSeconds!!.toDouble(), 1.0)
        assertTrue(draft.scale!!.curve.size >= 40)
    }

    @Test fun `losing the scale while the coffee is weighed goes back to taring the brewer, even with the sheet closed`() {
        repository.updateDraft(draft.copy(scaleStep = ScaleStep.COFFEE)).getOrThrow()
        brew.connected(false, now)
        assertEquals(ScaleStep.BREWER, draft.scaleStep)
        // Once a dose is chosen, losing the scale leaves the armed step alone.
        repository.updateDraft(draft.copy(scaleStep = ScaleStep.ARMED)).getOrThrow()
        brew.connected(false, now)
        assertEquals(ScaleStep.ARMED, draft.scaleStep)
    }

    @Test fun `a silent scale before the first pour drops its stale weight until a reading returns`() {
        feed(1200, to = 0.0)
        assertTrue(brew.live.value.zeroed)
        brew.lose(now)
        assertTrue(brew.live.value.silent)
        assertNull(brew.live.value.grams)
        assertFalse(brew.live.value.zeroed)
        feed(100, to = 0.0)
        assertFalse(brew.live.value.silent)
        assertEquals(0.0, brew.live.value.grams!!, 0.01)
    }

    @Test fun `a scale that goes silent before any draft does not leave a stale weight`() {
        repository.discardDraft().getOrThrow()
        feed(300, to = 312.4)
        brew.lose(now)
        assertTrue(brew.live.value.silent)
        assertNull(brew.live.value.grams)
    }

    @Test fun `a tare with no readings at all fails at its deadline`() {
        brew.tare(now)
        brew.expireTare(now + 1_000)
        assertTrue(brew.live.value.taring)
        brew.expireTare(now + 1_500)
        assertFalse(brew.live.value.taring)
        assertTrue(brew.live.value.tareFailed)
    }

    @Test fun `an overloaded scale does not leave a tare pending`() {
        brew.tare(now)
        now += 1_600
        brew.reading(now, 0.0, 0.0, overload = true)
        assertTrue(brew.live.value.tareFailed)
        assertFalse(brew.live.value.taring)
    }

    @Test fun `a tare the scale ignores asks for its own tare button and does not arm`() {
        grams = 12.0
        brew.tare(now)
        assertEquals(2, sent.size)
        // Until the scale reads about 0 g, the tare is pending, so the brewer's weight cannot pass as a dose.
        assertTrue(brew.live.value.taring)
        feed(1600)
        assertTrue(brew.live.value.tareFailed)
        assertFalse(brew.live.value.taring)
        assertEquals(12.0, brew.live.value.grams!!, 0.01)
        brew.tare(now)
        assertFalse(brew.live.value.tareFailed)
        assertTrue(brew.live.value.taring)
        feed(1200, to = 0.0)
        assertFalse(brew.live.value.tareFailed)
        assertFalse(brew.live.value.taring)
        assertTrue(brew.live.value.zeroed)
    }

    @Test fun `lifting the brewer while armed disarms, even if putting it back overshoots`() {
        feed(1200)
        feed(500, flow = -40.0, to = -300.0)
        feed(900, flow = 40.0, to = 3.0)
        feed(1200, to = 0.0)
        assertNull(draft.timer)
        assertTrue(brew.live.value.zeroed)
    }

    @Test fun `a scale that returns after the clock moved on guides from where the clock is`() {
        startBrew()
        feed(1000, flow = 5.0, to = 20.0)
        feed(1200)
        brew.lose(now)
        now += 8_000
        feed(100)
        assertTrue(timer.byWeight)
        assertEquals(TimerPhase.WAIT, TimerNow(recipe, timer, now).phase)
    }

    @Test fun `a new reader keeps the curve a finished brew already saved`() {
        startBrew()
        feed(7000, flow = 5.0, to = 40.5)
        feed(1500)
        val saved = draft.scale!!.curve
        assertTrue(saved.size >= 9)
        repository.updateTimer { d, t, r -> d.copy(timer = t.finish(r, now), stage = DraftStage.REVIEW) }
        brew = ScaleBrew(repository) { sent += it }
        feed(500)
        assertEquals(saved, draft.scale!!.curve)
    }

    @Test fun `a scale that goes silent while armed loses its zero`() {
        feed(1200)
        assertTrue(brew.live.value.zeroed)
        brew.lose(now)
        assertFalse(brew.live.value.zeroed)
    }

    @Test fun `a timer started again after Undo records its own curve`() {
        startBrew()
        feed(4000, flow = 5.0, to = 20.0)
        repository.changeDraft { it.copy(timer = null) }
        repository.changeDraft { it.copy(timer = BrewTimer(startedAt = now, byWeight = true)) }
        feed(2500)
        brew.connected(false, now)
        assertEquals(listOf(20.0, 20.0, 20.0), draft.scale!!.curve)
    }

    @Test fun `forgetting the scale mid-brew hands the brew to the clock`() {
        startBrew()
        repository.setScale(null).getOrThrow()
        brew.connected(false, now)
        assertFalse(timer.byWeight)
    }

    @Test fun `disconnecting after Finish saves the curve gathered since the last measurement`() {
        startBrew()
        feed(5000, flow = 5.0, to = 30.0)
        repository.updateTimer { d, t, r -> d.copy(timer = t.finish(r, now), stage = DraftStage.REVIEW) }
        assertTrue(draft.scale!!.curve.size < 6)
        brew.connected(false, now)
        assertTrue(draft.scale!!.curve.size >= 6)
    }

    @Test fun `a dripper already off at the first drawdown reading is still a lift`() {
        startBrew()
        feed(7000, flow = 5.0, to = 40.5)
        feed(1500)
        feed(21000)
        feed(12000, flow = 5.0, to = 98.5)
        feed(100, flow = 5.0, to = 99.0)
        assertEquals(TimerPhase.DRAWDOWN, TimerNow(recipe, timer, now).phase)
        feed(100, to = 0.0)
        feed(1500)
        assertNotNull(draft.scale!!.liftSeconds)
        assertNull(draft.scale!!.water)
    }

    @Test fun `a brief press at the end of the last pour is not a lift`() {
        startBrew()
        feed(7000, flow = 5.0, to = 40.5)
        feed(1500)
        feed(21000)
        feed(12000, flow = 5.0, to = 98.5)
        feed(100, flow = 5.0, to = 130.0)
        assertEquals(TimerPhase.DRAWDOWN, TimerNow(recipe, timer, now).phase)
        feed(1500, to = 100.2)
        assertNull(draft.scale!!.liftSeconds)
        assertEquals(100.2, draft.scale!!.water!!, 0.01)
    }

    @Test fun `lifting the dripper before water settles records the lift and leaves water not recorded`() {
        startBrew()
        feed(7000, flow = 5.0, to = 40.5)
        feed(1500)
        feed(21000)
        feed(13000, flow = 5.0, to = 100.8)
        assertEquals(TimerPhase.DRAWDOWN, TimerNow(recipe, timer, now).phase)
        feed(300, flow = -100.0, to = 0.0)
        feed(1500)
        assertNotNull(draft.scale!!.liftSeconds)
        assertNull(draft.scale!!.water)
        assertTrue(timer.byWeight)
    }
}

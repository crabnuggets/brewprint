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

    @Test fun `a tare the scale ignores asks for its own tare button and does not arm`() {
        grams = 12.0
        brew.tare(now)
        assertEquals(2, sent.size)
        feed(1600)
        assertTrue(brew.live.value.tareFailed)
        assertEquals(12.0, brew.live.value.grams!!, 0.01)
        feed(1200, to = 0.0)
        assertFalse(brew.live.value.tareFailed)
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

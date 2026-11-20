package com.brewprint.ui

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.brewprint.data.*
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The v2 polish journeys: deleting with Undo, swiping History rows, and the scale record's one-line timing.
 * Each journey also saves screenshots for review under ~/.cache/brewprint-android/v2-polish.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PolishJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()
    private val directory = File(System.getProperty("user.home"), ".cache/brewprint-android/v2-polish/screenshots")
    private var now = 1_800_000_000_000L
    private var appearance by mutableStateOf(Appearance.LIGHT)
    private var live by mutableStateOf(ScaleLive(connected = true, grams = 0.0, steady = true))
    private lateinit var repository: BrewRepository

    // The owner's ten-pulse brew: 20 g at 1:15, 30 g a pulse, pulses 6 to 10 started late.
    private val recipe = Recipe(name = "Ten-pulse", dose = 20.0, ratio = 15.0, pulses = listOf(Pulse("Bloom", 30.0, 5, 25)) +
        List(8) { Pulse(water = 30.0, pourSeconds = 5, waitSeconds = 10) } + Pulse(water = 30.0, pourSeconds = 5))
    private val starts = listOf(0, 30, 45, 60, 75, 99, 128, 159, 186, 216)
    private val reached = listOf(30.8, 61.2, 91.0, 121.1, 151.6, 182.0, 211.7, 242.3, null, 304.2)
    private val beans = Beans(name = "Ethiopia natural", roaster = "Sample Roaster")
    private val grinder = Grinder(name = "Comandante C40", lastSetting = 24)

    /** Water on the scale at [second]: a smooth rise through each pour, then steady. */
    private fun weightAt(second: Int, pulseStarts: List<Int>, settled: List<Double?>): Double {
        var water = 0.0
        pulseStarts.forEachIndexed { index, start ->
            val from = if (index == 0) 0.0 else settled[index - 1] ?: recipe.schedule()[index - 1].cumulativeWater
            val to = settled[index] ?: recipe.schedule()[index].cumulativeWater
            if (second >= start + 6) water = to else if (second > start) water = from + (to - from) * (second - start) / 6.0
        }
        return water
    }

    private fun open(seed: BrewRepository.() -> Unit = {}) {
        val data = temporary.newFolder("data")
        repository = BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = data
        })
        repository.upsertBeans(beans).getOrThrow()
        repository.upsertGrinder(grinder).getOrThrow()
        repository.upsertRecipe(recipe).getOrThrow()
        repository.seed()
        compose.setContent {
            val state by repository.state.collectAsState()
            CompositionLocalProvider(LocalClock provides { now }, LocalScale provides ScaleUi(live) {}) {
                BrewprintTheme(appearance) { BrewprintApp(state, repository) }
            }
        }
    }

    /** Saves the owner's brew as a record with its timing and scale readings. */
    private fun BrewRepository.saveScaleRecord(): BrewRecord {
        val draft = startDraft().getOrThrow().copy(beans = beans, grinder = grinder, setting = 24, recipe = recipe, startedAt = now - 3_600_000,
            journal = Journal(impression = "Bright and sweet, a little thin as it cooled.", nextTime = "Try one tick finer."))
        updateDraft(draft).getOrThrow()
        val saved = saveBrew(draft.id).getOrThrow()
        val record = saved.copy(timing = BrewTiming(totalSeconds = 270, pulseStarts = starts),
            scale = ScaleData(weighedDose = 20.0, water = 304.2, reached = reached, curve = (0..270).map { weightAt(it, starts, reached) }))
        updateBrew(record).getOrThrow()
        return record
    }

    /** A row of the record's timing table; the planned pours above it say "starts", not "started". */
    private fun timingRow(name: String) = hasContentDescription("$name, ", substring = true) and hasContentDescription("started", substring = true)

    private fun tap(text: String) = (if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) compose.onNodeWithContentDescription(text) else compose.onNodeWithText(text)).apply { if (!isDisplayed()) performScrollTo() }.performClick()

    private fun capture(name: String) {
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun both(name: String) {
        capture("$name-light")
        compose.runOnIdle { appearance = Appearance.DARK }
        capture("$name-dark")
        compose.runOnIdle { appearance = Appearance.LIGHT }
    }

    @Test fun `deleting a brew from its record offers Undo, which restores it unchanged`() {
        lateinit var record: BrewRecord
        open { record = saveScaleRecord() }
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Ethiopia natural").performClick()
        tap("Delete brew")
        compose.onNodeWithText("Brew deleted").assertExists()
        compose.onNodeWithText("Ethiopia natural").assertDoesNotExist()
        capture("history-undo-light")
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Ethiopia natural").assertExists()
        compose.runOnIdle { assertEquals(listOf(record), repository.state.value.brews) }
    }

    @Test fun `the Undo window survives the screen being recreated`() {
        val data = temporary.newFolder("restore")
        repository = BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = data
        })
        repository.upsertBeans(beans).getOrThrow()
        repository.upsertGrinder(grinder).getOrThrow()
        repository.upsertRecipe(recipe).getOrThrow()
        repository.saveScaleRecord()
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            val state by repository.state.collectAsState()
            CompositionLocalProvider(LocalClock provides { now }) { BrewprintTheme(appearance) { BrewprintApp(state, repository) } }
        }
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Ethiopia natural").performTouchInput { swipeLeft() }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Brew deleted").assertExists()
        compose.onNodeWithText("Ethiopia natural").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(UNDO_MILLIS + 1_000)
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(repository.state.value.brews.isEmpty()) }
    }

    @Test fun `a delete is written once the Undo window ends`() {
        open { saveScaleRecord() }
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Ethiopia natural").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(1, repository.state.value.brews.size) }
        compose.mainClock.advanceTimeBy(UNDO_MILLIS + 1_000)
        compose.waitForIdle()
        compose.onNodeWithText("Brew deleted").assertDoesNotExist()
        compose.runOnIdle { assertTrue(repository.state.value.brews.isEmpty()) }
    }

    @Test fun `swiping a History row left deletes it with Undo, and right repeats it`() {
        lateinit var record: BrewRecord
        open { record = saveScaleRecord() }
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Ethiopia natural").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Brew deleted").assertExists()
        compose.onNodeWithText("Ethiopia natural").assertDoesNotExist()
        compose.onNodeWithText("Undo").performClick()
        compose.runOnIdle { assertEquals(listOf(record), repository.state.value.brews) }
        compose.onNodeWithText("Ethiopia natural").performTouchInput { swipeRight() }
        compose.onNodeWithText("New brew").assertExists()
        compose.runOnIdle { assertEquals(record.recipe, repository.state.value.draft?.recipe) }
    }

    @Test fun `a swipe gives a haptic as it crosses the delete threshold and as it pulls back`() {
        open { saveScaleRecord() }
        compose.onNodeWithText("History").performClick()
        // The view the app's Compose content draws into, which performs the haptics.
        val host = compose.runOnIdle { generateSequence(listOf(compose.activity.window.decorView)) { views -> views.flatMap { (it as? android.view.ViewGroup)?.let { group -> (0 until group.childCount).map(group::getChildAt) } ?: emptyList() }.takeIf { it.isNotEmpty() } }.flatten().first { it.javaClass.simpleName == "AndroidComposeView" } }
        fun last() = compose.runOnIdle { org.robolectric.Shadows.shadowOf(host).lastHapticFeedbackPerformed() }
        val row = compose.onNodeWithText("Ethiopia natural")
        row.performTouchInput { down(centerRight - androidx.compose.ui.geometry.Offset(20f, 0f)); moveBy(androidx.compose.ui.geometry.Offset(-width * .25f, 0f)) }
        assertEquals(-1, last())
        row.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(-width * .3f, 0f)) }
        // API 28 here: the threshold uses a long-press haptic, pulling back a clock tick.
        assertEquals(android.view.HapticFeedbackConstants.LONG_PRESS, last())
        row.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(width * .5f, 0f)) }
        assertEquals(android.view.HapticFeedbackConstants.CLOCK_TICK, last())
        row.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, repository.state.value.brews.size) }
        // Repeat springs the row back without the pull-back haptic.
        row.performTouchInput { swipeRight() }
        compose.runOnIdle { assertTrue(repository.state.value.draft != null) }
        assertEquals(android.view.HapticFeedbackConstants.LONG_PRESS, last())
    }

    @Test fun `two deletes in a row share one Undo that restores both`() {
        lateinit var first: BrewRecord
        lateinit var second: BrewRecord
        open { first = saveScaleRecord(); second = saveScaleRecord() }
        compose.onNodeWithText("History").performClick()
        compose.onAllNodesWithText("Ethiopia natural")[0].performTouchInput { swipeLeft() }
        compose.onAllNodesWithText("Ethiopia natural")[0].performTouchInput { swipeLeft() }
        compose.onNodeWithText("2 brews deleted").assertExists()
        compose.onNodeWithText("Undo").performClick()
        compose.runOnIdle { assertEquals(setOf(first, second), repository.state.value.brews.toSet()) }
    }

    @Test fun `using the dripper lift while the total is being edited replaces the field`() {
        open {
            val timer = BrewTimer(startedAt = now - 270_000, step = 18, stepAt = now - 54_000, pulseStarts = starts.map { it * 1000L }, finishedAt = now, byWeight = true)
            updateDraft(startDraft().getOrThrow().copy(beans = beans, grinder = grinder, setting = 24, recipe = recipe, stage = DraftStage.REVIEW, timer = timer,
                scale = ScaleData(weighedDose = 20.0, water = 304.2, reached = reached, liftSeconds = 252))).getOrThrow()
        }
        tap("Resume brew")
        tap("Change total")
        compose.onNode(hasText("Total brew time") and hasSetTextAction()).performTextReplacement("4:3x")
        compose.onNodeWithText("Save brew").assertIsNotEnabled()
        tap("Use 4:12")
        compose.onNode(hasText("Total brew time") and hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("4:12").assertExists()
        compose.onNodeWithText("Save brew").assertIsEnabled()
        compose.runOnIdle { assertEquals(252, repository.state.value.draft?.timer?.totalOverride) }
    }

    @Test fun `Undo stays reachable above the next screen's footer`() {
        lateinit var deleted: BrewRecord
        open { deleted = saveScaleRecord(); saveScaleRecord() }
        compose.onNodeWithText("History").performClick()
        compose.onAllNodesWithText("Ethiopia natural")[0].performTouchInput { swipeLeft() }
        compose.onAllNodesWithText("Ethiopia natural")[0].performTouchInput { swipeRight() }
        compose.onNodeWithText("New brew").assertExists()
        val bar = compose.onNodeWithText("Brew deleted").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithText("Use this plan").fetchSemanticsNode().boundsInRoot
        assertTrue("Undo bar ${bar.bottom} should sit above the footer action ${footer.top}", bar.bottom <= footer.top)
        compose.onNodeWithText("Undo").performClick()
        compose.runOnIdle { assertTrue(repository.state.value.brews.any { it.id == deleted.id }) }
    }

    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    @Test fun `a target just under the total keeps both labels readable`() {
        compose.setContent {
            BrewprintTheme(Appearance.LIGHT) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(androidx.compose.material3.MaterialTheme.colorScheme.surface).padding(24.dp)) {
                    WaterCurve((0..140).map { weightAt(it, starts, reached) }, 185, 300.0, target = 295.0)
                }
            }
        }
        capture("curve-close-labels-light")
    }

    @Test fun `History rows offer Delete and Repeat to screen readers`() {
        open { saveScaleRecord() }
        compose.onNodeWithText("History").performClick()
        val row = compose.onNode(hasClickAction() and hasText("Ethiopia natural", substring = true))
        val actions = row.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("Repeat", "Delete"), actions)
        both("history")
    }

    @Test fun `the scale record shows each pulse on one line with its offset and reading`() {
        open { saveScaleRecord() }
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Ethiopia natural").performClick()
        both("record-top")
        compose.onNode(timingRow("Pulse 6")).performScrollTo()
        compose.onNode(hasContentDescription("Pulse 6, ", substring = true) and hasContentDescription("started 9 seconds late, reached 182.0 grams", substring = true)).assertExists()
        compose.onNode(hasContentDescription("Pulse 10, ", substring = true) and hasContentDescription("started 1 minute 6 seconds late", substring = true)).assertExists()
        compose.onNode(hasContentDescription("Pulse 9, ", substring = true) and hasContentDescription("no steady reading", substring = true)).assertExists()
        both("record-timing")
    }

    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    @Test fun `on a 360 dp phone every pulse still fits one line`() {
        open { saveScaleRecord() }
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Ethiopia natural").performClick()
        compose.onNode(timingRow("Pulse 10")).performScrollTo()
        // A wrapped row is taller than a single-line one.
        val heights = (1..10).map { pulse -> compose.onNode(timingRow(if (pulse == 1) "Bloom" else "Pulse $pulse")).fetchSemanticsNode().boundsInRoot.height }
        assertTrue("rows should share one height: $heights", heights.max() - heights.min() < 1f)
        capture("record-timing-360-light")
    }

    @Test fun `the Finish brew review leads with one results card`() {
        open {
            val timer = BrewTimer(startedAt = now - 270_000, step = 18, stepAt = now - 54_000, pulseStarts = starts.map { it * 1000L }, finishedAt = now, byWeight = true)
            updateDraft(startDraft().getOrThrow().copy(beans = beans, grinder = grinder, setting = 24, recipe = recipe, stage = DraftStage.REVIEW, timer = timer,
                scale = ScaleData(weighedDose = 20.0, water = 304.2, reached = reached, liftSeconds = 252))).getOrThrow()
        }
        tap("Resume brew")
        compose.onNodeWithContentDescription("Change setup").assertHasClickAction()
        compose.onNodeWithContentDescription("Change date").assertHasClickAction()
        compose.onNodeWithContentDescription("Change total").assertHasClickAction()
        compose.onNodeWithText("Dripper lifted at 4:12").assertExists()
        compose.onNodeWithContentDescription("Water 304.2 grams, planned 300 grams").assertExists()
        both("review")
    }

    @Test fun `the brew sheet labels the target and the total on the curve`() {
        val startedAt = now - 62_000
        live = ScaleLive(connected = true, grams = 104.6, flow = 6.2, curve = (0..62).map { weightAt(it, starts.take(4), reached) }, flows = (0..62).map { if (it in 0..5 || it in 30..35 || it in 45..50 || it >= 60) 6.0 else 0.0 })
        open {
            val timer = BrewTimer(startedAt = startedAt, step = 6, stepAt = startedAt + 60_000, pulseStarts = listOf(0, 30_000, 45_000, 60_000), byWeight = true)
            setScale(PairedScale("AA:BB:CC:DD:EE:FF", "Dotty")).getOrThrow()
            updateDraft(startDraft().getOrThrow().copy(beans = beans, grinder = grinder, setting = 24, recipe = recipe, stage = DraftStage.SHEET, timer = timer,
                scale = ScaleData(weighedDose = 20.0))).getOrThrow()
        }
        tap("Resume brew")
        compose.onNodeWithText("On scale").assertExists()
        compose.onNodeWithText("Pour done").assertExists()
        both("sheet-pour")
    }
}

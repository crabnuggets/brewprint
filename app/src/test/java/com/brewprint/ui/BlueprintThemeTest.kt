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
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BlueprintThemeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()
    private val screenshots = File(System.getProperty("user.home"), ".cache/brewprint-android/blueprint/screenshots")
    private lateinit var data: File
    private var live by mutableStateOf(ScaleLive())
    private var now = java.time.LocalDate.now().atTime(13, 27).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val beans = Beans(name = "Ethiopia natural", roaster = "Sample Roaster", expectedNotes = "Blueberry, cocoa")
    private val grinder = Grinder(name = "Comandante C40", lastSetting = 24)
    // Ten pulses of 30 g: a bloom with a long wait, then one every 15 s.
    private val recipe = Recipe(name = "Ten-pulse", dose = 20.0, pulses = List(10) { index ->
        Pulse(if (index == 0) "Bloom" else "", 30.0, 5, if (index == 0) 25 else if (index == 9) 0 else 10)
    })

    private fun repository() = BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir(): File = data
    })

    /** A saved scale brew with a late second half, as on the mockup board. */
    private fun seeded(theme: Theme): BrewRepository {
        data = temporary.newFolder("data")
        val repository = repository()
        repository.upsertBeans(beans).getOrThrow()
        repository.upsertGrinder(grinder).getOrThrow()
        repository.upsertRecipe(recipe).getOrThrow()
        repository.setExperiments(Experiments(theme)).getOrThrow()
        val draft = repository.startDraft().getOrThrow().copy(beans = beans, grinder = grinder, setting = 24, recipe = recipe,
            startedAt = now - 3_600_000L, journal = Journal(impression = "Bright and sweet, a little thin. Sweeter as it cooled.", nextTime = "Try one tick finer"))
        repository.updateDraft(draft).getOrThrow()
        val saved = repository.saveBrew(draft.id).getOrThrow()
        val starts = listOf(0, 30, 45, 60, 75, 99, 128, 159, 186, 216)
        val reached = listOf(30.8, 61.2, 91.0, 121.1, 151.6, 182.0, 211.7, 242.3, 272.9, 304.2)
        // One reading a second: water rises over each 5 s pour and holds until the next.
        val curve = (0..244).map { second ->
            val index = starts.indexOfLast { it <= second }
            val from = if (index == 0) 0.0 else reached[index - 1]
            from + (reached[index] - from) * minOf(1.0, (second - starts[index]) / 5.0)
        }
        repository.updateBrew(saved.copy(recipe = recipe.scaledTo(20.1), timing = BrewTiming(totalSeconds = 244, pulseStarts = starts),
            scale = ScaleData(weighedDose = 20.1, water = 304.2, reached = reached, curve = curve))).getOrThrow()
        return repository
    }

    private fun open(repository: BrewRepository) = compose.setContent {
        val state by repository.state.collectAsState()
        // Wired as in MainActivity: the saved theme picks the look.
        CompositionLocalProvider(LocalClock provides { now }, LocalScale provides ScaleUi(live)) {
            BrewprintTheme(state.appearance, state.experiments.theme) { BrewprintApp(state, repository) }
        }
    }

    private fun tap(text: String) = (if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) compose.onNodeWithContentDescription(text) else compose.onNodeWithText(text)).apply {
        if (!isDisplayed()) performScrollTo()
    }.performClick()

    @Test fun `Settings switches the theme and keeps the choice`() {
        val repository = seeded(Theme.MONOCHROME)
        open(repository)
        tap("Settings")
        tap("Blueprint")
        assertEquals(Theme.BLUEPRINT, repository.state.value.experiments.theme)
        compose.onNode(hasText("Blueprint") and isSelectable()).assertIsSelected()
        assertEquals(Theme.BLUEPRINT, repository().state.value.experiments.theme)
        tap("Monochrome")
        assertEquals(Theme.MONOCHROME, repository().state.value.experiments.theme)
    }

    @Test fun `the record keeps every value in both themes, with the setup in labelled cells`() {
        val repository = seeded(Theme.BLUEPRINT)
        open(repository)
        tap("View brew")
        for (theme in listOf(Theme.BLUEPRINT, Theme.MONOCHROME)) {
            compose.runOnIdle { repository.setExperiments(Experiments(theme)).getOrThrow() }
            compose.onNodeWithContentDescription("Grind setting, 24 ticks").assertExists()
            compose.onNodeWithContentDescription("Coffee dose 20.1 grams, weighed").assertExists()
            // "Next time" stays in the tasting journal, once.
            assertEquals(1, compose.onAllNodesWithText("Try one tick finer").fetchSemanticsNodes().size)
            compose.onNodeWithText("Bright and sweet, a little thin. Sweeter as it cooled.").assertExists()
            // The record's actions are the same in both themes.
            listOf("Edit tasting notes", "Repeat", "Edit brew", "Delete brew").forEach { compose.onNodeWithText(it).assertExists() }
        }
    }

    @Test fun `a scale brew without a final reading says so once, in the timing section`() {
        val repository = seeded(Theme.BLUEPRINT)
        val brew = repository.state.value.brews.single()
        repository.updateBrew(brew.copy(scale = brew.scale!!.copy(water = null))).getOrThrow()
        open(repository)
        tap("View brew")
        // Preparation shows the plan; what the scale measured, or didn't, is in "Timing and water" alone.
        compose.onAllNodesWithContentDescription("Water not recorded, planned 301.5 grams").assertCountEquals(1)
        compose.onNodeWithContentDescription("Total brew water 301.5 grams").assertExists()
    }

    /** Every word on screen in reading order: text and spoken names, from the unmerged semantics tree. */
    private fun words(): List<String> {
        val words = mutableListOf<String>()
        fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.forEach { words += it.text }
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.forEach { words += it }
            node.children.forEach(::walk)
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return words
    }

    @Test fun `both themes show the same words and actions in the same order on Brew and the record`() {
        val repository = seeded(Theme.MONOCHROME)
        open(repository)
        fun screens(): Pair<List<String>, List<String>> {
            val home = words()
            tap("View brew")
            val record = words()
            back()
            return home to record
        }
        val monochrome = screens()
        compose.runOnIdle { repository.setExperiments(Experiments(Theme.BLUEPRINT)).getOrThrow() }
        val blueprint = screens()
        assertTrue("Brew shows its recent brew", "Try one tick finer" in monochrome.first && "Grind setting, 24 ticks" in monochrome.first)
        assertEquals(monochrome.first, blueprint.first)
        assertEquals(monochrome.second, blueprint.second)
    }

    @Test
    @Config(qualifiers = "w320dp-h700dp-xxhdpi")
    fun `on a narrow screen the ruled pulse table stacks rather than cut the names`() {
        val repository = seeded(Theme.BLUEPRINT)
        open(repository)
        tap("View brew")
        compose.onNodeWithText("Pulse 10", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Plan 2:30 · Started +1:06 · Reached 304.2 g", useUnmergedTree = true).performScrollTo()
        capture("record-timing-narrow")
    }

    @Test fun `capture the blueprint theme`() {
        val repository = seeded(Theme.BLUEPRINT)
        open(repository)
        fun pair(name: String) {
            compose.runOnIdle { repository.setAppearance(Appearance.LIGHT).getOrThrow() }
            capture("$name-light")
            compose.runOnIdle { repository.setAppearance(Appearance.DARK).getOrThrow() }
            capture("$name-dark")
        }
        pair("home")
        compose.onNode(hasText("History") and isSelectable()).performClick()
        pair("history")
        compose.onNode(hasText("Brew") and isSelectable()).performClick()
        tap("View brew")
        pair("record")
        compose.onNodeWithText("Reached is the scale's steady reading after each pulse; — means none was recorded.", substring = true).performScrollTo()
        pair("record-timing")
        // The header has scrolled away, so go back as Android Back does.
        back()
        tap("Settings")
        compose.onNodeWithText("Blueprint").performScrollTo()
        pair("settings")
        back()

        // Pulse 4 of 10 is pouring, guided by the scale.
        compose.runOnIdle {
            repository.pairScale(PairedScale("AA:BB:CC:DD:EE:FF", "Dotty")).getOrThrow()
            val plan = recipe.scaledTo(20.0)
            val start = now - 62_000L
            repository.updateDraft(repository.startDraft().getOrThrow().copy(beans = beans, grinder = grinder, setting = 24, recipe = plan, stage = DraftStage.SHEET,
                scale = ScaleData(weighedDose = 20.0), scaleStep = ScaleStep.ARMED,
                timer = BrewTimer(startedAt = start, step = 6, stepAt = start + 60_000L, pulseStarts = listOf(0, 30_000, 45_000, 60_000), byWeight = true))).getOrThrow()
        }
        val curve = (0..62).map { second -> minOf(30.0 * (second / 15 + if (second < 30) 0 else -1).coerceAtLeast(0) + minOf(30.0, (second % 15) * 6.0), 104.6) }
        live = ScaleLive(connected = true, grams = 104.6, flow = 6.2, steady = false, curve = curve, flows = curve.map { 3.0 })
        tap("Resume brew")
        pair("sheet-pour")
        tap("Pour done")
        pair("sheet-after-skip")
        compose.onAllNodesWithText("Finish brew").onFirst().performClick()
        pair("review")
    }

    @Test
    @Config(qualifiers = "w320dp-h700dp-xxhdpi")
    fun `capture the narrow blueprint with large text`() {
        RuntimeEnvironment.setFontScale(1.5f)
        val repository = seeded(Theme.BLUEPRINT)
        open(repository)
        compose.onNodeWithContentDescription("Grind setting, 24 ticks").performScrollTo()
        capture("home-narrow-large-font")
        tap("View brew")
        compose.onNodeWithText("Try one tick finer").performScrollTo()
        capture("record-narrow-large-font")
    }

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun capture(name: String) {
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        screenshots.mkdirs()
        File(screenshots, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}

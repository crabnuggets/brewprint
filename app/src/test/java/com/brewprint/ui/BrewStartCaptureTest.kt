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
import com.brewprint.data.*
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** Captures every screen on the way from the Brew screen to the first pour, for design review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrewStartCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()
    private val directory = File(System.getProperty("user.home"), ".cache/brewprint-android/brew-start/screenshots")
    private var appearance by mutableStateOf(Appearance.LIGHT)
    private var live by mutableStateOf(ScaleLive(connected = true, grams = 312.4, steady = true))
    private var now = 1_000_000L

    private val beans = Beans(name = "Ethiopia natural", roaster = "Sample Roaster", expectedNotes = "Blueberry, cocoa")
    private val grinder = Grinder(name = "Hand grinder", lastSetting = 24)
    private val recipe = Recipe(name = "Three-pulse filter", dose = 15.0, ratio = 16.0, pulses = listOf(
        Pulse("Bloom", 60.0, 10, 30), Pulse(water = 90.0, pourSeconds = 20, waitSeconds = 20), Pulse(water = 90.0, pourSeconds = 20)))

    private fun repository(): BrewRepository {
        val data = temporary.newFolder()
        return BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = data
        })
    }

    private fun seeded(paired: Boolean = false): BrewRepository = repository().apply {
        upsertBeans(beans).getOrThrow()
        upsertGrinder(grinder).getOrThrow()
        upsertRecipe(recipe).getOrThrow()
        val morning = java.time.LocalDate.now().atTime(8, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val draft = startDraft().getOrThrow().copy(beans = beans, recipe = recipe, startedAt = morning - 86_400_000L, journal = Journal(impression = "Smells like cocoa; lighter than I expected", nextTime = "Try one tick finer"))
        updateDraft(draft).getOrThrow()
        saveBrew(draft.id).getOrThrow()
        if (paired) setScale(PairedScale("AA:BB:CC:DD:EE:FF", "Dotty")).getOrThrow()
    }

    private fun show(repository: BrewRepository) = compose.setContent {
        val state by repository.state.collectAsState()
        CompositionLocalProvider(LocalClock provides { now }, LocalScale provides ScaleUi(live) {}) {
            BrewprintTheme(appearance) { BrewprintApp(state, repository) }
        }
    }

    // Icon actions such as "Change date" are found by their spoken name.
    private fun tap(text: String) = (if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) compose.onNodeWithContentDescription(text) else compose.onNodeWithText(text)).apply { if (!isDisplayed()) performScrollTo() }.performClick()
    private fun scrollTo(text: String) = compose.onNodeWithText(text).performScrollTo()

    private fun capture(name: String, dialog: Boolean = false) {
        val bitmap = compose.runOnIdle {
            val view = if (dialog) ShadowDialog.getLatestDialog().window!!.decorView else compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun both(name: String, dialog: Boolean = false) {
        capture("$name-light", dialog)
        compose.runOnIdle { appearance = Appearance.DARK }
        capture("$name-dark", dialog)
        compose.runOnIdle { appearance = Appearance.LIGHT }
    }

    @Test fun `first brew with an empty library`() {
        show(repository())
        capture("01-home-first-run-light")
        tap("New brew")
        capture("02-setup-empty-light")
        tap("Add beans")
        capture("03-add-beans-from-empty-light")
    }

    @Test fun `everyday brew without a scale`() {
        show(seeded())
        both("10-home-returning")
        tap("New brew")
        both("11-setup")
        tap("Adjust")
        both("13-dial", dialog = true)
    }

    @Test fun `everyday brew sheet and first pour`() {
        show(seeded())
        tap("New brew")
        tap("Use this plan")
        both("14-sheet-scale-offer")
        compose.onNodeWithContentDescription("Scheduled pours end 1 minute 40 seconds into the brew. Allow for drawdown.").performScrollTo()
        capture("15-sheet-scale-offer-scrolled-light")
        tap("Not now")
        both("16-sheet")
        tap("Start timer")
        now += 4_000
        compose.mainClock.advanceTimeBy(250)
        both("17-first-pour")
    }

    @Test fun `repeat with a different recorded setting`() {
        val repository = seeded()
        repository.updateGrinderSetting(repository.state.value.grinders.single().id, 26).getOrThrow()
        show(repository)
        tap("Repeat")
        capture("20-setup-repeat-differs-light")
        tap("Use 26 ticks")
        capture("21-setup-repeat-uses-recorded-light")
    }

    @Test fun `brew sheet with a paired scale`() {
        show(seeded(paired = true))
        tap("New brew")
        tap("Use this plan")
        both("30-scale-brewer")
        tap("Tare")
        live = ScaleLive(connected = true, grams = 312.4, steady = true, taring = true)
        capture("30a-scale-taring-light")
        live = ScaleLive(connected = true, grams = 15.23, steady = true)
        both("31-scale-coffee")
        live = ScaleLive(connected = true, grams = 327.6, steady = true, tareFailed = true)
        capture("31a-scale-tare-failed-light")
        live = ScaleLive(connected = false)
        compose.waitForIdle()
        live = ScaleLive(connected = true, grams = 327.6, steady = true)
        capture("30b-scale-reconnected-light")
        tap("Tare")
        live = ScaleLive(connected = true, grams = 15.23, steady = true)
        tap("Use 15.2 g")
        capture("32a-scale-armed-before-zero-light")
        live = ScaleLive(connected = true, grams = 0.3, steady = false)
        capture("32b-scale-armed-settling-light")
        live = ScaleLive(connected = true, grams = 3.4, steady = true, tareFailed = true)
        capture("32c-scale-armed-tare-failed-light")
        live = ScaleLive(connected = true, grams = null, silent = true)
        capture("32d-scale-armed-no-reading-light")
        live = ScaleLive(connected = true, grams = 0.0, steady = true, zeroed = true)
        both("32-scale-armed")
    }

    @Test
    @Config(qualifiers = "w411dp-h1800dp-xxhdpi")
    fun `full length pages`() {
        show(seeded())
        tap("New brew")
        capture("40-full-setup-light")
        tap("Use this plan")
        capture("41-full-sheet-offer-light")
    }

    @Test
    @Config(qualifiers = "w320dp-h700dp-xxhdpi")
    fun `narrow with large text`() {
        RuntimeEnvironment.setFontScale(1.5f)
        show(seeded())
        capture("50-home-narrow-large")
        tap("New brew")
        capture("51-setup-narrow-large")
        tap("Use this plan")
        capture("52-sheet-narrow-large")
        tap("Not now")
        capture("53-sheet-narrow-large-after-offer")
    }
}

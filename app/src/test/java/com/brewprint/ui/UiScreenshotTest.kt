package com.brewprint.ui

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()
    private val directory = File(System.getProperty("user.home"), ".cache/brewprint-android/ui-refresh/screenshots")

    // Icon actions such as "Change date" are found by their spoken name.
    private fun tap(text: String) = (if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) compose.onNodeWithContentDescription(text) else compose.onNodeWithText(text)).apply {
        if (!isDisplayed()) performScrollTo()
    }.performClick()

    private fun seededRepository(): BrewRepository {
        val data = temporary.newFolder("journey")
        val repository = BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = data
        })
        val beans = Beans(name = "Ethiopia natural", roaster = "Sample Roaster", expectedNotes = "Blueberry, cocoa")
        val grinder = Grinder(name = "Hand grinder", lastSetting = 24)
        val recipe = Recipe(name = "Three-pulse filter", dose = 15.0, pulses = listOf(
            Pulse("Bloom", 60.0, 10, 30), Pulse(water = 90.0, pourSeconds = 20, waitSeconds = 20), Pulse(water = 90.0, pourSeconds = 20)))
        repository.upsertBeans(beans).getOrThrow()
        repository.upsertGrinder(grinder).getOrThrow()
        repository.upsertRecipe(recipe).getOrThrow()
        val morning = java.time.LocalDate.now().atTime(8, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        listOf(1, 0, 0).forEachIndexed { index, daysAgo ->
            val draft = repository.startDraft().getOrThrow().copy(beans = beans, recipe = recipe, startedAt = morning - daysAgo * 86_400_000L,
                journal = if (index == 1) Journal() else Journal(impression = "Smells like cocoa; lighter than I expected", nextTime = "Try one tick finer"))
            repository.updateDraft(draft).getOrThrow()
            repository.saveBrew(draft.id).getOrThrow()
        }
        return repository
    }

    @Test fun `capture complete brewing journey in both appearances`() {
        var appearance by mutableStateOf(Appearance.LIGHT)
        val repository = seededRepository()
        compose.setContent {
            val state by repository.state.collectAsState()
            BrewprintTheme(appearance) { BrewprintApp(state, repository) }
        }
        fun pair(name: String) {
            compose.runOnIdle { appearance = Appearance.LIGHT }
            capture("$name-light")
            compose.runOnIdle { appearance = Appearance.DARK }
            capture("$name-dark")
        }
        pair("home")
        compose.onNode(hasText("History") and isSelectable()).performClick()
        pair("history")
        compose.onNode(hasText("Library") and isSelectable()).performClick()
        pair("library")
        for (section in listOf("recipes", "beans", "grinders")) {
            compose.openLibrarySection(section)
            pair("library-$section")
        }
        compose.openLibrarySection("recipes")
        tap("Three-pulse filter")
        pair("library-recipe-detail")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNode(hasText("Brew") and isSelectable()).performClick()
        tap("New brew")
        pair("setup")
        tap("Use this plan")
        pair("sheet")
        tap("Finish without timing")
        pair("review")
        compose.onNodeWithText("View roaster's notes").performScrollTo()
        pair("review-more")
        tap("Save brew")
        pair("record")
        tap("Add tasting notes")
        pair("notes")
        tap("Explore the taste")
        pair("notes-expanded")
        compose.onNodeWithText("Save notes").assertIsDisplayed()
    }

    @Test fun `capture header actions and swipe feedback`() {
        var appearance by mutableStateOf(Appearance.DARK)
        val repository = seededRepository()
        compose.setContent {
            val state by repository.state.collectAsState()
            BrewprintTheme(appearance) { BrewprintApp(state, repository) }
        }
        fun pair(name: String) {
            compose.runOnIdle { appearance = Appearance.LIGHT }
            capture("$name-light")
            compose.runOnIdle { appearance = Appearance.DARK }
            capture("$name-dark")
        }
        compose.onNode(hasText("History") and isSelectable()).performClick()
        val row = compose.onAllNodesWithText("Ethiopia natural")[0]
        // Held mid-swipe: short of the threshold, then past it, each way.
        row.performTouchInput { down(centerRight - androidx.compose.ui.geometry.Offset(20f, 0f)); moveBy(androidx.compose.ui.geometry.Offset(-width * .25f, 0f)) }
        pair("swipe-delete-before")
        row.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(-width * .3f, 0f)) }
        pair("swipe-delete-armed")
        // Pulled back under the threshold before letting go, so nothing is deleted.
        row.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(width * .55f, 0f)); up() }
        row.performTouchInput { down(centerLeft + androidx.compose.ui.geometry.Offset(20f, 0f)); moveBy(androidx.compose.ui.geometry.Offset(width * .25f, 0f)) }
        pair("swipe-repeat-before")
        row.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(width * .3f, 0f)) }
        pair("swipe-repeat-armed")
        row.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(-width * .55f, 0f)); up() }
        compose.runOnIdle { assertTrue(repository.state.value.draft == null && repository.state.value.brews.size == 3) }
        compose.openLibrarySection("recipes")
        tap("Three-pulse filter")
        pair("recipe-header")
        // The menu opens in its own window, which this capture does not draw.
        tap("More actions")
        compose.onNodeWithText("Archive recipe").assertExists()
    }

    @Test
    @Config(qualifiers = "w320dp-h700dp-xxhdpi")
    fun `capture narrow brewing journey with large text`() {
        RuntimeEnvironment.setFontScale(1.5f)
        val repository = seededRepository()
        compose.setContent {
            val state by repository.state.collectAsState()
            BrewprintTheme(Appearance.LIGHT) { BrewprintApp(state, repository) }
        }
        capture("home-narrow-large-font")
        tap("New brew")
        capture("setup-narrow-large-font")
        compose.onNodeWithText("Use this plan").assertIsDisplayed().performClick()
        capture("sheet-narrow-large-font")
        compose.onNodeWithText("Finish without timing").assertIsDisplayed().performClick()
        capture("review-narrow-large-font")
        compose.onNodeWithText("Save brew").assertIsDisplayed()
        compose.onNode(hasText("Your notes") and hasSetTextAction()).performScrollTo().assertIsDisplayed()
        capture("review-narrow-large-font-input")
    }

    private fun capture(name: String, dialog: Boolean = false) {
        val bitmap = compose.runOnIdle {
            val view = if (dialog) ShadowDialog.getLatestDialog().window!!.decorView else compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test fun `capture navigation light and dark`() {
        var appearance by mutableStateOf(Appearance.LIGHT)
        val data = temporary.newFolder("data")
        val repository = BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = data
        })
        compose.setContent {
            BrewprintTheme(appearance) { BrewprintApp(AppState(), repository) }
        }
        capture("navigation-light")
        compose.runOnIdle { appearance = Appearance.DARK }
        capture("navigation-dark")
    }

    @Test fun `capture drafts from Claude light and dark`() {
        var appearance by mutableStateOf(Appearance.LIGHT)
        val repository = seededRepository()
        val beans = Beans(name = "Kii", roaster = "Tim Wendelboe", origin = "Kenya", variety = "SL28, SL34", processing = "Washed", expectedNotes = "Blackcurrant, hibiscus", notes = "https://timwendelboe.no/product/kii")
        val recipe = Recipe(name = "Three pours", dose = 15.0, notes = "Swirl after the bloom.",
            pulses = listOf(Pulse("Bloom", 45.0, 10, 35), Pulse(water = 100.0, pourSeconds = 15, waitSeconds = 20), Pulse(water = 95.0, pourSeconds = 15)))
        repository.applyDrafts(listOf(LibraryDraft(beans.id, beans = beans), LibraryDraft(recipe.id, recipe = recipe))).getOrThrow()
        compose.setContent {
            val state by repository.state.collectAsState()
            BrewprintTheme(appearance) { BrewprintApp(state, repository) }
        }
        fun pair(name: String) {
            compose.runOnIdle { appearance = Appearance.LIGHT }
            capture("$name-light")
            compose.runOnIdle { appearance = Appearance.DARK }
            capture("$name-dark")
        }
        pair("drafts-home")
        tap("Review 2 drafts from Claude")
        pair("drafts-library")
        tap("Kii")
        pair("drafts-review-beans")
        tap("Discard draft")
        capture("drafts-discard-dialog", dialog = true)
        tap("Keep draft")
        tap("Back")
        tap("Three pours")
        pair("drafts-review-recipe")
    }

    // Match Robolectric's floating-window width to avoid its dialog layout loop: robolectric/robolectric#8460.
    @Config(qualifiers = "w320dp-h891dp-xxhdpi")
    @Test fun `capture typing the coffee weight light and dark`() {
        var appearance by mutableStateOf(Appearance.LIGHT)
        val live = ScaleLive(connected = true, grams = 0.0, steady = true, zeroed = true)
        compose.setContent {
            BrewprintTheme(appearance) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ScalePrep(ScaleStep.COFFEE, live, 15.0, 16.0, null, false, onTare = {}, onUse = {}, onSkip = {}, onWithout = {})
                }
            }
        }
        capture("coffee-weight-card-light")
        tap("Add your coffee, 0.0 grams, plan 15 grams")
        compose.onNode(hasSetTextAction()).performTextReplacement("16")
        capture("coffee-weight-dialog-light", dialog = true)
        compose.runOnIdle { appearance = Appearance.DARK }
        capture("coffee-weight-dialog-dark", dialog = true)
    }

    @Test fun `capture untouched new recipe light and dark`() {
        var appearance by mutableStateOf(Appearance.LIGHT)
        compose.setContent {
            BrewprintTheme(appearance) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RecipeEditor(null, { false }, {})
                }
            }
        }
        capture("new-recipe-light")
        compose.runOnIdle { appearance = Appearance.DARK }
        capture("new-recipe-dark")
    }

    @Test fun `capture dial light and dark`() {
        var appearance by mutableStateOf(Appearance.LIGHT)
        compose.setContent {
            BrewprintTheme(appearance) { GrinderDial(24, 24, DialFinish.METAL, {}, {}, {}) }
        }
        capture("dial-light", dialog = true)
        compose.runOnIdle { appearance = Appearance.DARK }
        capture("dial-dark", dialog = true)
    }

    @Test
    @Config(qualifiers = "w320dp-h700dp-xxhdpi")
    fun `capture narrow dial with large font and reachable input`() {
        RuntimeEnvironment.setFontScale(1.5f)
        compose.setContent {
            BrewprintTheme(Appearance.LIGHT) { GrinderDial(100, 24, DialFinish.METAL, {}, {}, {}) }
        }
        capture("dial-narrow-large-font", dialog = true)
        compose.onNode(hasText("Enter value, ticks") and hasSetTextAction()).performScrollTo().assertIsDisplayed()
        capture("dial-narrow-large-font-input", dialog = true)
    }

    private var now = 1_000_000L
    private fun pass(seconds: Int) {
        now += seconds * 1000L
        compose.mainClock.advanceTimeBy(250)
    }

    private fun timerRepository(): BrewRepository {
        val data = temporary.newFolder("timer")
        val repository = BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = data
        })
        val beans = Beans(name = "Ethiopia natural", roaster = "Sample Roaster")
        val recipe = Recipe(name = "Five-pulse filter", dose = 15.0, pulses = listOf(
            Pulse("Bloom", 45.0, 10, 35), Pulse(water = 50.0, pourSeconds = 15, waitSeconds = 20), Pulse(water = 50.0, pourSeconds = 15, waitSeconds = 20),
            Pulse(water = 50.0, pourSeconds = 15, waitSeconds = 20), Pulse(water = 45.0, pourSeconds = 15)))
        repository.upsertBeans(beans).getOrThrow()
        repository.upsertGrinder(Grinder(name = "Hand grinder", lastSetting = 26)).getOrThrow()
        repository.upsertRecipe(recipe).getOrThrow()
        repository.updateDraft(repository.startDraft().getOrThrow().copy(beans = beans, recipe = recipe, stage = DraftStage.SHEET)).getOrThrow()
        return repository
    }

    @Test fun `capture brew timer states`() {
        var appearance by mutableStateOf(Appearance.LIGHT)
        val repository = timerRepository()
        compose.setContent {
            val state by repository.state.collectAsState()
            androidx.compose.runtime.CompositionLocalProvider(LocalClock provides { now }) {
                BrewprintTheme(appearance) { BrewprintApp(state, repository) }
            }
        }
        tap("Resume brew")
        capture("timer-01-start")
        tap("Start timer")
        pass(88)
        capture("timer-02-pour")
        compose.runOnIdle { appearance = Appearance.DARK }
        capture("timer-02-pour-dark")
        compose.runOnIdle { appearance = Appearance.LIGHT }
        pass(15)
        capture("timer-03-wait")
        tap("Start pulse 4")
        pass(2)
        capture("timer-04-skipped")
        pass(32)
        tap("Pause")
        pass(15)
        capture("timer-05-paused")
        tap("Resume")
        pass(8)
        tap("4 done, show plan")
        capture("timer-06-plan")
        tap("Back to current step")
        pass(40)
        capture("timer-07-drawdown")
        tap("Finish brew")
        capture("timer-08-review")
        tap("Save brew")
        capture("timer-09-record")
        compose.onNodeWithText("Started shows how far each pulse was from the plan: + late, − early. Pours are not measured.").performScrollTo()
        capture("timer-10-record-timing")
        // The header has scrolled away, so go back as Android Back does.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNode(hasText("Settings") and isSelectable()).performClick()
        capture("timer-11-settings")
    }

    @Test fun `capture forgotten brew on the brew screen`() {
        val repository = timerRepository()
        compose.setContent {
            val state by repository.state.collectAsState()
            androidx.compose.runtime.CompositionLocalProvider(LocalClock provides { now }) {
                BrewprintTheme(Appearance.LIGHT) { BrewprintApp(state, repository) }
            }
        }
        tap("Resume brew")
        tap("Start timer")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        pass(100)
        capture("timer-12-home-running")
        pass(1000)
        capture("timer-13-home-stopped")
        tap("Resume brew")
        capture("timer-14-review-stopped")
    }

    @Test
    @Config(qualifiers = "w320dp-h700dp-xxhdpi")
    fun `capture narrow brew timer with large text`() {
        RuntimeEnvironment.setFontScale(1.5f)
        val repository = timerRepository()
        compose.setContent {
            val state by repository.state.collectAsState()
            androidx.compose.runtime.CompositionLocalProvider(LocalClock provides { now }) {
                BrewprintTheme(Appearance.LIGHT) { BrewprintApp(state, repository) }
            }
        }
        tap("Resume brew")
        tap("Start timer")
        pass(103)
        capture("timer-narrow-large-font-wait")
        compose.onNodeWithText("Start pulse 4").assertIsDisplayed()
    }
}

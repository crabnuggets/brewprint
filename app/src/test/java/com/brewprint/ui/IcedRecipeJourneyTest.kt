package com.brewprint.ui

import android.content.ContextWrapper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.brewprint.data.*
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The owner's iced recipe through a brew: its details, step notes, ice and target finish wherever the brew needs them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IcedRecipeJourneyTest {
    private val screenshots = File(System.getProperty("user.home"), ".cache/brewprint-android/recipes/screenshots")
    private fun capture(name: String) {
        screenshots.mkdirs()
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        File(screenshots, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()
    private var now = 1_000_000L
    private val kurasu = Recipe(name = "Kurasu Iced Pour-Over", dose = 16.0, drinkStyle = DrinkStyle.ICED, iceGrams = 70.0, waterTempC = 91, targetFinishSeconds = 130,
        grindDescription = "Medium-fine", sourceUrl = "https://kurasu.kyoto/pages/iced", notes = "Make sure all the ice has melted, then pour over fresh ice.",
        pulses = listOf(Pulse("Bloom", 40.0, 10, 35, "Stir gently 3 times"), Pulse(water = 60.0, pourSeconds = 15, waitSeconds = 15, notes = "Swirl the brewer once"),
            Pulse(water = 50.0, pourSeconds = 15, notes = "Stir once, then let it draw down")))

    private fun tap(text: String) = (if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) compose.onNodeWithContentDescription(text) else compose.onNodeWithText(text)).apply {
        if (!isDisplayed()) performScrollTo()
    }.performClick()
    private fun pass(seconds: Int) { now += seconds * 1000L; compose.mainClock.advanceTimeBy(250) }

    @Test fun `an iced brew shows its details, each step's note, and the target at drawdown`() {
        val data = temporary.newFolder("data")
        val repository = BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir(): File = data })
        val beans = Beans(name = "Kii", roaster = "Tim Wendelboe")
        repository.upsertBeans(beans).getOrThrow()
        repository.upsertGrinder(Grinder(name = "Comandante C40", lastSetting = 22)).getOrThrow()
        repository.upsertRecipe(kurasu).getOrThrow()
        repository.updateDraft(repository.startDraft().getOrThrow().copy(beans = beans, recipe = repository.state.value.recipes.single())).getOrThrow()
        compose.setContent {
            val state by repository.state.collectAsState()
            CompositionLocalProvider(LocalClock provides { now }) { BrewprintTheme(state.appearance) { BrewprintApp(state, repository) } }
        }
        tap("Resume brew")
        // Setup: the grind the recipe asks for, and its ratio with and without ice.
        compose.onNodeWithText("Grind size: Medium-fine").assertExists()
        compose.onNodeWithContentDescription("Brew ratio 1 to 9.4, 1 to 13.8 with ice").assertExists()
        tap("Use this plan")
        // The sheet: details in a line, and each pour with its note.
        compose.onNodeWithText("Iced, 70 g ice · 91 °C · Target finish 2:10 · Medium-fine").assertExists()
        compose.onNodeWithContentDescription("Bloom, starts at the first pour. Add 40 grams. Pour for 10 seconds, then wait 35 seconds. Stir gently 3 times.").assertExists()
        tap("Start timer")
        pass(2)
        compose.onNodeWithText("Stir gently 3 times").assertIsDisplayed()
        capture("iced-pour")
        pass(115)
        compose.onNodeWithText("Scheduled pours ended at 1:30. Target finish ~2:10").assertExists()
        compose.onNodeWithText("Stir once, then let it draw down").assertExists()
        tap("Preparation notes")
        compose.onNodeWithText("Make sure all the ice has melted, then pour over fresh ice.").assertExists()
        capture("iced-drawdown")
        tap("Finish brew")
        compose.onNodeWithText("Target finish 2:10").assertExists()
        tap("Save brew")
        // The record: the details in Preparation, and the source to open.
        compose.onNodeWithContentDescription("Total brew water 150 grams, plus 70 grams of ice").performScrollTo()
        compose.onNodeWithContentDescription("Water temperature 91 degrees Celsius").assertExists()
        compose.onNodeWithContentDescription("Open the recipe's source, kurasu.kyoto").assertExists()
        capture("iced-record-preparation")
    }

    @Test fun `capture the iced recipe editor`() {
        compose.setContent { BrewprintTheme(Appearance.LIGHT) { RecipeEditor(kurasu, { true }, {}) } }
        capture("editor-top")
        compose.onNode(hasText("Coffee dose, g") and hasSetTextAction()).performTextReplacement("18")
        compose.onNodeWithText("Scale pours to keep 1:9.4").performScrollTo()
        capture("editor-dose-changed")
        compose.onNodeWithText("Stir gently 3 times").performScrollTo()
        capture("editor-pulses")
        compose.onNodeWithText("Preparation notes").performScrollTo()
        capture("editor-details")
    }

    @Test fun `an iced brew asks for the ice before the brewer goes on the scale`() {
        compose.setContent {
            BrewprintTheme(Appearance.LIGHT) {
                ScalePrep(ScaleStep.BREWER, ScaleLive(connected = true, grams = 312.4, steady = true), 16.0, 9.375, null, false, ice = 70.0, onTare = {}, onUse = {}, onSkip = {}, onWithout = {})
            }
        }
        compose.onNodeWithText("Put 70 g ice in the server first").assertExists()
        compose.onNodeWithText("Put the brewer on the scale").assertExists()
    }
}

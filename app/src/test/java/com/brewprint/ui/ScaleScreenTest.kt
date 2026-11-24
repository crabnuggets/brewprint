package com.brewprint.ui

import android.content.ContextWrapper
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class ScaleScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()
    private val screenshots = File(System.getProperty("user.home"), ".cache/brewprint-android/scale-screen/screenshots")
    private lateinit var repository: BrewRepository
    private var live by mutableStateOf(ScaleLive(connected = true, grams = 18.4, steady = true))
    private var tares = 0
    private val recipe = Recipe(name = "Three pours", dose = 15.0, pulses = listOf(Pulse(water = 240.0, pourSeconds = 40)))

    private fun open(paired: Boolean = true, appearance: Appearance = Appearance.LIGHT, theme: Theme = Theme.MONOCHROME, draft: (BrewDraft) -> BrewDraft = { it }, brewing: Boolean = false) {
        val directory = temporary.newFolder("data")
        repository = BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = directory
        })
        val beans = Beans(name = "Ethiopia", roaster = "Sample")
        repository.upsertBeans(beans).getOrThrow()
        repository.upsertGrinder(Grinder(name = "Hand grinder", lastSetting = 24)).getOrThrow()
        repository.upsertRecipe(recipe).getOrThrow()
        if (paired) repository.setScale(PairedScale("AA:BB:CC:DD:EE:FF", "TIMEMORE Dot")).getOrThrow()
        if (brewing) repository.updateDraft(draft(repository.startDraft().getOrThrow().copy(beans = beans, recipe = recipe, stage = DraftStage.SHEET))).getOrThrow()
        compose.setContent {
            val state by repository.state.collectAsState()
            CompositionLocalProvider(LocalScale provides ScaleUi(live) { tares++ }) {
                BrewprintTheme(appearance, theme) { BrewprintApp(state, repository) }
            }
        }
    }

    private fun capture(name: String) {
        screenshots.mkdirs()
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        File(screenshots, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun shows(text: String) = compose.onNodeWithText(text).assertIsDisplayed()

    @Test fun `the scale weighs and tares without a brew`() {
        open()
        capture("home-on")
        compose.onNodeWithContentDescription("Scale, TIMEMORE Dot, 18.4 grams").assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("On scale, 18.4 grams").assertIsDisplayed()
        capture("scale-live")
        // "Tare" can be used again and again; nothing is saved and no brew begins.
        compose.onNodeWithText("Tare").performClick()
        live = ScaleLive(connected = true, grams = 18.4, steady = true, taring = true)
        shows("Taring")
        compose.onNodeWithText("Tare").assertIsNotEnabled()
        capture("scale-taring")
        live = ScaleLive(connected = true, grams = 0.0, steady = true)
        compose.onNodeWithContentDescription("On scale, 0.0 grams").assertIsDisplayed()
        compose.onNodeWithText("Tare").performClick()
        assertEquals(2, tares)
        live = ScaleLive(connected = true, grams = 18.4, steady = true, tareFailed = true)
        shows("The scale didn't tare")
        compose.onNodeWithText("Tare").assertIsEnabled()
        capture("scale-tare-failed")
        assertNull(repository.state.value.draft)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Scale, TIMEMORE Dot, 18.4 grams").assertIsDisplayed()
    }

    @Test fun `a scale that is off or silent offers no tare`() {
        live = ScaleLive(connected = false)
        open()
        capture("home-off")
        compose.onNodeWithContentDescription("Scale, TIMEMORE Dot, off").performClick()
        shows("Turn the scale on")
        shows("It connects by itself.")
        compose.onNodeWithContentDescription("TIMEMORE Dot, not connected").assertIsDisplayed()
        compose.onNodeWithText("Tare").assertIsNotEnabled()
        capture("scale-off")
        live = ScaleLive(connected = true, grams = null, silent = true)
        shows("No scale reading")
        compose.onNodeWithText("Tare").assertIsNotEnabled()
        capture("scale-no-reading")
    }

    @Test fun `a brew using the scale is offered instead of a tare`() {
        live = ScaleLive(connected = true, grams = 152.6, steady = true)
        open(brewing = true, draft = { it.copy(scale = ScaleData(), scaleStep = ScaleStep.ARMED) })
        compose.onNodeWithContentDescription("Scale, TIMEMORE Dot, 152.6 grams").performClick()
        shows("Brew in progress")
        shows("No tare here, so the brew's targets stay right.")
        compose.onNodeWithText("Tare").assertDoesNotExist()
        capture("scale-brewing")
        compose.onNodeWithText("Resume brew").performClick()
        shows("Brew sheet")
        assertEquals(0, tares)
    }

    @Test fun `a brew that has not touched the scale leaves the tare`() {
        open(brewing = true)
        compose.onNodeWithContentDescription("Scale, TIMEMORE Dot, 18.4 grams").performScrollTo().performClick()
        compose.onNodeWithText("Tare").assertIsEnabled()
    }

    @Test fun `without a paired scale Brew has no scale line`() {
        open(paired = false)
        compose.onAllNodes(hasContentDescription("Scale,", substring = true)).assertCountEquals(0)
    }

    @Test fun `nearby devices blocked by Android offers its settings`() {
        compose.setContent {
            BrewprintTheme(Appearance.LIGHT) { ScaleScreen(PairedScale("AA:BB:CC:DD:EE:FF", "TIMEMORE Dot"), ScaleLive(connected = false), brewing = false, onTare = {}, onResumeBrew = {}, allowedCheck = { false }) {} }
        }
        shows("Blocked by Android")
        shows("Open Android settings")
        compose.onNodeWithText("Tare").assertDoesNotExist()
        capture("scale-blocked")
    }

    @Test fun `the scale screen in dark appearance`() {
        open(appearance = Appearance.DARK)
        capture("home-on-dark")
        compose.onNodeWithContentDescription("Scale, TIMEMORE Dot, 18.4 grams").performClick()
        compose.onNodeWithContentDescription("On scale, 18.4 grams").assertIsDisplayed()
        capture("scale-live-dark")
    }

    @Test fun `the scale screen in the blueprint`() {
        open(theme = Theme.BLUEPRINT)
        compose.onNodeWithContentDescription("Scale, TIMEMORE Dot, 18.4 grams").performClick()
        compose.onNodeWithContentDescription("On scale, 18.4 grams").assertIsDisplayed()
        capture("scale-live-blueprint")
    }

    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    @Test fun `a long reading fits a small phone at the largest text`() {
        RuntimeEnvironment.setFontScale(2f)
        live = ScaleLive(connected = true, grams = 1952.6, steady = true)
        open()
        compose.onNodeWithContentDescription("Scale, TIMEMORE Dot, 1952.6 grams").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        val reading = compose.onNodeWithContentDescription("On scale, 1952.6 grams").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assert(reading.left >= 0f && reading.right <= screen.right) { "$reading in $screen" }
        compose.onNodeWithText("Tare").assertIsDisplayed()
        capture("scale-small-largest")
    }
}

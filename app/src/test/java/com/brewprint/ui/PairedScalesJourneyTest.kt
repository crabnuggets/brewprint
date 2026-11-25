package com.brewprint.ui

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
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
class PairedScalesJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()
    private val screenshots = File(System.getProperty("user.home"), ".cache/brewprint-android/paired-scales/screenshots")
    private lateinit var repository: BrewRepository
    private val home = PairedScale("AA:AA:AA:AA:AA:AA", "Home", model = "TIMEMORE Dot")
    private val office = PairedScale("BB:BB:BB:BB:BB:BB", "TIMEMORE Basic3 Link")
    private var live by mutableStateOf(ScaleLive(connected = true, grams = 0.0, steady = true, battery = 82))
    private var statuses by mutableStateOf(mapOf(home.address to ScaleStatus(true, 82), office.address to ScaleStatus(true, 64)))
    private val recipe = Recipe(name = "Three pours", dose = 15.0, pulses = listOf(Pulse(water = 240.0, pourSeconds = 40)))

    private fun open(vararg scales: PairedScale, appearance: Appearance = Appearance.LIGHT, theme: Theme = Theme.MONOCHROME, brewing: Boolean = false) {
        val directory = temporary.newFolder("data")
        repository = BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = directory
        })
        val beans = Beans(name = "Ethiopia", roaster = "Sample")
        repository.upsertBeans(beans).getOrThrow()
        repository.upsertGrinder(Grinder(name = "Hand grinder", lastSetting = 24)).getOrThrow()
        repository.upsertRecipe(recipe).getOrThrow()
        scales.forEach { repository.pairScale(it).getOrThrow() }
        if (brewing) repository.updateDraft(repository.startDraft().getOrThrow().copy(beans = beans, recipe = recipe, stage = DraftStage.SHEET)).getOrThrow()
        compose.setContent {
            val state by repository.state.collectAsState()
            CompositionLocalProvider(LocalScale provides ScaleUi(live, statuses)) {
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

    private fun settings() {
        compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab) and hasText("Settings")).performClick()
        compose.onNodeWithText("Coffee scale").performScrollTo()
        compose.onNodeWithText("Add a scale").performScrollTo()
    }

    private val inUse get() = repository.state.value.scale

    @Test fun `one paired scale is listed without a choice to make`() {
        statuses = mapOf(office.address to ScaleStatus(true, 82))
        open(office)
        settings()
        compose.onNode(hasText("TIMEMORE Basic3 Link") and hasText("In use · Battery 82%")).assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.RadioButton) and hasText("TIMEMORE Basic3 Link")).assertCountEquals(0)
        capture("settings-one")
    }

    @Test fun `tapping a scale that is on uses it, and one that is off can't be chosen`() {
        open(home, office)
        settings()
        compose.onNode(hasText("Home") and hasText("In use · Battery 82%")).assertIsSelected()
        val other = compose.onNode(hasText("TIMEMORE Basic3 Link") and hasText("On · Battery 64%"))
        other.assertIsNotSelected()
        capture("settings-two-on")
        other.performClick()
        assertEquals(office.address, inUse?.address)
        compose.onNode(hasText("TIMEMORE Basic3 Link") and hasText("In use · Battery 64%")).assertIsSelected()

        statuses = mapOf(home.address to ScaleStatus(false), office.address to ScaleStatus(true, 64))
        val off = compose.onNode(hasText("Home") and hasText("Not connected"))
        off.assertIsNotEnabled()
        capture("settings-two-one-on")
        off.performClick()
        assertEquals(office.address, inUse?.address)
    }

    @Test fun `while a brew holds the scale, Settings offers no other`() {
        open(home, office, brewing = true)
        compose.runOnIdle { repository.changeDraft { it.copy(scale = ScaleData(), scaleStep = ScaleStep.ARMED) } }
        settings()
        val other = compose.onNode(hasText("TIMEMORE Basic3 Link") and hasText("On · Battery 64%"))
        other.assertIsNotEnabled()
        other.performClick()
        assertEquals(home.address, inUse?.address)
    }

    @Test fun `a scale is renamed in place and can be forgotten`() {
        open(home, office)
        settings()
        compose.onNodeWithContentDescription("More actions for TIMEMORE Basic3 Link").performClick()
        capture("settings-more-actions")
        compose.onNodeWithText("Rename").performClick()
        // The field starts from the current name, with the scale's own name beneath it.
        val field = compose.onNode(hasSetTextAction())
        field.assert(hasText("TIMEMORE Basic3 Link"))
        field.performTextReplacement("")
        compose.onNodeWithContentDescription("Save name").assertIsNotEnabled()
        field.performTextReplacement("Office")
        compose.onNode(hasText("TIMEMORE Basic3 Link") and !hasSetTextAction()).assertIsDisplayed()
        capture("settings-renaming")
        compose.onNodeWithContentDescription("Save name").performClick()
        assertEquals(PairedScale(office.address, "Office", model = "TIMEMORE Basic3 Link"), repository.state.value.pairedScales[1])
        compose.onNode(hasText("Office") and hasText("On · Battery 64%")).assertIsDisplayed()

        // Cancel leaves the name alone.
        compose.onNodeWithContentDescription("More actions for Office").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Elsewhere")
        compose.onNodeWithContentDescription("Cancel").performClick()
        assertEquals("Office", repository.state.value.pairedScales[1].name)

        // Forgetting the scale in use hands over to the one left.
        compose.onNodeWithContentDescription("More actions for Home").performClick()
        compose.onNodeWithText("Forget").performClick()
        assertEquals(listOf("Office"), repository.state.value.pairedScales.map { it.name })
        assertEquals(office.address, inUse?.address)
    }

    @Test fun `before the first pour the chip chooses a scale and starts the scale steps again`() {
        open(home, office, brewing = true)
        compose.onNodeWithText("Resume brew").performClick()
        compose.onNodeWithText("Tare").performClick()
        live = ScaleLive(connected = true, grams = 15.23, steady = true, battery = 82)
        compose.onNodeWithText("Use 15.2 g").assertIsDisplayed()
        capture("chip-button")
        compose.onNodeWithContentDescription("Home, in use. Choose a scale").performClick()
        compose.onNodeWithText("Choose a scale").assertIsDisplayed()
        compose.onNodeWithText("Switching starts the scale steps again.").assertIsDisplayed()
        compose.onNode(hasText("Home") and hasText("In use · Battery 82%")).assertIsSelected()
        compose.onNode(hasText("TIMEMORE Basic3 Link") and hasText("On · Battery 64%")).performClick()
        compose.waitForIdle()
        assertEquals(office.address, inUse?.address)
        val draft = repository.state.value.draft!!
        assertNull(draft.scale)
        assertEquals(ScaleStep.BREWER, draft.scaleStep)
        compose.onNodeWithText("Choose a scale").assertDoesNotExist()
        compose.onNodeWithText("Put the brewer on the scale").assertIsDisplayed()
        compose.onNodeWithContentDescription("TIMEMORE Basic3 Link, in use. Choose a scale").assertIsDisplayed()
    }

    @Test fun `choosing the scale already in use only closes the list`() {
        open(home, office, brewing = true)
        compose.onNodeWithText("Resume brew").performClick()
        compose.onNodeWithText("Tare").performClick()
        compose.onNodeWithContentDescription("Home, in use. Choose a scale").performClick()
        compose.onNode(hasText("Home") and hasText("In use · Battery 82%")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Choose a scale").assertDoesNotExist()
        assertEquals(home.address, inUse?.address)
        assertEquals(ScaleStep.COFFEE, repository.state.value.draft!!.scaleStep)
    }

    @Test fun `once the timer runs, or with one scale on, the chip only reports`() {
        open(home, office, brewing = true)
        compose.onNodeWithText("Resume brew").performClick()
        statuses = mapOf(home.address to ScaleStatus(true, 82), office.address to ScaleStatus(false))
        compose.onNodeWithContentDescription("Home, connected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Home, in use. Choose a scale").assertDoesNotExist()
        statuses = mapOf(home.address to ScaleStatus(true, 82), office.address to ScaleStatus(true, 64))
        compose.onNodeWithContentDescription("Home, in use. Choose a scale").assertIsDisplayed()
        compose.runOnIdle { repository.changeDraft { it.copy(scale = ScaleData(), scaleStep = ScaleStep.ARMED, timer = BrewTimer(startedAt = System.currentTimeMillis(), byWeight = true)) } }
        compose.onNodeWithContentDescription("Home, connected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Home, in use. Choose a scale").assertDoesNotExist()
        capture("chip-status-timer")
    }

    @Test fun `the scale screen chooses a scale too`() {
        open(home, office)
        compose.onNodeWithContentDescription("Scale, Home, 0.0 grams").performClick()
        compose.onNodeWithContentDescription("Home, in use. Choose a scale").performClick()
        compose.onNodeWithText("Switching starts the scale steps again.").assertDoesNotExist()
        compose.onNode(hasText("TIMEMORE Basic3 Link") and hasText("On · Battery 64%")).performClick()
        compose.waitForIdle()
        assertEquals(office.address, inUse?.address)
    }

    @Test fun `the list keeps its look in every theme`() {
        var appearance by mutableStateOf(Appearance.LIGHT)
        var theme by mutableStateOf(Theme.MONOCHROME)
        compose.setContent {
            CompositionLocalProvider(LocalScale provides ScaleUi(live, statuses)) {
                BrewprintTheme(appearance, theme) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().paper().padding(16.dp), contentAlignment = androidx.compose.ui.Alignment.TopEnd) {
                        ScaleChooserList(listOf(home, office), home, LocalScale.current, "Switching starts the scale steps again.", grown = true, onClose = {}, onChoose = {})
                    }
                }
            }
        }
        compose.onNode(hasText("Home") and hasText("In use · Battery 82%")).assertIsSelected()
        capture("chooser-light")
        appearance = Appearance.DARK
        capture("chooser-dark")
        theme = Theme.BLUEPRINT
        capture("chooser-blueprint-dark")
    }

    @Test fun `the chip grows into the list`() {
        var grown by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalScale provides ScaleUi(live, statuses)) {
                BrewprintTheme(Appearance.LIGHT) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().paper().padding(16.dp), contentAlignment = androidx.compose.ui.Alignment.TopEnd) {
                        ScaleChooserList(listOf(home, office), home, LocalScale.current, "Switching starts the scale steps again.", grown, onClose = {}, onChoose = {})
                        androidx.compose.material3.TextButton({ grown = true }, androidx.compose.ui.Modifier.padding(top = 400.dp)) { androidx.compose.material3.Text("Grow") }
                    }
                }
            }
        }
        val width = { compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.PaneTitle, "Choose a scale")).fetchSemanticsNode().size.width }
        val chip = width()
        capture("motion-chooser-0-chip")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Grow").performClick()
        compose.waitForIdle()
        repeat(10) { compose.mainClock.advanceTimeByFrame() }
        val midway = width()
        capture("motion-chooser-1-growing")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val full = width()
        assert(chip < midway && midway < full) { "$chip < $midway < $full" }
        compose.onNodeWithText("Choose a scale").assertIsDisplayed()
    }
}

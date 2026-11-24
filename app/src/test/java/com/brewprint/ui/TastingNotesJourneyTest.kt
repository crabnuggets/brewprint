package com.brewprint.ui

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.brewprint.data.*
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class TastingNotesJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()

    private val saved = Journal(impression = "Bright and sweet", nextTime = "Try one tick finer")
    private lateinit var brew: BrewRecord

    private fun repository(): BrewRepository {
        val data = temporary.newFolder("data")
        val repository = BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = data
        })
        val beans = Beans(name = "Kii", roaster = "Tim Wendelboe")
        val grinder = Grinder(name = "Comandante", lastSetting = 24)
        val plan = Recipe(name = "Three pours", dose = 15.0, pulses = listOf(Pulse(water = 240.0, pourSeconds = 40)))
        repository.upsertBeans(beans).getOrThrow()
        repository.upsertGrinder(grinder).getOrThrow()
        repository.upsertRecipe(plan).getOrThrow()
        val draft = repository.startDraft().getOrThrow().copy(beans = beans, grinder = grinder, setting = 24, recipe = plan, journal = saved)
        repository.updateDraft(draft).getOrThrow()
        brew = repository.saveBrew(draft.id).getOrThrow()
        return repository
    }

    private val screenshots = File(System.getProperty("user.home"), ".cache/brewprint-android/tasting/screenshots")

    private fun show(repository: BrewRepository, appearance: Appearance = Appearance.LIGHT, theme: Theme = Theme.MONOCHROME) = compose.setContent {
        val state by repository.state.collectAsState()
        BrewprintTheme(appearance, theme) { BrewprintApp(state, repository) }
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

    /** Opens the taste exploration with a mix of chosen and empty groups, as the design boards show it. */
    private fun explore(repository: BrewRepository) {
        repository.saveNoteEdit(brew.id, saved.copy(flavorTags = listOf("Fruity", "Floral"), sweetness = "High", acidity = "Medium", body = "Light", mouthfeelTags = listOf("Silky"))).getOrThrow()
        // At large text the floating "New brew" covers the card's action, so it is pressed as TalkBack would.
        compose.onNodeWithText("Resume tasting notes").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        tap("Explore the taste")
        compose.onNodeWithText("Flavor").performScrollTo()
    }

    // Footer actions such as "Save notes" are pinned, so only content off screen is scrolled to.
    private fun tap(text: String) = compose.onNodeWithText(text).apply { if (!isDisplayed()) performScrollTo() }.performClick()

    @Test fun `a taste is chosen, changed and cleared, and flavors are picked together`() {
        val repository = repository()
        show(repository)
        tap("Edit tasting notes")
        tap("Explore the taste")

        // Nothing is chosen at first, so no group offers to clear.
        compose.onNodeWithContentDescription("Clear sweetness").assertDoesNotExist()
        val high = compose.onAllNodesWithText("High")[0]
        high.performScrollTo().performClick()
        high.assertIsSelected()
        compose.onNodeWithContentDescription("Clear sweetness").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear acidity").assertDoesNotExist()

        // Another choice in the group replaces the first.
        compose.onAllNodesWithText("Low")[0].performClick()
        compose.onAllNodesWithText("Low")[0].assertIsSelected()
        high.assertIsNotSelected()

        // "×" clears the group; tapping the chosen one again does too.
        compose.onNodeWithContentDescription("Clear sweetness").performClick()
        compose.onAllNodesWithText("Low")[0].assertIsNotSelected()
        compose.onNodeWithContentDescription("Clear sweetness").assertDoesNotExist()
        tap("Full")
        compose.onNodeWithContentDescription("Clear body").assertIsDisplayed()
        tap("Full")
        compose.onNodeWithText("Full").assertIsNotSelected()
        tap("Light")
        high.performScrollTo().performClick()
        capture("choices-light")

        tap("Fruity")
        tap("Floral")
        tap("Nutty")
        tap("Floral")
        compose.onNodeWithText("Fruity").assertIsOn()
        compose.onNodeWithText("Floral").assertIsOff()

        tap("Save notes")
        val journal = repository.state.value.brews.single().journal
        assertEquals("High", journal.sweetness)
        assertEquals("Light", journal.body)
        assertEquals(listOf("Fruity", "Nutty"), journal.flavorTags)
    }

    @Test fun `unsaved edits to saved notes can be discarded from the header`() {
        val repository = repository()
        show(repository)
        tap("Edit tasting notes")
        // With nothing changed there is nothing to discard.
        compose.onNodeWithContentDescription("Discard changes").assertDoesNotExist()
        compose.onNodeWithText("Bright and sweet").performTextReplacement("Flat and dull")
        capture("discard-action")
        compose.onNodeWithContentDescription("Discard changes").performClick()
        compose.onNodeWithText("Your brew keeps the notes it last saved.").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Flat and dull").assertExists()

        // Edits set aside with "Continue later" can still be discarded on return.
        tap("Continue later")
        assertEquals("Flat and dull", repository.state.value.noteEdits.single().journal.impression)
        tap("Resume tasting notes")
        compose.onNodeWithContentDescription("Discard changes").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.waitForIdle()
        assertTrue(repository.state.value.noteEdits.isEmpty())
        assertEquals(saved, repository.state.value.brews.single().journal)
        assertNull(compose.onAllNodesWithText("Flat and dull").fetchSemanticsNodes().firstOrNull())
    }

    @Test fun `the choices keep their look in dark appearance and in the blueprint`() {
        val repository = repository()
        show(repository, Appearance.DARK)
        explore(repository)
        capture("choices-dark")
        compose.onAllNodesWithText("High")[0].assertIsSelected()
    }

    @Test fun `the blueprint draws the same choices`() {
        val repository = repository()
        show(repository, theme = Theme.BLUEPRINT)
        explore(repository)
        capture("choices-blueprint")
        compose.onNodeWithContentDescription("Clear sweetness").assertExists()
    }

    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    @Test fun `at the largest text the choices stack and stay reachable`() {
        RuntimeEnvironment.setFontScale(2f)
        val repository = repository()
        show(repository)
        explore(repository)
        compose.onNodeWithText("Sweetness in your words, optional").performScrollTo()
        capture("choices-360-largest")
        // Every choice is still on screen in full, one under another, with the clear action beside them.
        val low = compose.onAllNodesWithText("Low")[0].performScrollTo().fetchSemanticsNode().boundsInRoot
        val medium = compose.onAllNodesWithText("Medium")[0].fetchSemanticsNode().boundsInRoot
        assertTrue(medium.top >= low.bottom)
        compose.onNodeWithContentDescription("Clear sweetness").assertIsDisplayed()
        compose.onNodeWithText("Chocolatey").performScrollTo().assertIsDisplayed()
    }

    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    @Test fun `on a narrow phone with larger text the longest flavor still fits its block`() {
        RuntimeEnvironment.setFontScale(1.3f)
        val repository = repository()
        show(repository)
        explore(repository)
        compose.onNodeWithText("Flavor in your words, optional").performScrollTo()
        capture("choices-360-larger")
        // The label is narrower than its block, so nothing is cut off.
        val block = compose.onNodeWithText("Chocolatey").fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithText("Chocolatey", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("$label in $block", label.width < block.width && label.left > block.left)
    }

    @Test fun `the filled block slides to a new choice and the track narrows for the clear action`() {
        val repository = repository()
        show(repository)
        tap("Edit tasting notes")
        tap("Explore the taste")
        compose.onNodeWithText("Sweetness in your words, optional").performScrollTo()
        val track = { compose.onAllNodesWithText("Low")[0].fetchSemanticsNode().layoutInfo.parentInfo!!.width }
        val full = track()
        compose.mainClock.autoAdvance = false
        compose.onAllNodesWithText("Low")[0].performClick()
        compose.mainClock.advanceTimeBy(150)
        // Part-way through, the track is narrower than at rest and wider than it will end.
        val midway = track()
        capture("motion-1-clear-arriving")
        compose.mainClock.advanceTimeBy(400)
        val narrow = track()
        assertTrue("$full > $midway > $narrow", full > midway && midway > narrow)
        compose.onAllNodesWithText("High")[0].performClick()
        compose.mainClock.advanceTimeBy(140)
        capture("motion-2-block-sliding")
        compose.mainClock.autoAdvance = true
        compose.onAllNodesWithText("High")[0].assertIsSelected()
    }
}

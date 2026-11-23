package com.brewprint.ui

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()
    private val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun repository(): BrewRepository {
        val data = temporary.newFolder("data")
        return BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = data
        })
    }

    private fun open(repository: BrewRepository) = compose.setContent {
        val state by repository.state.collectAsState()
        BrewprintTheme(state.appearance) { BrewprintApp(state, repository) }
    }

    private fun back() { compose.waitForIdle(); compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() } }

    @Test fun `Settings is a tab without a Back arrow, and Back from any other tab returns to Brew`() {
        open(repository())
        compose.onNode(tab and hasText("Settings")).performClick()
        compose.onNodeWithText("Brew timer").assertExists()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        for (destination in listOf("Settings", "History", "Library")) {
            compose.onNode(tab and hasText(destination)).performClick()
            back()
            compose.onNode(tab and hasText("Brew")).assertIsSelected()
        }
        // Brew no longer has a gear of its own.
        compose.onNodeWithContentDescription("Settings").assertDoesNotExist()
    }

    @Test fun `a new page starts below its header in its first frame`() {
        val repository = repository()
        repository.upsertRecipe(Recipe(name = "Filter", dose = 15.0, pulses = listOf(Pulse(water = 240.0, pourSeconds = 30)))).getOrThrow()
        open(repository)
        compose.onNode(tab and hasText("Library")).performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("library-recipes").performClick()
        // Frame by frame, the list never sits under the header while the header is being measured.
        var checked = 0
        repeat(4) {
            compose.mainClock.advanceTimeByFrame()
            val title = compose.onAllNodesWithText("Recipes").fetchSemanticsNodes().singleOrNull() ?: return@repeat
            val first = compose.onAllNodesWithText("Filter").fetchSemanticsNodes().singleOrNull() ?: return@repeat
            assertTrue("The first recipe at ${first.boundsInRoot.top} starts under the header at ${title.boundsInRoot.bottom}", first.boundsInRoot.top >= title.boundsInRoot.bottom)
            checked++
        }
        assertTrue(checked > 0)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Filter").assertIsDisplayed()
    }

    @Test fun `New brew and the navigation take no taps on their way out`() {
        val repository = repository()
        repository.upsertBeans(Beans(name = "Ethiopia", roaster = "Sample")).getOrThrow()
        open(repository)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNode(tab and hasText("History")).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        // New brew is still fading out with Brew; tapping it starts nothing.
        compose.onNode(hasText("New brew") and hasClickAction()).performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertNull(repository.state.value.draft)
        compose.onNode(tab and hasText("History")).assertIsSelected()
        // Opening an item's form hides the navigation; while it slides away it offers no tabs.
        compose.openLibrarySection("beans")
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Add beans").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun `New brew keeps its label while it fades out with Brew`() {
        val repository = repository()
        repository.upsertBeans(Beans(name = "Ethiopia", roaster = "Sample")).getOrThrow()
        repository.upsertGrinder(Grinder(name = "Hand grinder", lastSetting = 24)).getOrThrow()
        open(repository)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("New brew").performClick()
        repeat(6) {
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("Resume brew").assertDoesNotExist()
        }
        // Still fading out, it keeps its label.
        compose.onNode(hasText("New brew") and hasClickAction()).assertExists()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        // The draft's page is titled "New brew"; the floating action is gone.
        compose.onNode(hasText("New brew") and hasClickAction()).assertDoesNotExist()
        compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
    }

    @Test fun `New brew floats over Brew only, and becomes Resume brew while a brew is in progress`() {
        val repository = repository()
        open(repository)
        compose.onNodeWithText("New brew").assertIsDisplayed().assertHasClickAction()
        compose.onNode(tab and hasText("History")).performClick()
        compose.onNodeWithText("New brew").assertDoesNotExist()
        compose.onNode(tab and hasText("Brew")).performClick()
        // A setup left with a choice made stays in progress.
        val beans = Beans(name = "Kii", roaster = "Sample")
        compose.runOnIdle {
            repository.upsertBeans(beans).getOrThrow()
            repository.updateDraft(repository.startDraft().getOrThrow().copy(beans = beans)).getOrThrow()
        }
        compose.onNodeWithText("Resume brew").assertIsDisplayed()
        compose.onNodeWithText("New brew").assertDoesNotExist()
        // The card says what is in progress and resumes it too.
        compose.onNodeWithText("Brew in progress").assertIsDisplayed()
        compose.onNodeWithText("Kii").assertIsDisplayed()
        compose.onNodeWithText("Brew in progress").performClick()
        compose.onNodeWithText("Use this plan").assertExists()
    }

    @Test fun `the Library opens on a hub, and a brew started from a recipe returns to it on Back`() {
        val repository = repository()
        val recipe = Recipe(name = "Kurasu Iced", dose = 16.0, pulses = listOf(Pulse(water = 150.0, pourSeconds = 30)))
        compose.runOnIdle {
            repository.upsertRecipe(recipe).getOrThrow()
            repository.upsertGrinder(Grinder(name = "Comandante C40", lastSetting = 22)).getOrThrow()
        }
        open(repository)
        compose.onNode(tab and hasText("Library")).performClick()
        compose.onNodeWithTag("library-recipes").assertIsDisplayed()
        compose.onNodeWithTag("library-beans").assertExists()
        compose.onNodeWithTag("library-grinders").assertExists()
        compose.openLibrarySection("recipes")
        compose.onNodeWithText("Kurasu Iced").performClick()
        // An item's own screen hides the navigation.
        compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
        compose.onNodeWithText("Brew with this recipe").performClick()
        compose.onNodeWithText("Use this plan").assertExists()
        compose.runOnIdle { assertEquals(recipe.id, repository.state.value.draft?.recipe?.id) }
        // Untouched, Back discards the new brew and returns to the recipe.
        back()
        compose.onNodeWithText("Brew with this recipe").assertIsDisplayed()
        compose.runOnIdle { assertNull(repository.state.value.draft) }
        back()
        back()
        compose.onNodeWithTag("library-recipes").assertIsDisplayed()
    }

    @Test fun `with a brew in progress an item offers Resume brew instead, and archived beans offer no brew`() {
        val repository = repository()
        val kii = Beans(name = "Kii", roaster = "Sample")
        val old = Beans(name = "Old bag", roaster = "Sample", archived = true)
        compose.runOnIdle {
            repository.upsertBeans(kii).getOrThrow()
            repository.upsertBeans(old).getOrThrow()
            repository.startDraft().getOrThrow()
        }
        open(repository)
        compose.openLibrarySection("beans")
        compose.onNodeWithText("Kii").performClick()
        compose.onNodeWithText("Brew with these beans").assertIsNotEnabled()
        compose.onNodeWithText("A brew is already in progress.").assertExists()
        back()
        compose.onNodeWithText("Archived beans").performScrollTo().performClick()
        compose.onNodeWithText("Old bag").performClick()
        compose.onNodeWithText("Brew with these beans").assertDoesNotExist()
        // Saving an edit keeps the beans under "Archived beans", where Back returns.
        compose.onNodeWithContentDescription("Edit beans").performClick()
        compose.onNodeWithText("Save beans").performScrollTo().performClick()
        back()
        // Only the archived list shows archived beans.
        compose.onNodeWithText("Old bag").assertExists()
        // Tapping Library again goes back to the hub.
        back()
        compose.onNode(tab and hasText("Library")).performClick()
        compose.onNodeWithTag("library-beans").assertIsDisplayed()
    }

    @Test fun `a failed sync marks Settings with a dot that screen readers hear`() {
        compose.setContent { BrewprintTheme(Appearance.LIGHT) { FloatingNavigation("Brew", shown = true, syncFailed = true) {} } }
        compose.onNode(tab and hasText("Settings")).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Sync failed"))
        compose.onNodeWithTag("sync-failed-dot", useUnmergedTree = true).assertExists()
    }

    @Test
    @Config(qualifiers = "w320dp-h700dp-xxhdpi")
    fun `on a narrow phone with large text every tab keeps a 48 dp target`() {
        RuntimeEnvironment.setFontScale(1.5f)
        compose.setContent { BrewprintTheme(Appearance.LIGHT) { FloatingNavigation("Settings", shown = true) {} } }
        compose.onAllNodes(tab).fetchSemanticsNodes().forEach { node ->
            val width = with(compose.density) { node.size.width.toDp() }
            val height = with(compose.density) { node.size.height.toDp() }
            assertTrue("tab is $width x $height", width >= 47.5.dp && height >= 47.5.dp)
        }
    }
}

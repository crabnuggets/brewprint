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
        val recipe = Recipe(name = "Kurasu Iced", dose = 16.0, ratio = 9.375, pulses = listOf(Pulse(water = 150.0, pourSeconds = 30)))
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
        // Tapping Library again goes back to the hub.
        back()
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

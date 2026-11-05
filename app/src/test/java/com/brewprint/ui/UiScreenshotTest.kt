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

    private fun tap(text: String) = compose.onNodeWithText(text).apply {
        if (!isDisplayed()) performScrollTo()
    }.performClick()

    private fun seededRepository(): BrewRepository {
        val data = temporary.newFolder("journey")
        val repository = BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = data
        })
        val beans = Beans(name = "Ethiopia natural", roaster = "Sample Roaster", expectedNotes = "Blueberry, cocoa")
        val grinder = Grinder(name = "Hand grinder", lastSetting = 24)
        val recipe = Recipe(name = "Three-pulse filter", dose = 15.0, ratio = 16.0, pulses = listOf(
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
        compose.onNode(hasText("Brew") and isSelectable()).performClick()
        tap("New brew")
        pair("setup")
        tap("Use this plan")
        pair("sheet")
        tap("Finish brew")
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
        compose.onNodeWithText("Finish brew").assertIsDisplayed().performClick()
        capture("review-narrow-large-font")
        compose.onNodeWithText("Save brew").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cup observations").performScrollTo().assertIsDisplayed()
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
}

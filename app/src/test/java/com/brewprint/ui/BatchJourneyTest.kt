package com.brewprint.ui

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.brewprint.data.*
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
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
class BatchJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()
    private val screenshots = File(System.getProperty("user.home"), ".cache/brewprint-android/batches/screenshots")
    private val kii = Beans(name = "Kii", roaster = "Tim Wendelboe")
    private val older = LocalDate.now().minusDays(20).toString()
    private val newer = LocalDate.now().minusDays(5).toString()

    private fun capture(name: String) {
        screenshots.mkdirs()
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        File(screenshots, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun back() { compose.waitForIdle(); compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() } }

    private fun seeded(): BrewRepository {
        val data = temporary.newFolder("data")
        val repository = BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir(): File = data })
        repository.upsertGrinder(Grinder(name = "Comandante C40", lastSetting = 22)).getOrThrow()
        val recipe = Recipe(name = "Filter", dose = 15.0, pulses = listOf(Pulse(water = 240.0, pourSeconds = 30)))
        repository.upsertRecipe(recipe).getOrThrow()
        repository.saveBeans(kii, older).getOrThrow()
        val draft = repository.startDraftWith(beansId = kii.id).getOrThrow().copy(recipe = recipe, stage = DraftStage.REVIEW)
        repository.updateDraft(draft).getOrThrow()
        repository.saveBrew(draft.id).getOrThrow()
        return repository
    }

    private fun open(repository: BrewRepository) = compose.setContent {
        val state by repository.state.collectAsState()
        BrewprintTheme(Appearance.LIGHT) { BrewprintApp(state, repository) }
    }

    @Test fun `buying the beans again adds a batch, and a repeat moves to it and says so`() {
        val repository = seeded()
        open(repository)
        // Brew shows the bag's age, and the record its days off roast on the day.
        compose.onNodeWithText("· 20 days ago", substring = true).assertExists()
        compose.onNodeWithText("View brew").performClick()
        compose.onNodeWithText("· 20 days off roast", substring = true).assertExists()
        back()
        compose.openLibrarySection("beans")
        compose.onNodeWithText("Kii").performClick()
        compose.onNodeWithText("Bought again").performScrollTo().performClick()
        // No date picked: a bag without a roast date.
        compose.onNodeWithText("Add batch").performClick()
        compose.runOnIdle { repository.addBeanBatch(kii.id, newer).getOrThrow() }
        compose.onNodeWithText("Current").performScrollTo().assertExists()
        capture("bean-detail-batches")
        compose.runOnIdle { assertEquals(3, repository.state.value.beans.single().batches.size) }
        // Delete the undated one, after asking.
        compose.onNodeWithContentDescription("Delete batch, No roast date").performScrollTo().performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle { assertEquals(listOf(older, newer), repository.state.value.beans.single().batches.map { it.roastDate }) }
        back()
        back()
        compose.onNode(hasText("Brew") and isSelectable()).performClick()
        compose.onNodeWithText("Repeat").performClick()
        compose.onNodeWithText("Using newest batch", substring = true).assertExists()
        compose.onNodeWithText("Original brew used", substring = true).assertExists()
        compose.onNodeWithText("Change batch").performScrollTo().performClick()
        capture("setup-change-batch")
        compose.onNodeWithText("Choose a batch").assertExists()
    }
}

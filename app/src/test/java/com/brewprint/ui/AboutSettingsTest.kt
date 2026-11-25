package com.brewprint.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import com.brewprint.BuildConfig
import com.brewprint.data.Appearance
import com.brewprint.data.Beans
import com.brewprint.data.BrewRepository
import com.brewprint.data.TimerSettings
import com.brewprint.data.unzip
import com.brewprint.update.Release
import com.brewprint.update.UpdateState
import com.brewprint.update.Version
import android.provider.Settings
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import kotlinx.coroutines.flow.MutableStateFlow
import org.robolectric.Shadows
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class AboutSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()
    private val directory = File(System.getProperty("user.home"), ".cache/brewprint-android/about")
    private val errors = mutableListOf<String>()

    /** Stands in for Android's save dialog: answers with [answer] and records the file name it was offered. */
    private class SaveDialog(val answer: () -> Uri?) : ActivityResultRegistry() {
        val offered = mutableListOf<Any?>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            offered += input
            dispatchResult(requestCode, answer())
        }
    }

    private val updates = MutableStateFlow<UpdateState>(UpdateState.UpToDate)
    private val release = Release(Version(1, 2, 0), beta = false, apkUrl = "https://example.com/brewprint-v1.2.0.apk", apkSize = 1, sha256 = null, pageUrl = "https://github.com/crabnuggets/brewprint/releases/tag/v1.2.0")

    private fun show(dialog: SaveDialog, readLog: () -> String = { "W Brewprint: Sync failed" }, theme: com.brewprint.data.Theme = com.brewprint.data.Theme.MONOCHROME, appearance: Appearance = Appearance.LIGHT): BrewRepository {
        val data = temporary.newFolder()
        val context: Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir(): File = data }
        val repository = BrewRepository(context)
        repository.upsertBeans(Beans(name = "Ethiopia", roaster = "Sample")).getOrThrow()
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner { override val activityResultRegistry = dialog }) {
                BrewprintTheme(appearance, theme) {
                    SettingsScreen(Appearance.SYSTEM, TimerSettings(), {}, {}, liveUpdatesCheck = { true }, about = { AboutSettings(repository, { errors += it }, readLog, updates) })
                }
            }
        }
        return repository
    }

    @Test fun `Settings ends with the version and an export of the data and log`() {
        val saved = File(temporary.root, "export.zip")
        val dialog = SaveDialog { Uri.fromFile(saved) }
        val repository = show(dialog)
        compose.onNodeWithText("Brewprint ${BuildConfig.VERSION_NAME}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Build ${BuildConfig.VERSION_CODE}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Up to date").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Support").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Report a problem").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Export data").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Export saved")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Export saved").performScrollTo().assertIsDisplayed()
        capture("exported")

        assertEquals(listOf("brewprint-export-${LocalDate.now()}.zip"), dialog.offered)
        val files = unzip(saved.readBytes())
        assertEquals(setOf("about.txt", "data.json", "log.txt"), files.keys)
        assertTrue(files.getValue("about.txt"), files.getValue("about.txt").startsWith("Brewprint ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nAndroid "))
        assertEquals(String(repository.exportData()), files.getValue("data.json"))
        assertTrue("Ethiopia" in files.getValue("data.json"))
        assertEquals("W Brewprint: Sync failed", files.getValue("log.txt"))
        assertEquals(emptyList<String>(), errors)
    }

    @Test fun `cancelling the save dialog says nothing`() {
        show(SaveDialog { null })
        compose.onNodeWithText("Export data").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasText("Export saved")).fetchSemanticsNodes().isEmpty())
        assertEquals(emptyList<String>(), errors)
    }

    @Test fun `a failed export says so`() {
        show(SaveDialog { Uri.fromFile(File(temporary.root, "export.zip")) }, readLog = { error("The log couldn't be read") })
        compose.onNodeWithText("Export data").performScrollTo().performClick()
        compose.waitUntil(5_000) { errors.isNotEmpty() }
        assertEquals(listOf("Couldn't export your data. Try again, or save it somewhere else."), errors)
        assertFalse(compose.onAllNodes(hasText("Export saved")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun `the blueprint theme shows the same section`() {
        show(SaveDialog { null }, theme = com.brewprint.data.Theme.BLUEPRINT)
        compose.onNodeWithText("Export data").performScrollTo().assertIsDisplayed()
        capture("blueprint")
    }

    @Test fun `Version says whether an update is out, in each state`() {
        show(SaveDialog { null })
        fun state(name: String, update: UpdateState, vararg texts: String) {
            compose.runOnIdle { updates.value = update }
            texts.forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
            compose.onNodeWithText("Beta releases").performScrollTo()
            capture("version-$name")
        }
        state("checking", UpdateState.Checking, "Checking for updates…")
        state("up-to-date", UpdateState.UpToDate, "Up to date")
        compose.onNodeWithContentDescription("Check for updates").assertIsDisplayed()
        state("check-failed", UpdateState.CheckFailed, "Couldn't check for updates", "Check your connection and try again.")
        state("available", UpdateState.Available(release), "Brewprint 1.2.0 is available", "Android will ask you to allow Brewprint to install updates.", "What's new", "Update")
        state("beta", UpdateState.Available(release.copy(version = Version(1, 2, 0, 1), beta = true)), "Brewprint 1.2.0-beta.1 is available", "Beta release")
        state("downloading", UpdateState.Available(release, UpdateState.Step.DOWNLOADING, progress = .42f), "Downloading… 42%")
        state("installing", UpdateState.Available(release, UpdateState.Step.INSTALLING), "Installing… Brewprint closes while it updates.")
        state("install-failed", UpdateState.Available(release, problem = "Couldn't install the update. Try again."), "Couldn't install the update. Try again.")
        state("debug", UpdateState.Unavailable, "Updates are off in debug builds")
    }

    @Test fun `an update in dark appearance`() {
        updates.value = UpdateState.Available(release)
        show(SaveDialog { null }, appearance = Appearance.DARK)
        compose.onNodeWithText("Update").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Beta releases").performScrollTo()
        capture("version-available-dark")
    }

    @Test fun `the blueprint theme shows the update too`() {
        updates.value = UpdateState.Available(release)
        show(SaveDialog { null }, theme = com.brewprint.data.Theme.BLUEPRINT)
        compose.onNodeWithText("Update").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Beta releases").performScrollTo()
        capture("version-available-blueprint")
    }

    @Test fun `turning on beta releases is saved`() {
        val repository = show(SaveDialog { null })
        compose.onNode(hasText("Beta releases") and hasClickAction(), useUnmergedTree = false).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(repository.state.value.betaReleases)
    }

    @Test fun `Update first asks Android to allow installs from Brewprint`() {
        updates.value = UpdateState.Available(release)
        show(SaveDialog { null })
        compose.onNodeWithText("Update").performScrollTo().performClick()
        val opened = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, opened.action)
        assertEquals("package:${RuntimeEnvironment.getApplication().packageName}", opened.dataString)
    }

    @Test fun `What's new opens the release on GitHub`() {
        updates.value = UpdateState.Available(release)
        show(SaveDialog { null })
        compose.onNodeWithText("What's new").performScrollTo().performClick()
        assertEquals(release.pageUrl, Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity.dataString)
    }

    @Test fun `a running brew holds the update`() {
        var updated = false
        compose.setContent {
            BrewprintTheme(Appearance.LIGHT) {
                androidx.compose.foundation.layout.Column {
                    VersionPanel("1.1.0", 40, UpdateState.Available(release), betas = false, brewing = true, installAllowed = true, onCheck = {}, onUpdate = { updated = true }, onNotes = {}, onBetas = {})
                }
            }
        }
        compose.onNodeWithText("Finish your brew first. Brewprint closes while it updates.").assertIsDisplayed()
        compose.onNodeWithText("Update").assertIsNotEnabled().performClick()
        assertFalse(updated)
    }

    private fun capture(name: String) {
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}

package com.brewprint.ui

import android.content.ContextWrapper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.brewprint.data.*
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
class BrewTimerJourneyTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var repository: BrewRepository
    private var now = 1_000_000L
    // Pours at 0:00, 0:40 and 1:20; scheduled pours end at 1:40.
    private val recipe = Recipe(name = "Three-pulse filter", dose = 15.0, ratio = 16.0, pulses = listOf(
        Pulse("Bloom", 60.0, 10, 30), Pulse(water = 90.0, pourSeconds = 20, waitSeconds = 20), Pulse(water = 90.0, pourSeconds = 20)))

    @Before fun setUp() {
        val directory = temporary.newFolder("data")
        repository = BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = directory
        })
        val beans = Beans(name = "Ethiopia", roaster = "Sample")
        repository.upsertBeans(beans).getOrThrow()
        repository.upsertGrinder(Grinder(name = "Hand grinder", lastSetting = 24)).getOrThrow()
        repository.upsertRecipe(recipe).getOrThrow()
        repository.updateDraft(repository.startDraft().getOrThrow().copy(beans = beans, recipe = recipe, stage = DraftStage.SHEET)).getOrThrow()
        compose.setContent {
            val state by repository.state.collectAsState()
            CompositionLocalProvider(LocalClock provides { now }) {
                BrewprintTheme(state.appearance) { BrewprintApp(state, repository) }
            }
        }
        tap("Resume brew")
    }

    // Icon actions such as "Change date" are found by their spoken name.
    private fun tap(text: String) = (if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) compose.onNodeWithContentDescription(text) else compose.onNodeWithText(text)).apply {
        if (!isDisplayed()) performScrollTo()
    }.performClick()
    private fun pass(seconds: Int) {
        now += seconds * 1000L
        compose.mainClock.advanceTimeBy(250)
    }
    private fun shows(text: String) = compose.onNodeWithText(text).assertExists()

    @Test fun `timer advances on schedule and the record shows every pulse on schedule`() {
        shows("Tap at the first pour.")
        tap("Start timer")
        shows("Pour"); shows("Pulse 1 of 3"); shows("Pour done")
        pass(12)
        shows("Wait"); shows("After pulse 1 of 3"); shows("Start pulse 2")
        pass(40)
        shows("Pulse 2 of 3")
        pass(60)
        shows("Drawdown"); shows("All 3 pulses done")
        compose.onNodeWithText("Pause").assertDoesNotExist()
        pass(12)
        tap("Finish brew")
        shows("2:04")
        tap("Save brew")
        shows("Timing"); shows("First pour to Finish brew.")
        // Each pulse is one spoken row; on screen it reads "On time".
        compose.onAllNodes(hasContentDescription("started on time", substring = true)).assertCountEquals(3)
        compose.runOnIdle {
            assertEquals(BrewTiming(totalSeconds = 124, pulseStarts = listOf(0, 40, 80)), repository.state.value.brews.single().timing)
        }
    }

    @Test fun `skip undo pause and resume change the schedule as described`() {
        tap("Start timer")
        pass(28)
        tap("Start pulse 2")
        shows("Pulse 2 started 12 s early"); shows("Pulse 2 of 3")
        pass(2)
        tap("Undo")
        shows("After pulse 1 of 3")
        pass(2)
        tap("Start pulse 2")
        pass(25)
        tap("Pause")
        shows("Paused"); shows("Wait held at"); shows("Start pulse 3")
        pass(15)
        shows("Paused for 0:15. Brew time is still running.")
        tap("Resume")
        pass(20)
        shows("Pulse 3 of 3"); shows("Pour done")
        tap("2 done, show plan")
        compose.onNodeWithText("Started 8 s early. Planned for 0:40", substring = true).assertExists()
        compose.onNodeWithText("Started 7 s late. Planned for 1:20", substring = true).assertExists()
        tap("Back to current step")
        tap("Finish brew")
        compose.onNodeWithContentDescription("Back").performClick()
        shows("Pulse 3 of 3")
        tap("Finish brew")
        tap("Save brew")
        compose.runOnIdle {
            val timing = repository.state.value.brews.single().timing!!
            assertEquals(listOf(0, 32, 87), timing.pulseStarts)
            assertEquals(15, timing.pausedSeconds)
            assertEquals(92, timing.totalSeconds)
        }
    }

    @Test fun `a forgotten brew opens the review with no total until one is entered`() {
        tap("Start timer")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        pass(100 + 900 + 5)
        shows("Timer stopped at 16:40, 15 min after the last pour")
        tap("Resume brew")
        shows("Not recorded"); shows("The timer stopped itself. Enter the total if you know it.")
        tap("Enter total")
        val total = compose.onNode(hasText("Total brew time") and hasSetTextAction())
        total.performTextInput("3:75")
        compose.onNodeWithText("Save brew").assertIsNotEnabled()
        // Leaving the review drops the invalid edit, so it cannot keep Save disabled.
        tap("Change setup")
        tap("Use this plan")
        tap("Finish brew")
        compose.onNodeWithText("Save brew").assertIsEnabled()
        tap("Enter total")
        total.performTextReplacement("3:10")
        tap("Save brew")
        shows("Edited.")
        compose.runOnIdle {
            assertEquals(BrewTiming(totalSeconds = 190, totalEdited = true, pulseStarts = listOf(0, 40, 80)), repository.state.value.brews.single().timing)
        }
    }

    @Test fun `untimed brews have no timing and settings hold the three switches`() {
        tap("Finish without timing")
        compose.onNodeWithText("Total brew time").assertDoesNotExist()
        tap("Save brew")
        compose.onNodeWithText("Timing").assertDoesNotExist()
        compose.runOnIdle { assertNull(repository.state.value.brews.single().timing) }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Blocked by Android").assertDoesNotExist()
        tap("Vibrate at each step")
        tap("Keep screen on while brewing")
        compose.runOnIdle { assertEquals(TimerSettings(liveNotification = false, vibrate = false, keepScreenOn = false), repository.state.value.timerSettings) }
    }
}

package com.brewprint.ui

import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.brewprint.data.Appearance
import com.brewprint.data.TimerSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
class LiveUpdatesNoticeTest {
    @get:Rule val compose = createComposeRule()

    private fun settings(timer: TimerSettings, liveUpdates: Boolean) = compose.setContent {
        var current by mutableStateOf(timer)
        BrewprintTheme(Appearance.LIGHT) { SettingsScreen(Appearance.LIGHT, current, {}, { current = it }, {}, liveUpdatesCheck = { liveUpdates }) }
    }

    @Test fun `turned off Live Updates send the user to the app's Live Updates setting`() {
        settings(TimerSettings(liveNotification = true), liveUpdates = false)
        compose.onNodeWithText("Live Updates are off").assertExists()
        compose.onNodeWithText("Blocked by Android").assertDoesNotExist()
        compose.onNodeWithText("Open Android settings").performClick()
        val opened = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS, opened.action)
        assertEquals(RuntimeEnvironment.getApplication().packageName, opened.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test fun `the notice waits for the live notification switch`() {
        settings(TimerSettings(liveNotification = false), liveUpdates = false)
        compose.onNodeWithText("Live Updates are off").assertDoesNotExist()
        compose.onNodeWithText("Live notification").performClick()
        compose.onNodeWithText("Live Updates are off").assertExists()
    }

    @Test fun `allowed Live Updates show no notice`() {
        settings(TimerSettings(liveNotification = true), liveUpdates = true)
        compose.onNodeWithText("Live Updates are off").assertDoesNotExist()
    }
}

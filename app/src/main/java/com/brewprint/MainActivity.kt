package com.brewprint

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brewprint.data.BrewRepository
import com.brewprint.data.Appearance
import com.brewprint.timer.BrewTimerService
import com.brewprint.timer.EXTRA_OPEN_DRAFT
import com.brewprint.scale.ScaleLink
import com.brewprint.ui.BrewprintApp
import com.brewprint.ui.BrewprintTheme
import com.brewprint.ui.LocalScale
import com.brewprint.ui.ScaleUi

class MainActivity : ComponentActivity() {
    /** Counts requests from the live notification to show the brew in progress. */
    private var openDraft by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = BrewRepository.get(applicationContext)
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_OPEN_DRAFT, false)) openDraft++
        setContent {
            val state by repository.state.collectAsStateWithLifecycle()
            val dark = state.appearance == Appearance.DARK || state.appearance == Appearance.SYSTEM && isSystemInDarkTheme()
            SideEffect {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            LaunchedEffect(state.draft?.timer, state.timerSettings) { BrewTimerService.sync(this@MainActivity) }
            LaunchedEffect(state.scale) { ScaleLink.sync(this@MainActivity) }
            val brew = ScaleLink.brew(this@MainActivity)
            val live by brew.live.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalScale provides ScaleUi(live) { brew.tare(System.currentTimeMillis()) }) {
                BrewprintTheme(state.appearance) { BrewprintApp(state, repository, openDraft) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_DRAFT, false)) openDraft++
    }

    override fun onStart() {
        super.onStart()
        BrewTimerService.appOpen = true
        BrewTimerService.clearFinished(this)
        BrewTimerService.sync(this)
        ScaleLink.sync(this)
    }

    override fun onStop() {
        BrewTimerService.appOpen = false
        ScaleLink.sync(this)
        super.onStop()
    }
}

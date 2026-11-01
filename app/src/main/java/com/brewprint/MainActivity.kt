package com.brewprint

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brewprint.data.BrewRepository
import com.brewprint.data.Appearance
import com.brewprint.ui.BrewprintApp
import com.brewprint.ui.BrewprintTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = BrewRepository(applicationContext)
        setContent {
            val state by repository.state.collectAsStateWithLifecycle()
            val dark = state.appearance == Appearance.DARK || state.appearance == Appearance.SYSTEM && isSystemInDarkTheme()
            SideEffect {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            BrewprintTheme(state.appearance) { BrewprintApp(state, repository) }
        }
    }
}

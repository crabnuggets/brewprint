package com.brewprint.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WaterCurveTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Lifting the tared cup off the scale sends readings below 0 g; the curve stays inside its own box. */
    @Test fun `readings below zero stay on the baseline`() {
        val curve = listOf(0.0, 40.0, 40.0, 300.0, 300.0, 280.0, -350.0, -350.0)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.background(Color.White).padding(16.dp)) {
                    Box(Modifier.testTag("curve")) { WaterCurve(curve, listOf(40.0, 300.0), curve.size) }
                    Box(Modifier.fillMaxWidth().height(200.dp).testTag("below"))
                }
            }
        }
        val below = compose.onNodeWithTag("below").fetchSemanticsNode().boundsInWindow
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        // A line along the baseline is 2 dp wide, so half of it may reach just past the curve's box.
        val stroke = with(compose.density) { 2.dp.toPx() }.toInt()
        var drawn = 0
        for (x in below.left.toInt() until below.right.toInt()) for (y in below.top.toInt() + stroke until below.bottom.toInt()) {
            if (bitmap.getPixel(x, y) != android.graphics.Color.WHITE) drawn++
        }
        assertEquals("pixels drawn below the curve", 0, drawn)
    }
}

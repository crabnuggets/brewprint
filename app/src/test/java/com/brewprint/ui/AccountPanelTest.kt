package com.brewprint.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.brewprint.cloud.CloudState
import com.brewprint.data.Appearance
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val directory = File(System.getProperty("user.home"), ".cache/brewprint-android/account")

    private fun show(cloud: CloudState, onSignOut: () -> Unit = {}) = compose.setContent {
        BrewprintTheme(Appearance.LIGHT) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.padding(20.dp)) { AccountPanel(cloud, busy = false, onSignIn = {}, onSignOut = onSignOut) }
            }
        }
    }

    @Test fun `signing out asks first and says the data leaves this phone`() {
        var signedOut = 0
        show(CloudState(available = true, email = "brewer@example.com", syncedAt = System.currentTimeMillis())) { signedOut++ }
        compose.onNodeWithText("Synced just now").assertIsDisplayed()
        capture("signed-in")
        compose.onNodeWithText("Sign out").performClick()
        compose.onNodeWithText("Your beans, grinders, recipes and brews will be removed from this phone. They stay in your Google account.").assertIsDisplayed()
        capture("sign-out-dialog", dialog = true)
        assertEquals(0, signedOut)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, signedOut)

        compose.onNodeWithText("Sign out").performClick()
        compose.onNodeWithText("Sign out?").assertIsDisplayed()
        compose.onNode(hasText("Sign out") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(1, signedOut)
    }

    @Test fun `signed out offers Google sign-in`() {
        show(CloudState(available = true))
        compose.onNodeWithText("Sign in with Google").assertIsDisplayed()
        capture("signed-out")
    }

    private fun capture(name: String, dialog: Boolean = false) {
        val bitmap = compose.runOnIdle {
            val view = if (dialog) ShadowDialog.getLatestDialog().window!!.decorView else compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}

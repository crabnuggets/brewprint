package com.brewprint.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brewprint.cloud.CloudState
import com.brewprint.cloud.CloudSync
import com.brewprint.cloud.DraftNotifications
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch

/**
 * Settings → Account: Google sign-in, which backs up and syncs the library and brews, and, while signed in,
 * notifications for Claude's drafts.
 */
@Composable
internal fun AccountSettings(draftNotifications: Boolean, onDraftNotifications: (Boolean) -> Unit, onError: (String) -> Unit) {
    val cloud by CloudSync.state.collectAsStateWithLifecycle()
    if (!cloud.available) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(DraftNotifications.blocked(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { blocked = DraftNotifications.blocked(context) }
    AccountPanel(
        cloud,
        busy,
        onSignIn = { busy = true; scope.launch { CloudSync.signIn(context)?.let(onError); busy = false } },
        onSignOut = { busy = true; scope.launch { CloudSync.signOut(context)?.let(onError); busy = false } },
        draftNotifications = draftNotifications,
        onDraftNotifications = onDraftNotifications,
        blocked = blocked,
        onOpenSettings = { openNotificationSettings(context) },
    )
}

@Composable
internal fun AccountPanel(
    cloud: CloudState,
    busy: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    draftNotifications: Boolean = false,
    onDraftNotifications: ((Boolean) -> Unit)? = null,
    blocked: Boolean = false,
    onOpenSettings: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    var confirming by remember { mutableStateOf(false) }
    SectionTitle("Account")
    Panel {
        val email = cloud.email
        if (email == null) {
            Text("Sign in to keep your beans, grinders, recipes and brews in your Google account, so they come back after a reinstall or on a new phone.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            SoftButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = onSignIn) { Text("Sign in with Google") }
        } else {
            Text(email, style = MaterialTheme.typography.titleMedium)
            val synced = cloud.syncedAt
            val status = when {
                cloud.syncing -> "Syncing…"
                cloud.failed -> "Couldn't sync. It will try again."
                synced != null && System.currentTimeMillis() - synced < DateUtils.MINUTE_IN_MILLIS -> "Synced just now"
                synced != null -> "Synced " + DateUtils.getRelativeTimeSpanString(synced, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                else -> "Waiting to sync"
            }
            Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            // Drafts need an account, so their notifications are offered only while signed in.
            if (onDraftNotifications != null) {
                SwitchRow("Drafts from Claude", "Notifies you when Claude drafts something to review", draftNotifications, inset = 0.dp, onChange = onDraftNotifications)
                if (draftNotifications && blocked) AndroidNotice("Blocked by Android", "Notifications for Brewprint are turned off. Drafts still wait in the app.", inset = 0.dp, onOpen = onOpenSettings)
            }
            SoftButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = { confirming = true }) { Text("Sign out") }
        }
    }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("Sign out?") },
        text = { Text("Your beans, grinders, recipes and brews will be removed from this phone. They stay in your Google account.") },
        confirmButton = { SoftTextButton(onClick = { confirming = false; onSignOut() }) { Text("Sign out") } },
        dismissButton = { SoftTextButton(onClick = { confirming = false }) { Text("Cancel") } },
    )
}

package com.brewprint.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.brewprint.data.Appearance
import com.brewprint.data.TimerSettings
import com.brewprint.timer.BrewTimerService

internal fun notificationsAllowed(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false

@Composable
internal fun SettingsScreen(appearance: Appearance, timer: TimerSettings, onAppearance: (Appearance) -> Unit, onTimer: (TimerSettings) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var allowed by remember { mutableStateOf(notificationsAllowed(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = notificationsAllowed(context) }
    val prompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = notificationsAllowed(context)
        // A timer may already be running; show it now that Android allows it.
        BrewTimerService.sync(context)
    }
    BackHandler { onClose() }
    Page("Settings", onClose) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Appearance.entries.forEach { option -> Surface(onClick = { onAppearance(option) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, colors.outlineVariant)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = option == appearance, onClick = { onAppearance(option) })
                Text(option.name.lowercase().replaceFirstChar { it.uppercase() }, Modifier.padding(start = 12.dp))
            }
        } }
        Text("Brew timer", style = MaterialTheme.typography.titleMedium)
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant)) {
            Column(Modifier.padding(vertical = 4.dp)) {
                SwitchRow("Live notification", "Shows the running brew on your lock screen", timer.liveNotification) { on ->
                    onTimer(timer.copy(liveNotification = on))
                    // The only place the app asks for notification permission.
                    if (on && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        prompt.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                if (timer.liveNotification && !allowed) Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.NotificationsOff, null, Modifier.size(20.dp))
                        Text("Blocked by Android", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("Notifications for Brewprint are turned off. The timer still runs in the app.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Open Android settings") }
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = colors.outlineVariant)
                SwitchRow("Vibrate at each step", null, timer.vibrate) { onTimer(timer.copy(vibrate = it)) }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = colors.outlineVariant)
                SwitchRow("Keep screen on while brewing", null, timer.keepScreenOn) { onTimer(timer.copy(keepScreenOn = it)) }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

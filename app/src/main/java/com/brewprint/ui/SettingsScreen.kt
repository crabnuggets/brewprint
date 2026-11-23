package com.brewprint.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.brewprint.data.Appearance
import com.brewprint.data.Experiments
import com.brewprint.data.Theme
import com.brewprint.data.TimerSettings
import com.brewprint.timer.BrewTimerService

internal fun notificationsAllowed(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false

/** Android 16 lets the user turn off Live Updates for one app; the brew then shows as a plain notification. */
internal fun liveUpdatesAllowed(context: Context): Boolean =
    Build.VERSION.SDK_INT < 36 || context.getSystemService(NotificationManager::class.java)?.canPostPromotedNotifications() != false

private fun openNotificationSettings(context: Context, action: String = Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
    val intent = Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {
        // Some phones have no page for one setting; the app's notification page holds it too.
        if (action != Settings.ACTION_APP_NOTIFICATION_SETTINGS) openNotificationSettings(context)
    }
}

@Composable
internal fun SettingsScreen(appearance: Appearance, timer: TimerSettings, onAppearance: (Appearance) -> Unit, onTimer: (TimerSettings) -> Unit, onClose: (() -> Unit)? = null, account: @Composable () -> Unit = {}, experiments: Experiments = Experiments(), onExperiments: (Experiments) -> Unit = {}, liveUpdatesCheck: (Context) -> Boolean = ::liveUpdatesAllowed, scale: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var allowed by remember { mutableStateOf(notificationsAllowed(context)) }
    var liveUpdates by remember { mutableStateOf(liveUpdatesCheck(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = notificationsAllowed(context); liveUpdates = liveUpdatesCheck(context) }
    val prompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = notificationsAllowed(context)
        // A timer may already be running; show it now that Android allows it.
        BrewTimerService.sync(context)
    }
    // As a bottom navigation destination Settings has no Back arrow; the app's Back returns to Brew.
    if (onClose != null) BackHandler(LocalScreenActive.current) { onClose() }
    Page("Settings", onClose) {
        account()
        SectionTitle("Appearance")
        Appearance.entries.forEach { option -> Choice(option.name.lowercase().replaceFirstChar { it.uppercase() }, null, option == appearance) { onAppearance(option) } }
        SectionTitle("Brew timer")
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant)) {
            Column(Modifier.padding(vertical = 4.dp)) {
                SwitchRow("Live notification", "Shows the running brew on your lock screen", timer.liveNotification) { on ->
                    onTimer(timer.copy(liveNotification = on))
                    // The only place the app asks for notification permission.
                    if (on && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        prompt.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                if (timer.liveNotification && !allowed) {
                    AndroidNotice("Blocked by Android", "Notifications for Brewprint are turned off. The timer still runs in the app.") { openNotificationSettings(context) }
                } else if (timer.liveNotification && !liveUpdates) {
                    AndroidNotice("Live Updates are off", "The brew shows as a normal notification, without the timer in the status bar. Turn on Live Updates for Brewprint to see it there.") {
                        openNotificationSettings(context, Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = colors.outlineVariant)
                SwitchRow("Vibrate at each step", null, timer.vibrate) { onTimer(timer.copy(vibrate = it)) }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = colors.outlineVariant)
                SwitchRow("Sound at each step", "A short tone, for a phone propped on the counter", timer.sound) { onTimer(timer.copy(sound = it)) }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = colors.outlineVariant)
                SwitchRow("Keep screen on while brewing", null, timer.keepScreenOn) { onTimer(timer.copy(keepScreenOn = it)) }
            }
        }
        scale()
        SectionTitle("Experimental")
        Text("Features still being tried out. They may change or go away.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text("Theme", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Choice("Monochrome", null, experiments.theme == Theme.MONOCHROME) { onExperiments(experiments.copy(theme = Theme.MONOCHROME)) }
        Choice("Blueprint", "Graphite on gridded paper, with a red pencil for targets", experiments.theme == Theme.BLUEPRINT) { onExperiments(experiments.copy(theme = Theme.BLUEPRINT)) }
    }
}

@Composable
private fun Choice(label: String, detail: String?, selected: Boolean, onSelect: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect), shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label)
                if (detail != null) Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
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

/** A setting Android holds for the app, with a way to change it. */
@Composable
private fun AndroidNotice(title: String, text: String, onOpen: () -> Unit) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.NotificationsOff, null, Modifier.size(20.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        SoftButton(shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp), onClick = onOpen) { Text("Open Android settings") }
    }
}

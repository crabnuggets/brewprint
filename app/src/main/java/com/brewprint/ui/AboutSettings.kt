package com.brewprint.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.brewprint.BuildConfig
import com.brewprint.R
import com.brewprint.data.BrewRepository
import com.brewprint.data.exportFileName
import com.brewprint.data.readAppLog
import com.brewprint.data.timerRunning
import com.brewprint.data.writeExport
import com.brewprint.update.UpdateState
import com.brewprint.update.Updater
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val APP_VERSION = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
private const val REPORT_PROBLEM_URL = "https://github.com/crabnuggets/brewprint/issues/new"

private fun aboutText(): String = """
    Brewprint $APP_VERSION
    Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
    Device ${Build.MANUFACTURER} ${Build.MODEL}
    Exported ${OffsetDateTime.now()}
""".trimIndent() + "\n"

private fun installAllowed(context: Context) = context.packageManager.canRequestPackageInstalls()

/** Settings → Version and Support: the app's version and its updates, then a way to report a problem with an export of the app's data and log. */
@Composable
internal fun AboutSettings(repository: BrewRepository, onError: (String) -> Unit, readLog: () -> String = ::readAppLog, updates: StateFlow<UpdateState> = Updater.state) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by repository.state.collectAsState()
    val update by updates.collectAsState()
    var allowed by remember { mutableStateOf(installAllowed(context)) }
    val brewing = { repository.state.value.timerRunning(System.currentTimeMillis()) }
    // "Update" sent the user to Android's settings to allow installs; if they come back allowed, carry on.
    var waitingForAllow by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        allowed = installAllowed(context)
        if (waitingForAllow) { waitingForAllow = false; if (allowed) Updater.update(context, brewing) }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val running = state.timerRunning(now)
    // A timer left alone stops by itself; look again each second while one runs, so "Update" comes back.
    LaunchedEffect(running) { while (running) { delay(1_000); now = System.currentTimeMillis() } }
    VersionPanel(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, update, state.betaReleases, brewing = running, installAllowed = allowed,
        onCheck = { Updater.check(state.betaReleases) },
        onUpdate = { if (installAllowed(context)) Updater.update(context, brewing) else { waitingForAllow = true; openInstallSettings(context) } },
        onNotes = { openPage(context, it) },
        onBetas = { on -> repository.setBetaReleases(on).onSuccess { Updater.check(on) }.onFailure { onError(it.message ?: "Couldn't save settings.") } },
    )

    var busy by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(remember { ActivityResultContracts.CreateDocument("application/zip") }) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val output = requireNotNull(context.contentResolver.openOutputStream(uri))
                    output.use { writeExport(it, aboutText(), repository.exportData(), readLog()) }
                }
            }
            busy = false
            exported = result.isSuccess
            result.onFailure { onError("Couldn't export your data. Try again, or save it somewhere else.") }
        }
    }
    SupportPanel(busy, exported, onReport = { openPage(context, REPORT_PROBLEM_URL) }) {
        exported = false
        save.launch(exportFileName(LocalDate.now()))
    }
}

private fun openPage(context: Context, url: String) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) { }
}

private fun openInstallSettings(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { }
}

/** Settings → Version: which Brewprint this is, whether a newer one is out, and whether betas count. */
@Composable
internal fun VersionPanel(
    version: String,
    build: Int,
    update: UpdateState,
    betas: Boolean,
    brewing: Boolean,
    installAllowed: Boolean,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
    onNotes: (String) -> Unit,
    onBetas: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    SectionTitle("Version")
    SettingsCard {
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // The launcher mark, in the app's own ink; drawn larger than the tile, since the icon art leaves room for launcher masks.
            Box(Modifier.size(48.dp).clip(MaterialTheme.shapes.small).background(colors.onSurface), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(76.dp), tint = colors.surface)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Brewprint $version", style = MaterialTheme.typography.titleMedium.tabular())
                Text("Build $build", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium.tabular())
            }
        }
        RowDivider()
        UpdateStatus(update, brewing, installAllowed, onCheck, onUpdate, onNotes)
        RowDivider()
        SwitchRow("Beta releases", "Try new features early. Betas may have problems.", betas, onBetas)
    }
}

@Composable
private fun UpdateStatus(update: UpdateState, brewing: Boolean, installAllowed: Boolean, onCheck: () -> Unit, onUpdate: () -> Unit, onNotes: (String) -> Unit) {
    val check: @Composable () -> Unit = { IconAction(Icons.Outlined.Refresh, "Check for updates", onClick = onCheck) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (update) {
            UpdateState.Unavailable -> StatusLine(Icons.Outlined.Info, "Updates are off in debug builds", "Install a release from GitHub to get updates here.")
            UpdateState.Checking -> StatusLine(null, "Checking for updates…")
            UpdateState.UpToDate -> StatusLine(Icons.Outlined.CheckCircle, "Up to date", action = check)
            UpdateState.CheckFailed -> StatusLine(Icons.Outlined.CloudOff, "Couldn't check for updates", "Check your connection and try again.", action = check)
            is UpdateState.Available -> {
                val release = update.release
                StatusLine(Icons.Outlined.SystemUpdate, "Brewprint ${release.version} is available", if (release.beta) "Beta release" else null)
                when (update.step) {
                    UpdateState.Step.READY -> {
                        val note = when {
                            update.problem != null -> update.problem
                            brewing -> "Finish your brew first. Brewprint closes while it updates."
                            !installAllowed -> "Android will ask you to allow Brewprint to install updates."
                            else -> "Brewprint closes while it updates."
                        }
                        Text(note, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = if (update.problem != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        ActionRow {
                            SoftButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { onNotes(release.pageUrl) }) { Text("What's new") }
                            PrimaryButton(enabled = !brewing, modifier = Modifier.heightIn(min = 48.dp), onClick = onUpdate) { Text("Update") }
                        }
                    }
                    UpdateState.Step.DOWNLOADING -> {
                        LinearProgressIndicator(progress = { update.progress }, modifier = Modifier.fillMaxWidth())
                        Text("Downloading… ${(update.progress * 100).roundToInt()}%", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium.tabular())
                    }
                    UpdateState.Step.INSTALLING -> Text("Installing… Brewprint closes while it updates.", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** One line of update status: an icon, or a spinner while [icon] is null, beside a title and an optional detail. */
@Composable
private fun StatusLine(icon: ImageVector?, title: String, detail: String? = null, action: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (icon == null) CircularProgressIndicator(Modifier.size(20.dp), color = colors.onSurfaceVariant, strokeWidth = 2.dp)
            else Icon(icon, null, tint = colors.onSurface)
        }
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        action?.invoke()
    }
}

/** Settings → Support: an export of the app's data and log, and a way to report a problem. */
@Composable
internal fun SupportPanel(busy: Boolean, exported: Boolean, onReport: () -> Unit, onExport: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    SectionTitle("Support")
    Panel {
        Text("Export saves your beans, grinders, recipes, brews and settings, with the app's recent log, as one file. Attach it to a problem report to help find what went wrong.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        SoftButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = onExport) { Text("Export data") }
        if (exported) Text("Export saved", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        SoftButton(modifier = Modifier.heightIn(min = 48.dp), onClick = onReport) { Text("Report a problem") }
    }
}

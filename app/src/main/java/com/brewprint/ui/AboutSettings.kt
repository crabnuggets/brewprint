package com.brewprint.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import com.brewprint.BuildConfig
import com.brewprint.data.BrewRepository
import com.brewprint.data.exportFileName
import com.brewprint.data.readAppLog
import com.brewprint.data.writeExport
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
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

/** Settings → About and support: the app's version, a way to report a problem, and an export of the app's data and log. */
@Composable
internal fun AboutSettings(repository: BrewRepository, onError: (String) -> Unit, readLog: () -> String = ::readAppLog) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    AboutPanel(APP_VERSION, busy, exported, onReport = { openReport(context) }) {
        exported = false
        save.launch(exportFileName(LocalDate.now()))
    }
}

private fun openReport(context: Context) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPORT_PROBLEM_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) { }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AboutPanel(version: String, busy: Boolean, exported: Boolean, onReport: () -> Unit, onExport: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    SectionTitle("About and support")
    Panel {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Version", style = MaterialTheme.typography.titleMedium)
            Text(version, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        }
        Text("Export saves your beans, grinders, recipes, brews and settings, with the app's recent log, as one file. Attach it to a problem report to help find what went wrong.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        SoftButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = onExport) { Text("Export data") }
        if (exported) Text("Export saved", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        SoftButton(modifier = Modifier.heightIn(min = 48.dp), onClick = onReport) { Text("Report a problem") }
    }
}

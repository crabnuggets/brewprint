@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.brewprint.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Scale
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.brewprint.data.*
import com.brewprint.scale.ScaleLink
import com.brewprint.scale.ZERO_GRAMS
import kotlin.math.abs
import java.util.regex.Pattern

/** The live scale and its one command. Tests replace it. */
class ScaleUi(val live: ScaleLive = ScaleLive(), val statuses: Map<String, ScaleStatus> = emptyMap(), val tare: () -> Unit = {})

val LocalScale = compositionLocalOf { ScaleUi() }

/** Grams to one decimal, the same in every locale. */
internal fun tenths(value: Double?): String = value?.let { java.math.BigDecimal.valueOf(it).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString() } ?: "–"

/**
 * What to tell the user when pairing fails, or null to stay quiet. Declining or cancelling is the user's own choice;
 * only a search that found nothing suggests turning the scale on. [resultCode] is null when Android gives only text.
 * With no reason at all (an empty picker result), a failure is not assumed.
 */
internal fun pairingFailure(resultCode: Int?, error: CharSequence?): String? {
    val text = error?.toString().orEmpty().lowercase()
    return when {
        resultCode == CompanionDeviceManager.RESULT_USER_REJECTED || resultCode == CompanionDeviceManager.RESULT_CANCELED -> null
        resultCode == CompanionDeviceManager.RESULT_DISCOVERY_TIMEOUT -> "No compatible scale found. Turn it on and try again."
        resultCode != null -> "Couldn't pair the scale. Try again."
        error == null -> null
        "cancel" in text || "reject" in text -> null
        "timeout" in text || "not found" in text || "no device" in text -> "No compatible scale found. Turn it on and try again."
        else -> "Couldn't pair the scale. Try again."
    }
}

/** Opens Android's companion device picker, then asks for Nearby devices once a scale is chosen. */
@Composable
internal fun rememberScalePairing(onFailed: (String) -> Unit = {}, onPaired: (PairedScale) -> Unit): () -> Unit {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ScaleLink.sync(context) }
    // The picker's result and the association callback can both report one failure; tell the user once per attempt.
    val reported = remember { booleanArrayOf(false) }
    val fail = { message: String? -> if (message != null && !reported[0]) { reported[0] = true; onFailed(message) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val scale = result.data?.let(::chosenScale)
        if (scale == null) {
            // The picker closes with Android's companion result code: declined or cancelled stays quiet.
            fail(pairingFailure(result.resultCode.takeUnless { it == android.app.Activity.RESULT_OK }, null))
            return@rememberLauncherForActivityResult
        }
        onPaired(scale)
        if (!ScaleLink.allowed(context)) permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }
    return {
        reported[0] = false
        // Only named TIMEMORE scales, so the picker is not a list of every nearby device and MAC address.
        val scales = BluetoothLeDeviceFilter.Builder().setNamePattern(Pattern.compile(ScaleProtocol.NAME_PATTERN)).build()
        val request = AssociationRequest.Builder().addDeviceFilter(scales).setSingleDevice(false).build()
        context.getSystemService(CompanionDeviceManager::class.java)?.associate(request, object : CompanionDeviceManager.Callback() {
            @Deprecated("Also receives onAssociationPending from Android 13")
            override fun onDeviceFound(chooser: IntentSender) = picker.launch(IntentSenderRequest.Builder(chooser).build())
            // Newer Android versions give the reason as a code; earlier ones give only text.
            override fun onFailure(resultCode: Int, error: CharSequence?) { fail(pairingFailure(resultCode, error)) }
            override fun onFailure(error: CharSequence?) { fail(pairingFailure(null, error)) }
        }, null)
    }
}

private fun chosenScale(data: Intent): PairedScale? {
    if (Build.VERSION.SDK_INT >= 33) {
        val info = data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
        val address = info?.deviceMacAddress?.toString()?.uppercase()
        if (address != null) return PairedScale(address, info.displayName?.toString()?.takeIf { it.isNotBlank() } ?: "Coffee scale")
    }
    @Suppress("DEPRECATION") val found = data.getParcelableExtra<Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)
    return when (found) {
        is ScanResult -> PairedScale(found.device.address, found.scanRecord?.deviceName?.takeIf { it.isNotBlank() } ?: "Coffee scale")
        is BluetoothDevice -> PairedScale(found.address, "Coffee scale")
        else -> null
    }
}

/** Ends the companion association, so Android forgets the scale too. */
@Suppress("DEPRECATION")
internal fun forgetScale(context: Context, scale: PairedScale) {
    val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return
    runCatching {
        if (Build.VERSION.SDK_INT >= 33) manager.myAssociations.filter { it.deviceMacAddress?.toString().equals(scale.address, ignoreCase = true) }.forEach { manager.disassociate(it.id) }
        else manager.disassociate(scale.address)
    }
}

@Composable
internal fun ScaleOffer(onPair: () -> Unit, onDismiss: () -> Unit) {
    Panel {
        Text("Brew with a scale?", style = MaterialTheme.typography.titleMedium)
        Text("Starts the timer at your first pour and moves on when each pour reaches its target. You can pair one later in Settings.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftTextButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Not now") }
            SoftButton(onClick = onPair, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("Pair a scale") }
        }
    }
}

@Composable
internal fun ScaleChip(name: String, connected: Boolean, reading: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    // "Off" only for a dropped link; a link that is up but sends nothing says "No reading", like the card.
    val status = if (!connected) "Off" else if (!reading) "No reading" else null
    // Status only: quiet text beside the title, with no fill or border to suggest a button. A long name gives way to the title.
    Row(Modifier.widthIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * .42f).dp).padding(horizontal = 8.dp, vertical = 8.dp).semantics(mergeDescendants = true) { contentDescription = "$name, ${if (!connected) "not connected" else if (!reading) "no reading" else "connected"}" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Outlined.Scale, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
        Text(if (status == null) name else "$name · $status", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** The scale's line on Brew: its name and live reading, or "Off". It opens the Scale screen. */
@Composable
internal fun ScaleHomeCard(scale: PairedScale, live: ScaleLive, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val grams = live.grams?.takeIf { live.connected && !live.silent }
    val status = if (!live.connected) "Off" else if (grams == null) "No reading" else null
    val spoken = "Scale, ${scale.name}, " + (status?.lowercase() ?: "${tenths(grams)} grams")
    Surface(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClickLabel = "Open", role = Role.Button, onClick = onClick).clearAndSetSemantics { contentDescription = spoken },
        shape = MaterialTheme.shapes.medium, color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Row(Modifier.heightIn(min = 52.dp).padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Scale, null, Modifier.size(20.dp))
            Text("Scale", style = MaterialTheme.typography.titleMedium)
            Text(scale.name, Modifier.weight(1f), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (status != null) Text(status, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            else Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tenths(grams), style = MaterialTheme.typography.titleMedium.tabular())
                Text("g", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = colors.onSurfaceVariant)
        }
    }
}

/**
 * The scale by itself, for weighing without a brew: one large reading and "Tare", as often as needed. Nothing here is
 * saved. While a brew is using the scale ([brewing]) a tare would move its targets, so the screen offers the brew instead.
 */
@Composable
internal fun ScaleScreen(scale: PairedScale, live: ScaleLive, brewing: Boolean, onTare: () -> Unit, onResumeBrew: () -> Unit, allowedCheck: (Context) -> Boolean = ScaleLink::allowed, chip: (@Composable (connected: Boolean, reading: Boolean) -> Unit)? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var allowed by remember { mutableStateOf(allowedCheck(context)) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { allowed = allowedCheck(context); ScaleLink.sync(context) }
    androidx.activity.compose.BackHandler(LocalScreenActive.current) { onClose() }
    KeepScreenOn(true)
    val grams = live.grams?.takeIf { allowed && live.connected && !live.silent }
    Page("Scale", onClose, actions = {
        if (chip != null) chip(allowed && live.connected, grams != null) else ScaleChip(scale.name, allowed && live.connected, reading = grams != null)
        Spacer(Modifier.width(8.dp))
    }, footer = {
        when {
            !allowed -> SecondaryAction("Open Android settings") { openAppSettings(context) }
            brewing -> PrimaryAction("Resume brew", onClick = onResumeBrew)
            else -> PrimaryAction("Tare", enabled = grams != null && !live.taring, onClick = onTare)
        }
    }) {
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        // Centered in the space between the header and the footer on a usual phone; it scrolls where that is too short.
        Column(Modifier.fillMaxWidth().heightIn(min = (configuration.screenHeightDp - 280).coerceAtLeast(0).dp).padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            @Composable fun message(title: String, text: String?, busy: Boolean = false) = Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onSurface, trackColor = colors.outlineVariant)
                    Text(title, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                if (text != null) Text(text, Modifier.widthIn(max = 300.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            @Composable fun tile(icon: androidx.compose.ui.graphics.vector.ImageVector) {
                Icon(icon, null, Modifier.size(64.dp).background(colors.soft, MaterialTheme.shapes.medium).padding(18.dp))
                Spacer(Modifier.height(20.dp))
            }
            if (grams != null) BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // Already far larger than body text, so it ignores the text scale and is sized to fit its digits and
                // the unit on one line: a tabular digit is about 0.58 of the font size wide.
                val fit = (maxWidth - 44.dp) / (tenths(grams).length * .58f)
                val size = with(androidx.compose.ui.platform.LocalDensity.current) { minOf(112.dp, fit).toSp() }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.clearAndSetSemantics { contentDescription = "On scale, ${tenths(grams)} grams" }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("On scale", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tenths(grams), modifier = Modifier.alignByBaseline(), color = if (live.taring) colors.onSurfaceVariant else colors.onSurface, maxLines = 1, softWrap = false,
                            style = MaterialTheme.typography.headlineLarge.copy(fontSize = size, lineHeight = size * 1.07f, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.03f).em).tabular())
                        Text("g", Modifier.alignByBaseline(), color = colors.onSurfaceVariant, maxLines = 1, softWrap = false, style = MaterialTheme.typography.headlineMedium.copy(fontSize = size / 4))
                    }
                }
                Box(Modifier.heightIn(min = 80.dp).padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
                    when {
                        brewing -> message("Brew in progress", "No tare here, so the brew's targets stay right.")
                        live.taring -> message("Taring", null, busy = true)
                        live.tareFailed -> message("The scale didn't tare", "Tap Tare again, or press the scale's tare button.")
                    }
                }
                }
            } else when {
                !allowed -> { tile(Icons.Outlined.BluetoothDisabled); message("Blocked by Android", "Nearby devices is turned off for Brewprint.") }
                !live.connected -> { tile(Icons.Outlined.Scale); message("Turn the scale on", "It connects by itself.") }
                else -> { tile(Icons.Outlined.Scale); message("No scale reading", "Check that the scale is on, nearby, and not overloaded. Its reading comes back here.") }
            }
        }
    }
}

private fun openAppSettings(context: Context) =
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

@Composable
private fun Reading(label: String, value: Double?, plan: Double? = null, announce: Boolean = false, dim: Boolean = false, busy: Boolean = false, onType: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val note = plan?.let { "plan ${formatAmount(it)} g" }
    val spoken = "$label, " + (value?.let { "${tenths(it)} grams" } ?: "no reading") + (plan?.let { ", plan ${formatAmount(it)} grams" } ?: "")
    // [onType] lets the weight be typed instead, for coffee the scale can't weigh.
    val tap = if (onType != null) Modifier.clickable(onClickLabel = "Type the coffee weight", role = Role.Button, onClick = onType) else Modifier
    // [announce] makes a steady reading a polite live region, so TalkBack says when it becomes ready; a moving one stays quiet.
    Column(tap.clearAndSetSemantics { contentDescription = spoken; if (announce) liveRegion = LiveRegionMode.Polite }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.onSurface, trackColor = colors.outlineVariant)
            Text(label, color = if (busy) colors.onSurface else colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // [dim] marks a reading about to change, such as the weight a tare is taking away.
            Text(tenths(value), color = if (dim) colors.onSurfaceVariant else colors.onSurface, style = MaterialTheme.typography.headlineLarge.copy(fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold))
            Text("g", Modifier.padding(bottom = 7.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
            if (onType != null) Icon(Icons.Outlined.Edit, null, Modifier.padding(bottom = 10.dp).size(18.dp), tint = colors.onSurfaceVariant)
            if (note != null) Text(note, Modifier.padding(bottom = 8.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Off zero with no tare pending: nothing changes until the user tares. A reading already near 0 g is settling
 * toward a steady zero, so it is not asked for again.
 */
internal fun ScaleLive.needsTare(): Boolean = !zeroed && !taring && (grams?.let { abs(it) > ZERO_GRAMS } ?: true)

/** The scale card before the first pour: tare the brewer, weigh the coffee, then wait at 0 g for the pour. */
@Composable
internal fun ScalePrep(step: ScaleStep, live: ScaleLive, plannedDose: Double, ratio: Double, weighedDose: Double?, reconnected: Boolean, ice: Double? = null, onTare: () -> Unit, onUse: (Double) -> Unit, onSkip: () -> Unit, onWithout: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // The coffee weight being typed, while its dialog is open.
    var typing by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(step) { if (step != ScaleStep.COFFEE) typing = null }
    // "Tare" was tapped on the coffee step, and the scale has not yet read about 0 g.
    var retare by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(live.taring, step) { if (!live.taring || step != ScaleStep.COFFEE) retare = false }
    Panel {
        // With no readings, any weight would be stale and waiting would not help, so the card says so and offers the clock.
        if (live.silent) {
            Text("No scale reading", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleMedium)
            Text("Check that the scale is on, nearby, and not overloaded. Its reading comes back here.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        // Only the scale's own zero makes its display match the targets, so a tare it ignored leads the card and Tare is offered again.
        } else if (live.tareFailed && step != ScaleStep.BREWER) {
            Text("The scale didn't tare", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleMedium)
            Reading("On scale", live.grams)
            Text("Tap Tare again, or press the scale's tare button.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            PrimaryButton(onClick = onTare, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Tare") }
        } else when (step) {
            ScaleStep.BREWER -> {
                // An iced recipe's ice goes in the server before the brewer sits on it; the scale is tared with both.
                if (ice != null) {
                    Text("Put ${formatAmount(ice)} g ice in the server first", style = MaterialTheme.typography.titleMedium)
                    HorizontalDivider(color = colors.outlineVariant)
                }
                Text("Put the brewer on the scale", style = MaterialTheme.typography.titleMedium)
                // Back here after the scale dropped mid-weighing: its tare is unknown, and grounds may already be in the brewer.
                if (reconnected) Text("Tare the brewer again. Take the coffee out first if you've added it.", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.onSurface, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Reading("On scale", live.grams) }
                    PrimaryButton(onClick = onTare, modifier = Modifier.heightIn(min = 48.dp)) { Text("Tare") }
                }
            }
            // Until the scale reads about 0 g, coffee must wait: grounds added now would be tared away or taken as the dose.
            ScaleStep.COFFEE -> if (live.taring && !retare) Row(Modifier.heightIn(min = 48.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onSurface, trackColor = colors.outlineVariant)
                Text("Taring the brewer", style = MaterialTheme.typography.titleMedium)
            } else {
                Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                    Text("Brewer tared", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider(color = colors.outlineVariant)
                // A reading that still counts as zero is not coffee.
                // While a tare is pending, the reading is about to drop to 0 g, so it is no dose to use or keep.
                val coffee = live.grams?.takeIf { live.steady && it > ZERO_GRAMS && !live.taring }?.let(::roundedAmount)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Coffee added before the tare reads as 0 g, so tapping the weight lets it be typed instead.
                    Box(Modifier.weight(1f)) {
                        Reading(if (live.taring) "Taring" else if (coffee != null) "Dose ready" else "Add your coffee", live.grams, plannedDose, announce = coffee != null || live.taring, dim = live.taring, busy = live.taring,
                            // No weight is typed against a zero the scale has not reached yet.
                            onType = if (live.taring) null else ({ typing = formatAmount(coffee ?: plannedDose) }))
                    }
                    // The scale can be zeroed again at will, for a rinsed filter or a dosing cup, before the dose is chosen.
                    SoftButton(onClick = { retare = true; onTare() }, enabled = !live.taring, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("Tare") }
                }
                // The weighed dose rescales this brew's pours; say so before the choice, not after.
                if (coffee != null && coffee != plannedDose) Text("Using ${formatAmount(coffee)} g adjusts the pours to keep 1:${formatRatio(ratio)}.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftTextButton(onClick = onSkip, enabled = coffee != null, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Keep ${formatAmount(plannedDose)} g") }
                    PrimaryButton(onClick = { coffee?.let(onUse) }, enabled = coffee != null, modifier = Modifier.weight(1.4f).heightIn(min = 48.dp)) {
                        Text(if (coffee != null) "Use ${formatAmount(coffee)} g" else "Use dose")
                    }
                }
            }
            ScaleStep.ARMED -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // The weighed dose belongs with a tared scale; off zero, the reading is what matters.
                val label = if (live.zeroed) "On scale, tared" + weighedDose?.let { " · Weighed ${formatAmount(it)} g" }.orEmpty() else "On scale"
                Box(Modifier.weight(1f)) { Reading(label, live.grams) }
                // Off zero with no tare pending, nothing changes until the user tares, so Tare becomes the step's action.
                if (live.needsTare()) PrimaryButton(onClick = onTare, modifier = Modifier.heightIn(min = 48.dp)) { Text("Tare") }
                else SoftButton(onClick = onTare, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("Tare") }
            }
        }
        SoftTextButton(onClick = onWithout, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Brew without the scale") }
    }
    typing?.let { text ->
        val weight = positiveDecimal(text)
        AlertDialog(onDismissRequest = { typing = null }, title = { Text("Coffee weight") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("For coffee the scale can't weigh, such as grounds added before the tare.", style = MaterialTheme.typography.bodyMedium)
                LibraryField(text, { typing = it }, "Coffee, g", KeyboardType.Decimal, error = if (weight == null) "Enter a positive amount with up to one decimal place." else null, required = true)
                if (weight != null && weight != plannedDose) Text("Using ${formatAmount(weight)} g adjusts the pours to keep 1:${formatRatio(ratio)}.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }, confirmButton = { SoftTextButton(enabled = weight != null, onClick = { typing = null; weight?.let(onUse) }) { Text("Use weight") } },
            dismissButton = { SoftTextButton(onClick = { typing = null }) { Text("Cancel") } })
    }
}

/**
 * The sheet's footer once the scale is armed. The status says what the sheet is waiting for. Only a scale at 0 g
 * offers "Start timer now"; until then, "Brew without the scale" on the card is the way to a clock-only start.
 */
@Composable
internal fun ArmedFooter(live: ScaleLive, onStartNow: () -> Unit, onFinishWithout: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // After a failed tare the card leads with the retry, so the footer adds no status of its own.
        if (!live.tareFailed && !live.silent) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            val waiting = !live.needsTare()
            if (waiting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onSurface, trackColor = colors.outlineVariant)
                Spacer(Modifier.width(10.dp))
            }
            // The instruction is styled as text, not like the button beneath it; the action is Tare on the card.
            Text(when {
                live.zeroed -> "Waiting for the first pour"
                live.taring -> "Waiting for the scale to read 0 g"
                waiting -> "Waiting for the scale to settle"
                else -> "Tare the scale to 0 g"
            }, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = if (waiting) colors.onSurface else colors.onSurfaceVariant,
                style = if (waiting) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
        }
        ActionRow {
            if (live.zeroed) SoftTextButton(onClick = onStartNow, modifier = Modifier.heightIn(min = 48.dp)) { Text("Start timer now") }
            SoftTextButton(onClick = onFinishWithout, modifier = Modifier.heightIn(min = 48.dp)) { Text("Finish without timing") }
        }
    }
}

@Composable
internal fun ScaleBanner(following: String) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, shape = MaterialTheme.shapes.small, color = colors.surfaceVariant, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(following, style = MaterialTheme.typography.titleMedium)
            Text(if (following == "Scale reading changed") "Following the clock until Finish brew." else "Following the clock until it is back.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Ratio so far against where the plan ends, over the water curve with the target to pour to now and the total labelled. */
@Composable
internal fun LiveCurveCard(view: TimerNow, live: ScaleLive) {
    val colors = MaterialTheme.colorScheme
    // During a pour, its own target; during a wait, the next pour's: the same number as "Pour to" or "Then pour to".
    val target = view.schedule.getOrNull(if (view.phase == TimerPhase.POUR) view.pulse else view.pulse + 1)?.cumulativeWater
    Panel {
        val ratio = live.grams?.takeIf { live.following == null }?.let { "1:" + formatRatio(it / view.recipe.dose) } ?: "–"
        Text(androidx.compose.ui.text.buildAnnotatedString {
            append("Ratio ")
            withStyle(androidx.compose.ui.text.SpanStyle(color = colors.onSurface, fontWeight = FontWeight.SemiBold)) { append(ratio) }
            append(" → 1:${formatRatio(view.recipe.computedRatio)}")
        }, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        // Read on focus, not announced: the step changes are announced by the focus card.
        // While the sheet follows the clock, the last reading is not current and is not read out as if it were.
        val summary = (live.grams?.takeIf { live.following == null }?.let { "Water on the scale ${tenths(it)} grams" } ?: "No current scale reading") + (target?.let { ", pour to ${formatAmount(it)} grams" } ?: "") + ", total ${formatAmount(view.recipe.totalWater)} grams."
        WaterCurve(live.curve, view.poursEndSeconds + 30, view.recipe.totalWater, target = target, flows = live.flows, description = summary)
    }
}

/** What the record's chart lines mean: the actual curve, the plan, the total, and the flow band. */
@Composable
internal fun CurveLegend() {
    val colors = MaterialTheme.colorScheme
    @Composable fun item(label: String, draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(width = 16.dp, height = 8.dp)) { draw() }
        Text(label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    FlowRow(Modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item("Actual") { drawLine(colors.onSurface, Offset(0f, center.y), Offset(size.width, center.y), 2.5.dp.toPx()) }
        item("Plan") { drawLine(colors.outline, Offset(0f, center.y), Offset(size.width, center.y), 2.5.dp.toPx()) }
        item("Total") { drawLine(colors.outline, Offset(0f, center.y), Offset(size.width, center.y), 1.5.dp.toPx(), StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(.1f, 4.dp.toPx()))) }
        item("Flow") { drawRect(colors.outline.copy(alpha = .5f)) }
    }
}

/**
 * Water over brew time, one reading a second; gaps stay gaps, and readings below 0 g draw at 0. [flows], when given, is drawn as a
 * shaded band along the bottom third. [target] is the target to pour to now, dark and dashed; [total] is faint and dotted; each is
 * labelled with its grams at the right end. [plan], on the record, is drawn faintly behind the curve. Decorative: the numbers are in the text around it.
 */
@Composable
internal fun WaterCurve(curve: List<Double?>, seconds: Int, total: Double, target: Double? = null, plan: List<Pair<Int, Double>> = emptyList(), flows: List<Double?> = emptyList(), description: String = "") {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium
    val withAxis = plan.isNotEmpty()
    // The target is what to act on now: red pencil in the blueprint.
    val targetInk = targetColor()
    val targetLine = if (LocalLook.current.blueprint) targetInk else colors.onSurface.copy(alpha = .8f)
    // The last pour's target is the total, so it is drawn once, as the target.
    val shownTarget = target
    val showTotal = target == null || abs(target - total) > .05
    val top = maxOf(total, target ?: 0.0, curve.filterNotNull().maxOrNull() ?: 0.0, 1.0) * 1.06
    val flowTop = maxOf(flows.filterNotNull().maxOrNull() ?: 0.0, 8.0)
    val span = maxOf(seconds, curve.size, plan.maxOfOrNull { it.first } ?: 0, 1)
    Canvas(Modifier.fillMaxWidth().height(if (withAxis) 128.dp else 104.dp).clearAndSetSemantics { if (description.isNotEmpty()) contentDescription = description }) {
        val totalLabel = measurer.measure("${formatAmount(total)} g", labelStyle.copy(color = colors.onSurfaceVariant))
        // When this pour's target is the total, its label says so.
        val targetLabel = shownTarget?.let { measurer.measure("${formatAmount(it)} g" + if (!showTotal) " total" else "", labelStyle.copy(color = targetInk, fontWeight = FontWeight.SemiBold)) }
        val labelWidth = maxOf(totalLabel.size.width, targetLabel?.size?.width ?: 0) + 6.dp.toPx()
        val axisLabel = if (withAxis) measurer.measure("0:00", labelStyle) else null
        val plotTop = totalLabel.size.height / 2f
        val plotBottom = size.height - (axisLabel?.let { it.size.height + 4.dp.toPx() } ?: 0f)
        val width = size.width - labelWidth
        val x = { second: Int -> second.toFloat() / span * width }
        // Below 0 g the tared brewer or cup is off the scale, so the reading sits on the baseline instead of below the card.
        val y = { value: Double -> plotBottom - (value.coerceAtLeast(0.0) / top * (plotBottom - plotTop)).toFloat() }
        flows.forEachIndexed { second, flow ->
            if (flow != null && flow > 0) {
                val height = (flow / flowTop * (plotBottom - plotTop) / 3).toFloat()
                drawRect(colors.outline.copy(alpha = .5f), Offset(x(second), plotBottom - height), androidx.compose.ui.geometry.Size(maxOf(x(second + 1) - x(second), 1f), height))
            }
        }
        drawLine(colors.outlineVariant, Offset(0f, plotBottom), Offset(width, plotBottom), strokeWidth = 1.dp.toPx())
        if (plan.isNotEmpty()) {
            val planned = Path()
            plan.forEachIndexed { index, (second, water) -> if (index == 0) planned.moveTo(x(second), y(water)) else planned.lineTo(x(second), y(water)) }
            // The plan ends with its last pour, so it never covers the dotted total.
            drawPath(planned, colors.outline, style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
        }
        val gap = 6.dp.toPx()
        // When the target sits close under the total, both labels get their own row: the total's directly above the target's.
        val labelHeight = totalLabel.size.height.toFloat()
        val targetTop = shownTarget?.let { (y(it) - labelHeight / 2f).coerceAtLeast(if (showTotal && abs(y(it) - y(total)) < labelHeight) labelHeight else 0f) } ?: 0f
        val merged = shownTarget != null && abs(y(shownTarget) - y(total)) < 4.dp.toPx()
        if (showTotal) {
            // When the two labels would overlap, the total's sits just above the target's instead.
            val clash = shownTarget != null && abs(y(shownTarget) - y(total)) < totalLabel.size.height
            val totalTop = if (clash) (targetTop - totalLabel.size.height) else y(total) - totalLabel.size.height / 2f
            drawText(totalLabel, topLeft = Offset(size.width - totalLabel.size.width, totalTop))
            if (clash) drawLine(colors.outline, Offset(width - gap, y(total)), Offset(size.width - totalLabel.size.width - 2.dp.toPx(), totalTop + labelHeight / 2f), 1.5.dp.toPx(), StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(.1f, 3.dp.toPx())))
        }
        if (shownTarget != null && targetLabel != null) {
            drawLine(targetLine, Offset(0f, y(shownTarget)), Offset(width - gap - if (merged && showTotal) 32.dp.toPx() else 0f, y(shownTarget)), 1.4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
            drawText(targetLabel, topLeft = Offset(size.width - targetLabel.size.width, targetTop))
            if (showTotal && abs(y(shownTarget) - y(total)) < labelHeight) drawLine(targetLine, Offset(width - gap, y(shownTarget)), Offset(size.width - targetLabel.size.width - 2.dp.toPx(), targetTop + labelHeight / 2f), 1.dp.toPx())
        }
        val path = Path()
        var open = false
        curve.forEachIndexed { second, value ->
            if (value == null) open = false
            else { if (open) path.lineTo(x(second), y(value)) else path.moveTo(x(second), y(value)); open = true }
        }
        drawPath(path, colors.onSurface, style = Stroke((if (withAxis) 2.5 else 2.0).dp.toPx(), join = StrokeJoin.Round))
        if (showTotal) drawLine(colors.outline, Offset(if (merged) width - gap - 28.dp.toPx() else 0f, y(total)), Offset(width - gap, y(total)), 1.5.dp.toPx(), StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(.1f, 4.dp.toPx())))
        // Minute ticks thin out on long brews, so labels never touch.
        val every = listOf(1, 2, 5, 10, 15).firstOrNull { axisLabel == null || x(it * 60) >= axisLabel.size.width + 12.dp.toPx() } ?: 30
        if (axisLabel != null) for (minute in 0..span / 60 step every) {
            val label = measurer.measure("$minute:00", labelStyle.copy(color = colors.onSurfaceVariant))
            val left = (x(minute * 60) - if (minute == 0) 0f else label.size.width / 2f).coerceAtMost(width - label.size.width)
            drawText(label, topLeft = Offset(left, size.height - label.size.height))
        }
    }
}

/**
 * Water, ratio and dose in one row, each with its plan beneath: in the Finish brew review, at drawdown, and on the record.
 * Without scale data the planned values are shown and labelled as planned.
 */
@Composable
internal fun MeasuredTrio(recipe: Recipe, data: ScaleData?) {
    val colors = MaterialTheme.colorScheme
    val water = data?.water
    val cells = listOf(
        Triple("Water", if (data == null) "${formatAmount(recipe.totalWater)} g" else water?.let { "${tenths(it)} g" } ?: "—", if (data == null) "planned" else "planned ${formatAmount(recipe.totalWater)} g"),
        Triple("Ratio", if (data == null) "1:${formatRatio(recipe.computedRatio)}" else water?.let { "1:" + formatRatio(it / recipe.dose) } ?: "—", if (data == null) "planned" else "planned 1:${formatRatio(recipe.computedRatio)}"),
        Triple("Dose", "${formatAmount(recipe.dose)} g", if (data?.weighedDose != null) "weighed" else if (data == null) "planned" else "recipe"),
    )
    @Composable fun cell(cell: Triple<String, String, String>, modifier: Modifier) {
        val (label, value, note) = cell
        fun spoken(text: String) = text.replace(" g", " grams").replace("1:", "1 to ")
        val description = "$label " + (if (value == "—") "not recorded" else spoken(value)) + ", " + spoken(note)
        Column(modifier.clearAndSetSemantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            Text(note, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
    // Large text stacks the three, so no value is cut.
    if (LocalDensity.current.fontScale >= 1.15f) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { cells.forEach { cell(it, Modifier.fillMaxWidth()) } }
    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { cells.forEachIndexed { index, it -> cell(it, Modifier.weight(listOf(1.2f, 1f, .8f)[index])) } }
}

/** Offers the moment the dripper came off as the total, when it differs from the Finish tap. */
@Composable
internal fun LiftOffer(timer: BrewTimer, data: ScaleData, onUse: (Int) -> Unit) {
    val lift = data.liftSeconds ?: return
    val measured = timer.finishedAt?.let { ((it - timer.startedAt + 500) / 1000).toInt() }
    if ((timer.totalOverride ?: measured) == lift) return
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Dripper lifted at ${formatDuration(lift)}", Modifier.align(Alignment.CenterVertically), color = MaterialTheme.colorScheme.onSurfaceVariant)
        SoftTextButton(onClick = { onUse(lift) }) { Text("Use ${formatDuration(lift)}") }
    }
}

/** Settings → Coffee scale. */
/** A paired scale's status. Where the link reports none, as in tests, only the scale in use has one, from its live reading. */
internal fun ScaleUi.status(address: String, inUse: Boolean): ScaleStatus = statuses[address] ?: if (inUse) ScaleStatus(live.connected, live.battery) else ScaleStatus()

private fun ScaleStatus.line(inUse: Boolean): String = if (!on) "Not connected" else (if (inUse) "In use" else "On") + (battery?.let { " · Battery $it%" } ?: "")

/** One paired scale as a choice: a radio, its name, and its status. A scale that is not on is dimmed and can't be chosen. */
@Composable
private fun ScaleChoice(scale: PairedScale, status: ScaleStatus, inUse: Boolean, radio: Boolean, modifier: Modifier = Modifier, onUse: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val choice = if (radio) Modifier.selectable(selected = inUse, enabled = status.on, role = Role.RadioButton, onClick = onUse) else Modifier.semantics(mergeDescendants = true) { }
    Row(modifier.then(choice).heightIn(min = 60.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (radio) RadioButton(selected = inUse, onClick = null, enabled = status.on)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(scale.name, color = if (status.on) colors.onSurface else colors.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
            Text(status.line(inUse), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The chip's own row: the scale icon, the name of the scale in use, and an arrow that turns over as the list opens. */
@Composable
private fun ChooserHead(name: String, turn: Float, modifier: Modifier = Modifier, title: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.heightIn(min = 40.dp).padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        title?.invoke(this)
        Icon(Icons.Outlined.Scale, null, Modifier.size(16.dp))
        Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp).graphicsLayer { rotationZ = turn })
    }
}

/**
 * "Choose a scale" as the chip grown into a list: the same surface, anchored at its top right, with the icon and
 * name where they were. [grown] is where its size is heading: over about 320 ms it grows from the chip to the list
 * or back, and the rows fade in a little after it starts to grow.
 */
@Composable
internal fun ScaleChooserList(scales: List<PairedScale>, inUse: PairedScale, scale: ScaleUi, note: String?, grown: Boolean, onClose: () -> Unit, onChoose: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val ease = androidx.compose.animation.core.CubicBezierEasing(.2f, .7f, .2f, 1f)
    val turn by androidx.compose.animation.core.animateFloatAsState(if (grown) 180f else 0f, androidx.compose.animation.core.tween(320, easing = ease), label = "chooser arrow")
    val corner by androidx.compose.animation.core.animateDpAsState(if (grown) 16.dp else 10.dp, androidx.compose.animation.core.tween(320), label = "chooser corner")
    val lift by androidx.compose.animation.core.animateDpAsState(if (grown) 8.dp else 0.dp, androidx.compose.animation.core.tween(320), label = "chooser shadow")
    val rows by androidx.compose.animation.core.animateFloatAsState(if (grown) 1f else 0f, if (grown) androidx.compose.animation.core.tween(200, delayMillis = 120) else androidx.compose.animation.core.tween(120), label = "chooser rows")
    // The size animates inside the surface, so its rounded shape and shadow follow the growing edge.
    Surface(shape = RoundedCornerShape(corner), color = colors.soft, shadowElevation = lift, modifier = Modifier.semantics { paneTitle = "Choose a scale" }) {
      Box(Modifier.animateContentSize(androidx.compose.animation.core.tween(320, easing = ease), alignment = Alignment.TopEnd)) {
        if (!grown) ChooserHead(inUse.name, turn)
        else Column(Modifier.width(288.dp)) {
            ChooserHead(inUse.name, turn, Modifier.fillMaxWidth().clickable(onClickLabel = "Close", role = Role.Button, onClick = onClose)) {
                Text("Choose a scale", Modifier.weight(1f).graphicsLayer { alpha = rows }, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
            Column(Modifier.graphicsLayer { alpha = rows }.selectableGroup()) {
                scales.forEach { paired ->
                    ScaleChoice(paired, scale.status(paired.address, paired.address == inUse.address), paired.address == inUse.address, radio = true, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp)) {
                        onClose()
                        if (paired.address != inUse.address) onChoose(paired.address)
                    }
                }
                if (note != null) Text(note, Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
      }
    }
}

/**
 * The scale in use as a button beside a screen's title, while another paired scale is on. Tapping it opens
 * "Choose a scale" over the chip itself. [note] says what choosing does on this screen.
 */
@Composable
internal fun ScaleChooser(scales: List<PairedScale>, inUse: PairedScale, scale: ScaleUi, note: String? = null, onChoose: (String) -> Unit) {
    // [open] holds the list on screen; [grown] is where its size is heading, so it shrinks back before it goes.
    var open by remember { mutableStateOf(false) }
    var grown by remember { mutableStateOf(false) }
    LaunchedEffect(open, grown) {
        if (open && !grown) { kotlinx.coroutines.delay(340); open = false }
    }
    Box {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.soft,
            modifier = Modifier.widthIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * .42f).dp).clip(RoundedCornerShape(10.dp))
                .clickable(onClickLabel = "Choose a scale", role = Role.Button) { open = true }.clearAndSetSemantics { contentDescription = "${inUse.name}, in use. Choose a scale" }) {
            ChooserHead(inUse.name, 0f)
        }
        if (open) Popup(alignment = Alignment.TopEnd, onDismissRequest = { grown = false }, properties = PopupProperties(focusable = true)) {
            // It starts at the chip's size, over the chip, and grows from there.
            LaunchedEffect(Unit) { grown = true }
            ScaleChooserList(scales, inUse, scale, note, grown, onClose = { grown = false }, onChoose = onChoose)
        }
    }
}

/**
 * The scale's name beside a screen's title. While another paired scale is on and [onChoose] is given, it is the
 * button to choose between them; otherwise it only says how the scale in use is.
 */
@Composable
internal fun ScaleHeaderChip(state: AppState, scale: ScaleUi, connected: Boolean, reading: Boolean, note: String? = null, onChoose: ((String) -> Unit)?) {
    val inUse = state.scale ?: return
    val others = state.pairedScales.any { it.address != inUse.address && scale.status(it.address, false).on }
    if (onChoose != null && others) ScaleChooser(state.pairedScales, inUse, scale, note, onChoose) else ScaleChip(inUse.name, connected, reading)
}

/**
 * Settings → "Coffee scale": every paired scale with its status. With more than one, the list is a radio group and
 * tapping a scale that is on uses it. "More actions" on a row renames it in place or forgets it.
 */
@Composable
internal fun ScaleSettings(scales: List<PairedScale>, inUse: String?, scale: ScaleUi, onAdd: () -> Unit, onUse: (String) -> Unit, onRename: (String, String) -> Unit, onForget: (PairedScale) -> Unit, allowedCheck: (Context) -> Boolean = ScaleLink::allowed) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var allowed by remember { mutableStateOf(allowedCheck(context)) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { allowed = allowedCheck(context); ScaleLink.sync(context) }
    // The address of the scale being renamed, and the name as typed so far.
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var typed by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf<String?>(null) }
    SectionTitle("Coffee scale")
    if (scales.isEmpty()) return Panel {
        Text("Pair a Bluetooth scale to start the timer at your first pour and end each pour at its target.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        SoftButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("Add a scale") }
    }
    val many = scales.size > 1
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column {
            Column(if (many) Modifier.selectableGroup() else Modifier) {
                scales.forEachIndexed { index, paired ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = colors.outlineVariant)
                    val using = paired.address == inUse
                    val status = scale.status(paired.address, using)
                    if (renaming == paired.address) Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (many) RadioButton(selected = using, onClick = null, enabled = false, modifier = Modifier.padding(top = 18.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LibraryField(typed, { typed = it }, "Name", error = if (typed.isBlank()) "Enter a name." else null)
                            Text(paired.model ?: paired.name, Modifier.padding(start = 16.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                        IconAction(Icons.Outlined.Close, "Cancel", Modifier.padding(top = 8.dp)) { renaming = null }
                        FilledIconButton({ onRename(paired.address, typed); renaming = null }, Modifier.padding(top = 8.dp).size(40.dp), enabled = typed.isNotBlank(),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary)) { Icon(Icons.Outlined.Check, "Save name", Modifier.size(18.dp)) }
                    } else Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ScaleChoice(paired, status, using, radio = many, modifier = Modifier.weight(1f)) { if (!using) onUse(paired.address) }
                        Box {
                            IconButton({ menu = paired.address }) { Icon(Icons.Outlined.MoreVert, "More actions for ${paired.name}", Modifier.size(20.dp)) }
                            DropdownMenu(menu == paired.address, { menu = null }, shape = MaterialTheme.shapes.small, containerColor = colors.surfaceContainerHigh) {
                                DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = null; typed = paired.name; renaming = paired.address })
                                DropdownMenuItem(text = { Text("Forget") }, leadingIcon = { Icon(Icons.Outlined.RemoveCircleOutline, null) }, onClick = { menu = null; onForget(paired) })
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SoftButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Add a scale")
                }
                if (!allowed) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.BluetoothDisabled, null, Modifier.size(20.dp))
                        Text("Blocked by Android", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("Nearby devices is turned off for Brewprint. Brews are timed by the clock.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    SoftButton(shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp), onClick = { openAppSettings(context) }) { Text("Open Android settings") }
                }
            }
        }
    }
}

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brewprint.data.*
import com.brewprint.scale.ScaleLink
import com.brewprint.scale.ZERO_GRAMS
import kotlin.math.abs
import java.util.regex.Pattern

/** The live scale and its one command. Tests replace it. */
class ScaleUi(val live: ScaleLive = ScaleLive(), val tare: () -> Unit = {})

val LocalScale = compositionLocalOf { ScaleUi() }

internal fun tenths(value: Double?): String = value?.let { "%.1f".format(it) } ?: "–"

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
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Not now") }
            OutlinedButton(onClick = onPair, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("Pair a scale") }
        }
    }
}

@Composable
internal fun ScaleChip(name: String, connected: Boolean, reading: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    // "Off" only for a dropped link; a link that is up but sends nothing says "No reading", like the card.
    val status = if (!connected) "Off" else if (!reading) "No reading" else null
    val live = status == null
    Surface(shape = MaterialTheme.shapes.small, color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$name, ${if (!connected) "not connected" else if (!reading) "no reading" else "connected"}" }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).then(if (live) Modifier.background(colors.onSurface, CircleShape) else Modifier.background(colors.outlineVariant, CircleShape)))
            Text(if (status == null) name else "$name · $status", color = if (live) colors.onSurface else colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Reading(label: String, value: Double?, plan: Double? = null, announce: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val note = plan?.let { "plan ${formatAmount(it)} g" }
    val spoken = "$label, " + (value?.let { "${tenths(it)} grams" } ?: "no reading") + (plan?.let { ", plan ${formatAmount(it)} grams" } ?: "")
    // [announce] makes a steady reading a polite live region, so TalkBack says when it becomes ready; a moving one stays quiet.
    Column(Modifier.clearAndSetSemantics { contentDescription = spoken; if (announce) liveRegion = LiveRegionMode.Polite }) {
        Text(label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tenths(value), style = MaterialTheme.typography.headlineLarge.copy(fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold))
            Text("g", Modifier.padding(bottom = 7.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
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
internal fun ScalePrep(step: ScaleStep, live: ScaleLive, plannedDose: Double, ratio: Double, weighedDose: Double?, reconnected: Boolean, onTare: () -> Unit, onUse: (Double) -> Unit, onSkip: () -> Unit, onWithout: () -> Unit) {
    val colors = MaterialTheme.colorScheme
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
            Button(onClick = onTare, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("Tare") }
        } else when (step) {
            ScaleStep.BREWER -> {
                Text("Put the brewer on the scale", style = MaterialTheme.typography.titleMedium)
                // Back here after the scale dropped mid-weighing: its tare is unknown, and grounds may already be in the brewer.
                if (reconnected) Text("Tare the brewer again. Take the coffee out first if you've added it.", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.onSurface, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Reading("On scale", live.grams) }
                    Button(onClick = onTare, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("Tare") }
                }
            }
            // Until the scale reads about 0 g, coffee must wait: grounds added now would be tared away or taken as the dose.
            ScaleStep.COFFEE -> if (live.taring) Row(Modifier.heightIn(min = 48.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onSurface, trackColor = colors.outlineVariant)
                Text("Taring the brewer", style = MaterialTheme.typography.titleMedium)
            } else {
                Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                    Text("Brewer tared", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider(color = colors.outlineVariant)
                // A reading that still counts as zero is not coffee.
                val coffee = live.grams?.takeIf { live.steady && it > ZERO_GRAMS }?.let(::roundedAmount)
                Reading(if (coffee != null) "Dose ready" else "Add your coffee", live.grams, plannedDose, announce = coffee != null)
                // The weighed dose rescales this brew's pours; say so before the choice, not after.
                if (coffee != null && coffee != plannedDose) Text("Using ${formatAmount(coffee)} g adjusts the pours to keep 1:${formatAmount(ratio)}.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onSkip, enabled = coffee != null, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Keep ${formatAmount(plannedDose)} g") }
                    Button(onClick = { coffee?.let(onUse) }, enabled = coffee != null, modifier = Modifier.weight(1.4f).heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                        Text(if (coffee != null) "Use ${formatAmount(coffee)} g" else "Use dose")
                    }
                }
            }
            ScaleStep.ARMED -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // The weighed dose belongs with a tared scale; off zero, the reading is what matters.
                val label = if (live.zeroed) "On scale, tared" + weighedDose?.let { " · Weighed ${formatAmount(it)} g" }.orEmpty() else "On scale"
                Box(Modifier.weight(1f)) { Reading(label, live.grams) }
                // Off zero with no tare pending, nothing changes until the user tares, so Tare becomes the step's action.
                if (live.needsTare()) Button(onClick = onTare, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("Tare") }
                else OutlinedButton(onClick = onTare, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("Tare") }
            }
        }
        TextButton(onClick = onWithout, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Brew without the scale") }
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
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (live.zeroed) TextButton(onClick = onStartNow, modifier = Modifier.heightIn(min = 48.dp)) { Text("Start timer now") }
            TextButton(onClick = onFinishWithout, modifier = Modifier.heightIn(min = 48.dp)) { Text("Finish without timing") }
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

/** Ratio so far against where the plan ends, over the water curve with the current and next targets dashed. */
@Composable
internal fun LiveCurveCard(view: TimerNow, live: ScaleLive) {
    val colors = MaterialTheme.colorScheme
    val next = if (view.phase == TimerPhase.POUR) view.pulse else view.pulse + 1
    val targets = listOfNotNull(view.schedule.getOrNull(next), view.schedule.getOrNull(next + 1)).map { it.cumulativeWater }
    Panel {
        val ratio = live.grams?.takeIf { live.following == null }?.let { "1:" + "%.1f".format(it / view.recipe.dose) } ?: "–"
        Text("Ratio $ratio → 1:${formatAmount(view.recipe.ratio)}", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        WaterCurve(live.curve, targets, view.poursEndSeconds + 30, live.flows)
    }
}

/**
 * Water over brew time, one reading a second; gaps stay gaps, and readings below 0 g draw at 0. [flows], when given, is drawn as a shaded band
 * along the bottom third. Decorative: the numbers are in the text around it.
 */
@Composable
internal fun WaterCurve(curve: List<Double?>, targets: List<Double>, seconds: Int, flows: List<Double?> = emptyList()) {
    val colors = MaterialTheme.colorScheme
    val line = colors.onSurface
    val dashed = colors.outline
    val base = colors.outlineVariant
    val shade = colors.outlineVariant
    val top = maxOf(targets.maxOrNull() ?: 0.0, curve.filterNotNull().maxOrNull() ?: 0.0, 1.0) * 1.08
    val flowTop = maxOf(flows.filterNotNull().maxOrNull() ?: 0.0, 8.0)
    val span = maxOf(seconds, curve.size, 1)
    Canvas(Modifier.fillMaxWidth().height(96.dp).clearAndSetSemantics { }) {
        val x = { second: Int -> second.toFloat() / span * size.width }
        // Below 0 g the tared brewer or cup is off the scale, so the reading sits on the baseline instead of below the card.
        val y = { value: Double -> size.height - (value.coerceAtLeast(0.0) / top * size.height).toFloat() }
        flows.forEachIndexed { second, flow ->
            if (flow != null && flow > 0) {
                val height = (flow / flowTop * size.height / 3).toFloat()
                drawRect(shade, Offset(x(second), size.height - height), androidx.compose.ui.geometry.Size(maxOf(x(second + 1) - x(second), 1f), height))
            }
        }
        drawLine(base, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        targets.forEach { drawLine(dashed, Offset(0f, y(it)), Offset(size.width, y(it)), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))) }
        val path = Path()
        var open = false
        curve.forEachIndexed { second, value ->
            if (value == null) open = false
            else { if (open) path.lineTo(x(second), y(value)) else path.moveTo(x(second), y(value)); open = true }
        }
        drawPath(path, line, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
    }
}

/** Measured dose and water beside the plan, in the Finish brew review and the saved record. */
@Composable
internal fun MeasuredRows(recipe: Recipe, data: ScaleData) {
    val colors = MaterialTheme.colorScheme
    @Composable fun row(label: String, value: String, note: String?) = FlowRow(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = colors.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            if (note != null) Text(note, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
    row("Dose", "${formatAmount(recipe.dose)} g", if (data.weighedDose != null) "weighed" else null)
    row("Total brew water", data.water?.let { "${tenths(it)} g" } ?: "Not recorded", "planned ${formatAmount(recipe.totalWater)} g")
    data.water?.let { row("Ratio", "1:" + "%.1f".format(it / recipe.dose), "planned 1:${formatAmount(recipe.ratio)}") }
}

/** Offers the moment the dripper came off as the total, when it differs from the Finish tap. */
@Composable
internal fun LiftOffer(timer: BrewTimer, data: ScaleData, onUse: (Int) -> Unit) {
    val lift = data.liftSeconds ?: return
    val measured = timer.finishedAt?.let { ((it - timer.startedAt + 500) / 1000).toInt() }
    if ((timer.totalOverride ?: measured) == lift) return
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Dripper lifted at ${formatDuration(lift)}", Modifier.align(Alignment.CenterVertically), color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { onUse(lift) }) { Text("Use ${formatDuration(lift)}") }
    }
}

/** Settings → Coffee scale. */
@Composable
internal fun ScaleSettings(scale: PairedScale?, live: ScaleLive, onAdd: () -> Unit, onForget: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var allowed by remember { mutableStateOf(ScaleLink.allowed(context)) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { allowed = ScaleLink.allowed(context); ScaleLink.sync(context) }
    Text("Coffee scale", style = MaterialTheme.typography.titleMedium)
    Panel {
        if (scale == null) {
            Text("Pair a Bluetooth scale to start the timer at your first pour and end each pour at its target.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("Add a scale") }
        } else {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.align(Alignment.CenterVertically)) {
                    Text(scale.name, style = MaterialTheme.typography.titleMedium)
                    Text(if (live.connected) "Connected" + (live.battery?.let { " · Battery $it%" } ?: "") else "Not connected", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(onClick = onForget, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("Forget") }
            }
            if (!allowed) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.BluetoothDisabled, null, Modifier.size(20.dp))
                    Text("Blocked by Android", style = MaterialTheme.typography.titleMedium)
                }
                Text("Nearby devices is turned off for Brewprint. Brews are timed by the clock.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Open Android settings") }
            }
        }
    }
}

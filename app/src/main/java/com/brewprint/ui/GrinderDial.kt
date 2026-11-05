package com.brewprint.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.brewprint.data.DialFinish
import com.brewprint.data.MAX_GRIND_SETTING
import com.brewprint.data.MIN_GRIND_SETTING
import com.brewprint.data.parseGrindSetting
import kotlin.math.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GrinderDial(value: Int?, lastSetting: Int?, finish: DialFinish, onValue: (Int?) -> Unit, onFinish: (DialFinish) -> Unit, onDismiss: () -> Unit) {
    val view = LocalView.current
    var pendingValue by remember(value) { mutableStateOf(value) }
    var input by rememberSaveable { mutableStateOf(value?.toString().orEmpty()) }
    val currentChange by rememberUpdatedState(onValue)
    val setValue: (Int) -> Boolean = { requested ->
        val next = requested.coerceIn(MIN_GRIND_SETTING, MAX_GRIND_SETTING)
        if (next == pendingValue) false else {
            pendingValue = next
            input = next.toString()
            currentChange(next)
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            true
        }
    }
    val tick: (Int) -> Boolean = { direction ->
        val previous = (pendingValue ?: MIN_GRIND_SETTING).coerceIn(MIN_GRIND_SETTING, MAX_GRIND_SETTING)
        val next = (previous + direction).coerceIn(MIN_GRIND_SETTING, MAX_GRIND_SETTING)
        if (next == previous) false else setValue(next)
    }
    LaunchedEffect(value) {
        if (value != null && parseGrindSetting(input) != value) input = value.toString()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth().padding(12.dp).heightIn(max = 720.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Grind setting, ticks", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val stacked = maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f
                    val readout: @Composable () -> Unit = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(pendingValue?.toString() ?: "\u2014", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Light)
                            Text("ticks", style = MaterialTheme.typography.titleMedium)
                            Text("0\u2013100", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (stacked) {
                        Column(Modifier.fillMaxWidth()) {
                            readout()
                            GrinderWheel(pendingValue, finish, tick, setValue, Modifier.fillMaxWidth(.75f).align(Alignment.End).height(280.dp))
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(.8f)) { readout() }
                            GrinderWheel(pendingValue, finish, tick, setValue, Modifier.weight(1.2f).height(300.dp))
                        }
                    }
                }
                LibraryField(
                    input,
                    { text -> input = text; pendingValue = parseGrindSetting(text); onValue(pendingValue) },
                    "Enter value, ticks",
                    KeyboardType.Number,
                    error = if (parseGrindSetting(input) == null) "Enter whole ticks from 0 to 100." else null,
                    required = true,
                )
                Text("Last recorded setting: ${lastSetting?.let { "$it ticks" } ?: "Not recorded"}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Text("Wheel finish", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialFinish.entries.forEach { material ->
                        FilterChip(selected = finish == material, onClick = { onFinish(material) }, label = {
                            Text(when (material) {
                                DialFinish.METAL -> "Brushed metal"
                                DialFinish.MATTE -> "Matte black"
                                DialFinish.GLASS -> "Frosted glass"
                            })
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun GrinderWheel(value: Int?, finish: DialFinish, onTick: (Int) -> Boolean, onSet: (Int) -> Boolean, modifier: Modifier) {
    val currentTick by rememberUpdatedState(onTick)
    val pointer = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.clipToBounds().semantics {
        contentDescription = "Grinder adjustment wheel. Turn to change whole ticks."
        stateDescription = value?.let { "$it ticks" } ?: "Not set"
        progressBarRangeInfo = ProgressBarRangeInfo((value ?: MIN_GRIND_SETTING).coerceIn(MIN_GRIND_SETTING, MAX_GRIND_SETTING).toFloat(), MIN_GRIND_SETTING.toFloat()..MAX_GRIND_SETTING.toFloat(), MAX_GRIND_SETTING - MIN_GRIND_SETTING - 1)
        setProgress { requested -> requested.isFinite() && onSet(requested.roundToInt()) }
        customActions = listOf(
            CustomAccessibilityAction("Increase grind setting") { onTick(1) },
            CustomAccessibilityAction("Decrease grind setting") { onTick(-1) },
        )
    }.onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
            Key.DirectionUp, Key.DirectionRight -> { onTick(1); true }
            Key.DirectionDown, Key.DirectionLeft -> { onTick(-1); true }
            Key.MoveHome -> { onSet(MIN_GRIND_SETTING); true }
            Key.MoveEnd -> { onSet(MAX_GRIND_SETTING); true }
            else -> false
        }
    }.focusable().pointerInput(Unit) {
        var previousAngle = 0.0
        var remainder = 0.0
        fun angle(position: Offset) = atan2((position.y - size.height / 2f).toDouble(), (position.x - (size.width - 2.dp.toPx())).toDouble())
        detectDragGestures(
            onDragStart = { previousAngle = angle(it); remainder = 0.0 },
            onDragEnd = { remainder = 0.0 },
            onDragCancel = { remainder = 0.0 },
        ) { change, _ ->
            change.consume()
            val next = angle(change.position)
            var delta = next - previousAngle
            if (delta > PI) delta -= 2 * PI
            if (delta < -PI) delta += 2 * PI
            previousAngle = next
            remainder += delta
            val interval = PI / 60
            while (abs(remainder) >= interval) {
                val direction = if (remainder > 0) 1 else -1
                currentTick(direction)
                remainder -= interval * direction
            }
        }
    }) {
        drawGrinderWheel(value ?: MIN_GRIND_SETTING, finish, pointer)
    }
}

private fun DrawScope.drawGrinderWheel(value: Int, finish: DialFinish, pointer: Color) {
    val center = Offset(size.width - 2.dp.toPx(), size.height / 2)
    val radius = min(size.width * .9f, size.height * .455f)
    val matte = finish == DialFinish.MATTE
    val glass = finish == DialFinish.GLASS
    for (layer in 8 downTo 1) {
        drawCircle(Color.Black.copy(alpha = .018f), radius + layer * 1.5.dp.toPx(), center + Offset(-1.dp.toPx(), 4.dp.toPx()))
    }
    // The outer bevel catches light independently of the machined face.
    drawCircle(Brush.sweepGradient(listOf(Color(0xFF404044), Color(0xFFE7E7E8), Color(0xFF606064), Color(0xFFF2F2F3), Color(0xFF404044)), center), radius, center)
    drawCircle(Color.Black.copy(alpha = .8f), radius * .975f, center)
    val tones = when (finish) {
        DialFinish.METAL -> listOf(0xFF747477, 0xFFD4D4D6, 0xFF67676A, 0xFFBEBEC0, 0xFF535356, 0xFFB6B6B8, 0xFF747477)
        DialFinish.MATTE -> listOf(0xFF252527, 0xFF48484B, 0xFF222224, 0xFF363639, 0xFF19191B, 0xFF38383B, 0xFF252527)
        DialFinish.GLASS -> listOf(0xFFBDBDC2, 0xFFF0F0F3, 0xFFA1A1A9, 0xFFDADAE0, 0xFF909098, 0xFFE4E4E9, 0xFFBDBDC2)
    }.map { Color(it) }
    drawCircle(Brush.sweepGradient(tones, center), radius * .966f, center)
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = if (glass) .38f else .17f), Color.Transparent, Color.Black.copy(alpha = .2f)), center - Offset(radius * .3f, radius * .4f), radius * 1.6f), radius * .966f, center)
    val rings = if (matte) 42 else 100
    for (ring in 1..rings) {
        val fraction = .35f + .6f * ring / rings
        drawCircle(
            (if (ring % 3 == 0) Color.Black else Color.White).copy(alpha = if (matte) .025f else if (glass) .045f else .09f),
            radius * fraction, center, style = Stroke(.45.dp.toPx()),
        )
    }
    drawCircle(Color.White.copy(alpha = if (glass) .65f else .35f), radius * .954f, center, style = Stroke(.7.dp.toPx()))
    drawCircle(Color.Black.copy(alpha = .2f), radius * .825f, center, style = Stroke(.6.dp.toPx()))
    val rotation = value * PI / 60
    for (index in 0 until 120) {
        val angle = index * PI / 60 + rotation
        val major = index % 5 == 0
        fun point(fraction: Float) = center + Offset((cos(angle) * radius * fraction).toFloat(), (sin(angle) * radius * fraction).toFloat())
        drawLine(
            (if (matte) Color.White else Color.Black).copy(alpha = if (major) .78f else .48f),
            point(if (major) .84f else .88f), point(.927f),
            if (major) 1.4.dp.toPx() else .75.dp.toPx(), StrokeCap.Round,
        )
    }
    drawCircle(Color(0xFF111113), radius * .365f, center)
    drawCircle(Brush.sweepGradient(listOf(Color(0xFF77777B), Color(0xFFF0F0F1), Color(0xFF464649), Color(0xFFC6C6C9), Color(0xFF77777B)), center), radius * .337f, center)
    drawCircle(Brush.radialGradient(listOf(tones[1], tones[0], tones[2]), center - Offset(radius * .1f, radius * .12f), radius * .5f), radius * .31f, center)
    drawCircle(Color.White.copy(alpha = .2f), radius * .3f, center, style = Stroke(.7.dp.toPx()))
    val pointerStart = Offset(center.x - radius - 3.dp.toPx(), center.y)
    val pointerEnd = Offset(center.x - radius * .85f, center.y)
    drawLine(Color.Black.copy(alpha = .45f), pointerStart + Offset(0f, 2.dp.toPx()), pointerEnd + Offset(0f, 2.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
    drawLine(pointer, pointerStart, pointerEnd, 2.dp.toPx(), StrokeCap.Round)
}

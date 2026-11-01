package com.brewprint.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.brewprint.data.DialFinish
import kotlin.math.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GrinderDial(value: Int?, lastSetting: Int?, finish: DialFinish, onValue: (Int?) -> Unit, onFinish: (DialFinish) -> Unit, onDismiss: () -> Unit) {
    val view = LocalView.current
    var pendingValue by remember(value) { mutableStateOf(value) }
    val currentChange by rememberUpdatedState(onValue)
    val tick: (Int) -> Unit = { direction ->
        val next = (pendingValue ?: 0).toLong() + direction
        if (next in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            pendingValue = next.toInt()
            currentChange(next.toInt())
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    val currentTick by rememberUpdatedState(tick)
    var input by rememberSaveable { mutableStateOf(value?.toString().orEmpty()) }
    LaunchedEffect(value) { if (value != null && input.toIntOrNull() != value) input = value.toString() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp).heightIn(max = 680.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = .98f), tonalElevation = 10.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Grind setting, ticks", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(value?.toString() ?: "—", fontSize = 48.sp)
                        Text("ticks")
                        OutlinedButton(onClick = { tick(-1) }) { Text("− Decrease") }
                        OutlinedButton(onClick = { tick(1) }) { Text("+ Increase") }
                    }
                    Canvas(Modifier.weight(1f).height(280.dp).semantics { contentDescription = "Grinder adjustment wheel. Turn to change whole ticks. Increase and decrease buttons are also available." }.pointerInput(Unit) {
                        var previousAngle = 0.0
                        var remainder = 0.0
                        fun angle(position: Offset) = atan2((position.y - size.height / 2f).toDouble(), (position.x - size.width).toDouble())
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
                            val interval = PI / 36
                            while (abs(remainder) >= interval) {
                                val direction = if (remainder > 0) 1 else -1
                                currentTick(direction)
                                remainder -= interval * direction
                            }
                        }
                    }) {
                        val center = Offset(size.width, size.height / 2)
                        val radius = min(size.width * .96f, size.height * .48f)
                        val palette = when (finish) {
                            DialFinish.METAL -> listOf(Color(0xFFDFE0E4), Color(0xFF696C74), Color(0xFFC5C7CE), Color(0xFF50535B))
                            DialFinish.MATTE -> listOf(Color(0xFF55575D), Color(0xFF202125), Color(0xFF41434A))
                            DialFinish.GLASS -> listOf(Color(0xFFE8ECF4), Color(0xFF8E9AAA), Color(0xFFD8DFEB))
                        }
                        drawCircle(Brush.sweepGradient(palette + palette.first(), center), radius, center)
                        drawCircle(Color.White.copy(alpha = .6f), radius, center, style = Stroke(2.dp.toPx()))
                        if (finish == DialFinish.METAL) for (ring in 1..22) drawCircle(Color.White.copy(alpha = .07f), radius * ring / 23, center, style = Stroke(.5.dp.toPx()))
                        val rotation = ((value ?: 0) % 72) * PI / 36
                        for (index in 0 until 72) {
                            val angle = index * PI / 36 + rotation
                            val inner = radius * if (index % 6 == 0) .82f else .9f
                            drawLine(Color(0xFF22242A), center + Offset((cos(angle) * inner).toFloat(), (sin(angle) * inner).toFloat()), center + Offset((cos(angle) * radius * .97).toFloat(), (sin(angle) * radius * .97).toFloat()), if (index % 6 == 0) 2.dp.toPx() else 1.dp.toPx())
                        }
                        drawCircle(Brush.radialGradient(listOf(Color(0xFFDADCE2), Color(0xFF303238)), center, radius * .38f), radius * .38f, center)
                        drawLine(Color.White, Offset(center.x - radius, center.y), Offset(center.x - radius * .72f, center.y), 4.dp.toPx())
                    }
                }
                OutlinedTextField(input, { text -> input = text; pendingValue = text.toIntOrNull(); onValue(pendingValue) }, Modifier.fillMaxWidth(), label = { Text("Enter value, ticks") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = input.toIntOrNull() == null, supportingText = { if (input.toIntOrNull() == null) Text("Enter whole ticks.") }, singleLine = true)
                Text("Last recorded setting: ${lastSetting?.let { "$it ticks" } ?: "Not recorded"}", style = MaterialTheme.typography.bodyMedium)
                Text("Wheel finish", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialFinish.entries.forEach { material -> FilterChip(selected = finish == material, onClick = { onFinish(material) }, label = { Text(when (material) { DialFinish.METAL -> "Brushed metal"; DialFinish.MATTE -> "Matte black"; DialFinish.GLASS -> "Frosted glass" }) }) }
                }
            }
        }
    }
}

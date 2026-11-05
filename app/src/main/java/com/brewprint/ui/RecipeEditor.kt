package com.brewprint.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.brewprint.data.*
import java.util.UUID

private data class PulseInput(val label: String, val water: String, val pour: String, val wait: String, val id: String = UUID.randomUUID().toString())

private val pulseInputSaver = listSaver<List<PulseInput>, String>(
    save = { it.flatMap { pulse -> listOf(pulse.label, pulse.water, pulse.pour, pulse.wait, pulse.id) } },
    restore = { it.chunked(5).map { fields -> PulseInput(fields[0], fields[1], fields[2], fields[3], fields[4]) } },
)

@Composable
fun RecipeEditor(initial: Recipe?, onSave: (Recipe) -> Boolean, onBack: () -> Unit) {
    val original = remember(initial?.id) { initial ?: Recipe() }
    val id = rememberSaveable { original.id }
    var name by rememberSaveable { mutableStateOf(original.name) }
    var dose by rememberSaveable { mutableStateOf(original.dose.takeIf { it > 0 }?.let(::formatAmount) ?: "") }
    var ratio by rememberSaveable { mutableStateOf(original.ratio.takeIf { it > 0 }?.let(::formatAmount) ?: "") }
    var notes by rememberSaveable { mutableStateOf(original.notes) }
    val initialPulses = remember(original) { original.pulses.mapIndexed { index, pulse -> PulseInput(pulse.label, pulse.water.takeIf { it > 0 }?.let(::formatAmount) ?: "", pulse.pourSeconds.takeIf { it > 0 }?.toString() ?: "", pulse.waitSeconds.toString(), "initial-$index") } }
    var pulses by rememberSaveable(stateSaver = pulseInputSaver) { mutableStateOf(initialPulses) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var announcement by rememberSaveable { mutableStateOf("") }
    val view = LocalView.current
    val originalDose = original.dose.takeIf { it > 0 }?.let(::formatAmount) ?: ""
    val originalRatio = original.ratio.takeIf { it > 0 }?.let(::formatAmount) ?: ""
    val dirty = name != original.name || dose != originalDose || ratio != originalRatio || notes != original.notes || pulses != initialPulses
    val back = { if (dirty) discard = true else onBack() }
    BackHandler(onBack = back)

    val doseValue = positiveDecimal(dose)
    val ratioValue = ratio.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    val target = if (doseValue != null && ratioValue != null) totalBrewWater(doseValue, ratioValue).takeIf { it.isFinite() && it > 0 } else null
    val waterValues = pulses.map { positiveDecimal(it.water) }
    val planned = waterValues.takeIf { values -> values.all { it != null } }?.sumOf { it!! }?.let(::roundedAmount)
    val schedule = pulses.mapIndexed { index, pulse -> (pulse.pour.toIntOrNull() ?: 0) + if (index < pulses.lastIndex) (pulse.wait.toIntOrNull() ?: 0) else 0 }.runningFold(0, Int::plus)
    val valid = name.isNotBlank() && doseValue != null && ratioValue != null && target != null && planned == target && pulses.all { it.pour.toIntOrNull()?.let { seconds -> seconds > 0 } == true && it.wait.toIntOrNull()?.let { seconds -> seconds >= 0 } == true }

    fun replace(pulseId: String, update: (PulseInput) -> PulseInput) { pulses = pulses.map { if (it.id == pulseId) update(it) else it } }
    fun reorderOrRemove(updated: List<PulseInput>) {
        val oldLast = pulses.last()
        val newLast = updated.last()
        if (newLast.id != oldLast.id) {
            announcement = "The last pulse has no wait. Its wait has been reset to zero."
            view.announceForAccessibility(announcement)
        }
        pulses = updated.dropLast(1) + newLast.copy(wait = "0")
    }

    val pulseBounds = remember { mutableStateMapOf<String, Rect>() }
    var listCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var draggedPulseId by remember { mutableStateOf<String?>(null) }
    var dragPointerY by remember { mutableFloatStateOf(0f) }
    var dragGrabOffset by remember { mutableFloatStateOf(0f) }
    var reorderPending by remember { mutableStateOf(false) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val edgeSize = with(LocalDensity.current) { 64.dp.toPx() }
    val scrollStep = with(LocalDensity.current) { 48.dp.toPx() }

    fun moveDraggedPulse() {
        val pulseId = draggedPulseId ?: return
        if (reorderPending) return
        val bounds = pulseBounds[pulseId] ?: return
        val index = pulses.indexOfFirst { it.id == pulseId }
        val center = dragPointerY - dragGrabOffset + bounds.height / 2
        val previous = pulses.getOrNull(index - 1)?.let { pulseBounds[it.id] }
        val next = pulses.getOrNull(index + 1)?.let { pulseBounds[it.id] }
        val targetIndex = when {
            previous != null && center < previous.center.y -> index - 1
            next != null && center > next.center.y -> index + 1
            else -> index
        }
        if (targetIndex != index) {
            reorderPending = true
            reorderOrRemove(pulses.toMutableList().also { it.add(targetIndex, it.removeAt(index)) })
        }
    }

    LaunchedEffect(draggedPulseId) {
        val pulseId = draggedPulseId ?: return@LaunchedEffect
        while (draggedPulseId == pulseId) {
            withFrameNanos { }
            moveDraggedPulse()
            val coordinates = listCoordinates?.takeIf { it.isAttached } ?: continue
            val index = pulses.indexOfFirst { it.id == pulseId }
            // Ask the existing page scroller to reveal the next strip while the grip is held at an edge.
            val visible = coordinates.boundsInWindow()
            val edge = when {
                dragPointerY < visible.top + edgeSize && index > 0 -> visible.top - scrollStep
                dragPointerY > visible.bottom - edgeSize && index < pulses.lastIndex -> visible.bottom + scrollStep
                else -> null
            }
            if (edge != null) {
                val y = coordinates.windowToLocal(Offset(visible.center.x, edge)).y
                bringIntoView.bringIntoView(Rect(0f, y, 1f, y + 1f))
            }
        }
    }

    LibraryPage(if (initial == null) "New recipe" else "Edit recipe", back) {
        LibraryField(name, { name = it }, "Name", error = if (name.isBlank()) "Enter a recipe name." else null, required = true)
        LibraryField(dose, { dose = it }, "Coffee dose, g", KeyboardType.Decimal, error = if (doseValue == null) "Enter a positive amount with up to one decimal place." else null, required = true)
        LibraryField(ratio, { ratio = it }, "Brew ratio", KeyboardType.Decimal, error = if (ratioValue == null) "Enter a positive ratio denominator." else null, required = true, prefix = { Text("1 : ") })
        if (ratioValue != null) Text("1 g coffee to ${formatAmount(ratioValue)} g water", style = MaterialTheme.typography.bodySmall)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Total brew water", style = MaterialTheme.typography.labelLarge)
                Text(target?.let { "${formatAmount(it)} g" } ?: "Enter dose and ratio", style = if (target == null) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.headlineSmall, color = if (target == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            }
        }
        Text("Pulses", style = MaterialTheme.typography.titleLarge)
        Column(Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView).onGloballyPositioned { listCoordinates = it }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            pulses.forEachIndexed { index, pulse ->
                val currentWater = waterValues[index]
                val cumulativeValid = waterValues.take(index + 1).all { it != null }
                val cumulative = waterValues.take(index + 1).sumOf { it ?: 0.0 }
                val start = schedule[index]
                key(pulse.id) {
                    var gripCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
                    Box(Modifier.fillMaxWidth().zIndex(if (draggedPulseId == pulse.id) 1f else 0f).onGloballyPositioned {
                        val topLeft = it.localToWindow(Offset.Zero)
                        if (draggedPulseId == pulse.id && topLeft.y != pulseBounds[pulse.id]?.top) reorderPending = false
                        pulseBounds[pulse.id] = Rect(topLeft, Size(it.size.width.toFloat(), it.size.height.toFloat()))
                    }) {
                        OutlinedCard(Modifier.fillMaxWidth().graphicsLayer {
                            translationY = if (draggedPulseId == pulse.id) dragPointerY - dragGrabOffset - (pulseBounds[pulse.id]?.top ?: 0f) else 0f
                        }) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Pulse ${index + 1} · ${formatDuration(start)}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    if (pulses.size > 1) {
                                        IconButton(enabled = index > 0, modifier = Modifier.size(48.dp), onClick = {
                                            reorderOrRemove(pulses.toMutableList().also { it.add(index - 1, it.removeAt(index)) })
                                        }) { Icon(Icons.Outlined.ArrowUpward, "Move pulse ${index + 1} up", Modifier.size(20.dp)) }
                                        IconButton(enabled = index < pulses.lastIndex, modifier = Modifier.size(48.dp), onClick = {
                                            reorderOrRemove(pulses.toMutableList().also { it.add(index + 1, it.removeAt(index)) })
                                        }) { Icon(Icons.Outlined.ArrowDownward, "Move pulse ${index + 1} down", Modifier.size(20.dp)) }
                                        Box(Modifier.size(48.dp).onGloballyPositioned { gripCoordinates = it }
                                            .semantics {
                                                contentDescription = "Drag pulse ${index + 1} to reorder"
                                                if (pulses.size == 1) disabled()
                                            }
                                            .pointerInput(pulse.id) {
                                                detectDragGestures(
                                                    onDragStart = { position ->
                                                        if (pulses.size > 1) {
                                                            dragPointerY = gripCoordinates!!.localToWindow(position).y
                                                            dragGrabOffset = dragPointerY - pulseBounds.getValue(pulse.id).top
                                                            reorderPending = false
                                                            draggedPulseId = pulse.id
                                                        }
                                                    },
                                                    onDrag = { change, _ ->
                                                        if (draggedPulseId == pulse.id) {
                                                            change.consume()
                                                            dragPointerY = gripCoordinates!!.localToWindow(change.position).y
                                                            moveDraggedPulse()
                                                        }
                                                    },
                                                    onDragEnd = { moveDraggedPulse(); draggedPulseId = null },
                                                    onDragCancel = { draggedPulseId = null },
                                                )
                                            }, contentAlignment = Alignment.Center) {
                                            Icon(Icons.Outlined.DragIndicator, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                LibraryField(pulse.label, { value -> replace(pulse.id) { it.copy(label = value) } }, "Label, optional")
                                LibraryField(pulse.water, { value -> replace(pulse.id) { it.copy(water = value) } }, "Water to add, g", KeyboardType.Decimal, error = if (currentWater == null) "Enter a positive amount with up to one decimal place." else null, required = true)
                                if (cumulativeValid) Text("Add ${formatAmount(currentWater!!)} g · Pour to ${formatAmount(roundedAmount(cumulative))} g total")
                                LibraryField(pulse.pour, { value -> replace(pulse.id) { it.copy(pour = value) } }, "Pour duration, seconds", KeyboardType.Number, error = if (pulse.pour.toIntOrNull()?.let { it > 0 } != true) "Enter a positive whole number of seconds." else null, required = true)
                                if (index < pulses.lastIndex) LibraryField(pulse.wait, { value -> replace(pulse.id) { it.copy(wait = value) } }, "Wait after pouring, seconds", KeyboardType.Number, error = if (pulse.wait.toIntOrNull()?.let { it >= 0 } != true) "Enter zero or a positive whole number." else null)
                                if (pulses.size > 1) TextButton(onClick = { reorderOrRemove(pulses.filterIndexed { pulseIndex, _ -> pulseIndex != index }) }) { Text("Remove pulse ${index + 1}") }
                            }
                        }
                    }
                }
            }
        }
        OutlinedButton(shape = MaterialTheme.shapes.small, onClick = {
            val last = pulses.last()
            pulses = pulses.dropLast(1) + last.copy(wait = "0") + PulseInput("", last.water, last.pour, "0")
        }, modifier = Modifier.fillMaxWidth()) { Text("Add pulse") }
        Text("Copies the previous water amount and pour duration. Check its new wait, which starts at zero.", style = MaterialTheme.typography.bodySmall)
        if (announcement.isNotEmpty()) Text(announcement, style = MaterialTheme.typography.bodySmall)
        Text("Keep the scale untared between pulses.", style = MaterialTheme.typography.bodySmall)
        Text("Planned pours: ${planned?.let(::formatAmount) ?: "—"} g / Target: ${target?.let(::formatAmount) ?: "—"} g", style = MaterialTheme.typography.titleMedium)
        if (planned != null && target != null && planned != target) {
            val difference = roundedAmount(target - planned)
            Text(if (difference > 0) "Add ${formatAmount(difference)} g across your pulses to reach ${formatAmount(target)} g." else "Remove ${formatAmount(-difference)} g across your pulses to reach ${formatAmount(target)} g.", color = MaterialTheme.colorScheme.error)
            Text("Changing dose or ratio changes the target without scaling your pulses.", style = MaterialTheme.typography.bodySmall)
        }
        Text("Scheduled pours end at ${formatDuration(schedule.last())}", style = MaterialTheme.typography.bodyMedium)
        LibraryField(notes, { notes = it }, "Preparation notes, optional", multiline = true)
        Button(shape = MaterialTheme.shapes.small, enabled = valid, onClick = {
            val recipe = original.copy(id = id, name = name.trim(), dose = doseValue!!, ratio = ratioValue!!, notes = notes, pulses = pulses.mapIndexed { index, pulse -> Pulse(pulse.label, pulse.water.toDouble(), pulse.pour.toInt(), if (index == pulses.lastIndex) 0 else pulse.wait.toInt()) })
            if (onSave(recipe)) onBack()
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Save recipe") }
    }
    DiscardLibraryDialog(discard, { discard = false }, onBack)
}

private fun positiveDecimal(value: String): Double? = value.toDoubleOrNull()?.takeIf {
    value.matches(Regex("[0-9]+(?:\\.[0-9])?")) && it.isFinite() && it > 0
}

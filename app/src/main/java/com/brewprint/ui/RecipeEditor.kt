package com.brewprint.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.outlined.Add
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Check
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.brewprint.data.*
import java.util.UUID

private data class PulseInput(val label: String, val water: String, val pour: String, val wait: String, val id: String = UUID.randomUUID().toString(), val notes: String = "")

private val pulseInputSaver = listSaver<List<PulseInput>, String>(
    save = { it.flatMap { pulse -> listOf(pulse.label, pulse.water, pulse.pour, pulse.wait, pulse.id, pulse.notes) } },
    restore = { it.chunked(6).map { fields -> PulseInput(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5]) } },
)

/** The recipe's optional details as typed, so an untouched form compares equal to the recipe it opened. */
private data class Details(val style: DrinkStyle, val ice: String, val temp: String, val target: String, val grind: String, val source: String, val notes: String) {
    constructor(recipe: Recipe) : this(recipe.drinkStyle, recipe.iceGrams?.let(::formatAmount).orEmpty(), recipe.waterTempC?.toString().orEmpty(),
        recipe.targetFinishSeconds?.let(::formatDuration).orEmpty(), recipe.grindDescription, recipe.sourceUrl, recipe.notes)
}

private val detailsSaver = listSaver<Details, String>(
    save = { listOf(it.style.name, it.ice, it.temp, it.target, it.grind, it.source, it.notes) },
    restore = { Details(DrinkStyle.valueOf(it[0]), it[1], it[2], it[3], it[4], it[5], it[6]) },
)

/** Adds or edits a recipe, or with [onDiscardDraft], reviews a library draft before adding it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipeEditor(initial: Recipe?, onSave: (Recipe) -> Boolean, onBack: () -> Unit, onDiscardDraft: (() -> Unit)? = null) {
    var original by remember(initial?.id) { mutableStateOf(initial ?: Recipe()) }
    val id = rememberSaveable { original.id }
    var name by rememberSaveable { mutableStateOf(original.name) }
    var dose by rememberSaveable { mutableStateOf(original.dose.takeIf { it > 0 }?.let(::formatAmount) ?: "") }
    // The dose the pours were planned for. Changing the dose offers to scale the pours from it; changing the pours by hand makes them the plan for the dose shown.
    var referenceDose by rememberSaveable { mutableStateOf(dose) }
    var details by rememberSaveable(stateSaver = detailsSaver) { mutableStateOf(Details(original)) }
    val initialPulses = remember(original) { pulseInputs(original) }
    var pulses by rememberSaveable(stateSaver = pulseInputSaver) { mutableStateOf(initialPulses) }
    // Pulses whose note field is open although it is still empty.
    var openNotes by rememberSaveable { mutableStateOf(listOf<String>()) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var announcement by rememberSaveable { mutableStateOf("") }
    val view = LocalView.current
    val originalDose = original.dose.takeIf { it > 0 }?.let(::formatAmount) ?: ""
    val dirty = name != original.name || dose != originalDose || details != Details(original) || pulses != initialPulses
    // A draft Claude corrects while it's open shows the correction, unless the user has started editing.
    LaunchedEffect(initial) {
        if (initial != null && initial != original && !dirty) {
            original = initial
            name = initial.name
            dose = initial.dose.takeIf { it > 0 }?.let(::formatAmount) ?: ""
            referenceDose = dose
            details = Details(initial)
            pulses = pulseInputs(initial)
        }
    }
    val back = { if (dirty) discard = true else onBack() }
    BackHandler(LocalScreenActive.current, back)

    val doseValue = positiveDecimal(dose)
    val iced = details.style == DrinkStyle.ICED
    val iceValue = positiveDecimal(details.ice)
    val iceError = iced && details.ice.isNotBlank() && iceValue == null
    val tempValue = details.temp.trim().toIntOrNull()
    val tempError = details.temp.isNotBlank() && tempValue?.let { it in 1..100 } != true
    val targetValue = parseDuration(details.target)
    val targetError = details.target.isNotBlank() && targetValue == null
    val sourceError = details.source.isNotBlank() && !webAddress(details.source)
    val waterValues = pulses.map { positiveDecimal(it.water) }
    val planned = waterValues.takeIf { values -> values.all { it != null } }?.sumOf { it!! }?.let(::roundedAmount)
    val schedule = pulses.mapIndexed { index, pulse -> (pulse.pour.toIntOrNull() ?: 0) + if (index < pulses.lastIndex) (pulse.wait.toIntOrNull() ?: 0) else 0 }.runningFold(0, Int::plus)
    val pulsesValid = planned != null && pulses.all { it.pour.toIntOrNull()?.let { seconds -> seconds > 0 } == true && it.wait.toIntOrNull()?.let { seconds -> seconds >= 0 } == true }
    val valid = name.isNotBlank() && doseValue != null && pulsesValid && !iceError && !tempError && !targetError && !sourceError
    /** The recipe as the form stands, with [withDose] as its dose. */
    fun current(withDose: Double): Recipe = original.copy(id = id, name = name.trim(), dose = withDose, notes = details.notes, drinkStyle = details.style,
        iceGrams = iceValue.takeIf { iced }, waterTempC = tempValue.takeIf { !tempError }, targetFinishSeconds = targetValue, grindDescription = details.grind.trim(), sourceUrl = details.source.trim(),
        pulses = pulses.mapIndexed { index, pulse -> Pulse(pulse.label, pulse.water.toDoubleOrNull() ?: 0.0, pulse.pour.toIntOrNull() ?: 0, if (index == pulses.lastIndex) 0 else pulse.wait.toIntOrNull() ?: 0, pulse.notes.trim()) }).normalized()

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

    LibraryPage(if (onDiscardDraft != null) "Review recipe" else if (initial == null) "New recipe" else "Edit recipe", back, actions = { onDiscardDraft?.let { DiscardDraftAction(it) } }) {
        LibraryField(name, { name = it }, "Name", error = if (name.isBlank()) "Enter a recipe name." else null, required = true)
        Text("Style", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DrinkStyle.entries.forEach { style ->
                val selected = details.style == style
                FilterChip(selected = selected, onClick = { details = details.copy(style = style) }, label = { Text(if (style == DrinkStyle.HOT) "Hot" else "Iced") },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { role = Role.RadioButton },
                    leadingIcon = if (selected) ({ Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }) else null)
            }
        }
        SideBySide(if (iced) 2 else 1) { modifier ->
            LibraryField(dose, { dose = it }, "Coffee dose, g", KeyboardType.Decimal, error = if (doseValue == null) "Enter a positive amount with up to one decimal place." else null, required = true, modifier = modifier)
            if (iced) LibraryField(details.ice, { details = details.copy(ice = it) }, "Ice in server, g", KeyboardType.Decimal, error = if (iceError) "Enter a positive amount with up to one decimal place." else null, modifier = modifier)
        }
        RecipeSummary(doseValue, planned, iceValue.takeIf { iced }, schedule.last())
        val reference = positiveDecimal(referenceDose)
        // A new dose leaves the pours as they are, so the ratio moves; one tap scales them back to the ratio they had.
        if (doseValue != null && reference != null && doseValue != reference && pulsesValid) {
            val before = current(reference)
            val scaled = before.scaledTo(doseValue, scaleIce = true)
            Panel {
                Text("Coffee dose changed from ${formatAmount(reference)} g to ${formatAmount(doseValue)} g. Pours still total ${formatAmount(before.totalWater)} g, so the ratio is ${if (doseValue > reference) "stronger" else "weaker"} than before.", style = MaterialTheme.typography.bodyMedium)
                SoftButton(onClick = {
                    pulses = pulses.zip(scaled.pulses) { input, pulse -> input.copy(water = formatAmount(pulse.water)) }
                    scaled.iceGrams?.takeIf { iced }?.let { details = details.copy(ice = formatAmount(it)) }
                    referenceDose = dose
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Scale pours to keep 1:${formatRatio(before.computedRatio)}") }
                Text("Pours become " + scaled.pulses.joinToString(" · ") { "${formatAmount(it.water)} g" } + ", total ${formatAmount(scaled.totalWater)} g." +
                    (scaled.iceGrams?.takeIf { iced }?.let { " Ice becomes ${formatAmount(it)} g." } ?: ""), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        SectionTitle("Pulses")
        Column(Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView).onGloballyPositioned { listCoordinates = it }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Pulse ${index + 1} · ${formatDuration(start)}", style = MaterialTheme.typography.titleMedium)
                                        if (cumulativeValid) Text("Add ${formatAmount(currentWater!!)} g · Pour to ${formatAmount(roundedAmount(cumulative))} g total", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                    }
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
                                            }
                                            .pointerInput(pulse.id) {
                                                detectDragGestures(
                                                    onDragStart = { position ->
                                                        dragPointerY = gripCoordinates!!.localToWindow(position).y
                                                        dragGrabOffset = dragPointerY - pulseBounds.getValue(pulse.id).top
                                                        reorderPending = false
                                                        draggedPulseId = pulse.id
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
                                // Water, pour and wait share a row where they fit; the last pulse has no wait.
                                SideBySide(3) { modifier ->
                                    LibraryField(pulse.water, { value -> replace(pulse.id) { it.copy(water = value) }; referenceDose = dose }, "Water, g", KeyboardType.Decimal, error = if (currentWater == null) "Positive, up to 0.1 g" else null, modifier = modifier)
                                    LibraryField(pulse.pour, { value -> replace(pulse.id) { it.copy(pour = value) } }, "Pour, s", KeyboardType.Number, error = if (pulse.pour.toIntOrNull()?.let { it > 0 } != true) "Whole seconds above 0" else null, modifier = modifier)
                                    if (index < pulses.lastIndex) LibraryField(pulse.wait, { value -> replace(pulse.id) { it.copy(wait = value) } }, "Wait, s", KeyboardType.Number, error = if (pulse.wait.toIntOrNull()?.let { it >= 0 } != true) "Whole seconds, 0 or more" else null, modifier = modifier)
                                    else Box(modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                                        Text("Last pour, no wait", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                if (pulse.notes.isNotEmpty() || pulse.id in openNotes) LibraryField(pulse.notes, { value -> replace(pulse.id) { it.copy(notes = value) } }, "Step note, optional", helper = "For example, \"Stir gently 3 times\"".takeIf { pulse.notes.isEmpty() })
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (pulse.notes.isEmpty() && pulse.id !in openNotes) SoftTextButton(onClick = { openNotes = openNotes + pulse.id }) {
                                        Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Add note")
                                    }
                                    if (pulses.size > 1) SoftTextButton(onClick = { reorderOrRemove(pulses.filterIndexed { pulseIndex, _ -> pulseIndex != index }); referenceDose = dose }) { Text("Remove pulse ${index + 1}") }
                                }
                            }
                        }
                    }
                }
            }
        }
        SoftButton(shape = MaterialTheme.shapes.small, onClick = {
            val last = pulses.last()
            // A new pulse copies the last one's water and pour time; its label and note start empty.
            pulses = pulses.dropLast(1) + last.copy(wait = "0") + PulseInput("", last.water, last.pour, "0")
            referenceDose = dose
        }, modifier = Modifier.fillMaxWidth()) { Text("Add pulse") }
        Text("Copies the previous water amount and pour duration. Check its new wait, which starts at zero.", style = MaterialTheme.typography.bodySmall)
        if (announcement.isNotEmpty()) Text(announcement, style = MaterialTheme.typography.bodySmall)
        Text("Keep the scale untared between pulses.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Details", Modifier.weight(1f))
            Text("Optional", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        SideBySide(2) { modifier ->
            LibraryField(details.temp, { details = details.copy(temp = it) }, "Water temp, °C", KeyboardType.Number, error = if (tempError) "From 1 to 100 °C" else null, modifier = modifier)
            LibraryField(details.target, { details = details.copy(target = it) }, "Target finish", error = if (targetError) "Minutes and seconds, like 2:10" else null, helper = "Like 2:10", modifier = modifier)
        }
        LibraryField(details.grind, { details = details.copy(grind = it) }, "Grind size", helper = "How it should look, such as \"Medium-fine\"")
        LibraryField(details.source, { details = details.copy(source = it) }, "Source URL", KeyboardType.Uri, error = if (sourceError) "Enter a web address starting with http:// or https://." else null)
        LibraryField(details.notes, { details = details.copy(notes = it) }, "Preparation notes", multiline = true)
        PrimaryAction(if (onDiscardDraft != null) "Add to Library" else "Save recipe", enabled = valid, icon = Icons.Outlined.Check) {
            if (onSave(current(doseValue!!))) onBack()
        }
    }
    DiscardLibraryDialog(discard, { discard = false }, onBack)
}

/** Total brew water, the ratio it gives, when the pours end, and for an iced recipe, what ends up in the cup. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipeSummary(dose: Double?, water: Double?, ice: Double?, end: Int) {
    val ratio = if (dose != null && water != null) Recipe(dose = dose, pulses = listOf(Pulse(water = water))).computedRatio.takeIf { it > 0 } else null
    val overall = if (ratio != null && ice != null) Recipe(dose = dose!!, pulses = listOf(Pulse(water = water!!)), drinkStyle = DrinkStyle.ICED, iceGrams = ice).overallRatio else null
    Panel {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp), maxItemsInEachRow = 2) {
            SummaryValue("Total brew water", water?.let { "${formatAmount(it)} g" } ?: "—", null, water?.let { "Total brew water ${formatAmount(it)} grams" })
            SummaryValue("Brew ratio", ratio?.let { "1:${formatRatio(it)}" } ?: "—", overall?.let { "1:${formatRatio(it)} with ice" },
                ratio?.let { "Brew ratio 1 to ${formatRatio(it)}" + (overall?.let { o -> ", 1 to ${formatRatio(o)} with ice" } ?: "") })
            SummaryValue("Pours end at", formatDuration(end), null, null)
            if (ice != null && water != null) SummaryValue("In the cup", "${formatAmount(roundedAmount(water + ice))} g", "water + ice", null)
        }
    }
}

@Composable
private fun SummaryValue(label: String, value: String, detail: String?, spoken: String?) {
    Column(Modifier.widthIn(min = 120.dp).then(if (spoken != null) Modifier.clearAndSetSemantics { contentDescription = spoken } else Modifier.semantics(mergeDescendants = true) { }), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.titleLarge)
        if (detail != null) Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

/** Up to [count] fields in a row on a phone at normal text size; one above the other when they would be cramped. */
@Composable
private fun SideBySide(count: Int, fields: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (count > 1 && maxWidth / LocalDensity.current.fontScale >= 100.dp * count) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { fields(Modifier.weight(1f)) }
        else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { fields(Modifier.fillMaxWidth()) }
    }
}

private fun pulseInputs(recipe: Recipe) = recipe.pulses.mapIndexed { index, pulse -> PulseInput(pulse.label, pulse.water.takeIf { it > 0 }?.let(::formatAmount) ?: "", pulse.pourSeconds.takeIf { it > 0 }?.toString() ?: "", pulse.waitSeconds.toString(), "initial-$index", pulse.notes) }

internal fun positiveDecimal(value: String): Double? = value.toDoubleOrNull()?.takeIf {
    value.matches(Regex("[0-9]+(?:\\.[0-9])?")) && it.isFinite() && it > 0
}

package com.brewprint.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.brewprint.data.*

private data class PulseInput(val label: String, val water: String, val pour: String, val wait: String)

private val pulseInputSaver = listSaver<List<PulseInput>, String>(
    save = { it.flatMap { pulse -> listOf(pulse.label, pulse.water, pulse.pour, pulse.wait) } },
    restore = { it.chunked(4).map { fields -> PulseInput(fields[0], fields[1], fields[2], fields[3]) } },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipeEditor(initial: Recipe?, onSave: (Recipe) -> Boolean, onBack: () -> Unit) {
    val original = remember(initial?.id) { initial ?: Recipe() }
    val id = rememberSaveable { original.id }
    var name by rememberSaveable { mutableStateOf(original.name) }
    var dose by rememberSaveable { mutableStateOf(original.dose.takeIf { it > 0 }?.let(::formatAmount) ?: "") }
    var ratio by rememberSaveable { mutableStateOf(original.ratio.takeIf { it > 0 }?.let(::formatAmount) ?: "") }
    var notes by rememberSaveable { mutableStateOf(original.notes) }
    val initialPulses = remember(original) { original.pulses.map { PulseInput(it.label, it.water.takeIf { water -> water > 0 }?.let(::formatAmount) ?: "", it.pourSeconds.takeIf { seconds -> seconds > 0 }?.toString() ?: "", it.waitSeconds.toString()) } }
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
    val valid = name.isNotBlank() && doseValue != null && ratioValue != null && target != null && planned == target && pulses.all { it.pour.toIntOrNull()?.let { seconds -> seconds > 0 } == true && it.wait.toIntOrNull()?.let { seconds -> seconds >= 0 } == true }

    fun replace(index: Int, value: PulseInput) { pulses = pulses.toMutableList().also { it[index] = value } }
    fun reorderOrRemove(updated: List<PulseInput>) {
        val oldLast = pulses.last()
        val newLast = updated.last()
        if (newLast != oldLast) {
            announcement = "The last pulse has no wait. Its wait has been reset to zero."
            view.announceForAccessibility(announcement)
        }
        pulses = updated.dropLast(1) + newLast.copy(wait = "0")
    }

    LibraryPage(if (initial == null) "New recipe" else "Edit recipe", back) {
        LibraryField(name, { name = it }, "Name", error = if (name.isBlank()) "Enter a recipe name." else null)
        LibraryField(dose, { dose = it }, "Coffee dose, g", KeyboardType.Decimal, error = if (doseValue == null) "Enter a positive amount with up to one decimal place." else null)
        LibraryField(ratio, { ratio = it }, "Brew ratio, 1 :", KeyboardType.Decimal, error = if (ratioValue == null) "Enter a positive ratio denominator." else null)
        if (ratioValue != null) Text("1 g coffee to ${formatAmount(ratioValue)} g water", style = MaterialTheme.typography.bodySmall)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Total brew water", style = MaterialTheme.typography.labelLarge)
                Text(target?.let { "${formatAmount(it)} g" } ?: "Enter dose and ratio", style = MaterialTheme.typography.headlineSmall)
            }
        }
        Text("Pulses", style = MaterialTheme.typography.titleLarge)
        var cumulative = 0.0
        var elapsed = 0
        pulses.forEachIndexed { index, pulse ->
            val currentWater = waterValues[index]
            val cumulativeValid = waterValues.take(index + 1).all { it != null }
            cumulative += currentWater ?: 0.0
            val start = elapsed
            elapsed += (pulse.pour.toIntOrNull() ?: 0) + if (index < pulses.lastIndex) (pulse.wait.toIntOrNull() ?: 0) else 0
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Pulse ${index + 1} · ${formatDuration(start)}", style = MaterialTheme.typography.titleMedium)
                    LibraryField(pulse.label, { replace(index, pulse.copy(label = it)) }, "Label, optional")
                    LibraryField(pulse.water, { replace(index, pulse.copy(water = it)) }, "Water to add, g", KeyboardType.Decimal, error = if (currentWater == null) "Enter a positive amount with up to one decimal place." else null)
                    if (cumulativeValid) Text("Add ${formatAmount(currentWater!!)} g · Pour to ${formatAmount(roundedAmount(cumulative))} g total")
                    LibraryField(pulse.pour, { replace(index, pulse.copy(pour = it)) }, "Pour duration, seconds", KeyboardType.Number, error = if (pulse.pour.toIntOrNull()?.let { it > 0 } != true) "Enter a positive whole number of seconds." else null)
                    if (index < pulses.lastIndex) LibraryField(pulse.wait, { replace(index, pulse.copy(wait = it)) }, "Wait after pouring, seconds", KeyboardType.Number, error = if (pulse.wait.toIntOrNull()?.let { it >= 0 } != true) "Enter zero or a positive whole number." else null)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = index > 0, onClick = {
                            reorderOrRemove(pulses.toMutableList().also { val moved = it.removeAt(index); it.add(index - 1, moved) })
                        }) { Text("Move pulse ${index + 1} up") }
                        TextButton(enabled = index < pulses.lastIndex, onClick = {
                            reorderOrRemove(pulses.toMutableList().also { val moved = it.removeAt(index); it.add(index + 1, moved) })
                        }) { Text("Move pulse ${index + 1} down") }
                        TextButton(enabled = pulses.size > 1, onClick = { reorderOrRemove(pulses.filterIndexed { pulseIndex, _ -> pulseIndex != index }) }) { Text("Remove pulse ${index + 1}") }
                    }
                }
            }
        }
        OutlinedButton(onClick = {
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
        Text("Scheduled pours end at ${formatDuration(elapsed)}", style = MaterialTheme.typography.bodyMedium)
        LibraryField(notes, { notes = it }, "Preparation notes, optional", multiline = true)
        Button(enabled = valid, onClick = {
            val recipe = original.copy(id = id, name = name.trim(), dose = doseValue!!, ratio = ratioValue!!, notes = notes, pulses = pulses.mapIndexed { index, pulse -> Pulse(pulse.label, pulse.water.toDouble(), pulse.pour.toInt(), if (index == pulses.lastIndex) 0 else pulse.wait.toInt()) })
            if (onSave(recipe)) onBack()
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Save recipe") }
    }
    DiscardLibraryDialog(discard, { discard = false }, onBack)
}

private fun positiveDecimal(value: String): Double? = value.toDoubleOrNull()?.takeIf {
    value.matches(Regex("[0-9]+(?:\\.[0-9])?")) && it.isFinite() && it > 0
}

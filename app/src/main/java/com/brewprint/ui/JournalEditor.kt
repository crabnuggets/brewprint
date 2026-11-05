package com.brewprint.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brewprint.data.Journal

@Composable
fun JournalEditor(journal: Journal, expectedNotes: String, onChange: (Journal) -> Unit) {
    var explore by rememberSaveable { mutableStateOf(false) }
    var cooling by rememberSaveable { mutableStateOf(journal.asItCools.isNotBlank()) }
    var roaster by rememberSaveable { mutableStateOf(false) }
    Text("How was this cup?", style = MaterialTheme.typography.titleLarge)
    Text("You can taste first and add notes later.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(journal.impression, { onChange(journal.copy(impression = it)) }, Modifier.fillMaxWidth(), label = { Text("Your notes") }, placeholder = { Text("What did you notice? Everyday words are welcome.") }, minLines = 4, shape = MaterialTheme.shapes.medium, colors = softFieldColors())
    DisclosureRow(if (explore) "Hide taste exploration" else "Explore the taste", explore) { explore = !explore }
    if (explore) {
        JournalText("Aroma", "What do you smell? Try floral, toasted nuts, or fruit.", journal.aroma) { onChange(journal.copy(aroma = it)) }
        Text("Flavor", style = MaterialTheme.typography.titleMedium)
        Text("What does it remind you of?", style = MaterialTheme.typography.bodyMedium)
        Tags(listOf("Fruity", "Floral", "Chocolatey", "Nutty", "Spicy"), journal.flavorTags) { onChange(journal.copy(flavorTags = it)) }
        JournalText("Flavor in your words", "Broad descriptions are enough.", journal.flavor) { onChange(journal.copy(flavor = it)) }
        Intensity("Sweetness", "How much sweetness do you notice?", journal.sweetness, journal.sweetnessNotes, { onChange(journal.copy(sweetness = it)) }, { onChange(journal.copy(sweetnessNotes = it)) })
        Intensity("Acidity", "How bright or tangy does it taste? This describes a sensation, not pH or quality.", journal.acidity, journal.acidityNotes, { onChange(journal.copy(acidity = it)) }, { onChange(journal.copy(acidityNotes = it)) })
        Intensity("Bitterness", "How much bitterness do you notice?", journal.bitterness, journal.bitternessNotes, { onChange(journal.copy(bitterness = it)) }, { onChange(journal.copy(bitternessNotes = it)) })
        Text("Body", style = MaterialTheme.typography.titleMedium)
        Text("How light or weighty does it feel in your mouth?", style = MaterialTheme.typography.bodyMedium)
        Choice(listOf("Light", "Medium", "Full"), journal.body) { onChange(journal.copy(body = it)) }
        Text("Mouthfeel", style = MaterialTheme.typography.titleMedium)
        Text("What is the texture like?", style = MaterialTheme.typography.bodyMedium)
        Tags(listOf("Silky", "Creamy", "Syrupy", "Drying"), journal.mouthfeelTags) { onChange(journal.copy(mouthfeelTags = it)) }
        JournalText("Mouthfeel in your words", "Describe the texture separately from its weight.", journal.mouthfeel) { onChange(journal.copy(mouthfeel = it)) }
        JournalText("Finish / aftertaste", "What lingers after swallowing?", journal.finish) { onChange(journal.copy(finish = it)) }
        Choice(listOf("Short", "Medium", "Long"), journal.finishLength) { onChange(journal.copy(finishLength = it)) }
    }
    Text("Next time", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(journal.nextTime, { onChange(journal.copy(nextTime = it)) }, Modifier.fillMaxWidth(), label = { Text("Reminder") }, placeholder = { Text("Try one tick finer") }, minLines = 2, shape = MaterialTheme.shapes.medium, colors = softFieldColors())
    DisclosureRow("As it cools", cooling) { cooling = !cooling }
    if (cooling) JournalText("As it cools", "Did anything change as you kept drinking?", journal.asItCools) { onChange(journal.copy(asItCools = it)) }
    DisclosureRow("View roaster's notes", roaster) { roaster = !roaster }
    if (roaster) Panel {
        Text("Roaster's expected tasting notes", style = MaterialTheme.typography.titleMedium)
        Text(expectedNotes.ifBlank { "No expected tasting notes recorded." })
        Text("Your notes don't need to match the bag.", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun JournalText(label: String, help: String, value: String, onChange: (String) -> Unit) {
    LibraryField(value, onChange, "$label, optional", multiline = true, helper = help)
}

@Composable
private fun Intensity(label: String, help: String, value: String?, notes: String, onValue: (String?) -> Unit, onNotes: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleMedium)
    Text(help, style = MaterialTheme.typography.bodyMedium)
    Choice(listOf("Low", "Medium", "High"), value, onValue)
    LibraryField(notes, onNotes, "$label in your words, optional")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choice(options: List<String>, value: String?, onChange: (String?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option -> FilterChip(selected = value == option, onClick = { onChange(if (value == option) null else option) }, label = { Text(option) }) }
        if (value != null) TextButton(onClick = { onChange(null) }) { Text("Clear") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tags(options: List<String>, values: List<String>, onChange: (List<String>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option -> FilterChip(selected = option in values, onClick = { onChange(if (option in values) values - option else values + option) }, label = { Text(option) }) }
    }
}

@Composable
fun JournalSummary(journal: Journal) {
    if (journal.impression.isNotBlank()) Text(journal.impression, style = MaterialTheme.typography.bodyLarge)
    listOf(
        "Aroma" to journal.aroma,
        "Flavor" to (journal.flavorTags + journal.flavor).filter { it.isNotBlank() }.joinToString(", "),
        "Sweetness" to listOfNotNull(journal.sweetness, journal.sweetnessNotes.takeIf { it.isNotBlank() }).joinToString(" · "),
        "Acidity" to listOfNotNull(journal.acidity, journal.acidityNotes.takeIf { it.isNotBlank() }).joinToString(" · "),
        "Bitterness" to listOfNotNull(journal.bitterness, journal.bitternessNotes.takeIf { it.isNotBlank() }).joinToString(" · "),
        "Body" to journal.body.orEmpty(),
        "Mouthfeel" to (journal.mouthfeelTags + journal.mouthfeel).filter { it.isNotBlank() }.joinToString(", "),
        "Finish / aftertaste" to listOfNotNull(journal.finish.takeIf { it.isNotBlank() }, journal.finishLength).joinToString(" · "),
        "As it cools" to journal.asItCools,
        "Next time" to journal.nextTime,
    ).filter { it.second.isNotBlank() }.forEach { (label, value) ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value)
        }
    }
}

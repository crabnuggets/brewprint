package com.brewprint.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.brewprint.data.Journal

@Composable
fun JournalEditor(journal: Journal, expectedNotes: String, exploring: Boolean = false, onChange: (Journal) -> Unit) {
    var explore by rememberSaveable { mutableStateOf(exploring) }
    var cooling by rememberSaveable { mutableStateOf(journal.asItCools.isNotBlank()) }
    var roaster by rememberSaveable { mutableStateOf(false) }
    // A correction from Claude that fills in more fields opens them, so the user sees it before saving.
    LaunchedEffect(exploring) { if (exploring) explore = true }
    LaunchedEffect(journal.asItCools.isNotBlank()) { if (journal.asItCools.isNotBlank()) cooling = true }
    SectionTitle("How was this cup?")
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
        Choice("Body", listOf("Light", "Medium", "Full"), journal.body) { onChange(journal.copy(body = it)) }
        Text("Mouthfeel", style = MaterialTheme.typography.titleMedium)
        Text("What is the texture like?", style = MaterialTheme.typography.bodyMedium)
        Tags(listOf("Silky", "Creamy", "Syrupy", "Drying"), journal.mouthfeelTags) { onChange(journal.copy(mouthfeelTags = it)) }
        JournalText("Mouthfeel in your words", "Describe the texture separately from its weight.", journal.mouthfeel) { onChange(journal.copy(mouthfeel = it)) }
        JournalText("Finish / aftertaste", "What lingers after swallowing?", journal.finish) { onChange(journal.copy(finish = it)) }
        Choice("Finish", listOf("Short", "Medium", "Long"), journal.finishLength) { onChange(journal.copy(finishLength = it)) }
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
    Choice(label, listOf("Low", "Medium", "High"), value, onValue)
    LibraryField(notes, onNotes, "$label in your words, optional")
}

/** Whether three choices fit side by side. On a narrow screen or with large text they stack, and tags drop to two columns. */
@Composable
private fun roomy(): Boolean = LocalConfiguration.current.let { it.screenWidthDp / it.fontScale >= 300 }

/**
 * One choice of a few, or none: a soft track whose filled block slides to the choice. Tapping the choice again clears
 * it, as does the "×" that appears beside the track while something is chosen. [name] names the group in "Clear …".
 */
@Composable
private fun Choice(name: String, options: List<String>, value: String?, onChange: (String?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val side = roomy()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val radius = if (LocalLook.current.blueprint) 6.dp else 12.dp
    val ease = CubicBezierEasing(.2f, .7f, .2f, 1f)
    val index = options.indexOf(value)
    val spot = remember { Animatable(maxOf(index, 0).toFloat()) }
    val shown by animateFloatAsState(if (index >= 0) 1f else 0f, tween(150), label = "choice shown")
    val last = remember { intArrayOf(index) }
    LaunchedEffect(index) {
        // With nothing chosen the block has nowhere to slide from, so it fades in where it was tapped.
        if (index >= 0) { if (last[0] < 0) spot.snapTo(index.toFloat()) else spot.animateTo(index.toFloat(), tween(280, easing = ease)) }
        last[0] = index
    }
    val track = Modifier.background(colors.soft, MaterialTheme.shapes.small).drawBehind {
        val gap = 4.dp.toPx()
        val length = if (side) size.width else size.height
        val segment = (length - gap * (options.size + 1)) / options.size
        val along = gap + spot.value * (segment + gap)
        val at = if (side && rtl) size.width - along - segment else along
        drawRoundRect(colors.primary, if (side) Offset(at, gap) else Offset(gap, at),
            if (side) Size(segment, size.height - 2 * gap) else Size(size.width - 2 * gap, segment), CornerRadius(radius.toPx()), alpha = shown)
    }.padding(4.dp).selectableGroup()
    @Composable fun segment(option: String, modifier: Modifier) {
        val on = option == value
        // The label takes the fill's contrasting color only as far as the block covers it, so it stays readable mid-slide.
        val covered = (1f - kotlin.math.abs(spot.value - options.indexOf(option))).coerceIn(0f, 1f) * shown
        val ink = lerp(colors.onSurface, colors.onPrimary, covered)
        Box(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(radius)).selectable(on, role = Role.RadioButton) { onChange(if (on) null else option) }.padding(horizontal = 8.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(option, color = ink, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
    Row(verticalAlignment = if (side) Alignment.CenterVertically else Alignment.Top) {
        if (side) Row(Modifier.weight(1f).then(track), horizontalArrangement = Arrangement.spacedBy(4.dp)) { options.forEach { segment(it, Modifier.weight(1f)) } }
        else Column(Modifier.weight(1f).then(track), verticalArrangement = Arrangement.spacedBy(4.dp)) { options.forEach { segment(it, Modifier.fillMaxWidth()) } }
        // The track runs full width until something is chosen, then narrows to make room for the clear action.
        AnimatedVisibility(value != null, enter = expandHorizontally(tween(300, easing = ease)) + fadeIn(tween(200)), exit = shrinkHorizontally(tween(300, easing = ease)) + fadeOut(tween(200))) {
            Box(Modifier.width(48.dp).heightIn(min = 52.dp), contentAlignment = Alignment.CenterEnd) { IconAction(Icons.Outlined.Close, "Clear ${name.lowercase()}") { onChange(null) } }
        }
    }
}

/** Any number of choices: separate blocks in even columns, filled when on. */
@Composable
private fun Tags(options: List<String>, values: List<String>, onChange: (List<String>) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val columns = if (!roomy()) 2 else if (options.size <= 4) options.size else 3
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { option ->
                    val on = option in values
                    val fill by animateColorAsState(if (on) colors.primary else colors.soft, tween(200), label = "tag fill")
                    val ink by animateColorAsState(if (on) colors.onPrimary else colors.onSurface, tween(200), label = "tag ink")
                    Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(MaterialTheme.shapes.small).background(fill).toggleable(on, role = Role.Checkbox) { onChange(if (on) values - option else values + option) }.padding(horizontal = 6.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                        Text(option, color = ink, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun JournalSummary(journal: Journal) {
    if (journal.impression.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("How was this cup?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(journal.impression, style = LocalLook.current.hand(MaterialTheme.typography.bodyLarge))
    }
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
    ).filter { it.second.isNotBlank() }.forEach { (label, value) ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // The user's own words, in pencil in the blueprint.
            Text(value, style = LocalLook.current.hand(LocalTextStyle.current))
        }
    }
    // "Next time" closes the journal in every theme, set apart by a rule: a hairline, or the blueprint's double rule.
    if (journal.nextTime.isNotBlank()) {
        val others = journal.copy(nextTime = "") != Journal()
        if (others) Column(Modifier.bleed(20.dp)) { HeadRule() }
        NextTimeLine(journal.nextTime)
    }
}

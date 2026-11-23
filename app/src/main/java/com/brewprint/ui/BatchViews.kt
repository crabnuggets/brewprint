package com.brewprint.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.brewprint.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** "Roasted 10 Nov", with the year when it isn't this one. */
internal fun roastedLabel(roastDate: String, today: LocalDate = LocalDate.now()): String {
    val date = runCatching { LocalDate.parse(roastDate) }.getOrNull() ?: return "Roasted $roastDate"
    return "Roasted " + date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "d MMM" else "d MMM uuuu"))
}

/** "Roasted 10 Nov · 12 days ago", counted on this phone's calendar; no count for a date after today. */
internal fun roastLine(roastDate: String?, today: LocalDate = LocalDate.now()): String? = roastDate?.let { date ->
    roastedLabel(date, today) + (daysOffRoast(date, today)?.let { " · " + when (it) { 0 -> "today"; 1 -> "1 day ago"; else -> "$it days ago" } } ?: "")
}

/** "12 days off roast" on the day of a brew. */
internal fun offRoast(roastDate: String?, brewedAt: Long): String? =
    daysOffRoast(roastDate, Instant.ofEpochMilli(brewedAt).atZone(ZoneId.systemDefault()).toLocalDate())?.let { if (it == 1) "1 day off roast" else "$it days off roast" }

/** A batch's name in a list: its roast date, or what the bag is when it has none. */
internal fun batchName(batch: BeanBatch): String = batch.roastDate?.let { roastedLabel(it) } ?: batch.notes.ifBlank { "No roast date" }

/** Picks a roast date up to today, or none. The date is a calendar day, kept in UTC so it never shifts a day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoastDateDialog(initial: String?, confirm: String, onConfirm: (String?) -> Unit, onDismiss: () -> Unit) {
    val today = LocalDate.now()
    val picker = rememberDatePickerState(
        initialSelectedDateMillis = initial?.let { runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull() },
        yearRange = 1990..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
            override fun isSelectableYear(year: Int) = year <= today.year
        },
    )
    val chosen = picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
    DatePickerDialog(onDismissRequest = onDismiss,
        confirmButton = { SoftTextButton(onClick = { onConfirm(chosen) }) { Text(confirm) } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (chosen != null) SoftTextButton(onClick = { picker.selectedDateMillis = null }) { Text("Clear") }
                SoftTextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }) {
        DatePicker(picker, title = { Text("Roast date", Modifier.padding(start = 24.dp, top = 16.dp)) })
    }
}

/** The form's roast date: its value, with a way to set, change or clear it. */
@Composable
internal fun RoastDateField(value: String?, onChange: (String?) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Roast date, optional", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Text(value?.let { roastedLabel(it).removePrefix("Roasted ") } ?: "Not set", style = MaterialTheme.typography.bodyLarge)
        }
        SoftTextButton(onClick = { picking = true }) { Text(if (value == null) "Set roast date" else "Change roast date") }
    }
    if (picking) RoastDateDialog(value, "Done", { onChange(it); picking = false }) { picking = false }
}

/** Chooses which bag of [beans] a brew uses, newest first. */
@Composable
internal fun BatchPickerDialog(beans: Beans, selectedId: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose a batch") }, text = {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            beans.batchesNewestFirst.forEach { batch ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(batch.id == selectedId, role = Role.RadioButton) { onPick(batch.id) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = batch.id == selectedId, onClick = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(batchName(batch))
                        daysOffRoast(batch.roastDate, LocalDate.now())?.let { Text(if (it == 1) "1 day ago" else "$it days ago", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }, confirmButton = { SoftTextButton(onClick = onDismiss) { Text("Close") } })
}

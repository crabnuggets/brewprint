package com.brewprint.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brewprint.data.*

/** A recipe's optional details in short phrases, such as "Iced, 70 g ice", "91 °C", "Target finish 2:10" and "Medium-fine". */
internal fun recipeFacts(recipe: Recipe): List<String> = listOfNotNull(
    if (recipe.drinkStyle == DrinkStyle.ICED) "Iced" + (recipe.iceGrams?.let { ", ${formatAmount(it)} g ice" } ?: "") else null,
    recipe.waterTempC?.let { "$it °C" },
    recipe.targetFinishSeconds?.let { "Target finish ${formatDuration(it)}" },
    recipe.grindDescription.takeIf { it.isNotBlank() },
)

/** The planned water and ratio, with the ice an iced recipe adds. */
internal fun waterCell(recipe: Recipe): Cell {
    val water = formatAmount(recipe.totalWater)
    val ice = recipe.iceGrams?.takeIf { recipe.drinkStyle == DrinkStyle.ICED }
    return Cell("Water", water, if (ice != null) "g + ${formatAmount(ice)} g ice" else "g", "Total brew water $water grams" + (ice?.let { ", plus ${formatAmount(it)} grams of ice" } ?: ""))
}

internal fun ratioCell(recipe: Recipe): Cell {
    val ratio = formatRatio(recipe.computedRatio)
    val overall = recipe.overallRatio?.let(::formatRatio)
    return Cell("Ratio", "1:$ratio", overall?.let { "1:$it with ice" }.orEmpty(), "Brew ratio 1 to $ratio" + (overall?.let { ", 1 to $it with ice" } ?: ""))
}

/** Style, and the details a recipe gives, as labelled cells. */
internal fun recipeDetailCells(recipe: Recipe): List<Cell> = listOfNotNull(
    Cell("Style", if (recipe.drinkStyle == DrinkStyle.ICED) "Iced" else "Hot"),
    recipe.waterTempC?.let { Cell("Water temp", "$it", "°C", "Water temperature $it degrees Celsius") },
    recipe.targetFinishSeconds?.let { Cell("Target finish", formatDuration(it), spoken = "Target finish ${spokenStart(it).removePrefix("starts ")}") },
    recipe.grindDescription.takeIf { it.isNotBlank() }?.let { Cell("Grind size", it) },
)

/** A step's note, such as "Stir gently 3 times", beside a note icon. */
@Composable
internal fun StepNote(text: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.StickyNote2, null, Modifier.padding(top = 2.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium.let { if (strong) it.copy(fontWeight = FontWeight.SemiBold) else it })
    }
}

/** The recipe's source, opened in the browser. Only http and https addresses open. */
@Composable
internal fun SourceLink(url: String) {
    if (!webAddress(url)) return
    val context = LocalContext.current
    val address = url.trim()
    val host = runCatching { java.net.URI(address).host.removePrefix("www.") }.getOrDefault(address)
    SoftTextButton(onClick = {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) { }
    }, modifier = Modifier.semantics { contentDescription = "Open the recipe's source, $host" }) {
        Text(host)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
    }
}

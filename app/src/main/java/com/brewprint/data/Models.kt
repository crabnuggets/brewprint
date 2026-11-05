package com.brewprint.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlinx.serialization.Serializable

fun newId(): String = UUID.randomUUID().toString()

const val MIN_GRIND_SETTING = 0
const val MAX_GRIND_SETTING = 100

fun parseGrindSetting(text: String): Int? =
    text.trim().toIntOrNull()?.takeIf { it in MIN_GRIND_SETTING..MAX_GRIND_SETTING }

@Serializable
data class Beans(
    val id: String = newId(),
    val name: String = "",
    val roaster: String = "",
    val origin: String = "",
    val variety: String = "",
    val processing: String = "",
    val expectedNotes: String = "",
    val notes: String = "",
    val archived: Boolean = false,
)

@Serializable enum class DialFinish { METAL, MATTE, GLASS }
@Serializable enum class Appearance { SYSTEM, LIGHT, DARK }
@Serializable enum class DraftStage { SETUP, SHEET, REVIEW }

@Serializable
data class Grinder(
    val id: String = newId(),
    val name: String = "",
    val lastSetting: Int? = null,
    val referenceNotes: String = "",
    val finish: DialFinish = DialFinish.METAL,
    val archived: Boolean = false,
)

@Serializable
data class Pulse(
    val label: String = "",
    val water: Double = 0.0,
    val pourSeconds: Int = 0,
    val waitSeconds: Int = 0,
)

@Serializable
data class Recipe(
    val id: String = newId(),
    val name: String = "",
    val dose: Double = 0.0,
    val ratio: Double = 0.0,
    val pulses: List<Pulse> = listOf(Pulse()),
    val notes: String = "",
    val archived: Boolean = false,
) {
    val totalWater: Double get() = totalBrewWater(dose, ratio)
    val plannedWater: Double get() = roundedAmount(pulses.sumOf { it.water })

    fun validationErrors(): List<String> = buildList {
        if (name.isBlank()) add("Enter a recipe name.")
        if (!dose.isFinite() || dose <= 0 || !oneDecimal(dose)) {
            add("Coffee dose must be positive with up to one decimal place.")
        }
        if (!ratio.isFinite() || ratio <= 0 || !totalWater.isFinite() || totalWater <= 0) {
            add("Enter a positive brew ratio.")
        }
        if (pulses.isEmpty()) add("Add at least one pulse.")
        pulses.forEachIndexed { index, pulse ->
            if (!pulse.water.isFinite() || pulse.water <= 0 || !oneDecimal(pulse.water)) {
                add("Pulse ${index + 1}: water must be positive with up to one decimal place.")
            }
            if (pulse.pourSeconds <= 0) add("Pulse ${index + 1}: pour duration must be positive.")
            if (pulse.waitSeconds < 0) add("Pulse ${index + 1}: wait cannot be negative.")
            if (index == pulses.lastIndex && pulse.waitSeconds != 0) {
                add("The last pulse cannot have a wait.")
            }
        }
        if (plannedWater.isFinite() && totalWater.isFinite() && plannedWater != totalWater) {
            val difference = roundedAmount(totalWater - plannedWater)
            add(if (difference > 0) {
                "Add ${formatAmount(difference)} g across your pulses to reach ${formatAmount(totalWater)} g."
            } else {
                "Remove ${formatAmount(-difference)} g across your pulses to reach ${formatAmount(totalWater)} g."
            })
        }
    }

    fun schedule(): List<ScheduledPulse> {
        var start = 0
        var water = 0.0
        return pulses.map { pulse ->
            water = roundedAmount(water + pulse.water)
            ScheduledPulse(pulse, start, water).also {
                start += pulse.pourSeconds + pulse.waitSeconds
            }
        }
    }
}

data class ScheduledPulse(val pulse: Pulse, val startSeconds: Int, val cumulativeWater: Double)

fun totalBrewWater(dose: Double, ratio: Double): Double = if (dose.isFinite() && ratio.isFinite()) {
    BigDecimal.valueOf(dose).multiply(BigDecimal.valueOf(ratio)).setScale(1, RoundingMode.HALF_UP).toDouble()
} else dose * ratio

fun roundedAmount(value: Double): Double = if (value.isFinite()) {
    BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toDouble()
} else value

private fun oneDecimal(value: Double): Boolean = roundedAmount(value) == value

fun formatAmount(value: Double): String = if (value.isFinite()) {
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
} else "0"

fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

@Serializable
data class Journal(
    val impression: String = "",
    val aroma: String = "",
    val flavorTags: List<String> = emptyList(),
    val flavor: String = "",
    val sweetness: String? = null,
    val sweetnessNotes: String = "",
    val acidity: String? = null,
    val acidityNotes: String = "",
    val bitterness: String? = null,
    val bitternessNotes: String = "",
    val body: String? = null,
    val mouthfeelTags: List<String> = emptyList(),
    val mouthfeel: String = "",
    val finish: String = "",
    val finishLength: String? = null,
    val asItCools: String = "",
    val nextTime: String = "",
) {
    val isEmpty: Boolean get() = this == Journal()
}

@Serializable
data class BrewRecord(
    val id: String = newId(),
    val startedAt: Long = System.currentTimeMillis(),
    val beans: Beans,
    val grinder: Grinder,
    val setting: Int,
    val recipe: Recipe,
    val journal: Journal = Journal(),
)

@Serializable
data class BrewDraft(
    val id: String = newId(),
    val startedAt: Long = System.currentTimeMillis(),
    val beans: Beans? = null,
    val grinder: Grinder? = null,
    val setting: Int? = null,
    val recipe: Recipe? = null,
    val stage: DraftStage = DraftStage.SETUP,
    val journal: Journal = Journal(),
    val allowArchivedBeans: Boolean = false,
)

@Serializable
data class NoteEdit(val brewId: String, val journal: Journal)

@Serializable
data class AppState(
    val beans: List<Beans> = emptyList(),
    val grinders: List<Grinder> = emptyList(),
    val recipes: List<Recipe> = emptyList(),
    val brews: List<BrewRecord> = emptyList(),
    val draft: BrewDraft? = null,
    val noteEdits: List<NoteEdit> = emptyList(),
    val defaultGrinderId: String? = null,
    val lastBeansId: String? = null,
    val lastRecipeId: String? = null,
    val appearance: Appearance = Appearance.SYSTEM,
)

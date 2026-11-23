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
    /** Each bag bought, kept in the Library only; a brew records the one it used. */
    val batches: List<BeanBatch> = emptyList(),
)

@Serializable enum class DialFinish { METAL, MATTE, GLASS }
@Serializable enum class Appearance { SYSTEM, LIGHT, DARK }
/** The app's look. Monochrome is the default; Blueprint is an experiment. Both offer the same features. */
@Serializable enum class Theme { MONOCHROME, BLUEPRINT }

/** Opt-in features that may change or be removed. */
@Serializable
data class Experiments(val theme: Theme = Theme.MONOCHROME)
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
    /** What to do during this step, such as "Stir gently 3 times". Optional. */
    val notes: String = "",
)

/** Whether a recipe brews hot coffee, or onto ice. */
@Serializable enum class DrinkStyle { HOT, ICED }

@Serializable
data class Recipe(
    val id: String = newId(),
    val name: String = "",
    val dose: Double = 0.0,
    val pulses: List<Pulse> = listOf(Pulse()),
    val notes: String = "",
    val archived: Boolean = false,
    val drinkStyle: DrinkStyle = DrinkStyle.HOT,
    /** Ice in the server before brewing, for an iced recipe only. Never a scale target. */
    val iceGrams: Double? = null,
    val waterTempC: Int? = null,
    /** When the drawdown should end, counted from the first pour. */
    val targetFinishSeconds: Int? = null,
    /** How the grind should look, such as "Medium-fine", alongside the grinder's ticks. */
    val grindDescription: String = "",
    val sourceUrl: String = "",
) {
    /** The hot water poured: the pulses' total, rounded to 0.1 g. */
    val totalWater: Double get() = roundedAmount(pulses.sumOf { it.water })

    /** Grams of water per gram of coffee, from the dose and the pours; 0 when either is missing. */
    val computedRatio: Double get() = derivedRatio(dose, pulses)

    /** For an iced recipe, water and ice together per gram of coffee; null otherwise. */
    val overallRatio: Double? get() = iceGrams?.takeIf { drinkStyle == DrinkStyle.ICED && it > 0 && dose > 0 }?.let {
        BigDecimal.valueOf(totalWater).add(BigDecimal.valueOf(it)).divide(BigDecimal.valueOf(dose), 6, RoundingMode.HALF_UP).toDouble()
    }

    /** The recipe as it is saved: no ice on a hot recipe. */
    fun normalized(): Recipe = copy(iceGrams = iceGrams.takeIf { drinkStyle == DrinkStyle.ICED })

    fun validationErrors(): List<String> = buildList {
        if (name.isBlank()) add("Enter a recipe name.")
        if (!dose.isFinite() || dose <= 0 || !oneDecimal(dose)) {
            add("Coffee dose must be positive with up to one decimal place.")
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
        if (drinkStyle == DrinkStyle.ICED && iceGrams != null && (!iceGrams.isFinite() || iceGrams <= 0 || !oneDecimal(iceGrams))) {
            add("Ice must be positive with up to one decimal place.")
        }
        if (waterTempC != null && waterTempC !in 1..100) add("Water temperature must be from 1 to 100 °C.")
        if (targetFinishSeconds != null && targetFinishSeconds <= 0) add("Target finish time must be positive.")
        if (sourceUrl.isNotBlank() && !webAddress(sourceUrl)) add("Enter a web address starting with http:// or https://.")
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

/** [pulses]' water over [dose], rounded half up to 6 decimals in decimal arithmetic; [formatRatio] shows it to one. */
fun derivedRatio(dose: Double, pulses: List<Pulse>): Double {
    val water = roundedAmount(pulses.sumOf { it.water })
    if (!dose.isFinite() || dose <= 0 || !water.isFinite() || water <= 0) return 0.0
    return BigDecimal.valueOf(water).divide(BigDecimal.valueOf(dose), 6, RoundingMode.HALF_UP).toDouble()
}

/** An http or https address with a host, the only kind the app opens. */
fun webAddress(text: String): Boolean = runCatching {
    val uri = java.net.URI(text.trim())
    uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
}.getOrDefault(false)

data class ScheduledPulse(val pulse: Pulse, val startSeconds: Int, val cumulativeWater: Double)

fun roundedAmount(value: Double): Double = if (value.isFinite()) {
    BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toDouble()
} else value

private fun oneDecimal(value: Double): Boolean = roundedAmount(value) == value

fun formatAmount(value: Double): String = if (value.isFinite()) {
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
} else "0"

/** A ratio's denominator for display: half up to one decimal, without a trailing ".0", the same in every locale. */
fun formatRatio(value: Double): String = if (value.isFinite()) {
    BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
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
    val timing: BrewTiming? = null,
    val scale: ScaleData? = null,
    /** The batch of the beans used, and its roast date as it was. */
    val batchId: String? = null,
    val roastDate: String? = null,
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
    val timer: BrewTimer? = null,
    /** Set once the scale is used for this brew; null for a brew timed by the clock alone. */
    val scale: ScaleData? = null,
    val scaleStep: ScaleStep = ScaleStep.BREWER,
    /** "Brew without the scale" was chosen for this brew. */
    val withoutScale: Boolean = false,
    val batchId: String? = null,
    val roastDate: String? = null,
    /** For a repeat that moved to the newest batch, the roast date of the batch the original brew used; empty when it had none. */
    val repeatedFromRoastDate: String? = null,
)

@Serializable
data class NoteEdit(val brewId: String, val journal: Journal)

/**
 * Beans or a recipe Claude drafted, waiting in the Library for the user to add or discard. Exactly one of [beans] and
 * [recipe] is set, with this draft's [id]. The account holds drafts; this phone keeps the latest copy it fetched.
 */
@Serializable
data class LibraryDraft(
    val id: String,
    val beans: Beans? = null,
    val recipe: Recipe? = null,
    /** The draft's JSON when it has fields this version doesn't know, kept for the item it becomes once added. */
    val raw: kotlinx.serialization.json.JsonObject? = null,
)

/**
 * Tasting notes Claude drafted for the brew with [brewId], waiting on that brew for the user to save or discard. The
 * account holds the draft under the brew's id; this phone keeps the latest copy it fetched.
 */
@Serializable
data class JournalDraft(val brewId: String, val journal: Journal)

@Serializable
data class AppState(
    val beans: List<Beans> = emptyList(),
    val grinders: List<Grinder> = emptyList(),
    val recipes: List<Recipe> = emptyList(),
    val brews: List<BrewRecord> = emptyList(),
    val draft: BrewDraft? = null,
    val noteEdits: List<NoteEdit> = emptyList(),
    val libraryDrafts: List<LibraryDraft> = emptyList(),
    val journalDrafts: List<JournalDraft> = emptyList(),
    val defaultGrinderId: String? = null,
    val lastBeansId: String? = null,
    /** The batch of [lastBeansId] used last, chosen again for the next brew with those beans. */
    val lastBatchId: String? = null,
    val lastRecipeId: String? = null,
    val appearance: Appearance = Appearance.SYSTEM,
    val experiments: Experiments = Experiments(),
    val timerSettings: TimerSettings = TimerSettings(),
    val scale: PairedScale? = null,
    /** "Not now" on the pairing card hides it for good. */
    val scaleOfferDismissed: Boolean = false,
    val sync: SyncState = SyncState(),
)

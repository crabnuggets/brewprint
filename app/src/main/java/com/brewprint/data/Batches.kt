package com.brewprint.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable

/**
 * One bag of [Beans] bought at a time. [roastDate] is an ISO date ("2026-11-10") when the bag gives one.
 * [addedAt] orders batches by purchase; 0 is the bag the beans were first added with, before batches existed.
 */
@Serializable
data class BeanBatch(
    val id: String = newId(),
    val roastDate: String? = null,
    val notes: String = "",
    val addedAt: Long = 0L,
) {
    companion object {
        /** A batch bought after every batch [beans] already has, even if this phone's clock is behind another's. */
        fun create(beans: Beans, roastDate: String?, now: Long = System.currentTimeMillis()) =
            BeanBatch(roastDate = roastDate, addedAt = maxOf(now, (beans.batches.maxOfOrNull { it.addedAt } ?: 0L) + 1))
    }
}

/** Batches in purchase order, newest first; of two added at the same moment, the later in the list is newer. */
val Beans.batchesNewestFirst: List<BeanBatch>
    get() = batches.withIndex().sortedWith(compareByDescending<IndexedValue<BeanBatch>> { it.value.addedAt }.thenByDescending { it.index }).map { it.value }

val Beans.newestBatch: BeanBatch? get() = batchesNewestFirst.firstOrNull()

/**
 * The brew with these beans, which a brew keeps without their batches. The batch is [batchId] when it is one of
 * theirs, else the one used last ([remembered]), else the newest.
 */
fun BrewDraft.withBeans(beans: Beans, remembered: String? = null, batchId: String? = null): BrewDraft {
    val batch = beans.batches.find { it.id == batchId } ?: beans.batches.find { it.id == remembered } ?: beans.newestBatch
    return copy(beans = beans.copy(batches = emptyList()), batchId = batch?.id, roastDate = batch?.roastDate, repeatedFromRoastDate = null)
}

/** Whole days from [roastDate] to [on], or null when there is no date or it is after [on]. */
fun daysOffRoast(roastDate: String?, on: LocalDate): Int? =
    roastDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { ChronoUnit.DAYS.between(it, on).toInt() }?.takeIf { it >= 0 }

/** An ISO date that is not in the future, as the roast date field allows. */
fun roastDateValid(text: String?, today: LocalDate = LocalDate.now()): Boolean =
    text == null || runCatching { LocalDate.parse(text) }.getOrNull()?.let { !it.isAfter(today) } == true

package com.brewprint.scale

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkTimingTest {
    private val lines = mutableListOf<String>()
    private val timing = LinkTiming(every = 3) { lines += it }

    @Test
    fun sumsUpTheGapsAndTheWaitOnceEveryFewReadings() {
        timing.add(1_000, 1_002)
        timing.add(1_100, 1_140)
        assertEquals(emptyList<String>(), lines)
        timing.add(1_350, 1_351)
        assertEquals(listOf("Scale readings: 3 in 350 ms, longest gap 250 ms, longest wait for the main thread 40 ms"), lines)
    }

    @Test
    fun theNextSummaryCountsFromTheLastReadingOfTheOneBefore() {
        listOf(0L, 100L, 200L, 500L, 600L, 700L).forEach { timing.add(it, it) }
        assertEquals("Scale readings: 3 in 500 ms, longest gap 300 ms, longest wait for the main thread 0 ms", lines.last())
    }

    @Test
    fun aDroppedLinkIsNotAGapBetweenReadings() {
        timing.add(0, 0)
        timing.reset()
        listOf(60_000L, 60_100L, 60_200L).forEach { timing.add(it, it) }
        assertEquals(listOf("Scale readings: 3 in 200 ms, longest gap 100 ms, longest wait for the main thread 0 ms"), lines)
    }
}

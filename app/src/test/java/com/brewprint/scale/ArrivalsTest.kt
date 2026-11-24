package com.brewprint.scale

import org.junit.Assert.assertEquals
import org.junit.Test

class ArrivalsTest {
    private var now = 0L
    private val posted = mutableListOf<Runnable>()
    private val taken = mutableListOf<Pair<Long, Byte>>()
    private val arrivals = Arrivals<String>(post = { posted += it }, clock = { now }) { _, at, value -> taken += at to value.single() }

    @Test fun `readings that wait for a busy main thread keep their arrival times and are taken in one pass`() {
        // A second of readings while the main thread is busy elsewhere.
        repeat(10) { now += 100; arrivals.add("scale", byteArrayOf(it.toByte())) }
        assertEquals(1, posted.size)
        now += 900
        posted.removeAt(0).run()
        assertEquals((0 until 10).map { (it + 1) * 100L to it.toByte() }, taken)
    }

    @Test fun `a reading after a pass asks for another`() {
        arrivals.add("scale", byteArrayOf(1))
        posted.removeAt(0).run()
        arrivals.add("scale", byteArrayOf(2))
        assertEquals(1, posted.size)
        posted.removeAt(0).run()
        assertEquals(listOf<Byte>(1, 2), taken.map { it.second })
    }
}

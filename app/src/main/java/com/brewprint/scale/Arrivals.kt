package com.brewprint.scale

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hands scale notifications from Bluetooth's thread to the main thread. Each keeps the time it arrived, and one
 * pass on the main thread takes every notification waiting, so a busy main thread delays the readings by at most
 * that one pass instead of leaving a line of old readings to work through.
 */
internal class Arrivals<S>(
    private val post: (Runnable) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val take: (source: S, at: Long, value: ByteArray) -> Unit,
) {
    private class Arrival<S>(val source: S, val at: Long, val value: ByteArray)

    private val waiting = ConcurrentLinkedQueue<Arrival<S>>()
    private val posted = AtomicBoolean(false)
    private val drain = Runnable {
        posted.set(false)
        while (true) {
            val arrival = waiting.poll() ?: break
            take(arrival.source, arrival.at, arrival.value)
        }
    }

    /** Called on Bluetooth's thread as a notification arrives. */
    fun add(source: S, value: ByteArray) {
        waiting.add(Arrival(source, clock(), value))
        if (posted.compareAndSet(false, true)) post(drain)
    }
}

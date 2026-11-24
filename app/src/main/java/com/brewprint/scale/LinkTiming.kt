package com.brewprint.scale

/**
 * How promptly the scale's readings reach the app, summed up in one log line every [every] readings: how far apart
 * they arrived, and how long they waited for the main thread. An exported log then shows where a slow weight comes from.
 */
internal class LinkTiming(private val every: Int = 100, private val log: (String) -> Unit) {
    private var count = 0
    private var first = 0L
    private var last: Long? = null
    private var longestGap = 0L
    private var longestWait = 0L

    fun add(arrivedAt: Long, handledAt: Long) {
        if (count == 0) first = last ?: arrivedAt
        last?.let { longestGap = maxOf(longestGap, arrivedAt - it) }
        last = arrivedAt
        longestWait = maxOf(longestWait, handledAt - arrivedAt)
        if (++count < every) return
        log("Scale readings: $count in ${arrivedAt - first} ms, longest gap $longestGap ms, longest wait for the main thread $longestWait ms")
        count = 0
        longestGap = 0
        longestWait = 0
    }

    /** The link dropped: the time until it returns is not a gap between readings. */
    fun reset() {
        count = 0
        last = null
        longestGap = 0
        longestWait = 0
    }
}

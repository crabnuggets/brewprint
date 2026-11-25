package com.brewprint.scale

import java.io.File
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScaleLogTest {
    @get:Rule val temporary = TemporaryFolder()
    private var now = 1_795_580_831_967L

    private fun log(file: File, limit: Long = 1_000) = ScaleLog(file, limit, writes = { it.run() }, zone = ZoneOffset.UTC) { now }

    @Test fun `each line is kept with the time it happened`() {
        val file = File(temporary.root, "scale-log.txt")
        val log = log(file)
        assertEquals("", log.read())
        log.add("Scale 38:68 (in use) connected, status 0")
        now += 10_250
        log.add("Scale readings: 100 in 10013 ms, longest gap 131 ms, longest wait for the main thread 9 ms")
        assertEquals(
            "2026-11-25 04:27:11.967 Scale 38:68 (in use) connected, status 0\n" +
                "2026-11-25 04:27:22.217 Scale readings: 100 in 10013 ms, longest gap 131 ms, longest wait for the main thread 9 ms\n",
            log.read(),
        )
        // A log opened again, as after the app restarts, adds to what is there.
        log(file).add("Scales held: none")
        assertEquals(3, log.read().lines().count { it.isNotEmpty() })
    }

    @Test fun `old lines make room for new ones`() {
        val file = File(temporary.root, "scale-log.txt")
        val log = log(file, limit = 200)
        repeat(100) { log.add("Reading $it") }
        val lines = log.read().lines().filter { it.isNotEmpty() }
        // The newest lines are kept, in order, and the oldest are gone.
        assertTrue(lines.last().endsWith("Reading 99"))
        assertEquals(lines.indices.map { "Reading ${100 - lines.size + it}" }, lines.map { it.substringAfter(" ").substringAfter(" ") })
        assertFalse(lines.any { it.endsWith("Reading 0") })
        // Never much more than twice the limit: the current file and the one before it.
        assertTrue(file.length() + File(file.path + ".1").length() < 600)
    }
}

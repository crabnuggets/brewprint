package com.brewprint.cloud

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PullPagesTest {
    private data class Row(val id: String, val updatedAt: String)

    private fun at(second: Int) = "2026-11-20T00:00:%02dZ".format(second)

    @Test fun `a row edited elsewhere mid-pull doesn't hide the rows after it`() {
        val server = (1..5).map { Row("r$it", at(it)) }.toMutableList()
        var calls = 0
        val rows = runBlocking {
            pullPages(null, page = 2, updatedAt = Row::updatedAt) { from, limit ->
                // After the first page, another phone edits r1, moving it to the end.
                if (calls++ == 1) server[0] = Row("r1", at(9))
                server.filter { from == null || it.updatedAt >= from }.sortedBy { it.updatedAt }.take(limit.toInt())
            }
        }
        assertEquals(setOf("r1", "r2", "r3", "r4", "r5"), rows.map { it.id }.toSet())
        assertEquals(at(9), rows.last { it.id == "r1" }.updatedAt)
    }

    @Test fun `a short page ends the pull`() {
        var calls = 0
        val rows = runBlocking { pullPages(at(0), page = 10, updatedAt = Row::updatedAt) { _, _ -> calls++; listOf(Row("a", at(1))) } }
        assertEquals(1, calls)
        assertEquals(1, rows.size)
    }
}

package com.brewprint.data

import org.junit.Assert.*
import org.junit.Test

class ScaleTest {
    @Test fun `the pairing picker lists TIMEMORE scales and leaves out other devices`() {
        val names = java.util.regex.Pattern.compile(ScaleProtocol.NAME_PATTERN)
        listOf("TIMEMORE DOT", "Dot_8F2A", "TES017", "TIMEMORE Basic3 Link", "Basic 3", "Timemore Basic").forEach {
            assertTrue(it, names.matcher(it).matches())
        }
        listOf("DAP", "Pixel Buds", "C4:7F:51:0A:22:9B", "Mi Band 8").forEach {
            assertFalse(it, names.matcher(it).matches())
        }
    }

    @Test fun `the record's flow comes from the saved curve, starts with its pour and keeps gaps`() {
        // A 6 g/s pour from 0 s to 2 s, a still second with a 0.3 g wobble, a lost second, a pour from 5 s,
        // then the brewer lifted at 7 s and put back at 8 s.
        val data = ScaleData(curve = listOf(0.0, 6.0, 12.0, 12.3, null, 14.0, 20.0, -350.0, 120.0, 120.0))
        assertEquals(listOf(6.0, 6.0, 0.0, null, null, 6.0, null, null, 0.0), data.flows())
        // A light dripper lifted in drawdown leaves the reading above 0 g; putting it back is not a pour.
        assertEquals(listOf(0.0), ScaleData(curve = listOf(240.0, 240.0, 180.0, 240.0), liftSeconds = 2).flows())
    }

    // Pours at 0:00, 0:45, 1:20, 1:55 and 2:30; scheduled pours end at 2:45.
    private val recipe = Recipe(name = "Five-pulse filter", dose = 15.0, ratio = 16.0, pulses = listOf(
        Pulse("Bloom", 45.0, 10, 35), Pulse(water = 50.0, pourSeconds = 15, waitSeconds = 20), Pulse(water = 50.0, pourSeconds = 15, waitSeconds = 20),
        Pulse(water = 50.0, pourSeconds = 15, waitSeconds = 20), Pulse(water = 45.0, pourSeconds = 15)))
    private val start = 1_000_000L
    private fun at(seconds: Int) = start + seconds * 1000L
    private val guided = BrewTimer(startedAt = start, byWeight = true)

    private fun frame(vararg body: Int): ByteArray {
        val bytes = body.map { it.toByte() }.toByteArray()
        val crc = ScaleProtocol.crc(bytes)
        return bytes + byteArrayOf((crc shr 8).toByte(), crc.toByte())
    }

    @Test fun `a weight frame gives grams, flow and the overload flag`() {
        val reading = frame(0xA5, 0x5A, 0x01, 0x01, 0x00, 0x09, 0x00, 0x00, 0x06, 0xEE, 0x00, 0x2A, 0x00, 0x5E, 0x00)
        val negative = frame(0xA5, 0x5A, 0x01, 0x01, 0x00, 0x09, 0xFF, 0xFF, 0xFF, 0xEC, 0x00, 0x00, 0x00, 0x00, 0x01)
        val battery = frame(0xA5, 0x5A, 0x01, 0x05, 0x00, 0x02, 0x04, 0x57)
        assertEquals(
            listOf(ScaleProtocol.Reading(177.4, 4.2, false), ScaleProtocol.Reading(-2.0, 0.0, true), ScaleProtocol.Battery(87)),
            ScaleProtocol.parse(reading + negative + battery),
        )
        assertEquals(emptyList<ScaleProtocol.Frame>(), ScaleProtocol.parse(reading.copyOf(10)))
    }

    @Test fun `the CRC is CRC-16 MODBUS and tare is sent in both byte orders`() {
        assertEquals(0x4B37, ScaleProtocol.crc("123456789".toByteArray()))
        val (first, second) = ScaleProtocol.tareFrames()
        val body = byteArrayOf(0xA5.toByte(), 0x5A, 0x03, 0x0D, 0x00, 0x00)
        assertArrayEquals(body, first.copyOf(6))
        assertArrayEquals(body, second.copyOf(6))
        assertEquals(first[6], second[7])
        assertEquals(first[7], second[6])
    }

    @Test fun `a weighed dose rescales the pulses to keep the ratio`() {
        val scaled = recipe.scaledTo(15.23)
        assertEquals(15.2, scaled.dose, 0.0)
        assertEquals(243.2, scaled.totalWater, 0.0)
        assertEquals(listOf(45.6, 50.7, 50.7, 50.7, 45.5), scaled.pulses.map { it.water })
        assertEquals(emptyList<String>(), scaled.validationErrors())
        assertEquals(recipe.pulses.map { it.pourSeconds }, scaled.pulses.map { it.pourSeconds })
    }

    @Test fun `scaling rounds an exact half tenth up`() {
        // 45.3 g × 15 / 18 is exactly 37.75 g.
        val scaled = Recipe(name = "Down", dose = 18.0, pulses = listOf(Pulse(water = 45.3, pourSeconds = 5, waitSeconds = 5), Pulse(water = 54.7, pourSeconds = 5))).scaledTo(15.0)
        assertEquals(listOf(37.8, 45.5), scaled.pulses.map { it.water })
    }

    @Test fun `scaling never leaves a pulse at 0 g, and a weighed dose leaves the ice alone`() {
        // Halved, 0.5 g pulses round up to 0.3 g each, which would leave the last at 0 g; it borrows from the largest.
        val tiny = Recipe(name = "Tiny last", dose = 10.0, drinkStyle = DrinkStyle.ICED, iceGrams = 70.0,
            pulses = listOf(Pulse(water = 0.5, pourSeconds = 5, waitSeconds = 5), Pulse(water = 0.5, pourSeconds = 5, waitSeconds = 5), Pulse(water = 0.1, pourSeconds = 5)))
        val down = tiny.scaledTo(5.0)
        assertEquals(listOf(0.2, 0.3, 0.1), down.pulses.map { it.water })
        assertEquals(emptyList<String>(), down.validationErrors())
        assertEquals(70.0, down.iceGrams!!, 0.0)
        assertEquals(140.0, tiny.scaledTo(20.0, scaleIce = true).iceGrams!!, 0.0)
    }

    @Test fun `a pour held for the scale does not end on the clock`() {
        val view = TimerNow(recipe, guided, at(20))
        assertEquals(TimerPhase.POUR, view.phase)
        assertEquals(0, view.pulse)
        assertEquals(10, view.overSeconds)
    }

    @Test fun `reaching a target early lengthens the wait and keeps the schedule`() {
        val reached = guided.reachTarget(recipe, at(6))
        val waiting = TimerNow(recipe, reached, at(6))
        assertEquals(TimerPhase.WAIT, waiting.phase)
        assertEquals(39, waiting.leftSeconds)
        assertEquals(45, waiting.startSeconds(1))
        val next = TimerNow(recipe, reached, at(46))
        assertEquals(TimerPhase.POUR, next.phase)
        assertEquals(1, next.pulse)
        assertEquals(45, next.startSeconds(1))
    }

    @Test fun `reaching a target late moves later pulses later`() {
        val reached = guided.reachTarget(recipe, at(14))
        assertEquals(49, TimerNow(recipe, reached, at(14)).startSeconds(1))
        assertEquals(TimerPhase.POUR, TimerNow(recipe, reached, at(50)).phase)
    }

    @Test fun `undo after losing the scale keeps following the clock`() {
        val skipped = guided.reachTarget(recipe, at(8)).skip(recipe, at(20))
        val undone = skipped.followClock(recipe, at(21)).undone(at(22))
        assertEquals(1, undone.step)
        assertFalse(undone.byWeight)
    }

    @Test fun `losing the scale ends an overrunning pour now and otherwise follows the clock`() {
        val overrun = guided.followClock(recipe, at(14))
        assertFalse(overrun.byWeight)
        assertEquals(TimerPhase.WAIT, TimerNow(recipe, overrun, at(14)).phase)
        assertEquals(49, TimerNow(recipe, overrun, at(14)).startSeconds(1))

        val onTime = guided.followClock(recipe, at(5))
        assertEquals(TimerPhase.POUR, TimerNow(recipe, onTime, at(9)).phase)
        assertEquals(TimerPhase.WAIT, TimerNow(recipe, onTime, at(11)).phase)
    }
}

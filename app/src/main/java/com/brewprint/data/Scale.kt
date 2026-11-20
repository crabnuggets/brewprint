package com.brewprint.data

import kotlinx.serialization.Serializable

/** The Bluetooth coffee scale chosen in Android's device picker. */
@Serializable
data class PairedScale(val address: String, val name: String)

/** Where a scale brew stands before the first pour. */
@Serializable enum class ScaleStep { BREWER, COFFEE, ARMED }

/** What the scale measured during one brew. Grams; seconds count from the first pour. */
@Serializable
data class ScaleData(
    val weighedDose: Double? = null,
    /** The steady reading once flow stopped after the last pour. */
    val water: Double? = null,
    /** The steady reading after each pulse, null where it was not seen. */
    val reached: List<Double?> = emptyList(),
    /** One reading a second from the first pour; null where the scale was lost. */
    val curve: List<Double?> = emptyList(),
    /** When the dripper came off after the last pour. */
    val liftSeconds: Int? = null,
)

/** Flow in grams a second below which the scale counts as still. */
internal const val QUIET_FLOW = 0.5

/**
 * Flow for each second of [ScaleData.curve], worked out from the change to the next second, so flow need not be saved.
 * Gaps stay gaps and still seconds are 0. Below 0 g the brewer is off the scale, so a second next to such a reading is a gap:
 * putting the brewer back would otherwise read as a pour.
 */
fun ScaleData.flows(): List<Double?> = curve.zipWithNext { grams, next ->
    if (grams == null || next == null || grams < 0 || next < 0) null else (next - grams).takeIf { it >= QUIET_FLOW } ?: 0.0
}

/** The scale as the screens see it. Not saved. */
data class ScaleLive(
    val connected: Boolean = false,
    val grams: Double? = null,
    val flow: Double = 0.0,
    val battery: Int? = null,
    /** Flow has been about 0 for a second. */
    val steady: Boolean = false,
    /** Armed and steady at about 0 g, so a rise starts the timer. */
    val zeroed: Boolean = false,
    /** The scale did not zero after a tare command, so its own tare button is needed. */
    val tareFailed: Boolean = false,
    /** Before the first pour, no usable reading has arrived since the scale went silent or overloaded. */
    val silent: Boolean = false,
    /** A tare was sent and the scale has not yet read about 0 g. */
    val taring: Boolean = false,
    /** Why a scale brew is following the clock, or null while the scale guides it. */
    val following: String? = null,
    val curve: List<Double?> = emptyList(),
    /** Flow for each second of [curve]. */
    val flows: List<Double?> = emptyList(),
)

/** True while a timed brew is guided by the scale and still running. */
fun AppState.scaleBrewRunning(now: Long): Boolean {
    val draft = draft ?: return false
    val timer = draft.timer ?: return false
    val recipe = draft.recipe ?: return false
    return scale != null && draft.scale != null && TimerNow(recipe, timer, now).active
}

/** This brew's plan for [dose] at the same ratio. The last pulse takes the rounding, so the plan stays valid. */
fun Recipe.scaledTo(dose: Double): Recipe {
    val rounded = roundedAmount(dose)
    val total = totalBrewWater(rounded, ratio)
    val factor = total / plannedWater
    val scaled = pulses.dropLast(1).map { it.copy(water = roundedAmount(it.water * factor)) }
    val last = pulses.last().copy(water = roundedAmount(total - scaled.sumOf { it.water }))
    return copy(dose = rounded, pulses = scaled + last)
}

/**
 * Frames of the TIMEMORE open scale protocol v1.0.3:
 * A5 5A, opcode, command, payload length (2 bytes, big-endian), payload, CRC (2 bytes).
 */
object ScaleProtocol {
    /** The protocol gives weight as an int32 without a unit. The Dot shows 0.1 g, as flow is given in 0.1 g/s. Unverified on hardware. */
    private const val UNITS_PER_GRAM = 10.0

    /**
     * Advertised names of the scales that speak this protocol: the Dot (TES017) and the Basic3 Link (TES016).
     * The protocol leaves names undocumented; these follow Beanconqueror's name matching. Unverified on hardware.
     */
    const val NAME_PATTERN = "(?i).*(dot|tes01[67]|basic ?3|timemore.*basic|basic.*timemore).*"

    sealed interface Frame
    data class Reading(val grams: Double, val flow: Double, val overload: Boolean) : Frame
    data class Battery(val percent: Int) : Frame

    /** Every recognised frame in one notification. Unknown commands and truncated frames are skipped. */
    fun parse(bytes: ByteArray): List<Frame> {
        val frames = mutableListOf<Frame>()
        var i = 0
        while (i + 8 <= bytes.size) {
            if (bytes[i] != 0xA5.toByte() || bytes[i + 1] != 0x5A.toByte()) { i++; continue }
            val command = bytes[i + 3].toInt() and 0xFF
            val length = (bytes[i + 4].toInt() and 0xFF shl 8) or (bytes[i + 5].toInt() and 0xFF)
            if (i + 8 + length > bytes.size) break
            val p = i + 6
            if (command == 0x01 && length >= 9) {
                val weight = (bytes[p].toInt() shl 24) or (bytes[p + 1].toInt() and 0xFF shl 16) or (bytes[p + 2].toInt() and 0xFF shl 8) or (bytes[p + 3].toInt() and 0xFF)
                val flow = ((bytes[p + 4].toInt() shl 8) or (bytes[p + 5].toInt() and 0xFF)).toShort()
                frames += Reading(weight / UNITS_PER_GRAM, flow / 10.0, bytes[p + 8].toInt() != 0)
            } else if (command == 0x05 && length >= 2) {
                frames += Battery(bytes[p + 1].toInt() and 0xFF)
            }
            i += 8 + length
        }
        return frames
    }

    /**
     * Tare, written twice: the protocol does not state the CRC's byte order, and the scale drops a frame
     * whose CRC fails, so exactly one of these is applied.
     */
    fun tareFrames(): List<ByteArray> {
        val body = byteArrayOf(0xA5.toByte(), 0x5A, 0x03, 0x0D, 0x00, 0x00)
        val crc = crc(body)
        return listOf(body + byteArrayOf((crc shr 8).toByte(), crc.toByte()), body + byteArrayOf(crc.toByte(), (crc shr 8).toByte()))
    }

    /** CRC-16 with polynomial 0x8005 and initial value 0xFFFF, reflected (CRC-16/MODBUS). */
    fun crc(bytes: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in bytes) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }
}

/**
 * The draft after the scale is lost. Before a dose is chosen, its tare is no longer known, so the coffee step goes
 * back to taring the brewer. Scale data on the brewer step marks this reset, so the sheet can say why.
 */
fun BrewDraft.afterScaleLost(): BrewDraft =
    if (timer == null && scale != null && scaleStep == ScaleStep.COFFEE) copy(scaleStep = ScaleStep.BREWER) else this

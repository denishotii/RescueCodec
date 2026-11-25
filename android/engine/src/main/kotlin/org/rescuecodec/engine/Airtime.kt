// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.engine

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow

/** Meshtastic modem presets: spreading factor, bandwidth in Hz and coding rate denominator (5 means 4/5). */
enum class ModemPreset(val sf: Int, val bandwidthHz: Int, val codingRate: Int) {
    LONG_FAST(11, 250_000, 5),
    LONG_MODERATE(11, 125_000, 8),
    LONG_SLOW(12, 125_000, 8),
    LONG_TURBO(11, 500_000, 8),
    MEDIUM_SLOW(10, 250_000, 5),
    MEDIUM_FAST(9, 250_000, 5),
    SHORT_SLOW(8, 250_000, 5),
    SHORT_FAST(7, 250_000, 5),
    SHORT_TURBO(7, 500_000, 5),
}

/** Radio region and the share of time a node may transmit. */
enum class Region(val dutyCycle: Double) {
    /** EU 868 MHz, 10 percent duty cycle on the 869.4 to 869.65 MHz band used by Meshtastic. */
    EU_868(0.10),

    /** EU 433 MHz, 10 percent. */
    EU_433(0.10),

    /** No legal duty cycle (for example US 915). Use the channel-sharing budget only. */
    NO_LIMIT(1.0),
}

/**
 * LoRa time on air for a RescueCodec payload sent through Meshtastic.
 *
 * Meshtastic adds about [overheadBytes] (23) bytes around every payload: a 16-byte radio header plus
 * the protobuf `Data` wrapper. The preamble is 16 symbols. Numbers match spec section 9.
 */
class Airtime(val preset: ModemPreset = ModemPreset.LONG_FAST, val overheadBytes: Int = 23) {
    /** Milliseconds on air for an application payload of [payloadBytes]. */
    fun millis(payloadBytes: Int): Double {
        val sf = preset.sf
        val tSym = 2.0.pow(sf) / preset.bandwidthHz * 1000.0
        val lowDataRate = tSym > 16.0
        val de = if (lowDataRate) 1 else 0
        val pl = payloadBytes + overheadBytes
        val bits = 8 * pl - 4 * sf + 28 + 16 // explicit header, CRC on
        val payloadSymbols = 8 + max(ceil(bits.toDouble() / (4 * (sf - 2 * de))) * preset.codingRate, 0.0)
        val preamble = (PREAMBLE_SYMBOLS + 4.25) * tSym
        return preamble + payloadSymbols * tSym
    }

    companion object {
        const val PREAMBLE_SYMBOLS = 16
    }
}

/**
 * Rolling airtime budget. A node must stay under [Region.dutyCycle] of time on air in any window
 * (EU_868: 10 percent of one hour is 360 s).
 *
 * Every message type has its own limit as a share of that budget (spec section 15.1, same as the
 * Python engine): Mayday and Ack are never held (`share` null), Found, Request and Hazard may use
 * 100 percent, Sector 90 percent, Track and Text 70 percent. So routine traffic cannot eat the
 * budget that a find or a Mayday needs. See [BudgetLimits].
 *
 * Not thread-safe: call it from one thread, or lock outside.
 */
class AirtimeBudget(
    val airtime: Airtime = Airtime(),
    val region: Region = Region.EU_868,
    val windowMs: Long = 3_600_000,
    /** Multiplies each message's airtime, for retries. 1.0 = send once. */
    val retryFactor: Double = 1.0,
) {
    private val log = ArrayDeque<Pair<Long, Double>>()

    /** Total airtime allowed per window, in ms. */
    val limitMs: Double get() = windowMs * region.dutyCycle

    private fun expire(now: Long) {
        while (log.isNotEmpty() && log.first().first <= now - windowMs) log.removeFirst()
    }

    /** Airtime used in the current window, in ms. */
    fun usedMs(now: Long): Double {
        expire(now)
        return log.sumOf { it.second }
    }

    /** Airtime left for a message that may use [share] of the budget (null = never held: all of it). */
    fun remainingMs(now: Long, share: Double?): Double = (limitMs * (share ?: 1.0)) - usedMs(now)

    /** Cost of one message of [payloadBytes], including the retry factor. */
    fun costMs(payloadBytes: Int): Double = airtime.millis(payloadBytes) * retryFactor

    /** True when a message of [payloadBytes] with limit [share] may go out now. */
    fun canSend(payloadBytes: Int, now: Long, share: Double?): Boolean =
        share == null || region.dutyCycle >= 1.0 || remainingMs(now, share) + 1e-6 >= costMs(payloadBytes)

    /** Writes a transmission into the budget. */
    fun record(payloadBytes: Int, now: Long) {
        expire(now)
        log.addLast(now to costMs(payloadBytes))
    }

    /** Milliseconds until a message of [payloadBytes] with limit [share] fits, 0 if it fits now. */
    fun waitMs(payloadBytes: Int, now: Long, share: Double?): Long {
        if (canSend(payloadBytes, now, share)) return 0
        expire(now)
        val cap = limitMs * (share ?: 1.0)
        val need = costMs(payloadBytes)
        if (need > cap) return Long.MAX_VALUE // never fits
        var used = log.sumOf { it.second }
        for ((t, cost) in log) {
            used -= cost
            if (cap - used + 1e-6 >= need) return max(0L, t + windowMs - now)
        }
        return 0
    }
}

/** Per message type: the share of the airtime budget that may be used before the type is held. */
object BudgetLimits {
    /** Message type name to limit. `null` means never held (Mayday and Ack: life safety, tiny packets). */
    private val byType: Map<String, Double?> = mapOf(
        "mayday" to null, "ack" to null,
        "found" to 1.0, "request" to 1.0, "hazard" to 1.0,
        "sector" to 0.9, "text" to 0.7, "track" to 0.7,
    )

    /** Limit for a message type. Unknown types count as text (70 percent). */
    fun forType(type: String?): Double? = if (type != null && type in byType) byType.getValue(type) else 0.7

    fun forMessage(message: Map<String, Any?>): Double? =
        forType(message.keys.firstOrNull { it in org.rescuecodec.codec.Codes.BODY_TYPES })
}

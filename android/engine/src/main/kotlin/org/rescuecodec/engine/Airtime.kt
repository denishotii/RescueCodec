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
 * (EU_868: 10 percent of one hour is 360 s). The budget also keeps [criticalReserve] of the limit
 * for the most important messages (rank 0, Mayday), so routine traffic cannot starve them.
 *
 * Not thread-safe: call it from one thread, or lock outside.
 */
class AirtimeBudget(
    val airtime: Airtime = Airtime(),
    val region: Region = Region.EU_868,
    val windowMs: Long = 3_600_000,
    val criticalReserve: Double = 0.2,
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

    /** Airtime left for a message of [rank] (0 is allowed to use the reserve). */
    fun remainingMs(now: Long, rank: Int): Double {
        val cap = if (rank == 0) limitMs else limitMs * (1 - criticalReserve)
        return cap - usedMs(now)
    }

    /** Cost of one message of [payloadBytes], including the retry factor. */
    fun costMs(payloadBytes: Int): Double = airtime.millis(payloadBytes) * retryFactor

    fun canSend(payloadBytes: Int, now: Long, rank: Int): Boolean =
        region.dutyCycle >= 1.0 || remainingMs(now, rank) >= costMs(payloadBytes)

    /** Writes a transmission into the budget. */
    fun record(payloadBytes: Int, now: Long) {
        expire(now)
        log.addLast(now to costMs(payloadBytes))
    }

    /** Milliseconds until a message of [payloadBytes] and [rank] fits, 0 if it fits now. */
    fun waitMs(payloadBytes: Int, now: Long, rank: Int): Long {
        if (canSend(payloadBytes, now, rank)) return 0
        expire(now)
        val cap = if (rank == 0) limitMs else limitMs * (1 - criticalReserve)
        val need = costMs(payloadBytes)
        if (need > cap) return Long.MAX_VALUE // never fits
        var used = log.sumOf { it.second }
        for ((t, cost) in log) {
            used -= cost
            if (cap - used >= need) return max(0L, t + windowMs - now)
        }
        return 0
    }
}

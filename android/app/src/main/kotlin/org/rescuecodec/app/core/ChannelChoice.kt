// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.core

import org.meshtastic.proto.Channel

/** One channel of the connected radio, as the settings list shows it. */
data class ChannelOption(val index: Int, val name: String, val isPrivate: Boolean)

/**
 * Which radio channel carries our reports. We never guess and never fall back to channel 0:
 * reports on the public default channel would leak positions and injuries (spec: private channel,
 * never the default key). If there is no safe choice, messages wait and [Problem] says why.
 */
sealed class ChannelChoice {
    /** Reports go out on channel [index]. [auto] is true when we picked it without a setting. */
    data class Ready(val index: Int, val name: String, val auto: Boolean) : ChannelChoice()

    /** The radio has not told us its channels yet (or no radio is connected). Messages wait. */
    data class Waiting(val detail: String) : ChannelChoice()

    /** No safe channel. Messages wait until the person fixes it. */
    data class Problem(val detail: String) : ChannelChoice()

    /** Text for the screen and for the waiting messages. */
    fun text(): String = when (this) {
        is Ready -> "Channel $index \"$name\"" + if (auto) " (picked automatically)." else "."
        is Waiting -> detail
        is Problem -> detail
    }

    val ready: Boolean get() = this is Ready

    companion object {
        /** The key that stands for "no real key": empty, one byte (default key variants), or the expanded default. */
        private val DEFAULT_PSK_16 = byteArrayOf(
            0xd4.toByte(), 0xf1.toByte(), 0xbb.toByte(), 0x3a, 0x20, 0x29, 0x07, 0x59,
            0xf0.toByte(), 0xbc.toByte(), 0xff.toByte(), 0xab.toByte(), 0xcf.toByte(), 0x4e, 0x69, 0x01,
        )

        /** True if [psk] is empty (no encryption) or the well known public default key. */
        fun isDefaultKey(psk: ByteArray): Boolean =
            psk.size <= 1 || (psk.size == 16 && psk.contentEquals(DEFAULT_PSK_16))

        /** Channels that are switched on, with their name and whether the key is our own. */
        fun options(channels: List<Channel>?): List<ChannelOption> = channels.orEmpty()
            .filter { it.role != Channel.Role.DISABLED }
            .map { ChannelOption(it.index, it.settings?.name.orEmpty(), !isDefaultKey(it.settings?.psk?.toByteArray() ?: ByteArray(0))) }
            .sortedBy { it.index }

        /**
         * Chooses the channel. [fixedIndex] is the person's choice in the settings (-1 = automatic).
         * Automatic: the enabled channel named [AppSettings.CHANNEL_NAME]; else the only enabled
         * channel with its own key. Anything else is a [Problem] or [Waiting], never channel 0.
         */
        fun resolve(channels: List<Channel>?, fixedIndex: Int): ChannelChoice {
            val all = options(channels)
            if (channels.isNullOrEmpty()) return Waiting("Waiting for the radio to tell us its channels.")
            if (fixedIndex >= 0) {
                val c = all.firstOrNull { it.index == fixedIndex }
                    ?: return Problem("Channel $fixedIndex is not set up on this radio. Pick another channel in the settings.")
                if (!c.isPrivate) return Problem("Channel $fixedIndex uses the public default key. Pick a channel with its own key.")
                return Ready(c.index, c.name, auto = false)
            }
            val named = all.firstOrNull { it.name == AppSettings.CHANNEL_NAME }
            if (named != null) {
                if (!named.isPrivate) {
                    return Problem("Channel \"${AppSettings.CHANNEL_NAME}\" has no private key. Give it its own key on the radio.")
                }
                return Ready(named.index, named.name, auto = false)
            }
            val own = all.filter { it.isPrivate }
            return when (own.size) {
                1 -> Ready(own[0].index, own[0].name, auto = true)
                0 -> Problem("No private ${AppSettings.CHANNEL_NAME} channel on this radio. Add a channel named ${AppSettings.CHANNEL_NAME} with its own key, or pick a channel in the settings.")
                else -> Problem("Several private channels on this radio. Name the team channel ${AppSettings.CHANNEL_NAME} or pick one in the settings.")
            }
        }
    }
}

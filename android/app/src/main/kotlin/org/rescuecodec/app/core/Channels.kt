// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.core

import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config

/**
 * One switched-on channel of the connected radio, as the chat list shows it. Shared contract with the gateway: a
 * channel is identified by its [name]; an empty primary name is the modem preset's name ("LongFast"). A channel is
 * public when it has no key or the one-byte default key.
 */
data class RadioChannel(val index: Int, val name: String, val isPublic: Boolean) {
    val isPrivate: Boolean get() = !isPublic
}

/** The channel a message was sent or received on: index at that time, name, public. */
data class ChannelRef(val index: Int?, val name: String?, val isPublic: Boolean) {
    companion object {
        fun of(c: RadioChannel) = ChannelRef(c.index, c.name, c.isPublic)
    }
}

/** Where a received packet belongs: its channel, and the other node for a direct message (null for a broadcast). */
data class Inbound(val channel: ChannelRef, val peer: Long?)

object Channels {
    /** Meshtastic's display name of a modem preset, as the official apps show an unnamed primary channel. */
    fun presetName(lora: Config.LoRaConfig?): String {
        if (lora == null) return "LongFast"
        if (!lora.use_preset) return "Custom"
        return when (lora.modem_preset) {
            Config.LoRaConfig.ModemPreset.LONG_FAST -> "LongFast"
            Config.LoRaConfig.ModemPreset.LONG_SLOW -> "LongSlow"
            Config.LoRaConfig.ModemPreset.VERY_LONG_SLOW -> "VLongSlow"
            Config.LoRaConfig.ModemPreset.MEDIUM_SLOW -> "MediumSlow"
            Config.LoRaConfig.ModemPreset.MEDIUM_FAST -> "MediumFast"
            Config.LoRaConfig.ModemPreset.SHORT_SLOW -> "ShortSlow"
            Config.LoRaConfig.ModemPreset.SHORT_FAST -> "ShortFast"
            Config.LoRaConfig.ModemPreset.SHORT_TURBO -> "ShortTurbo"
            Config.LoRaConfig.ModemPreset.LONG_MODERATE -> "LongMod"
            Config.LoRaConfig.ModemPreset.LONG_TURBO -> "LongTurbo"
            else -> lora.modem_preset.name.split('_').joinToString("") { it.lowercase().replaceFirstChar(Char::uppercase) }
        }
    }

    /** The name a channel shows: its own name, or the preset name when it has none. */
    fun displayName(c: Channel, preset: String): String = c.settings?.name.orEmpty().ifBlank { preset }

    /** Switched-on channels, index order, with their display name and whether they are public. */
    fun list(channels: List<Channel>?, preset: String): List<RadioChannel> = channels.orEmpty()
        .filter { it.role != Channel.Role.DISABLED }
        .map { RadioChannel(it.index, displayName(it, preset), ChannelChoice.isDefaultKey(it.settings?.psk?.toByteArray() ?: ByteArray(0))) }
        .sortedBy { it.index }

    /**
     * Where a packet belongs, or null to drop it. A direct message to our node [self] is always shown, whatever the
     * channel. A broadcast only on a private channel of the radio, or on a public one when [showPublic] is on.
     * A direct message to another node is never ours.
     */
    fun inbound(to: Long, from: Long, channel: Int, self: Long, radio: List<RadioChannel>, showPublic: Boolean): Inbound? {
        val ch = radio.firstOrNull { it.index == channel }
        if (to != MeshText.BROADCAST) {
            if (self == 0L || to != self) return null
            return Inbound(ch?.let { ChannelRef.of(it) } ?: ChannelRef(channel, null, false), peer = from)
        }
        if (ch == null) return null
        if (ch.isPublic && !showPublic) return null
        return Inbound(ChannelRef.of(ch), peer = null)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.radio

import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.sdk.AdminResult
import org.meshtastic.sdk.RadioClient
import java.util.Base64

/** A channel link from the Meshtastic app or QR code, decoded and checked. */
data class ChannelLink(val set: ChannelSet) {
    val channels: List<ChannelSettings> get() = set.settings
    val primaryName: String get() = channels.firstOrNull()?.name.orEmpty()

    /** True when every channel has its own key. A public key must never carry rescue reports. */
    val allPrivate: Boolean get() = channels.isNotEmpty() && channels.all { it.psk.size > 1 && !isDefault(it.psk.toByteArray()) }

    private fun isDefault(psk: ByteArray) = psk.size == 16 && psk[0] == 0xd4.toByte() && psk[1] == 0xf1.toByte()
}

/** Reads and applies Meshtastic channel links (`https://meshtastic.org/e/#...`). */
object ChannelJoin {
    /**
     * Decodes a link or QR text. Returns null when it is not a Meshtastic channel link.
     * The part after `#` is the URL-safe Base64 of a `ChannelSet` protobuf.
     */
    fun parse(text: String): ChannelLink? {
        val t = text.trim()
        val frag = if ('#' in t) t.substringAfter('#') else return null
        if (!t.contains("meshtastic.org/e/", ignoreCase = true) && !t.startsWith("meshtastic://", ignoreCase = true)) return null
        return try {
            val bytes = Base64.getUrlDecoder().decode(frag.trim().trimEnd('='))
            val set = ChannelSet.ADAPTER.decode(bytes)
            if (set.settings.isEmpty()) null else ChannelLink(set)
        } catch (e: Exception) {
            null
        }
    }

    /** Builds a link from a channel set (used in tests and to show the current channel). */
    fun toLink(set: ChannelSet): String =
        "https://meshtastic.org/e/#" + Base64.getUrlEncoder().withoutPadding().encodeToString(ChannelSet.ADAPTER.encode(set))

    /**
     * Writes the channels of [link] to the connected radio: channel 0 becomes primary, the others
     * secondary, and the LoRa settings are taken over when the link has them. Returns null on
     * success or a short reason. The radio may restart afterwards; the app reconnects by itself.
     */
    suspend fun apply(client: RadioClient, link: ChannelLink): String? {
        link.channels.forEachIndexed { i, s ->
            val ch = Channel.Builder().index(i).settings(s).role(if (i == 0) Channel.Role.PRIMARY else Channel.Role.SECONDARY).build()
            when (val r = client.admin.setChannel(ch)) {
                is AdminResult.Success -> Unit
                else -> return "Channel ${i + 1} was not accepted by the radio ($r)."
            }
        }
        val lora = link.set.lora_config
        if (lora != null && lora.region != Config.LoRaConfig.RegionCode.UNSET) {
            when (val r = client.admin.setConfig(Config.Builder().lora(lora).build())) {
                is AdminResult.Success -> Unit
                else -> return "The radio did not accept the region settings ($r)."
            }
        }
        return null
    }
}

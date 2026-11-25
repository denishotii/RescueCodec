// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.radio

import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.sdk.AdminResult
import org.meshtastic.sdk.RadioClient
import org.rescuecodec.app.core.ChannelChoice
import org.rescuecodec.app.core.Channels
import java.util.Base64

/** A channel link from the Meshtastic app or QR code, decoded and checked. */
data class ChannelLink(val set: ChannelSet) {
    val channels: List<ChannelSettings> get() = set.settings
    val primaryName: String get() = channels.firstOrNull()?.name.orEmpty()

    /** True when every channel has its own key. A public key must never carry rescue reports. */
    val allPrivate: Boolean get() = channels.isNotEmpty() && channels.all { isPrivate(it) }

    /** True when at least one channel has its own key: only those are added. */
    val anyPrivate: Boolean get() = channels.any { isPrivate(it) }

    companion object {
        fun isPrivate(s: ChannelSettings): Boolean = !ChannelChoice.isDefaultKey(s.psk.toByteArray())
    }
}

/**
 * What joining a link will do to the radio, worked out before anything is written, so the confirm screen can say it.
 * Joining only ADDS: the link's private channels go into free secondary slots. The primary channel is never touched,
 * and region and modem settings only when the radio has no region yet.
 */
data class JoinPlan(
    /** New channels: slot index and settings. */
    val add: List<Pair<Int, ChannelSettings>>,
    /** Secondary channels with the same name but another key: slot index and the link's settings. */
    val update: List<Pair<Int, ChannelSettings>>,
    /** Names already on the radio with the same key. */
    val already: List<String>,
    /** The radio's primary channel has this name too (another key): left alone. */
    val keptPrimary: List<String>,
    /** Public channels of the link (no key or the default key): never added. */
    val skippedPublic: List<String>,
    /** Private channels that did not fit: all 7 secondary slots are in use. */
    val noRoom: List<String>,
    /** LoRa settings to write, only when the radio's region is UNSET and the link has a region. Else null. */
    val lora: Config.LoRaConfig?,
) {
    val writes: Boolean get() = add.isNotEmpty() || update.isNotEmpty() || lora != null
}

/** Reads and applies Meshtastic channel links (`https://meshtastic.org/e/#...`). */
object ChannelJoin {
    /** Meshtastic radios have 8 channel slots: 0 is primary, 1 to 7 secondary. */
    const val SLOTS = 8

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
     * The plan for [link] on a radio with [current] channels and [radioLora] settings (null when not known: then the
     * region is left alone). Pure, so it is unit tested.
     */
    fun plan(current: List<Channel>, radioLora: Config.LoRaConfig?, link: ChannelLink): JoinPlan {
        val preset = Channels.presetName(radioLora)
        val used = current.filter { it.role != Channel.Role.DISABLED }
        val free = (1 until SLOTS).filter { i -> used.none { it.index == i } }.toMutableList()
        val add = ArrayList<Pair<Int, ChannelSettings>>()
        val update = ArrayList<Pair<Int, ChannelSettings>>()
        val already = ArrayList<String>()
        val kept = ArrayList<String>()
        val skipped = ArrayList<String>()
        val noRoom = ArrayList<String>()
        val linkPreset = Channels.presetName(link.set.lora_config)
        for (s in link.channels) {
            val name = s.name.ifBlank { linkPreset }
            if (!ChannelLink.isPrivate(s)) { skipped += name; continue }
            val same = used.firstOrNull { (it.settings?.name.orEmpty().ifBlank { preset }) == name }
            when {
                same == null -> {
                    val slot = free.removeFirstOrNull()
                    if (slot == null) noRoom += name else add += slot to s
                }
                same.settings?.psk == s.psk -> already += name
                same.role == Channel.Role.PRIMARY || same.index == 0 -> kept += name
                else -> update += same.index to s
            }
        }
        val linkLora = link.set.lora_config
        val lora = if (radioLora != null && radioLora.region == Config.LoRaConfig.RegionCode.UNSET &&
            linkLora != null && linkLora.region != Config.LoRaConfig.RegionCode.UNSET
        ) linkLora else null
        return JoinPlan(add, update, already, kept, skipped, noRoom, lora)
    }

    /** The radio's LoRa settings, or null when the radio has not told us. */
    fun loraOf(client: RadioClient): Config.LoRaConfig? = client.configBundle.value?.configs?.firstNotNullOfOrNull { it.lora }

    /**
     * Writes [plan] with [setChannel] and [setConfig] (the radio's admin calls). Channels first, region last (the radio
     * restarts after a region change; the app reconnects by itself). Returns null on success or a short reason.
     */
    suspend fun apply(
        plan: JoinPlan,
        setChannel: suspend (Channel) -> AdminResult<Unit>,
        setConfig: suspend (Config) -> AdminResult<Unit>,
    ): String? {
        for ((slot, s) in plan.add + plan.update) {
            val ch = Channel.Builder().index(slot).settings(s).role(Channel.Role.SECONDARY).build()
            when (val r = setChannel(ch)) {
                is AdminResult.Success -> Unit
                else -> return "Channel \"${s.name}\" was not accepted by the radio ($r)."
            }
        }
        plan.lora?.let { lora ->
            when (val r = setConfig(Config.Builder().lora(lora).build())) {
                is AdminResult.Success -> Unit
                else -> return "The radio did not accept the region settings ($r)."
            }
        }
        return null
    }

    /** Works out the plan on the connected radio and writes it. */
    suspend fun apply(client: RadioClient, link: ChannelLink): String? {
        val plan = plan(client.channels.value.orEmpty(), loraOf(client), link)
        return apply(plan, { client.admin.setChannel(it) }, { client.admin.setConfig(it) })
    }
}

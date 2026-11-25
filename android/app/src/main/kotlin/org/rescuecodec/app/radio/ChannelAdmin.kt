// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.radio

import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.sdk.AdminResult
import org.rescuecodec.app.core.ChannelChoice
import org.rescuecodec.app.core.Channels
import java.security.SecureRandom

/** Why a name for a new channel cannot be used. */
enum class NameProblem { EMPTY, TOO_LONG, TAKEN }

/** What creating a channel did: the slot and the share link, or why it did not work. */
sealed interface CreateResult {
    data class Created(val index: Int, val name: String, val link: String) : CreateResult
    data class Failed(val reason: Reason, val detail: String? = null) : CreateResult

    enum class Reason { BAD_NAME, NO_FREE_SLOT, REFUSED }
}

/**
 * Making, sharing and removing channels from the phone, without a computer or the Meshtastic app.
 *
 * - A new channel goes into a free SECONDARY slot (1 to 7) with a fresh 32-byte key. The primary channel, the region
 *   and the modem preset are never touched.
 * - Sharing builds the same link the Meshtastic app shares (`https://meshtastic.org/e/#` + URL-safe Base64 without
 *   padding of a `ChannelSet`), with only that one channel and the radio's LoRa settings. [ChannelJoin] reads it, and
 *   so does the official app.
 * - Removing switches the slot off (role DISABLED). The primary is never removed.
 */
object ChannelAdmin {
    /** Meshtastic keeps at most 11 bytes of a channel name (UTF-8). */
    const val MAX_NAME_BYTES = 11

    /** AES-256. */
    const val KEY_BYTES = 32

    /** Bytes of [name] as the radio stores it. */
    fun nameBytes(name: String): Int = name.trim().toByteArray(Charsets.UTF_8).size

    /** Names on the radio, as the screens show them (an unnamed channel has the preset's name). */
    private fun names(current: List<Channel>, preset: String): List<String> =
        current.filter { it.role != Channel.Role.DISABLED }.map { Channels.displayName(it, preset) }

    /** Null when [name] can be used, else the problem. Names are compared without case: "team 2" and "Team 2" clash. */
    fun checkName(name: String, current: List<Channel>, preset: String = "LongFast"): NameProblem? {
        val n = name.trim()
        return when {
            n.isEmpty() -> NameProblem.EMPTY
            nameBytes(n) > MAX_NAME_BYTES -> NameProblem.TOO_LONG
            names(current, preset).any { it.equals(n, ignoreCase = true) } || n.equals(preset, ignoreCase = true) -> NameProblem.TAKEN
            else -> null
        }
    }

    /** "Team 2", or "Team 3" when that is taken, and so on. */
    fun suggestName(current: List<Channel>, preset: String = "LongFast"): String {
        var i = 2
        while (checkName("Team $i", current, preset) != null && i < 99) i++
        return "Team $i"
    }

    /** The first free secondary slot (1 to 7), or null when all are in use. */
    fun freeSlot(current: List<Channel>): Int? {
        val used = current.filter { it.role != Channel.Role.DISABLED }.map { it.index }.toSet()
        return (1 until ChannelJoin.SLOTS).firstOrNull { it !in used }
    }

    /** A fresh random key. Never the default key and never all zero. */
    fun newKey(random: SecureRandom = SecureRandom()): ByteArray {
        while (true) {
            val k = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
            if (k.any { it != 0.toByte() } && !ChannelChoice.isDefaultKey(k)) return k
        }
    }

    /** The settings of a new private channel. */
    fun settings(name: String, key: ByteArray): ChannelSettings =
        ChannelSettings.Builder().name(name.trim()).psk(key.toByteString()).build()

    /**
     * Writes a new private channel named [name] into a free secondary slot with [setChannel] (the radio's admin call).
     * [lora] goes into the share link (null when the radio has not told us). Pure apart from [setChannel].
     */
    suspend fun create(
        current: List<Channel>,
        lora: Config.LoRaConfig?,
        name: String,
        setChannel: suspend (Channel) -> AdminResult<Unit>,
        random: SecureRandom = SecureRandom(),
    ): CreateResult {
        val preset = Channels.presetName(lora)
        checkName(name, current, preset)?.let { return CreateResult.Failed(CreateResult.Reason.BAD_NAME, it.name) }
        val slot = freeSlot(current) ?: return CreateResult.Failed(CreateResult.Reason.NO_FREE_SLOT)
        val s = settings(name, newKey(random))
        val ch = Channel.Builder().index(slot).role(Channel.Role.SECONDARY).settings(s).build()
        return when (val r = setChannel(ch)) {
            is AdminResult.Success -> CreateResult.Created(slot, s.name, ChannelJoin.toLink(shareSet(s, lora)))
            else -> CreateResult.Failed(CreateResult.Reason.REFUSED, r.toString())
        }
    }

    /** A channel set with only [settings] and the radio's LoRa settings, as the Meshtastic app shares one channel. */
    fun shareSet(settings: ChannelSettings, lora: Config.LoRaConfig?): ChannelSet =
        ChannelSet.Builder().settings(listOf(settings)).lora_config(lora).build()

    /** The share link of channel [index], or null when it is not on the radio or has no key of its own. */
    fun shareLink(current: List<Channel>, index: Int, lora: Config.LoRaConfig?): String? {
        val ch = current.firstOrNull { it.index == index && it.role != Channel.Role.DISABLED } ?: return null
        val s = ch.settings ?: return null
        if (!ChannelLink.isPrivate(s)) return null
        // A shared channel always carries its name: the receiver's chat is found by name.
        val named = if (s.name.isBlank()) s.newBuilder().name(Channels.presetName(lora)).build() else s
        return ChannelJoin.toLink(shareSet(named, lora))
    }

    /** True for channels that may be removed: on the radio, switched on, and not the primary. */
    fun removable(current: List<Channel>, index: Int): Boolean {
        val ch = current.firstOrNull { it.index == index } ?: return false
        return index in 1 until ChannelJoin.SLOTS && ch.role == Channel.Role.SECONDARY
    }

    /** Switches channel [index] off. Returns null on success or a short reason. Never touches the primary. */
    suspend fun remove(current: List<Channel>, index: Int, setChannel: suspend (Channel) -> AdminResult<Unit>): String? {
        if (!removable(current, index)) return "Channel $index cannot be removed."
        val off = Channel.Builder().index(index).role(Channel.Role.DISABLED).settings(ChannelSettings.Builder().build()).build()
        return when (val r = setChannel(off)) {
            is AdminResult.Success -> null
            else -> "The radio did not accept the change ($r)."
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.core

import org.rescuecodec.codec.TextDomain
import org.rescuecodec.engine.ModemPreset
import org.rescuecodec.engine.Region
import org.rescuecodec.engine.TextMode
import kotlin.random.Random

/** Settings the radio layer needs. Plain values in a [KeyValueStore]. */
class AppSettings(private val store: KeyValueStore) {

    /** Short label of our team in Acks, at most 12 bytes. Never a personal name. */
    var teamLabel: String
        get() = store.getString("team_label").orEmpty()
        set(v) = store.putString("team_label", v.trim().ifEmpty { null })

    /**
     * Meshtastic channel index used for reports. -1 means automatic: the channel named [CHANNEL_NAME],
     * else the only channel with its own key, else wait (never channel 0 by default, see [ChannelChoice]).
     */
    var channelIndex: Int
        get() = store.getLong("channel_index", -1).toInt()
        set(v) = store.putLong("channel_index", v.toLong())

    var modemPreset: ModemPreset
        get() = runCatching { ModemPreset.valueOf(store.getString("modem_preset") ?: "") }.getOrDefault(ModemPreset.LONG_FAST)
        set(v) = store.putString("modem_preset", v.name)

    var region: Region
        get() = runCatching { Region.valueOf(store.getString("region") ?: "") }.getOrDefault(Region.EU_868)
        set(v) = store.putString("region", v.name)

    /** Language of readable text: "en" or "de". */
    var language: String
        get() = store.getString("language") ?: "en"
        set(v) = store.putString("language", v)

    /** Allow other apps to send reports through the Intent API. */
    var allowIntentApi: Boolean
        get() = store.getLong("allow_intent_api", 1) == 1L
        set(v) = store.putLong("allow_intent_api", if (v) 1 else 0)

    /**
     * Text mode of a channel that has no choice of its own: auto (the default), compressed or normal (spec section
     * 2.3). Direct chats start at auto. Each chat can pick its own mode ([TextRoutes.setMode]).
     */
    var defaultChannelMode: String
        get() {
            migrateTextMode()
            return store.getString("default_channel_mode")?.takeIf { it in TextMode.MODES } ?: TextMode.AUTO
        }
        set(v) = store.putString("default_channel_mode", v.takeIf { it in TextMode.MODES })

    /**
     * The old switch "Send plain text as normal Meshtastic text" is gone: packed texts replace it. Whoever had it off
     * wanted texts on port 256, so their channels start at "Always compressed". Runs once; safe to call often.
     */
    fun migrateTextMode() {
        if (store.getLong("text_mode_migrated", 0) == 1L) return
        if (store.getLong(OLD_PLAIN_TEXT_KEY, 1) == 0L) store.putString("default_channel_mode", TextMode.COMPRESSED)
        store.putLong("text_mode_migrated", 1)
    }

    /**
     * Compact texts (spec section 3.6): on a chat that packs, a text goes as 2-byte word tokens when that is smaller
     * than the packed or normal text. Only RescueCodec apps with spec 2.1 can read them. Off by default.
     */
    var compactText: Boolean
        get() = store.getLong("compact_text", 0) == 1L
        set(v) = store.putLong("compact_text", if (v) 1 else 0)

    /**
     * Team vocabulary for compact texts (spec section 3.8.6), only used with [compactText] on: "auto" (default, every
     * domain frame is tried), "general" (no domain frame) or one domain ("sar", "fire", ...: that frame and the general
     * forms). The smallest complete form still wins, never larger than plain. Receiving reads every vocabulary.
     */
    var teamVocabulary: String
        get() = store.getString("team_vocabulary")?.takeIf { it in TextDomain.SETTINGS } ?: TextDomain.AUTO
        set(v) = store.putString("team_vocabulary", v.takeIf { it in TextDomain.SETTINGS && it != TextDomain.AUTO })

    /** Show the public channel (no key or the default key, for example LongFast) in the chat list. Off by default. */
    var showPublicChannel: Boolean
        get() = store.getLong("show_public_channel", 0) == 1L
        set(v) = store.putLong("show_public_channel", if (v) 1 else 0)

    /** Name of the team channel when we last knew it, so the chat list and old rows find it without a radio. */
    var lastTeamChannel: String
        get() = store.getString("last_team_channel") ?: CHANNEL_NAME
        set(v) = store.putString("last_team_channel", v.ifBlank { null })

    /** The radio's channels when we last saw them ("index|public|name" per line), so the chat list works offline. */
    var lastRadioChannels: String?
        get() = store.getString("last_radio_channels")
        set(v) = store.putString("last_radio_channels", v)

    /** Key of the chat the person had open last ("ch:RescueCodec", "dm:0badf00d"). The app opens it at start. */
    var lastChat: String?
        get() = store.getString("last_chat")
        set(v) = store.putString("last_chat", v)

    /** Node number of the radio we talked to last (0 if never connected). */
    var lastOwnNode: Long
        get() = store.getLong("last_own_node", 0)
        set(v) = store.putLong("last_own_node", v)

    /**
     * Next message id, 1 to 16383. The counter starts at a random value on first use, so a reinstalled
     * app does not reuse ids that receivers still remember (they dedupe for 24 hours).
     */
    @Synchronized
    fun nextMessageId(): Int {
        val last = store.getLong("id_counter", 0).toInt()
        val base = if (last in 1..MAX_ID) last else Random.nextInt(1, MAX_ID + 1)
        val next = if (last in 1..MAX_ID) (base % MAX_ID) + 1 else base
        store.putLong("id_counter", next.toLong())
        return next
    }

    companion object {
        const val MAX_ID = 16383
        const val CHANNEL_NAME = "RescueCodec"

        /** Key of the removed setting "Send plain text as normal Meshtastic text" (1 = on, the old default). */
        const val OLD_PLAIN_TEXT_KEY = "plain_text_as_meshtastic"
    }
}

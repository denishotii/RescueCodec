// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.core

import org.rescuecodec.engine.ModemPreset
import org.rescuecodec.engine.Region
import kotlin.random.Random

/** Settings the radio layer needs. Plain values in a [KeyValueStore]. */
class AppSettings(private val store: KeyValueStore) {

    /** Short label of our team in Acks, at most 12 bytes. Never a personal name. */
    var teamLabel: String
        get() = store.getString("team_label").orEmpty()
        set(v) = store.putString("team_label", v.trim().ifEmpty { null })

    /** Meshtastic channel index used for reports. -1 means: find the channel named [CHANNEL_NAME], else use 0. */
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
    }
}

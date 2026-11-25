// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.radio

import org.rescuecodec.app.core.KeyValueStore

/** A radio we can connect to. */
sealed interface RadioTarget {
    /** Short text for the screen, for example "Heltec V3 1a2b" or "10.0.2.2:4403". */
    val label: String

    /** Text form for saving: "tcp|host|port" or "ble|address|name". */
    fun encode(): String

    /** Network (Wi-Fi) connection. The Android emulator reaches the simulator at 10.0.2.2:4403. */
    data class Tcp(val host: String, val port: Int = 4403) : RadioTarget {
        override val label get() = "$host:$port"
        override fun encode() = "tcp|$host|$port"
    }

    /** Bluetooth LE radio, by its address. */
    data class Ble(val address: String, val name: String? = null) : RadioTarget {
        override val label get() = name?.takeIf { it.isNotBlank() } ?: address
        override fun encode() = "ble|$address|${name.orEmpty()}"
    }

    companion object {
        fun decode(text: String?): RadioTarget? {
            val p = text?.split('|') ?: return null
            return when (p.firstOrNull()) {
                "tcp" -> if (p.size >= 3) p[2].toIntOrNull()?.let { Tcp(p[1], it) } else null
                "ble" -> if (p.size >= 2 && p[1].isNotBlank()) Ble(p[1], p.getOrNull(2)?.ifBlank { null }) else null
                else -> null
            }
        }
    }
}

/** A Bluetooth radio found by a scan. */
data class DiscoveredRadio(val address: String, val name: String?, val rssi: Int)

/** Remembers the last radio, so the app can reconnect after a restart. */
class LastRadioStore(private val store: KeyValueStore) {
    fun get(): RadioTarget? = RadioTarget.decode(store.getString(KEY))
    fun set(target: RadioTarget) = store.putString(KEY, target.encode())
    fun clear() = store.putString(KEY, null)

    private companion object { const val KEY = "last_radio" }
}

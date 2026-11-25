// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.core

import org.rescuecodec.codec.Json
import org.rescuecodec.codec.RescueCodec

/** Turns stored message JSON into the readable line in the chosen language. */
object Readable {
    /** The readable line, or null when the JSON is missing or not a message. */
    fun of(json: String?, lang: String): String? {
        if (json == null) return null
        return runCatching { RescueCodec.reconstruct(Json.parseObject(json), lang) }.getOrNull()
    }

    /** The head word only ("Person found", "MAYDAY") for a type, in [lang]. */
    fun head(type: String, lang: String): String = Labels.head(lang, type)

    /** The readable line without the "Position x, y" sentence: the card shows the grid reference as a chip instead. */
    fun withoutPosition(json: String?, lang: String): String? {
        if (json == null) return null
        return runCatching {
            val obj = Json.parseObject(json).toMutableMap()
            obj.remove("pos")
            RescueCodec.reconstruct(obj, lang)
        }.getOrNull()
    }

    /** UTM grid reference of the report position, or null. */
    fun gridRef(json: String?): String? {
        if (json == null) return null
        return runCatching {
            val pos = Json.parseObject(json)["pos"] as? Map<*, *> ?: return null
            Utm.label((pos["lat"] as Number).toDouble(), (pos["lon"] as Number).toDouble())
        }.getOrNull()
    }

    /** Number of persons in a Mayday, or null. */
    fun maydayPersons(json: String?): Int? {
        if (json == null) return null
        return runCatching { ((Json.parseObject(json)["mayday"] as? Map<*, *>)?.get("persons") as? Number)?.toInt()?.takeIf { it > 0 } }.getOrNull()
    }
}

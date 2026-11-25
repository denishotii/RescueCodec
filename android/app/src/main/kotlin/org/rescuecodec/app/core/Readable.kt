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
}

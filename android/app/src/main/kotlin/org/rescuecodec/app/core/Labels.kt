// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.core

import org.rescuecodec.codec.Json
import org.rescuecodec.codec.RescueCodec

/**
 * Words for message values ("conscious", "Unterkühlung") from the spec word files in `:codec`
 * (spec/i18n). The app does not keep a second copy, so a chip says exactly what the readable
 * line says.
 */
object Labels {
    private val cache = HashMap<String, Map<String, Any?>>()

    @Suppress("UNCHECKED_CAST")
    private fun file(lang: String): Map<String, Any?> = synchronized(cache) {
        cache.getOrPut(lang) {
            val stream = RescueCodec::class.java.getResourceAsStream("/rescuecodec/i18n/$lang.json")
                ?: RescueCodec::class.java.getResourceAsStream("/rescuecodec/i18n/en.json")
                ?: error("i18n word files are missing from :codec")
            Json.parseObject(stream.readBytes().toString(Charsets.UTF_8))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun section(lang: String, name: String) = file(lang)[name] as? Map<String, String> ?: emptyMap()

    /** Word for a value, for example `value("de", "injuries", "hypothermia")`. Falls back to the code. */
    fun value(lang: String, group: String, code: String): String =
        section(lang, "value")["$group.$code"] ?: section("en", "value")["$group.$code"] ?: code.replace('_', ' ')

    /** True when the word file of [lang] has a word for this value (used by tests). */
    fun has(lang: String, group: String, code: String): Boolean = section(lang, "value").containsKey("$group.$code")

    /** Head word of a message type, for example "Person gefunden". Placeholders are removed. */
    fun head(lang: String, type: String): String =
        (section(lang, "head")[type] ?: section("en", "head")[type] ?: type)
            .replace(Regex("\\s*#?\\{[a-z]+\\}"), "").trim()
}

// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.engine

import org.rescuecodec.codec.Json

/**
 * The SAR vocabulary from `spec/vocab/sar.yaml`: for every code, the German and English words that
 * name it. The YAML file is converted to JSON at build time and bundled as a resource, so new
 * words need no code change.
 */
class Vocab private constructor(private val root: Map<String, Any?>) {
    /** Words that select a message type, by type name (found, sector, request, hazard, mayday, ...). */
    val messages: Map<String, List<String>> = (root["messages"] as Map<*, *>).entries.associate { (k, v) ->
        k.toString() to words(v as Map<*, *>)
    }

    /** One entry per field group (for example `injuries`, `request.resource`): code to its words. */
    val fields: Map<String, Map<String, List<String>>> = (root["fields"] as Map<*, *>).entries.associate { (k, v) ->
        val values = (v as Map<*, *>)["values"] as List<*>
        k.toString() to values.associate { e ->
            e as Map<*, *>
            e["code"].toString() to words(e)
        }
    }

    private val modifiers = root["modifiers"] as Map<*, *>

    /** Negation words ("nicht", "not", ...). */
    val negation: List<String> = modWords("negation")

    /** Words that make a fact unclear ("unklar", "maybe", ...). */
    val uncertainty: List<String> = modWords("uncertainty")

    /** Words that mark a count of helpers ("Helfer", "more people"). */
    val helpers: List<String> = modWords("helpers")

    /** Words that mark a count of persons ("Wanderer", "kids"). */
    val persons: List<String> = modWords("persons")

    /** Words around a hazard radius ("Umkreis", "around"). */
    val radius: List<String> = modWords("radius")

    /** Coverage words with a fixed percent ("zur Hälfte" = 50). */
    val coverageFixed: Map<String, Int> = ((modifiers["coverage"] as Map<*, *>)["fixed"] as? Map<*, *>)
        ?.entries?.associate { (k, v) -> k.toString() to (v as Number).toInt() } ?: emptyMap()

    /** Number words to values ("zwo" = 2). */
    val numbers: Map<String, Int> = HashMap<String, Int>().also { m ->
        val n = modifiers["numbers"] as Map<*, *>
        for (lang in listOf("en", "de")) (n[lang] as? Map<*, *>)?.forEach { (k, v) -> m[k.toString()] = (v as Number).toInt() }
    }

    val specVersion: String = root["spec_version"].toString()

    private fun modWords(name: String): List<String> = words(modifiers[name] as Map<*, *>)

    companion object {
        private fun words(m: Map<*, *>): List<String> =
            listOf("en", "de").flatMap { lang -> (m[lang] as? List<*>)?.map { it.toString() } ?: emptyList() }

        /** The vocabulary bundled in this library. */
        val default: Vocab by lazy {
            val stream = Vocab::class.java.getResourceAsStream("/rescuecodec/vocab/sar.json")
                ?: error("rescuecodec/vocab/sar.json is missing from the library resources")
            Vocab(Json.parseObject(stream.use { String(it.readBytes(), Charsets.UTF_8) }))
        }

        /** Loads a vocabulary from JSON text (the YAML file converted to JSON). */
        fun fromJson(json: String): Vocab = Vocab(Json.parseObject(json))
    }
}

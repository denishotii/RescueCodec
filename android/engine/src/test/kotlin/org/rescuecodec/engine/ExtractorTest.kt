// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.rescuecodec.codec.Json
import org.rescuecodec.codec.RescueCodec

/** Scores the extractor on spec/vectors/extraction.json (level 3 conformance). */
class ExtractorTest {
    private val extractor = Extractor()

    private fun same(a: Any?, b: Any?): Boolean = when {
        a is Map<*, *> && b is Map<*, *> -> a.keys == b.keys && a.keys.all { same(a[it], b[it]) }
        a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun scoresOnSharedSentences() {
        val file = File(System.getProperty("rescuecodec.spec"), "vectors/extraction.json")
        val cases = Json.parseObject(file.readText())["cases"] as List<Map<String, Any?>>
        var pass = 0
        val failures = ArrayList<String>()
        for (c in cases) {
            val r = extractor.extract(c["text"] as String, c["lang"] as String)
            val ok = same(c["expect"], r.message) && r.unsupported == (c["unsupported"] == true)
            if (ok) pass++ else failures.add("${c["name"]}: expected ${Json.stringify(c["expect"])} got ${Json.stringify(r.message)} (unsupported=${r.unsupported}, ${r.reasons})")
        }
        println("EXTRACTION SCORE $pass/${cases.size}")
        failures.forEach { println("  FAIL $it") }
        assertEquals(cases.size, pass, "extraction score $pass/${cases.size}:\n" + failures.joinToString("\n"))
    }

    @Test
    fun everyExtractedMessageEncodes() {
        // Add the fields the app adds (id) and check the wire codec accepts the result.
        val sentences = listOf(
            "Kind gefunden, ansprechbar, stark unterkühlt, Knöchel verletzt. Wir brauchen eine Trage und zwei Leute.",
            "Sektor 12 abgesucht, nichts gefunden", "SOS", "Windbruch, Weg komplett blockiert",
            "Brauchen zwo Tragen", "Person gefunden bei 50.69212, 7.21345, ansprechbar",
        )
        for (t in sentences) {
            val m = LinkedHashMap(extractor.extract(t).message)
            if (m.keys.any { it in setOf("found", "request", "hazard", "mayday") }) m["id"] = 5
            RescueCodec.encode(m)
        }
    }

    @Test
    fun heldOutSentences() {
        // Sentences written after the shared set, to check the rules are not tuned to it.
        fun msg(t: String) = extractor.extract(t)
        fun found(t: String): Map<*, *> = msg(t).message["found"] as Map<*, *>

        assertEquals(listOf("hypothermia"), found("Wir haben eine Frau gefunden, sie ist unterkühlt").get("injuries"))
        assertEquals("unconscious", found("Mann gefunden, nicht ansprechbar").get("consciousness"))
        assertEquals("conscious", found("Man found, not unconscious, talking to us").get("consciousness"))
        assertEquals("none", found("kind gefunden, atmet nicht").get("breathing"))
        assertEquals(listOf("stretcher", "medic"), found("Hiker found, broken leg, need paramedic and stretcher").get("needs"))
        assertEquals("critical", msg("Found child, not breathing, hurry").message["priority"])
        assertEquals(mapOf("reason" to "trapped"), msg("Mayday we are stuck").message["mayday"])
        assertEquals(mapOf("sector" to 3, "status" to "cleared"), msg("Abschnitt 3 komplett abgesucht").message["sector"])
        assertEquals(mapOf("kind" to "other", "severity" to "high"), msg("Warning, avalanche danger, do not enter").message["hazard"])
        assertTrue(msg("Wir treffen uns um acht am Parkplatz").unsupported)
        assertTrue(msg("").unsupported)
    }
}

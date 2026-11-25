// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.engine

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.rescuecodec.codec.Codes
import org.rescuecodec.codec.Json
import org.rescuecodec.codec.RescueCodec
import org.rescuecodec.codec.TextDomain

/**
 * The send rule with team vocabularies (spec section 2.3 rule 6 and 3.8.6): the strictly smallest complete payload,
 * plain wins ties, auto tries every domain frame, a pinned domain only that one, general none. Runs the `choice` cases
 * of spec/vectors/domain.json (the same bytes as Python) and the checks of tests/test_v2_domain_frame.py.
 */
class DomainChoiceTest {
    private val medical = "Patient is conscious, bleeding from the leg, apply a tourniquet."
    private val pos = mapOf("lat" to 50.69212, "lon" to 7.21345)

    @Suppress("UNCHECKED_CAST")
    private val vectors: Map<String, Any?> = Json.parseObject(File(System.getProperty("rescuecodec.spec"), "vectors/domain.json").readText(Charsets.UTF_8))

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun form(text: String, vocab: String = "auto", extra: Map<String, Any?> = emptyMap()) =
        TextMode.chooseForm(linkedMapOf<String, Any?>("id" to 3).apply { putAll(extra); put("text", mapOf("text" to text)) }, true, "x", compact = true, vocab = vocab)

    @Test
    @Suppress("UNCHECKED_CAST")
    fun vectorChoices() {
        val cases = vectors["choice"] as List<Map<String, Any?>>
        assertTrue(cases.size >= 10)
        for (case in cases) {
            val name = case["name"] as String
            val f = form(case["text"] as String, case["setting"] as String)
            assertEquals(listOf(case["form"], case["vocab"], (case["size"] as Number).toInt(), case["hex"]), listOf(f.form, f.vocab, f.payload.size, hex(f.payload)), name)
            val sizes = case["sizes"] as Map<String, Any?>
            val domain = (sizes["domain"] as Map<String, Any?>).mapValues { (it.value as Number).toInt() }
            assertEquals(
                listOf((sizes["plain"] as Number).toInt(), (sizes["lean"] as Number?)?.toInt(), (sizes["words"] as Number?)?.toInt(), domain),
                listOf(f.plainSize, f.leanSize, f.wordsSize, f.domainSizes ?: emptyMap<String, Int>()),
                name,
            )
            assertEquals(domain.keys.toList(), (f.domainSizes ?: emptyMap()).keys.toList(), "$name: domains in id order")
        }
    }

    @Test
    fun domainFrameWinsAndKeepsPriorityLocally() {
        val f = form(medical, extra = mapOf("priority" to "urgent"))
        assertEquals(Triple("domain", "medical", 19), Triple(f.form, f.vocab, f.payload.size))
        assertEquals(mapOf("priority" to "urgent", "text" to mapOf("text" to medical, "vocab" to 5)), f.message)
        assertContentEquals(byteArrayOf(3, 5), f.payload.copyOf(2))
        assertEquals(19, f.domainSize)
        assertTrue(f.packed)
        assertNull(f.message["id"]) // a domain frame has no id
    }

    /** Random text from the S&R vocabulary, word dictionary 2 and odd characters. */
    private fun randomText(rnd: Random): String {
        val dd = TextDomain.vocabulary(2)
        val words = listOf("Müller", "🙂", "50.6921", "7.2134", "DL1ABC", "Löwenburg", ",", ".", "(", ")", "the", "we", "need")
        val sb = StringBuilder()
        repeat(rnd.nextInt(1, 40)) {
            var w = if (rnd.nextDouble() < 0.6) dd.entries[rnd.nextInt(dd.entries.size)] else words[rnd.nextInt(words.size)]
            if (rnd.nextDouble() < 0.2) w = w.take(1).uppercase() + w.drop(1)
            sb.append(w).append(listOf(" ", " ", " ", "", "\n")[rnd.nextInt(5)])
        }
        return sb.toString().take(300).trim().ifEmpty { "x" }
    }

    @Test
    fun neverLargerThanPlainOrTheGeneralForms() {
        val rnd = Random(3)
        for (vocab in TextMode.VOCAB_SETTINGS) {
            repeat(25) {
                val text = randomText(rnd)
                val f = form(text, vocab)
                val sizes = listOfNotNull(f.leanSize, f.wordsSize, f.packedSize, f.domainSize)
                if (f.form == "plain") {
                    assertTrue(sizes.all { f.payload.size <= it } || f.cut, text)
                } else {
                    assertEquals(sizes.min(), f.payload.size, text)
                    assertTrue(f.payload.size < f.plainSize || f.cut, text)
                    val decoded = RescueCodec.decodeAny(f.payload)
                    assertEquals(f.text, (decoded["text"] as Map<*, *>)["text"], text)
                }
                if (vocab == "general") assertNull(f.domainSizes)
                else if (vocab != "auto" && f.domainSizes != null) assertEquals(setOf(vocab), f.domainSizes!!.keys)
                if (f.form == "domain") assertEquals(f.domainSize, f.payload.size)
            }
        }
    }

    @Test
    fun pinnedVocabularyTriesOnlyThatOne() {
        assertEquals("lean", form(medical, "sar").form)
        assertEquals("domain" to "medical", form(medical, "medical").let { it.form to it.vocab })
        assertEquals("lean", form(medical, "general").form)
        assertFailsWith<IllegalArgumentException> { form(medical, "sars") }
    }

    @Test
    fun aPositionKeepsTheEnvelope() {
        val f = form(medical, extra = mapOf("pos" to pos))
        assertEquals("words", f.form)
        assertNull(f.domainSizes)
        assertEquals(pos, RescueCodec.decode(f.payload)["pos"])
    }

    @Test
    fun compactOffNeverSendsADomainFrame() {
        val f = TextMode.chooseForm(mapOf("id" to 3, "text" to mapOf("text" to medical)), true, "x")
        assertEquals("deflate", f.form)
        assertNull(f.domainSizes)
        val public = TextMode.chooseForm(mapOf("id" to 3, "text" to mapOf("text" to medical)), false, "public_channel", compact = true)
        assertEquals("plain", public.form)
    }

    @Test
    fun longTextIsFittedWithDomainFrames() {
        // Over 200 bytes of text: a domain frame can keep it whole in one packet.
        val text = "Patient is conscious, bleeding from the leg, apply a tourniquet. Check breathing and pulse every five " +
            "minutes. Keep the patient warm, raise the legs and give nothing by mouth. Ambulance needed at the trailhead."
        assertTrue(text.toByteArray(Charsets.UTF_8).size > Codes.MAX_TEXT_BYTES) // 210 bytes
        val f = form(text)
        assertTrue(f.packed)
        assertEquals(false, f.cut)
        assertEquals(listOf("domain", "medical", 68, 96, 102, 127), listOf(f.form, f.vocab, f.payload.size, f.leanSize, f.wordsSize, f.packedSize))
        assertEquals(mapOf("emergency" to 101, "sar" to 95, "fire" to 98, "hiking" to 91, "medical" to 68), f.domainSizes)
        assertEquals(text, (RescueCodec.decodeAny(f.payload)["text"] as Map<*, *>)["text"])
        assertTrue(f.payload.size <= Codes.MAX_ENCODE_BYTES)
    }
}

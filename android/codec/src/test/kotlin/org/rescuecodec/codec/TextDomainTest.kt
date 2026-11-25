// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.codec

import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Domain frames (spec section 3.8, first byte 0x03): the shared vectors spec/vectors/domain.json (the same bytes as
 * Python), the bundled vocabularies against spec/vocab, round trips, errors. Same checks as
 * tests/test_v2_domain_frame.py.
 */
class TextDomainTest {
    private val spec = File(System.getProperty("rescuecodec.spec"))

    @Suppress("UNCHECKED_CAST")
    private val vectors: Map<String, Any?> = Json.parseObject(File(spec, "vectors/domain.json").readText(Charsets.UTF_8))

    @Suppress("UNCHECKED_CAST")
    private fun cases(name: String) = vectors[name] as List<Map<String, Any?>>

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** A vocabulary of a vector: a domain name or an id. */
    private fun vid(v: Any?): Int = when (v) {
        is Number -> v.toInt()
        else -> TextDomain.vocabId(v as String)!!
    }

    private val medical = "Patient is conscious, bleeding from the leg, apply a tourniquet."

    /** Numbers compare by value (Int 7 equals Long 7), maps and lists deeply. */
    private fun same(a: Any?, b: Any?): Boolean = when {
        a is Map<*, *> && b is Map<*, *> -> a.keys == b.keys && a.keys.all { same(a[it], b[it]) }
        a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }

    private fun assertSame(expected: Any?, actual: Any?, name: String) =
        assertTrue(same(expected, actual), "$name: expected ${Json.stringify(expected)} but got ${Json.stringify(actual)}")

    // ------------------------------------------------------------ vocabularies

    @Test
    fun vocabulariesMatchDomainsYamlAndTheFiles() {
        // A small reader for the fields we need: the YAML stays the one source in spec/vocab.
        val yaml = File(spec, "vocab/domains.yaml").readText(Charsets.UTF_8)
        assertTrue(Regex("(?m)^reserved_codes: 8\\b").containsMatchIn(yaml))
        assertTrue(yaml.contains("general_dictionary: {file: words-dict-v2.txt, version: 2, sha256: ${TextWords.DICTS.getValue(2).second}}"))
        val rows = Regex("(?ms)^  - id: (\\d+)\\n(.*?)(?=^  - id: |^\\S)").findAll(yaml).associate { m ->
            val fields = Regex("(?m)^    (\\w+): \"?(.*?)\"?$").findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
            m.groupValues[1].toInt() to fields
        }
        assertEquals(TextDomain.VOCABS.keys, rows.keys)
        for ((id, v) in TextDomain.VOCABS) {
            val row = rows.getValue(id)
            assertEquals(
                listOf(row["domain"], row["label"], row["short"], row["version"], row["bits"], row["entries"], row["file"], row["sha256"]),
                listOf(v.domain, v.label, v.short, "${v.version}", "${v.bits}", "${v.entries}", v.file, v.sha256),
                "vocabulary $id",
            )
            assertTrue(TextDomain.available(id))
            assertEquals((1 shl v.bits) - TextDomain.RESERVED, TextDomain.vocabulary(id).entries.size)
        }
    }

    @Test
    fun bundledCopiesAreTheSpecFiles() {
        // The library reads the copies processResources made; they must be the spec files byte for byte.
        for (v in TextDomain.VOCABS.values) {
            val bundled = TextDomain::class.java.getResourceAsStream("/rescuecodec/vocab/${v.file}")!!.use { it.readBytes() }
            val original = File(spec, "vocab/${v.file}").readBytes()
            assertEquals(v.sha256, sha(original), v.file)
            assertEquals(v.sha256, sha(bundled), v.file)
            assertContentEquals(original, bundled, v.file)
        }
        for (row in cases("vocabularies")) {
            val v = TextDomain.VOCABS.getValue((row["id"] as Number).toInt())
            assertEquals(listOf(row["domain"], row["sha256"], row["file"]), listOf(v.domain, v.sha256, v.file))
            assertEquals(listOf(v.bits, v.entries), listOf((row["bits"] as Number).toInt(), (row["entries"] as Number).toInt()))
        }
    }

    @Test
    fun settings() {
        assertEquals(listOf("auto", "general", "emergency", "sar", "fire", "hiking", "medical"), TextDomain.SETTINGS)
        assertEquals(listOf(1, 2, 3, 4, 5), TextDomain.candidates("auto"))
        assertEquals(emptyList(), TextDomain.candidates("general"))
        assertEquals(listOf(2), TextDomain.candidates("sar"))
        assertFailsWith<IllegalArgumentException> { TextDomain.candidates("SAR") }
        for (bad in listOf("0", "6", "255", "x", "")) assertNull(TextDomain.vocabId(bad), bad)
        assertEquals(2, TextDomain.vocabId("sar"))
    }

    @Test
    fun damagedCopyIsUnsupportedNotWords() {
        val data = RescueCodec.encodeDomain("Apply a tourniquet.", "medical")
        val v = TextDomain.VOCABS.getValue(5)
        val original = File(spec, "vocab/${v.file}").readBytes()
        val lines = String(original, Charsets.US_ASCII).split("\n").toMutableList()
        lines[39] = lines[40].also { lines[40] = lines[39] } // two entries swapped
        val damaged = lines.joinToString("\n").toByteArray(Charsets.US_ASCII)
        assertNull(TextDomain.checked(damaged, v))
        assertNull(TextDomain.checked(String(original, Charsets.US_ASCII).replace("\n", "\r\n").toByteArray(), v), "CRLF copy")
        assertNull(TextDomain.checked(null, v))
        assertNotNull(TextDomain.checked(original, v))
        try {
            TextDomain.useCopy(5, damaged)
            assertFalse(TextDomain.available(5))
            assertTrue(TextDomain.available(2))
            assertEquals(mapOf("v" to 2, "unknown" to mapOf("vocab" to 5)), RescueCodec.decodeAny(data))
            assertEquals("invalid_value", assertFailsWith<CodecException> { RescueCodec.encodeDomain("Apply a tourniquet.", 5) }.code)
        } finally {
            TextDomain.useCopy(5, null)
        }
        assertTrue(TextDomain.available(5))
    }

    // ------------------------------------------------------------ vectors

    @Test
    @Suppress("UNCHECKED_CAST")
    fun frames() {
        for (case in cases("frames")) {
            val name = case["name"] as String
            val text = case["text"] as String
            val data = RescueCodec.encodeDomain(text, case["vocab"] as String)
            assertEquals(case["hex"], hex(data), name)
            assertContentEquals(data, RescueCodec.encodeDomain(text, (case["vocab_id"] as Number).toInt()), name)
            assertSame(case["size"], data.size, name)
            assertSame(case["decoded"], RescueCodec.decodeDomain(data), name)
            assertSame(mapOf("v" to 2) + (case["decoded"] as Map<String, Any?>), RescueCodec.decodeAny(data), name)
            val trace = ArrayList<String>()
            TextDomain.decode(data.copyOfRange(2, data.size), vid(case["vocab"]), trace)
            assertEquals(case["trace"], trace, name)
        }
    }

    @Test
    fun streamsAndRandom() {
        for (case in cases("streams") + cases("random")) {
            val name = case["name"] as String
            val vocab = vid(case["vocab"])
            assertEquals(case["stream"], hex(TextDomain.encode(case["text"] as String, vocab)), name)
            assertEquals(case["text"], TextDomain.decode(hex(case["stream"] as String), vocab), name)
        }
    }

    @Test
    fun invalid() {
        for (case in cases("invalid_decode")) {
            val name = case["name"] as String
            val data = hex(case["hex"] as String)
            assertEquals(case["error"], assertFailsWith<CodecException>(name) { RescueCodec.decodeDomain(data) }.code, name)
            assertFailsWith<CodecException>(name) { RescueCodec.decodeAny(data) }
        }
        for (case in cases("invalid_encode")) {
            val name = case["name"] as String
            val text = case["text"] as String
            val e = assertFailsWith<CodecException>(name) {
                when (val v = case["vocab"]) {
                    is Number -> RescueCodec.encodeDomain(text, v.toInt())
                    else -> RescueCodec.encodeDomain(text, v as String)
                }
            }
            assertEquals(case["error"], e.code, name)
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun unknownVocabularyIsVisible() {
        for (case in cases("unknown")) {
            val name = case["name"] as String
            val msg = RescueCodec.decodeDomain(hex(case["hex"] as String))
            assertSame(case["decoded"], msg, name)
            val render = case["render"] as Map<String, String>
            assertEquals(render["en"], RescueCodec.reconstruct(msg, "en"), name)
            assertEquals(render["de"], RescueCodec.reconstruct(msg, "de"), name)
            assertTrue("unsupported vocabulary" in RescueCodec.reconstruct(msg, "en"))
        }
    }

    // ------------------------------------------------------------ round trips

    private fun randomText(rnd: Random, vocab: Int): String {
        val dd = TextDomain.vocabulary(vocab)
        val d2 = TextWords.dictionary(2)
        val split = ".,;:!?()[]{}\"…" + "-'/#"
        val sb = StringBuilder()
        repeat(rnd.nextInt(0, 31)) {
            val r = rnd.nextDouble()
            var w = when {
                r < 0.35 -> dd.entries[rnd.nextInt(dd.entries.size)]
                r < 0.55 -> d2.entries[rnd.nextInt(d2.entries.size)]
                r < 0.7 -> split[rnd.nextInt(split.length)].toString()
                r < 0.85 -> buildString {
                    repeat(rnd.nextInt(1, 7)) {
                        val cp = when (rnd.nextInt(4)) {
                            0 -> rnd.nextInt(0x20, 0x7F)
                            1 -> rnd.nextInt(0xA0, 0x3000)
                            2 -> rnd.nextInt(0x1F300, 0x1F700)
                            else -> rnd.nextInt(0xE000, 0xFFFE)
                        }
                        appendCodePoint(cp)
                    }
                }
                else -> rnd.nextLong(0, 100_000_000).toString()
            }
            if (rnd.nextDouble() < 0.2) w = w.take(1).uppercase() + w.drop(1)
            else if (rnd.nextDouble() < 0.1) w = w.uppercase()
            sb.append(w)
            sb.append(listOf(" ", " ", " ", " ", " ", " ", " ", " ", " ", " ", "", "  ", "\n", "\t", "\r\n", "  ")[rnd.nextInt(16)])
        }
        return sb.toString()
    }

    /** A prefix of at most [n] UTF-16 units that does not split a surrogate pair. */
    private fun cut(s: String, n: Int): String {
        var k = minOf(n, s.length)
        if (k in 1 until s.length && Character.isHighSurrogate(s[k - 1])) k--
        return s.substring(0, k)
    }

    @Test
    fun randomUtf8RoundTripForEveryVocabulary() {
        val rnd = Random(7)
        for ((domain, id) in TextDomain.CURRENT) {
            repeat(300) {
                val text = cut(randomText(rnd, id), rnd.nextInt(0, 601))
                val stream = TextDomain.encode(text, id)
                if (text.isEmpty()) {
                    assertEquals(0, stream.size)
                    return@repeat
                }
                assertEquals(text, TextDomain.decode(stream, id), "$domain: $text")
                val raw = text.toByteArray(Charsets.UTF_8).size
                assertTrue(stream.size <= raw + 3 * (raw / 255 + 1), "$domain: never worse than LIT: $text")
                val data = try {
                    RescueCodec.encodeDomain(text, domain)
                } catch (e: CodecException) {
                    assertEquals("too_large", e.code)
                    assertTrue(2 + stream.size > 200 || raw > TextWords.MAX_TEXT_BYTES)
                    return@repeat
                }
                assertEquals(mapOf("text" to mapOf("text" to text, "vocab" to id)), RescueCodec.decodeDomain(data))
            }
        }
    }

    @Test
    fun randomBytesNeverCrash() {
        val rnd = Random(11)
        repeat(3000) {
            val vocab = if (rnd.nextBoolean()) rnd.nextInt(1, 6) else rnd.nextInt(0, 256)
            val data = byteArrayOf(3, vocab.toByte()) + rnd.nextBytes(rnd.nextInt(0, 61))
            val msg = try {
                RescueCodec.decodeAny(data)
            } catch (e: CodecException) {
                assertTrue(e.code in setOf("malformed", "too_large"), e.code)
                return@repeat
            }
            assertEquals(if ("text" in msg) setOf("text") else setOf("unknown"), msg.keys - "v")
        }
    }

    @Test
    fun everyShortPayload() {
        for (a in 0 until 256) {
            val payloads = listOf(byteArrayOf(3, a.toByte())) + (0 until 256 step 17).map { b -> byteArrayOf(3, 2, a.toByte(), b.toByte()) }
            for (data in payloads) {
                try {
                    RescueCodec.decodeAny(data)
                } catch (e: CodecException) {
                    assertTrue(e.code in setOf("malformed", "too_large"), e.code)
                }
            }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aCutFrameIsAnErrorOrAShorterPrefix() {
        // The frame has no length: a cut on a code boundary with zero padding reads as a shorter text, like 3.7.
        val rnd = Random(5)
        for ((domain, id) in TextDomain.CURRENT) {
            repeat(30) {
                val text = cut(randomText(rnd, id), 150).ifEmpty { "x" }
                val data = try {
                    RescueCodec.encodeDomain(text, domain)
                } catch (e: CodecException) {
                    return@repeat
                }
                for (n in 2 until data.size) {
                    val got = try {
                        (RescueCodec.decodeDomain(data.copyOf(n))["text"] as Map<String, Any?>)["text"] as String
                    } catch (e: CodecException) {
                        assertEquals("malformed", e.code)
                        continue
                    }
                    assertTrue(text.startsWith(got) && got != text, "$text / $n / $got")
                }
            }
        }
    }

    // ------------------------------------------------------------ codec

    @Test
    fun vocabularyWordsCost10Bits() {
        assertEquals("0305e300609c00", hex(RescueCodec.encodeDomain("Apply a tourniquet.", "medical")))
        assertEquals(4, RescueCodec.encodeDomain("tourniquet", "medical").size) // 10 bits -> 2 bytes + 2
        assertEquals(6, RescueCodec.encodeDomain("ridge", "fire").size) // ESC16: 26 bits -> 4 bytes + 2
        assertEquals(15, RescueCodec.encodeDomain("tourniquet", "fire").size) // not in dictionary 2: LIT, 98 bits
        assertEquals(19, RescueCodec.encodeDomain(medical, "medical").size)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun neverANearMatch() {
        for (text in listOf("tourniquets", "Tourniquet!", "TOURNIQUEt", "tourniquet ", "touniquet", "Café", "🙂 ok")) {
            assertEquals(text, (RescueCodec.decodeDomain(RescueCodec.encodeDomain(text, "medical"))["text"] as Map<String, Any?>)["text"])
        }
    }

    @Test
    fun firstByte() {
        val data = RescueCodec.encodeDomain("Apply a tourniquet.", "medical")
        assertTrue(RescueCodec.isDomainFrame(data))
        assertFalse(RescueCodec.isDomainFrame(RescueCodec.encodeCompact("hello")))
        assertFalse(RescueCodec.isCompactFrame(data))
        assertEquals(0, RescueCodec.detectVersion(data)) // not an Envelope: decode() rejects it
        for (first in 0x04..0x07) {
            assertEquals("unsupported_version", assertFailsWith<CodecException> { RescueCodec.decodeAny(byteArrayOf(first.toByte(), 1, 0)) }.code)
        }
    }

    @Test
    fun vocabIsNotAnEnvelopeField() {
        val e = assertFailsWith<CodecException> { RescueCodec.encode(mapOf("id" to 1, "text" to mapOf("text" to "fire", "vocab" to 3))) }
        assertEquals("invalid_value" to "text.vocab", e.code to e.field)
    }

    @Test
    fun loneSurrogateIsRefused() {
        assertEquals("invalid_value", assertFailsWith<CodecException> { RescueCodec.encodeDomain("fire \uD83D", "fire") }.code)
    }

    @Test
    fun render() {
        val msg = RescueCodec.decodeAny(RescueCodec.encodeDomain("Apply a tourniquet.", "medical"))
        assertEquals("Message: Apply a tourniquet.", RescueCodec.reconstruct(msg, "en"))
    }

    @Test
    fun literalChunksEndOnWholeCharacters() {
        val run = ("Q".repeat(254) + "ä" + "Q".repeat(300)).toByteArray(Charsets.UTF_8)
        val chunks = TextDomain.literalChunks(run)
        assertEquals(listOf(254, 255, 47), chunks.map { it.size })
        assertContentEquals(run, chunks.reduce { a, b -> a + b })
    }
}

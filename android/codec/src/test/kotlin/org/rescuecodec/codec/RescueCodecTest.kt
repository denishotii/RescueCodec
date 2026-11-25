// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.codec

import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/** Runs spec/vectors/codec.json: every valid case byte for byte, every invalid case rejected. */
class RescueCodecTest {
    @Suppress("UNCHECKED_CAST")
    private val vectors: Map<String, Any?> = Json.parseObject(
        File(System.getProperty("rescuecodec.spec"), "vectors/codec.json").readText(),
    )

    @Suppress("UNCHECKED_CAST")
    private fun cases(key: String) = vectors[key] as List<Map<String, Any?>>

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    /** Numbers compare by value (Int 7 equals Long 7), maps and lists deeply. */
    private fun same(a: Any?, b: Any?): Boolean = when {
        a is Map<*, *> && b is Map<*, *> -> a.keys == b.keys && a.keys.all { same(a[it], b[it]) }
        a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }

    private fun assertSame(expected: Any?, actual: Any?, name: String) {
        if (!same(expected, actual)) fail("$name: expected ${Json.stringify(expected)} but got ${Json.stringify(actual)}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun obj(v: Any?) = v as Map<String, Any?>

    @Test
    fun validCases() {
        val list = cases("valid")
        assertTrue(list.size >= 40)
        for (c in list) {
            val name = c["name"] as String
            val bytes = RescueCodec.encode(obj(c["input"]))
            assertEquals(c["hex"], bytes.hex(), "$name: encode")
            assertSame(c["decoded"], RescueCodec.decode(hex(c["hex"] as String)), "$name: decode")
            assertSame(c["decoded"], RescueCodec.decodeAny(hex(c["hex"] as String)).filterKeys { it != "v" }, "$name: decodeAny")
        }
    }

    @Test
    fun decodeOnlyCases() {
        for (c in cases("decode_only")) {
            assertSame(c["decoded"], RescueCodec.decode(hex(c["hex"] as String)), c["name"] as String)
        }
    }

    @Test
    fun invalidEncodeCases() {
        for (c in cases("invalid_encode")) {
            val name = c["name"] as String
            val e = assertFailsWith<CodecException>(name) { RescueCodec.encode(obj(c["input"])) }
            assertEquals(c["error"], e.code, "$name: code")
            assertEquals(c["field"], e.field, "$name: field")
        }
    }

    @Test
    fun invalidDecodeCases() {
        for (c in cases("invalid_decode")) {
            val name = c["name"] as String
            val e = assertFailsWith<CodecException>(name) { RescueCodec.decode(hex(c["hex"] as String)) }
            assertEquals(c["error"], e.code, "$name: code")
            assertEquals(c["field"], e.field, "$name: field")
        }
    }

    @Test
    fun v1Cases() {
        for (c in cases("v1")) {
            assertSame(c["decoded"], RescueCodec.decodeAny(hex(c["hex"] as String)), c["name"] as String)
        }
    }

    @Test
    fun packedTextCases() {
        val list = cases("packed_text")
        assertTrue(list.size >= 5)
        for (c in list) {
            val name = c["name"] as String
            val dict = (c["dict"] as Number).toInt()
            assertEquals(c["text"], TextPack.unpack(hex(c["deflate"] as String), dict), "$name: unpack reference bytes")
            assertSame(c["decoded"], RescueCodec.decode(hex(c["hex"] as String)), "$name: decode")
            val again = RescueCodec.encode(obj(c["decoded"]))
            assertSame(c["decoded"], RescueCodec.decode(again), "$name: round trip")
            assertTrue(hex(c["deflate"] as String).size < (c["text_size"] as Number).toInt(), "$name: smaller")
        }
    }

    @Test
    fun textDictionaryHashes() {
        @Suppress("UNCHECKED_CAST")
        val dicts = vectors["text_dicts"] as Map<String, Map<String, Any?>>
        for ((number, entry) in dicts) {
            val file = File(System.getProperty("rescuecodec.spec"), entry["file"] as String).readBytes()
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(file).joinToString("") { "%02x".format(it) }
            assertEquals(entry["sha256"], sha, "dictionary $number file")
            assertEquals(TextPack.DICTS[number.toInt()]?.second, sha, "dictionary $number in TextPack")
            assertTrue(TextPack.dictionary(number.toInt()).contentEquals(file), "dictionary $number bundled")
        }
    }

    @Test
    fun compactTextCases() {
        val list = cases("compact_text")
        assertTrue(list.size >= 10)
        for (c in list) {
            val name = c["name"] as String
            assertEquals(c["hex"], RescueCodec.encode(obj(c["input"])).hex(), "$name: encode")
            assertSame(c["decoded"], RescueCodec.decode(hex(c["hex"] as String)), "$name: decode")
            assertEquals(c["words"], TextWords.encode(c["text"] as String, 1).hex(), "$name: encode words")
            assertEquals(c["text"], TextWords.decode(hex(c["words"] as String), 1), "$name: decode words")
            val render = obj(c["render"])
            for (lang in listOf("en", "de")) {
                assertEquals(render[lang], RescueCodec.reconstruct(obj(c["decoded"]), lang), "$name: render [$lang]")
            }
        }
    }

    @Test
    fun wordStreamCases() {
        val list = cases("word_streams")
        assertTrue(list.size >= 5)
        for (c in list) {
            val name = c["name"] as String
            assertEquals(c["words"], TextWords.encode(c["text"] as String, 1).hex(), "$name: encode")
            assertEquals(c["text"], TextWords.decode(hex(c["words"] as String), 1), "$name: decode")
        }
        val invalid = cases("word_streams_invalid")
        assertTrue(invalid.size >= 5)
        for (c in invalid) {
            val name = c["name"] as String
            val e = assertFailsWith<CodecException>(name) { TextWords.decode(hex(c["words"] as String), 1) }
            assertEquals(c["error"], e.code, "$name: code")
            assertEquals("text.words", e.field, "$name: field")
        }
    }

    @Test
    fun wordDictionaryHashes() {
        @Suppress("UNCHECKED_CAST")
        val dicts = vectors["word_dicts"] as Map<String, Map<String, Any?>>
        assertTrue(dicts.isNotEmpty())
        for ((number, entry) in dicts) {
            val file = File(System.getProperty("rescuecodec.spec"), entry["file"] as String).readBytes()
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(file).joinToString("") { "%02x".format(it) }
            assertEquals(entry["sha256"], sha, "word dictionary $number file")
            assertEquals(TextWords.DICTS[number.toInt()]?.second, sha, "word dictionary $number in TextWords")
            assertTrue(TextWords.available(number.toInt()), "word dictionary $number bundled")
            assertEquals((entry["entries"] as Number).toInt(), TextWords.dictionary(number.toInt()).entries.size, "entries")
            // The bundled copy is the spec file byte for byte.
            val bundled = TextWords::class.java.getResourceAsStream("/rescuecodec/vocab/words-dict-v$number.txt")!!.use { it.readBytes() }
            assertTrue(bundled.contentEquals(file), "word dictionary $number bundled byte for byte")
        }
    }

    @Test
    fun packedTextIsSmallerAndRoundTrips() {
        val words = "We are at the hut, two people with us, waiting for the vehicle on the forest road"
        val packed = TextPack.pack(words)
        assertTrue(packed.size < 45, "packed ${packed.size} B")
        assertEquals(words, TextPack.unpack(packed, 1))
        val bytes = RescueCodec.encode(mapOf("id" to 3, "text" to mapOf("text" to words, "dict" to 1)))
        assertSame(mapOf("id" to 3, "text" to mapOf("text" to words, "dict" to 1)), RescueCodec.decode(bytes), "decode")
    }

    @Test
    fun readableTextLevel2() {
        var checked = 0
        for (c in cases("valid") + cases("decode_only")) {
            val text = c["text"] as? Map<*, *> ?: continue
            val decoded = obj(c["decoded"])
            for ((lang, expected) in text) {
                assertEquals(expected, RescueCodec.reconstruct(decoded, lang as String), "${c["name"]} [$lang]")
                checked++
            }
        }
        assertTrue(checked >= 80, "only $checked text checks")
    }

    @Test
    fun jsonRoundTripAndLocales() {
        val bytes = RescueCodec.encodeJson("""{"id":7,"pos":{"lat":50.69212,"lon":7.21345},"found":{"age":"child","needs":["stretcher"]}}""")
        assertEquals("08071a0908b8e6ea0410828758320408401801", bytes.hex())
        val de = RescueCodec.reconstruct(RescueCodec.decode(bytes), java.util.Locale.GERMANY)
        assertTrue(de.startsWith("Person gefunden: Kind"), de)
        assertTrue(RescueCodec.reconstruct(RescueCodec.decode(bytes), "xx").startsWith("Person found"))
    }

    @Test
    fun resolveTimeWrapsToYesterday() {
        val now = Instant.parse("2026-11-26T00:05:00Z")
        assertEquals(Instant.parse("2026-11-25T23:58:00Z"), RescueCodec.resolveTime("23:58", now))
        assertEquals(Instant.parse("2026-11-26T00:10:00Z"), RescueCodec.resolveTime("00:10", now))
    }

    @Test
    fun neverCrashesOnJunk() {
        for (a in 0..255) {
            for (b in 0..255) {
                try { RescueCodec.decodeAny(byteArrayOf(a.toByte(), b.toByte())) } catch (_: CodecException) {}
            }
            try { RescueCodec.decodeAny(byteArrayOf(a.toByte())) } catch (_: CodecException) {}
        }
    }
}

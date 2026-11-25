// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.codec

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.rescuecodec.v2.proto.Envelope
import org.rescuecodec.v2.proto.Text

/** Compact texts: 2-byte word tokens (spec section 3.6). Same checks as tests/test_v2_textwords.py. */
class TextWordsTest {
    private val d = TextWords.dictionary(1)
    private val pos = mapOf("lat" to 50.69212, "lon" to 7.21345)

    private fun unit(code: Int) = byteArrayOf((code shr 8).toByte(), code.toByte())
    private fun tok(entry: String) = unit(d.index.getValue(entry))
    private fun lit(text: String): ByteArray {
        val data = text.toByteArray(Charsets.UTF_8)
        return unit(TextWords.LITERAL + data.size) + data
    }
    private fun ctl(code: Int) = unit(code)
    private fun enc(text: String) = TextWords.encode(text, 1)
    private fun dec(data: ByteArray) = TextWords.decode(data, 1)

    // ------------------------------------------------------------ dictionary

    @Test
    fun frozenFile() {
        val data = File(System.getProperty("rescuecodec.spec"), "vocab/words-dict-v1.txt").readBytes()
        assertEquals(data.last(), '\n'.code.toByte())
        assertFalse(data.contains('\r'.code.toByte()))
        assertEquals(data.count { it == '\n'.code.toByte() }, d.entries.size)
        assertTrue(d.entries.size < TextWords.FIRST_CONTROL)
    }

    @Test
    fun noPhoneNumbersOrAddresses() {
        val bad = Regex("\\d{5}|@|http|www")
        for (entry in d.entries) assertFalse(bad.containsMatchIn(entry), entry)
    }

    @Test
    fun damagedCopyIsNotUsed() {
        assertTrue(TextWords.available(1))
        assertTrue(TextWords.available(2))
        assertFalse(TextWords.available(9))
        assertFailsWith<IllegalArgumentException> { TextWords.dictionary(9) }
        val data = File(System.getProperty("rescuecodec.spec"), "vocab/words-dict-v1.txt").readBytes()
        val sha = TextWords.DICTS.getValue(1).second
        assertNotNull(TextWords.checked(data, sha))
        val damaged = data.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertNull(TextWords.checked(damaged, sha), "a copy with other bytes is never used")
        assertNull(TextWords.checked(String(data, Charsets.UTF_8).replace("\n", "\r\n").toByteArray(), sha), "CRLF copy")
        assertNull(TextWords.checked(null, sha))
    }

    // ------------------------------------------------------------ boundaries

    @Test
    fun literalOwnsItsLeadingSpace() {
        val data = enc("We found Aylin Demir")
        assertContentEquals(tok("We") + tok("found") + lit(" Aylin Demir"), data) // 2 + 2 + 2 + 12 bytes
        assertEquals("We found Aylin Demir", dec(data))
    }

    @Test
    fun tokenAfterALiteralGetsNoImpliedSpace() {
        val data = enc("Aylin Demir is safe")
        assertContentEquals(lit("Aylin Demir ") + tok("is") + tok("safe"), data)
        assertEquals("Aylin Demir is safe", dec(data))
        // the same tokens without the space in the literal: the words touch, nothing is invented
        assertEquals("Aylin Demiris safe", dec(lit("Aylin Demir") + tok("is") + tok("safe")))
    }

    @Test
    fun spaceOnlyBetweenAdjacentTokens() {
        assertEquals("is safe", dec(tok("is") + tok("safe")))
        assertEquals("issafe", dec(tok("is") + ctl(TextWords.GLUE) + tok("safe")))
        assertEquals("safe.", dec(tok("safe") + tok(".")))
        assertEquals("(safe)", dec(tok("(") + tok("safe") + tok(")")))
    }

    @Test
    fun caseControls() {
        assertEquals("Safe", dec(ctl(TextWords.CAP) + tok("safe")))
        assertEquals("SAFE", dec(ctl(TextWords.UPPER) + tok("safe")))
        assertContentEquals(ctl(TextWords.UPPER) + tok("safe"), enc("SAFE"))
    }

    @Test
    fun coordinatesTypedAsProseKeepEveryCharacter() {
        val text = "Meet at 50.6900, -1.2500"
        val data = enc(text)
        assertEquals(text, dec(data))
        assertContentEquals(ctl(TextWords.CAP) + tok("meet") + tok("at") + tok("50") + lit(".6900, -1.2500"), data)
    }

    @Test
    fun noNearMatches() {
        for (text in listOf("recieve the hepl", "Teh flod", "Aylin", "Müller", "DL1ABC", "Apt 4B")) assertEquals(text, dec(enc(text)))
        assertFalse("help" in dec(enc("hepl")))
    }

    @Test
    fun utf16IsCountedAsUtf8() {
        // Kotlin strings are UTF-16; literal lengths and costs are UTF-8 bytes.
        assertContentEquals(lit("🙂"), enc("🙂"))
        assertContentEquals(lit("Ä"), enc("Ä"))
    }

    // ------------------------------------------------------------ round trip

    private val cases = listOf(
        "Hi, are you still at the station? I will be there in ten minutes.",
        "Team 3 reached Löwenburg hut. Missing hiker found at 50.6921, 7.2134, conscious, needs a stretcher.",
        "The river runs\n  under the bridge,\n\nand we wait   for morning.\n",
        "Zyxomorph flibberjab quantimeter",
        "Wir sind am Parkplatz, the road is blocked, bitte langsam fahren.",
        "Wait... what?! (Really) [yes] {ok}; done: \"fine\" - right?",
        "All safe 🙂👍 see you soon ❤️",
        "NEED WATER AND FOOD NOW",
        "line one\r\nline two\tend",
        "  hello there  ",
        " ", "\n", ".", "…", "a", "I", "x".repeat(300),
    )

    @Test
    fun roundTripCases() {
        for (text in cases) assertEquals(text, dec(enc(text)), text)
    }

    @Test
    fun roundTripRandomUtf8() {
        val rnd = Random(20261125)
        val ranges = listOf(0x20 to 0x7E, 0x09 to 0x0D, 0xA0 to 0x7FF, 0x800 to 0xD7FF, 0xE000 to 0xFFFD, 0x10000 to 0x10FFFF)
        repeat(3000) {
            val sb = StringBuilder()
            repeat(rnd.nextInt(1, 61)) {
                val (lo, hi) = ranges[rnd.nextInt(ranges.size)]
                sb.appendCodePoint(rnd.nextInt(lo, hi + 1))
            }
            val text = sb.toString()
            assertEquals(text, dec(enc(text)))
        }
    }

    @Test
    fun roundTripDictionaryMix() {
        val rnd = Random(7)
        val parts = d.entries.take(400) + listOf(" ", "  ", "\n", "", ",", ".", "Ä", "🙂", "X", "42")
        val seps = listOf("", " ", " ", " ")
        repeat(3000) {
            var text = (1..rnd.nextInt(1, 21)).joinToString("") { parts[rnd.nextInt(parts.size)] + seps[rnd.nextInt(seps.size)] }
            if (rnd.nextDouble() < 0.3) {
                text = if (rnd.nextDouble() < 0.5) text.uppercase() else text.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
            }
            if (text.isNotEmpty()) assertEquals(text, dec(enc(text)))
        }
    }

    @Test
    fun shortestStream() {
        // "x and y" in one literal (2+7 bytes) beats literal "x " + [and] + literal " y" (2+2+2+2+2 = 10 bytes).
        assertContentEquals(lit("x and y"), enc("x and y"))
    }

    // ------------------------------------------------------------ errors

    private fun check(data: ByteArray, code: String = "malformed") {
        val e = assertFailsWith<CodecException> { dec(data) }
        assertEquals(code, e.code)
        assertEquals("text.words", e.field)
    }

    @Test
    fun malformedStreams() {
        check(ByteArray(0)) // empty
        check(tok("safe").copyOf(1)) // half a token
        check(lit("Aylin").let { it.copyOf(it.size - 1) }) // literal cut off
        check(ctl(TextWords.LITERAL)) // literal of 0 bytes
        check(ctl(0xF004) + tok("safe")) // undefined control
        check(ctl(0xF000))
        check(unit(d.entries.size)) // token past the end of the dictionary
        check(tok("safe") + ctl(TextWords.CAP)) // control at the end
        check(ctl(TextWords.CAP) + lit("x")) // control before a literal
        check(ctl(TextWords.CAP) + ctl(TextWords.UPPER) + tok("safe")) // two case controls
        check(ctl(TextWords.GLUE) + ctl(TextWords.GLUE) + tok("safe"))
        check(ctl(TextWords.LITERAL + 2) + byteArrayOf(0xC3.toByte(), 0x28)) // not UTF-8
    }

    @Test
    fun tooLarge() {
        check(lit("x".repeat(1000)) + lit("x".repeat(25)), "too_large")
        assertEquals("too_large", assertFailsWith<CodecException> { enc("x".repeat(1025)) }.code)
    }

    // ------------------------------------------------------------ codec

    @Test
    fun encodeDecodeAndRender() {
        val msg = mapOf("id" to 3, "text" to mapOf("text" to "We found Aylin Demir", "words" to 1))
        val data = RescueCodec.encode(msg)
        assertEquals(mapOf<String, Any?>("id" to 3, "text" to mapOf("text" to "We found Aylin Demir", "words" to 1)), RescueCodec.decode(data))
        assertEquals("Message: We found Aylin Demir.", RescueCodec.reconstruct(RescueCodec.decode(data), "en"))
    }

    @Test
    fun gpsStaysInThePositionField() {
        val msg = linkedMapOf("id" to 9, "priority" to "urgent", "pos" to pos, "text" to mapOf("text" to "Found him, conscious, needs a stretcher", "words" to 1))
        val env = Envelope.parseFrom(RescueCodec.encode(msg))
        assertEquals(5069212 to 721345, env.pos.lat to env.pos.lon)
        val decoded = RescueCodec.decode(RescueCodec.encode(msg))
        assertFalse("50.69" in ((decoded["text"] as Map<*, *>)["text"] as String))
        assertEquals(pos, decoded["pos"])
    }

    @Test
    fun codecRules() {
        val cases = listOf(
            mapOf("text" to "", "words" to 1) to ("missing_field" to "text.text"),
            mapOf("text" to "x", "words" to 1, "dict" to 1) to ("invalid_value" to "text.words"),
            mapOf("text" to "x", "words" to 9) to ("invalid_value" to "text.words"),
            mapOf("text" to "x".repeat(1025), "words" to 1) to ("invalid_value" to "text.text"),
        )
        for ((body, expected) in cases) {
            val e = assertFailsWith<CodecException>(body.toString()) { RescueCodec.encode(mapOf("text" to body)) }
            assertEquals(expected, e.code to e.field, body.toString())
        }
    }

    private fun wire(build: Text.Builder.() -> Unit): ByteArray =
        Envelope.newBuilder().setId(7).setText(Text.newBuilder().apply(build)).build().toByteArray()

    private fun bytes(b: ByteArray) = com.google.protobuf.ByteString.copyFrom(b)

    @Test
    fun wireRules() {
        val words = enc("is safe")
        val cases = listOf(
            wire { setWords(bytes(words)) } to ("missing_field" to "text.words_dict"),
            wire { setWordsDict(1); setText("x") } to ("invalid_value" to "text.words_dict"),
            wire { setWords(bytes(words)); setWordsDict(1); setText("x") } to ("invalid_value" to "text.words"),
            wire { setWords(bytes(words)); setWordsDict(1); setDict(1) } to ("invalid_value" to "text.dict"),
            wire { setWords(bytes(words.copyOf(words.size - 1))); setWordsDict(1) } to ("malformed" to "text.words"),
        )
        for ((packet, expected) in cases) {
            val e = assertFailsWith<CodecException> { RescueCodec.decode(packet) }
            assertEquals(expected, e.code to e.field)
        }
    }

    @Test
    fun unknownDictionaryIsVisibleNotGuessed() {
        val msg = RescueCodec.decode(wire { setWords(bytes(enc("is safe"))); setWordsDict(9) })
        assertEquals(mapOf<String, Any?>("id" to 7, "unknown" to mapOf("words" to 9)), msg)
        val en = RescueCodec.reconstruct(msg, "en")
        assertTrue("dictionary this app does not have" in en, en)
        assertTrue("Wörterbuch" in RescueCodec.reconstruct(msg, "de"))
        assertFalse("safe" in en)
    }

    @Test
    fun oldReaderRejectsAndNeverShowsWrongText() {
        // A 2.0 reader does not know fields 4 and 5: it sees a Text with an empty `text` and rejects the packet.
        val env = Envelope.parseFrom(RescueCodec.encode(mapOf("id" to 3, "text" to mapOf("text" to "We found Aylin Demir", "words" to 1))))
        val old = env.toBuilder().setText(env.text.toBuilder().clearWords().clearWordsDict()).build().toByteArray()
        val e = assertFailsWith<CodecException> { RescueCodec.decode(old) }
        assertEquals("missing_field" to "text.text", e.code to e.field)
    }

    @Test
    fun existingPacketsStillDecode() {
        val hut = "We are at the hut, two people with us, waiting for the vehicle on the forest road"
        assertEquals(mapOf("text" to hut, "dict" to 1), RescueCodec.decode(RescueCodec.encode(mapOf("id" to 3, "text" to mapOf("text" to hut, "dict" to 1))))["text"])
        assertEquals(mapOf("text" to "Ok"), RescueCodec.decode(RescueCodec.encode(mapOf("id" to 3, "text" to mapOf("text" to "Ok"))))["text"])
        assertTrue("found" in RescueCodec.decode(RescueCodec.encode(mapOf("id" to 7, "pos" to pos, "found" to mapOf("age" to "child")))))
    }
}

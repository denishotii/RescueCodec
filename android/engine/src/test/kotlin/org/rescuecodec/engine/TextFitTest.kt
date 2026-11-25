// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.engine

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.rescuecodec.codec.CodecException
import org.rescuecodec.codec.Codes
import org.rescuecodec.codec.RescueCodec

/** A long free-text sentence plus the phone's position must still go out as one packet. */
class TextFitTest {
    // A position the app adds when the fix is not fresh. With a 180-byte text the message is 203 bytes.
    private val pos = mapOf("lat" to 52.52001, "lon" to 13.40495, "acc" to "estimated")

    /** The message as the app sends it: id (2 bytes, the largest), position, text. */
    private fun message(text: String): Map<String, Any?> = linkedMapOf("id" to 300, "pos" to pos, "text" to mapOf("text" to text))

    private fun textOf(m: Map<String, Any?>) = (m["text"] as Map<*, *>)["text"] as String

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)

    /** Exactly [n] bytes of UTF-8 from [words] repeated, padded with ASCII. */
    private fun bytesOf(words: String, n: Int): String {
        var s = words.repeat(n / words.length + 2)
        while (utf8(s).size > n) s = s.dropLast(1)
        while (utf8(s).size < n) s += "x"
        return s
    }

    private fun strictUtf8(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()

    @Test
    fun aGermanTextOf180BytesWithAPositionIsCutTo200Bytes() {
        val text = bytesOf("Verletzte Person an der Brücke, Fußweg über den Bach zerstört, nördlich gehts nicht weiter. ", Codes.MAX_TEXT_BYTES)
        assertEquals(180, utf8(text).size)
        val m = message(text)
        assertEquals("too_large", assertFailsWith<CodecException> { RescueCodec.encode(m) }.code, "the bug: it did not fit")

        val fitted = TextFit.fitText(m)
        val bytes = RescueCodec.encode(fitted)
        assertTrue(bytes.size <= Codes.MAX_ENCODE_BYTES, "${bytes.size} bytes")
        val cut = textOf(fitted)
        assertTrue(cut.endsWith("…"), cut)
        assertEquals(cut, strictUtf8(utf8(cut)), "valid UTF-8")
        assertFalse(cut.contains('�'))
        assertTrue(text.startsWith(cut.removeSuffix("…")), "a prefix of the text")
        assertFalse(cut.removeSuffix("…").last().isWhitespace(), "no space before the …")
        // The position and the id stay, and the decoded packet has the same text.
        assertEquals(m["pos"], fitted["pos"])
        assertEquals(300, fitted["id"])
        assertEquals(cut, (RescueCodec.decode(bytes)["text"] as Map<*, *>)["text"])
    }

    @Test
    fun theCutIsTheLongestThatFits() {
        // No spaces, so one character more is the next longer candidate.
        val text = bytesOf("Hilfe,Brücke,Fußweg,", Codes.MAX_TEXT_BYTES)
        val cut = textOf(TextFit.fitText(message(text))).removeSuffix("…")
        val longer = text.substring(0, cut.length + 1) + "…"
        assertFailsWith<CodecException> { RescueCodec.encode(message(longer)) }
    }

    @Test
    fun aCutNeverSplitsAMultiByteCharacter() {
        for (ch in listOf("ü", "€", "🚑")) { // 2, 3 and 4 bytes
            val text = ch.repeat(Codes.MAX_TEXT_BYTES / utf8(ch).size)
            val fitted = TextFit.fitText(message(text))
            val cut = textOf(fitted)
            assertTrue(RescueCodec.encode(fitted).size <= Codes.MAX_ENCODE_BYTES)
            assertTrue(Regex("^(${Regex.escape(ch)})+…$").matches(cut), "whole characters only: $cut")
        }
    }

    @Test
    fun aShortTextIsUnchanged() {
        val m = message("Hilfe am Waldweg, Bein gebrochen")
        assertSame(m, TextFit.fitText(m))
        val exact = message(bytesOf("Weg frei. ", 150))
        assertSame(exact, TextFit.fitText(exact))
    }

    @Test
    fun otherMessagesAreUnchanged() {
        val found = mapOf("id" to 7, "pos" to pos, "found" to mapOf("age" to "child"))
        assertSame(found, TextFit.fitText(found))
        // A text that is broken for another reason keeps its own error.
        val tooLong = message("x".repeat(Codes.MAX_TEXT_BYTES + 1))
        assertSame(tooLong, TextFit.fitText(tooLong))
        val noBody = mapOf("id" to 7)
        assertSame(noBody, TextFit.fitText(noBody))
    }

    @Test
    fun plainTextIsCutTo200BytesOnACharacterBoundary() {
        val short = "Treffpunkt am Gasthaus"
        assertSame(short, TextFit.fitPlain(short))
        val exact = "a".repeat(200)
        assertSame(exact, TextFit.fitPlain(exact))
        for (text in listOf("ü".repeat(150), "a" + "ü".repeat(150), "€".repeat(100), "Weg ".repeat(80), "\uD83D\uDE91".repeat(70))) {
            val cut = TextFit.fitPlain(text)
            val size = utf8(cut).size
            assertTrue(size in 195..200, "$size bytes")
            assertTrue(cut.endsWith(TextFit.ELLIPSIS))
            assertEquals(cut, strictUtf8(utf8(cut)), "no broken character")
            assertTrue(text.startsWith(cut.dropLast(1).trimEnd()))
        }
        assertEquals("a".repeat(196) + "…", TextFit.fitPlain("a".repeat(196) + " " + "b".repeat(50)), "a trailing space goes")
    }
}

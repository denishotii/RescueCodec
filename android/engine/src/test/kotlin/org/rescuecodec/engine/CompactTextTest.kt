// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.rescuecodec.codec.CodecException
import org.rescuecodec.codec.Codes
import org.rescuecodec.codec.RescueCodec

/** The opt-in compact text send rule (spec section 2.3, rules 5 and 6). Same checks as tests/test_v2_textwords.py. */
class CompactTextTest {
    private val pos = mapOf("lat" to 50.69212, "lon" to 7.21345)
    private val hut = "We are at the hut, two people with us, waiting for the vehicle on the forest road"

    private val texts = listOf(
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
        "Ok", "Hilfe", "We are at the hut, two people with us", "Bin unterwegs".repeat(10),
    )

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)

    /** The send rule before compact texts (2.0), for the "setting off changes nothing" check. */
    private fun oldChoose(message: Map<String, Any?>, wantPacked: Boolean): ByteArray {
        val words = (message["text"] as Map<*, *>)["text"] as String
        val plain = utf8(TextFit.fitPlain(words))
        val packed = try {
            RescueCodec.encode(message.filterKeys { it != "text" } + ("text" to mapOf("text" to words, "dict" to 1)))
        } catch (e: CodecException) {
            null
        }
        if (wantPacked && packed != null && packed.size <= plain.size - TextMode.MIN_SAVING_BYTES) return packed
        return plain
    }

    @Test
    fun settingOffIsTheOldRule() {
        for (text in texts) {
            for (msg in listOf(linkedMapOf("id" to 3, "text" to mapOf("text" to text)), linkedMapOf("id" to 300, "pos" to pos, "text" to mapOf("text" to text)))) {
                for (want in listOf(true, false)) {
                    if (utf8(text).size > Codes.MAX_TEXT_BYTES) continue
                    val form = TextMode.chooseForm(msg, want, "x")
                    assertContentEquals(oldChoose(msg, want), form.payload, "$text / $want")
                    assertNull(form.wordsSize)
                    assertTrue(form.form != "words")
                }
            }
        }
    }

    @Test
    fun compactWinsOnlyWhenSmaller() {
        var words = 0
        for (text in texts) {
            val msg = mapOf("id" to 3, "text" to mapOf("text" to text))
            val off = TextMode.chooseForm(msg, true, "rescuecodec_only")
            val on = TextMode.chooseForm(msg, true, "rescuecodec_only", compact = true)
            assertTrue(on.payload.size <= off.payload.size, text)
            if (on.form == "words") {
                words++
                assertTrue(on.wordsSize!! < off.payload.size, text)
                assertTrue(on.packed)
                assertEquals(mapOf("text" to text, "words" to 1), RescueCodec.decode(on.payload)["text"])
            } else {
                assertContentEquals(off.payload, on.payload, text) // ties and losses: the existing path
            }
        }
        assertTrue(words >= 3, "only $words texts went compact")
    }

    @Test
    fun neverCompactWhereTheChatDoesNotPack() {
        val form = TextMode.chooseForm(mapOf("id" to 3, "text" to mapOf("text" to texts[0])), false, "public_channel", compact = true)
        assertEquals(Triple("plain", false, null as Int?), Triple(form.form, form.packed, form.wordsSize))
    }

    @Test
    fun compactExample() {
        val form = TextMode.chooseForm(mapOf("id" to 3, "text" to mapOf("text" to hut)), true, "rescuecodec_only", compact = true)
        assertEquals("words", form.form)
        assertTrue(form.wordsSize!! < form.packedSize!!)
        assertTrue(form.wordsSize!! < form.plainSize)
        assertEquals(hut, form.text)
        assertEquals(mapOf("text" to hut, "words" to 1), form.message["text"])
        assertEquals(3, form.message["id"])
    }

    @Test
    fun gpsStaysInThePositionField() {
        val msg = linkedMapOf("id" to 9, "priority" to "urgent", "pos" to pos, "text" to mapOf("text" to hut))
        val form = TextMode.chooseForm(msg, true, "x", compact = true)
        assertEquals("words", form.form)
        val decoded = RescueCodec.decode(form.payload)
        assertEquals(pos, decoded["pos"])
        assertEquals(hut, (decoded["text"] as Map<*, *>)["text"])
    }

    @Test
    fun longTextOnAPackingChat() {
        val text = ("Sector 4 fully searched, nothing found. Moving to sector 5 along the river, two people, " +
            "radio check every 30 minutes. ").repeat(3) // about 330 bytes
        assertTrue(utf8(text).size > Codes.MAX_TEXT_BYTES)
        val form = TextMode.chooseForm(mapOf("id" to 3, "text" to mapOf("text" to text)), true, "x", compact = true)
        assertTrue(form.packed)
        assertFalse(form.cut)
        assertEquals(text, (RescueCodec.decode(form.payload)["text"] as Map<*, *>)["text"])
    }

    @Test
    fun tooLongIsCutToFitOnePacket() {
        val rnd = Random(3)
        val text = (1..380).map { "qwrtzpsdfghjklxcvbnm "[rnd.nextInt(21)] }.joinToString("") // does not compress
        val form = TextMode.chooseForm(mapOf("id" to 3, "text" to mapOf("text" to text)), true, "x", compact = true)
        assertTrue(form.cut)
        assertTrue(form.text.endsWith("…"))
        assertTrue(text.startsWith(form.text.dropLast(1)))
        assertTrue(form.payload.size <= Codes.MAX_ENCODE_BYTES)
        val over = "Sector 4 searched. ".repeat(30) // 570 bytes, over 400
        val cut = TextFit.fitPacked(over) { true }
        assertTrue(utf8(cut).size <= Codes.MAX_PACKED_TEXT_BYTES)
        assertTrue(cut.endsWith("…"))
    }

    @Test
    fun fitPackedBoundaries() {
        val exact = "a".repeat(Codes.MAX_PACKED_TEXT_BYTES)
        assertTrue(TextFit.fitPacked(exact) { true } === exact, "400 bytes that fit come back unchanged")
        assertEquals("a".repeat(397) + "…", TextFit.fitPacked(exact + "b") { true })
        // never inside a character: "ä" is 2 bytes
        val umlauts = "ä".repeat(250) // 500 bytes
        val cut = TextFit.fitPacked(umlauts) { true }
        assertEquals("ä".repeat(198) + "…", cut)
        // trailing whitespace goes before the ellipsis
        assertEquals("a b…", TextFit.fitPacked("a b    c".repeat(1)) { it.length <= 4 })
        assertEquals("…", TextFit.fitPacked("abc") { it == "…" })
    }
}

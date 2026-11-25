// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.rescuecodec.codec.RescueCodec

/** The send rule for chat texts. The numbers are the same as in tests for rescuecodec/engine/textmode.py. */
class TextModeTest {
    private val hut = "We are at the hut, two people with us, waiting for the vehicle on the forest road"
    private val pos = mapOf("lat" to 50.69212, "lon" to 7.21345)

    @Test
    fun publicAndNormalAlwaysGoPlain() {
        assertEquals(TextMode.Wish(false, "public_channel"), TextMode.wantsPacked(TextMode.COMPRESSED, public = true, direct = false))
        assertEquals(TextMode.Wish(false, "mode_normal"), TextMode.wantsPacked(TextMode.NORMAL, public = false, direct = true, peerRescueCodec = true))
        assertEquals(TextMode.Wish(true, "mode_compressed"), TextMode.wantsPacked(TextMode.COMPRESSED, public = false, direct = false, meshtasticUsers = 3))
    }

    @Test
    fun autoFollowsWhoIsListening() {
        assertEquals(TextMode.Wish(true, "peer_rescuecodec"), TextMode.wantsPacked(TextMode.AUTO, public = false, direct = true, peerRescueCodec = true))
        assertEquals(TextMode.Wish(false, "peer_unknown"), TextMode.wantsPacked(TextMode.AUTO, public = false, direct = true))
        assertEquals(TextMode.Wish(false, "meshtastic_users"), TextMode.wantsPacked(TextMode.AUTO, public = false, direct = false, meshtasticUsers = 1))
        assertEquals(TextMode.Wish(true, "rescuecodec_only"), TextMode.wantsPacked(TextMode.AUTO, public = false, direct = false))
        assertEquals(TextMode.Wish(true, "rescuecodec_only"), TextMode.wantsPacked("something new", public = false, direct = false))
    }

    @Test
    fun longTextGoesPacked() {
        val form = TextMode.chooseForm(mapOf("id" to 3, "text" to mapOf("text" to hut)), true, "x")
        assertTrue(form.packed)
        assertEquals(81, form.plainSize)
        assertEquals(42, form.packedSize)
        assertEquals("x", form.reason)
        assertEquals(form.payload.size, form.packedSize)
        assertEquals(mapOf("text" to hut, "dict" to 1), RescueCodec.decode(form.payload)["text"])
    }

    @Test
    fun packedKeepsPositionAndId() {
        val words = "Bin unterwegs, Ankunft in ca. 10 Minuten"
        val form = TextMode.chooseForm(linkedMapOf("id" to 3, "pos" to pos, "text" to mapOf("text" to words)), true)
        assertTrue(form.packed)
        assertEquals(40, form.plainSize)
        assertEquals(34, form.packedSize)
        assertEquals(3, form.message["id"])
        assertEquals(pos, form.message["pos"])
    }

    @Test
    fun shortTextStaysPlain() {
        val form = TextMode.chooseForm(mapOf("id" to 3, "text" to mapOf("text" to "Ok")), true)
        assertFalse(form.packed)
        assertEquals("no_saving", form.reason)
        assertEquals(2, form.plainSize)
        assertEquals(12, form.packedSize)
        assertEquals("Ok", String(form.payload, Charsets.UTF_8))
        assertEquals(mapOf("text" to mapOf("text" to "Ok")), form.message)
    }

    @Test
    fun notWantedStaysPlainWithItsReason() {
        val form = TextMode.chooseForm(mapOf("id" to 3, "priority" to "urgent", "text" to mapOf("text" to hut)), false, "peer_unknown")
        assertFalse(form.packed)
        assertEquals("peer_unknown", form.reason)
        assertEquals(hut, String(form.payload, Charsets.UTF_8))
        assertEquals("urgent", form.message["priority"])
        assertTrue(form.packedSize!! < form.plainSize)
    }

    @Test
    fun longTextThatDoesNotPackFitsAsPlain() {
        // 1100 bytes of text cannot be packed (over 1024), so it goes plain and is cut to 200 bytes.
        val form = TextMode.chooseForm(mapOf("id" to 3, "text" to mapOf("text" to "A".repeat(1100))), true)
        assertFalse(form.packed)
        assertEquals("too_large", form.reason)
        assertNull(form.packedSize)
        assertEquals(200, form.plainSize)
    }

    @Test
    fun longTextFitsPackedWithoutCutting() {
        val words = ("Sector 4 fully searched, nothing found. ".repeat(8)).take(300)
        val form = TextMode.chooseForm(mapOf("id" to 8, "text" to mapOf("text" to words)), true)
        assertTrue(form.packed)
        assertEquals(200, form.plainSize)
        assertEquals(words, (form.message["text"] as Map<*, *>)["text"])
    }
}

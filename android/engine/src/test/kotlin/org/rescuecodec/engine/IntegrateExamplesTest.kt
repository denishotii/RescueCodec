// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.engine

import org.rescuecodec.codec.CodecException
import org.rescuecodec.codec.RescueCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The Kotlin examples of docs/INTEGRATE.md, with the exact output printed there. */
class IntegrateExamplesTest {
    private val found = mapOf(
        "id" to 7,
        "pos" to mapOf("lat" to 50.69212, "lon" to 7.21345),
        "found" to mapOf(
            "age" to "child", "consciousness" to "conscious",
            "injuries" to listOf("hypothermia", "fracture"),
            "needs" to listOf("stretcher"), "responders" to 2,
        ),
    )

    @Test
    fun encodeDecodeRender() {
        val bytes = RescueCodec.encode(found)
        assertEquals(23, bytes.size)
        assertEquals(
            "08071a0908b8e6ea041082875832080841100518012802",
            bytes.joinToString("") { "%02x".format(it) },
        )
        val msg = RescueCodec.decodeAny(bytes)
        assertEquals(
            "Person found: child, conscious, hypothermia, fracture, needs stretcher, 2 more helpers needed. Position 50.69212, 7.21345.",
            RescueCodec.reconstruct(msg, "en"),
        )
        assertEquals(
            "Person gefunden: Kind, ansprechbar, Unterkühlung, Knochenbruch, braucht Trage, 2 weitere Helfer benötigt. Position 50.69212, 7.21345.",
            RescueCodec.reconstruct(msg, "de"),
        )
    }

    @Test
    fun badInputHasStableCode() {
        val e = assertFailsWith<CodecException> { RescueCodec.encode(mapOf("found" to mapOf("age" to "giant"))) }
        assertEquals("invalid_value", e.code)
        assertEquals("found.age", e.field)
    }

    @Test
    fun extractAndQueue() {
        val result = Extractor().extract("Kind gefunden, ansprechbar, stark unterkühlt. Wir brauchen eine Trage.")
        assertEquals(false, result.unsupported)
        val body = result.message["found"] as Map<*, *>
        assertEquals("child", body["age"])
        assertEquals("conscious", body["consciousness"])
        assertEquals(listOf("hypothermia"), body["injuries"])
        assertEquals(listOf("stretcher"), body["needs"])
        val queue = SendQueue()
        queue.enqueue(result.message + ("id" to 7))
        val next = queue.poll()
        assertEquals(true, next != null && next.bytes.isNotEmpty())
    }
}

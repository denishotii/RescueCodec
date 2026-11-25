// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.codec

import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneOffset

/**
 * Reads v1 packets (first byte 0x01) as canonical JSON with `"v": 1` (spec section 7).
 * v1 is only decoded, never produced. The result may hold values a v2 encoder refuses (for
 * example a 32-bit id), so do not re-encode it.
 */
internal object V1 {
    private const val CODEC_TEXT = 0
    private const val CODEC_SEMANTIC = 1
    private const val CODEC_CONFIRMATION = 7
    private const val SCHEMA_PERSON_FOUND = 6
    private const val MAX_PACKET = 200
    private const val HEADER = 3
    private const val BODY = 8

    private val EQUIPMENT = listOf("oxygen", "first_aid", "aed", "stretcher", "oxygen", "water", "radio", "other")
    private val ASSISTANCE = mapOf(2 to "medic", 3 to "other", 4 to "rope", 5 to "other", 6 to "other")
    private val PRIORITY = mapOf(1 to "routine", 2 to "urgent", 3 to "critical")
    private val ACCURACY = mapOf(3 to "gps", 2 to "estimated", 1 to "area", 0 to "estimated")

    private fun bad(message: String) = CodecException("malformed", "bad v1 packet: $message")

    fun decode(data: ByteArray): Map<String, Any?> {
        if (data.size < 3 || data[0].toInt() != 1) throw CodecException("malformed", "not a v1 packet")
        if (data.size > MAX_PACKET) throw bad("oversized")
        val buf = ByteBuffer.wrap(data) // big endian
        val codec = data[1].toInt() and 0xFF
        val schema = data[2].toInt() and 0xFF
        return when (codec) {
            CODEC_TEXT -> text(buf, schema)
            CODEC_CONFIRMATION -> confirmation(buf, schema)
            else -> report(buf, codec, schema)
        }
    }

    private fun text(buf: ByteBuffer, schema: Int): Map<String, Any?> {
        if (buf.limit() < HEADER + 5 || schema != 0) throw bad("invalid text packet")
        val id = buf.getInt(HEADER).toLong() and 0xFFFFFFFFL
        val bytes = ByteArray(buf.limit() - HEADER - 4)
        buf.position(HEADER + 4)
        buf.get(bytes)
        val text = try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw bad("invalid UTF-8 text")
        }
        val out = LinkedHashMap<String, Any?>()
        out["v"] = 1
        if (id != 0L) out["id"] = id
        out["text"] = linkedMapOf<String, Any?>("text" to text)
        return out
    }

    private fun confirmation(buf: ByteBuffer, schema: Int): Map<String, Any?> {
        if (buf.limit() != 8 || schema != 0) throw bad("invalid confirmation")
        val id = buf.getInt(HEADER).toLong() and 0xFFFFFFFFL
        val status = buf.get(HEADER + 4).toInt()
        if (status != 1 && status != 2) throw bad("invalid confirmation status")
        return linkedMapOf(
            "v" to 1,
            "ack" to linkedMapOf<String, Any?>("ref" to id, "status" to if (status == 1) "accepted" else "done"),
        )
    }

    private fun report(buf: ByteBuffer, codec: Int, schema: Int): Map<String, Any?> {
        if (buf.limit() < HEADER + BODY) throw bad("truncated packet")
        if (codec != CODEC_SEMANTIC || schema != SCHEMA_PERSON_FOUND) throw bad("unsupported codec/schema $codec/$schema")
        var o = HEADER
        val requestId = buf.getInt(o).toLong() and 0xFFFFFFFFL
        val states = buf.get(o + 4).toInt() and 0xFF
        val mask = buf.get(o + 5).toInt() and 0xFF
        val request = buf.get(o + 6).toInt() and 0xFF
        val flags = buf.get(o + 7).toInt() and 0xFF
        o += BODY
        if (flags and 0x0F.inv() != 0) throw bad("reserved flag bits must be zero")
        val expected = HEADER + BODY + (if (flags and 1 != 0) 8 else 0) + (if (flags and 2 != 0) 4 else 0) +
            (if (flags and 4 != 0) 1 else 0) + (if (flags and 8 != 0) 1 else 0)
        if (buf.limit() != expected) throw bad("payload length does not match option flags")
        val conscious = states shr 6
        val breathing = (states shr 4) and 3
        val urgency = (states shr 2) and 3
        val quality = states and 3
        if (request > 6) throw bad("invalid categorical value")
        var lat = 0.0
        var lon = 0.0
        if (flags and 1 != 0) {
            lat = buf.getInt(o) / 100_000.0
            lon = buf.getInt(o + 4) / 100_000.0
            o += 8
        }
        var timestamp: Long? = null
        if (flags and 2 != 0) {
            timestamp = buf.getInt(o).toLong() and 0xFFFFFFFFL
            o += 4
        }
        var count = 0
        if (flags and 4 != 0) {
            count = buf.get(o).toInt() and 0xFF
            if (count !in 1..15) throw bad("person count must be 1..15")
            o += 1
        }
        var responders = 0
        if (flags and 8 != 0) {
            responders = buf.get(o).toInt() and 0xFF
            if (responders !in 1..15) throw bad("minimum responders needed must be 1..15")
        }

        val out = LinkedHashMap<String, Any?>()
        out["v"] = 1
        out["id"] = requestId
        PRIORITY[urgency]?.let { out["priority"] = it }
        if (flags and 1 != 0) {
            val pos = LinkedHashMap<String, Any?>()
            pos["lat"] = lat
            pos["lon"] = lon
            val acc = ACCURACY.getValue(quality)
            if (acc != "gps") pos["acc"] = acc
            out["pos"] = pos
        }
        if (timestamp != null) {
            val t = Instant.ofEpochSecond(timestamp).atOffset(ZoneOffset.UTC)
            out["time"] = "%02d:%02d".format(t.hour, t.minute)
            out["v1_timestamp"] = timestamp
        }
        val found = LinkedHashMap<String, Any?>()
        if (count != 0) found["count"] = count
        when (conscious) {
            1 -> found["consciousness"] = "conscious"
            2 -> found["consciousness"] = "unconscious"
        }
        when (breathing) {
            1 -> found["breathing"] = "normal"
            2 -> found["breathing"] = "none"
        }
        val needs = HashSet<String>()
        for (bit in 0 until 8) if (mask and (1 shl bit) != 0) needs.add(EQUIPMENT[bit])
        ASSISTANCE[request]?.let { needs.add(it) }
        if (needs.isNotEmpty()) found["needs"] = needs.sortedBy { Codes.NEEDS.getValue(it) }
        if (responders != 0) found["responders"] = responders
        out["found"] = found
        return out
    }
}

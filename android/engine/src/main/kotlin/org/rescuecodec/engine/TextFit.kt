// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.engine

import org.rescuecodec.codec.CodecException
import org.rescuecodec.codec.Codes
import org.rescuecodec.codec.RescueCodec

/**
 * Makes a free-text message fit into one packet. The Python library has the same rule, keep them identical:
 *
 * - Only a text message. Every other message comes back unchanged.
 * - If the encoded message (with its position and id) is over [Codes.MAX_ENCODE_BYTES] (200) bytes, the text is
 *   cut to the longest prefix that ends on a UTF-8 character boundary, trailing whitespace is stripped and
 *   [ELLIPSIS] ("…", U+2026) is added, so that the whole encoded message is at most 200 bytes.
 * - The position and every other field stay. A cut never lands inside a multi-byte character.
 *
 * Example: the extractor's text fallback is up to 180 bytes. With the phone's position and an id the message
 * becomes 201 to 204 bytes and could not be sent. [fitText] cuts a few bytes off the end of the text instead.
 */
object TextFit {
    /** Added at the end of a shortened text. */
    const val ELLIPSIS = "…"

    /** [message] with its text shortened to fit, or [message] itself (the same object) when nothing has to change. */
    fun fitText(message: Map<String, Any?>): Map<String, Any?> {
        val type = try {
            RescueCodec.messageType(message)
        } catch (e: CodecException) {
            return message
        }
        if (type != "text") return message
        val body = message["text"] as? Map<*, *> ?: return message
        val text = body["text"] as? String ?: return message
        // Fits already, or broken for another reason (the caller shows that error): leave it.
        if (encodeError(message) != "too_large") return message
        val bytes = text.toByteArray(Charsets.UTF_8)
        // Longest prefix first. The encoded size only shrinks with the prefix, so the first one that fits wins.
        for (cut in bytes.size - 1 downTo 0) {
            if (isContinuationByte(bytes[cut])) continue // the cut would split a character
            val candidate = withText(message, body, String(bytes, 0, cut, Charsets.UTF_8).trimEnd() + ELLIPSIS)
            if (encodeError(candidate) == null) return candidate
        }
        return message
    }

    /**
     * A plain Meshtastic text (port 1) cut to at most [maxBytes] bytes of UTF-8. The Python library has the same rule
     * (`fit_plain`): a text that fits comes back unchanged (the same object); a longer one is cut to the longest
     * prefix that ends on a whole character, trailing whitespace is stripped and [ELLIPSIS] is added.
     * Meshtastic allows 233 bytes, we stay at 200 like every encoder (spec section 2.1).
     */
    fun fitPlain(text: String, maxBytes: Int = Codes.MAX_ENCODE_BYTES): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text
        var cut = (maxBytes - ELLIPSIS.toByteArray(Charsets.UTF_8).size).coerceAtLeast(0)
        while (cut > 0 && isContinuationByte(bytes[cut])) cut-- // the cut would split a character
        return String(bytes, 0, cut, Charsets.UTF_8).trimEnd() + ELLIPSIS
    }

    /** The codec's error code for [message], or null when it encodes. */
    private fun encodeError(message: Map<String, Any?>): String? = try {
        RescueCodec.encode(message)
        null
    } catch (e: CodecException) {
        e.code
    }

    /** 10xxxxxx: the second, third or fourth byte of a UTF-8 character. */
    private fun isContinuationByte(b: Byte) = (b.toInt() and 0xC0) == 0x80

    private fun withText(message: Map<String, Any?>, body: Map<*, *>, text: String): Map<String, Any?> {
        val newBody = LinkedHashMap<String, Any?>()
        for ((k, v) in body) newBody[k.toString()] = v
        newBody["text"] = text
        val out = LinkedHashMap(message)
        out["text"] = newBody
        return out
    }
}

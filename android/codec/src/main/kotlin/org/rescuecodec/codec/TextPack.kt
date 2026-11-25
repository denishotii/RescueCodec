// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.codec

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Packed texts: raw DEFLATE with a shared preset dictionary (spec section 3.5).
 *
 * ```
 * val data = TextPack.pack("We are at the hut", 1)   // raw DEFLATE, level 9, dictionary text-dict-v1.txt
 * TextPack.unpack(data, 1)                            // "We are at the hut"
 * ```
 * Any DEFLATE stream unpacks to the same text everywhere, so only the dictionary bytes must be the
 * same in every library. The packed bytes of two compressors may differ; both are valid.
 * Same as rescuecodec/v2/textpack.py.
 */
object TextPack {
    /** Largest text a receiver unpacks (bytes of UTF-8). Anything longer is rejected. */
    const val MAX_UNPACKED_BYTES: Int = 1024

    /** The dictionary new texts are packed with. */
    const val CURRENT_DICT: Int = 1

    /** Dictionary versions: number to (resource file, SHA-256 of its bytes). */
    val DICTS: Map<Int, Pair<String, String>> = mapOf(
        1 to ("text-dict-v1.txt" to "f1cc69167232438d0440ec8f2be9fa4270c4dd04c502dae13aa2d91bd31c6ec9"),
    )

    private val cache = HashMap<Int, ByteArray>()

    /** The bytes of a dictionary version, from the bundled `rescuecodec/vocab/<file>`. Checked by SHA-256. */
    @Synchronized
    fun dictionary(version: Int): ByteArray = cache.getOrPut(version) {
        val (name, sha) = DICTS[version] ?: throw IllegalArgumentException("unknown dictionary $version")
        val data = TextPack::class.java.getResourceAsStream("/rescuecodec/vocab/$name")?.use { it.readBytes() }
            ?: throw IllegalStateException("dictionary $name is not bundled")
        val digest = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        check(digest == sha) { "dictionary $name is damaged (SHA-256 differs; check line endings)" }
        data
    }

    /** UTF-8 text to raw DEFLATE bytes (level 9, window 15, memLevel 8, with the dictionary). */
    fun pack(text: String, version: Int = CURRENT_DICT): ByteArray {
        val deflater = Deflater(9, true)
        try {
            deflater.setDictionary(dictionary(version))
            deflater.setInput(text.toByteArray(Charsets.UTF_8))
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(512)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /**
     * Raw DEFLATE bytes to text. Never makes more than [MAX_UNPACKED_BYTES] + 1 bytes. Throws
     * [CodecException] with code `malformed` (broken, cut off, bytes after the end, not UTF-8) or
     * `too_large`, field `text.deflate`.
     */
    fun unpack(data: ByteArray, version: Int): String {
        val inflater = Inflater(true)
        try {
            inflater.setDictionary(dictionary(version))
            inflater.setInput(data)
            val out = ByteArray(MAX_UNPACKED_BYTES + 1)
            var size = 0
            try {
                while (!inflater.finished() && size < out.size) {
                    val n = inflater.inflate(out, size, out.size - size)
                    if (n == 0) break // needs more input (cut off) or a dictionary
                    size += n
                }
            } catch (e: DataFormatException) {
                throw fail("malformed", "not a valid DEFLATE stream: ${e.message}")
            }
            if (size > MAX_UNPACKED_BYTES) throw fail("too_large", "unpacks to more than $MAX_UNPACKED_BYTES bytes")
            if (!inflater.finished()) throw fail("malformed", "the DEFLATE stream is cut off")
            if (inflater.remaining > 0) throw fail("malformed", "bytes after the end of the DEFLATE stream")
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(out, 0, size)).toString()
            } catch (e: CharacterCodingException) {
                throw fail("malformed", "the unpacked text is not valid UTF-8")
            }
        } finally {
            inflater.end()
        }
    }

    private fun fail(code: String, message: String) = CodecException(code, message, "text.deflate")
}

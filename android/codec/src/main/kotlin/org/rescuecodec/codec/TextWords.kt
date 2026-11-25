// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.codec

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * Compact texts: 2-byte word tokens from a shared word dictionary (spec section 3.6).
 *
 * ```
 * val data = TextWords.encode("We found Aylin Demir", 1)   // [We] [found] [literal " Aylin Demir"]
 * TextWords.decode(data, 1)                                // "We found Aylin Demir", byte for byte
 * ```
 * The stream is a list of 16-bit big-endian units:
 * - 0x0000..0xEFFF dictionary entry (line number - 1 of spec/vocab/words-dict-v<N>.txt)
 * - 0xF001 CAP: next entry with its first letter a-z written A-Z
 * - 0xF002 UPPER: next entry with every letter a-z written A-Z
 * - 0xF003 GLUE: no space before the next entry
 * - 0xF800 + N literal: the next N bytes (1..2047) are UTF-8, copied exactly
 * - anything else is reserved: the stream is malformed
 *
 * Spaces: the decoder writes one space between two adjacent entries, except before an entry that starts with
 * . , ; : ! ? ) ] } or the ellipsis, and after an entry that ends with ( [ {. Never next to a literal: a literal
 * carries its own spaces. The encoder finds the shortest stream (dynamic programming, fixed tie rules) so Python,
 * Kotlin and TypeScript write the same bytes. Same as rescuecodec/v2/textwords.py, keep them identical.
 */
object TextWords {
    /** Largest text a receiver decodes (bytes of UTF-8), same as for DEFLATE texts. */
    const val MAX_TEXT_BYTES: Int = 1024

    /** The dictionary new texts are encoded with (2: 61,440 entries; 1 stays readable forever). */
    const val CURRENT_DICT: Int = 2

    /** Dictionary versions: number to (resource file, SHA-256 of its bytes). */
    val DICTS: Map<Int, Pair<String, String>> = mapOf(
        1 to ("words-dict-v1.txt" to "ffe93afb4623fcd364479169281ca745cc263317a4f83ce27f77d8d37b26a0d8"),
        2 to ("words-dict-v2.txt" to "3b6fff5d9bb04bfd0e624e5904afaa93895ee373f7fb88c52ae903a6e9a52616"),
    )

    const val CAP: Int = 0xF001
    const val UPPER: Int = 0xF002
    const val GLUE: Int = 0xF003
    const val LITERAL: Int = 0xF800
    const val MAX_LITERAL: Int = 0x07FF
    const val FIRST_CONTROL: Int = 0xF000

    private const val WHITESPACE = " \t\n\r"

    /** Characters that are a piece on their own. */
    private const val SPLIT = ".,;:!?()[]{}\"…"

    /** An entry starting with one of these gets no space before it. */
    private const val LEFT_ATTACH = ".,;:!?)]}…"

    /** An entry ending with one of these gets no space after it. */
    private const val RIGHT_ATTACH = "([{"

    /** A word dictionary: entries by token number, the index back, and the most pieces in one entry. */
    class WordDict private constructor(val entries: List<String>, val index: Map<String, Int>, val maxSpan: Int) {
        /** UTF-8 bytes of each entry. */
        internal val bytes: List<ByteArray> = entries.map { it.toByteArray(Charsets.UTF_8) }

        companion object {
            fun fromEntries(entries: List<String>): WordDict {
                require(entries.size <= FIRST_CONTROL) { "at most $FIRST_CONTROL entries" }
                val index = HashMap<String, Int>(entries.size * 2)
                var span = 1
                for ((i, entry) in entries.withIndex()) {
                    require(
                        entry.isNotEmpty() && entry !in index && entry.none { it == '\t' || it == '\n' || it == '\r' } &&
                            entry == entry.trim(' '),
                    ) { "bad or repeated entry $i: $entry" }
                    index[entry] = i
                    span = maxOf(span, splitPieces(entry).second.size)
                }
                return WordDict(entries.toList(), index, span)
            }

            /** UTF-8, one entry per line, LF, ends with LF. Throws [IllegalArgumentException] for a bad file. */
            fun fromBytes(data: ByteArray): WordDict {
                val text = try {
                    strictUtf8(data, 0, data.size)
                } catch (e: CharacterCodingException) {
                    throw IllegalArgumentException("the dictionary is not UTF-8")
                }
                require(text.endsWith("\n")) { "the dictionary must end with a line break" }
                return fromEntries(text.substring(0, text.length - 1).split("\n"))
            }
        }
    }

    /** Loaded dictionaries, and null for a version that is not bundled or fails its SHA-256 check. */
    private val cache = HashMap<Int, WordDict?>()

    /**
     * The word dictionary of a version, from the bundled `rescuecodec/vocab/<file>`, checked by SHA-256. Throws
     * [IllegalArgumentException] for an unknown version and [IllegalStateException] for a missing or damaged copy.
     */
    fun dictionary(version: Int): WordDict {
        val (name, _) = DICTS[version] ?: throw IllegalArgumentException("unknown word dictionary $version")
        return load(version) ?: throw IllegalStateException("word dictionary $name is missing or damaged (SHA-256 differs)")
    }

    /** True when this library has the dictionary [version] and its bytes match the SHA-256 of the spec. */
    fun available(version: Int): Boolean = DICTS.containsKey(version) && load(version) != null

    @Synchronized
    private fun load(version: Int): WordDict? {
        if (cache.containsKey(version)) return cache[version]
        val (name, sha) = DICTS[version] ?: return null
        val data = TextWords::class.java.getResourceAsStream("/rescuecodec/vocab/$name")?.use { it.readBytes() }
        val dict = checked(data, sha)
        cache[version] = dict
        return dict
    }

    /** The dictionary in [data] when its SHA-256 is [sha] and the file is well formed, else null (never a guess). */
    internal fun checked(data: ByteArray?, sha: String): WordDict? {
        if (data == null) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        if (digest != sha) return null
        return try {
            WordDict.fromBytes(data)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    // ------------------------------------------------------------------ pieces

    /**
     * Text to (gaps, pieces) with text == gaps[0] + pieces[0] + gaps[1] + ... + pieces[n-1] + gaps[n]. A gap is a run of
     * space, tab, CR, LF (maybe empty). A piece is one SPLIT character, or a run of anything else.
     */
    fun splitPieces(text: String): Pair<List<String>, List<String>> {
        val gaps = ArrayList<String>()
        val pieces = ArrayList<String>()
        var i = 0
        val n = text.length
        var j = i
        while (j < n && text[j] in WHITESPACE) j++
        gaps.add(text.substring(i, j))
        i = j
        while (i < n) {
            if (text[i] in SPLIT) {
                j = i + 1
            } else {
                j = i
                while (j < n && text[j] !in WHITESPACE && text[j] !in SPLIT) j++
            }
            pieces.add(text.substring(i, j))
            i = j
            while (j < n && text[j] in WHITESPACE) j++
            gaps.add(text.substring(i, j))
            i = j
        }
        return gaps to pieces
    }

    private fun asciiLower(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in 'A'..'Z') (c.code + 32).toChar() else c)
        return sb.toString()
    }

    private fun asciiUpper(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in 'a'..'z') (c.code - 32).toChar() else c)
        return sb.toString()
    }

    /** (entry index, control or 0) for [s]: the exact entry, else CAP of an entry, else UPPER of an entry. */
    private fun match(s: String, d: WordDict): Pair<Int, Int>? {
        d.index[s]?.let { return it to 0 }
        if (s[0] in 'A'..'Z') {
            d.index[(s[0].code + 32).toChar() + s.substring(1)]?.let { return it to CAP }
        }
        val low = asciiLower(s)
        if (low != s && asciiUpper(low) == s) {
            d.index[low]?.let { return it to UPPER }
        }
        return null
    }

    private fun len8(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    // ------------------------------------------------------------------ encode

    private const val TOK = 0
    private const val LIT = 1
    private const val START = 2
    private const val INF = 1 shl 30

    private const val MODE_NONE = 0
    private const val MODE_GLUE = 1
    private const val MODE_GAP = 2

    /** One step of the shortest stream: piece [k] reached from state [st], as a literal or as a token. */
    private class Step(val k: Int, val st: Int, val lit: Boolean, val idx: Int = 0, val ctrl: Int = 0, val mode: Int = MODE_NONE)

    /** Text to the shortest token stream with dictionary [version] (spec section 3.6, "Encoder"). */
    fun encode(text: String, version: Int = CURRENT_DICT): ByteArray = encode(text, dictionary(version))

    /** Text to the shortest token stream. Throws [CodecException] `too_large` for more than 1024 bytes. */
    fun encode(text: String, d: WordDict): ByteArray {
        if (len8(text) > MAX_TEXT_BYTES) {
            throw CodecException("too_large", "a compact text is at most $MAX_TEXT_BYTES bytes", "text.text")
        }
        val (gaps, pieces) = splitPieces(text)
        val n = pieces.size
        val cost = Array(n + 1) { intArrayOf(INF, INF) }
        val back = Array(n + 1) { arrayOfNulls<Step>(2) }

        fun relax(pos: Int, state: Int, value: Int, step: Step) {
            if (value < cost[pos][state]) {
                cost[pos][state] = value
                back[pos][state] = step
            }
        }

        for (k in 0 until n) {
            val g = gaps[k]
            val g8 = len8(g)
            val states = if (k == 0) intArrayOf(START) else intArrayOf(TOK, LIT)
            for (st in states) {
                val c0 = if (st == START) 0 else cost[k][st]
                if (c0 >= INF) continue
                var span = ""
                for (j in k until minOf(n, k + d.maxSpan)) {
                    span = if (j == k) pieces[k] else span + gaps[j] + pieces[j]
                    val m = match(span, d) ?: continue
                    val (idx, ctrl) = m
                    val ct = if (ctrl != 0) 4 else 2
                    val implied = st == TOK && span[0] !in LEFT_ATTACH && pieces[k - 1].last() !in RIGHT_ATTACH
                    val add: Int
                    val mode: Int
                    if (implied && g == " ") {
                        add = ct; mode = MODE_NONE
                    } else if (implied && g == "") {
                        add = 2 + ct; mode = MODE_GLUE
                    } else if (g == "") {
                        add = ct; mode = MODE_NONE
                    } else if (st == LIT) {
                        add = g8 + ct; mode = MODE_GAP
                    } else {
                        add = 2 + g8 + ct; mode = MODE_GAP
                    }
                    relax(j + 1, TOK, c0 + add, Step(k, st, false, idx, ctrl, mode))
                }
                val add = g8 + len8(pieces[k]) + (if (st == LIT) 0 else 2)
                relax(k + 1, LIT, c0 + add, Step(k, st, true))
            }
        }

        val last = gaps[n]
        val steps = ArrayList<Step>()
        if (n > 0) {
            val endTok = cost[n][TOK] + (if (last == "") 0 else 2 + len8(last))
            val endLit = cost[n][LIT] + len8(last)
            var pos = n
            var st = if (endTok <= endLit) TOK else LIT
            while (pos > 0) {
                val step = back[pos][st]!!
                steps.add(step)
                pos = step.k
                st = step.st
            }
            steps.reverse()
        }

        val out = ByteArrayOutputStream()
        val run = ByteArrayOutputStream()
        fun unit(code: Int) {
            out.write((code shr 8) and 0xFF)
            out.write(code and 0xFF)
        }
        fun flush() {
            if (run.size() > 0) {
                unit(LITERAL + run.size())
                run.writeTo(out)
                run.reset()
            }
        }
        for (step in steps) {
            val k = step.k
            if (step.lit) {
                run.write((gaps[k] + pieces[k]).toByteArray(Charsets.UTF_8))
                continue
            }
            if (step.mode == MODE_GAP) run.write(gaps[k].toByteArray(Charsets.UTF_8))
            flush()
            if (step.mode == MODE_GLUE) unit(GLUE)
            if (step.ctrl != 0) unit(step.ctrl)
            unit(step.idx)
        }
        run.write(last.toByteArray(Charsets.UTF_8))
        flush()
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ decode

    /**
     * Token stream to text with dictionary [version]. Throws [CodecException] with code `malformed` or `too_large`,
     * field `text.words`, and [IllegalArgumentException] / [IllegalStateException] for a dictionary we do not have.
     */
    fun decode(data: ByteArray, version: Int): String = decode(data, dictionary(version))

    fun decode(data: ByteArray, d: WordDict): String {
        val out = ByteArrayOutputStream()
        var prev = START // last unit written: start, token or literal
        var prevRight = false // the last token ends with ( [ {
        var glue = false
        var case = 0
        var i = 0
        while (i < data.size) {
            if (i + 2 > data.size) throw fail("malformed", "the token stream is cut off (half a token)")
            val code = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
            if (code < FIRST_CONTROL) {
                if (code >= d.entries.size) throw fail("malformed", "token $code is not in the dictionary (${d.entries.size} entries)")
                var entry = d.entries[code]
                if (case == CAP && entry[0] in 'a'..'z') {
                    entry = (entry[0].code - 32).toChar() + entry.substring(1)
                } else if (case == UPPER) {
                    entry = asciiUpper(entry)
                }
                if (prev == TOK && !glue && entry[0] !in LEFT_ATTACH && !prevRight) out.write(0x20)
                out.write(if (case == 0) d.bytes[code] else entry.toByteArray(Charsets.UTF_8))
                prev = TOK
                prevRight = entry.last() in RIGHT_ATTACH
                glue = false
                case = 0
            } else if (code > LITERAL) {
                if (glue || case != 0) throw fail("malformed", "a control must be followed by a token, not a literal")
                val size = code - LITERAL
                if (i + size > data.size) throw fail("malformed", "the literal of $size bytes is cut off")
                try {
                    strictUtf8(data, i, size)
                } catch (e: CharacterCodingException) {
                    throw fail("malformed", "a literal is not valid UTF-8")
                }
                out.write(data, i, size)
                i += size
                prev = LIT
            } else if (code == CAP || code == UPPER) {
                if (case != 0) throw fail("malformed", "two case controls before one token")
                case = code
            } else if (code == GLUE) {
                if (glue) throw fail("malformed", "two GLUE controls before one token")
                glue = true
            } else {
                throw fail("malformed", if (code != LITERAL) "unknown control 0x%04X".format(code) else "empty literal")
            }
            if (out.size() > MAX_TEXT_BYTES) throw fail("too_large", "decodes to more than $MAX_TEXT_BYTES bytes")
        }
        if (glue || case != 0) throw fail("malformed", "the stream ends with a control")
        if (out.size() == 0) throw fail("malformed", "empty token stream")
        val bytes = out.toByteArray()
        return try {
            strictUtf8(bytes, 0, bytes.size)
        } catch (e: CharacterCodingException) {
            throw fail("malformed", "the decoded text is not valid UTF-8")
        }
    }

    private fun strictUtf8(data: ByteArray, offset: Int, length: Int): String =
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data, offset, length)).toString()

    private fun fail(code: String, message: String) = CodecException(code, message, "text.words")
}

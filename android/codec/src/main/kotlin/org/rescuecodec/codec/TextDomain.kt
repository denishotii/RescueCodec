// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.codec

import java.io.ByteArrayOutputStream
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest

/**
 * Domain frames: chat texts with a small team vocabulary of N-bit codes (spec section 3.8).
 *
 * ```
 * val stream = TextDomain.encode("Patient is conscious, bleeding from the leg.", 5)   // 5 = medical
 * TextDomain.decode(stream, 5)                                                     // the same text, byte for byte
 * ```
 * A domain vocabulary has 2^N - 8 entries (N = 10 for every vocabulary of version 1: 1016 entries). The stream is a
 * bit stream of N-bit codes, most significant bit first, the last byte padded with zero bits:
 * - 0 .. 2^N-9: entry (line number - 1 of spec/vocab/domain-<domain>-v1.txt)
 * - 2^N-8 ESC16: the next 16 bits are a token of word dictionary 2 (0x0000..0xEFFF)
 * - 2^N-7 CAP: the next entry (or escaped token) with its first letter a-z written A-Z
 * - 2^N-6 UPPER: the next entry (or escaped token) with every letter a-z written A-Z
 * - 2^N-5 GLUE: no space before the next entry
 * - 2^N-4 LIT: the next 8 bits are a length L (1..255), then L bytes of UTF-8, copied exactly
 * - 2^N-3 .. 2^N-1: reserved, the stream is malformed
 *
 * Spaces, punctuation and literals follow spec section 3.6.3 exactly (an escaped token counts as an entry). The
 * encoder is the shortest-stream search of section 3.6.4 with costs in bits, so Python, Kotlin and TypeScript write
 * the same bytes. Never a near match: a word in neither the vocabulary nor word dictionary 2 goes as a literal.
 * Same as rescuecodec/v2/textdomain.py, keep them identical.
 */
object TextDomain {
    /** Version of the frame rules (spec/vocab/domains.yaml `format`). */
    const val FORMAT: Int = 1

    /** The top 8 codes of every code space are controls. */
    const val RESERVED: Int = 8

    /** Control offsets from 2^N - 8. */
    const val ESC16: Int = 0
    const val CAP: Int = 1
    const val UPPER: Int = 2
    const val GLUE: Int = 3
    const val LIT: Int = 4

    /** Word dictionary that ESC16 tokens come from. */
    const val GENERAL_DICT: Int = 2

    /** Longest literal in one LIT code (the length is 8 bits). */
    const val MAX_LITERAL: Int = 255

    /** Team vocabulary setting: try every domain frame. */
    const val AUTO: String = "auto"

    /** Team vocabulary setting: no domain frame, only the general forms. */
    const val GENERAL: String = "general"

    /**
     * One team vocabulary. [id] is the byte after 0x03 on the air; [domain] a stable name ("sar"); [label] the long name
     * for settings ("Search & Rescue"); [short] the name in message tags ("S&R"); [bits] the code width N; [file] in
     * spec/vocab with its [sha256]; [entries] = 2^N - 8.
     */
    data class Vocab(
        val id: Int,
        val domain: String,
        val label: String,
        val short: String,
        val version: Int,
        val bits: Int,
        val file: String,
        val sha256: String,
        val entries: Int,
    )

    /** Vocabulary id to vocabulary (spec/vocab/domains.yaml). Ids 0 and 6..255 are not assigned. */
    val VOCABS: Map<Int, Vocab> = listOf(
        Vocab(1, "emergency", "Emergency", "Emergency", 1, 10, "domain-emergency-v1.txt",
            "e98e8c9026b9f75b29a681dfaee2a49ebac39fbbea4d1471e60949ac71164664", 1016),
        Vocab(2, "sar", "Search & Rescue", "S&R", 1, 10, "domain-sar-v1.txt",
            "444798678a105827cf783b990c0ceda05b4cf927b4846c15e0345213f60092ee", 1016),
        Vocab(3, "fire", "Firefighting", "Fire", 1, 10, "domain-fire-v1.txt",
            "d37f489ed55a944d59fcc3b53c985da2facf3aefa81cf9053240d80cf62dc9a2", 1016),
        Vocab(4, "hiking", "Hiking / Mountain", "Hiking", 1, 10, "domain-hiking-v1.txt",
            "3e03a004a0696afc41738d71280e13125390eff85f3ab073d6b9c7b510e6464a", 1016),
        Vocab(5, "medical", "Medical / First aid", "Medical", 1, 10, "domain-medical-v1.txt",
            "21a76e1f01d3aac7291875f6b863244fc873d724cdae4afddd7a3cfcade6be3b", 1016),
    ).associateBy { it.id }

    /** Domain name to the id new texts are encoded with (the newest version of each domain). */
    val CURRENT: Map<String, Int> = LinkedHashMap<String, Int>().apply {
        for (v in VOCABS.values.sortedWith(compareBy({ it.version }, { it.id }))) put(v.domain, v.id)
    }

    /** Choices of the "team vocabulary" setting: auto (every domain), general (no domain frame) or one domain. */
    val SETTINGS: List<String> = listOf(AUTO, GENERAL) + CURRENT.keys

    /** The id new texts of a domain ("sar") are encoded with; null for an unknown name. */
    fun vocabId(domain: String): Int? = CURRENT[domain]

    /**
     * Vocabulary ids the sender tries for a team vocabulary [setting]: auto = all (by id), general = none, a domain =
     * that one. Throws [IllegalArgumentException] for an unknown setting.
     */
    fun candidates(setting: String): List<Int> = when (setting) {
        AUTO -> CURRENT.values.sorted()
        GENERAL -> emptyList()
        else -> listOf(CURRENT[setting] ?: throw IllegalArgumentException("team vocabulary must be one of ${SETTINGS.joinToString(", ")}"))
    }

    // ------------------------------------------------------------------ files

    /** Loaded vocabularies, and null for an id that is not bundled or fails its SHA-256 check. */
    private val cache = HashMap<Int, TextWords.WordDict?>()

    /**
     * The entries of vocabulary [id], from the bundled `rescuecodec/vocab/<file>`, checked by SHA-256. Throws
     * [IllegalArgumentException] for an unknown id and [IllegalStateException] for a missing or damaged copy.
     */
    fun vocabulary(id: Int): TextWords.WordDict {
        val v = VOCABS[id] ?: throw IllegalArgumentException("unknown team vocabulary $id")
        return load(id) ?: throw IllegalStateException("team vocabulary ${v.file} is missing or damaged (SHA-256 differs)")
    }

    /** True when this library has vocabulary [id] and word dictionary 2 (for ESC16), both matching their SHA-256. */
    fun available(id: Int): Boolean = VOCABS.containsKey(id) && load(id) != null && TextWords.available(GENERAL_DICT)

    @Synchronized
    private fun load(id: Int): TextWords.WordDict? {
        if (cache.containsKey(id)) return cache[id]
        val v = VOCABS[id] ?: return null
        val data = TextDomain::class.java.getResourceAsStream("/rescuecodec/vocab/${v.file}")?.use { it.readBytes() }
        val dict = checked(data, v)
        cache[id] = dict
        return dict
    }

    /** Tests: use [data] as the copy of vocabulary [id], checked like the bundled file; null: the bundled file again. */
    @Synchronized
    internal fun useCopy(id: Int, data: ByteArray?) {
        if (data == null) cache.remove(id) else cache[id] = VOCABS[id]?.let { checked(data, it) }
    }

    /** The vocabulary in [data] when its SHA-256 and entry count are those of [v], else null (never a guess). */
    internal fun checked(data: ByteArray?, v: Vocab): TextWords.WordDict? {
        if (data == null) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        if (digest != v.sha256) return null
        val dict = try {
            TextWords.WordDict.fromBytes(data)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return dict.takeIf { it.entries.size == v.entries && v.entries == (1 shl v.bits) - RESERVED }
    }

    // ------------------------------------------------------------------ bits

    /** Writes values of 1..16 bits, most significant bit first. */
    private class BitWriter {
        val out = ByteArrayOutputStream()
        private var acc = 0
        private var n = 0

        fun put(value: Int, width: Int) {
            acc = (acc shl width) or value
            n += width
            while (n >= 8) {
                n -= 8
                out.write((acc shr n) and 0xFF)
            }
            acc = acc and ((1 shl n) - 1)
        }

        /** The bytes, the last one padded with fewer than 8 zero bits. */
        fun done(): ByteArray {
            if (n > 0) out.write((acc shl (8 - n)) and 0xFF)
            return out.toByteArray()
        }
    }

    private class BitReader(private val data: ByteArray) {
        private var pos = 0
        private val total = 8 * data.size

        fun left(): Int = total - pos

        fun get(width: Int): Int {
            var value = 0
            repeat(width) {
                val byte = data[pos shr 3].toInt() and 0xFF
                value = (value shl 1) or ((byte shr (7 - (pos and 7))) and 1)
                pos++
            }
            return value
        }
    }

    /** A literal run as LIT pieces of at most 255 bytes: each the longest prefix that ends on a whole character. */
    fun literalChunks(run: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var start = 0
        while (start < run.size) {
            var cut = minOf(MAX_LITERAL, run.size - start)
            while (start + cut < run.size && (run[start + cut].toInt() and 0xC0) == 0x80) cut--
            out.add(run.copyOfRange(start, start + cut))
            start += cut
        }
        return out
    }

    // ------------------------------------------------------------------ encode

    private const val TOK = 0
    private const val LITS = 1
    private const val START = 2
    private const val INF = 1 shl 30

    private const val MODE_NONE = 0
    private const val MODE_GLUE = 1
    private const val MODE_GAP = 2

    /** One step of the shortest stream: piece [k] reached from state [st], as a literal or as an entry. */
    private class Step(
        val k: Int,
        val st: Int,
        val lit: Boolean,
        val idx: Int = 0,
        val ctrl: Int = 0,
        val escaped: Boolean = false,
        val mode: Int = MODE_NONE,
    )

    /** A match: entry index, case control (0, [CAP], [UPPER]), escaped to word dictionary 2, cost in bits. */
    private class Match(val idx: Int, val ctrl: Int, val escaped: Boolean, val bits: Int)

    /**
     * Text to the shortest domain bit stream (spec section 3.8.4), padded to whole bytes. Throws [CodecException]
     * `too_large` for more than 1024 bytes of text, [IllegalArgumentException] for an unknown vocabulary and
     * [IllegalStateException] for a damaged one.
     */
    fun encode(text: String, vocab: Int): ByteArray {
        val v = VOCABS[vocab] ?: throw IllegalArgumentException("unknown team vocabulary $vocab")
        val dd = vocabulary(vocab)
        val d2 = TextWords.dictionary(GENERAL_DICT)
        val nb = v.bits
        val base = (1 shl nb) - RESERVED
        val litHead = nb + 8
        if (TextWords.len8(text) > TextWords.MAX_TEXT_BYTES) {
            throw CodecException("too_large", "a compact text is at most ${TextWords.MAX_TEXT_BYTES} bytes", "text.text")
        }
        val (gaps, pieces) = TextWords.splitPieces(text)
        val n = pieces.size
        val spanMax = maxOf(dd.maxSpan, d2.maxSpan)
        val cost = Array(n + 1) { intArrayOf(INF, INF) }
        val back = Array(n + 1) { arrayOfNulls<Step>(2) }

        fun relax(pos: Int, state: Int, value: Int, step: Step) {
            if (value < cost[pos][state]) {
                cost[pos][state] = value
                back[pos][state] = step
            }
        }

        // The vocabulary first (always cheaper), else word dictionary 2 behind ESC16.
        fun best(span: String): Match? {
            TextWords.match(span, dd)?.let { (idx, ctrl) -> return Match(idx, caseOf(ctrl), false, nb * (if (ctrl != 0) 2 else 1)) }
            TextWords.match(span, d2)?.let { (idx, ctrl) -> return Match(idx, caseOf(ctrl), true, nb * (if (ctrl != 0) 2 else 1) + 16) }
            return null
        }

        for (k in 0 until n) {
            val g = gaps[k]
            val gBits = 8 * TextWords.len8(g)
            val states = if (k == 0) intArrayOf(START) else intArrayOf(TOK, LITS)
            for (st in states) {
                val c0 = if (st == START) 0 else cost[k][st]
                if (c0 >= INF) continue
                var span = ""
                for (j in k until minOf(n, k + spanMax)) {
                    span = if (j == k) pieces[k] else span + gaps[j] + pieces[j]
                    val m = best(span) ?: continue
                    val ct = m.bits
                    val implied = st == TOK && span[0] !in TextWords.LEFT_ATTACH && pieces[k - 1].last() !in TextWords.RIGHT_ATTACH
                    val add: Int
                    val mode: Int
                    if (implied && g == " ") {
                        add = ct; mode = MODE_NONE
                    } else if (implied && g == "") {
                        add = nb + ct; mode = MODE_GLUE
                    } else if (g == "") {
                        add = ct; mode = MODE_NONE
                    } else if (st == LITS) {
                        add = gBits + ct; mode = MODE_GAP
                    } else {
                        add = litHead + gBits + ct; mode = MODE_GAP
                    }
                    relax(j + 1, TOK, c0 + add, Step(k, st, false, m.idx, m.ctrl, m.escaped, mode))
                }
                val add = gBits + 8 * TextWords.len8(pieces[k]) + (if (st == LITS) 0 else litHead)
                relax(k + 1, LITS, c0 + add, Step(k, st, true))
            }
        }

        val last = gaps[n]
        val steps = ArrayList<Step>()
        if (n > 0) {
            val endTok = cost[n][TOK] + (if (last == "") 0 else litHead + 8 * TextWords.len8(last))
            val endLit = cost[n][LITS] + 8 * TextWords.len8(last)
            var pos = n
            var st = if (endTok <= endLit) TOK else LITS
            while (pos > 0) {
                val step = back[pos][st]!!
                steps.add(step)
                pos = step.k
                st = step.st
            }
            steps.reverse()
        }

        val w = BitWriter()
        val run = ByteArrayOutputStream()
        fun flush() {
            for (chunk in literalChunks(run.toByteArray())) {
                w.put(base + LIT, nb)
                w.put(chunk.size, 8)
                for (b in chunk) w.put(b.toInt() and 0xFF, 8)
            }
            run.reset()
        }
        for (step in steps) {
            val k = step.k
            if (step.lit) {
                run.write((gaps[k] + pieces[k]).toByteArray(Charsets.UTF_8))
                continue
            }
            if (step.mode == MODE_GAP) run.write(gaps[k].toByteArray(Charsets.UTF_8))
            flush()
            if (step.mode == MODE_GLUE) w.put(base + GLUE, nb)
            if (step.ctrl != 0) w.put(base + step.ctrl, nb)
            if (step.escaped) {
                w.put(base + ESC16, nb)
                w.put(step.idx, 16)
            } else {
                w.put(step.idx, nb)
            }
        }
        run.write(last.toByteArray(Charsets.UTF_8))
        flush()
        return w.done()
    }

    /** A case control of [TextWords.match] as a domain control offset. */
    private fun caseOf(ctrl: Int): Int = when (ctrl) {
        0 -> 0
        TextWords.CAP -> CAP
        else -> UPPER
    }

    // ------------------------------------------------------------------ decode

    /**
     * Domain bit stream to text. Throws [CodecException] `malformed` or `too_large` (field `text.vocab`),
     * [IllegalArgumentException] for an unknown vocabulary and [IllegalStateException] for a damaged one. [trace],
     * when given, gets one line per code for tools and test vectors, e.g. `CAP`, `E 31 "search"`,
     * `ESC16 0x0D0A "pizza"`, `LIT 3 " 4."`, `GLUE`.
     */
    fun decode(data: ByteArray, vocab: Int, trace: MutableList<String>? = null): String {
        val v = VOCABS[vocab] ?: throw IllegalArgumentException("unknown team vocabulary $vocab")
        val dd = vocabulary(vocab)
        val d2 = TextWords.dictionary(GENERAL_DICT)
        val nb = v.bits
        val base = (1 shl nb) - RESERVED
        val r = BitReader(data)
        val out = ByteArrayOutputStream()
        var prev = START // last unit written: start, entry or literal
        var prevRight = false // the last entry ends with ( [ {
        var glue = false
        var case = 0 // 0, CAP or UPPER
        while (r.left() >= nb) {
            val code = r.get(nb)
            if (code < base || code == base + ESC16) {
                var entry: String
                if (code < base) {
                    if (code >= dd.entries.size) throw fail("malformed", "code $code is not in the vocabulary (${dd.entries.size} entries)")
                    entry = dd.entries[code]
                    trace?.add("E $code ${quote(entry)}")
                } else {
                    if (r.left() < 16) throw fail("malformed", "the escaped token is cut off")
                    val token = r.get(16)
                    if (token >= TextWords.FIRST_CONTROL || token >= d2.entries.size) {
                        throw fail("malformed", "escaped token 0x%04X is not in word dictionary $GENERAL_DICT".format(token))
                    }
                    entry = d2.entries[token]
                    trace?.add("ESC16 0x%04X %s".format(token, quote(entry)))
                }
                if (case == CAP && entry[0] in 'a'..'z') {
                    entry = (entry[0].code - 32).toChar() + entry.substring(1)
                } else if (case == UPPER) {
                    entry = TextWords.asciiUpper(entry)
                }
                if (prev == TOK && !glue && entry[0] !in TextWords.LEFT_ATTACH && !prevRight) out.write(0x20)
                out.write(entry.toByteArray(Charsets.UTF_8))
                prev = TOK
                prevRight = entry.last() in TextWords.RIGHT_ATTACH
                glue = false
                case = 0
            } else if (code == base + LIT) {
                if (glue || case != 0) throw fail("malformed", "a control must be followed by an entry, not a literal")
                if (r.left() < 8) throw fail("malformed", "the literal length is cut off")
                val size = r.get(8)
                if (size == 0) throw fail("malformed", "empty literal")
                if (r.left() < 8 * size) throw fail("malformed", "the literal of $size bytes is cut off")
                val chunk = ByteArray(size) { r.get(8).toByte() }
                val s = try {
                    TextWords.strictUtf8(chunk, 0, size)
                } catch (e: CharacterCodingException) {
                    throw fail("malformed", "a literal is not valid UTF-8")
                }
                out.write(chunk)
                prev = LITS
                trace?.add("LIT $size ${quote(s)}")
            } else if (code == base + CAP || code == base + UPPER) {
                if (case != 0) throw fail("malformed", "two case controls before one entry")
                case = if (code == base + CAP) CAP else UPPER
                trace?.add(if (case == CAP) "CAP" else "UPPER")
            } else if (code == base + GLUE) {
                if (glue) throw fail("malformed", "two GLUE controls before one entry")
                glue = true
                trace?.add("GLUE")
            } else {
                throw fail("malformed", "reserved code $code (control ${code - base})")
            }
            if (out.size() > TextWords.MAX_TEXT_BYTES) throw fail("too_large", "decodes to more than ${TextWords.MAX_TEXT_BYTES} bytes")
        }
        val rest = r.left()
        if (rest >= 8 || (rest > 0 && r.get(rest) != 0)) throw fail("malformed", "the padding must be fewer than 8 zero bits")
        if (glue || case != 0) throw fail("malformed", "the stream ends with a control")
        if (out.size() == 0) throw fail("malformed", "empty stream")
        val bytes = out.toByteArray()
        return try {
            TextWords.strictUtf8(bytes, 0, bytes.size)
        } catch (e: CharacterCodingException) {
            throw fail("malformed", "the decoded text is not valid UTF-8")
        }
    }

    /** A string in double quotes with JSON escapes (the same in every language), for traces. */
    private fun quote(s: String): String = Json.stringify(s)

    private fun fail(code: String, message: String) = CodecException(code, message, "text.vocab")
}

// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.engine

import java.math.BigDecimal
import java.math.RoundingMode
import org.rescuecodec.codec.CodecException
import org.rescuecodec.codec.Codes
import org.rescuecodec.codec.RescueCodec

/** One fact the extractor found, so the app can show why a field was set. */
data class Evidence(val field: String, val value: String, val words: String)

/**
 * Result of [Extractor.extract].
 *
 * @property message canonical JSON message (spec section 4) holding only what the sentence says.
 *   It has no `id` and no device GPS: the app adds both. When [unsupported] is true it is the text
 *   fallback `{"text": {"text": "..."}}`, cut to 180 bytes.
 * @property unsupported true when the sentence cannot be coded safely. The app should offer the text
 *   fallback and let the person decide.
 * @property reasons short machine-readable notes ("type=found", "conflicting_states", ...).
 * @property evidence which words set which field.
 * @property pos spoken coordinates, kept even when the result is a text fallback.
 */
data class Extraction(
    val message: Map<String, Any?>,
    val unsupported: Boolean,
    val reasons: List<String> = emptyList(),
    val evidence: List<Evidence> = emptyList(),
    val pos: Map<String, Any?>? = null,
)

/**
 * Rule-based extractor: a spoken or typed German or English report becomes one RescueCodec message.
 *
 * This is a port of the Python reference extractor (`rescuecodec/engine/extract.py`). Both read the
 * same word list (`spec/vocab/sar.yaml`) and must give the same fields on `spec/vectors/extraction.json`.
 * Rules in short:
 *  - Words match on tokens after folding umlauts. A word may carry an ending (s, e, n, en, er, es, em),
 *    one filler word may sit inside a phrase ("kann selbst laufen"), and words of 6+ letters allow one
 *    typo unless the written word is a known word itself.
 *  - The longest phrase wins ("nicht ansprechbar" beats "ansprechbar"). Negation and uncertainty words
 *    act on the next three words of the same clause.
 *  - Type order: Mayday, Sector (with a status), Found, Request, Hazard. A generic person noun
 *    ("Person", "Verletzter") plus a state counts as Found when there is no request or hazard cue.
 *  - Only what the sentence says. Two persons in different states or an object found give the text fallback.
 *
 * The extractor is pure and thread-safe. A person must always check the fields before sending.
 */
class Extractor(private val vocab: Vocab = Vocab.default) {

    // ------------------------------------------------------------ data types

    private class Phrase(val field: String, val code: String, val tokens: List<String>)

    private class Match(val field: String, val code: String, val start: Int, val end: Int, val cost: Int) {
        var negated = false
        var uncertain = false
        val len get() = end - start
    }

    /** One analysed sentence. */
    private class Run(val text: String, val tokens: List<String>, val clauses: List<Int>, val matches: List<Match>, val pos: Map<String, Any?>?) {
        fun words(m: Match) = tokens.subList(m.start, m.end).joinToString(" ")

        /** Matches of one field after negation and uncertainty: uncertain drops, negated flips or drops. */
        fun effective(field: String): List<Match> {
            val out = ArrayList<Match>()
            for (m in matches) {
                if (m.field != field || m.uncertain) continue
                if (m.negated) {
                    FLIP[field to m.code]?.let { out.add(Match(field, it, m.start, m.end, m.cost)) }
                    continue
                }
                out.add(m)
            }
            return out
        }

        fun cue(kind: String) = matches.filter { it.field == "msg" && it.code == kind && !it.negated }
    }

    // ----------------------------------------------------------- vocabulary

    private val phrases = ArrayList<Phrase>()
    private val known: Set<String>
    private val numberWords = ArrayList<Pair<List<String>, Int>>()
    private val helperPhrases = ArrayList<List<String>>()
    private val personPhrases = ArrayList<List<String>>()
    private val personNounPhrases = ArrayList<List<String>>()
    private val coverageFixed = ArrayList<Pair<List<String>, Int>>()

    init {
        fun add(field: String, code: String, word: String) {
            val t = tokenize(word).first
            if (t.isNotEmpty()) phrases.add(Phrase(field, code, t))
        }
        for ((type, words) in vocab.messages) for (w in words) add("msg", type, w)
        for ((field, codes) in vocab.fields) for ((code, words) in codes) for (w in words) add(field, code, w)
        for (w in vocab.negation) add("negation", "negation", w)
        for (w in vocab.uncertainty) add("uncertainty", "uncertainty", w)
        known = phrases.flatMap { it.tokens }.toSet()
        for ((k, v) in vocab.numbers) {
            val t = tokenize(k).first
            if (t.isNotEmpty()) numberWords.add(t to v)
        }
        fun plain(words: List<String>, into: MutableList<List<String>>) {
            for (w in words) tokenize(w).first.takeIf { it.isNotEmpty() }?.let { into.add(it) }
        }
        plain(vocab.helpers, helperPhrases)
        plain(vocab.persons, personPhrases)
        plain(vocab.personNouns, personNounPhrases)
        // Age words ("Kind", "Wanderer") also count as persons in "zwei Kinder".
        personPhrases.addAll(phrases.filter { it.field == "age" }.map { it.tokens })
        for ((k, v) in vocab.coverageFixed) tokenize(k).first.takeIf { it.isNotEmpty() }?.let { coverageFixed.add(it to v) }
    }

    // ------------------------------------------------------------ entry point

    /**
     * Turns [text] into a message. [lang] ("de" or "en") is a hint only: both languages are always
     * matched, so a German sentence with an English word still works.
     */
    @Suppress("UNUSED_PARAMETER")
    fun extract(text: String, lang: String? = null): Extraction {
        if (text.isBlank()) return Extraction(emptyMap(), true, listOf("empty"))
        val run = analyse(text)
        val cues = listOf("mayday", "sector", "found", "request", "hazard").filter { run.cue(it).isNotEmpty() }
        var type: String? = null
        var body: Map<String, Any?>? = null
        var reason: String? = null

        val maydayReasons = run.effective("mayday.reason").filter { it.code != "other" }
        if (run.cue("mayday").isNotEmpty() || maydayReasons.isNotEmpty()) {
            type = "mayday"
            body = buildMayday(run)
        } else {
            val sector = if (run.cue("sector").isNotEmpty()) buildSector(run) else null
            if (sector?.first != null) {
                type = "sector"
                body = sector.first
            } else {
                val hasAge = run.effective("age").isNotEmpty()
                val anyState = STATE_GROUPS.any { run.effective(it).isNotEmpty() }
                val impliedFound = hasAge && anyState
                val generic = run.cue("request").isEmpty() && run.cue("hazard").isEmpty() &&
                    run.effective("hazard.kind").isEmpty() && anyState &&
                    personNounPhrases.any { p -> run.tokens.indices.any { matchAt(run, it, p, false) != null } }
                if (run.cue("found").isNotEmpty() || impliedFound || generic) {
                    if (run.cue("found").isNotEmpty() && objectFound(run)) return fallback(run, text, "object_found", cues)
                    val f = buildFound(run)
                    type = "found"
                    body = f.first
                    reason = f.second
                } else if (sector?.second == "ambiguous") {
                    return fallback(run, text, "ambiguous", cues)
                } else if (run.cue("request").isNotEmpty()) {
                    type = "request"
                    body = buildRequest(run)
                    reason = "missing_field"
                } else if (run.cue("hazard").isNotEmpty() || run.effective("hazard.kind").isNotEmpty()) {
                    type = "hazard"
                    body = buildHazard(run)
                    reason = "missing_field"
                }
            }
        }
        if (type == null || body == null) return fallback(run, text, reason ?: "no_supported_type", cues)

        val msg = LinkedHashMap<String, Any?>()
        priority(run, type, body)?.let { msg["priority"] = it }
        run.pos?.let { msg["pos"] = LinkedHashMap(it) }
        msg[type] = body
        val probe = LinkedHashMap(msg)
        if (type in Codes.ID_REQUIRED) probe["id"] = 1
        try {
            RescueCodec.encode(probe)
        } catch (e: CodecException) {
            return fallback(run, text, "invalid", cues)
        }
        return Extraction(msg, false, listOf("type=$type"), evidence(run), run.pos)
    }

    private fun evidence(run: Run) = run.matches.map { Evidence(it.field, it.code, run.words(it)) }

    private fun fallback(run: Run, text: String, reason: String, cues: List<String>): Extraction {
        var raw = text.trim().split(Regex("\\s+")).joinToString(" ").toByteArray(Charsets.UTF_8)
        if (raw.size > Codes.MAX_TEXT_BYTES) raw = raw.copyOf(Codes.MAX_TEXT_BYTES)
        // A cut inside a multi-byte character leaves a broken tail: drop it.
        val cut = String(raw, Charsets.UTF_8).trimEnd('�')
        if (cut.isEmpty()) return Extraction(emptyMap(), true, listOf("empty"), emptyList(), run.pos)
        return Extraction(linkedMapOf("text" to linkedMapOf("text" to cut)), true, listOf(reason) + cues.map { "cue=$it" }, evidence(run), run.pos)
    }

    // ------------------------------------------------------------- analysis

    private fun analyse(original: String): Run {
        // Spoken coordinates: "50.69212, 7.21345" or "50.70001 7.19876".
        var clean = original
        var pos: Map<String, Any?>? = null
        val m = COORD.find(original)
        if (m != null) {
            val g = m.groupValues.drop(1).filter { it.isNotEmpty() }
            val lat = g[0].replace(',', '.').toDouble()
            val lon = g[1].replace(',', '.').toDouble()
            if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                pos = linkedMapOf("lat" to round5(g[0]), "lon" to round5(g[1]), "acc" to "estimated")
                clean = original.substring(0, m.range.first) + " " + original.substring(m.range.last + 1)
            }
        }
        val (tokens, clauses) = tokenize(clean)
        val run0 = Run(original, tokens, clauses, emptyList(), pos)
        return Run(original, tokens, clauses, findMatches(run0), pos)
    }

    private fun round5(s: String): Double = BigDecimal(s.replace(',', '.')).setScale(5, RoundingMode.HALF_EVEN).toDouble()

    private fun findMatches(run: Run): List<Match> {
        val found = LinkedHashMap<List<Any>, Match>()
        for (ph in phrases) {
            if (ph.field in IGNORED_FIELDS) continue
            val fuzzy = ph.field != "negation" && ph.field != "uncertainty"
            for (i in run.tokens.indices) {
                val hit = matchAt(run, i, ph.tokens, fuzzy) ?: continue
                val key = listOf(ph.field, ph.code, i, hit.first)
                val old = found[key]
                if (old == null || old.cost > hit.second) found[key] = Match(ph.field, ph.code, i, hit.first, hit.second)
            }
        }
        val all = found.values.toList()
        val mods = all.filter { it.field == "negation" || it.field == "uncertainty" }
        val cues = all.filter { it.field != "negation" && it.field != "uncertainty" }
        // A longer phrase eats the shorter phrase inside it, except that message cue words never eat field words.
        val kept = cues.filter { m ->
            cues.none { n -> n !== m && n.field != "msg" && n.start <= m.start && m.end <= n.end && n.len > m.len }
        }
        // Exact beats typo on the same span and field.
        val best = HashMap<Triple<String, Int, Int>, Int>()
        for (m in kept) best.merge(Triple(m.field, m.start, m.end), m.cost) { a, b -> minOf(a, b) }
        val keep = kept.filter { it.cost == best.getValue(Triple(it.field, it.start, it.end)) }
            .sortedWith(compareBy({ it.start }, { it.end }))
        annotate(run, keep, mods)
        return keep
    }

    private fun annotate(run: Run, keep: List<Match>, mods: List<Match>) {
        val covered = HashSet<Int>()
        for (m in keep) for (t in m.start until m.end) covered.add(t)
        val uncSpans = mods.filter { it.field == "uncertainty" }
        val uncTokens = HashSet<Int>()
        for (u in uncSpans) for (t in u.start until u.end) uncTokens.add(t)
        val negTokens = HashSet<Int>()
        for (n in mods) {
            if (n.field == "negation" && (n.start until n.end).none { it in covered || it in uncTokens }) negTokens.add(n.end - 1)
        }
        for ((i, tok) in run.tokens.withIndex()) {
            if (tok in EXTRA_NEGATION && i !in covered && i !in uncTokens) negTokens.add(i)
        }
        for (m in keep) {
            for (ni in negTokens) {
                if (ni >= m.start - 3 && ni < m.start && sameScope(run, ni, m.start)) {
                    m.negated = true
                    break
                }
            }
            for (u in uncSpans) {
                val before = u.end - 1 >= m.start - 3 && u.end - 1 < m.start
                val after = u.start >= m.end && u.start <= m.end + 1
                if ((before || after) && sameScope(run, minOf(u.start, m.start), maxOf(u.start, m.start))) {
                    if (m.field != "msg") {
                        m.uncertain = true
                        break
                    }
                }
            }
        }
    }

    private fun sameScope(run: Run, a: Int, b: Int): Boolean {
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        if (run.clauses[lo] != run.clauses[hi]) return false
        return (lo until hi).none { run.tokens[it] in BOUNDARY_WORDS }
    }

    // ------------------------------------------------------- token matching

    /** 0 for an exact or inflected match, 1 for a typo, null for no match. */
    private fun tokenCost(tok: String, ptok: String, fuzzy: Boolean): Int? {
        if (tok == ptok) return 0
        if (tok.startsWith(ptok)) {
            val rest = tok.substring(ptok.length)
            if ((ptok.length >= 4 && rest in SUFFIXES) || (ptok.length == 3 && rest == "s")) return 0
        }
        if (fuzzy && ptok.length >= 6 && tok.length >= 6 && tok.take(2) == ptok.take(2) && !ptok[0].isDigit() &&
            tok !in known && withinOneEdit(tok, ptok)
        ) return 1
        return null
    }

    /** Matches a phrase at token [i]. One filler word may sit between two phrase words. Returns (end, cost). */
    private fun matchAt(run: Run, i: Int, ptoks: List<String>, fuzzy: Boolean): Pair<Int, Int>? {
        var j = i
        var cost = 0
        for ((k, pt) in ptoks.withIndex()) {
            if (j >= run.tokens.size || run.clauses[j] != run.clauses[i]) return null
            var c = tokenCost(run.tokens[j], pt, fuzzy)
            if (c == null && k > 0 && run.tokens[j] in FILLERS && j + 1 < run.tokens.size && run.clauses[j + 1] == run.clauses[i]) {
                j++
                c = tokenCost(run.tokens[j], pt, fuzzy)
            }
            if (c == null) return null
            cost += c
            j++
        }
        return j to cost
    }

    private fun matchesPlain(run: Run, i: Int, ptoks: List<String>) = matchAt(run, i, ptoks, false)

    // ----------------------------------------------------------- numbers

    /** A number at token [i]: digits or a number word (one or more tokens). Returns value and token count. */
    private fun numberAt(run: Run, i: Int): Pair<Int, Int>? {
        if (i !in run.tokens.indices) return null
        val t = run.tokens[i]
        if (t.isNotEmpty() && t.all { it in '0'..'9' }) return t.toIntOrNull()?.let { it to 1 }
        for ((words, value) in numberWords) {
            if (i + words.size <= run.tokens.size && words.indices.all { run.tokens[i + it] == words[it] }) return value to words.size
        }
        return null
    }

    /** The number whose last token is [i] (one or two tokens long), or null. */
    private fun numberEndingAt(run: Run, i: Int): Int? {
        if (i < 0) return null
        for (n in listOf(2, 1)) {
            val start = i - n + 1
            if (start < 0) continue
            val got = numberAt(run, start)
            if (got != null && got.second == n) return got.first
        }
        return null
    }

    private class Qty(val value: Int, val helper: Boolean, val person: Boolean, val clause: Int, val more: Boolean)

    /** Every "number (more) noun" with a number of 2 or more and a helper or person noun, in text order. */
    private fun quantities(run: Run): List<Qty> {
        val out = ArrayList<Qty>()
        for (i in run.tokens.indices) {
            val (value, n) = numberAt(run, i) ?: continue
            if (value < 2) continue
            var j = i + n
            var more = false
            if (j < run.tokens.size && run.tokens[j] in MORE_WORDS) { more = true; j++ }
            if (j >= run.tokens.size || run.clauses[j] != run.clauses[i]) continue
            val helper = helperPhrases.any { matchesPlain(run, j, it) != null }
            val person = personPhrases.any { matchesPlain(run, j, it) != null }
            if (helper || person) out.add(Qty(value, helper, person, run.clauses[i], more))
        }
        return out
    }

    /** True when the clause asks for something: a request cue or a need word. */
    private fun clauseHasRequest(run: Run, clause: Int) = run.matches.any {
        !it.negated && run.clauses[it.start] == clause && ((it.field == "msg" && it.code == "request") || it.field == "needs")
    }

    // ----------------------------------------------------------- builders

    private fun flags(matches: List<Match>, table: Map<String, Int>): List<String> =
        matches.map { it.code }.filter { it in table }.toSet().sortedBy { table.getValue(it) }

    /** Returns the body, or null and a reason. */
    private fun buildFound(run: Run): Pair<Map<String, Any?>?, String?> {
        val body = LinkedHashMap<String, Any?>()
        var conflict = false
        // age: a specific group beats "adult" ("elderly man")
        val ages = run.effective("age").map { it.code }.toCollection(LinkedHashSet())
        if (ages.size > 1) ages.remove("adult")
        if (ages.size > 1) conflict = true else ages.firstOrNull()?.let { body["age"] = it }
        val cons = run.effective("consciousness").map { it.code }.toCollection(LinkedHashSet())
        if (cons == setOf("conscious", "confused")) { cons.clear(); cons.add("confused") }
        if (cons.size > 1) conflict = true else cons.firstOrNull()?.let { body["consciousness"] = it }
        val breath = run.effective("breathing").map { it.code }.toSet()
        if (breath.size > 1) conflict = true else breath.firstOrNull()?.let { body["breathing"] = it }
        val mob = run.effective("mobility").map { it.code }.toCollection(LinkedHashSet())
        if ("trapped" in mob) mob.remove("cannot_walk")
        if (mob.size > 1) conflict = true else mob.firstOrNull()?.let { body["mobility"] = it }
        val injuries = flags(run.effective("injuries"), Codes.INJURIES).toMutableList()
        val needs = flags(run.effective("needs"), Codes.NEEDS).toMutableList()
        // a vague "injured" adds nothing when a specific injury is named
        if ("other" in injuries && injuries.size > 1) injuries.remove("other")
        if ("other" in needs && needs.size > 1) needs.remove("other")
        if (injuries.isNotEmpty()) body["injuries"] = injuries
        if (needs.isNotEmpty()) body["needs"] = needs

        var count = 0
        var responders = 0
        for (q in quantities(run)) {
            val isResponders = (q.helper && !q.person) || (q.helper && q.person && (q.more || clauseHasRequest(run, q.clause)))
            if (isResponders && responders == 0) responders = q.value
            else if (q.person && !isResponders && count == 0) count = q.value
        }
        if (count >= 2) body["count"] = minOf(count, Codes.MAX_COUNT)
        if (responders >= 2) body["responders"] = minOf(responders, Codes.MAX_COUNT)
        // "one ... and one": two persons in different states, one message cannot say it safely.
        if (count >= 2 && TWO_PERSONS.containsMatchIn(fold(run.text))) conflict = true
        return if (conflict) null to "conflicting_states" else body to null
    }

    /** "Found a backpack" is not a person found: an object word next to the found cue. */
    private fun objectFound(run: Run): Boolean {
        for (cue in run.cue("found")) {
            for (forward in listOf(true, false)) {
                var idx = if (forward) cue.end else cue.start - 1
                val step = if (forward) 1 else -1
                while (idx in run.tokens.indices && run.tokens[idx] in DETERMINERS) idx += step
                if (idx !in run.tokens.indices) continue
                if (run.tokens[idx] in OBJECTS) return true
                if (run.matches.any { it.start <= idx && idx < it.end && it.field in setOf("needs", "hazard.kind", "request.resource") }) return true
            }
        }
        return false
    }

    /** Returns the body, or null and a reason ("no_supported_type", "ambiguous", "conflicting_states"). */
    private fun buildSector(run: Run): Pair<Map<String, Any?>?, String?> {
        val numbers = LinkedHashSet<Int>()
        for (cue in run.cue("sector")) {
            var i = cue.end
            while (i < run.tokens.size && run.tokens[i] in SECTOR_PREFIX) i++
            numberAt(run, i)?.let { numbers.add(it.first) }
        }
        if (numbers.isEmpty()) return null to "no_supported_type"
        if (numbers.size > 1) return null to "ambiguous"
        var status = run.effective("sector.status").map { it.code }.toCollection(LinkedHashSet())
        var coverage = 0
        var fromPercent = false
        for ((i, tok) in run.tokens.withIndex()) {
            if (tok in PERCENT_WORDS && i > 0) {
                val n = numberEndingAt(run, i - 1)
                if (n != null && n in 1..100) { coverage = n; fromPercent = true }
            }
        }
        if (coverage == 0) {
            for ((words, value) in coverageFixed) {
                for (i in run.tokens.indices) if (matchesPlain(run, i, words) != null) coverage = value
            }
        }
        if ("partial" in status) status = linkedSetOf("partial")
        if (status.isEmpty() && coverage != 0 && fromPercent) status = linkedSetOf(if (coverage < 100) "partial" else "cleared")
        if (status.size != 1) return null to (if (status.isEmpty()) "no_supported_type" else "conflicting_states")
        var st = status.first()
        if (coverage != 0 && coverage < 100 && st == "cleared") st = "partial"
        val body = LinkedHashMap<String, Any?>()
        body["sector"] = numbers.first()
        body["status"] = st
        if (coverage != 0 && st == "partial") body["coverage"] = coverage
        return body to null
    }

    private fun buildRequest(run: Run): Map<String, Any?>? {
        val res = run.effective("request.resource")
        val first = res.firstOrNull() ?: return null
        val body = LinkedHashMap<String, Any?>()
        body["resource"] = first.code
        var count = numberEndingAt(run, first.start - 1)
        if (count == null && first.start - 2 >= 0 && run.tokens[first.start - 1] in MORE_WORDS) count = numberEndingAt(run, first.start - 2)
        if (count != null && count >= 2) body["count"] = minOf(count, Codes.MAX_COUNT)
        run.effective("request.urgency").firstOrNull()?.let { body["urgency"] = it.code }
        return body
    }

    private fun buildHazard(run: Run): Map<String, Any?>? {
        var kinds = run.effective("hazard.kind")
        kinds = kinds.filter { it.code != "other" }.ifEmpty { kinds }
        if (kinds.isEmpty()) return null
        val body = LinkedHashMap<String, Any?>()
        body["kind"] = kinds[0].code
        val sev = run.effective("hazard.severity").map { it.code }.toSet()
        if (sev.isNotEmpty()) body["severity"] = sev.maxBy { Codes.SEVERITY[it] ?: 0 }
        for ((i, tok) in run.tokens.withIndex()) {
            val km = tok in UNIT_KM
            if (!km && tok !in UNIT_M) continue
            if (i == 0 || !run.tokens[i - 1][0].isDigit()) continue
            val value = run.tokens[i - 1].replace(',', '.').toDouble()
            val metres = Math.round(value * (if (km) 1000 else 1)).toInt()
            if (metres in 1..Codes.MAX_RADIUS_M) {
                body["radius"] = metres
                break
            }
        }
        return body
    }

    private fun buildMayday(run: Run): Map<String, Any?> {
        val body = LinkedHashMap<String, Any?>()
        val reasons = run.effective("mayday.reason").filter { it.code != "other" }
        if (reasons.isNotEmpty()) body["reason"] = reasons[0].code
        else if (run.effective("injuries").isNotEmpty()) body["reason"] = "injured"
        val q = quantities(run).firstOrNull { it.value >= 1 }
        if (q != null) {
            body["persons"] = minOf(q.value, Codes.MAX_COUNT)
        } else {
            for ((i, tok) in run.tokens.withIndex()) {
                if (tok in setOf("of", "von") && i + 1 < run.tokens.size && run.tokens[i + 1] in setOf("us", "uns")) {
                    val n = numberEndingAt(run, i - 1)
                    if (n != null && n >= 1) body["persons"] = minOf(n, Codes.MAX_COUNT)
                }
            }
        }
        return body
    }

    private fun priority(run: Run, type: String, body: Map<String, Any?>): String? {
        if (type == "mayday") return null
        val urgencySpans = if (type == "request") run.matches.filter { it.field == "request.urgency" }.map { it.start to it.end }.toSet() else emptySet()
        var level = run.effective("priority").filter { (it.start to it.end) !in urgencySpans }.maxOfOrNull { Codes.PRIORITY.getValue(it.code) } ?: 0
        if (type == "found" && body["breathing"] == "none") level = maxOf(level, Codes.PRIORITY.getValue("critical"))
        if (level == 0) return null
        val name = Codes.PRIORITY.entries.first { it.value == level }.key
        return if (name == Codes.DEFAULT_PRIORITY[type]) null else name
    }

    // ----------------------------------------------------------- tokenising

    companion object {
        /** Spoken coordinates, with optional degree signs and N/E letters. Same expression as the Python engine. */
        private val COORD = Regex(
            """(?<![\d.,])(-?\d{1,2}[.,]\d{3,8})\s*°?\s*[nN]?\s*[,;/]?\s+(-?\d{1,3}[.,]\d{3,8})\s*°?\s*[eE]?(?![\d])""" +
                """|(?<![\d.,])(-?\d{1,2}[.,]\d{3,8})\s*°?\s*[nN]?\s*[,;/]\s*(-?\d{1,3}[.,]\d{3,8})\s*°?\s*[eE]?(?![\d])""",
        )
        private val TOKEN = Regex("""\d+(?:[.,]\d+)?|[a-z]+(?:'[a-z]+)?|%""")
        private val CLAUSE_BREAK = Regex("""[.,;:!?\n()]""")

        private val SUFFIXES = setOf("s", "e", "n", "en", "er", "es", "em")
        /** One of these may sit inside a phrase ("kann selbst laufen"). */
        private val FILLERS = setOf("selbst", "auch", "noch", "wieder", "sehr", "ganz", "very", "still", "just", "really", "quite")
        private val MORE_WORDS = setOf("more", "weitere", "weiteren", "weiterer", "mehr", "extra", "additional", "zusaetzliche")
        private val DETERMINERS = setOf("a", "an", "the", "some", "ein", "eine", "einen", "einem", "der", "die", "das", "den", "dem")
        private val EXTRA_NEGATION = setOf("nobody", "noone", "niemand", "nothing", "nichts")
        private val BOUNDARY_WORDS = setOf("aber", "but", "sondern", "however", "jedoch", "doch")
        private val OBJECTS = setOf(
            "backpack", "rucksack", "jacket", "jacke", "phone", "handy", "smartphone", "hat", "muetze", "cap", "glove", "gloves",
            "handschuh", "handschuhe", "boot", "boots", "stiefel", "shoe", "shoes", "schuh", "schuhe", "tracks", "spur", "spuren",
            "fussspuren", "footprints", "footprint", "clothing", "kleidung", "equipment", "ausruestung", "wallet", "bag",
            "tasche", "car", "wagen", "item", "items", "gegenstand", "sachen",
        )
        private val UNIT_M = setOf("m", "meter", "metern", "metre", "metres", "meters")
        private val UNIT_KM = setOf("km", "kilometer", "kilometern", "kilometre", "kilometres", "kilometers")
        private val PERCENT_WORDS = setOf("%", "percent", "prozent")
        private val SECTOR_PREFIX = setOf("nr", "no", "nummer", "number")
        private val IGNORED_FIELDS = setOf("acc", "ack.status")
        private val STATE_GROUPS = listOf("consciousness", "breathing", "mobility", "injuries")

        /** A negated value with an opposite meaning flips; every other negated value is dropped. */
        private val FLIP = mapOf(
            ("consciousness" to "conscious") to "unconscious", ("consciousness" to "unconscious") to "conscious",
            ("breathing" to "normal") to "none", ("mobility" to "walks") to "cannot_walk",
        )
        private val TWO_PERSONS = Regex(
            """\bone\b.{0,60}\b(?:and one|the other|and the other|another)\b|""" +
                """\b(?:eine|einer|einen|ein)\b.{0,60}\b(?:die|der|den) ander\w*|\bund (?:eine|einer)\b""",
        )

        /** Lowercase text without umlauts: ä to ae, ö to oe, ü to ue, ß to ss. */
        private fun fold(s: String): String = s.lowercase()
            .replace('’', '\'').replace('`', '\'').replace('´', '\'')
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")

        /** Tokens and the clause number of each token. A clause starts after . , ; : ! ? ( ) or a line break. */
        private fun tokenize(text: String): Pair<List<String>, List<Int>> {
            val folded = fold(text)
            val tokens = ArrayList<String>()
            val clauses = ArrayList<Int>()
            var clause = 0
            var lastEnd = 0
            for (m in TOKEN.findAll(folded)) {
                if (CLAUSE_BREAK.containsMatchIn(folded.substring(lastEnd, m.range.first))) clause++
                tokens.add(m.value)
                clauses.add(clause)
                lastEnd = m.range.last + 1
            }
            return tokens to clauses
        }

        /** True when [a] and [b] differ by one insertion, deletion, substitution or swap. */
        private fun withinOneEdit(a: String, b: String): Boolean {
            if (a == b) return true
            val la = a.length
            val lb = b.length
            if (Math.abs(la - lb) > 1) return false
            if (la == lb) {
                val diff = (0 until la).filter { a[it] != b[it] }
                if (diff.size == 1) return true
                return diff.size == 2 && diff[1] == diff[0] + 1 && a[diff[0]] == b[diff[1]] && a[diff[1]] == b[diff[0]]
            }
            val (long, short) = if (la > lb) a to b else b to a
            for (i in 0..short.length) {
                if (long.substring(0, i) == short.substring(0, i) && long.substring(i + 1) == short.substring(i)) return true
            }
            return false
        }
    }
}

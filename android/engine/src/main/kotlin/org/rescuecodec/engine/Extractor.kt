// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.engine

import java.math.BigDecimal
import org.rescuecodec.codec.Codes

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
 * @property reasons short machine-readable notes ("type=found", "two_states", ...).
 * @property evidence which words set which field.
 */
data class Extraction(
    val message: Map<String, Any?>,
    val unsupported: Boolean,
    val reasons: List<String> = emptyList(),
    val evidence: List<Evidence> = emptyList(),
)

/**
 * Rule-based extractor: a spoken or typed German or English report becomes one RescueCodec message.
 *
 * It matches the words of [Vocab] (both languages at once, because volunteers mix them), handles
 * negation ("nicht unterkühlt"), uncertainty ("Atmung unklar"), number words ("zwo"), spoken
 * coordinates and typos. It follows spec section 11: only what the sentence says, one message per
 * report, and no guessing when a sentence holds facts one message cannot carry (two persons in
 * different states, an object found instead of a person).
 *
 * The extractor is pure and thread-safe. A person must always check the fields before sending.
 */
class Extractor(private val vocab: Vocab = Vocab.default) {

    // ------------------------------------------------------------ data types

    private class Tok(val raw: String, val stem: String, val clause: Int)

    private class Syn(val stems: List<String>, val group: String, val code: String, val raw: String)

    private class Hit(val group: String, val code: String, val start: Int, val end: Int, val fuzzy: Boolean) {
        val len get() = end - start
        var negated = false
        var uncertain = false
    }

    private class Sentence(val toks: List<Tok>, val hits: List<Hit>, val raw: String) {
        fun of(group: String) = hits.filter { it.group == group }
        fun of(group: String, code: String) = hits.filter { it.group == group && it.code == code }
        fun has(group: String, code: String? = null) = hits.any { it.group == group && (code == null || it.code == code) && !it.negated }
        fun words(h: Hit) = toks.subList(h.start, h.end).joinToString(" ") { it.raw }
    }

    // ----------------------------------------------------------- vocabulary

    private val index = HashMap<String, MutableList<Syn>>()
    private val fuzzyList = ArrayList<Syn>()
    private val knownStems = HashSet<String>()
    private val numberWords = HashMap<String, Int>()
    private val radiusWords: Set<String>

    init {
        fun add(group: String, code: String, phrase: String) {
            val toks = tokenize(phrase)
            if (toks.isEmpty()) return
            val syn = Syn(toks.map { it.stem }, group, code, toks.joinToString("") { it.raw })
            index.getOrPut(syn.stems[0]) { ArrayList() }.add(syn)
            knownStems.addAll(syn.stems)
            // Typo matching only for content words, never for negation, uncertainty or counts.
            if (toks.size == 1 && syn.raw.length >= 7 && group !in setOf("neg", "unc", "helper", "person")) fuzzyList.add(syn)
        }
        for ((type, words) in vocab.messages) for (w in words) add("msg", type, w)
        for ((group, codes) in vocab.fields) for ((code, words) in codes) for (w in words) add(group, code, w)
        for (w in vocab.negation) add("neg", "", w)
        for (w in vocab.uncertainty) add("unc", "", w)
        for (w in vocab.helpers) add("helper", "", w)
        for (w in vocab.persons) add("person", "", w)
        for ((k, v) in vocab.numbers) numberWords[fold(k)] = v
        for ((code, words) in EXTRAS) for (w in words) {
            val (group, c) = code.split("=")
            add(group, c, w)
        }
        radiusWords = vocab.radius.flatMap { tokenize(it).map { t -> t.stem } }.toSet() - RADIUS_STOP
    }

    // ------------------------------------------------------------ entry point

    /**
     * Turns [text] into a message. [lang] ("de" or "en") is a hint only: both languages are always
     * matched, so a German sentence with an English word still works.
     */
    @Suppress("UNUSED_PARAMETER")
    fun extract(text: String, lang: String? = null): Extraction {
        val original = text.trim()
        if (original.isEmpty()) return Extraction(emptyMap(), true, listOf("empty"))
        val evidence = ArrayList<Evidence>()
        var normalized = fold(original.lowercase())

        // Spoken coordinates: "50.69212, 7.21345" or "50.70001 7.19876".
        var pos: Map<String, Any?>? = null
        val m = COORDS.find(normalized)
        if (m != null) {
            val lat = BigDecimal(m.groupValues[1])
            val lon = BigDecimal(m.groupValues[2])
            if (lat.abs() <= BigDecimal(90) && lon.abs() <= BigDecimal(180)) {
                pos = linkedMapOf("lat" to lat.toDouble(), "lon" to lon.toDouble(), "acc" to "estimated")
                normalized = normalized.replaceRange(m.range, " ")
                evidence.add(Evidence("pos", "${m.groupValues[1]}, ${m.groupValues[2]}", m.value))
            }
        }

        val s = analyse(normalized, original)
        val reasons = ArrayList<String>()

        val liveKind = live(s, "hazard.kind").isNotEmpty()
        val body: Pair<String, Map<String, Any?>>? = when {
            isMayday(s) -> "mayday" to mayday(s, evidence)
            sectorNumber(s) != null -> "sector" to sector(s, evidence)
            isFound(s) -> "found" to found(s, evidence, reasons)
            s.has("msg", "hazard") && liveKind -> "hazard" to hazard(s, evidence)
            s.has("msg", "request") && firstHit(s, "request.resource") != null -> "request" to request(s, evidence)
            liveKind -> "hazard" to hazard(s, evidence)
            s.has("msg", "hazard") -> "hazard" to hazard(s, evidence)
            else -> null
        }

        if (body == null || reasons.any { it in BLOCKERS }) {
            if (body == null && reasons.isEmpty()) reasons.add("no_type")
            return fallback(original, reasons, evidence)
        }
        val (type, content) = body
        reasons.add("type=$type")
        val out = LinkedHashMap<String, Any?>()
        priority(s, type, evidence)?.let { out["priority"] = it }
        if (pos != null) out["pos"] = pos
        out[type] = content
        return Extraction(out, false, reasons, evidence)
    }

    private fun fallback(original: String, reasons: List<String>, evidence: List<Evidence>): Extraction {
        var t = original
        while (t.toByteArray(Charsets.UTF_8).size > 180) t = t.dropLast(1)
        return Extraction(linkedMapOf("text" to linkedMapOf("text" to t.trim())), true, reasons, evidence)
    }

    // ------------------------------------------------------------- analysis

    private fun analyse(normalized: String, raw: String): Sentence {
        val toks = tokenize(normalized)
        val pool = ArrayList<Hit>()
        for (i in toks.indices) {
            index[toks[i].stem]?.forEach { syn ->
                val n = syn.stems.size
                if (i + n <= toks.size && (0 until n).all { toks[i + it].stem == syn.stems[it] && toks[i + it].clause == toks[i].clause }) {
                    pool.add(Hit(syn.group, syn.code, i, i + n, false))
                }
            }
            val r = toks[i].raw
            if (r.length >= 7 && toks[i].stem !in knownStems) {
                for (syn in fuzzyList) {
                    if (Math.abs(syn.raw.length - r.length) <= 1 && syn.raw != r && toks[i].stem != syn.stems[0] &&
                        withinOneEdit(syn.raw, r)
                    ) {
                        pool.add(Hit(syn.group, syn.code, i, i + 1, true))
                    }
                }
            }
        }
        // Longest match wins, so "nicht ansprechbar" beats "nicht" and "ansprechbar", and
        // "nichts gefunden" beats "gefunden". Equal spans stay together (one word, two meanings).
        val sorted = pool.sortedWith(compareBy<Hit>({ -it.len }, { it.fuzzy }, { it.start }))
        val won = ArrayList<Hit>()
        for (h in sorted) {
            val clash = won.any { w -> w.start < h.end && h.start < w.end && !(w.start == h.start && w.end == h.end) }
            if (!clash) won.add(h)
        }
        val hits = won.sortedBy { it.start }
        val s = Sentence(toks, hits, raw)
        for (h in hits) {
            if (h.group == "neg" || h.group == "unc") continue
            h.negated = hits.any { n ->
                n.group == "neg" && n.clause(toks) == h.clause(toks) && h.start - n.end in 0..2 &&
                    hits.none { o -> o !== h && o.group !in MODIFIER_GROUPS && o.start >= n.end && o.start < h.start }
            }
            h.uncertain = hits.any { u ->
                u.group == "unc" && u.clause(toks) == h.clause(toks) && (h.start - u.end in 0..2 || u.start - h.end in 0..2)
            }
        }
        return s
    }

    private fun Hit.clause(toks: List<Tok>) = toks[start].clause

    // ------------------------------------------------------------ type rules

    private fun live(s: Sentence, group: String) = s.of(group).filter { !it.negated && !it.uncertain }

    private fun firstHit(s: Sentence, group: String) = live(s, group).firstOrNull()

    private fun isMayday(s: Sentence) = s.has("msg", "mayday") || live(s, "mayday.reason").isNotEmpty()

    /** Sector word followed by a number, plus a status or a percentage. Returns the number. */
    private fun sectorNumber(s: Sentence): Int? {
        val words = s.of("msg", "sector")
        for (w in words) {
            var i = w.end
            if (i < s.toks.size && s.toks[i].raw in NUMBER_PREFIX) i++
            val n = digits(s, i) ?: continue
            if (n !in 1..65535) continue
            if (live(s, "sector.status").isNotEmpty() || percent(s) != null) return n
        }
        return null
    }

    /** A found cue, or a described person with at least two facts ("Kind eingeklemmt, ansprechbar"). */
    private fun isFound(s: Sentence): Boolean {
        if (s.has("msg", "found")) return true
        val facts = listOf("age", "consciousness", "breathing", "mobility", "injuries").count { live(s, it).isNotEmpty() }
        return facts >= 2
    }

    // ----------------------------------------------------------- field rules

    private fun found(s: Sentence, ev: MutableList<Evidence>, reasons: MutableList<String>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val single = LinkedHashMap<String, String>()
        var conflict = false

        fun pick(group: String, order: List<String>, flips: Map<String, String> = emptyMap()) {
            val codes = LinkedHashMap<String, Hit>()
            for (h in s.of(group)) {
                if (h.uncertain) continue
                if (h.negated) {
                    val flipped = flips[h.code] ?: continue
                    codes.putIfAbsent(flipped, h)
                } else {
                    codes.putIfAbsent(h.code, h)
                }
            }
            if (codes.isEmpty()) return
            val best = order.firstOrNull { it in codes } ?: codes.keys.first()
            single[group] = best
            ev.add(Evidence("found.$group", best, s.words(codes.getValue(best))))
            if (group == "consciousness" && "unconscious" in codes && ("conscious" in codes)) conflict = true
            if (group == "breathing" && "none" in codes && "normal" in codes) conflict = true
        }
        pick("age", listOf("senior", "teen", "child", "adult"))
        pick("consciousness", listOf("unconscious", "confused", "conscious"), mapOf("conscious" to "unconscious", "unconscious" to "conscious"))
        pick("breathing", listOf("none", "difficult", "normal"), mapOf("normal" to "none", "none" to "normal"))
        pick("mobility", listOf("trapped", "cannot_walk", "walks"), mapOf("walks" to "cannot_walk", "cannot_walk" to "walks"))

        val injuries = flagList(s, "injuries", "found.injuries", ev, Codes.INJURIES)
        val needs = flagList(s, "needs", "found.needs", ev, Codes.NEEDS)

        var count = 0
        var responders = 0
        for (p in s.hits.filter { (it.group == "person" || it.group == "helper") && !it.negated }.distinctBy { it.start }) {
            val n = numberBefore(s, p.start) ?: continue
            val isHelper = s.hits.any { it.group == "helper" && it.start == p.start && it.end == p.end }
            val isPerson = s.hits.any { it.group == "person" && it.start == p.start && it.end == p.end }
            val asHelper = when {
                isHelper && !isPerson -> true
                isPerson && !isHelper -> false
                else -> hasRequestBefore(s, p.start) || fillerBetween(s, p.start)
            }
            if (asHelper && responders == 0) {
                responders = n
                ev.add(Evidence("found.responders", n.toString(), s.words(p)))
            } else if (!asHelper && count == 0 && n >= 2) {
                count = n
                ev.add(Evidence("found.count", n.toString(), s.words(p)))
            }
        }

        // Two persons in different states, or two "one ... and one": one message cannot say it safely.
        val ones = s.toks.count { it.raw == "one" }
        val others = s.toks.any { it.raw in OTHERS }
        val objectFound = s.of("msg", "found").firstOrNull { !it.negated }?.let { c ->
            (c.end until minOf(c.end + 4, s.toks.size)).any { s.toks[it].raw in OBJECTS } && live(s, "age").isEmpty()
        } == true
        if (conflict || ones >= 2 || others) reasons.add("two_states")
        if (objectFound) reasons.add("object_found")

        if (count > 0) out["count"] = count
        for (k in listOf("age", "consciousness", "breathing", "mobility")) single[k]?.let { out[k] = it }
        if (injuries.isNotEmpty()) out["injuries"] = injuries
        if (needs.isNotEmpty()) out["needs"] = needs
        if (responders > 0) out["responders"] = responders
        return out
    }

    private fun flagList(s: Sentence, group: String, field: String, ev: MutableList<Evidence>, table: Map<String, Int>): List<String> {
        val codes = LinkedHashSet<String>()
        for (h in s.of(group)) {
            if (h.negated || h.uncertain) continue
            if (codes.add(h.code)) ev.add(Evidence(field, h.code, s.words(h)))
        }
        return codes.sortedBy { table.getValue(it) }
    }

    private fun sector(s: Sentence, ev: MutableList<Evidence>): Map<String, Any?> {
        val number = sectorNumber(s)!!
        val statuses = live(s, "sector.status").map { it.code }.toSet()
        val pct = percent(s)
        val fixed = fixedCoverage(s)
        val status = when {
            "blocked" in statuses -> "blocked"
            "partial" in statuses -> "partial"
            pct != null && pct in 1..99 -> "partial" // "40 Prozent abgesucht" is not "cleared"
            "cleared" in statuses -> "cleared"
            else -> "partial"
        }
        val out = LinkedHashMap<String, Any?>()
        out["sector"] = number
        out["status"] = status
        ev.add(Evidence("sector.status", status, live(s, "sector.status").firstOrNull()?.let { s.words(it) } ?: "percent"))
        val coverage = pct ?: fixed
        if (coverage != null && coverage in 1..100 && status == "partial") out["coverage"] = coverage
        return out
    }

    private fun request(s: Sentence, ev: MutableList<Evidence>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val r = firstHit(s, "request.resource")!!
        out["resource"] = r.code
        ev.add(Evidence("request.resource", r.code, s.words(r)))
        val n = numberBefore(s, r.start)
        if (n != null && n >= 2) {
            out["count"] = n
            ev.add(Evidence("request.count", n.toString(), s.words(r)))
        }
        firstHit(s, "request.urgency")?.let {
            out["urgency"] = it.code
            ev.add(Evidence("request.urgency", it.code, s.words(it)))
        }
        return out
    }

    private fun hazard(s: Sentence, ev: MutableList<Evidence>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val kind = firstHit(s, "hazard.kind")
        out["kind"] = kind?.code ?: "other"
        if (kind != null) ev.add(Evidence("hazard.kind", kind.code, s.words(kind)))
        firstHit(s, "hazard.severity")?.let {
            out["severity"] = it.code
            ev.add(Evidence("hazard.severity", it.code, s.words(it)))
        }
        radius(s)?.let {
            out["radius"] = it
            ev.add(Evidence("hazard.radius", it.toString(), "radius"))
        }
        return out
    }

    private fun mayday(s: Sentence, ev: MutableList<Evidence>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        var reason = firstHit(s, "mayday.reason")?.code
        if (reason == null) {
            val fell = s.toks.any { it.raw in FELL } || live(s, "injuries").isNotEmpty()
            if (fell) reason = "injured"
        }
        if (reason != null) {
            out["reason"] = reason
            ev.add(Evidence("mayday.reason", reason, s.of("mayday.reason").firstOrNull()?.let { s.words(it) } ?: "fell or hurt"))
        }
        for (p in s.hits.filter { (it.group == "person" || it.group == "helper") && !it.negated }) {
            val n = numberBefore(s, p.start) ?: continue
            if (n in 1..255) {
                out["persons"] = n
                ev.add(Evidence("mayday.persons", n.toString(), s.words(p)))
                break
            }
        }
        return out
    }

    // -------------------------------------------------------------- priority

    private fun priority(s: Sentence, type: String, ev: MutableList<Evidence>): String? {
        val claimed = HashSet<Int>()
        // Words that already say something else (urgency of a request, severity of a hazard).
        if (type == "request") s.of("request.urgency").forEach { claimed.add(it.start) }
        if (type == "hazard") s.of("hazard.severity").forEach { claimed.add(it.start) }
        val cues = s.of("priority").filter { !it.negated && !it.uncertain && it.start !in claimed }.map { it.code }.toMutableList()
        if (type == "found" && s.of("breathing", "none").any { !it.negated }) cues.add("critical")
        if (type == "found" && NOW_SHOUT.containsMatchIn(s.raw)) cues.add("critical")
        val best = listOf("critical", "urgent", "normal", "routine").firstOrNull { it in cues } ?: return null
        if (best == DEFAULT_PRIORITY[type]) return null
        ev.add(Evidence("priority", best, "priority words"))
        return best
    }

    // --------------------------------------------------------------- numbers

    private fun digits(s: Sentence, i: Int): Int? {
        if (i !in s.toks.indices) return null
        val t = s.toks[i].raw
        if (t.all { it.isDigit() }) return t.toIntOrNull()
        return numberWords[t]
    }

    /** A whole number right before [start], allowing one filler word ("10 weitere Suchkräfte"). */
    private fun numberBefore(s: Sentence, start: Int): Int? {
        for (back in 1..2) {
            val i = start - back
            if (i < 0 || s.toks[i].clause != s.toks[start].clause) return null
            val tok = s.toks[i].raw
            val n = digits(s, i)
            if (n != null) {
                // "a couple" is two tokens: handled by the number words list ("couple" alone is not).
                return n
            }
            if (i >= 1 && "${s.toks[i - 1].raw} $tok" in numberWords) return numberWords.getValue("${s.toks[i - 1].raw} $tok")
            if (tok !in FILLERS) return null
        }
        return null
    }

    private fun hasRequestBefore(s: Sentence, start: Int) =
        s.of("msg", "request").any { it.end <= start && it.clause(s.toks) == s.toks[start].clause }

    private fun fillerBetween(s: Sentence, start: Int) =
        (maxOf(0, start - 2) until start).any { s.toks[it].raw in MORE_WORDS && s.toks[it].clause == s.toks[start].clause }

    private fun percent(s: Sentence): Int? {
        for (i in s.toks.indices) {
            val t = s.toks[i].raw
            if ((t == "%" || t == "percent" || t == "prozent") && i >= 1) digits(s, i - 1)?.let { if (it in 0..100) return it }
        }
        return null
    }

    private fun fixedCoverage(s: Sentence): Int? {
        val text = s.toks.joinToString(" ") { it.raw }
        return vocab.coverageFixed.entries.firstOrNull { (k, _) -> " ${fold(k)} " in " $text " }?.value
    }

    private fun radius(s: Sentence): Int? {
        for (i in s.toks.indices) {
            val t = s.toks[i].raw
            if (t.isEmpty() || !(t[0].isDigit())) continue
            val value = t.replace(',', '.').toDoubleOrNull() ?: continue
            val unit = s.toks.getOrNull(i + 1)?.raw ?: continue
            val factor = when (unit) {
                "m", "meter", "metern", "metre", "metres", "meters" -> 1.0
                "km", "kilometer", "kilometern", "kilometre", "kilometres", "kilometers" -> 1000.0
                else -> continue
            }
            val clause = s.toks[i].clause
            val near = (maxOf(0, i - 3)..minOf(s.toks.size - 1, i + 4)).any {
                s.toks[it].clause == clause && s.toks[it].stem in radiusWords
            }
            if (near) return Math.round(value * factor).toInt().takeIf { it in 1..100_000 }
        }
        return null
    }

    // ----------------------------------------------------------- tokenising

    companion object {
        private val COORDS = Regex("""(-?\d{1,3}\.\d{3,})\s*[,;/]?\s*(-?\d{1,3}\.\d{3,})""")
        private val TOKEN = Regex("""\d+(?:[.,]\d+)?|[a-z]+(?:'[a-z]+)?|%|[.,;!?:]""")
        private val CLAUSE_WORDS = setOf("aber", "but", "however", "jedoch", "sondern", "although", "obwohl")
        private val ABBREVIATIONS = setOf("ca", "evtl", "bzw", "ggf", "etc", "nr", "no", "approx", "z", "b")
        private val MODIFIER_GROUPS = setOf("neg", "unc", "helper", "person")
        private val RADIUS_STOP = setOf("m", "metres", "meters", "im", "von", "um", "metre")
        private val NUMBER_PREFIX = setOf("nr", "no", "number", "nummer", "n")
        private val FILLERS = setOf(
            "more", "weitere", "weiteren", "extra", "additional", "zusaetzliche", "noch", "another", "further", "other",
        )
        private val MORE_WORDS = setOf("more", "weitere", "weiteren", "extra", "additional", "zusaetzliche", "further")
        private val OBJECTS = setOf(
            "backpack", "rucksack", "jacket", "jacke", "bag", "tasche", "phone", "handy", "shoe", "shoes", "schuh",
            "schuhe", "hat", "muetze", "clothes", "kleidung", "tracks", "spuren", "footprints", "trace", "traces",
        )
        private val OTHERS = setOf("andere", "anderen", "anderer", "another", "other")
        private val FELL = setOf("fell", "fall", "fallen", "gestuerzt", "sturz", "abgestuerzt", "stuerzte", "ausgerutscht")
        private val NOW_SHOUT = Regex("""\bNOW\b""")
        private val BLOCKERS = setOf("two_states", "object_found")

        /** Default priority per message type (spec section 2). */
        private val DEFAULT_PRIORITY = mapOf(
            "mayday" to "critical", "found" to "urgent", "hazard" to "urgent", "request" to "normal",
            "sector" to "routine",
        )

        /**
         * Small additions to the vocabulary, as `group=code` to words. They cover phrasing seen in
         * practice that the shared word list does not have yet. Everything else comes from sar.yaml.
         */
        private val EXTRAS = mapOf(
            "msg=found" to listOf("entdeckt", "gesichtet", "spotted", "lokalisiert"),
            "mobility=walks" to listOf("kann selbst laufen", "kann selbst gehen", "selbst laufen"),
            "request.urgency=soon" to listOf("half an hour", "halbe stunde", "halben stunde"),
            "person=" to listOf("of us", "von uns", "mitglieder", "members", "kollegen", "retter", "rescuers"),
        )

        /** Lowercase text without umlauts: ä to ae, ö to oe, ü to ue, ß to ss. */
        private fun fold(s: String): String = s.lowercase()
            .replace('’', '\'').replace('`', '\'').replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")

        /** Light stemming so "Tragen" matches "Trage" and "injuries" matches "injury". */
        private fun stem(w: String): String {
            if (w.isEmpty() || !w[0].isLetter()) return w
            if (w.endsWith("ies") && w.length > 5) return w.dropLast(3) + "y"
            for (suffix in listOf("en", "er", "es", "e", "n", "s")) {
                if (suffix == "n" && w.endsWith("en")) continue // "teen" is not "tee"
                if (w.endsWith(suffix) && w.length - suffix.length >= 3) return w.dropLast(suffix.length)
            }
            return w
        }

        /** Splits text into word tokens. Punctuation and "aber"/"but" start a new clause. */
        private fun tokenize(text: String): List<Tok> {
            val folded = fold(text)
            val toks = ArrayList<Tok>()
            var clause = 0
            var prev = ""
            for (m in TOKEN.findAll(folded)) {
                val t = m.value
                if (t.length == 1 && t[0] in ".,;!?:") {
                    val isAbbr = t == "." && prev in ABBREVIATIONS
                    if (!isAbbr) clause++
                } else {
                    if (t in CLAUSE_WORDS) clause++
                    toks.add(Tok(t, stem(t), clause))
                    if (t in CLAUSE_WORDS) clause++
                }
                prev = t
            }
            return toks
        }

        /** True when [a] and [b] differ by one insertion, deletion, substitution or swap. */
        private fun withinOneEdit(a: String, b: String): Boolean {
            if (a == b) return false
            val la = a.length
            val lb = b.length
            if (Math.abs(la - lb) > 1) return false
            var i = 0
            while (i < la && i < lb && a[i] == b[i]) i++
            if (la == lb) {
                if (a.substring(i + 1) == b.substring(i + 1)) return true
                return i + 1 < la && a[i] == b[i + 1] && a[i + 1] == b[i] && a.substring(i + 2) == b.substring(i + 2)
            }
            return if (la > lb) a.substring(i + 1) == b.substring(i) else b.substring(i + 1) == a.substring(i)
        }
    }
}

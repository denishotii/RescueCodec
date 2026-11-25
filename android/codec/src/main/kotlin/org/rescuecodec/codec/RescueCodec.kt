// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.codec

import com.google.protobuf.InvalidProtocolBufferException
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.rescuecodec.v2.proto.Ack
import org.rescuecodec.v2.proto.Envelope
import org.rescuecodec.v2.proto.Found
import org.rescuecodec.v2.proto.Hazard
import org.rescuecodec.v2.proto.Mayday
import org.rescuecodec.v2.proto.Position
import org.rescuecodec.v2.proto.Request
import org.rescuecodec.v2.proto.Sector
import org.rescuecodec.v2.proto.Text
import org.rescuecodec.v2.proto.Track

/**
 * RescueCodec v2 codec: canonical JSON message to wire bytes and back, readable text, v1 reading.
 *
 * A message is a plain `Map<String, Any?>` in the canonical JSON shape of spec section 4, for
 * example:
 * ```
 * mapOf("id" to 7, "pos" to mapOf("lat" to 50.69212, "lon" to 7.21345),
 *       "found" to mapOf("age" to "child", "needs" to listOf("stretcher")))
 * ```
 * Use [Json] to turn it into text and back. Every function throws [CodecException] for input that
 * breaks the spec rules, and never for valid input.
 *
 * Passes RescueCodec 2.0.0 conformance Level 1 (codec) and Level 2 (readable text).
 */
object RescueCodec {
    /** Meshtastic port used for RescueCodec packets (PRIVATE_APP). */
    const val PORT_NUM: Int = 256

    /** Version of this library. */
    const val LIBRARY_VERSION: String = "0.1.0"

    /** Spec version this library implements. */
    const val SPEC_VERSION: String = Codes.SPEC_VERSION

    // ------------------------------------------------------------ public API

    /** Canonical JSON message to v2 wire bytes (at most 200 bytes). */
    fun encode(message: Map<String, Any?>): ByteArray {
        val env = toProto(message)
        val data = env.toByteArray()
        if (data.size > Codes.MAX_ENCODE_BYTES) {
            throw err("too_large", "${data.size} bytes, the limit is ${Codes.MAX_ENCODE_BYTES}")
        }
        return data
    }

    /** Like [encode], but takes a JSON text. */
    fun encodeJson(json: String): ByteArray = encode(
        try {
            Json.parseObject(json)
        } catch (e: IllegalArgumentException) {
            throw err("invalid_value", "not a JSON object: ${e.message}")
        },
    )

    /** v2 wire bytes to canonical JSON message. Does not read v1 packets, see [decodeAny]. */
    fun decode(bytes: ByteArray): Map<String, Any?> = fromProto(parse(bytes))

    /** Like [decode], but returns JSON text. */
    fun decodeToJson(bytes: ByteArray): String = Json.stringify(decode(bytes))

    /**
     * Decodes a packet of any version. First byte 0x01 is a v1 packet (result has `"v": 1`), other
     * reserved first bytes are rejected with `unsupported_version`, anything else is v2 (`"v": 2`).
     */
    fun decodeAny(bytes: ByteArray): Map<String, Any?> {
        if (bytes.isEmpty()) throw err("empty", "empty payload")
        if (detectVersion(bytes) == 1) return V1.decode(bytes)
        val out = LinkedHashMap<String, Any?>()
        out["v"] = 2
        out.putAll(decode(bytes))
        return out
    }

    /**
     * Rebuilds one readable line from a decoded message using the word files of spec section 10.
     * [locale] is a language tag such as "de", "en" or "de-DE". Languages: en, de (reviewed) and
     * uk, tr, ar (machine drafts). An unknown language falls back to English.
     */
    fun reconstruct(decoded: Map<String, Any?>, locale: String = "en"): String =
        Renderer.render(decoded, locale)

    /** Same as above for a [Locale]. */
    fun reconstruct(decoded: Map<String, Any?>, locale: Locale): String =
        Renderer.render(decoded, locale.language)

    /** 1 for a v1 packet (first byte 0x01), 2 for v2, 0 for reserved versions or empty input. */
    fun detectVersion(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        val first = bytes[0].toInt() and 0xFF
        return when {
            first == 0x01 -> 1
            first <= 0x07 -> 0
            else -> 2
        }
    }

    /** Message type name: one of [Codes.BODY_TYPES] or `unknown`. Throws `no_body` if none. */
    fun messageType(message: Map<String, Any?>): String {
        for (kind in Codes.BODY_TYPES + "unknown") if (message.containsKey(kind)) return kind
        throw err("no_body", "the message has no body")
    }

    /** Priority name on the wire, or the default for the message type. */
    fun effectivePriority(message: Map<String, Any?>): String =
        (message["priority"] as? String) ?: Codes.DEFAULT_PRIORITY.getValue(messageType(message))

    /**
     * Turns a message time "HH:MM" (UTC) into an instant: the most recent matching minute that is at
     * most 10 minutes after [now] (the receiver's clock).
     */
    fun resolveTime(hhmm: String, now: Instant): Instant {
        val m = TIME_RE.matchEntire(hhmm) ?: throw err("invalid_value", "must be \"HH:MM\" (UTC)", "time")
        val nowUtc = now.atOffset(ZoneOffset.UTC)
        var candidate = nowUtc.withHour(m.groupValues[1].toInt()).withMinute(m.groupValues[2].toInt())
            .withSecond(0).withNano(0).toInstant()
        if (candidate.isAfter(now.plus(Duration.ofMinutes(10)))) candidate = candidate.minus(Duration.ofDays(1))
        return candidate
    }

    // ------------------------------------------------------------- helpers

    internal fun err(code: String, message: String, field: String? = null) = CodecException(code, message, field)

    private val UNKNOWN_RE = Regex("^unknown_(\\d{1,5})$")
    private val BIT_RE = Regex("^bit_(\\d{1,2})$")
    private val TIME_RE = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")
    private val NODE_RE = Regex("^!([0-9a-f]{8})$")

    private fun u(i: Int): Long = i.toLong() and 0xFFFFFFFFL

    private fun intValue(value: Any?, field: String, lo: Long, hi: Long): Int {
        val n = when (value) {
            is Int, is Long, is Short, is Byte -> (value as Number).toLong()
            else -> throw err("invalid_value", "must be an integer", field)
        }
        if (n < lo || n > hi) throw err("invalid_value", "must be $lo..$hi", field)
        return n.toInt()
    }

    private fun enumToWire(value: Any?, table: Map<String, Int>, field: String, max: Long = 0x7FFFFFFFL): Int {
        if (value is String) {
            table[value]?.let { return it }
            val m = UNKNOWN_RE.matchEntire(value)
            if (m != null) {
                val n = m.groupValues[1].toInt()
                if (n in 1..max && n !in table.values) return n
            }
        }
        throw err("invalid_value", "unknown value ${Json.stringify(value)}", field)
    }

    private fun enumToJson(number: Int, table: Map<String, Int>): String =
        table.entries.firstOrNull { it.value == number }?.key ?: "unknown_$number"

    private fun flagsToWire(values: Any?, table: Map<String, Int>, field: String): Int {
        if (values !is List<*>) throw err("invalid_value", "must be a list of names", field)
        var mask = 0
        for (value in values) {
            val bit = (value as? String)?.let { name ->
                table[name] ?: BIT_RE.matchEntire(name)?.groupValues?.get(1)?.toInt()
                    ?.takeIf { it < 32 && it !in table.values }
            } ?: throw err("invalid_value", "unknown flag ${Json.stringify(value)}", field)
            mask = mask or (1 shl bit)
        }
        return mask
    }

    private fun flagsToJson(mask: Int, table: Map<String, Int>): List<String> {
        val byBit = table.entries.associate { it.value to it.key }
        return (0 until 32).filter { mask and (1 shl it) != 0 }.map { byBit[it] ?: "bit_$it" }
    }

    /** Degrees to integer degrees x 1e5, rounding half away from zero on the decimal text. */
    internal fun degToE5(value: Any?, field: String): Int {
        val d = when (value) {
            is Int, is Long, is Short, is Byte -> BigDecimal((value as Number).toLong())
            is Double -> if (value.isFinite()) BigDecimal(value.toString()) else null
            is Float -> if (value.isFinite()) BigDecimal(value.toString()) else null
            else -> throw err("invalid_value", "must be a number", field)
        } ?: throw err("invalid_value", "must be a finite number", field)
        val r = d.multiply(BigDecimal(100000)).setScale(0, RoundingMode.HALF_UP)
        if (r.abs() > BigDecimal(Int.MAX_VALUE)) throw err("invalid_value", "out of range", field)
        return r.toInt()
    }

    private fun e5ToDeg(v: Long): Double = v / 100000.0

    private fun nodeToJson(num: Int): String = "!%08x".format(u(num))

    private fun nodeFromJson(value: Any?): Int {
        if (value is String) {
            NODE_RE.matchEntire(value)?.let { return it.groupValues[1].toLong(16).toInt() }
        }
        throw err("invalid_value", "must look like \"!a1b2c3d4\"", "ack.node")
    }

    @Suppress("UNCHECKED_CAST")
    private fun checkKeys(obj: Any?, allowed: Set<String>, field: String): Map<String, Any?> {
        if (obj !is Map<*, *>) throw err("invalid_value", "must be an object", field)
        for (key in obj.keys) {
            if (key !in allowed) {
                throw err("unknown_key", "unknown key '$key'", if (field.isNotEmpty()) "$field.$key" else key.toString())
            }
        }
        return obj as Map<String, Any?>
    }

    private fun checkLatLon(lat: Long, lon: Long, field: String) {
        if (lat !in -9_000_000..9_000_000) throw err("invalid_value", "latitude must be -90..90", "$field.lat")
        if (lon !in -18_000_000..18_000_000) throw err("invalid_value", "longitude must be -180..180", "$field.lon")
    }

    // ------------------------------------------------------ JSON to Envelope

    private fun toProto(msg: Map<String, Any?>): Envelope {
        checkKeys(msg, setOf("v", "id", "priority", "pos", "time") + Codes.BODY_TYPES, "")
        if (msg.containsKey("v") && !(msg["v"].let { (it is Int || it is Long) && (it as Number).toLong() == 2L })) {
            throw err("invalid_value", "only v=2 can be encoded", "v")
        }
        val bodies = Codes.BODY_TYPES.filter { msg.containsKey(it) }
        if (bodies.isEmpty()) throw err("no_body", "a message needs exactly one body (found, track, ...)")
        if (bodies.size > 1) throw err("multiple_bodies", "only one body allowed, got $bodies")
        val kind = bodies[0]
        val env = Envelope.newBuilder()
        if (msg.containsKey("id")) env.id = intValue(msg["id"], "id", 0, Codes.MAX_ID.toLong())
        if (msg.containsKey("priority")) env.priorityValue = enumToWire(msg["priority"], Codes.PRIORITY, "priority")
        if (msg.containsKey("pos")) {
            val pos = checkKeys(msg["pos"], setOf("lat", "lon", "acc"), "pos")
            if (!pos.containsKey("lat") || !pos.containsKey("lon")) throw err("missing_field", "pos needs lat and lon", "pos")
            val lat = degToE5(pos["lat"], "pos.lat")
            val lon = degToE5(pos["lon"], "pos.lon")
            checkLatLon(lat.toLong(), lon.toLong(), "pos")
            val p = Position.newBuilder().setLat(lat).setLon(lon)
            if (pos.containsKey("acc")) p.accValue = enumToWire(pos["acc"], Codes.ACCURACY, "pos.acc")
            env.pos = p.build()
        }
        if (msg.containsKey("time")) {
            val t = msg["time"]
            val m = (t as? String)?.let { TIME_RE.matchEntire(it) } ?: throw err("invalid_value", "must be \"HH:MM\" (UTC)", "time")
            env.time = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
        }
        val body = msg[kind]
        when (kind) {
            "found" -> env.found = buildFound(body)
            "track" -> env.track = buildTrack(body)
            "sector" -> env.sector = buildSector(body)
            "request" -> env.request = buildRequest(body)
            "hazard" -> env.hazard = buildHazard(body)
            "mayday" -> env.mayday = buildMayday(body)
            "ack" -> env.ack = buildAck(body)
            "text" -> env.text = buildText(body)
        }
        val built = env.build()
        validate(built)
        return built
    }

    private fun buildFound(body: Any?): Found {
        val b = checkKeys(
            body,
            setOf("count", "age", "consciousness", "breathing", "mobility", "injuries", "needs", "responders"),
            "found",
        )
        val f = Found.newBuilder()
        var state = 0
        for (s in Codes.STATE_LAYOUT) {
            if (b.containsKey(s.name)) {
                state = state or (enumToWire(b[s.name], s.table, "found.${s.name}", (1L shl s.width) - 1) shl s.shift)
            }
        }
        f.state = state
        if (b.containsKey("injuries")) f.injuries = flagsToWire(b["injuries"], Codes.INJURIES, "found.injuries")
        if (b.containsKey("needs")) f.needs = flagsToWire(b["needs"], Codes.NEEDS, "found.needs")
        if (b.containsKey("count")) f.count = intValue(b["count"], "found.count", 0, Codes.MAX_COUNT.toLong())
        if (b.containsKey("responders")) f.responders = intValue(b["responders"], "found.responders", 0, Codes.MAX_COUNT.toLong())
        return f.build()
    }

    private fun buildTrack(body: Any?): Track {
        val b = checkKeys(body, setOf("points"), "track")
        val points = b["points"]
        if (points !is List<*> || points.isEmpty()) throw err("missing_field", "a track needs 1 or more points", "track.points")
        if (points.size > Codes.MAX_TRACK_POINTS) {
            throw err("invalid_value", "at most ${Codes.MAX_TRACK_POINTS} points per packet", "track.points")
        }
        val t = Track.newBuilder()
        var withTime: Boolean? = null
        var prevLat = 0
        var prevLon = 0
        var prevS = 0
        for ((i, raw) in points.withIndex()) {
            val field = "track.points[$i]"
            val p = checkKeys(raw, setOf("lat", "lon", "s"), field)
            if (!p.containsKey("lat") || !p.containsKey("lon")) throw err("missing_field", "a point needs lat and lon", field)
            val lat = degToE5(p["lat"], "$field.lat")
            val lon = degToE5(p["lon"], "$field.lon")
            checkLatLon(lat.toLong(), lon.toLong(), field)
            t.addLat(lat - prevLat)
            t.addLon(lon - prevLon)
            prevLat = lat
            prevLon = lon
            val hasS = p.containsKey("s")
            if (withTime == null) {
                withTime = hasS
            } else if (hasS != withTime) {
                throw err("invalid_value", "give s on every point or on none", "track.points")
            }
            if (hasS) {
                val s = intValue(p["s"], "$field.s", 0, 86_400)
                if (s < prevS) throw err("invalid_value", "s must not go down", "$field.s")
                t.addDt(s - prevS)
                prevS = s
            }
        }
        return t.build()
    }

    private fun buildSector(body: Any?): Sector {
        val b = checkKeys(body, setOf("sector", "status", "coverage"), "sector")
        val s = Sector.newBuilder()
        if (b.containsKey("sector")) s.sector = intValue(b["sector"], "sector.sector", 0, Codes.MAX_SECTOR.toLong())
        if (b.containsKey("status")) s.statusValue = enumToWire(b["status"], Codes.SECTOR_STATUS, "sector.status")
        if (b.containsKey("coverage")) s.coverage = intValue(b["coverage"], "sector.coverage", 0, 100)
        return s.build()
    }

    private fun buildRequest(body: Any?): Request {
        val b = checkKeys(body, setOf("resource", "count", "urgency"), "request")
        val r = Request.newBuilder()
        if (b.containsKey("resource")) r.resourceValue = enumToWire(b["resource"], Codes.RESOURCE, "request.resource")
        if (b.containsKey("count")) r.count = intValue(b["count"], "request.count", 0, Codes.MAX_COUNT.toLong())
        if (b.containsKey("urgency")) r.urgencyValue = enumToWire(b["urgency"], Codes.URGENCY, "request.urgency")
        return r.build()
    }

    private fun buildHazard(body: Any?): Hazard {
        val b = checkKeys(body, setOf("kind", "severity", "radius"), "hazard")
        val h = Hazard.newBuilder()
        if (b.containsKey("kind")) h.kindValue = enumToWire(b["kind"], Codes.HAZARD, "hazard.kind")
        if (b.containsKey("severity")) h.severityValue = enumToWire(b["severity"], Codes.SEVERITY, "hazard.severity")
        if (b.containsKey("radius")) h.radius = intValue(b["radius"], "hazard.radius", 0, Codes.MAX_RADIUS_M.toLong())
        return h.build()
    }

    private fun buildMayday(body: Any?): Mayday {
        val b = checkKeys(body, setOf("reason", "persons"), "mayday")
        val m = Mayday.newBuilder()
        if (b.containsKey("reason")) m.reasonValue = enumToWire(b["reason"], Codes.MAYDAY_REASON, "mayday.reason")
        if (b.containsKey("persons")) m.persons = intValue(b["persons"], "mayday.persons", 0, Codes.MAX_COUNT.toLong())
        return m.build()
    }

    private fun buildAck(body: Any?): Ack {
        val b = checkKeys(body, setOf("ref", "node", "status", "eta", "team"), "ack")
        val a = Ack.newBuilder()
        if (b.containsKey("ref")) a.ref = intValue(b["ref"], "ack.ref", 0, Codes.MAX_ID.toLong())
        if (b.containsKey("node")) a.node = nodeFromJson(b["node"])
        if (b.containsKey("status")) a.statusValue = enumToWire(b["status"], Codes.ACK_STATUS, "ack.status")
        if (b.containsKey("eta")) a.eta = intValue(b["eta"], "ack.eta", 0, Codes.MAX_ETA_MIN.toLong())
        if (b.containsKey("team")) a.team = checkedString(b["team"], "ack.team")
        return a.build()
    }

    private fun buildText(body: Any?): Text {
        val b = checkKeys(body, setOf("text"), "text")
        val t = Text.newBuilder()
        if (b.containsKey("text")) t.text = checkedString(b["text"], "text.text")
        return t.build()
    }

    private fun checkedString(value: Any?, field: String): String {
        if (value !is String) throw err("invalid_value", "must be a string", field)
        try {
            Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw err("invalid_value", "must be valid Unicode", field)
        }
        return value
    }

    // ------------------------------------------------- validation (both ways)

    private fun bodyType(env: Envelope): String = when (env.bodyCase) {
        Envelope.BodyCase.FOUND -> "found"
        Envelope.BodyCase.TRACK -> "track"
        Envelope.BodyCase.SECTOR -> "sector"
        Envelope.BodyCase.REQUEST -> "request"
        Envelope.BodyCase.HAZARD -> "hazard"
        Envelope.BodyCase.MAYDAY -> "mayday"
        Envelope.BodyCase.ACK -> "ack"
        Envelope.BodyCase.TEXT -> "text"
        else -> "unknown"
    }

    /** Value rules shared by encoder and decoder (spec section 5). */
    private fun validate(env: Envelope) {
        val kind = bodyType(env)
        if (u(env.id) > Codes.MAX_ID) throw err("invalid_value", "id must be 0..${Codes.MAX_ID}", "id")
        if (kind in Codes.ID_REQUIRED && env.id == 0) {
            throw err("missing_field", "$kind needs an id (1..${Codes.MAX_ID})", "id")
        }
        if (env.hasPos()) checkLatLon(env.pos.lat.toLong(), env.pos.lon.toLong(), "pos")
        if (env.hasTime() && u(env.time) > 1439) {
            throw err("invalid_value", "time must be 0..1439 (minute of the UTC day)", "time")
        }
        when (kind) {
            "found" -> {
                val f = env.found
                if (u(f.count) > Codes.MAX_COUNT) throw err("invalid_value", "must be 0..${Codes.MAX_COUNT}", "found.count")
                if (u(f.responders) > Codes.MAX_COUNT) throw err("invalid_value", "must be 0..${Codes.MAX_COUNT}", "found.responders")
            }
            "track" -> validateTrack(env)
            "sector" -> {
                val s = env.sector
                val n = u(s.sector)
                if (n == 0L || n > Codes.MAX_SECTOR) {
                    throw err(if (n == 0L) "missing_field" else "invalid_value", "sector must be 1..${Codes.MAX_SECTOR}", "sector.sector")
                }
                if (s.statusValue == 0) throw err("missing_field", "sector needs a status", "sector.status")
                if (u(s.coverage) > 100) throw err("invalid_value", "coverage must be 0..100", "sector.coverage")
            }
            "request" -> {
                val r = env.request
                if (r.resourceValue == 0) throw err("missing_field", "request needs a resource", "request.resource")
                if (u(r.count) > Codes.MAX_COUNT) throw err("invalid_value", "must be 0..${Codes.MAX_COUNT}", "request.count")
            }
            "hazard" -> {
                val h = env.hazard
                if (h.kindValue == 0) throw err("missing_field", "hazard needs a kind", "hazard.kind")
                if (u(h.radius) > Codes.MAX_RADIUS_M) throw err("invalid_value", "radius must be 0..${Codes.MAX_RADIUS_M}", "hazard.radius")
            }
            "mayday" -> if (u(env.mayday.persons) > Codes.MAX_COUNT) {
                throw err("invalid_value", "must be 0..${Codes.MAX_COUNT}", "mayday.persons")
            }
            "ack" -> {
                val a = env.ack
                val ref = u(a.ref)
                if (ref == 0L || ref > Codes.MAX_ID) {
                    throw err(if (ref == 0L) "missing_field" else "invalid_value", "ref must be 1..${Codes.MAX_ID}", "ack.ref")
                }
                if (a.node == 0) throw err("missing_field", "ack needs the node of the answered message", "ack.node")
                if (a.statusValue == 0) throw err("missing_field", "ack needs a status", "ack.status")
                if (u(a.eta) > Codes.MAX_ETA_MIN) throw err("invalid_value", "eta must be 0..${Codes.MAX_ETA_MIN} minutes", "ack.eta")
                if (a.team.toByteArray(Charsets.UTF_8).size > Codes.MAX_TEAM_BYTES) {
                    throw err("invalid_value", "team label is at most ${Codes.MAX_TEAM_BYTES} bytes", "ack.team")
                }
            }
            "text" -> {
                val size = env.text.text.toByteArray(Charsets.UTF_8).size
                if (size == 0) throw err("missing_field", "text must not be empty", "text.text")
                if (size > Codes.MAX_TEXT_BYTES) throw err("invalid_value", "text is at most ${Codes.MAX_TEXT_BYTES} bytes", "text.text")
            }
        }
    }

    private fun validateTrack(env: Envelope) {
        val t = env.track
        val n = t.latCount
        if (n == 0) throw err("missing_field", "a track needs 1 or more points", "track.points")
        if (n > Codes.MAX_TRACK_POINTS) {
            throw err("invalid_value", "at most ${Codes.MAX_TRACK_POINTS} points per packet", "track.points")
        }
        if (t.lonCount != n || (t.dtCount != 0 && t.dtCount != n)) {
            throw err("invalid_value", "lat, lon and dt must have the same length (dt may be empty)", "track.points")
        }
        if (t.dtCount != 0 && !env.hasTime()) throw err("missing_field", "a track with times needs Envelope.time", "time")
        var lat = 0L
        var lon = 0L
        for (i in 0 until n) {
            lat += t.getLat(i)
            lon += t.getLon(i)
            checkLatLon(lat, lon, "track.points[$i]")
        }
    }

    // ------------------------------------------------------ Envelope to JSON

    private fun fromProto(env: Envelope): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        if (env.id != 0) out["id"] = env.id
        if (env.priorityValue != 0) out["priority"] = enumToJson(env.priorityValue, Codes.PRIORITY)
        if (env.hasPos()) {
            val pos = LinkedHashMap<String, Any?>()
            pos["lat"] = e5ToDeg(env.pos.lat.toLong())
            pos["lon"] = e5ToDeg(env.pos.lon.toLong())
            if (env.pos.accValue != 0) pos["acc"] = enumToJson(env.pos.accValue, Codes.ACCURACY)
            out["pos"] = pos
        }
        if (env.hasTime()) out["time"] = "%02d:%02d".format(env.time / 60, env.time % 60)
        when (bodyType(env)) {
            "found" -> {
                val f = env.found
                val b = LinkedHashMap<String, Any?>()
                if (f.count != 0) b["count"] = f.count
                for (s in Codes.STATE_LAYOUT) {
                    val v = (f.state ushr s.shift) and ((1 shl s.width) - 1)
                    if (v != 0) b[s.name] = enumToJson(v, s.table)
                }
                if (f.injuries != 0) b["injuries"] = flagsToJson(f.injuries, Codes.INJURIES)
                if (f.needs != 0) b["needs"] = flagsToJson(f.needs, Codes.NEEDS)
                if (f.responders != 0) b["responders"] = f.responders
                out["found"] = b
            }
            "track" -> {
                val t = env.track
                val points = ArrayList<Any?>()
                var lat = 0L
                var lon = 0L
                var s = 0L
                for (i in 0 until t.latCount) {
                    lat += t.getLat(i)
                    lon += t.getLon(i)
                    val p = LinkedHashMap<String, Any?>()
                    p["lat"] = e5ToDeg(lat)
                    p["lon"] = e5ToDeg(lon)
                    if (t.dtCount != 0) {
                        s += u(t.getDt(i))
                        p["s"] = s
                    }
                    points.add(p)
                }
                out["track"] = linkedMapOf<String, Any?>("points" to points)
            }
            "sector" -> {
                val s = env.sector
                val b = LinkedHashMap<String, Any?>()
                b["sector"] = s.sector
                b["status"] = enumToJson(s.statusValue, Codes.SECTOR_STATUS)
                if (s.coverage != 0) b["coverage"] = s.coverage
                out["sector"] = b
            }
            "request" -> {
                val r = env.request
                val b = LinkedHashMap<String, Any?>()
                b["resource"] = enumToJson(r.resourceValue, Codes.RESOURCE)
                if (r.count != 0) b["count"] = r.count
                if (r.urgencyValue != 0) b["urgency"] = enumToJson(r.urgencyValue, Codes.URGENCY)
                out["request"] = b
            }
            "hazard" -> {
                val h = env.hazard
                val b = LinkedHashMap<String, Any?>()
                b["kind"] = enumToJson(h.kindValue, Codes.HAZARD)
                if (h.severityValue != 0) b["severity"] = enumToJson(h.severityValue, Codes.SEVERITY)
                if (h.radius != 0) b["radius"] = h.radius
                out["hazard"] = b
            }
            "mayday" -> {
                val m = env.mayday
                val b = LinkedHashMap<String, Any?>()
                if (m.reasonValue != 0) b["reason"] = enumToJson(m.reasonValue, Codes.MAYDAY_REASON)
                if (m.persons != 0) b["persons"] = m.persons
                out["mayday"] = b
            }
            "ack" -> {
                val a = env.ack
                val b = LinkedHashMap<String, Any?>()
                b["ref"] = a.ref
                b["node"] = nodeToJson(a.node)
                b["status"] = enumToJson(a.statusValue, Codes.ACK_STATUS)
                if (a.eta != 0) b["eta"] = a.eta
                if (a.team.isNotEmpty()) b["team"] = a.team
                out["ack"] = b
            }
            "text" -> out["text"] = linkedMapOf<String, Any?>("text" to env.text.text)
            else -> out["unknown"] = LinkedHashMap<String, Any?>()
        }
        return out
    }

    // ---------------------------------------------------------------- parsing

    private fun parse(bytes: ByteArray): Envelope {
        if (bytes.isEmpty()) throw err("empty", "empty payload")
        if (bytes.size > Codes.MAX_DECODE_BYTES) {
            throw err("too_large", "${bytes.size} bytes, the limit is ${Codes.MAX_DECODE_BYTES}")
        }
        if (detectVersion(bytes) != 2) {
            throw err("unsupported_version", "first byte 0x%02x is not a v2 packet".format(bytes[0].toInt() and 0xFF))
        }
        val env = try {
            Envelope.parseFrom(bytes)
        } catch (e: InvalidProtocolBufferException) {
            throw err("malformed", "not a valid protobuf Envelope: ${e.message}")
        }
        if (env.bodyCase == Envelope.BodyCase.BODY_NOT_SET && !hasUnknownTopLevelField(bytes)) {
            throw err("no_body", "the Envelope has no body")
        }
        validate(env)
        return env
    }

    /** True when the packet has a top-level field this version does not define (a newer body type). */
    private fun hasUnknownTopLevelField(bytes: ByteArray): Boolean = try {
        scanForUnknownTopLevelField(bytes)
    } catch (e: RuntimeException) {
        false // a packet that parsed but cannot be walked again: not "a newer body", the caller reports no_body
    }

    private fun scanForUnknownTopLevelField(bytes: ByteArray): Boolean {
        val buf = ByteBuffer.wrap(bytes)
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                val b = buf.get().toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        val known = mapOf(1 to 0, 2 to 0, 3 to 2, 4 to 0, 6 to 2, 7 to 2, 8 to 2, 9 to 2, 10 to 2, 11 to 2, 12 to 2, 13 to 2)
        while (buf.hasRemaining()) {
            val tag = varint()
            val number = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            if (known[number] != wire) return true
            when (wire) {
                0 -> varint()
                2 -> {
                    val len = varint()
                    if (len < 0 || len > buf.remaining()) return false
                    buf.position(buf.position() + len.toInt())
                }
            }
        }
        return false
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.codec

/**
 * Readable text from a decoded message (spec section 10). The algorithm is fixed by the spec; the
 * words come from the bundled `rescuecodec/i18n/<lang>.json` files.
 */
internal object Renderer {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Map<String, Any?>>()

    @Suppress("UNCHECKED_CAST")
    private fun load(lang: String): Map<String, Any?> = cache.getOrPut(lang) {
        val stream = Renderer::class.java.getResourceAsStream("/rescuecodec/i18n/$lang.json")
            ?: throw IllegalArgumentException("no language file for '$lang'")
        Json.parseObject(stream.use { String(it.readBytes(), Charsets.UTF_8) })
    }

    @Suppress("UNCHECKED_CAST")
    private fun section(d: Map<String, Any?>, name: String) = d[name] as Map<String, String>

    private fun format5(deg: Any?, field: String): String {
        val e5 = RescueCodec.degToE5(deg, field)
        val sign = if (e5 < 0) "-" else ""
        val a = Math.abs(e5.toLong())
        return "$sign${a / 100000}.${(a % 100000).toString().padStart(5, '0')}"
    }

    private fun fill(template: String, vararg values: Pair<String, Any?>): String {
        var t = template
        for ((k, v) in values) t = t.replace("{$k}", v.toString())
        return t
    }

    private fun int(v: Any?): Int = (v as? Number)?.toInt() ?: 0

    @Suppress("UNCHECKED_CAST", "CyclomaticComplexMethod")
    fun render(msg: Map<String, Any?>, locale: String): String {
        val lang = locale.lowercase().substringBefore('-').substringBefore('_')
        val data = try { load(lang) } catch (e: IllegalArgumentException) { load("en") }
        val head = section(data, "head")
        val part = section(data, "part")
        val value = section(data, "value")
        val punct = section(data, "punct")

        fun v(group: String, name: Any?): String {
            val n = name.toString()
            if (n.startsWith("unknown_") || n.startsWith("bit_")) {
                return fill(part.getValue("unknown_value"), "n" to n.substringAfter('_'))
            }
            return value["$group.$n"] ?: n
        }
        fun joinList(items: List<String>): String =
            if (items.size <= 1) items.joinToString("")
            else items.dropLast(1).joinToString(punct.getValue("list_sep")) + punct.getValue("list_last") + items.last()
        fun end(s: String) = if (s.lastOrNull()?.let { it == '.' || it == '!' || it == '?' } == true || s.endsWith(punct.getValue("end"))) s else s + punct.getValue("end")

        val kind = RescueCodec.messageType(msg)
        val body = (msg[kind] as? Map<String, Any?>) ?: emptyMap()
        val parts = ArrayList<String>()
        val headText: String

        when (kind) {
            "found" -> {
                val count = int(body["count"])
                headText = if (count >= 2) fill(head.getValue("found_many"), "n" to count) else head.getValue("found")
                for (name in listOf("age", "consciousness", "breathing", "mobility")) {
                    if (body.containsKey(name)) parts.add(v(name, body[name]))
                }
                (body["injuries"] as? List<*>)?.forEach { parts.add(v("injuries", it)) }
                val needs = body["needs"] as? List<*>
                if (!needs.isNullOrEmpty()) {
                    parts.add(fill(part.getValue("found.needs"), "list" to joinList(needs.map { v("needs", it) })))
                }
                if (int(body["responders"]) != 0) parts.add(fill(part.getValue("found.responders"), "n" to int(body["responders"])))
            }
            "track" -> {
                val points = (body["points"] as? List<Map<String, Any?>>) ?: emptyList()
                headText = head.getValue("track")
                parts.add(if (points.size == 1) part.getValue("track.points_1") else fill(part.getValue("track.points"), "n" to points.size))
                if (points.isNotEmpty() && points[0].containsKey("s")) {
                    val span = (points.last()["s"] as Number).toLong() - (points[0]["s"] as Number).toLong()
                    if (span > 0) parts.add(fill(part.getValue("track.minutes"), "n" to (span + 30) / 60))
                }
                if (points.isNotEmpty()) {
                    val last = points.last()
                    parts.add(fill(part.getValue("track.last"), "lat" to format5(last["lat"], "lat"), "lon" to format5(last["lon"], "lon")))
                }
            }
            "sector" -> {
                headText = fill(head.getValue("sector"), "sector" to int(body["sector"]))
                parts.add(v("sector", body["status"]))
                if (int(body["coverage"]) != 0) parts.add(fill(part.getValue("sector.coverage"), "n" to int(body["coverage"])))
            }
            "request" -> {
                headText = head.getValue("request")
                val resource = v("resource", body["resource"])
                val count = int(body["count"])
                parts.add(if (count >= 2) fill(part.getValue("request.count"), "n" to count, "resource" to resource) else resource)
                if (body.containsKey("urgency")) parts.add(v("urgency", body["urgency"]))
            }
            "hazard" -> {
                headText = head.getValue("hazard")
                parts.add(v("hazard", body["kind"]))
                if (body.containsKey("severity")) parts.add(v("severity", body["severity"]))
                if (int(body["radius"]) != 0) parts.add(fill(part.getValue("hazard.radius"), "n" to int(body["radius"])))
            }
            "mayday" -> {
                headText = head.getValue("mayday")
                if (body.containsKey("reason")) parts.add(v("mayday", body["reason"]))
                val persons = int(body["persons"])
                if (persons == 1) parts.add(part.getValue("mayday.persons_1"))
                else if (persons != 0) parts.add(fill(part.getValue("mayday.persons"), "n" to persons))
            }
            "ack" -> {
                val status = body["status"]?.toString() ?: ""
                val key = if (head.containsKey("ack.$status")) "ack.$status" else "ack.other"
                headText = fill(head.getValue(key), "ref" to int(body["ref"]))
                if (body["node"] != null) parts.add(fill(part.getValue("ack.node"), "node" to body["node"]))
                if (!(body["team"] as? String).isNullOrEmpty()) parts.add(fill(part.getValue("ack.team"), "team" to body["team"]))
                if (int(body["eta"]) != 0) parts.add(fill(part.getValue("ack.eta"), "n" to int(body["eta"])))
            }
            "text" -> {
                headText = head.getValue("text")
                parts.add(body["text"]?.toString() ?: "")
            }
            else -> headText = if (body.containsKey("words") && head.containsKey("unknown_dict")) {
                // A compact text with a word dictionary we do not have.
                val n = body["words"]
                fill(head.getValue("unknown_dict"), "n" to ((n as? Number)?.toLong() ?: n))
            } else if (body.containsKey("vocab") && head.containsKey("unknown_vocab")) {
                // A domain frame with a team vocabulary we do not have (spec section 3.8).
                val n = body["vocab"]
                fill(head.getValue("unknown_vocab"), "n" to ((n as? Number)?.toLong() ?: n))
            } else {
                head.getValue("unknown")
            }
        }

        var sentence = headText + if (parts.isNotEmpty()) punct.getValue("head_sep") + parts.joinToString(punct.getValue("part_sep")) else ""
        sentence = end(sentence)
        val priority = msg["priority"] as? String
        if (priority != null && priority != Codes.DEFAULT_PRIORITY.getValue(kind)) sentence = "[" + v("priority", priority) + "] " + sentence
        val pos = msg["pos"] as? Map<String, Any?>
        if (pos != null) {
            var where = fill(part.getValue("pos"), "lat" to format5(pos["lat"], "pos.lat"), "lon" to format5(pos["lon"], "pos.lon"))
            val acc = (pos["acc"] as? String) ?: "gps"
            if (acc != "gps") where += " (" + v("acc", acc) + ")"
            sentence += " " + where + punct.getValue("end")
        }
        if (msg.containsKey("time")) sentence += " " + fill(part.getValue("time"), "time" to msg["time"]) + punct.getValue("end")
        return sentence
    }
}

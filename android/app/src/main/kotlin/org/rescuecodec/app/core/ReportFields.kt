// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.core

import org.rescuecodec.codec.Codes

/** How a field is edited: pick one value, pick several, or step a number. */
enum class FieldKind { ONE, MANY, NUMBER }

/**
 * One editable field of a report, as a chip. [group] is the word group in spec/i18n for the labels.
 * [required] fields cannot be removed.
 */
data class FieldDef(
    val body: String,
    val key: String,
    val group: String,
    val kind: FieldKind,
    val options: List<String> = emptyList(),
    val min: Int = 1,
    val max: Int = 255,
    val required: Boolean = false,
)

/**
 * The chips of the Report screen and the edits behind them. Messages stay plain maps in the
 * canonical JSON shape (spec section 4), so what is edited here is what `:codec` encodes.
 * Every function returns a new map.
 */
object ReportFields {
    /** Message types a person can pick. Track and Ack are made by the app, not typed. */
    val TYPES = listOf("found", "request", "hazard", "mayday", "sector", "text")
    val PRIORITIES = listOf("routine", "normal", "urgent", "critical")

    private val ALL: Map<String, List<FieldDef>> = mapOf(
        "found" to listOf(
            FieldDef("found", "count", "", FieldKind.NUMBER, min = 2),
            FieldDef("found", "age", "age", FieldKind.ONE, Codes.AGE.keys.toList()),
            FieldDef("found", "consciousness", "consciousness", FieldKind.ONE, Codes.CONSCIOUSNESS.keys.toList()),
            FieldDef("found", "breathing", "breathing", FieldKind.ONE, Codes.BREATHING.keys.toList()),
            FieldDef("found", "mobility", "mobility", FieldKind.ONE, Codes.MOBILITY.keys.toList()),
            FieldDef("found", "injuries", "injuries", FieldKind.MANY, Codes.INJURIES.keys.toList()),
            FieldDef("found", "needs", "needs", FieldKind.MANY, Codes.NEEDS.keys.toList()),
            FieldDef("found", "responders", "", FieldKind.NUMBER),
        ),
        "request" to listOf(
            FieldDef("request", "resource", "resource", FieldKind.ONE, Codes.RESOURCE.keys.toList(), required = true),
            FieldDef("request", "count", "", FieldKind.NUMBER, min = 2),
            FieldDef("request", "urgency", "urgency", FieldKind.ONE, Codes.URGENCY.keys.toList()),
        ),
        "hazard" to listOf(
            FieldDef("hazard", "kind", "hazard", FieldKind.ONE, Codes.HAZARD.keys.toList(), required = true),
            FieldDef("hazard", "severity", "severity", FieldKind.ONE, Codes.SEVERITY.keys.toList()),
            FieldDef("hazard", "radius", "", FieldKind.NUMBER, min = 10, max = Codes.MAX_RADIUS_M),
        ),
        "mayday" to listOf(
            FieldDef("mayday", "reason", "mayday", FieldKind.ONE, Codes.MAYDAY_REASON.keys.toList()),
            FieldDef("mayday", "persons", "", FieldKind.NUMBER),
        ),
        "sector" to listOf(
            FieldDef("sector", "sector", "", FieldKind.NUMBER, max = Codes.MAX_SECTOR, required = true),
            FieldDef("sector", "status", "sector", FieldKind.ONE, Codes.SECTOR_STATUS.keys.toList(), required = true),
            FieldDef("sector", "coverage", "", FieldKind.NUMBER, max = 100),
        ),
        "text" to emptyList(),
    )

    fun defs(type: String): List<FieldDef> = ALL[type].orEmpty()

    fun def(type: String, key: String): FieldDef? = defs(type).firstOrNull { it.key == key }

    /** The body type of [message] (the one body key), or "text" when there is none. */
    fun typeOf(message: Map<String, Any?>): String =
        TYPES.firstOrNull { message[it] != null } ?: "text"

    @Suppress("UNCHECKED_CAST")
    fun body(message: Map<String, Any?>): Map<String, Any?> =
        (message[typeOf(message)] as? Map<String, Any?>) ?: emptyMap()

    /** The fields that are set, in the order of [defs]. */
    fun present(message: Map<String, Any?>): List<FieldDef> {
        val b = body(message)
        return defs(typeOf(message)).filter { b[it.key] != null && !(b[it.key] is List<*> && (b[it.key] as List<*>).isEmpty()) }
    }

    /** The fields that could still be added. */
    fun missing(message: Map<String, Any?>): List<FieldDef> {
        val have = present(message).map { it.key }.toSet()
        return defs(typeOf(message)).filter { it.key !in have }
    }

    fun withBody(message: Map<String, Any?>, type: String, body: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in message) if (k !in TYPES) out[k] = v
        out[type] = body
        return out
    }

    /** Sets a single value (ONE) or a number (NUMBER). */
    fun set(message: Map<String, Any?>, def: FieldDef, value: Any): Map<String, Any?> {
        val type = typeOf(message)
        val b = LinkedHashMap(body(message))
        b[def.key] = value
        return withBody(message, type, sortBody(type, b))
    }

    /** Adds or removes [code] in a MANY field. Codes stay in the order of the code table. */
    fun toggle(message: Map<String, Any?>, def: FieldDef, code: String): Map<String, Any?> {
        val type = typeOf(message)
        val b = LinkedHashMap(body(message))
        val now = (b[def.key] as? List<*>).orEmpty().map { it.toString() }.toMutableList()
        if (!now.remove(code)) now.add(code)
        if (now.isEmpty()) b.remove(def.key) else b[def.key] = def.options.filter { it in now }
        return withBody(message, type, sortBody(type, b))
    }

    /** Removes a field (not a required one). */
    fun remove(message: Map<String, Any?>, def: FieldDef): Map<String, Any?> {
        if (def.required) return message
        val type = typeOf(message)
        val b = LinkedHashMap(body(message))
        b.remove(def.key)
        return withBody(message, type, b)
    }

    /** Switches the message type. The old body is dropped; required fields get a first value. */
    fun setType(message: Map<String, Any?>, type: String, text: String = ""): Map<String, Any?> {
        if (type == typeOf(message)) return message
        val b = LinkedHashMap<String, Any?>()
        when (type) {
            "request" -> b["resource"] = "other"
            "hazard" -> b["kind"] = "other"
            "sector" -> { b["sector"] = 1; b["status"] = "partial" }
            "text" -> b["text"] = clampText(text)
        }
        return withBody(message, type, b)
    }

    /** Sets a priority, or clears it (null) so the type's default is used. */
    fun setPriority(message: Map<String, Any?>, priority: String?): Map<String, Any?> {
        val out = LinkedHashMap(message)
        if (priority == null || priority == Codes.DEFAULT_PRIORITY[typeOf(message)]) out.remove("priority") else out["priority"] = priority
        // keep "priority" first like the extractor does
        val sorted = LinkedHashMap<String, Any?>()
        out["priority"]?.let { sorted["priority"] = it }
        for ((k, v) in out) if (k != "priority") sorted[k] = v
        return sorted
    }

    fun priorityOf(message: Map<String, Any?>): String =
        (message["priority"] as? String) ?: Codes.DEFAULT_PRIORITY[typeOf(message)] ?: "normal"

    /** Plain text message with [text] cut to the limit of 180 bytes. */
    fun textMessage(text: String): Map<String, Any?> = mapOf("text" to mapOf("text" to clampText(text)))

    fun clampText(text: String): String {
        var t = text.trim()
        while (t.toByteArray(Charsets.UTF_8).size > Codes.MAX_TEXT_BYTES) t = t.dropLast(1)
        return t
    }

    private fun sortBody(type: String, b: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (d in defs(type)) b[d.key]?.let { out[d.key] = it }
        for ((k, v) in b) if (k !in out) out[k] = v
        return out
    }
}

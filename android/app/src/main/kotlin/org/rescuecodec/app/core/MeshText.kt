// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.core

import org.rescuecodec.app.data.MessageEntity

/**
 * Normal Meshtastic texts (port 1, `TEXT_MESSAGE_APP`), as the official app and radio screens send and show them
 * (spec section 2.1). They are stored as normal `text` rows, marked with [SOURCE] in [MessageEntity.source]. A plain
 * text shows no tag (users read "via Meshtastic" as "sent from the Meshtastic app"); a packed one shows [tagOf].
 */
object MeshText {
    /** [MessageEntity.source] of a plain Meshtastic text, sent or received. No schema change needed. */
    const val SOURCE = "meshtastic"

    /**
     * Gateways put this in front of a readable copy of a RescueCodec message (`--text-over-air`, `also_plain`).
     * We skip such texts: the message itself came on port 256 on the same channel.
     */
    const val COPY_PREFIX = "[RC] "

    /** Meshtastic's broadcast address as an unsigned node number. */
    const val BROADCAST = 0xFFFFFFFFL

    /** True for a row that is a plain Meshtastic text, not a RescueCodec message. */
    fun isPlain(row: MessageEntity): Boolean = row.source == SOURCE

    /**
     * True for a RescueCodec text that went packed (raw DEFLATE with the shared dictionary, spec section 3.5). The
     * stored JSON keeps the unpacked words and `"dict": 1`.
     */
    fun isPacked(row: MessageEntity): Boolean = textKey(row, "dict") != null

    /**
     * The word dictionary of a compact text (2-byte word tokens, spec section 3.6), or null for any other row. The
     * stored JSON keeps the decoded words and `"words": 1`.
     */
    fun compactDict(row: MessageEntity): Int? = (textKey(row, "words") as? Number)?.toInt()

    /** True for a compact frame (first byte 0x02, spec section 3.7): the word stream without an Envelope or id. */
    fun isCompactFrame(row: MessageEntity): Boolean =
        !isPlain(row) && row.bytes.isNotEmpty() && (row.bytes[0].toInt() and 0xFF) == org.rescuecodec.codec.RescueCodec.COMPACT_FRAME

    /**
     * The small tag of a text that went packed for RescueCodec: [TAG_COMPACT] (word tokens, in the Envelope or as a
     * compact frame) or [TAG_PACKED] (DEFLATE); null for a plain Meshtastic text and for reports (they get no tag).
     */
    fun tagOf(row: MessageEntity): String? = when {
        isPlain(row) || row.type != "text" -> null
        compactDict(row) != null -> TAG_COMPACT
        isPacked(row) -> TAG_PACKED
        else -> null
    }

    const val TAG_COMPACT = "compact"
    const val TAG_PACKED = "packed"

    private fun textKey(row: MessageEntity, key: String): Any? {
        if (isPlain(row) || row.type != "text") return null
        val json = row.json ?: return null
        return runCatching { (org.rescuecodec.codec.Json.parseObject(json)["text"] as? Map<*, *>)?.get(key) }.getOrNull()
    }

    /** True for a gateway's readable copy of a RescueCodec message. */
    fun isCopy(text: String): Boolean = text.startsWith(COPY_PREFIX)

    /** The canonical JSON shape of a plain text, so it shows like any text message. */
    fun message(text: String, priority: String? = null): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        if (priority != null) out["priority"] = priority
        out["text"] = mapOf("text" to text)
        return out
    }
}

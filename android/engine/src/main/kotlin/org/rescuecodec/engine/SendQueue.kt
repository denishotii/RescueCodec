// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.engine

import org.rescuecodec.codec.RescueCodec

/** A message waiting to be sent. [rank] 0 is sent first. */
class QueuedMessage internal constructor(
    val seq: Long,
    val message: Map<String, Any?>,
    val bytes: ByteArray,
    val rank: Int,
    val queuedAtMs: Long,
    /** Same key means "newer version of the same thing": it replaces the older one in the queue. */
    val key: String?,
)

/**
 * Priority send queue with an airtime budget.
 *
 * Order: Mayday, then Found, Request and Ack, then Hazard, then Sector, then Track, then Text. An
 * explicit `critical` priority on a message moves it to the top. Inside one rank the oldest goes
 * first. Strict order: if the best message does not fit the airtime budget yet, nothing else goes
 * before it (so a big Found cannot be overtaken by small Text forever).
 *
 * Messages that carry a version of the same thing replace each other: same `(type, id)`, or the
 * same sector number. Rank 3 and lower (Sector, Track, Text) expire after [staleAfterMs].
 *
 * Not thread-safe: call it from one thread, or lock outside.
 */
class SendQueue(
    val budget: AirtimeBudget = AirtimeBudget(),
    private val clock: () -> Long = System::currentTimeMillis,
    val staleAfterMs: Long = 15 * 60_000L,
    /** Minimum time between two sends, in ms. */
    val minGapMs: Long = 0,
) {
    private val items = ArrayList<QueuedMessage>()
    private var seq = 0L
    private var lastSendMs = Long.MIN_VALUE / 2

    /** Messages waiting. */
    val size: Int get() = items.size

    /** Adds [message]. [bytes] defaults to the encoded message. */
    fun enqueue(message: Map<String, Any?>, bytes: ByteArray = RescueCodec.encode(message)): QueuedMessage {
        val key = keyOf(message)
        if (key != null) items.removeAll { it.key == key }
        val q = QueuedMessage(seq++, message, bytes, rankOf(message), clock(), key)
        items.add(q)
        return q
    }

    /** What would be sent next if the budget allowed, or null. */
    fun peek(): QueuedMessage? {
        dropStale()
        return items.minWithOrNull(compareBy({ it.rank }, { it.seq }))
    }

    /** Milliseconds until the next message may go: null if empty, 0 if now, Long.MAX_VALUE if it never fits. */
    fun waitMs(): Long? {
        val next = peek() ?: return null
        val now = clock()
        val gap = max0(lastSendMs + minGapMs - now)
        return maxOf(gap, budget.waitMs(next.bytes.size, now, next.rank))
    }

    /**
     * Takes the next message if the airtime budget and the gap allow it now, and records its
     * airtime. Returns null otherwise.
     */
    fun poll(): QueuedMessage? {
        val next = peek() ?: return null
        val now = clock()
        if (now < lastSendMs + minGapMs) return null
        if (!budget.canSend(next.bytes.size, now, next.rank)) return null
        budget.record(next.bytes.size, now)
        lastSendMs = now
        items.remove(next)
        return next
    }

    /** Removes a message, for example when the person cancels it. Returns true if it was waiting. */
    fun cancel(seq: Long): Boolean = items.removeAll { it.seq == seq }

    /** The waiting messages in sending order. */
    fun snapshot(): List<QueuedMessage> = items.sortedWith(compareBy({ it.rank }, { it.seq }))

    fun clear() = items.clear()

    private fun max0(v: Long) = if (v < 0) 0 else v

    private fun dropStale() {
        val now = clock()
        items.removeAll { it.rank >= 3 && now - it.queuedAtMs > staleAfterMs }
    }

    companion object {
        /** Send order of a message: 0 Mayday, 1 Found/Request/Ack, 2 Hazard, 3 Sector, 4 Track, 5 Text and unknown. */
        fun rankOf(message: Map<String, Any?>): Int {
            val base = when (message.keys.firstOrNull { it in org.rescuecodec.codec.Codes.BODY_TYPES }) {
                "mayday" -> 0
                "found", "request", "ack" -> 1
                "hazard" -> 2
                "sector" -> 3
                "track" -> 4
                else -> 5
            }
            return if (message["priority"] == "critical") 0 else base
        }

        private fun keyOf(message: Map<String, Any?>): String? {
            val type = message.keys.firstOrNull { it in org.rescuecodec.codec.Codes.BODY_TYPES } ?: return null
            if (type == "sector") return "sector:" + ((message["sector"] as? Map<*, *>)?.get("sector") ?: return null)
            val id = (message["id"] as? Number)?.toInt() ?: return null
            if (id == 0 || type == "ack" || type == "track") return null
            return "$type:$id"
        }
    }
}

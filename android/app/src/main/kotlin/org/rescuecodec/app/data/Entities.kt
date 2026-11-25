// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where a message is on its way. Strings, so the database stays readable and old rows stay valid. */
object MessageState {
    /** Waiting in our send queue. */
    const val QUEUED = "queued"

    /** Waiting because our own airtime budget is used up. The row says when it may go. */
    const val HELD = "held"

    /** Handed to the radio. It will transmit it. */
    const val SENT = "sent"

    /** A neighbour repeated it, or the radio got an answer: the mesh took it. */
    const val DELIVERED = "delivered"

    /** The radio sent it but nobody confirmed. It may or may not have arrived. */
    const val NOT_CONFIRMED = "not_confirmed"

    /** The radio refused to send because of the legal airtime limit. We try again later. */
    const val REFUSED_AIRTIME = "refused_airtime"

    /** The radio could not send it for another reason. */
    const val FAILED = "failed"

    /** It sat in the queue too long (routine traffic) or a newer version replaced it. */
    const val EXPIRED = "expired"

    /** The person cancelled it before it was sent. */
    const val CANCELLED = "cancelled"

    /** Received from the mesh. */
    const val RECEIVED = "received"

    /** Received, but the bytes are not a valid RescueCodec message. */
    const val INVALID = "invalid"

    val WAITING = setOf(QUEUED, HELD, REFUSED_AIRTIME)
}

/** One row per message we sent or received. Newest rows have the highest [id]. */
@Entity(tableName = "messages", indices = [Index("atMs"), Index("taskKey"), Index("state")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "out" or "in". */
    val dir: String,
    /** Meshtastic node number of the sender (for "out": our own node, 0 while still unknown). */
    val node: Long,
    /** Meshtastic packet id (radio level), if known. */
    val packetId: Long? = null,
    /** Message type: found, request, hazard, mayday, ack, sector, track, text, unknown. */
    val type: String,
    /** RescueCodec id of the message (found, request, hazard, mayday), 0 if none. */
    val msgId: Int = 0,
    val bytes: ByteArray,
    /** Canonical JSON, or null when the bytes could not be decoded. */
    val json: String?,
    val atMs: Long,
    val state: String,
    /** Human-readable detail, for example the reason of a refusal. */
    val detail: String? = null,
    /** For waiting messages: when we will try again (ms since epoch). */
    val retryAtMs: Long? = null,
    val attempts: Int = 0,
    /** Task this message belongs to ("a1b2c3d4-7"), for reports and Acks. */
    val taskKey: String? = null,
    val snr: Float? = null,
    val rssi: Int? = null,
    val hops: Int? = null,
    /** Who asked us to send it: "app", or the package name of another app (Intent API). */
    val source: String? = null,
) {
    override fun equals(other: Any?) = other is MessageEntity && id == other.id && state == other.state &&
        detail == other.detail && retryAtMs == other.retryAtMs && attempts == other.attempts &&
        atMs == other.atMs && bytes.contentEquals(other.bytes) && json == other.json && taskKey == other.taskKey
    override fun hashCode() = id.hashCode() * 31 + state.hashCode()
}

/** One row per task (found, request, hazard, mayday), sent or received. */
@Entity(tableName = "tasks", indices = [Index("updatedMs")])
data class TaskEntity(
    /** "a1b2c3d4-7": sender node in hex, dash, message id. */
    @PrimaryKey val key: String,
    val node: Long,
    val msgId: Int,
    /** found, request, hazard, mayday, or unknown (an Ack arrived before the report). */
    val type: String,
    val json: String?,
    /** True if we sent it. */
    val local: Boolean,
    /** open, accepted, declined, done, delivery_unknown. */
    val state: String,
    /** The mesh confirmed that a neighbour took our report. */
    val meshDelivered: Boolean,
    val createdMs: Long,
    val updatedMs: Long,
    /** All answers, oldest first, as JSON: [{"from":123,"status":"accepted","team":"Trupp 3","eta":15,"at":1700000000000}]. */
    val answersJson: String = "[]",
    /** What WE last answered on this task: accepted, declined, done, or null. */
    val ownStatus: String? = null,
    /** Highest priority first: 0 mayday, 1 other. Used for sorting. */
    val rank: Int = 1,
)

/** A named counter. Names are listed in [StatNames]. */
@Entity(tableName = "stats")
data class StatEntity(@PrimaryKey val name: String, val value: Long)

object StatNames {
    const val TX_MESSAGES = "tx_messages"
    const val TX_BYTES = "tx_bytes"
    const val TX_AIRTIME_MS = "tx_airtime_ms"
    const val TX_DELIVERED = "tx_delivered"
    const val TX_NOT_CONFIRMED = "tx_not_confirmed"
    const val TX_REFUSED_AIRTIME = "tx_refused_airtime"
    const val TX_FAILED = "tx_failed"
    const val RX_MESSAGES = "rx_messages"
    const val RX_BYTES = "rx_bytes"
    const val RX_DUPLICATES = "rx_duplicates"
    const val RX_INVALID = "rx_invalid"

    /** Reports typed or spoken in the app that went into the send queue: bytes of the text, bytes of the coded message. */
    const val REPORT_TEXT_BYTES = "report_text_bytes"
    const val REPORT_CODED_BYTES = "report_coded_bytes"

    /** Estimated airtime saved by coding those reports instead of sending their text (LoRa formula). */
    const val REPORT_AIRTIME_SAVED_MS = "report_airtime_saved_ms"
    const val REPORT_COUNT = "report_count"
}

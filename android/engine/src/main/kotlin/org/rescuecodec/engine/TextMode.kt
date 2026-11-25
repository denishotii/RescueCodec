// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.engine

import org.rescuecodec.codec.CodecException
import org.rescuecodec.codec.RescueCodec
import org.rescuecodec.codec.TextPack

/**
 * Which way a chat text goes: packed for RescueCodec (port 256) or a normal Meshtastic text (port 1).
 * Same rule as rescuecodec/engine/textmode.py (spec section 2.3), keep them identical:
 *
 * 1. Every chat (a channel, or a direct chat with one node) has a mode: auto (default), compressed or normal.
 * 2. A public channel always gets normal text. Mode normal always gets normal text.
 * 3. Mode compressed: packed. Mode auto: a direct chat is packed when the peer runs RescueCodec (it sent a port 256
 *    packet in the last 24 h); a channel is packed when at least one RescueCodec node and no Meshtastic user (a node
 *    that sent port 1 texts there and no port 256 packet) was heard on it in the last 24 h. Nobody known: normal.
 * 4. Packed only when the packet is at least 3 bytes smaller than the words as a normal text. Else normal text.
 *
 * Example: "We are at the hut, two people with us, waiting for the vehicle on the forest road" is 81 B as a normal
 * text and 42 B packed with an id (no position), so it goes packed on a RescueCodec channel.
 */
object TextMode {
    const val AUTO = "auto"
    const val COMPRESSED = "compressed"
    const val NORMAL = "normal"
    val MODES = listOf(AUTO, COMPRESSED, NORMAL)

    const val MIN_SAVING_BYTES = 3

    /** How long a node stays "RescueCodec" or "Meshtastic user" after it was last heard. */
    const val MEMORY_MS = 24L * 3600 * 1000

    /** Packed wanted, and why. */
    data class Wish(val packed: Boolean, val reason: String)

    /**
     * Reasons: public_channel, mode_normal, mode_compressed, peer_rescuecodec, peer_unknown, meshtastic_users,
     * rescuecodec_only, nobody_known. An unknown [mode] counts as auto.
     */
    fun wantsPacked(
        mode: String,
        public: Boolean,
        direct: Boolean,
        peerRescueCodec: Boolean = false,
        meshtasticUsers: Int = 0,
        rescueCodecNodes: Int = 0,
    ): Wish = when {
        public -> Wish(false, "public_channel")
        mode == NORMAL -> Wish(false, "mode_normal")
        mode == COMPRESSED -> Wish(true, "mode_compressed")
        direct -> if (peerRescueCodec) Wish(true, "peer_rescuecodec") else Wish(false, "peer_unknown")
        meshtasticUsers > 0 -> Wish(false, "meshtastic_users")
        rescueCodecNodes > 0 -> Wish(true, "rescuecodec_only")
        else -> Wish(false, "nobody_known")
    }

    /**
     * The chosen form of a text. [payload] goes on the air (port 256 when [packed], else port 1); [message] is the
     * canonical message of what goes out. [plainSize] = bytes as a normal text (cut to 200 like [TextFit.fitPlain]),
     * [packedSize] = bytes as a packed RescueCodec text, null when it cannot be built (over 200 bytes).
     * [reason] is a [wantsPacked] reason, or "no_saving" / "too_large".
     */
    data class Form(
        val packed: Boolean,
        val payload: ByteArray,
        val message: Map<String, Any?>,
        val plainSize: Int,
        val packedSize: Int?,
        val reason: String,
    )

    /**
     * Builds both forms of a text message and picks one. The id, pos, time and priority of [message] go only into the
     * packed form. Never throws for a text message.
     */
    fun chooseForm(message: Map<String, Any?>, wantPacked: Boolean, reason: String = ""): Form {
        val words = (message["text"] as? Map<*, *>)?.get("text") as? String ?: ""
        val plain = TextFit.fitPlain(words)
        val plainBytes = plain.toByteArray(Charsets.UTF_8)
        val plainMsg = LinkedHashMap<String, Any?>()
        message["priority"]?.let { plainMsg["priority"] = it }
        plainMsg["text"] = linkedMapOf<String, Any?>("text" to plain)
        val packedMsg = LinkedHashMap<String, Any?>()
        for ((k, v) in message) if (k != "text") packedMsg[k] = v
        packedMsg["text"] = linkedMapOf<String, Any?>("text" to words, "dict" to TextPack.CURRENT_DICT)
        val packedPayload = try {
            RescueCodec.encode(packedMsg)
        } catch (e: CodecException) {
            null // over 200 bytes, or over 1024 bytes of text: normal text
        }
        val packedSize = packedPayload?.size
        if (wantPacked && packedPayload != null && packedPayload.size <= plainBytes.size - MIN_SAVING_BYTES) {
            return Form(true, packedPayload, RescueCodec.decode(packedPayload), plainBytes.size, packedSize, reason)
        }
        val why = if (!wantPacked) reason else if (packedPayload == null) "too_large" else "no_saving"
        return Form(false, plainBytes, plainMsg, plainBytes.size, packedSize, why)
    }
}

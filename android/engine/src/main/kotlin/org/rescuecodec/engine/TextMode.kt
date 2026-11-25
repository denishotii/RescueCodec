// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.engine

import org.rescuecodec.codec.CodecException
import org.rescuecodec.codec.Codes
import org.rescuecodec.codec.RescueCodec
import org.rescuecodec.codec.TextPack
import org.rescuecodec.codec.TextWords

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
 * 5. Compact text (opt-in setting, off by default): on a chat that packs, every complete form is built (normal text,
 *    DEFLATE Envelope, word tokens in the Envelope, compact frame 0x02) and the strictly smallest goes out; the normal
 *    text when it is smaller or equal. Spec sections 2.3, 3.6 and 3.7.
 * 6. A chat that packs takes up to 400 bytes of text; the packet stays at most 200 bytes ([TextFit.fitPacked] cuts the
 *    rest).
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

    /** [Form.form] of a normal Meshtastic text, a DEFLATE packed text and a compact word-token text. */
    const val FORM_PLAIN = "plain"
    const val FORM_DEFLATE = "deflate"
    const val FORM_WORDS = "words"

    /** [Form.form] of a compact frame (first byte 0x02, spec section 3.7): the word stream without an Envelope. */
    const val FORM_LEAN = "lean"

    /**
     * The chosen form of a text. [payload] goes on the air (port 256 when [packed], else port 1); [message] is the
     * canonical message of what goes out. [plainSize] = bytes as a normal text (cut to 200 like [TextFit.fitPlain]),
     * [packedSize] = bytes as a DEFLATE packed RescueCodec text, null when it cannot be built (over 200 bytes).
     * [reason] is a [wantsPacked] reason, or "no_saving" / "too_large". [form] is [FORM_PLAIN], [FORM_DEFLATE] or
     * [FORM_WORDS]; [wordsSize] = bytes as a compact word-token text, null when not tried or over 200 bytes. [text] is
     * the words that go out (shortened with "…" when they did not fit), [cut] is true when they were shortened.
     */
    data class Form(
        val packed: Boolean,
        val payload: ByteArray,
        val message: Map<String, Any?>,
        val plainSize: Int,
        val packedSize: Int?,
        val reason: String,
        val form: String = FORM_PLAIN,
        val wordsSize: Int? = null,
        val text: String = "",
        val cut: Boolean = false,
        /** Bytes as a compact frame (0x02), null when not tried, over 200 bytes or with a position or time. */
        val leanSize: Int? = null,
    )

    /** [rest] (id, pos, time, priority) with the text [body], encoded; null when it does not fit or is not valid. */
    private fun payload(rest: Map<String, Any?>, body: Map<String, Any?>): ByteArray? = try {
        RescueCodec.encode(LinkedHashMap(rest).also { it["text"] = body })
    } catch (e: CodecException) {
        null // over 200 bytes, or over 1024 bytes of text
    }

    private fun deflateBody(words: String) = linkedMapOf<String, Any?>("text" to words, "dict" to TextPack.CURRENT_DICT)

    private fun wordsBody(words: String) = linkedMapOf<String, Any?>("text" to words, "words" to TextWords.CURRENT_DICT)

    /**
     * The compact frame of a text, or null: it has no id, position or time, so a message with `pos` or `time` (they
     * would be lost) does not get one, nor a text over 200 bytes.
     */
    private fun lean(rest: Map<String, Any?>, words: String): ByteArray? {
        if (rest["pos"] != null || rest["time"] != null) return null
        return try {
            RescueCodec.encodeCompact(words, TextWords.CURRENT_DICT)
        } catch (e: CodecException) {
            null
        }
    }

    /**
     * Builds the forms of a text message and picks one. The id, pos, time and priority of [message] go only into the
     * packed forms. Never throws for a text message. Same as choose_form in rescuecodec/engine/textmode.py.
     *
     * [compact] off: DEFLATE when it fits and saves at least 3 bytes against the normal text, else the normal text.
     * [compact] on (the chat must pack, [wantPacked]): the complete payloads of the compact frame (0x02, no id), the
     * word tokens in the Envelope and DEFLATE are built, and the strictly smallest goes out (ties: compact frame, then
     * words, then DEFLATE); the normal text when it is smaller or equal, unless it would cut the text.
     * A chat that packs takes up to 400 bytes of text; what does not fit one packet is shortened ([TextFit.fitPacked]).
     */
    fun chooseForm(message: Map<String, Any?>, wantPacked: Boolean, reason: String = "", compact: Boolean = false): Form {
        var words = (message["text"] as? Map<*, *>)?.get("text") as? String ?: ""
        val rest = LinkedHashMap<String, Any?>()
        for ((k, v) in message) if (k != "text") rest[k] = v
        var cut = false
        if (wantPacked) {
            val fits = { w: String ->
                w.toByteArray(Charsets.UTF_8).size <= Codes.MAX_ENCODE_BYTES ||
                    payload(rest, deflateBody(w)) != null ||
                    (compact && (lean(rest, w) != null || payload(rest, wordsBody(w)) != null))
            }
            val fitted = TextFit.fitPacked(words, fits)
            cut = fitted != words
            words = fitted
        }
        val plain = TextFit.fitPlain(words)
        val plainBytes = plain.toByteArray(Charsets.UTF_8)
        val plainMsg = LinkedHashMap<String, Any?>()
        message["priority"]?.let { plainMsg["priority"] = it }
        plainMsg["text"] = linkedMapOf<String, Any?>("text" to plain)
        val packedPayload = payload(rest, deflateBody(words))
        val packedSize = packedPayload?.size
        if (compact && wantPacked) return smallest(message, rest, words, plain, plainMsg, packedPayload, reason, cut)
        if (wantPacked && packedPayload != null && packedPayload.size <= plainBytes.size - MIN_SAVING_BYTES) {
            return Form(true, packedPayload, RescueCodec.decode(packedPayload), plainBytes.size, packedSize, reason, FORM_DEFLATE, null, words, cut)
        }
        val why = if (!wantPacked) reason else if (packedPayload == null) "too_large" else "no_saving"
        return Form(false, plainBytes, plainMsg, plainBytes.size, packedSize, why, FORM_PLAIN, null, plain, cut || plain != words)
    }

    /** Compact on, a chat that packs: the strictly smallest complete payload; the normal text on a tie. */
    private fun smallest(
        message: Map<String, Any?>,
        rest: Map<String, Any?>,
        words: String,
        plain: String,
        plainMsg: Map<String, Any?>,
        packedPayload: ByteArray?,
        reason: String,
        cut: Boolean,
    ): Form {
        val plainBytes = plain.toByteArray(Charsets.UTF_8)
        val leanPayload = lean(rest, words)
        val wordsPayload = payload(rest, wordsBody(words))
        // In tie order: compact frame, words in the Envelope, DEFLATE.
        val candidates = listOf(FORM_LEAN to leanPayload, FORM_WORDS to wordsPayload, FORM_DEFLATE to packedPayload)
        var best: Pair<String, ByteArray>? = null
        for ((name, data) in candidates) if (data != null && (best == null || data.size < best.second.size)) best = name to data
        val lossy = plain != words // the normal text would be cut, a packed form keeps it whole
        if (best != null && (best.second.size < plainBytes.size || lossy)) {
            val (name, data) = best
            val msg: Map<String, Any?> = if (name == FORM_LEAN) {
                LinkedHashMap<String, Any?>().apply {
                    message["priority"]?.let { put("priority", it) } // not on the air: the radio's packet priority
                    put("text", wordsBody(words))
                }
            } else {
                RescueCodec.decode(data)
            }
            return Form(true, data, msg, plainBytes.size, packedPayload?.size, reason, name, wordsPayload?.size, words, cut, leanPayload?.size)
        }
        val why = if (best == null) "too_large" else "no_saving"
        return Form(false, plainBytes, plainMsg, plainBytes.size, packedPayload?.size, why, FORM_PLAIN, wordsPayload?.size, plain, cut || plain != words, leanPayload?.size)
    }
}

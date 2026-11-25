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
 * 5. Compact text (opt-in setting, off by default): on a chat that packs, the word-token form is built too and is sent
 *    when its whole payload is smaller than what rule 4 picked (ties keep rule 4). Spec section 3.6.
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
     * Builds the forms of a text message and picks one. The id, pos, time and priority of [message] go only into the
     * packed forms. Never throws for a text message. Same as choose_form in rescuecodec/engine/textmode.py.
     *
     * Existing rule ([compact] off): DEFLATE when it fits and saves at least 3 bytes against the normal text, else the
     * normal text. With [compact] (the chat must pack, [wantPacked]), the word-token form is built too and replaces
     * the existing choice only when its whole payload is smaller, or when the existing choice would cut the text.
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
                    (compact && payload(rest, wordsBody(w)) != null)
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
        val wordsPayload = if (compact && wantPacked) payload(rest, wordsBody(words)) else null
        val wordsSize = wordsPayload?.size
        val existing = if (wantPacked && packedPayload != null && packedPayload.size <= plainBytes.size - MIN_SAVING_BYTES) {
            Form(true, packedPayload, RescueCodec.decode(packedPayload), plainBytes.size, packedSize, reason, FORM_DEFLATE, wordsSize, words, cut)
        } else {
            val why = if (!wantPacked) reason else if (packedPayload == null) "too_large" else "no_saving"
            Form(false, plainBytes, plainMsg, plainBytes.size, packedSize, why, FORM_PLAIN, wordsSize, plain, cut || plain != words)
        }
        if (wordsPayload != null) {
            val lossy = existing.form == FORM_PLAIN && plain != words
            if (wordsPayload.size < existing.payload.size || lossy) {
                return Form(true, wordsPayload, RescueCodec.decode(wordsPayload), plainBytes.size, packedSize, reason, FORM_WORDS, wordsSize, words, cut)
            }
        }
        return existing
    }
}

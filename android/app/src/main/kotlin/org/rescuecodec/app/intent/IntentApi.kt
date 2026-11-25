// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.intent

import android.os.Bundle
import kotlinx.coroutines.delay
import org.rescuecodec.app.AppGraph
import org.rescuecodec.app.core.SubmitResult
import org.rescuecodec.app.data.MessageState
import org.rescuecodec.app.radio.LinkState
import org.rescuecodec.codec.Json
import org.rescuecodec.engine.Extractor

/**
 * The public Intent API: other apps ask us to send a report.
 *
 * Action `org.rescuecodec.action.SEND_REPORT`, started with `startActivityForResult` (or the
 * Activity Result API). Input is either a canonical JSON message ([EXTRA_JSON]) or free text
 * ([EXTRA_TEXT], German or English, understood by the rule-based extractor). The result is an Intent
 * with a JSON string ([EXTRA_RESULT_JSON]) and a few flat extras. See docs/INTEGRATE.md.
 */
object IntentApi {
    const val ACTION_SEND_REPORT = "org.rescuecodec.action.SEND_REPORT"

    // Input extras
    const val EXTRA_JSON = "org.rescuecodec.extra.JSON"
    const val EXTRA_TEXT = "org.rescuecodec.extra.TEXT"
    const val EXTRA_LANG = "org.rescuecodec.extra.LANG"
    const val EXTRA_LAT = "org.rescuecodec.extra.LAT"
    const val EXTRA_LON = "org.rescuecodec.extra.LON"
    const val EXTRA_PRIORITY = "org.rescuecodec.extra.PRIORITY"
    const val EXTRA_WAIT_SECONDS = "org.rescuecodec.extra.WAIT_SECONDS"

    // Result extras
    const val EXTRA_RESULT_JSON = "org.rescuecodec.extra.RESULT_JSON"
    const val EXTRA_RESULT_STATUS = "org.rescuecodec.extra.RESULT_STATUS"
    const val EXTRA_RESULT_ID = "org.rescuecodec.extra.RESULT_ID"
    const val EXTRA_RESULT_ERROR = "org.rescuecodec.extra.RESULT_ERROR"

    /** Longest time we wait for the radio when [EXTRA_WAIT_SECONDS] is set. */
    const val MAX_WAIT_SECONDS = 30

    /** What the caller asked for, as plain values (so it can be tested without Android). */
    data class Request(
        val json: String? = null,
        val text: String? = null,
        val lang: String? = null,
        val lat: Double? = null,
        val lon: Double? = null,
        val priority: String? = null,
        val waitSeconds: Int = 0,
        val callingPackage: String? = null,
    ) {
        companion object {
            fun fromBundle(b: Bundle?, callingPackage: String?): Request = Request(
                json = b?.getString(EXTRA_JSON),
                text = b?.getString(EXTRA_TEXT),
                lang = b?.getString(EXTRA_LANG),
                lat = if (b?.containsKey(EXTRA_LAT) == true) b.getDouble(EXTRA_LAT) else null,
                lon = if (b?.containsKey(EXTRA_LON) == true) b.getDouble(EXTRA_LON) else null,
                priority = b?.getString(EXTRA_PRIORITY),
                waitSeconds = (b?.getInt(EXTRA_WAIT_SECONDS, 0) ?: 0).coerceIn(0, MAX_WAIT_SECONDS),
                callingPackage = callingPackage,
            )
        }
    }

    /** The answer. [json] is what goes into [EXTRA_RESULT_JSON]. */
    data class Result(val ok: Boolean, val status: String, val id: Int, val json: String)

    /**
     * Builds the message from [req]: JSON as given, or text through the extractor. Adds a position
     * from [Request.lat] and [Request.lon] if the message has none. Returns the message or an error
     * code with text.
     */
    fun buildMessage(req: Request): Pair<Map<String, Any?>?, Pair<String, String>?> {
        val msg: LinkedHashMap<String, Any?>
        when {
            !req.json.isNullOrBlank() -> {
                msg = try {
                    LinkedHashMap(Json.parseObject(req.json))
                } catch (e: IllegalArgumentException) {
                    return null to ("invalid_json" to "EXTRA_JSON is not a JSON object: ${e.message}")
                }
            }
            !req.text.isNullOrBlank() -> {
                val ex = Extractor().extract(req.text, req.lang)
                msg = LinkedHashMap(ex.message)
            }
            else -> return null to ("no_input" to "Send EXTRA_JSON or EXTRA_TEXT.")
        }
        if (msg["pos"] == null && req.lat != null && req.lon != null) {
            msg["pos"] = linkedMapOf("lat" to req.lat, "lon" to req.lon)
        }
        if (!req.priority.isNullOrBlank()) msg["priority"] = req.priority
        return msg to null
    }

    /** Runs [req]: build, queue, optionally wait for the radio, and describe the outcome. */
    suspend fun handle(graph: AppGraph, req: Request): Result {
        if (!graph.settings.allowIntentApi) {
            return error("disabled", "The person turned off sending from other apps in RescueCodec.")
        }
        val (msg, err) = buildMessage(req)
        if (msg == null) return error(err!!.first, err.second)
        graph.start()
        val queued = when (val r = graph.messenger.submit(msg, source = req.callingPackage ?: "intent")) {
            is SubmitResult.Rejected -> return error(r.code, r.message, r.field)
            is SubmitResult.Queued -> r
        }
        // Make sure the radio link is up, so the message does not wait for a person to open the app.
        val linkState = graph.link.state.value
        var status = "queued"
        var key: String? = null
        var detail: String? = null
        if (req.waitSeconds > 0) {
            val dao = graph.database.messages()
            val until = System.currentTimeMillis() + req.waitSeconds * 1000L
            while (System.currentTimeMillis() < until) {
                val row = dao.get(queued.rowId)
                if (row != null && row.state !in MessageState.WAITING) {
                    status = row.state
                    key = row.taskKey
                    detail = row.detail
                    break
                }
                detail = row?.detail
                delay(200)
            }
        }
        val out = linkedMapOf<String, Any?>(
            "ok" to true,
            "status" to status,
            "type" to queued.type,
            "id" to queued.msgId,
            "size" to queued.bytes.size,
            "hex" to queued.bytes.joinToString("") { "%02x".format(it) },
            "readable" to queued.readable,
            "radio" to radioName(linkState),
        )
        if (key != null) out["key"] = key
        if (detail != null) out["detail"] = detail
        return Result(true, status, queued.msgId, Json.stringify(out))
    }

    fun error(code: String, message: String, field: String? = null): Result {
        val out = linkedMapOf<String, Any?>("ok" to false, "status" to "error", "error" to code, "message" to message)
        if (field != null) out["field"] = field
        return Result(false, "error", 0, Json.stringify(out))
    }

    private fun radioName(s: LinkState): String = when (s) {
        is LinkState.Connected -> "connected"
        is LinkState.Connecting -> "connecting"
        is LinkState.Scanning -> "disconnected"
        is LinkState.Error -> "error"
        LinkState.Disconnected -> "disconnected"
    }
}

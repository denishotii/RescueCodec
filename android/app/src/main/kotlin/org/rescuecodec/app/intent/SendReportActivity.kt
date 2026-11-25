// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.intent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.rescuecodec.app.AppGraph
import org.rescuecodec.app.radio.LinkState
import org.rescuecodec.app.service.RadioService

/**
 * Exported entry point for other apps: action `org.rescuecodec.action.SEND_REPORT`.
 *
 * It has no screen. It queues the report, starts the radio service if a radio is remembered, and
 * returns the result with `setResult`. `RESULT_OK` means the report is queued (or already sent, if
 * the caller asked to wait). `RESULT_CANCELED` means it was refused: see the `error` extra.
 */
class SendReportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = AppGraph.from(this)
        val req = IntentApi.Request.fromBundle(intent?.extras, callingPackage ?: referrer?.host)
        lifecycleScope.launch {
            val result = IntentApi.handle(graph, req)
            if (result.ok && graph.link.state.value is LinkState.Disconnected && graph.lastRadio.get() != null) {
                runCatching { RadioService.start(applicationContext) }
            }
            val data = Intent()
                .putExtra(IntentApi.EXTRA_RESULT_JSON, result.json)
                .putExtra(IntentApi.EXTRA_RESULT_STATUS, result.status)
                .putExtra(IntentApi.EXTRA_RESULT_ID, result.id)
            if (!result.ok) data.putExtra(IntentApi.EXTRA_RESULT_ERROR, org.rescuecodec.codec.Json.parseObject(result.json)["error"] as? String)
            setResult(if (result.ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, data)
            finish()
        }
    }
}

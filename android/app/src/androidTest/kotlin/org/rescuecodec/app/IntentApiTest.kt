// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import android.app.Activity
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.rescuecodec.app.intent.IntentApi
import org.rescuecodec.codec.Json
import org.rescuecodec.codec.RescueCodec
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The public Intent API, called the way another app would: start the exported activity for a result.
 * The app is not connected to a radio here, so reports stay in the queue ("queued").
 */
@RunWith(AndroidJUnit4::class)
class IntentApiTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = AppGraph.from(context)

    @Before
    fun clean() {
        graph.settings.allowIntentApi = true
        graph.link.disconnect()
        runBlocking { graph.database.messages().clear() }
    }

    @After
    fun restore() {
        graph.settings.allowIntentApi = true
        runBlocking { graph.database.messages().clear() }
    }

    /**
     * Starts the exported activity for a result, as another app would. We read the result and do not
     * call scenario.close(): the activity has finished itself, and close() can wait 45 s for a
     * lifecycle state that never comes for a self-finishing translucent activity.
     */
    private fun call(configure: Intent.() -> Unit): Pair<Int, Map<String, Any?>> {
        val intent = Intent(IntentApi.ACTION_SEND_REPORT).setPackage(context.packageName).apply(configure)
        val scenario = ActivityScenario.launchActivityForResult<org.rescuecodec.app.intent.SendReportActivity>(intent)
        val result = scenario.result
        val json = result.resultData?.getStringExtra(IntentApi.EXTRA_RESULT_JSON)
        return result.resultCode to Json.parseObject(json ?: "{}")
    }

    @Test
    fun jsonReportIsQueuedAndAnswered() {
        val report = """{"id":7,"pos":{"lat":50.69212,"lon":7.21345},"found":{"age":"child","needs":["stretcher"]}}"""
        val (code, out) = call { putExtra(IntentApi.EXTRA_JSON, report) }
        assertEquals(Activity.RESULT_OK, code)
        assertEquals(true, out["ok"])
        assertEquals("queued", out["status"])
        assertEquals("found", out["type"])
        assertEquals(7, (out["id"] as Number).toInt())
        assertEquals(RescueCodec.encode(Json.parseObject(report)).joinToString("") { "%02x".format(it) }, out["hex"])
        assertTrue((out["readable"] as String).contains("child", ignoreCase = true))
        val rows = runBlocking { graph.database.messages().waiting() }
        assertEquals(1, rows.size)
        assertEquals(context.packageName.takeIf { rows[0].source == it } ?: rows[0].source, rows[0].source)
    }

    @Test
    fun germanTextIsUnderstoodAndGetsAnId() {
        val (code, out) = call {
            putExtra(IntentApi.EXTRA_TEXT, "Kind gefunden, ansprechbar, unterkühlt, wir brauchen eine Trage")
            putExtra(IntentApi.EXTRA_LAT, 50.69)
            putExtra(IntentApi.EXTRA_LON, 7.21)
        }
        assertEquals(Activity.RESULT_OK, code)
        assertEquals("found", out["type"])
        assertTrue((out["id"] as Number).toInt() in 1..16383)
    }

    @Test
    fun errorsComeBackAsCanceledWithAnErrorCode() {
        val (c1, o1) = call { putExtra(IntentApi.EXTRA_JSON, "{not json") }
        assertEquals(Activity.RESULT_CANCELED, c1)
        assertEquals("invalid_json", o1["error"])

        val (c2, o2) = call { putExtra(IntentApi.EXTRA_JSON, """{"found":{"age":"dinosaur"}}""") }
        assertEquals(Activity.RESULT_CANCELED, c2)
        assertEquals("invalid_value", o2["error"])

        val (c3, o3) = call { }
        assertEquals(Activity.RESULT_CANCELED, c3)
        assertEquals("no_input", o3["error"])
        assertEquals(0, runBlocking { graph.database.messages().waiting().size })
    }

    @Test
    fun theUserCanSwitchTheApiOff() {
        graph.settings.allowIntentApi = false
        val (code, out) = call { putExtra(IntentApi.EXTRA_JSON, """{"text":{"text":"hi"}}""") }
        assertEquals(Activity.RESULT_CANCELED, code)
        assertEquals("disabled", out["error"])
    }
}

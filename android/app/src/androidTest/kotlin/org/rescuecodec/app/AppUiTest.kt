// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.test.center
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rescuecodec.app.core.MemoryKeyValueStore
import org.rescuecodec.app.data.AppDatabase
import org.rescuecodec.app.data.MessageEntity
import org.rescuecodec.app.data.MessageState
import org.rescuecodec.app.data.TaskEntity
import org.rescuecodec.app.ui.RescueRoot
import org.rescuecodec.codec.Json
import org.rescuecodec.codec.RescueCodec
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose UI tests on the emulator. The app runs with an in-memory database and no radio, so a
 * report stays in the queue and we can see it there. No network, no Bluetooth.
 */
@RunWith(AndroidJUnit4::class)
class AppUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        graph = AppGraph(context, database = AppDatabase.inMemory(context), keyValue = MemoryKeyValueStore())
    }

    @After
    fun tearDown() {
        graph.shutdown()
        graph.database.close()
    }

    private fun show() {
        graph.start()
        rule.setContent { RescueRoot(graph) }
    }

    @Test
    fun reportThenSendQueuesTheCodedMessage() {
        show()
        rule.onNodeWithTag("report_text").performTextInput("Child found, conscious, hypothermia, need stretcher")
        // The fields appear as chips and the counter shows the saving.
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("chip_type").fetchSemanticsNodes().isNotEmpty() }
        // The keyboard is up, so scroll the list to each chip before checking it.
        rule.onNodeWithTag("chip_type").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("chip_age").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("chip_injuries_hypothermia").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("report_readable").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("send_button").assertIsEnabled().performClick()

        // One row in the send queue, and it decodes to what was typed.
        rule.waitUntil(8_000) { runBlocking { graph.database.messages().waiting().size } == 1 }
        val row = runBlocking { graph.database.messages().waiting() }.single()
        assertEquals("found", row.type)
        assertEquals(MessageState.QUEUED, row.state)
        val msg = RescueCodec.decode(row.bytes)
        @Suppress("UNCHECKED_CAST") val found = msg["found"] as Map<String, Any?>
        assertEquals("child", found["age"])
        assertEquals(listOf("hypothermia"), found["injuries"])
        assertEquals(listOf("stretcher"), found["needs"])
        // Smaller than the text it came from, and the stat was counted.
        assertTrue(row.bytes.size < "Child found, conscious, hypothermia, need stretcher".length)
        // The counters are written just after the row, so wait for them.
        fun stats() = runBlocking { graph.database.stats().all() }.associate { it.name to it.value }
        rule.waitUntil(8_000) { stats()["report_airtime_saved_ms"] != null } // the last counter written
        assertEquals(1L, stats()["report_count"])
        assertEquals(row.bytes.size.toLong(), stats()["report_coded_bytes"])

        // The Feed shows it as waiting for the radio.
        rule.onNodeWithTag("tab_feed").performClick()
        rule.onNodeWithText("Waiting for radio").assertIsDisplayed()
    }

    @Test
    fun acceptThenDoneChangesTheTask() {
        val key = "abcd1234-7"
        val json = """{"id":7,"found":{"age":"child","consciousness":"conscious"}}"""
        runBlocking {
            graph.settings.lastOwnNode = 0x11111111L
            graph.database.messages().insert(
                MessageEntity(
                    dir = "in", node = 0xabcd1234L, type = "found", msgId = 7, bytes = RescueCodec.encode(Json.parseObject(json)),
                    json = json, atMs = System.currentTimeMillis(), state = MessageState.RECEIVED, taskKey = key,
                ),
            )
            graph.database.tasks().upsert(
                TaskEntity(
                    key = key, node = 0xabcd1234L, msgId = 7, type = "found", json = json, local = false, state = "open",
                    meshDelivered = false, createdMs = System.currentTimeMillis(), updatedMs = System.currentTimeMillis(),
                ),
            )
        }
        show()
        rule.onNodeWithTag("tab_actions").performClick()
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("accept_$key").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("accept_$key").performClick()

        // Our Accept goes into the queue as an Ack, and the card now offers Done.
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("done_$key").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("done_$key").assertIsDisplayed()
        val ack = runBlocking { graph.database.messages().waiting() }.single { it.type == "ack" }
        @Suppress("UNCHECKED_CAST") val body = RescueCodec.decode(ack.bytes)["ack"] as Map<String, Any?>
        assertEquals("accepted", body["status"])
        assertEquals(7, (body["ref"] as Number).toInt())

        rule.onNodeWithTag("done_$key").performClick()
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("done_$key").fetchSemanticsNodes().isEmpty() }
        val acks = runBlocking { graph.database.messages().waiting() }.filter { it.type == "ack" }.map { (RescueCodec.decode(it.bytes)["ack"] as Map<*, *>)["status"] }
        assertEquals(listOf("accepted", "done"), acks)
    }

    @Test
    fun sosNeedsAHoldAndThenQueuesAMayday() {
        show()
        // A quick tap does nothing.
        rule.onNodeWithTag("sos_bar").performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertEquals(0, runBlocking { graph.database.messages().waiting() }.size)
        // A hold of two seconds sends it.
        rule.onNodeWithTag("sos_bar").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(2_600)
        rule.onNodeWithTag("sos_bar").performTouchInput { up() }
        rule.waitUntil(8_000) { runBlocking { graph.database.messages().waiting().size } == 1 }
        assertEquals("mayday", runBlocking { graph.database.messages().waiting() }.single().type)
        rule.onNodeWithTag("mayday_banner").assertIsDisplayed()
    }

    @Test
    fun languageToggleSwitchesTheWholeApp() {
        show()
        rule.onNodeWithText("Actions").assertIsDisplayed()
        rule.onNodeWithTag("settings_button").performClick()
        rule.onNodeWithTag("lang_de").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Senden").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Aufgaben").assertIsDisplayed()
        assertEquals("de", graph.settings.language)
    }
}

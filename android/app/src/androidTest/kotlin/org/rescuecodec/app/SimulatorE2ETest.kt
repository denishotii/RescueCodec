// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.rescuecodec.app.core.AnswerResult
import org.rescuecodec.app.core.MemoryKeyValueStore
import org.rescuecodec.app.core.SubmitResult
import org.rescuecodec.app.data.AppDatabase
import org.rescuecodec.app.data.MessageState
import org.rescuecodec.app.radio.LinkState
import org.rescuecodec.app.radio.RadioTarget
import org.rescuecodec.app.service.RadioService
import org.rescuecodec.codec.Json
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs on the emulator against the Meshtastic simulator (real firmware in Docker, sim/up.sh).
 * The emulator reaches the host at 10.0.2.2. The app is connected to SIM-FIELD (port 4403).
 *
 * [foundReachesThePeerAndAcksComeBack] needs the Python peer on SIM-BASE (sim/android_e2e_peer.py).
 * `scripts/android-e2e.sh` starts both. Run on its own, the first test fails with a timeout.
 */
@RunWith(AndroidJUnit4::class)
class SimulatorE2ETest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val host get() = args.getString("sim_host") ?: "10.0.2.2"
    private val port get() = args.getString("sim_port")?.toIntOrNull() ?: 4403
    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        graph = AppGraph(
            context,
            database = AppDatabase.inMemory(context),
            keyValue = MemoryKeyValueStore(),
        )
        graph.start()
    }

    @After
    fun tearDown() {
        graph.shutdown()
        runBlocking { eventually(10_000, "disconnected") { graph.link.state.value is LinkState.Disconnected } }
        graph.database.close()
    }

    @Test
    fun foundReachesThePeerAndAcksComeBack() = runBlocking {
        val report = Json.parseObject(
            instrumentation.context.assets.open("found.json").bufferedReader().use { it.readText() },
        )
        graph.link.connect(RadioTarget.Tcp(host, port))
        eventually(90_000, "connected to the simulator") { graph.link.state.value is LinkState.Connected }
        val me = (graph.link.state.value as LinkState.Connected)
        assertTrue(me.nodeNum != 0L, "own node number known")

        // 1. Send the Found report. The peer compares our bytes with the Python codec.
        val queued = graph.messenger.submit(report)
        assertIs<SubmitResult.Queued>(queued)
        val key = "%08x-4242".format(me.nodeNum)
        eventually(30_000, "report handed to the radio") {
            graph.database.messages().get(queued.rowId)!!.state !in MessageState.WAITING
        }
        val sentRow = graph.database.messages().get(queued.rowId)!!
        assertEquals(key, sentRow.taskKey)

        // 2. The peer answers with Accept (twice, the second is a duplicate), then Done.
        eventually(120_000, "task accepted by the peer") { graph.database.tasks().get(key)?.state in setOf("accepted", "done") }
        val accepted = graph.database.tasks().get(key)!!
        assertTrue(accepted.answersJson.contains("Trupp 3"), "team label arrived: ${accepted.answersJson}")
        eventually(60_000, "task done") { graph.database.tasks().get(key)?.state == "done" }
        eventually(5_000, "duplicate Accept ignored") {
            graph.database.stats().all().associate { it.name to it.value }["rx_duplicates"] ?: 0 >= 1
        }

        // 3. The peer sends its own Found report. We Accept it and the peer must see the Ack.
        eventually(120_000, "peer report arrived") { graph.database.tasks().all().any { it.msgId == 99 && !it.local } }
        val theirs = graph.database.tasks().all().first { it.msgId == 99 && !it.local }
        assertEquals("open", theirs.state)
        assertEquals("found", theirs.type)
        val answer = graph.messenger.answer(theirs.key, "accepted", etaMin = 20)
        assertIs<AnswerResult.Sent>(answer)
        assertEquals("accepted", graph.database.tasks().get(theirs.key)!!.state)
        eventually(30_000, "our Ack handed to the radio") { graph.database.messages().waiting().isEmpty() }
        // Give the peer time to read the Ack before the test disconnects.
        kotlinx.coroutines.delay(20_000)
    }

    @Test
    fun serviceKeepsTheLinkAndShowsANotification() = runBlocking {
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        // The service uses the app's own graph, so use that one here.
        val real = AppGraph.from(context)
        real.link.disconnect()
        RadioService.start(context, RadioTarget.Tcp(host, port))
        eventually(90_000, "connected through the service") { real.link.state.value is LinkState.Connected }
        assertTrue(RadioService.running)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        eventually(10_000, "foreground notification") { nm.activeNotifications.isNotEmpty() }
        // The notification text follows the link state a moment later, so wait for it.
        eventually(10_000, "notification says connected") {
            nm.activeNotifications.firstOrNull()?.notification?.extras?.getCharSequence("android.text")?.contains("Connected") == true
        }
        RadioService.stop(context)
        eventually(15_000, "disconnected after stop") { real.link.state.value is LinkState.Disconnected }
        eventually(10_000, "service stopped") { !RadioService.running }
    }
}

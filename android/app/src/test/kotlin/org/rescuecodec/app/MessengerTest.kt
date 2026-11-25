// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Routing
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import org.rescuecodec.app.core.AnswerResult
import org.rescuecodec.app.core.AppSettings
import org.rescuecodec.app.core.MemoryKeyValueStore
import org.rescuecodec.app.core.Messenger
import org.rescuecodec.app.core.SubmitResult
import org.rescuecodec.app.data.MessageState
import org.rescuecodec.app.data.StatNames
import org.rescuecodec.codec.Json
import org.rescuecodec.codec.RescueCodec
import org.rescuecodec.engine.Airtime
import org.rescuecodec.engine.AirtimeBudget
import org.rescuecodec.engine.ModemPreset
import org.rescuecodec.engine.Region
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessengerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val messages = FakeMessageDao()
    private val tasks = FakeTaskDao()
    private val stats = FakeStatDao()
    private val store = MemoryKeyValueStore()
    private val settings = AppSettings(store)
    private val clientFlow = MutableStateFlow<RadioClient?>(null)
    private val attachedFlow = MutableStateFlow<RadioClient?>(null)

    private val me = 0xa1b2c3d4L
    private val other = 0x0badf00dL
    private lateinit var radio: FakeRadioTransport

    private fun messenger(
        useScope: CoroutineScope = scope,
        gap: Long = 0,
        refusalDelays: List<Long> = listOf(150),
        timeoutMs: (String) -> Long = { 300_000 },
        budget: AirtimeBudget = AirtimeBudget(Airtime(settings.modemPreset), settings.region),
    ) = Messenger(
        useScope, clientFlow, attachedFlow, messages, tasks, stats, settings, gapMs = gap,
        refusalDelaysMs = refusalDelays, taskTimeoutMs = timeoutMs, log = { println("[messenger] $it") }, budget = budget,
    )

    private suspend fun connectRadio(channels: List<org.meshtastic.proto.Channel> = listOf(teamChannel(0))) {
        radio = fakeRadio("fake:me", me.toInt(), channels)
        val client = RadioClient.Builder().transport(radio).storage(InMemoryStorageProvider()).build()
        attachedFlow.value = client // as ConnectionManager does: listen before connecting
        delay(200)
        client.connect()
        eventually(5_000, "own node known") { Messenger.nodeOf(client) == me }
        clientFlow.value = client
    }

    private fun runUnit(block: suspend CoroutineScope.() -> Unit) { runBlocking { block() } }

    @AfterTest
    fun tearDown() { scope.cancel() }

    private val found = mapOf(
        "id" to 7,
        "pos" to mapOf("lat" to 50.69212, "lon" to 7.21345),
        "found" to mapOf("age" to "child", "consciousness" to "conscious", "injuries" to listOf("hypothermia"), "needs" to listOf("stretcher")),
    )

    @Test
    fun foundGoesOutAsPrivateAppBroadcastWithTheExactBytes() = runUnit {
        val m = messenger()
        m.start()
        connectRadio()
        val r = m.submit(found)
        assertIs<SubmitResult.Queued>(r)
        assertContentEquals(RescueCodec.encode(found), r.bytes)
        eventually(10_000, "packet sent") { radio.sentPrivateApp().size == 1 }
        val p = radio.sentPrivateApp().single()
        assertContentEquals(r.bytes, p.decoded!!.payload.toByteArray())
        assertEquals(PortNum.PRIVATE_APP, p.decoded!!.portnum)
        assertEquals(-1, p.to, "broadcast")
        assertEquals(true, p.want_ack)
        assertEquals(MeshPacket.Priority.HIGH, p.priority)
        eventually(5_000, "row is sent") { messages.all.single().state == MessageState.SENT }
        val task = tasks.get("a1b2c3d4-7")!!
        assertEquals("open", task.state)
        assertTrue(task.local)
        assertEquals(1L, stats[StatNames.TX_MESSAGES])
        assertEquals(r.bytes.size.toLong(), stats[StatNames.TX_BYTES])
    }

    @Test
    fun maydayLeavesFirstEvenWhenSubmittedLast() = runUnit {
        val m = messenger()
        m.start()
        // Radio not connected yet: everything waits in the queue.
        m.submit(mapOf("text" to mapOf("text" to "lunch break")))
        m.submit(found)
        val may = m.submit(mapOf("pos" to mapOf("lat" to 50.7, "lon" to 7.2), "mayday" to mapOf("reason" to "lost")), source = "test")
        if (may is SubmitResult.Rejected) throw AssertionError("mayday rejected: $may")
        eventually(2_000, "three waiting") { messages.waiting().size == 3 }
        connectRadio()
        eventually(10_000, "three sent") { radio.sentPrivateApp().size == 3 }
        val order = radio.sentPrivateApp().map { RescueCodec.messageType(RescueCodec.decodeAny(it.decoded!!.payload.toByteArray())) }
        assertEquals(listOf("mayday", "found", "text"), order)
        assertEquals(MeshPacket.Priority.ALERT, radio.sentPrivateApp()[0].priority)
        assertEquals(MeshPacket.Priority.BACKGROUND.let { MeshPacket.Priority.RELIABLE }, radio.sentPrivateApp()[2].priority, "text is normal priority")
    }

    @Test
    fun ackFromAnotherNodeMarksTheTaskAccepted() = runUnit {
        val m = messenger()
        m.start()
        connectRadio()
        m.submit(found)
        eventually(10_000, "sent") { radio.sentPrivateApp().size == 1 }
        val ack = RescueCodec.encode(mapOf("ack" to mapOf("ref" to 7, "node" to "!a1b2c3d4", "status" to "accepted", "eta" to 15, "team" to "Trupp 3")))
        radio.injectPacket(privateAppPacket(other, 111, ack))
        eventually(5_000, "accepted") { tasks.get("a1b2c3d4-7")?.state == "accepted" }
        val row = tasks.get("a1b2c3d4-7")!!
        assertTrue(row.answersJson.contains("Trupp 3"))
        // The same Ack again is a duplicate.
        radio.injectPacket(privateAppPacket(other, 112, ack))
        eventually(5_000, "duplicate counted") { stats[StatNames.RX_DUPLICATES] == 1L }
        // Done follows.
        val done = RescueCodec.encode(mapOf("ack" to mapOf("ref" to 7, "node" to "!a1b2c3d4", "status" to "done")))
        radio.injectPacket(privateAppPacket(other, 113, done))
        eventually(5_000, "done") { tasks.get("a1b2c3d4-7")?.state == "done" }
    }

    @Test
    fun receivedReportBecomesATaskAndDuplicatesAreDropped() = runUnit {
        val m = messenger()
        m.start()
        connectRadio()
        val bytes = RescueCodec.encode(found + ("id" to 21))
        radio.injectPacket(privateAppPacket(other, 1, bytes))
        eventually(5_000, "task") { tasks.get("0badf00d-21") != null }
        val t = tasks.get("0badf00d-21")!!
        assertEquals("found", t.type)
        assertEquals("open", t.state)
        assertEquals(false, t.local)
        radio.injectPacket(privateAppPacket(other, 2, bytes)) // the mesh delivered it twice
        eventually(5_000, "duplicate counted") { stats[StatNames.RX_DUPLICATES] == 1L }
        assertEquals(1, tasks.all.size)
        assertEquals(1, messages.all.count { it.dir == "in" })
        assertEquals(1L, stats[StatNames.RX_MESSAGES])
        val row = messages.all.single()
        assertEquals(1, row.hops)
        assertEquals("0badf00d-21", row.taskKey)
    }

    @Test
    fun textWithoutIdIsDedupedByPayload() = runUnit {
        val m = messenger()
        m.start()
        connectRadio()
        val bytes = RescueCodec.encode(mapOf("text" to mapOf("text" to "Base to all teams: radio check")))
        radio.injectPacket(privateAppPacket(other, 1, bytes))
        radio.injectPacket(privateAppPacket(other, 2, bytes))
        eventually(5_000, "one stored, one duplicate") { messages.all.size == 1 && stats[StatNames.RX_DUPLICATES] == 1L }
        assertTrue(tasks.all.isEmpty(), "text is not a task")
    }

    @Test
    fun invalidBytesAreKeptAsInvalidAndCounted() = runUnit {
        val m = messenger()
        m.start()
        connectRadio()
        radio.injectPacket(privateAppPacket(other, 1, byteArrayOf(0x08, 0x01, 0x7f, 0x7f)))
        eventually(5_000, "invalid row") { messages.all.any { it.state == MessageState.INVALID } }
        assertEquals(1L, stats[StatNames.RX_INVALID])
        assertNull(messages.all.single().json)
    }

    @Test
    fun acceptThenDoneSendAcksAndFollowTheMoveRules() = runUnit {
        val m = messenger()
        m.start()
        connectRadio()
        settings.teamLabel = "Trupp 3"
        radio.injectPacket(privateAppPacket(other, 1, RescueCodec.encode(found + ("id" to 21))))
        eventually(5_000, "task") { tasks.get("0badf00d-21") != null }

        assertEquals(AnswerResult.InvalidMove("open", "done"), m.answer("0badf00d-21", "done"))
        assertIs<AnswerResult.Sent>(m.answer("0badf00d-21", "accepted", etaMin = 15))
        eventually(5_000, "ack sent") { radio.sentPrivateApp().size == 1 }
        val sent = RescueCodec.decodeAny(radio.sentPrivateApp().single().decoded!!.payload.toByteArray())
        val ack = sent["ack"] as Map<*, *>
        assertEquals(21, (ack["ref"] as Number).toInt())
        assertEquals("!0badf00d", ack["node"])
        assertEquals("accepted", ack["status"])
        assertEquals("Trupp 3", ack["team"])
        assertEquals(MeshPacket.Priority.HIGH, radio.sentPrivateApp().single().priority)
        // Applied at once, no need to wait for the mesh.
        assertEquals("accepted", tasks.get("0badf00d-21")!!.state)
        assertEquals("accepted", tasks.get("0badf00d-21")!!.ownStatus)

        assertEquals(AnswerResult.InvalidMove("accepted", "accepted"), m.answer("0badf00d-21", "accepted"))
        assertIs<AnswerResult.Sent>(m.answer("0badf00d-21", "done"))
        assertEquals("done", tasks.get("0badf00d-21")!!.state)
        assertEquals(AnswerResult.InvalidMove("done", "accepted"), m.answer("0badf00d-21", "accepted"))
        assertEquals(AnswerResult.UnknownTask, m.answer("00000001-1", "accepted"))
    }

    @Test
    fun theRadiosAirtimeRefusalIsShownAndTheMessageIsSentAgainLater() = runUnit {
        val m = messenger(refusalDelays = listOf(300))
        m.start()
        connectRadio()
        m.submit(found)
        eventually(10_000, "first send") { radio.sentPrivateApp().size == 1 }
        radio.injectRoutingError(requestId = radio.sentPrivateApp().single().id, error = Routing.Error.DUTY_CYCLE_LIMIT)
        eventually(5_000, "refusal shown") { messages.all.single().state == MessageState.REFUSED_AIRTIME }
        val row = messages.all.single()
        assertTrue(row.detail!!.contains("airtime"), row.detail)
        assertTrue(row.retryAtMs!! > System.currentTimeMillis() - 1_000)
        assertEquals(1L, stats[StatNames.TX_REFUSED_AIRTIME])
        eventually(10_000, "sent again") { radio.sentPrivateApp().size == 2 }
        assertContentEquals(radio.sentPrivateApp()[0].decoded!!.payload.toByteArray(), radio.sentPrivateApp()[1].decoded!!.payload.toByteArray())
    }

    @Test
    fun aRoutingAckMarksTheMessageAndTaskDelivered() = runUnit {
        val m = messenger()
        m.start()
        connectRadio()
        m.submit(found)
        eventually(10_000, "sent") { radio.sentPrivateApp().size == 1 }
        radio.injectRoutingAck(requestId = radio.sentPrivateApp().single().id, fromNode = other.toInt())
        eventually(5_000, "delivered") { messages.all.single().state == MessageState.DELIVERED && tasks.get("a1b2c3d4-7")!!.meshDelivered }
        assertEquals(1L, stats[StatNames.TX_DELIVERED])
    }

    @Test
    fun aSentTaskWithNoAnswerBecomesDeliveryUnknown() = runUnit {
        val m = messenger(timeoutMs = { 400 })
        m.start()
        connectRadio()
        m.submit(found)
        eventually(10_000, "sent") { radio.sentPrivateApp().size == 1 }
        eventually(10_000, "delivery unknown") { tasks.get("a1b2c3d4-7")?.state == "delivery_unknown" }
    }

    @Test
    fun anAckBeforeTheReportWaitsAsUnknownTask() = runUnit {
        val m = messenger()
        m.start()
        connectRadio()
        val ack = RescueCodec.encode(mapOf("ack" to mapOf("ref" to 21, "node" to "!0badf00d", "status" to "accepted")))
        radio.injectPacket(privateAppPacket(0x0c0ffee0L, 1, ack))
        eventually(5_000, "placeholder") { tasks.get("0badf00d-21")?.type == "unknown" }
        radio.injectPacket(privateAppPacket(other, 2, RescueCodec.encode(found + ("id" to 21))))
        eventually(5_000, "resolved") { tasks.get("0badf00d-21")?.type == "found" }
        assertEquals("accepted", tasks.get("0badf00d-21")!!.state)
    }

    @Test
    fun anInvalidMessageIsRejectedNotQueued() = runUnit {
        val m = messenger()
        m.start()
        val r = m.submit(mapOf("found" to mapOf("age" to "dinosaur")))
        assertIs<SubmitResult.Rejected>(r)
        assertEquals("invalid_value", r.code)
        assertTrue(messages.all.isEmpty())
        val none = m.submit(mapOf("hello" to 1))
        assertIs<SubmitResult.Rejected>(none)
    }

    @Test
    fun idsAreAssignedForTasksAndStayInRange() = runUnit {
        val m = messenger()
        m.start()
        val ids = (1..5).map { (m.submit(mapOf("found" to mapOf("age" to "adult"))) as SubmitResult.Queued).msgId }
        assertTrue(ids.all { it in 1..16383 })
        assertEquals(ids.size, ids.toSet().size)
        assertEquals((ids[0] % 16383) + 1, ids[1])
    }

    @Test
    fun aRestartKeepsTasksQueueAndDuplicateMemory() = runUnit {
        val oldProcess = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = messenger(oldProcess)
        first.start()
        connectRadio()
        val bytes = RescueCodec.encode(found + ("id" to 21))
        radio.injectPacket(privateAppPacket(other, 1, bytes))
        eventually(5_000, "task") { tasks.get("0badf00d-21") != null }
        // The radio goes away, a message waits in the queue, then the app dies.
        clientFlow.value = null
        attachedFlow.value = null
        assertIs<SubmitResult.Queued>(first.submit(mapOf("text" to mapOf("text" to "still waiting"))))
        oldProcess.cancel()

        // New process: same database, fresh Messenger.
        val second = messenger()
        second.start()
        connectRadio()
        eventually(10_000, "waiting message sent after restart") { radio.sentPrivateApp().size == 1 }
        radio.injectPacket(privateAppPacket(other, 2, bytes))
        eventually(5_000, "duplicate recognised after restart") { stats[StatNames.RX_DUPLICATES] == 1L }
        assertEquals(1, tasks.all.size)
    }

    @Test
    fun theAirtimeBudgetHoldsRoutineTrafficButNeverAMayday() = runUnit {
        // SHORT_FAST: a 22 byte report is about 50 ms on air. A 1.5 s window at 10 percent is 150 ms,
        // 120 ms for routine traffic and 150 ms with the Mayday reserve. So two reports fit, the third waits.
        val budget = AirtimeBudget(Airtime(ModemPreset.SHORT_FAST), Region.EU_868, windowMs = 1_500)
        val m = messenger(budget = budget)
        m.start()
        connectRadio()
        for (id in 1..3) m.submit(found + ("id" to id))
        eventually(10_000, "two sent") { radio.sentPrivateApp().size >= 2 }
        eventually(10_000, "third is held") { messages.all.any { it.state == MessageState.HELD } }
        val held = messages.all.single { it.state == MessageState.HELD }
        assertTrue(held.detail!!.contains("airtime", ignoreCase = true), held.detail)
        assertTrue(held.retryAtMs!! > 0)
        assertEquals(2, radio.sentPrivateApp().size)
        // The Mayday jumps the queue and is not held by the routine limit.
        val may = m.submit(mapOf("mayday" to mapOf("reason" to "injured")))
        assertIs<SubmitResult.Queued>(may)
        eventually(10_000, "all four sent in the end") { radio.sentPrivateApp().size == 4 }
        val types = radio.sentPrivateApp().map { RescueCodec.messageType(RescueCodec.decodeAny(it.decoded!!.payload.toByteArray())) }
        assertTrue(types.indexOf("mayday") <= 2, "mayday did not wait behind the held report: $types")
        assertTrue(messages.all.none { it.state == MessageState.HELD || it.state == MessageState.QUEUED })
    }

    @Test
    fun anAcceptThatWasNotSentYetSurvivesARestart() = runUnit {
        val oldProcess = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = messenger(oldProcess)
        first.start()
        connectRadio()
        radio.injectPacket(privateAppPacket(other, 1, RescueCodec.encode(found + ("id" to 21))))
        eventually(5_000, "task") { tasks.get("0badf00d-21") != null }
        // Radio goes away, the person taps Accept (queued), the app dies.
        clientFlow.value = null
        attachedFlow.value = null
        assertIs<AnswerResult.Sent>(first.answer("0badf00d-21", "accepted"))
        assertEquals("accepted", tasks.get("0badf00d-21")!!.state)
        oldProcess.cancel()

        val second = messenger()
        second.start()
        connectRadio()
        eventually(10_000, "the Ack goes out after the restart") { radio.sentPrivateApp().size == 1 }
        assertEquals("accepted", tasks.get("0badf00d-21")!!.state)
        assertEquals("accepted", tasks.get("0badf00d-21")!!.ownStatus)
    }

    @Test
    fun airtimeAlreadyUsedIsRememberedAfterARestart() = runUnit {
        val oldProcess = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = messenger(oldProcess)
        first.start()
        connectRadio()
        first.submit(found)
        eventually(10_000, "sent") { radio.sentPrivateApp().size == 1 }
        val used = first.airtime.value.usedMs
        eventually(3_000, "airtime shown") { first.airtime.value.usedMs > 0 }
        oldProcess.cancel()
        val second = messenger()
        second.start()
        kotlinx.coroutines.delay(1_300) // tick loop publishes once a second
        assertTrue(second.airtime.value.usedMs >= first.airtime.value.usedMs * 0.99, "second ${second.airtime.value} first ${first.airtime.value} used=$used")
    }

    private val publicDefault = teamChannel(0, name = "", psk = byteArrayOf(1))

    @Test
    fun reportsGoOnTheNamedChannelNotOnChannelZero() = runUnit {
        val m = messenger()
        m.start()
        connectRadio(listOf(publicDefault, teamChannel(2, name = "RescueCodec")))
        m.submit(found)
        eventually(10_000, "packet sent") { radio.sentPrivateApp().size == 1 }
        assertEquals(2, radio.sentPrivateApp().single().channel)
        assertEquals(2, (m.channel.value as org.rescuecodec.app.core.ChannelChoice.Ready).index)
    }

    @Test
    fun theOnlyPrivateChannelIsUsedEvenWithAnotherName() = runUnit {
        val m = messenger()
        m.start()
        connectRadio(listOf(publicDefault, teamChannel(1, name = "Bergwacht")))
        m.submit(found)
        eventually(10_000, "packet sent") { radio.sentPrivateApp().size == 1 }
        assertEquals(1, radio.sentPrivateApp().single().channel)
        assertTrue((m.channel.value as org.rescuecodec.app.core.ChannelChoice.Ready).auto)
    }

    @Test
    fun noPrivateChannelMeansNothingIsSentAndTheOutboxSaysWhy() = runUnit {
        val m = messenger()
        m.start()
        connectRadio(listOf(publicDefault))
        val r = m.submit(mapOf("pos" to mapOf("lat" to 50.7, "lon" to 7.2), "mayday" to mapOf("reason" to "lost")))
        assertIs<SubmitResult.Queued>(r)
        eventually(5_000, "problem shown") { messages.all.single().detail?.contains("No private RescueCodec channel") == true }
        assertIs<org.rescuecodec.app.core.ChannelChoice.Problem>(m.channel.value)
        delay(700)
        assertEquals(0, radio.sentPrivateApp().size, "nothing may go out on the public channel")
        assertEquals(MessageState.QUEUED, messages.all.single().state)
    }

    @Test
    fun aChannelPickedInTheSettingsIsUsedAndAPublicOneIsRefused() = runUnit {
        val m = messenger()
        m.start()
        connectRadio(listOf(teamChannel(0, name = "A"), teamChannel(1, name = "B", psk = ByteArray(16) { 9 })))
        m.submit(found)
        eventually(5_000, "ambiguous shown") { messages.all.single().detail?.contains("Several private channels") == true }
        assertEquals(0, radio.sentPrivateApp().size)
        settings.channelIndex = 1
        m.refreshChannel()
        eventually(10_000, "packet sent") { radio.sentPrivateApp().size == 1 }
        assertEquals(1, radio.sentPrivateApp().single().channel)
        settings.channelIndex = 0
        m.refreshChannel()
        assertEquals(org.rescuecodec.app.core.ChannelChoice.Ready(0, "A", false), m.channel.value)
    }

    @Test
    fun helpersRoundTrip() {
        assertEquals("Trupp 3", Messenger.teamLabelFor("  Trupp 3 "))
        assertEquals("Überlänge1", Messenger.teamLabelFor("Überlänge12"))
        assertTrue(Messenger.teamLabelFor("ÜÜÜÜÜÜÜÜÜÜ")!!.toByteArray().size <= 12)
        assertNull(Messenger.teamLabelFor("   "))
        assertEquals("0badf00d-21", Messenger.parseKey("0badf00d-21").toString())
        assertNull(Messenger.parseKey("nonsense"))
        assertEquals(MeshPacket.Priority.ALERT, Messenger.meshPriority("critical"))
        assertEquals(MeshPacket.Priority.HIGH, Messenger.meshPriority("urgent"))
        assertEquals(MeshPacket.Priority.RELIABLE, Messenger.meshPriority("normal"))
        assertEquals(MeshPacket.Priority.BACKGROUND, Messenger.meshPriority("routine"))
        Json.stringify(mapOf("a" to 1)) // keeps the import honest
    }
}

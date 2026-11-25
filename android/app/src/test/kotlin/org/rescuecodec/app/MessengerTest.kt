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
import org.rescuecodec.app.core.MaydayAlerts
import org.rescuecodec.app.core.MemoryKeyValueStore
import org.rescuecodec.app.core.MeshText
import org.rescuecodec.app.core.Messenger
import org.rescuecodec.app.core.Readable
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
        alerts: MaydayAlerts = MaydayAlerts.NONE,
    ) = Messenger(
        useScope, clientFlow, attachedFlow, messages, tasks, stats, settings, gapMs = gap,
        refusalDelaysMs = refusalDelays, taskTimeoutMs = timeoutMs, log = { println("[messenger] $it") }, alerts = alerts, budget = budget,
    )

    /** Records what the Mayday notification would do. */
    private class RecordingAlerts : MaydayAlerts {
        val shown = java.util.concurrent.CopyOnWriteArrayList<Triple<String, Long, String?>>()
        val cleared = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun show(key: String, from: Long, reason: String?) { shown.add(Triple(key, from, reason)) }
        override fun clear(key: String) { cleared.add(key) }
    }

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
        eventually(10_000, "three sent") { radio.sentMessages().size == 3 }
        // The text goes out as a normal Meshtastic text (port 1), but through the same queue: still last.
        val sent = radio.sentMessages()
        val order = sent.map {
            if (it.decoded!!.portnum == PortNum.TEXT_MESSAGE_APP) "plain text"
            else RescueCodec.messageType(RescueCodec.decodeAny(it.decoded!!.payload.toByteArray()))
        }
        assertEquals(listOf("mayday", "found", "plain text"), order)
        assertEquals(MeshPacket.Priority.ALERT, sent[0].priority)
        assertEquals(MeshPacket.Priority.RELIABLE, sent[2].priority, "text is normal priority")
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
    fun aMaydayFromAnotherRadioAlertsOnceAndTheAlertGoesWhenItIsDone() = runUnit {
        val alerts = RecordingAlerts()
        val m = messenger(alerts = alerts)
        m.start()
        connectRadio()
        val may = RescueCodec.encode(mapOf("id" to 9, "pos" to mapOf("lat" to 50.7, "lon" to 7.2), "mayday" to mapOf("reason" to "injured")))
        radio.injectPacket(privateAppPacket(other, 1, may))
        eventually(5_000, "alerted") { alerts.shown.size == 1 }
        assertEquals(Triple("0badf00d-9", other, "injured"), alerts.shown.single())
        // The mesh delivers it again: no second alarm.
        radio.injectPacket(privateAppPacket(other, 2, may))
        eventually(5_000, "duplicate counted") { stats[StatNames.RX_DUPLICATES] == 1L }
        // A Found report rings nothing.
        radio.injectPacket(privateAppPacket(other, 3, RescueCodec.encode(found + ("id" to 10))))
        eventually(5_000, "found task") { tasks.get("0badf00d-10") != null }
        assertEquals(1, alerts.shown.size)
        assertTrue(alerts.cleared.isEmpty())
        // A third team reports it done: the notification goes away.
        val done = RescueCodec.encode(mapOf("ack" to mapOf("ref" to 9, "node" to "!0badf00d", "status" to "done")))
        radio.injectPacket(privateAppPacket(0x12345678L, 4, done))
        eventually(5_000, "cleared") { "0badf00d-9" in alerts.cleared }
        assertEquals(1, alerts.shown.size)
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
        eventually(10_000, "waiting message sent after restart") { radio.sentText().size == 1 }
        assertEquals("still waiting", radio.sentText().single().decoded!!.payload.utf8(), "still a plain text after the restart")
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

    // ------------------------------------------------------------ review findings

    @Test
    fun portTwoFiftySixPacketsFromOtherChannelsAreDroppedAndCounted() = runUnit {
        val m = messenger()
        m.start()
        connectRadio(listOf(publicDefault, teamChannel(1, name = "RescueCodec")))
        val bytes = RescueCodec.encode(found + ("id" to 21))
        radio.injectPacket(privateAppPacket(other, 1, bytes, channel = 0))          // public LongFast, another team
        radio.injectPacket(privateAppPacket(other, 2, bytes, channel = 3))
        eventually(5_000, "both counted") { stats[StatNames.RX_OTHER_CHANNEL] == 2L }
        assertTrue(tasks.all.isEmpty() && messages.all.isEmpty(), "nothing from other channels is stored")
        radio.injectPacket(privateAppPacket(other, 3, bytes, channel = 1))          // ours
        eventually(5_000, "task from our channel") { tasks.get("0badf00d-21") != null }
        assertEquals(2L, stats[StatNames.RX_OTHER_CHANNEL])
    }

    @Test
    fun theSameTextTwiceIsTwoMessages() = runUnit {
        settings.plainTextAsMeshtastic = false // RescueCodec texts on port 256
        val m = messenger()
        m.start()
        connectRadio()
        val a = m.submit(mapOf("text" to mapOf("text" to "Hilfe"))) as SubmitResult.Queued
        val b = m.submit(mapOf("text" to mapOf("text" to "Hilfe"))) as SubmitResult.Queued
        assertTrue(a.msgId != 0 && b.msgId != 0 && a.msgId != b.msgId, "every message but an Ack has its own id")
        eventually(10_000, "both sent") { radio.sentPrivateApp().size == 2 }
        val sent = radio.sentPrivateApp().map { it.decoded!!.payload.toByteArray().toList() }
        assertTrue(sent[0] != sent[1], "different bytes, so a receiver does not drop the second one")
        // and the receiving side keeps both
        radio.injectPacket(privateAppPacket(other, 1, a.bytes))
        radio.injectPacket(privateAppPacket(other, 2, b.bytes))
        eventually(5_000, "two received") { messages.all.count { it.dir == "in" } == 2 }
        assertEquals(0L, stats[StatNames.RX_DUPLICATES])
        // An ack stays id-less: sending the same accept twice is a repeat on purpose.
        assertIs<SubmitResult.Queued>(m.submit(mapOf("ack" to mapOf("ref" to 3, "node" to "!0badf00d", "status" to "accepted"))))
        assertEquals(0, (messages.all.last { it.type == "ack" }.msgId))
    }

    private suspend fun dropLinkAndReconnect(): FakeRadioTransport {
        radio.simulateError(java.io.IOException("link lost"), recoverable = false)
        clientFlow.value = null
        connectRadio()
        return radio
    }

    @Test
    fun aMessageHandedToADeadLinkIsSentOnceMoreAfterTheReconnect() = runUnit {
        val m = messenger()
        m.start()
        connectRadio()
        m.submit(found)
        eventually(10_000, "sent") { radio.sentPrivateApp().size == 1 && messages.all.single().state == MessageState.SENT }
        val first = radio.sentPrivateApp().single().decoded!!.payload.toByteArray()
        val second = dropLinkAndReconnect()
        eventually(15_000, "the same bytes go out once on the new link") { second.sentPrivateApp().size == 1 }
        assertContentEquals(first, second.sentPrivateApp().single().decoded!!.payload.toByteArray())
        assertEquals(1, Messenger.linkResendsOf(messages.all.single().attempts))
        // A second drop: now it is "not confirmed", no third copy.
        eventually(10_000, "sent again") { messages.all.single().state == MessageState.SENT }
        val third = dropLinkAndReconnect()
        eventually(15_000, "not confirmed") { messages.all.single().state == MessageState.NOT_CONFIRMED }
        delay(800)
        assertEquals(0, third.sentPrivateApp().size, "no third send")
        assertEquals(1L, stats[StatNames.TX_NOT_CONFIRMED])
    }

    @Test
    fun aDeliveredMessageIsNeverResentAfterALinkDrop() = runUnit {
        val m = messenger()
        m.start()
        connectRadio()
        m.submit(found)
        eventually(10_000, "sent") { radio.sentPrivateApp().size == 1 }
        radio.injectRoutingAck(requestId = radio.sentPrivateApp().single().id, fromNode = other.toInt())
        eventually(5_000, "delivered") { messages.all.single().state == MessageState.DELIVERED }
        val second = dropLinkAndReconnect()
        delay(1_000)
        assertEquals(0, second.sentPrivateApp().size)
        assertEquals(MessageState.DELIVERED, messages.all.single().state)
    }

    @Test
    fun anAirtimeRefusalDoesNotUseUpTheLinkResend() = runUnit {
        val m = messenger(refusalDelays = listOf(200))
        m.start()
        connectRadio()
        m.submit(found)
        eventually(10_000, "first send") { radio.sentPrivateApp().size == 1 }
        radio.injectRoutingError(requestId = radio.sentPrivateApp().single().id, error = Routing.Error.DUTY_CYCLE_LIMIT)
        eventually(10_000, "sent again after the refusal") { radio.sentPrivateApp().size == 2 }
        eventually(5_000, "row is sent") { messages.all.single().state == MessageState.SENT }
        assertEquals(1, Messenger.refusalsOf(messages.all.single().attempts))
        val second = dropLinkAndReconnect()
        eventually(15_000, "still gets its link resend") { second.sentPrivateApp().size == 1 }
        assertEquals(1, Messenger.linkResendsOf(messages.all.single().attempts))
    }

    // ----------------------------------------------- normal Meshtastic texts (port 1), spec section 2.1

    /** LongFast (public key) on 0, our team channel RescueCodec on 1. */
    private suspend fun connectTeamRadio() = connectRadio(listOf(publicDefault, teamChannel(1, name = "RescueCodec")))

    @Test
    fun aPlainTextGoesOutAsANormalMeshtasticTextOnTheTeamChannel() = runUnit {
        val m = messenger()
        m.start()
        connectTeamRadio()
        val r = m.submit(mapOf("text" to mapOf("text" to "Treffpunkt jetzt am Gasthaus")))
        assertIs<SubmitResult.Queued>(r)
        assertEquals(0, r.msgId, "a plain text has no RescueCodec id")
        assertContentEquals("Treffpunkt jetzt am Gasthaus".toByteArray(), r.bytes)
        eventually(10_000, "text sent") { radio.sentText().size == 1 }
        val p = radio.sentText().single()
        assertEquals("Treffpunkt jetzt am Gasthaus", p.decoded!!.payload.utf8())
        assertEquals(1, p.channel, "the team channel, never LongFast")
        assertEquals(-1, p.to, "broadcast")
        assertEquals(true, p.want_ack)
        assertEquals(MeshPacket.Priority.RELIABLE, p.priority)
        assertEquals(0, radio.sentPrivateApp().size)
        eventually(5_000, "row is sent") { messages.all.single().state == MessageState.SENT }
        val row = messages.all.single()
        assertTrue(MeshText.isPlain(row))
        assertEquals("text", row.type)
        assertEquals("Message: Treffpunkt jetzt am Gasthaus.", Readable.of(row.json, "en"))
        assertEquals(1L, stats[StatNames.TX_MESSAGES])
        assertEquals(28L, stats[StatNames.TX_BYTES])
    }

    @Test
    fun aPlainTextKeepsItsPriorityAndLeavesThePositionOut() = runUnit {
        val m = messenger()
        m.start()
        connectTeamRadio()
        m.submit(mapOf("priority" to "urgent", "pos" to mapOf("lat" to 50.7, "lon" to 7.2), "text" to mapOf("text" to "Weg gesperrt")))
        eventually(10_000, "text sent") { radio.sentText().size == 1 }
        assertEquals("Weg gesperrt", radio.sentText().single().decoded!!.payload.utf8())
        assertEquals(MeshPacket.Priority.HIGH, radio.sentText().single().priority)
        assertEquals(mapOf("priority" to "urgent", "text" to mapOf("text" to "Weg gesperrt")), Json.parseObject(messages.all.single().json!!))
    }

    @Test
    fun withTheSettingOffATextIsARescueCodecTextOnPort256() = runUnit {
        settings.plainTextAsMeshtastic = false
        val m = messenger()
        m.start()
        connectTeamRadio()
        val r = m.submit(mapOf("text" to mapOf("text" to "Treffpunkt")))
        assertIs<SubmitResult.Queued>(r)
        eventually(10_000, "sent") { radio.sentPrivateApp().size == 1 }
        val decoded = RescueCodec.decodeAny(radio.sentPrivateApp().single().decoded!!.payload.toByteArray())
        assertEquals(mapOf("text" to "Treffpunkt"), decoded["text"])
        assertEquals(0, radio.sentText().size)
        assertEquals("app", messages.all.single().source)
    }

    @Test
    fun reportsAlwaysGoOnPort256() = runUnit {
        assertTrue(settings.plainTextAsMeshtastic, "on by default")
        val m = messenger()
        m.start()
        connectTeamRadio()
        m.submit(found)
        m.submit(mapOf("mayday" to mapOf("reason" to "injured")))
        m.submit(mapOf("hazard" to mapOf("kind" to "fire")))
        eventually(10_000, "three sent") { radio.sentPrivateApp().size == 3 }
        delay(300)
        assertEquals(0, radio.sentText().size)
        assertTrue(messages.all.none { MeshText.isPlain(it) })
    }

    @Test
    fun aTextOnTheTeamChannelIsShownLikeAText() = runUnit {
        val m = messenger()
        m.start()
        connectTeamRadio()
        radio.injectPacket(textPacket(other, 4242, "Wir brauchen Wasser am Gasthaus", channel = 1, hopStart = 3, hopLimit = 1))
        eventually(5_000, "stored") { messages.all.size == 1 }
        val row = messages.all.single()
        assertEquals("in", row.dir)
        assertEquals(other, row.node)
        assertEquals("text", row.type)
        assertEquals(MessageState.RECEIVED, row.state)
        assertTrue(MeshText.isPlain(row), "marked, so a screen can say via Meshtastic")
        assertEquals(4242L, row.packetId)
        assertEquals(-97, row.rssi)
        assertEquals(6.25f, row.snr)
        assertEquals(2, row.hops)
        assertEquals("Nachricht: Wir brauchen Wasser am Gasthaus.", Readable.of(row.json, "de"))
        assertEquals(1L, stats[StatNames.RX_MESSAGES])
        assertTrue(tasks.all.isEmpty(), "a text is never a task")
    }

    @Test
    fun aTextOnAnotherChannelOrToAnotherNodeIsNeverShown() = runUnit {
        val m = messenger()
        m.start()
        connectTeamRadio()
        radio.injectPacket(textPacket(other, 1, "LongFast chat", channel = 0))                          // public channel
        radio.injectPacket(textPacket(other, 2, "Another channel", channel = 3))
        radio.injectPacket(textPacket(other, 3, "For someone else", to = 0x12345678L, channel = 1))      // a DM, not to us
        radio.injectPacket(textPacket(me, 4, "Our own echo", channel = 1))
        radio.injectPacket(textPacket(other, 5, "Team", channel = 1))
        eventually(5_000, "the team text") { messages.all.size == 1 }
        delay(300)
        assertEquals(listOf("Team"), messages.all.map { (Json.parseObject(it.json!!)["text"] as Map<*, *>)["text"] })
        assertEquals(0L, stats[StatNames.RX_OTHER_CHANNEL], "LongFast chat is not even counted")
    }

    @Test
    fun aDirectMessageToUsIsShownOnAnyChannel() = runUnit {
        val m = messenger()
        m.start()
        connectTeamRadio()
        radio.injectPacket(textPacket(other, 7, "Kommst du?", to = me, channel = 0))
        eventually(5_000, "stored") { messages.all.size == 1 }
        assertTrue(MeshText.isPlain(messages.all.single()))
    }

    @Test
    fun aTextIsShownOnceAndReadableCopiesAreSkipped() = runUnit {
        val m = messenger()
        m.start()
        connectTeamRadio()
        // A gateway with --text-over-air posts a readable copy of a report it also sent on port 256.
        radio.injectPacket(privateAppPacket(0x0c0ffee0L, 9, RescueCodec.encode(found + ("id" to 31)), channel = 1))
        radio.injectPacket(textPacket(0x0c0ffee0L, 10, MeshText.COPY_PREFIX + "Person found: child, conscious.", channel = 1))
        eventually(5_000, "report stored, copy counted as a duplicate") { messages.all.size == 1 && stats[StatNames.RX_DUPLICATES] == 1L }
        assertEquals("found", messages.all.single().type)
        // The same packet twice is one message (the SDK drops a repeat it still remembers; after a reconnect we do,
        // see theTextDuplicateMemorySurvivesARestart). The same words in a new packet are a new message.
        radio.injectPacket(textPacket(other, 500, "Hilfe", channel = 1))
        radio.injectPacket(textPacket(other, 500, "Hilfe", channel = 1))
        radio.injectPacket(textPacket(other, 501, "Hilfe", channel = 1))
        eventually(5_000, "two texts") { messages.all.count { MeshText.isPlain(it) } == 2 }
        delay(300)
        assertEquals(listOf(500L, 501L), messages.all.filter { MeshText.isPlain(it) }.map { it.packetId })
    }

    @Test
    fun theTextDuplicateMemorySurvivesARestart() = runUnit {
        val oldProcess = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = messenger(oldProcess)
        first.start()
        connectTeamRadio()
        radio.injectPacket(textPacket(other, 900, "Treffpunkt Gasthaus", channel = 1))
        eventually(5_000, "stored") { messages.all.size == 1 }
        clientFlow.value = null
        attachedFlow.value = null
        oldProcess.cancel()

        val second = messenger()
        second.start()
        connectTeamRadio()
        radio.injectPacket(textPacket(other, 900, "Treffpunkt Gasthaus", channel = 1))   // the radio hands it over again
        eventually(5_000, "duplicate") { stats[StatNames.RX_DUPLICATES] == 1L }
        assertEquals(1, messages.all.size)
    }
}

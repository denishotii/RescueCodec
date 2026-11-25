// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.meshtastic.proto.PortNum
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import org.rescuecodec.app.core.AnswerResult
import org.rescuecodec.app.core.AppSettings
import org.rescuecodec.app.core.ChatId
import org.rescuecodec.app.core.ChatList
import org.rescuecodec.app.core.MemoryKeyValueStore
import org.rescuecodec.app.core.Messenger
import org.rescuecodec.app.core.NodeDirectory
import org.rescuecodec.app.core.NodeName
import org.rescuecodec.app.core.SubmitResult
import org.rescuecodec.app.data.MessageState
import org.rescuecodec.app.data.StatNames
import org.rescuecodec.codec.RescueCodec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Several channels and direct messages: receive, send, answers, the chat list. */
class ChannelsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val messages = FakeMessageDao()
    private val tasks = FakeTaskDao()
    private val stats = FakeStatDao()
    private val settings = AppSettings(MemoryKeyValueStore())
    private val nodes = NodeDirectory()
    private val clientFlow = MutableStateFlow<RadioClient?>(null)
    private val attachedFlow = MutableStateFlow<RadioClient?>(null)
    private val me = 0xa1b2c3d4L
    private val other = 0x0badf00dL
    private lateinit var radio: FakeRadioTransport

    /** The radio of the field test: public LongFast, RescueCodec (team), draeger-srs (the sponsor's channel). */
    private val longFast = teamChannel(0, name = "", psk = byteArrayOf(1))
    private val rescue = teamChannel(1, name = "RescueCodec")
    private val draeger = teamChannel(2, name = "draeger-srs", psk = ByteArray(16) { 7 })

    private fun messenger() = Messenger(
        scope, clientFlow, attachedFlow, messages, tasks, stats, settings, gapMs = 0, refusalDelaysMs = listOf(150),
        log = { println("[messenger] $it") }, nodes = nodes, parkMs = 200,
    )

    private suspend fun connect(channels: List<org.meshtastic.proto.Channel> = listOf(longFast, rescue, draeger)) {
        radio = fakeRadio("fake:me", me.toInt(), channels)
        val client = RadioClient.Builder().transport(radio).storage(InMemoryStorageProvider()).build()
        attachedFlow.value = client
        delay(200)
        client.connect()
        eventually(5_000, "own node known") { Messenger.nodeOf(client) == me }
        clientFlow.value = client
        eventually(5_000, "channels known") { messengerUnderTest.radioChannels.value?.size == channels.size }
    }

    private lateinit var messengerUnderTest: Messenger

    private fun run(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        messengerUnderTest = messenger()
        messengerUnderTest.start()
        block()
    }

    @AfterTest
    fun tearDown() { scope.cancel() }

    private val found = mapOf("id" to 21, "pos" to mapOf("lat" to 50.69, "lon" to 7.21), "found" to mapOf("age" to "child"))

    @Test
    fun messagesOnTwoPrivateChannelsLandInTwoChats() = run {
        connect()
        radio.injectPacket(textPacket(other, 1, "Treffpunkt Gasthaus", channel = 1))
        radio.injectPacket(privateAppPacket(other, 2, RescueCodec.encode(found), channel = 2))
        eventually(5_000, "both stored") { messages.all.size == 2 }
        val byChannel = messages.all.associateBy { it.channelName }
        assertEquals(1, byChannel["RescueCodec"]!!.channelIndex)
        assertEquals("text", byChannel["RescueCodec"]!!.type)
        assertEquals("found", byChannel["draeger-srs"]!!.type)
        assertEquals(2, byChannel["draeger-srs"]!!.channelIndex)
        assertTrue(messages.all.none { it.channelPublic || it.peer != null })
        assertEquals("draeger-srs", tasks.get("0badf00d-21")!!.channelName, "the task knows its channel")

        val list = ChatList.build(messages.all.sortedByDescending { it.id }, messengerUnderTest.radioChannels.value, "RescueCodec", false, emptyMap(), emptyMap(), emptySet())
        assertEquals(listOf(ChatId.Channel("RescueCodec"), ChatId.Channel("draeger-srs")), list.map { it.id }, "team pinned on top, public hidden")
        assertEquals(listOf(1, 1), list.map { it.unread })
    }

    @Test
    fun thePublicChannelIsIgnoredUnlessSwitchedOn() = run {
        connect()
        radio.injectPacket(textPacket(other, 1, "LongFast chat", channel = 0))
        radio.injectPacket(privateAppPacket(other, 2, RescueCodec.encode(found), channel = 0))
        eventually(5_000, "report counted as other channel") { stats[StatNames.RX_OTHER_CHANNEL] == 1L }
        delay(200)
        assertTrue(messages.all.isEmpty(), "nothing from the hidden public channel")

        settings.showPublicChannel = true
        radio.injectPacket(textPacket(other, 3, "LongFast chat", channel = 0))
        eventually(5_000, "public text stored") { messages.all.size == 1 }
        val row = messages.all.single()
        assertEquals("LongFast", row.channelName, "an unnamed primary shows as the preset name")
        assertTrue(row.channelPublic)
        val list = ChatList.build(messages.all, messengerUnderTest.radioChannels.value, "RescueCodec", true, emptyMap(), emptyMap(), emptySet())
        assertEquals(ChatId.Channel("LongFast"), list.last().id)
        assertTrue(list.last().isPublic)
    }

    @Test
    fun aDirectMessageIsItsOwnChatWithTheSendersName() = run {
        connect()
        nodes.update(listOf(NodeName(other, "Anna Trupp 3", "AN3", 1)))
        radio.injectPacket(textPacket(other, 7, "Kommst du?", to = me, channel = 0))
        eventually(5_000, "stored") { messages.all.size == 1 }
        val row = messages.all.single()
        assertEquals(other, row.peer)
        assertEquals(ChatId.Direct(other), ChatId.of(row, "RescueCodec"))
        val list = ChatList.build(messages.all, messengerUnderTest.radioChannels.value, "RescueCodec", false, nodes.nodes.value, emptyMap(), emptySet())
        val dm = list.single { it.id is ChatId.Direct }
        assertEquals("Anna Trupp 3", dm.title)
        assertEquals("AN3", dm.shortName)
        assertEquals(1, dm.unread)
        // The answer goes back as a direct message: port 1, to Anna, on the channel the radio heard her on.
        val r = messengerUnderTest.submit(mapOf("text" to mapOf("text" to "Ja, 10 min")), peer = other)
        assertIs<SubmitResult.Queued>(r)
        eventually(10_000, "sent") { radio.sentText().size == 1 }
        val p = radio.sentText().single()
        assertEquals(other.toInt(), p.to)
        assertEquals(1, p.channel)
        assertEquals("Ja, 10 min", p.decoded!!.payload.utf8())
        eventually(5_000, "row has its channel") { messages.all.last().channelName == "RescueCodec" }
        assertEquals(other, messages.all.last().peer)
        // A report in a direct message is refused: reports go on a channel.
        assertIs<SubmitResult.Rejected>(messengerUnderTest.submit(found, peer = other))
    }

    @Test
    fun anAnswerGoesOutOnTheChannelOfTheReport() = run {
        connect()
        radio.injectPacket(privateAppPacket(other, 1, RescueCodec.encode(found), channel = 2))
        eventually(5_000, "task") { tasks.get("0badf00d-21") != null }
        val r = messengerUnderTest.answer("0badf00d-21", "accepted")
        assertIs<AnswerResult.Sent>(r)
        eventually(10_000, "ack sent") { radio.sentPrivateApp().size == 1 }
        val p = radio.sentPrivateApp().single()
        assertEquals(2, p.channel, "on draeger-srs, where the report came from, not on the team channel")
        assertEquals(-1, p.to)
        val ack = RescueCodec.decodeAny(p.decoded!!.payload.toByteArray())["ack"] as Map<*, *>
        assertEquals("accepted", ack["status"])
        eventually(5_000, "row has its channel") { messages.all.single { it.dir == "out" }.channelName == "draeger-srs" }
    }

    @Test
    fun theComposerSendsOnTheOpenChatsChannel() = run {
        connect()
        messengerUnderTest.submit(mapOf("text" to mapOf("text" to "Hallo Dräger")), channel = "draeger-srs")
        messengerUnderTest.submit(found, channel = "draeger-srs")
        messengerUnderTest.submit(mapOf("text" to mapOf("text" to "Team")))
        eventually(10_000, "three sent") { radio.sentMessages().size == 3 }
        val sent = radio.sentMessages()
        val text = sent.single { it.decoded!!.portnum == PortNum.TEXT_MESSAGE_APP && it.decoded!!.payload.utf8() == "Hallo Dräger" }
        assertEquals(2, text.channel)
        assertEquals(2, sent.single { it.decoded!!.portnum == PortNum.PRIVATE_APP }.channel)
        assertEquals(1, sent.single { it.decoded!!.payload.utf8() == "Team" }.channel, "no channel: the team channel")
        eventually(5_000, "team row named") { messages.all.single { it.json!!.contains("\"Team\"") }.channelName == "RescueCodec" }
        assertEquals("draeger-srs", tasks.get("a1b2c3d4-21")!!.channelName)
    }

    @Test
    fun aReportNeverGoesOnThePublicChannel() = run {
        connect()
        val r = messengerUnderTest.submit(found, channel = "LongFast")
        assertIs<SubmitResult.Rejected>(r)
        assertEquals("public_channel", r.code)
        // A text on the public channel goes as a plain Meshtastic text, without position.
        messengerUnderTest.submit(mapOf("pos" to mapOf("lat" to 50.7, "lon" to 7.2), "text" to mapOf("text" to "Hallo")), channel = "LongFast")
        eventually(10_000, "sent") { radio.sentText().size == 1 }
        assertEquals(0, radio.sentText().single().channel)
        assertEquals("Hallo", radio.sentText().single().decoded!!.payload.utf8())
        assertTrue(radio.sentPrivateApp().isEmpty())
    }

    @Test
    fun aMessageForAChannelThatIsNotOnTheRadioWaitsAndDoesNotBlockTheOthers() = run {
        connect()
        val waiting = messengerUnderTest.submit(found, channel = "Bergwacht", source = "app") as SubmitResult.Queued
        messengerUnderTest.submit(mapOf("text" to mapOf("text" to "Team")))
        eventually(10_000, "team text sent") { radio.sentText().size == 1 }
        eventually(5_000, "reason shown") { messages.get(waiting.rowId)!!.detail?.contains("Bergwacht") == true }
        assertEquals(MessageState.QUEUED, messages.get(waiting.rowId)!!.state)
        assertTrue(radio.sentPrivateApp().isEmpty(), "never on another channel")
        assertNull(messages.get(waiting.rowId)!!.channelIndex)
    }
}

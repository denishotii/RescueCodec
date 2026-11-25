// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import org.rescuecodec.app.core.ChatId
import org.rescuecodec.app.core.ChatList
import org.rescuecodec.app.core.ChatReads
import org.rescuecodec.app.core.MemoryKeyValueStore
import org.rescuecodec.app.core.NodeDirectory
import org.rescuecodec.app.core.NodeName
import org.rescuecodec.app.core.RadioChannel
import org.rescuecodec.app.data.MessageEntity
import org.rescuecodec.app.data.MessageState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatListTest {
    private val radio = listOf(
        RadioChannel(0, "LongFast", isPublic = true),
        RadioChannel(1, "MeshMeister", isPublic = false),
        RadioChannel(2, "draeger-srs", isPublic = false),
        RadioChannel(3, "RescueCodec", isPublic = false),
    )

    private fun row(id: Long, channel: String?, peer: Long? = null, dir: String = "in", type: String = "text") = MessageEntity(
        id = id, dir = dir, node = peer ?: 2, type = type, bytes = byteArrayOf(1), json = "{}", atMs = id * 1000,
        state = if (dir == "in") MessageState.RECEIVED else MessageState.SENT, channelName = channel, peer = peer,
    )

    @Test
    fun teamOnTopThenChannelsThenDirectThenPublicWhenOn() {
        val rows = listOf(
            row(6, "LongFast"), row(5, null, peer = 0x77), row(4, "draeger-srs"), row(3, "MeshMeister"),
            row(2, null, dir = "out"), row(1, "RescueCodec", type = "ack"),
        )
        val names = mapOf(0x77L to NodeName(0x77, "Anna", "AN"))
        val off = ChatList.build(rows, radio, "RescueCodec", false, names, emptyMap(), setOf("MeshMeister"))
        assertEquals(
            listOf(ChatId.Channel("RescueCodec"), ChatId.Channel("draeger-srs"), ChatId.Channel("MeshMeister"), ChatId.Direct(0x77)),
            off.map { it.id },
        )
        assertEquals(2L, off[0].last?.id, "a waiting message without a channel is in the team chat")
        assertEquals(0, off[0].unread, "my own message and an answer are not unread")
        assertTrue(off[2].mayday && !off[1].mayday)
        assertEquals("Anna", off[3].title)
        val on = ChatList.build(rows, radio, "RescueCodec", true, names, mapOf("ch:draeger-srs" to 4L), emptySet())
        assertEquals(ChatId.Channel("LongFast"), on.last().id)
        assertEquals(0, on.single { it.id == ChatId.Channel("draeger-srs") }.unread, "read up to row 4")
    }

    @Test
    fun withoutARadioTheChannelsComeFromTheStoredMessages() {
        val list = ChatList.build(listOf(row(2, "draeger-srs"), row(1, "RescueCodec")), null, "RescueCodec", false, emptyMap(), emptyMap(), emptySet())
        assertEquals(listOf("RescueCodec", "draeger-srs"), list.map { (it.id as ChatId.Channel).name })
        assertEquals(ChatId.Channel("RescueCodec"), ChatList.build(emptyList(), null, "RescueCodec", false, emptyMap(), emptyMap(), emptySet()).single().id)
    }

    @Test
    fun chatKeysRoundTripAndNamesAndReadsAreKept() {
        for (id in listOf(ChatId.Channel("draeger-srs"), ChatId.Direct(0xa1b2c3d4L))) assertEquals(id, ChatId.parse(id.key))
        assertNull(ChatId.parse("x"))
        val store = MemoryKeyValueStore()
        NodeDirectory(store).update(listOf(NodeName(0xa1b2c3d4L, "Trupp 3 Anna", "T3A", 2)))
        val again = NodeDirectory(store)
        assertEquals("Trupp 3 Anna", again.label(0xa1b2c3d4L))
        assertEquals(2, again.channelOf(0xa1b2c3d4L))
        ChatReads(store).markRead("ch:RescueCodec", 42)
        assertEquals(42L, ChatReads(store).read.value["ch:RescueCodec"])
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.rescuecodec.app.data.AppDatabase
import org.rescuecodec.app.data.MessageEntity
import org.rescuecodec.app.data.MessageState
import org.rescuecodec.app.data.StatNames
import org.rescuecodec.app.data.TaskEntity
import org.rescuecodec.app.data.add
import kotlin.test.assertEquals

/** The real Room database on the emulator (in memory). */
@RunWith(AndroidJUnit4::class)
class RoomTest {
    private lateinit var db: AppDatabase

    @Before
    fun open() {
        db = AppDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After
    fun close() = db.close()

    private fun row(state: String, at: Long, dir: String = "out", json: String? = "{\"text\":{\"text\":\"x\"}}") =
        MessageEntity(dir = dir, node = 1, type = "text", bytes = byteArrayOf(1, 2), json = json, atMs = at, state = state)

    @Test
    fun messagesWaitingAndReplay() = runBlocking {
        val dao = db.messages()
        val a = dao.insert(row(MessageState.QUEUED, 100))
        dao.insert(row(MessageState.HELD, 110))
        dao.insert(row(MessageState.SENT, 120))
        dao.insert(row(MessageState.RECEIVED, 130, dir = "in"))
        dao.insert(row(MessageState.INVALID, 140, dir = "in", json = null))
        assertEquals(listOf(MessageState.QUEUED, MessageState.HELD), dao.waiting().map { it.state })
        assertEquals(2, dao.observeWaiting().first().size)
        assertEquals(listOf(MessageState.SENT, MessageState.RECEIVED), dao.replaySince(0).map { it.state })
        assertEquals(listOf(MessageState.RECEIVED), dao.replaySince(125).map { it.state })
        dao.update(dao.get(a)!!.copy(state = MessageState.SENT))
        assertEquals(1, dao.waiting().size)
        assertEquals(5, dao.observeRecent(100).first().size)
        assertEquals(140L, dao.observeRecent(1).first().single().atMs)
    }

    @Test
    fun tasksSortMaydayFirstThenOpenBeforeDone() = runBlocking {
        val dao = db.tasks()
        fun t(key: String, type: String, state: String, at: Long) =
            TaskEntity(key = key, node = 1, msgId = 1, type = type, json = null, local = false, state = state, meshDelivered = false, createdMs = at, updatedMs = at)
        dao.upsert(t("a-1", "found", "done", 300))
        dao.upsert(t("b-1", "found", "open", 100))
        dao.upsert(t("c-1", "mayday", "open", 50))
        dao.upsert(t("d-1", "request", "open", 200))
        assertEquals(listOf("c-1", "d-1", "b-1", "a-1"), dao.observeAll().first().map { it.key })
        dao.upsert(dao.get("b-1")!!.copy(state = "accepted"))
        assertEquals("accepted", dao.get("b-1")!!.state)
    }

    @Test
    fun statsCountUp() = runBlocking {
        val dao = db.stats()
        dao.add(StatNames.TX_MESSAGES)
        dao.add(StatNames.TX_MESSAGES)
        dao.add(StatNames.TX_BYTES, 22)
        val m = dao.all().associate { it.name to it.value }
        assertEquals(2L, m[StatNames.TX_MESSAGES])
        assertEquals(22L, m[StatNames.TX_BYTES])
    }
}

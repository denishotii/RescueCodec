// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.rescuecodec.app.data.MessageDao
import org.rescuecodec.app.data.MessageEntity
import org.rescuecodec.app.data.MessageState
import org.rescuecodec.app.data.StatDao
import org.rescuecodec.app.data.StatEntity
import org.rescuecodec.app.data.TaskDao
import org.rescuecodec.app.data.TaskEntity

/** In-memory DAOs for unit tests that run on the JVM without Room. */
class FakeMessageDao : MessageDao {
    private val rows = MutableStateFlow<List<MessageEntity>>(emptyList())
    private var next = 1L
    val all: List<MessageEntity> get() = rows.value

    override suspend fun insert(row: MessageEntity): Long {
        val id = next++
        rows.value = rows.value + row.copy(id = id)
        return id
    }

    override suspend fun update(row: MessageEntity) {
        rows.value = rows.value.map { if (it.id == row.id) row else it }
    }

    override suspend fun get(id: Long) = rows.value.firstOrNull { it.id == id }
    override fun observeRecent(limit: Int): Flow<List<MessageEntity>> = rows.map { it.sortedByDescending { r -> r.id }.take(limit) }
    override fun observeChannel(name: String, team: Boolean, limit: Int): Flow<List<MessageEntity>> = rows.map { l ->
        l.filter { it.peer == null && (it.channelName == name || (team && it.channelName == null)) }.sortedByDescending { r -> r.id }.take(limit)
    }
    override fun observeDirect(peer: Long, limit: Int): Flow<List<MessageEntity>> =
        rows.map { l -> l.filter { it.peer == peer }.sortedByDescending { r -> r.id }.take(limit) }
    override suspend fun waiting() = rows.value.filter { it.dir == "out" && it.state in MessageState.WAITING }.sortedBy { it.id }
    override fun observeLastSignal(): Flow<MessageEntity?> =
        rows.map { l -> l.filter { it.dir == "in" && it.rssi != null && it.rssi != 0 }.maxByOrNull { it.id } }

    override fun observeWaiting(): Flow<List<MessageEntity>> = rows.map { l -> l.filter { it.dir == "out" && it.state in MessageState.WAITING } }
    override suspend fun replaySince(sinceMs: Long) = rows.value.filter {
        it.atMs >= sinceMs && it.json != null && it.state !in setOf("queued", "held", "refused_airtime", "cancelled", "expired", "invalid")
    }.sortedWith(compareBy({ it.atMs }, { it.id }))
    override suspend fun count() = rows.value.size
    override suspend fun clear() { rows.value = emptyList() }
}

class FakeTaskDao : TaskDao {
    private val rows = MutableStateFlow<Map<String, TaskEntity>>(emptyMap())
    val all: List<TaskEntity> get() = rows.value.values.toList()
    override suspend fun upsert(row: TaskEntity) { rows.value = rows.value + (row.key to row) }
    override suspend fun get(key: String) = rows.value[key]
    override fun observeAll(): Flow<List<TaskEntity>> = rows.map { it.values.toList() }
    override suspend fun all() = rows.value.values.sortedByDescending { it.updatedMs }
    override suspend fun clear() { rows.value = emptyMap() }
}

class FakeStatDao : StatDao {
    private val rows = MutableStateFlow<Map<String, Long>>(emptyMap())
    operator fun get(name: String): Long = rows.value[name] ?: 0
    override suspend fun ensure(row: StatEntity): Long {
        if (row.name in rows.value) return -1
        rows.value = rows.value + (row.name to row.value)
        return 1
    }
    override suspend fun addRaw(name: String, delta: Long) { rows.value = rows.value + (name to ((rows.value[name] ?: 0) + delta)) }
    override fun observeAll(): Flow<List<StatEntity>> = rows.map { m -> m.map { StatEntity(it.key, it.value) } }
    override suspend fun all() = rows.value.map { StatEntity(it.key, it.value) }
    override suspend fun clear() { rows.value = emptyMap() }
}

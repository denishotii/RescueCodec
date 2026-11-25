// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(row: MessageEntity): Long

    @Update
    suspend fun update(row: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: Long): MessageEntity?

    @Query("SELECT * FROM messages ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MessageEntity>>

    /**
     * One channel chat, newest first: broadcasts on the channel [name]. With [team] true it also holds the outgoing
     * messages that wait for the team channel (channelName still null).
     */
    @Query("SELECT * FROM messages WHERE peer IS NULL AND (channelName = :name OR (:team AND channelName IS NULL)) ORDER BY id DESC LIMIT :limit")
    fun observeChannel(name: String, team: Boolean, limit: Int): Flow<List<MessageEntity>>

    /** One direct-message chat with node [peer], newest first. */
    @Query("SELECT * FROM messages WHERE peer = :peer ORDER BY id DESC LIMIT :limit")
    fun observeDirect(peer: Long, limit: Int): Flow<List<MessageEntity>>

    /** Messages that still wait to be sent (also used to rebuild the queue after a restart). */
    @Query("SELECT * FROM messages WHERE dir = 'out' AND state IN ('queued','held','refused_airtime') ORDER BY id ASC")
    suspend fun waiting(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE dir = 'out' AND state IN ('queued','held','refused_airtime') ORDER BY id ASC")
    fun observeWaiting(): Flow<List<MessageEntity>>

    /** Everything newer than [sinceMs], oldest first. Replayed into the task tracker after a restart. */
    @Query("SELECT * FROM messages WHERE atMs >= :sinceMs AND json IS NOT NULL AND state NOT IN ('queued','held','refused_airtime','cancelled','expired','invalid') ORDER BY atMs ASC, id ASC")
    suspend fun replaySince(sinceMs: Long): List<MessageEntity>

    /** The newest received message that carries a signal reading (RSSI and SNR are measured by the radio). */
    @Query("SELECT * FROM messages WHERE dir = 'in' AND rssi IS NOT NULL AND rssi != 0 ORDER BY id DESC LIMIT 1")
    fun observeLastSignal(): Flow<MessageEntity?>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    @Query("DELETE FROM messages")
    suspend fun clear()
}

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: TaskEntity)

    @Query("SELECT * FROM tasks WHERE `key` = :key")
    suspend fun get(key: String): TaskEntity?

    /** Mayday first, then open before done, newest first. */
    @Query("SELECT * FROM tasks ORDER BY (type = 'mayday') DESC, (state = 'done') ASC, updatedMs DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY updatedMs DESC")
    suspend fun all(): List<TaskEntity>

    @Query("DELETE FROM tasks")
    suspend fun clear()
}

@Dao
interface StatDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensure(row: StatEntity): Long

    @Query("UPDATE stats SET value = value + :delta WHERE name = :name")
    suspend fun addRaw(name: String, delta: Long)

    @Query("SELECT * FROM stats")
    fun observeAll(): Flow<List<StatEntity>>

    @Query("SELECT * FROM stats")
    suspend fun all(): List<StatEntity>

    @Query("DELETE FROM stats")
    suspend fun clear()
}

/** Adds [delta] to a counter, creating it at 0 first. */
suspend fun StatDao.add(name: String, delta: Long = 1) {
    ensure(StatEntity(name, 0))
    addRaw(name, delta)
}

@Dao
interface NodeSeenDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: NodeSeenEntity)

    /** True when [node] sent a RescueCodec packet since [sinceMs]. */
    @Query("SELECT COUNT(*) > 0 FROM nodes_seen WHERE node = :node AND port = 256 AND lastSeenMs >= :sinceMs")
    suspend fun isRescueCodec(node: Long, sinceMs: Long): Boolean

    /** Nodes that sent normal texts on [channel] since [sinceMs] and no RescueCodec packet in that time. */
    @Query(
        "SELECT DISTINCT node FROM nodes_seen WHERE channel = :channel AND port = 1 AND lastSeenMs >= :sinceMs " +
            "AND node NOT IN (SELECT node FROM nodes_seen WHERE port = 256 AND lastSeenMs >= :sinceMs) ORDER BY node",
    )
    suspend fun meshtasticUsers(channel: String, sinceMs: Long): List<Long>

    @Query("DELETE FROM nodes_seen WHERE lastSeenMs < :beforeMs")
    suspend fun forget(beforeMs: Long)

    @Query("SELECT * FROM nodes_seen ORDER BY lastSeenMs DESC")
    fun observeAll(): Flow<List<NodeSeenEntity>>
}

@Dao
interface ChatModeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(row: ChatModeEntity)

    @Query("SELECT mode FROM chat_modes WHERE chat = :chat")
    suspend fun get(chat: String): String?

    @Query("SELECT mode FROM chat_modes WHERE chat = :chat")
    fun observe(chat: String): Flow<String?>

    @Query("DELETE FROM chat_modes WHERE chat = :chat")
    suspend fun clear(chat: String)
}

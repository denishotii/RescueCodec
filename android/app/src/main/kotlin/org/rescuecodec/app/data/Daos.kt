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

    /** Messages that still wait to be sent (also used to rebuild the queue after a restart). */
    @Query("SELECT * FROM messages WHERE dir = 'out' AND state IN ('queued','held','refused_airtime') ORDER BY id ASC")
    suspend fun waiting(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE dir = 'out' AND state IN ('queued','held','refused_airtime') ORDER BY id ASC")
    fun observeWaiting(): Flow<List<MessageEntity>>

    /** Everything newer than [sinceMs], oldest first. Replayed into the task tracker after a restart. */
    @Query("SELECT * FROM messages WHERE atMs >= :sinceMs AND json IS NOT NULL AND state NOT IN ('queued','held','refused_airtime','cancelled','expired','invalid') ORDER BY atMs ASC, id ASC")
    suspend fun replaySince(sinceMs: Long): List<MessageEntity>

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

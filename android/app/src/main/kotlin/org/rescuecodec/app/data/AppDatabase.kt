// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [MessageEntity::class, TaskEntity::class, StatEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun tasks(): TaskDao
    abstract fun stats(): StatDao

    companion object {
        fun open(context: Context, name: String = "rescuecodec.db"): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name).build()

        /** A database that lives in memory only. For tests. */
        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java).build()
    }
}

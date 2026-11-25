// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MessageEntity::class, TaskEntity::class, StatEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun tasks(): TaskDao
    abstract fun stats(): StatDao

    companion object {
        /** [teamChannel] names the channel that all rows of an old (version 1) database were sent and received on. */
        fun open(context: Context, name: String = "rescuecodec.db", teamChannel: () -> String = { Migrations.DEFAULT_TEAM }): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addMigrations(Migrations.from1To2(teamChannel))
                .build()

        /** A database that lives in memory only. For tests. */
        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java).build()
    }
}

/** Schema changes. The SQL lives in plain lists, so a JVM test runs exactly these statements on a real SQLite. */
object Migrations {
    /** The team channel's name unless the person named it differently ([org.rescuecodec.app.core.AppSettings.CHANNEL_NAME]). */
    const val DEFAULT_TEAM = "RescueCodec"

    /**
     * Version 1 to 2: every message and task knows its channel (index, name, public) and, for a direct message, the
     * other node. Version 1 had one channel only, so every old row gets the team channel [team]. Index unknown (null),
     * not public (version 1 never used a public channel).
     */
    fun sql1To2(team: String): List<String> {
        val name = "'" + team.replace("'", "''") + "'"
        return listOf(
            "ALTER TABLE `messages` ADD COLUMN `channelIndex` INTEGER",
            "ALTER TABLE `messages` ADD COLUMN `channelName` TEXT",
            "ALTER TABLE `messages` ADD COLUMN `channelPublic` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `messages` ADD COLUMN `peer` INTEGER",
            "CREATE INDEX IF NOT EXISTS `index_messages_channelName` ON `messages` (`channelName`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_peer` ON `messages` (`peer`)",
            "UPDATE `messages` SET `channelName` = $name",
            "ALTER TABLE `tasks` ADD COLUMN `channelIndex` INTEGER",
            "ALTER TABLE `tasks` ADD COLUMN `channelName` TEXT",
            "ALTER TABLE `tasks` ADD COLUMN `channelPublic` INTEGER NOT NULL DEFAULT 0",
            "UPDATE `tasks` SET `channelName` = $name",
        )
    }

    fun from1To2(team: () -> String): Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in sql1To2(team().ifBlank { DEFAULT_TEAM })) db.execSQL(sql)
        }
    }
}

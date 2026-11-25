// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MessageEntity::class, TaskEntity::class, StatEntity::class, NodeSeenEntity::class, ChatModeEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun tasks(): TaskDao
    abstract fun stats(): StatDao
    abstract fun nodesSeen(): NodeSeenDao
    abstract fun chatModes(): ChatModeDao

    companion object {
        /** [teamChannel] names the channel that all rows of an old (version 1) database were sent and received on. */
        fun open(context: Context, name: String = "rescuecodec.db", teamChannel: () -> String = { Migrations.DEFAULT_TEAM }): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addMigrations(Migrations.from1To2(teamChannel), Migrations.FROM_2_TO_3)
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
     * other node. Version 1 had one channel only, so every old row gets the team channel [team] (rows still waiting
     * to be sent: null, the team channel at send time). Index unknown (null), not public (version 1 never used one).
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
            // Sent and received rows were on the team channel. Rows still waiting keep no name: they go out on
            // whatever the team channel is when the radio is back (a null name means "the team channel").
            "UPDATE `messages` SET `channelName` = $name WHERE NOT (`dir` = 'out' AND `state` IN ('queued','held','refused_airtime'))",
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

    /**
     * Version 2 to 3: packed texts (spec section 2.3). Two new tables: which nodes we heard on which port
     * (RescueCodec or normal Meshtastic text) and the text mode of each chat. Nothing else changes.
     */
    val SQL_2_TO_3: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `nodes_seen` (`node` INTEGER NOT NULL, `channel` TEXT NOT NULL, `port` INTEGER NOT NULL, " +
            "`lastSeenMs` INTEGER NOT NULL, PRIMARY KEY(`node`, `channel`, `port`))",
        "CREATE INDEX IF NOT EXISTS `index_nodes_seen_lastSeenMs` ON `nodes_seen` (`lastSeenMs`)",
        "CREATE TABLE IF NOT EXISTS `chat_modes` (`chat` TEXT NOT NULL, `mode` TEXT NOT NULL, PRIMARY KEY(`chat`))",
    )

    val FROM_2_TO_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in SQL_2_TO_3) db.execSQL(sql)
        }
    }
}

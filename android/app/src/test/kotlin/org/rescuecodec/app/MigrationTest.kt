// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import org.rescuecodec.app.data.Migrations
import org.rescuecodec.codec.Json
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Room migration 1 to 2 on a real SQLite (JVM): builds the version 1 tables from Room's exported schema
 * (`schemas/.../1.json`), adds rows, runs exactly the migration's SQL, and checks that the result has every column
 * and index of the exported version 2 schema with the same type, NOT NULL and default (what Room validates on open),
 * and that old rows got the team channel.
 */
class MigrationTest {
    private val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = db.close()

    private fun schema(version: Int): Map<String, Any?> {
        val f = listOf("schemas", "app/schemas").map { File(it, "org.rescuecodec.app.data.AppDatabase/$version.json") }.first { it.exists() }
        @Suppress("UNCHECKED_CAST")
        return Json.parseObject(f.readText())["database"] as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun entities(s: Map<String, Any?>) = s["entities"] as List<Map<String, Any?>>

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun createVersion1() {
        for (e in entities(schema(1))) {
            val table = e["tableName"] as String
            exec((e["createSql"] as String).replace("\${TABLE_NAME}", table))
            @Suppress("UNCHECKED_CAST")
            for (i in (e["indices"] as? List<Map<String, Any?>>).orEmpty()) exec((i["createSql"] as String).replace("\${TABLE_NAME}", table))
        }
    }

    private data class Col(val type: String, val notNull: Boolean, val default: String?)

    private fun columns(table: String): Map<String, Col> = db.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            buildMap { while (rs.next()) put(rs.getString("name"), Col(rs.getString("type"), rs.getInt("notnull") == 1, rs.getString("dflt_value"))) }
        }
    }

    private fun indices(table: String): Set<String> = db.createStatement().use { st ->
        st.executeQuery("PRAGMA index_list(`$table`)").use { rs -> buildSet { while (rs.next()) add(rs.getString("name")) } }
    }

    @Test
    fun version1RowsMoveToTheTeamChannelAndTheSchemaMatchesVersion2() {
        createVersion1()
        exec("INSERT INTO messages (dir, node, type, msgId, bytes, json, atMs, state, attempts) VALUES ('in', 2, 'text', 0, x'01', '{\"text\":{\"text\":\"hi\"}}', 100, 'received', 0)")
        exec("INSERT INTO messages (dir, node, type, msgId, bytes, json, atMs, state, attempts) VALUES ('out', 1, 'found', 7, x'0203', '{}', 200, 'queued', 0)")
        exec("INSERT INTO tasks (`key`, node, msgId, type, json, local, state, meshDelivered, createdMs, updatedMs, answersJson, rank) VALUES ('00000001-7', 1, 7, 'found', '{}', 1, 'open', 0, 200, 200, '[]', 1)")

        for (sql in Migrations.sql1To2("Berg'wacht")) exec(sql)

        // Every column and index of version 2, as Room expects them.
        for (e in entities(schema(2))) {
            val table = e["tableName"] as String
            val have = columns(table)
            @Suppress("UNCHECKED_CAST")
            for (f in e["fields"] as List<Map<String, Any?>>) {
                val name = f["columnName"] as String
                val col = have[name] ?: throw AssertionError("$table.$name missing after the migration")
                assertEquals(f["affinity"], col.type, "$table.$name type")
                assertEquals((f["notNull"] as? Boolean) ?: false, col.notNull, "$table.$name NOT NULL")
                assertEquals(f["defaultValue"] as String?, col.default?.trim('\''), "$table.$name default")
            }
            assertEquals((e["fields"] as List<*>).size, have.size, "$table has no extra columns")
            @Suppress("UNCHECKED_CAST")
            val wanted = (e["indices"] as? List<Map<String, Any?>>).orEmpty().map { it["name"] as String }.toSet()
            assertTrue(indices(table).containsAll(wanted), "$table indices ${indices(table)} lack some of $wanted")
        }

        // Old rows: the team channel (the quote survives), index unknown, not public, no direct-message peer. A row that
        // still waits to be sent has no name yet: it goes out on the team channel of that moment.
        db.createStatement().use { st ->
            st.executeQuery("SELECT channelName, channelIndex, channelPublic, peer FROM messages ORDER BY id").use { rs ->
                var n = 0
                while (rs.next()) {
                    n++
                    assertEquals(if (n == 1) "Berg'wacht" else null, rs.getString(1))
                    rs.getInt(2); assertTrue(rs.wasNull())
                    assertEquals(0, rs.getInt(3))
                    rs.getLong(4); assertTrue(rs.wasNull())
                }
                assertEquals(2, n)
            }
            st.executeQuery("SELECT channelName, channelPublic FROM tasks").use { rs ->
                assertTrue(rs.next())
                assertEquals("Berg'wacht", rs.getString(1))
                assertEquals(0, rs.getInt(2))
            }
        }
    }

    @Test
    fun theDefaultTeamNameIsRescueCodec() {
        assertEquals("RescueCodec", Migrations.DEFAULT_TEAM)
        assertNull(Migrations.sql1To2("x").firstOrNull { it.contains("DROP") }, "the migration never drops data")
    }
}

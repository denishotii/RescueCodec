// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.rescuecodec.app.data.ChatModeDao
import org.rescuecodec.app.data.ChatModeEntity
import org.rescuecodec.app.data.NodeSeenDao
import org.rescuecodec.app.data.NodeSeenEntity
import org.rescuecodec.engine.TextMode

/**
 * Which way a chat text goes (spec section 2.3): packed for RescueCodec on port 256, or a normal Meshtastic text on
 * port 1 that every app can read.
 *
 * It remembers who we heard: a node that sent any RescueCodec packet runs RescueCodec; a node that sent normal texts on
 * a channel and no RescueCodec packet is a Meshtastic user there. Both are forgotten after 24 h.
 * Each chat has a mode: auto (default), compressed or normal. The rule itself is [TextMode] in `:engine`.
 *
 * Example: the team channel where only RescueCodec apps talk packs "We are at the hut, two people with us, waiting for
 * the vehicle on the forest road" (81 B) to about 42 B. As soon as someone with the official app writes there, texts
 * go out normal again, so that person can read them.
 */
class TextRoutes(
    private val seen: NodeSeenDao,
    private val modes: ChatModeDao,
    private val settings: AppSettings,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** We got a valid RescueCodec packet from [node] on [channel]. */
    suspend fun heardRescueCodec(node: Long, channel: String?) = heard(node, channel, PORT_RESCUECODEC)

    /** We got a normal Meshtastic text broadcast from [node] on [channel]. */
    suspend fun heardText(node: Long, channel: String?) = heard(node, channel, PORT_TEXT)

    private suspend fun heard(node: Long, channel: String?, port: Int) {
        val now = clock()
        seen.upsert(NodeSeenEntity(node, channel.orEmpty(), port, now))
        seen.forget(now - TextMode.MEMORY_MS)
    }

    /** True when [node] sent a RescueCodec packet in the last 24 h. */
    suspend fun isRescueCodec(node: Long): Boolean = seen.isRescueCodec(node, clock() - TextMode.MEMORY_MS)

    /** Meshtastic users heard on [channel] in the last 24 h. */
    suspend fun meshtasticUsers(channel: String): List<Long> = seen.meshtasticUsers(channel, clock() - TextMode.MEMORY_MS)

    /** RescueCodec nodes heard on [channel] in the last 24 h. */
    suspend fun rescueCodecNodes(channel: String): List<Long> = seen.rescueCodecNodes(channel, clock() - TextMode.MEMORY_MS)

    /** The mode the person picked for [chat], else the default (channels: [AppSettings.defaultChannelMode]). */
    suspend fun modeOf(chat: ChatId): String = modes.get(chat.key)?.takeIf { it in TextMode.MODES } ?: defaultFor(chat)

    fun observeMode(chat: ChatId): Flow<String> = modes.observe(chat.key).map { it?.takeIf { m -> m in TextMode.MODES } ?: defaultFor(chat) }

    suspend fun setMode(chat: ChatId, mode: String) {
        require(mode in TextMode.MODES) { "mode must be one of ${TextMode.MODES}" }
        modes.set(ChatModeEntity(chat.key, mode))
    }

    private fun defaultFor(chat: ChatId) = if (chat is ChatId.Channel) settings.defaultChannelMode else TextMode.AUTO

    /** Should a text in [chat] go packed? [public]: the chat is a public channel. */
    suspend fun wish(chat: ChatId, public: Boolean): TextMode.Wish = when (chat) {
        is ChatId.Direct -> TextMode.wantsPacked(modeOf(chat), public = false, direct = true, peerRescueCodec = isRescueCodec(chat.node))
        is ChatId.Channel -> TextMode.wantsPacked(modeOf(chat), public = public, direct = false, meshtasticUsers = meshtasticUsers(chat.name).size, rescueCodecNodes = rescueCodecNodes(chat.name).size)
    }

    companion object {
        const val PORT_RESCUECODEC = 256
        const val PORT_TEXT = 1

        /** Memory only, for tests and previews. */
        fun inMemory(settings: AppSettings, clock: () -> Long = System::currentTimeMillis) =
            TextRoutes(MemoryNodeSeenDao(), MemoryChatModeDao(), settings, clock)
    }
}

/** [NodeSeenDao] in memory. */
class MemoryNodeSeenDao : NodeSeenDao {
    private val rows = MutableStateFlow<Map<Triple<Long, String, Int>, NodeSeenEntity>>(emptyMap())
    val all: List<NodeSeenEntity> get() = rows.value.values.toList()

    override suspend fun upsert(row: NodeSeenEntity) { rows.update { it + (Triple(row.node, row.channel, row.port) to row) } }
    override suspend fun isRescueCodec(node: Long, sinceMs: Long) = rows.value.values.any { it.node == node && it.port == 256 && it.lastSeenMs >= sinceMs }
    override suspend fun meshtasticUsers(channel: String, sinceMs: Long): List<Long> {
        val rc = rows.value.values.filter { it.port == 256 && it.lastSeenMs >= sinceMs }.map { it.node }.toSet()
        return rows.value.values.filter { it.channel == channel && it.port == 1 && it.lastSeenMs >= sinceMs && it.node !in rc }
            .map { it.node }.distinct().sorted()
    }
    override suspend fun rescueCodecNodes(channel: String, sinceMs: Long): List<Long> =
        rows.value.values.filter { it.channel == channel && it.port == 256 && it.lastSeenMs >= sinceMs }.map { it.node }.distinct().sorted()
    override suspend fun forget(beforeMs: Long) { rows.update { m -> m.filterValues { it.lastSeenMs >= beforeMs } } }
    override fun observeAll(): Flow<List<NodeSeenEntity>> = rows.map { m -> m.values.sortedByDescending { it.lastSeenMs } }
}

/** [ChatModeDao] in memory. */
class MemoryChatModeDao : ChatModeDao {
    private val rows = MutableStateFlow<Map<String, String>>(emptyMap())
    override suspend fun set(row: ChatModeEntity) { rows.update { it + (row.chat to row.mode) } }
    override suspend fun get(chat: String) = rows.value[chat]
    override fun observe(chat: String): Flow<String?> = rows.map { it[chat] }
    override suspend fun clear(chat: String) { rows.update { it - chat } }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.rescuecodec.app.data.MessageEntity
import org.rescuecodec.app.data.MessageState
import org.rescuecodec.codec.Json

/** One conversation: a channel (by name, the shared contract) or a direct message with one node. */
sealed interface ChatId {
    /** Stable key for settings, notifications and view models: "ch:RescueCodec", "dm:0badf00d". */
    val key: String

    data class Channel(val name: String) : ChatId {
        override val key: String get() = "ch:$name"
    }

    data class Direct(val node: Long) : ChatId {
        override val key: String get() = "dm:%08x".format(node)
    }

    companion object {
        fun parse(key: String?): ChatId? = when {
            key == null -> null
            key.startsWith("ch:") && key.length > 3 -> Channel(key.substring(3))
            key.startsWith("dm:") -> key.substring(3).toLongOrNull(16)?.let { Direct(it) }
            else -> null
        }

        /** The chat a stored row belongs to. A row still waiting for the team channel ([team]) is in the team chat. */
        fun of(row: MessageEntity, team: String): ChatId = row.peer?.let { Direct(it) } ?: Channel(row.channelName ?: team)
    }
}

/** A node from the radio's node list: its names and the channel we heard it on. */
data class NodeName(val num: Long, val longName: String, val shortName: String, val channel: Int? = null) {
    /** The long name, else the short name, else null. */
    val label: String? get() = longName.ifBlank { shortName }.ifBlank { null }
}

/**
 * The names of the other radios, from the connected radio's node list. Kept in the key-value store, so names survive
 * a restart and a Bluetooth link that skips the node list download (the radio still tells us new nodes as it hears them).
 */
class NodeDirectory(private val store: KeyValueStore? = null) {
    private val _nodes = MutableStateFlow(load())
    val nodes: StateFlow<Map<Long, NodeName>> = _nodes.asStateFlow()

    /** Merges [list] in. Saves only when something changed. */
    @Synchronized
    fun update(list: Collection<NodeName>) {
        val cur = _nodes.value
        val next = LinkedHashMap(cur)
        for (n in list) {
            if (n.num == 0L || n.label == null) continue
            next[n.num] = n
        }
        if (next == cur) return
        val trimmed = if (next.size > MAX) next.entries.toList().takeLast(MAX).associate { it.key to it.value } else next
        _nodes.value = trimmed
        save(trimmed)
    }

    /** "Anna Trupp 3" or "AN3", or null when the radio never told us. */
    fun label(num: Long): String? = _nodes.value[num]?.label

    /** The channel index the radio heard [num] on, or null. A direct message goes out there. */
    fun channelOf(num: Long): Int? = _nodes.value[num]?.channel

    private fun load(): Map<Long, NodeName> = runCatching {
        val raw = store?.getString(KEY) ?: return emptyMap()
        (Json.parse(raw) as? List<*>).orEmpty().mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val num = (m["n"] as? Number)?.toLong() ?: return@mapNotNull null
            NodeName(num, m["l"] as? String ?: "", m["s"] as? String ?: "", (m["c"] as? Number)?.toInt())
        }.associateBy { it.num }
    }.getOrDefault(emptyMap())

    private fun save(m: Map<Long, NodeName>) {
        val s = store ?: return
        runCatching {
            s.putString(KEY, Json.stringify(m.values.map { linkedMapOf<String, Any?>("n" to it.num, "l" to it.longName, "s" to it.shortName, "c" to it.channel) }))
        }
    }

    companion object {
        private const val KEY = "node_names"
        private const val MAX = 400
    }
}

/** Up to which row each chat was read ("ch:RescueCodec" to the newest row id seen there). Kept in the key-value store. */
class ChatReads(private val store: KeyValueStore? = null) {
    private val _read = MutableStateFlow(load())
    val read: StateFlow<Map<String, Long>> = _read.asStateFlow()

    @Synchronized
    fun markRead(key: String, upToId: Long) {
        if ((_read.value[key] ?: 0L) >= upToId) return
        _read.value = _read.value + (key to upToId)
        store?.let { s -> runCatching { s.putString(KEY, Json.stringify(_read.value)) } }
    }

    private fun load(): Map<String, Long> = runCatching {
        (Json.parse(store?.getString(KEY) ?: return emptyMap()) as? Map<*, *>).orEmpty()
            .mapNotNull { (k, v) -> (k as? String)?.let { key -> (v as? Number)?.toLong()?.let { key to it } } }.toMap()
    }.getOrDefault(emptyMap())

    companion object {
        private const val KEY = "chat_reads"
    }
}

/** One row of the chat list. */
data class ChatSummary(
    val id: ChatId,
    /** Channel name, or the node's long name ("Node 1a2b" style fallback is up to the screen when null). */
    val title: String?,
    /** Short name of a node ("AN3"), for the avatar. Null for channels. */
    val shortName: String?,
    /** The team channel: pinned on top. */
    val isTeam: Boolean,
    val isPublic: Boolean,
    /** Newest row in this chat, or null when it is empty. */
    val last: MessageEntity?,
    /** Received messages newer than what the person has seen. */
    val unread: Int,
    /** An open Mayday came on this channel. */
    val mayday: Boolean,
)

/** Builds the chat list. Pure, so it is unit tested. */
object ChatList {
    /** Rows that count as unread: received texts and reports. Answers and track points do not. */
    fun countsAsUnread(r: MessageEntity): Boolean =
        r.dir == "in" && r.state == MessageState.RECEIVED && r.type != "ack" && r.type != "track"

    /**
     * [rows] newest first. [radio] the switched-on channels of the radio (null when unknown: then the channels come
     * from the rows). [team] the team channel's name. [maydayOn] channel names with an open Mayday.
     * Order: the team channel pinned on top, the other private channels, direct messages (newest first), then the
     * public channel only when [showPublic] is on.
     */
    fun build(
        rows: List<MessageEntity>,
        radio: List<RadioChannel>?,
        team: String,
        showPublic: Boolean,
        names: Map<Long, NodeName>,
        reads: Map<String, Long>,
        maydayOn: Set<String>,
    ): List<ChatSummary> {
        val byChat = LinkedHashMap<ChatId, MutableList<MessageEntity>>()
        for (r in rows) if (r.state != MessageState.INVALID) byChat.getOrPut(ChatId.of(r, team)) { ArrayList() }.add(r)

        fun summary(id: ChatId, title: String?, short: String?, isTeam: Boolean, isPublic: Boolean): ChatSummary {
            val list = byChat[id].orEmpty()
            val read = reads[id.key] ?: 0L
            val name = (id as? ChatId.Channel)?.name
            return ChatSummary(
                id = id, title = title, shortName = short, isTeam = isTeam, isPublic = isPublic,
                last = list.firstOrNull(), unread = list.count { it.id > read && countsAsUnread(it) },
                mayday = name != null && name in maydayOn,
            )
        }
        fun lastId(s: ChatSummary) = s.last?.id ?: 0L

        val channels: List<RadioChannel> = radio ?: byChat.keys.filterIsInstance<ChatId.Channel>()
            .map { c -> RadioChannel(-1, c.name, byChat[c].orEmpty().any { it.channelPublic }) }
        val out = ArrayList<ChatSummary>()
        out += summary(ChatId.Channel(team), team, null, isTeam = true, isPublic = false)
        out += channels.filter { it.isPrivate && it.name != team }.distinctBy { it.name }
            .map { summary(ChatId.Channel(it.name), it.name, null, isTeam = false, isPublic = false) }
            .sortedWith(compareByDescending<ChatSummary> { lastId(it) })
        out += byChat.keys.filterIsInstance<ChatId.Direct>()
            .map { d -> names[d.node].let { n -> summary(d, n?.label, n?.shortName?.ifBlank { null }, isTeam = false, isPublic = false) } }
            .sortedByDescending { lastId(it) }
        if (showPublic) {
            out += channels.filter { it.isPublic && it.name != team }.distinctBy { it.name }
                .map { summary(ChatId.Channel(it.name), it.name, null, isTeam = false, isPublic = true) }
        }
        return out
    }
}

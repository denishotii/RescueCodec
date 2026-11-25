// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Routing
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.SendFailure
import org.meshtastic.sdk.SendState
import org.meshtastic.sdk.nodeMap
import org.rescuecodec.app.data.MessageDao
import org.rescuecodec.app.data.MessageEntity
import org.rescuecodec.app.data.MessageState
import org.rescuecodec.app.data.StatDao
import org.rescuecodec.app.data.StatNames
import org.rescuecodec.app.data.TaskDao
import org.rescuecodec.app.data.TaskEntity
import org.rescuecodec.app.data.add
import org.rescuecodec.codec.CodecException
import org.rescuecodec.codec.Codes
import org.rescuecodec.codec.Json
import org.rescuecodec.codec.RescueCodec
import org.rescuecodec.engine.Airtime
import org.rescuecodec.engine.AirtimeBudget
import org.rescuecodec.engine.Outcome
import org.rescuecodec.engine.QueuedMessage
import org.rescuecodec.engine.RequestTracker
import org.rescuecodec.engine.SendQueue
import org.rescuecodec.engine.Task
import org.rescuecodec.engine.TaskKey
import org.rescuecodec.engine.TaskState
import org.rescuecodec.engine.TextFit
import org.rescuecodec.engine.TextMode

/** Result of [Messenger.submit]. */
sealed interface SubmitResult {
    /** The message is in the send queue. It goes out as soon as the radio is connected and the airtime budget allows. */
    data class Queued(
        val rowId: Long,
        val type: String,
        /** RescueCodec id (found, request, hazard, mayday), 0 if the type has none. */
        val msgId: Int,
        val bytes: ByteArray,
        val readable: String,
    ) : SubmitResult

    /** The message is not valid. [code] is a stable code from spec section 5, or `invalid_json`. */
    data class Rejected(val code: String, val field: String?, val message: String) : SubmitResult
}

/** Result of [Messenger.answer]. */
sealed interface AnswerResult {
    data class Sent(val rowId: Long) : AnswerResult
    data object UnknownTask : AnswerResult
    data object RadioUnknown : AnswerResult
    data class InvalidMove(val from: String, val to: String) : AnswerResult
    data class Rejected(val code: String, val message: String) : AnswerResult
}

/** Airtime used in the last hour, an estimate from the LoRa formula (not measured). */
data class AirtimeStatus(val usedMs: Double, val limitMs: Double) {
    val percent: Double get() = if (limitMs <= 0) 0.0 else usedMs / limitMs * 100
    val basis: String get() = "estimated"
}

/**
 * Sends and receives RescueCodec messages through the connected radio.
 *
 * Send: [submit] puts a message into the [SendQueue] of `:engine` (Mayday first, one airtime budget for all
 * channels). A loop hands queued messages to the radio as PRIVATE_APP (port 256) broadcasts on the message's channel
 * (by name; no name means the team channel), with `want_ack` and a Meshtastic priority taken from the message priority
 * (spec section 2). A direct message is a port 1 text with `to` set to the other node. The radio's answer decides the
 * state of the message: sent, delivered (a neighbour repeated it), not confirmed, or refused because of the legal
 * airtime limit (we try again later). A message whose channel is not on the radio waits; it never goes elsewhere.
 *
 * Receive: port 256 and port 1 on every private channel of the radio, the public channel only when
 * [AppSettings.showPublicChannel] is on, and direct messages to our node on any channel. Every row keeps its channel
 * (index, name, public) and, for a direct message, the other node. Reports become tasks, Acks update tasks.
 * Everything is written to the database, so a restart loses nothing.
 *
 * Normal Meshtastic texts (port 1, spec section 2.1) are stored as `text` rows marked [MeshText.SOURCE]. A message with
 * only a text goes out packed for RescueCodec (port 256, raw DEFLATE with the shared dictionary) or as a normal
 * Meshtastic text that every app can read, by the chat's mode and who we heard in it ([TextRoutes], spec section
 * 2.3). Reports always go on port 256, never on a public channel.
 *
 * Tasks: [answer] sends Accept, Decline or Done as an Ack on the report's channel and applies it locally at once.
 */
class Messenger(
    private val scope: CoroutineScope,
    /** The connected client: we send only while this is not null. */
    private val clientFlow: StateFlow<RadioClient?>,
    /**
     * The client we listen to. It is set as soon as the client is built, before it connects, so we
     * hear the packets the radio replays right after the handshake (the SDK does not buffer them).
     */
    private val attachedFlow: StateFlow<RadioClient?>,
    private val messages: MessageDao,
    private val tasks: TaskDao,
    private val stats: StatDao,
    private val settings: AppSettings,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Minimum time between two packets handed to the radio. */
    private val gapMs: Long = 300,
    /** Waits before we retry a message the radio refused for airtime. The last value repeats. */
    private val refusalDelaysMs: List<Long> = listOf(30_000, 60_000, 120_000, 300_000),
    private val maxRefusalRetries: Int = 12,
    /** Time without any answer after which a sent task shows "delivery unknown". */
    private val taskTimeoutMs: (type: String) -> Long = { if (it == "mayday") 90_000L else 300_000L },
    private val log: (String) -> Unit = {},
    /** Alerts the person to a Mayday from another radio (a loud notification in the app). */
    private val alerts: MaydayAlerts = MaydayAlerts.NONE,
    /** Airtime budget of the send queue. The default follows the region and preset in the settings. */
    budget: AirtimeBudget = AirtimeBudget(Airtime(settings.modemPreset), settings.region),
    /** Names of the other radios, filled from the radio's node list. */
    private val nodes: NodeDirectory? = null,
    /** How long a message whose channel is not on the radio waits before we look again. */
    private val parkMs: Long = 5_000,
    /** Who runs RescueCodec and the text mode of each chat: decides packed or normal text. */
    val routes: TextRoutes = TextRoutes.inMemory(settings, clock),
) {
    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var replayAt: Long? = null
    private val tracker = RequestTracker(clock = { replayAt ?: clock() }, timeoutMs = taskTimeoutMs)
    private val queue = SendQueue(
        budget = budget,
        clock = clock,
        minGapMs = gapMs,
    )
    private val rowBySeq = HashMap<Long, Long>()
    private var started = false

    /** Mayday task keys we already alerted for, so a copy never alerts twice. */
    private val alerted = HashSet<String>()

    /** Channel of each task: answers go out on it. Filled from reports (sent and received); the table keeps it too. */
    private val taskChannel = HashMap<TaskKey, ChannelRef>()

    /** Plain texts we stored: "sender:packet id" (or "sender:bytes" without an id) to the time we got it. */
    private val textsSeen = LinkedHashMap<String, Long>()

    private val _airtime = MutableStateFlow(AirtimeStatus(0.0, queue.budget.limitMs))

    /** Airtime used in the last hour and the legal limit (estimated). */
    val airtime: StateFlow<AirtimeStatus> = _airtime.asStateFlow()

    private val _channel = MutableStateFlow<ChannelChoice>(ChannelChoice.Waiting("No radio connected."))

    /** The radio channel reports go out on, or why there is none yet. */
    val channel: StateFlow<ChannelChoice> = _channel.asStateFlow()

    private val _channelOptions = MutableStateFlow<List<ChannelOption>>(emptyList())

    /** Enabled channels of the connected radio, for the channel picker. */
    val channelOptions: StateFlow<List<ChannelOption>> = _channelOptions.asStateFlow()

    private val _radioChannels = MutableStateFlow<List<RadioChannel>?>(loadRadioChannels())

    /**
     * Switched-on channels of the radio with their display names, for the chat list. The last known list stays while
     * no radio is connected (null if we never saw one).
     */
    val radioChannels: StateFlow<List<RadioChannel>?> = _radioChannels.asStateFlow()

    /** Starts the loops. Safe to call twice. Restores tracker and queue from the database first. */
    suspend fun start() {
        if (started) return
        started = true
        mutex.withLock { restore() }
        scope.launch { tickLoop() }
        scope.launch { radioLoop() }
    }

    // ------------------------------------------------------------------ sending

    /**
     * Puts [message] (canonical JSON shape as a map) into the send queue. Every message except an Ack gets an id if it
     * has none. [source] says who asked: "app" or a package name.
     *
     * Where it goes: [channel] by name, or the team channel when null. With [peer] set it is a direct message to that
     * node: text only. A public channel takes plain texts and answers only, never a report (it would leak positions
     * and injuries). A text goes packed (port 256, with its id and position) or as a normal Meshtastic text (port 1)
     * by [textForm].
     */
    suspend fun submit(message: Map<String, Any?>, source: String = "app", channel: String? = null, peer: Long? = null): SubmitResult {
        val input = LinkedHashMap(message)
        val type = try {
            RescueCodec.messageType(input)
        } catch (e: CodecException) {
            return SubmitResult.Rejected(e.code, e.field, e.message ?: e.code)
        }
        if (peer != null && type != "text") {
            return SubmitResult.Rejected("direct_text_only", null, "A direct message carries text only. Reports go on a channel.")
        }
        val public = peer == null && isPublic(channel)
        if (public && type != "text" && type != "ack") {
            return SubmitResult.Rejected("public_channel", null, "Reports never go on a public channel. Send them on a private channel.")
        }
        if (type == "text") return submitText(input, source, channel, peer)
        // Every message except an Ack gets an id (spec section 14), so a second "Hilfe" is a new message and not a
        // repeat of the first. It costs 1 to 2 bytes. An Ack is a repeat by design and carries none.
        if (type != "ack" && (input["id"] as? Number)?.toInt().let { it == null || it == 0 }) {
            input["id"] = settings.nextMessageId()
        }
        val bytes: ByteArray
        val canonical: Map<String, Any?>
        try {
            bytes = RescueCodec.encode(input)
            canonical = RescueCodec.decodeAny(bytes)
        } catch (e: CodecException) {
            return SubmitResult.Rejected(e.code, e.field, e.message ?: e.code)
        } catch (e: IllegalArgumentException) {
            return SubmitResult.Rejected("invalid_value", null, e.message ?: "invalid message")
        }
        val msgId = (canonical["id"] as? Number)?.toInt() ?: 0
        return mutex.withLock {
            val row = MessageEntity(
                dir = "out", node = currentNode(), type = type, msgId = msgId, bytes = bytes,
                json = Json.stringify(canonical), atMs = clock(), state = MessageState.QUEUED, source = source,
                channelName = channel,
            )
            val rowId = messages.insert(row)
            val q = queue.enqueue(canonical, bytes)
            rowBySeq[q.seq] = rowId
            wake.trySend(Unit)
            SubmitResult.Queued(rowId, type, msgId, bytes, RescueCodec.reconstruct(canonical, settings.language))
        }
    }

    /**
     * A message with only a text, to [channel] (null: the team channel) or to [peer]. Packed for RescueCodec when the
     * chat's mode and the people in it allow it and it saves at least 3 bytes (spec section 2.3), else a normal
     * Meshtastic text. A packed text keeps its id and position, like any RescueCodec message. With
     * [AppSettings.compactText] on, a chat that packs sends word tokens when they are smaller (spec section 3.6).
     * A chat that packs takes up to 400 bytes of text, cut to fit one packet ([TextFit.fitPacked]); a normal chat 180.
     */
    private suspend fun submitText(input: Map<String, Any?>, source: String, channel: String?, peer: Long?): SubmitResult {
        val wish = routes.wish(chatOf(channel, peer), peer == null && isPublic(channel))
        // Checked as a RescueCodec text first, so a broken message is refused the same way in every chat. Where the
        // chat packs, a text over 180 bytes is fine (it is fitted below), so only the rest of the message is checked.
        try {
            RescueCodec.encode(TextFit.fitText(if (wish.packed) withCheckableText(input) else input))
        } catch (e: CodecException) {
            return SubmitResult.Rejected(e.code, e.field, e.message ?: e.code)
        } catch (e: IllegalArgumentException) {
            return SubmitResult.Rejected("invalid_value", null, e.message ?: "invalid message")
        }
        if (!wish.packed) return submitPlain(input, channel, peer)
        val message = LinkedHashMap(input)
        if ((message["id"] as? Number)?.toInt().let { it == null || it == 0 }) message["id"] = settings.nextMessageId()
        val form = TextMode.chooseForm(message, true, wish.reason, compact = settings.compactText)
        if (!form.packed) return submitPlain(input, channel, peer, form.text)
        val msgId = (form.message["id"] as? Number)?.toInt() ?: 0
        return mutex.withLock {
            val row = MessageEntity(
                dir = "out", node = currentNode(), type = "text", msgId = msgId, bytes = form.payload,
                json = Json.stringify(form.message), atMs = clock(), state = MessageState.QUEUED, source = source,
                channelName = channel, peer = peer,
            )
            val rowId = messages.insert(row)
            rowBySeq[queue.enqueue(form.message, form.payload).seq] = rowId
            wake.trySend(Unit)
            SubmitResult.Queued(rowId, "text", msgId, form.payload, RescueCodec.reconstruct(form.message, settings.language))
        }
    }

    /**
     * What a text would look like on the air in this chat: packed or normal, and both sizes (for the composer). The
     * message is measured with a 2-byte id when it has none, the largest one [submit] gives.
     */
    suspend fun textForm(message: Map<String, Any?>, channel: String?, peer: Long?): TextMode.Form {
        val wish = textWish(channel, peer)
        val probe = if (message["id"] == null) LinkedHashMap(message).also { it["id"] = ID_PROBE } else message
        return TextMode.chooseForm(probe, wish.packed, wish.reason, compact = settings.compactText)
    }

    /** Whether a text to [channel] (null: the team channel) or [peer] goes packed, and why (spec section 2.3). */
    suspend fun textWish(channel: String?, peer: Long?): TextMode.Wish =
        routes.wish(chatOf(channel, peer), peer == null && isPublic(channel))

    /** [input] with a text over 180 bytes cut to 180, so the codec checks everything but the length of the text. */
    private fun withCheckableText(input: Map<String, Any?>): Map<String, Any?> {
        val body = input["text"] as? Map<*, *> ?: return input
        val text = body["text"] as? String ?: return input
        if (text.toByteArray(Charsets.UTF_8).size <= Codes.MAX_TEXT_BYTES) return input
        val newBody = LinkedHashMap<String, Any?>()
        for ((k, v) in body) newBody[k.toString()] = v
        newBody["text"] = TextFit.fitPlain(text, Codes.MAX_TEXT_BYTES)
        return LinkedHashMap(input).also { it["text"] = newBody }
    }

    /** The chat a message goes to: the direct chat with [peer], else the channel (null: the team channel). */
    fun chatOf(channel: String?, peer: Long?): ChatId =
        if (peer != null) ChatId.Direct(peer) else ChatId.Channel(channel ?: teamChannelName())

    /** Name of the team channel: from the radio when it is known, else the one we knew last. */
    private fun teamChannelName(): String =
        (_channel.value as? ChannelChoice.Ready)?.name?.takeIf { it.isNotBlank() } ?: settings.lastTeamChannel

    private fun isPublic(channel: String?): Boolean =
        channel != null && _radioChannels.value?.firstOrNull { it.name == channel }?.isPublic == true

    /**
     * A message with only a text, sent as a normal Meshtastic text (port 1) on [channel] (null: the team channel), or
     * to [peer] as a direct message (spec section 2.1). It takes the same queue, priority, airtime budget and
     * `want_ack` as a report. Only the words go out, at most 200 bytes: no id, position or time. The row is marked
     * [MeshText.SOURCE] and [MessageEntity.bytes] is the text. [submitText] has checked the message already; [words]
     * is the text that goes out (the form's text on a chat that packs).
     */
    private suspend fun submitPlain(
        input: Map<String, Any?>,
        channel: String?,
        peer: Long?,
        words: String = (input["text"] as Map<*, *>)["text"] as String,
    ): SubmitResult {
        val text = TextFit.fitPlain(words)
        val canonical = MeshText.message(text, input["priority"] as? String)
        val bytes = text.toByteArray(Charsets.UTF_8)
        return mutex.withLock {
            val row = MessageEntity(
                dir = "out", node = currentNode(), type = "text", bytes = bytes, json = Json.stringify(canonical),
                atMs = clock(), state = MessageState.QUEUED, source = MeshText.SOURCE, channelName = channel, peer = peer,
            )
            val rowId = messages.insert(row)
            rowBySeq[queue.enqueue(canonical, bytes).seq] = rowId
            wake.trySend(Unit)
            SubmitResult.Queued(rowId, "text", 0, bytes, RescueCodec.reconstruct(canonical, settings.language))
        }
    }

    /** Removes a message that has not been handed to the radio yet. Returns true if it was waiting. */
    suspend fun cancel(rowId: Long): Boolean = mutex.withLock {
        val seq = rowBySeq.entries.firstOrNull { it.value == rowId }?.key
        val removed = seq != null && queue.cancel(seq)
        if (seq != null) rowBySeq.remove(seq)
        val row = messages.get(rowId)
        if (row != null && row.state in MessageState.WAITING) {
            messages.update(row.copy(state = MessageState.CANCELLED, detail = "Cancelled by the person."))
            true
        } else {
            removed
        }
    }

    /** Puts a message that was refused or not confirmed back into the queue. */
    suspend fun resend(rowId: Long): Boolean = mutex.withLock {
        val row = messages.get(rowId) ?: return@withLock false
        if (row.dir != "out" || row.state in MessageState.WAITING || row.state == MessageState.SENT) return@withLock false
        val canonical = row.json?.let { Json.parseObject(it) } ?: return@withLock false
        messages.update(row.copy(state = MessageState.QUEUED, detail = null, retryAtMs = null, attempts = 0, atMs = clock()))
        rowBySeq[queue.enqueue(canonical, row.bytes).seq] = rowId
        wake.trySend(Unit)
        true
    }

    /**
     * The person taps Accept, Decline or Done on the task [key] ("a1b2c3d4-7"). Sends an Ack and
     * applies it at once. The moves follow spec section 15.1.
     */
    suspend fun answer(key: String, status: String, etaMin: Int? = null): AnswerResult {
        val taskKey = parseKey(key) ?: return AnswerResult.UnknownTask
        val self = currentNode()
        if (self == 0L) return AnswerResult.RadioUnknown
        val ack: Map<String, Any?>
        val channel: String?
        mutex.withLock {
            val task = tracker[taskKey] ?: return AnswerResult.UnknownTask
            val own = task.answers.lastOrNull { it.from == self }?.status ?: "open"
            if (status !in ALLOWED_MOVES[own].orEmpty()) return AnswerResult.InvalidMove(own, status)
            ack = RequestTracker.ackFor(task, status, etaMin, teamLabelFor(settings.teamLabel))
            // The answer goes out on the same channel as the report, so the team that sent it hears it.
            channel = channelOfTask(taskKey)?.name
        }
        return when (val r = submit(ack, source = "app", channel = channel)) {
            is SubmitResult.Rejected -> AnswerResult.Rejected(r.code, r.message)
            is SubmitResult.Queued -> {
                mutex.withLock {
                    tracker.onReceived(self, ack)
                    persistTask(taskKey, self)
                }
                AnswerResult.Sent(r.rowId)
            }
        }
    }

    // ----------------------------------------------------------------- the loops

    private suspend fun radioLoop() {
        scope.launch { receiveLoop() }
        clientFlow.collectLatest { client ->
            if (client == null) {
                evaluateChannel(null)
                return@collectLatest
            }
            val self = nodeOf(client)
            if (self != 0L) settings.lastOwnNode = self
            // Channels can change while connected (the person edits them in the Meshtastic app).
            coroutineScope {
                launch {
                    client.channels.collect {
                        evaluateChannel(client)
                        wake.trySend(Unit)
                    }
                }
                // Names of the other radios, for senders, notifications and new direct messages.
                if (nodes != null) launch {
                    try {
                        client.nodeMap().collect { m ->
                            nodes.update(m.values.map { n -> NodeName(n.num.toLong() and 0xFFFFFFFFL, n.user?.long_name.orEmpty(), n.user?.short_name.orEmpty(), n.channel) })
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("node list failed: $e")
                    }
                }
                sendLoop(client, self)
            }
        }
    }

    private suspend fun receiveLoop() {
        attachedFlow.collectLatest { client ->
            if (client == null) return@collectLatest
            try {
                client.packets.collect { p ->
                    try {
                        val self = nodeOf(client).takeIf { it != 0L } ?: settings.lastOwnNode
                        // Every private channel of the radio counts; the public one only when the person wants it.
                        val radio = Channels.list(client.channels.value, presetOf(client))
                        mutex.withLock { onPacket(p, self, radio, settings.showPublicChannel) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("receive failed: $e")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("packet stream ended: $e")
            }
        }
    }

    private suspend fun sendLoop(client: RadioClient, self: Long) {
        while (currentCoroutineContext().isActive) {
            try {
                var item: Pair<QueuedMessage, Long>? = null
                var waitMs: Long? = null
                var route: ChannelRef? = null
                mutex.withLock {
                    reconcile()
                    val choice = evaluateChannel(client)
                    val radio = Channels.list(client.channels.value, presetOf(client))
                    // A message whose channel is not on the radio waits aside, so it never blocks the others.
                    parkUnroutable(radio, choice)
                    val q = queue.poll()
                    if (q != null) {
                        val rowId = rowBySeq.remove(q.seq) ?: -1L
                        val row = if (rowId >= 0) messages.get(rowId) else null
                        route = row?.let { (route(it, radio, choice) as? Route.Go)?.channel }
                        item = q to rowId
                    } else {
                        waitMs = queue.waitMs()
                        if (waitMs != null && waitMs!! > 0) markHeld(waitMs!!)
                    }
                    publishAirtime()
                }
                val next = item
                if (next == null) {
                    val w = waitMs
                    val sleep = if (w == null || w == Long.MAX_VALUE) 30_000L else w.coerceIn(100L, 5_000L)
                    withTimeoutOrNull(sleep) { wake.receive() }
                    continue
                }
                val ch = route
                if (next.second >= 0 && ch?.index != null) withContext(NonCancellable) { dispatch(client, self, next.first, next.second, ch) }
                else if (next.second >= 0) mutex.withLock { requeue(next.second, "Waiting for the channel.", delayMs = parkMs) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("send loop error: $e")
                delay(1_000)
            }
        }
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            delay(1_000)
            try {
                mutex.withLock {
                    for (t in tracker.tick()) persistTask(t.key, currentNode())
                    publishAirtime()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("tick error: $e")
            }
        }
    }

    // ------------------------------------------------------------ sending, inner

    private suspend fun dispatch(client: RadioClient, self: Long, q: QueuedMessage, rowId: Long, channel: ChannelRef) {
        val row = messages.get(rowId) ?: return
        val canonical = q.message
        val type = row.type
        val prio = meshPriority(RescueCodec.effectivePriority(canonical))
        val packet = MeshPacket.Builder().also { b ->
            // A direct message goes to its node (the radio encrypts it for that node when it knows its key).
            b.to = row.peer?.toInt() ?: NodeId.BROADCAST.raw
            b.channel = channel.index ?: 0
            b.want_ack = true
            b.priority = prio
            b.decoded = Data.Builder().also { d ->
                // A plain text goes on the normal text port, so stock Meshtastic apps show it. Everything else on 256.
                d.portnum = if (MeshText.isPlain(row)) PortNum.TEXT_MESSAGE_APP else PortNum.PRIVATE_APP
                d.payload = okio.ByteString.of(*q.bytes)
            }.build()
        }.build()
        val handle = try {
            client.send(packet)
        } catch (e: MeshtasticException.NotConnected) {
            mutex.withLock { requeue(rowId, "Radio not connected. Waiting.") }
            return
        } catch (e: MeshtasticException) {
            mutex.withLock { finish(rowId, MessageState.FAILED, e.message ?: "Send failed.", StatNames.TX_FAILED) }
            return
        }
        mutex.withLock {
            val key = if (RequestTracker.taskType(canonical) != null) TaskKey(self, row.msgId) else null
            messages.update(
                row.copy(
                    node = self, packetId = handle.id.raw.toLong() and 0xFFFFFFFFL, state = MessageState.SENT,
                    detail = null, retryAtMs = null, taskKey = key?.toString() ?: row.taskKey,
                    atMs = clock(), channelIndex = channel.index, channelName = channel.name ?: row.channelName,
                    channelPublic = channel.isPublic,
                ),
            )
            if (key != null) {
                taskChannel[key] = channel
                tracker.onSent(self, canonical)
                persistTask(key, self)
            }
            applyOwnAck(canonical, self)
            stats.add(StatNames.TX_MESSAGES)
            stats.add(StatNames.TX_BYTES, q.bytes.size.toLong())
            stats.add(StatNames.TX_AIRTIME_MS, queue.budget.costMs(q.bytes.size).toLong())
        }
        log("sent $type id=${row.msgId} ${q.bytes.size} B")
        scope.launch { watch(handle.state, rowId, self, canonical, row.attempts) }
    }

    private suspend fun watch(state: StateFlow<SendState>, rowId: Long, self: Long, msg: Map<String, Any?>, attempts: Int) {
        var sawSent = false
        val end = state.first { s ->
            if (s == SendState.Sent) sawSent = true
            s == SendState.Acked || s == SendState.Delivered || s is SendState.Failed
        }
        mutex.withLock {
            when (end) {
                SendState.Acked, SendState.Delivered -> {
                    finish(rowId, MessageState.DELIVERED, null, StatNames.TX_DELIVERED)
                    val id = (msg["id"] as? Number)?.toInt()
                    if (id != null && RequestTracker.taskType(msg) != null) {
                        tracker.onMeshDelivered(self, id)
                        persistTask(TaskKey(self, id), self)
                    }
                }
                is SendState.Failed -> onFailed(rowId, end.reason, sawSent, attempts)
                else -> Unit
            }
        }
    }

    private suspend fun onFailed(rowId: Long, reason: SendFailure, sawSent: Boolean, attempts: Int) {
        when {
            reason == SendFailure.DutyCycleLimit || (reason is SendFailure.QueueRejected && reason.res == DUTY_CYCLE_LIMIT_RES) ->
                refuse(rowId, attempts)
            reason is SendFailure.QueueRejected ->
                requeue(rowId, "The radio queue is full. Trying again in a few seconds.", delayMs = 5_000)
            reason == SendFailure.Disconnected || reason == SendFailure.HandshakeFailed ->
                if (sawSent && linkResendsOf(attempts) >= LINK_RESENDS) {
                    finish(rowId, MessageState.NOT_CONFIRMED, "The link to the radio dropped while sending. It may have gone out.", StatNames.TX_NOT_CONFIRMED)
                } else if (sawSent) {
                    // A link that dies silently (Bluetooth out of range, Wi-Fi gone) takes the packets of the last
                    // seconds with it. Send once more after the reconnect. If the first one did go out, receivers
                    // drop the copy: they dedupe on (sender, id) or on the payload bytes (spec section 14).
                    messages.get(rowId)?.let { messages.update(it.copy(attempts = attempts + LINK_UNIT)) }
                    requeue(rowId, "The link to the radio dropped while sending. Sending it again when it is back.")
                } else requeue(rowId, "The link to the radio dropped. Waiting.")
            reason == SendFailure.IdCollision -> requeue(rowId, "The radio had an id clash. Trying again.", delayMs = 1_000)
            reason == SendFailure.Cancelled -> finish(rowId, MessageState.CANCELLED, null, null)
            reason == SendFailure.MaxRetransmit || reason == SendFailure.AckTimeout || reason == SendFailure.Timeout ->
                finish(rowId, MessageState.NOT_CONFIRMED, "Sent, but no other radio repeated it. It may not have arrived.", StatNames.TX_NOT_CONFIRMED)
            reason == SendFailure.NoRoute ->
                finish(rowId, MessageState.NOT_CONFIRMED, "Sent, but no route was found.", StatNames.TX_NOT_CONFIRMED)
            reason is SendFailure.Other -> {
                if (reason.routingError == Routing.Error.DUTY_CYCLE_LIMIT) refuse(rowId, attempts)
                else finish(rowId, MessageState.FAILED, "The radio refused: ${reason.routingError}.", StatNames.TX_FAILED)
            }
            else -> finish(rowId, MessageState.FAILED, "Send failed: $reason.", StatNames.TX_FAILED)
        }
    }

    /** The radio's own airtime limit said no. Show it, wait, try again. */
    private suspend fun refuse(rowId: Long, attempts: Int) {
        stats.add(StatNames.TX_REFUSED_AIRTIME)
        val row = messages.get(rowId) ?: return
        val n = refusalsOf(attempts) + 1
        val isMayday = row.type == "mayday"
        if (n > maxRefusalRetries && !isMayday) {
            finish(rowId, MessageState.FAILED, "The radio kept refusing because of the airtime limit. Send it again later.", StatNames.TX_FAILED)
            return
        }
        val wait = if (isMayday) 20_000L else refusalDelaysMs[(n - 1).coerceAtMost(refusalDelaysMs.lastIndex)]
        val at = clock() + wait
        messages.update(
            row.copy(
                state = MessageState.REFUSED_AIRTIME, attempts = linkResendsOf(attempts) * LINK_UNIT + n, retryAtMs = at,
                detail = "The radio refused to send: airtime limit reached. Trying again in ${(wait + 59_999) / 60_000} min.",
            ),
        )
        scope.launch {
            delay(wait)
            mutex.withLock { requeue(rowId, null) }
        }
    }

    private suspend fun requeue(rowId: Long, detail: String?, delayMs: Long = 0) {
        if (delayMs > 0) {
            scope.launch {
                delay(delayMs)
                mutex.withLock { requeue(rowId, detail) }
            }
            return
        }
        val row = messages.get(rowId) ?: return
        if (row.state == MessageState.CANCELLED) return
        val canonical = row.json?.let { Json.parseObject(it) } ?: return
        val next = row.copy(state = MessageState.QUEUED, detail = detail, retryAtMs = null)
        if (next != row) messages.update(next)
        rowBySeq[queue.enqueue(canonical, row.bytes).seq] = rowId
        wake.trySend(Unit)
    }

    private suspend fun finish(rowId: Long, state: String, detail: String?, stat: String?) {
        val row = messages.get(rowId) ?: return
        // A late "delivered" must not overwrite a refusal or a failure that is already final, but it may
        // upgrade "not confirmed" (the answer can arrive after the timeout).
        if (state == MessageState.DELIVERED && row.state in setOf(MessageState.CANCELLED, MessageState.FAILED)) return
        messages.update(row.copy(state = state, detail = detail, retryAtMs = null))
        if (stat != null) stats.add(stat)
    }

    /** Drops rows whose queue entry disappeared (too old, or replaced by a newer version). */
    private suspend fun reconcile() {
        queue.peek() // drops stale entries
        val present = queue.snapshot().map { it.seq }.toSet()
        for ((seq, rowId) in rowBySeq.toList()) {
            if (seq in present) continue
            rowBySeq.remove(seq)
            val row = messages.get(rowId) ?: continue
            if (row.state in MessageState.WAITING) {
                messages.update(row.copy(state = MessageState.EXPIRED, detail = "Not sent: too old, or replaced by a newer message.", retryAtMs = null))
            }
        }
    }

    private suspend fun markHeld(waitMs: Long) {
        val at = clock() + waitMs
        for (seq in queue.snapshot().map { it.seq }) {
            val row = rowBySeq[seq]?.let { messages.get(it) } ?: continue
            if (row.state == MessageState.QUEUED) {
                messages.update(
                    row.copy(
                        state = MessageState.HELD, retryAtMs = at,
                        detail = "Waiting for airtime. Free in about ${(waitMs + 59_999) / 60_000} min.",
                    ),
                )
            } else if (row.state == MessageState.HELD) {
                messages.update(row.copy(retryAtMs = at))
            }
        }
    }

    private fun publishAirtime() {
        _airtime.value = AirtimeStatus(queue.budget.usedMs(clock()), queue.budget.limitMs)
    }

    // --------------------------------------------------------------- receiving

    private suspend fun onPacket(p: MeshPacket, self: Long, radio: List<RadioChannel>, showPublic: Boolean) {
        val d = p.decoded ?: return
        if (d.portnum != PortNum.TEXT_MESSAGE_APP && d.portnum != PortNum.PRIVATE_APP) return
        val from = p.from.toLong() and 0xFFFFFFFFL
        if (from == self) return
        // Port 256 is free for any app, and other teams use it on their channels too: only the channels we show.
        val to = p.to.toLong() and 0xFFFFFFFFL
        val inbound = Channels.inbound(to, from, p.channel, self, radio, showPublic)
        if (inbound == null) {
            if (d.portnum == PortNum.PRIVATE_APP && to == MeshText.BROADCAST) stats.add(StatNames.RX_OTHER_CHANNEL)
            return
        }
        if (d.portnum == PortNum.TEXT_MESSAGE_APP) return onText(p, d.payload.toByteArray(), from, inbound)
        val ch = inbound.channel
        val bytes = d.payload.toByteArray()
        val now = clock()
        val hops = if (p.hop_start > 0) (p.hop_start - p.hop_limit).coerceAtLeast(0) else null
        val canonical = try {
            RescueCodec.decodeAny(bytes)
        } catch (e: CodecException) {
            stats.add(StatNames.RX_INVALID)
            messages.insert(
                MessageEntity(
                    dir = "in", node = from, packetId = p.id.toLong() and 0xFFFFFFFFL, type = "unknown", bytes = bytes, json = null,
                    atMs = now, state = MessageState.INVALID, detail = "${e.code}${e.field?.let { " ($it)" } ?: ""}",
                    snr = p.rx_snr, rssi = p.rx_rssi, hops = hops,
                    channelIndex = ch.index, channelName = ch.name, channelPublic = ch.isPublic, peer = inbound.peer,
                ),
            )
            return
        }
        routes.heardRescueCodec(from, ch.name)
        val type = RescueCodec.messageType(canonical)
        val outcome = tracker.onReceived(from, canonical)
        val duplicate = when (outcome) {
            Outcome.DUPLICATE -> true
            Outcome.IGNORED -> canonical["ack"] != null || (RequestTracker.taskType(canonical) == null && tracker.isDuplicatePayload(from, bytes))
            else -> false
        }
        if (duplicate) {
            stats.add(StatNames.RX_DUPLICATES)
            return
        }
        stats.add(StatNames.RX_MESSAGES)
        stats.add(StatNames.RX_BYTES, bytes.size.toLong())
        val taskKey: TaskKey? = when {
            canonical["ack"] is Map<*, *> -> (canonical["ack"] as Map<*, *>).let { a ->
                val ref = (a["ref"] as? Number)?.toInt()
                val node = RequestTracker.parseNode(a["node"] as? String)
                if (ref != null && node != null) TaskKey(node, ref) else null
            }
            RequestTracker.taskType(canonical) != null -> TaskKey(from, (canonical["id"] as Number).toInt())
            else -> null
        }
        messages.insert(
            MessageEntity(
                dir = "in", node = from, packetId = p.id.toLong() and 0xFFFFFFFFL, type = type,
                msgId = (canonical["id"] as? Number)?.toInt() ?: 0, bytes = bytes, json = Json.stringify(canonical),
                atMs = now, state = MessageState.RECEIVED, taskKey = taskKey?.toString(),
                snr = p.rx_snr, rssi = p.rx_rssi, hops = hops,
                channelIndex = ch.index, channelName = ch.name, channelPublic = ch.isPublic, peer = inbound.peer,
            ),
        )
        if (taskKey != null) {
            // The report's own channel wins; an answer that came first only fills the gap until the report arrives.
            if (RequestTracker.taskType(canonical) != null) taskChannel[taskKey] = ch
            else if (taskKey !in taskChannel && tasks.get(taskKey.toString())?.channelName == null) taskChannel[taskKey] = ch
            persistTask(taskKey, self)
            val key = taskKey.toString()
            if (MaydayAlerts.shouldAlert(type, from, self, outcome, tracker[taskKey]?.state, key in alerted)) {
                alerted.add(key)
                alerts.show(key, from, MaydayAlerts.reasonOf(canonical), ch.name)
            }
        }
    }

    /**
     * A normal Meshtastic text (port 1), for example from the official app (spec section 2.1), already checked by
     * [Channels.inbound]: a broadcast on a channel we show, or a direct message to us.
     * A gateway's readable copy of a RescueCodec message and the same packet twice count as duplicates.
     */
    private suspend fun onText(p: MeshPacket, bytes: ByteArray, from: Long, inbound: Inbound) {
        val ch = inbound.channel
        val text = String(bytes, Charsets.UTF_8)
        if (text.isBlank()) return
        val packetId = p.id.toLong() and 0xFFFFFFFFL
        val now = clock()
        if (MeshText.isCopy(text)) {
            stats.add(StatNames.RX_DUPLICATES)
            return
        }
        // Someone writing normal texts on a channel may not read packed ones: auto mode sends them normal text.
        if (inbound.peer == null) routes.heardText(from, ch.name)
        if (textSeenBefore(from, packetId, bytes, now)) {
            stats.add(StatNames.RX_DUPLICATES)
            return
        }
        stats.add(StatNames.RX_MESSAGES)
        stats.add(StatNames.RX_BYTES, bytes.size.toLong())
        messages.insert(
            MessageEntity(
                dir = "in", node = from, packetId = packetId, type = "text", bytes = bytes,
                json = Json.stringify(MeshText.message(text)), atMs = now, state = MessageState.RECEIVED,
                snr = p.rx_snr, rssi = p.rx_rssi, hops = if (p.hop_start > 0) (p.hop_start - p.hop_limit).coerceAtLeast(0) else null,
                source = MeshText.SOURCE,
                channelIndex = ch.index, channelName = ch.name, channelPublic = ch.isPublic, peer = inbound.peer,
            ),
        )
    }

    /**
     * Dedupe for plain texts: the same (sender, Meshtastic packet id) within [RequestTracker.dedupeMs] (24 h), or
     * without a packet id the same (sender, bytes) within 10 minutes. Remembers a new text and returns false.
     */
    private fun textSeenBefore(from: Long, packetId: Long, bytes: ByteArray, now: Long): Boolean {
        val key = if (packetId != 0L) "%08x:%08x".format(from, packetId)
        else "%08x:%s".format(from, bytes.joinToString("") { "%02x".format(it) })
        val ttl = if (packetId != 0L) tracker.dedupeMs else tracker.payloadDedupeMs
        if (textsSeen.size > MAX_TEXTS_SEEN) textsSeen.entries.removeAll { now - it.value > tracker.dedupeMs }
        while (textsSeen.size > MAX_TEXTS_SEEN) textsSeen.remove(textsSeen.keys.first())
        val seen = textsSeen[key]
        if (seen != null && now - seen < ttl) return true
        textsSeen[key] = now
        return false
    }

    // ------------------------------------------------------------------- tasks

    private suspend fun persistTask(key: TaskKey, self: Long) {
        val t: Task = tracker[key] ?: return
        val ch = channelOfTask(key)
        val answers = t.answers.map { a ->
            linkedMapOf<String, Any?>("from" to a.from, "status" to a.status, "team" to a.team, "eta" to a.etaMin, "at" to a.atMs)
        }
        tasks.upsert(
            TaskEntity(
                key = key.toString(), node = key.node, msgId = key.id, type = t.type,
                json = t.message?.let { Json.stringify(it) }, local = t.local,
                state = stateName(t.state), meshDelivered = t.meshDelivered,
                createdMs = t.createdMs, updatedMs = t.updatedMs, answersJson = Json.stringify(answers),
                ownStatus = if (self == 0L) null else t.answers.lastOrNull { it.from == self }?.status,
                rank = if (t.type == "mayday") 0 else 1,
                channelIndex = ch?.index, channelName = ch?.name, channelPublic = ch?.isPublic ?: false,
            ),
        )
        if (MaydayAlerts.shouldClear(t.type, t.state)) alerts.clear(key.toString())
    }

    /** The channel of task [key]: from this run, else the one stored with the task. Null if unknown (the team channel). */
    private suspend fun channelOfTask(key: TaskKey): ChannelRef? =
        taskChannel[key] ?: tasks.get(key.toString())?.takeIf { it.channelName != null }?.let { ChannelRef(it.channelIndex, it.channelName, it.channelPublic) }
            ?.also { taskChannel[key] = it }

    /** Our own Accept, Decline or Done counts as soon as we make it, and again when it is really sent. */
    private suspend fun applyOwnAck(msg: Map<String, Any?>, self: Long) {
        val ack = msg["ack"] as? Map<*, *> ?: return
        if (self == 0L) return
        tracker.onReceived(self, msg)
        val ref = (ack["ref"] as? Number)?.toInt() ?: return
        val node = RequestTracker.parseNode(ack["node"] as? String) ?: return
        persistTask(TaskKey(node, ref), self)
    }

    // ---------------------------------------------------------------- restoring

    private suspend fun restore() {
        val since = clock() - tracker.dedupeMs
        try {
            for (row in messages.replaySince(since)) {
                val msg = Json.parseObject(row.json ?: continue)
                replayAt = row.atMs
                if (row.dir == "in" && MeshText.isPlain(row)) {
                    textSeenBefore(row.node, row.packetId ?: 0L, row.bytes, row.atMs)
                    continue
                }
                if (row.dir == "out") {
                    if (row.node == 0L) continue
                    if (row.atMs > clock() - queue.budget.windowMs) queue.budget.record(row.bytes.size, row.atMs)
                    if (row.state == MessageState.SENT || row.state == MessageState.DELIVERED || row.state == MessageState.NOT_CONFIRMED) {
                        if (RequestTracker.taskType(msg) != null) {
                            tracker.onSent(row.node, msg)
                            if (row.state == MessageState.DELIVERED) tracker.onMeshDelivered(row.node, row.msgId)
                        } else if (msg["ack"] != null) {
                            tracker.onReceived(row.node, msg)
                        }
                    }
                } else {
                    tracker.onReceived(row.node, msg)
                }
            }
        } finally {
            replayAt = null
        }
        for (row in messages.waiting()) {
            val canonical = row.json?.let { Json.parseObject(it) } ?: continue
            applyOwnAck(canonical, settings.lastOwnNode)
            val retryIn = (row.retryAtMs ?: 0) - clock()
            if (row.state == MessageState.REFUSED_AIRTIME && retryIn > 0) {
                scope.launch {
                    delay(retryIn)
                    mutex.withLock { requeue(row.id, null) }
                }
            } else {
                rowBySeq[queue.enqueue(canonical, row.bytes).seq] = row.id
                if (row.state != MessageState.QUEUED) messages.update(row.copy(state = MessageState.QUEUED, retryAtMs = null, detail = null))
            }
        }
        wake.trySend(Unit)
    }

    // ------------------------------------------------------------------ helpers

    private fun currentNode(): Long {
        val c = clientFlow.value
        val n = if (c != null) nodeOf(c) else 0L
        return if (n != 0L) n else settings.lastOwnNode
    }

    /** Works out the channel again from the radio's channel list and the setting, and publishes it. */
    private fun evaluateChannel(client: RadioClient?): ChannelChoice {
        val preset = client?.let { presetOf(it) } ?: ""
        val choice = if (client == null) {
            ChannelChoice.Waiting("No radio connected.")
        } else {
            ChannelChoice.resolve(client.channels.value, settings.channelIndex, preset)
        }
        _channel.value = choice
        _channelOptions.value = ChannelChoice.options(client?.channels?.value, preset)
        if (choice is ChannelChoice.Ready && choice.name.isNotBlank() && settings.lastTeamChannel != choice.name) settings.lastTeamChannel = choice.name
        val list = client?.channels?.value?.takeIf { it.isNotEmpty() }?.let { Channels.list(it, preset) }
        if (list != null && list != _radioChannels.value) {
            _radioChannels.value = list
            settings.lastRadioChannels = list.joinToString("\n") { "${it.index}|${if (it.isPublic) 1 else 0}|${it.name}" }
        }
        return choice
    }

    private fun loadRadioChannels(): List<RadioChannel>? = settings.lastRadioChannels?.lines()?.mapNotNull { l ->
        val p = l.split('|', limit = 3)
        if (p.size != 3) null else p[0].toIntOrNull()?.let { RadioChannel(it, p[2], p[1] == "1") }
    }?.takeIf { it.isNotEmpty() }

    /** Where an outgoing row goes now: a channel of the radio, wait (with the reason), or never (with the reason). */
    private sealed interface Route {
        data class Go(val channel: ChannelRef) : Route
        data class Wait(val why: String) : Route
        data class Refuse(val why: String) : Route
    }

    private fun route(row: MessageEntity, radio: List<RadioChannel>, choice: ChannelChoice): Route {
        if (row.peer != null) {
            // A direct message goes on the channel the radio heard that node on (Meshtastic does the same), else the
            // primary channel. With current firmware the radio encrypts it for that node alone.
            val idx = nodes?.channelOf(row.peer) ?: 0
            val ch = radio.firstOrNull { it.index == idx } ?: radio.firstOrNull { it.index == 0 }
            return Route.Go(ch?.let { ChannelRef.of(it) } ?: ChannelRef(idx, null, false))
        }
        val ch = if (row.channelName == null) {
            val c = choice as? ChannelChoice.Ready ?: return Route.Wait(choice.text())
            radio.firstOrNull { it.index == c.index } ?: return Route.Wait(choice.text())
        } else {
            radio.firstOrNull { it.name == row.channelName }
                ?: return Route.Wait("Channel \"${row.channelName}\" is not on this radio. The message waits until it is.")
        }
        if (ch.isPublic && !MeshText.isPlain(row) && row.type != "ack") return Route.Refuse("Reports never go on a public channel.")
        return Route.Go(ChannelRef.of(ch))
    }

    /** Takes queued messages that cannot go now out of the queue for a while ([parkMs]), with the reason on the row. */
    private suspend fun parkUnroutable(radio: List<RadioChannel>, choice: ChannelChoice) {
        for (q in queue.snapshot()) {
            val rowId = rowBySeq[q.seq] ?: continue
            val row = messages.get(rowId) ?: continue
            when (val r = route(row, radio, choice)) {
                is Route.Go -> Unit
                is Route.Wait -> {
                    queue.cancel(q.seq)
                    rowBySeq.remove(q.seq)
                    if (row.detail != r.why) messages.update(row.copy(detail = r.why))
                    requeue(rowId, r.why, delayMs = parkMs)
                }
                is Route.Refuse -> {
                    queue.cancel(q.seq)
                    rowBySeq.remove(q.seq)
                    finish(rowId, MessageState.FAILED, r.why, StatNames.TX_FAILED)
                }
            }
        }
    }

    /** Call after the person changed the channel setting. */
    fun refreshChannel() {
        evaluateChannel(clientFlow.value)
        wake.trySend(Unit)
    }

    /** The modem preset's name ("LongFast"): the name of a channel that has none. */
    private fun presetOf(client: RadioClient): String =
        Channels.presetName(client.configBundle.value?.configs?.firstNotNullOfOrNull { it.lora })

    companion object {
        private const val DUTY_CYCLE_LIMIT_RES = 9

        /** A 2-byte id (128 and up): the size of the real id that is added when a message is sent. */
        private const val ID_PROBE = 128

        /** Plain texts remembered for dedupe at most (the team channel is quiet; this only bounds memory). */
        private const val MAX_TEXTS_SEEN = 2_000

        /** How often a message that was handed to a link that then dropped is sent again. */
        private const val LINK_RESENDS = 1

        /**
         * `attempts` of a row holds two counters, so no new column is needed: airtime refusals in the low part
         * (0..99) and link resends in units of [LINK_UNIT]. A message that was refused for airtime and then loses
         * its link still gets its one resend.
         */
        private const val LINK_UNIT = 100
        internal fun refusalsOf(attempts: Int) = attempts % LINK_UNIT
        internal fun linkResendsOf(attempts: Int) = attempts / LINK_UNIT

        /** Own moves from spec section 15.1: current own status to allowed next statuses. */
        val ALLOWED_MOVES: Map<String, Set<String>> = mapOf(
            "open" to setOf("accepted", "declined"),
            "accepted" to setOf("done", "declined"),
            "declined" to setOf("accepted"),
            "done" to emptySet(),
        )

        fun parseKey(key: String): TaskKey? {
            val p = key.split('-')
            if (p.size != 2) return null
            val node = p[0].toLongOrNull(16) ?: return null
            val id = p[1].toIntOrNull() ?: return null
            return TaskKey(node, id)
        }

        fun stateName(s: TaskState): String = when (s) {
            TaskState.OPEN -> "open"
            TaskState.ACCEPTED -> "accepted"
            TaskState.DECLINED -> "declined"
            TaskState.DONE -> "done"
            TaskState.DELIVERY_UNKNOWN -> "delivery_unknown"
        }

        /** Meshtastic priority for a RescueCodec priority name (spec section 2). */
        fun meshPriority(name: String): MeshPacket.Priority = when (name) {
            "critical" -> MeshPacket.Priority.ALERT
            "urgent" -> MeshPacket.Priority.HIGH
            "routine" -> MeshPacket.Priority.BACKGROUND
            else -> MeshPacket.Priority.RELIABLE
        }

        /** Team label cut to 12 bytes of UTF-8 (the limit of the `team` field), or null if empty. */
        fun teamLabelFor(label: String): String? {
            var s = label.trim()
            while (s.isNotEmpty() && s.toByteArray(Charsets.UTF_8).size > 12) s = s.dropLast(1)
            return s.ifEmpty { null }
        }

        /** Node number of the radio behind [client], or 0 if not known yet. */
        fun nodeOf(client: RadioClient): Long {
            val n = client.configBundle.value?.myInfo?.my_node_num ?: client.ownNode.value?.num ?: 0
            return n.toLong() and 0xFFFFFFFFL
        }
    }
}

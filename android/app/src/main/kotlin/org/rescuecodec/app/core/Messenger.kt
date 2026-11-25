// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.core

import kotlinx.coroutines.CancellationException
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
import org.rescuecodec.app.data.MessageDao
import org.rescuecodec.app.data.MessageEntity
import org.rescuecodec.app.data.MessageState
import org.rescuecodec.app.data.StatDao
import org.rescuecodec.app.data.StatNames
import org.rescuecodec.app.data.TaskDao
import org.rescuecodec.app.data.TaskEntity
import org.rescuecodec.app.data.add
import org.rescuecodec.codec.CodecException
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
 * Send: [submit] puts a message into the [SendQueue] of `:engine` (Mayday first, airtime budget).
 * A loop hands queued messages to the radio as PRIVATE_APP (port 256) broadcasts on the team
 * channel, with `want_ack` and a Meshtastic priority taken from the message priority (spec
 * section 2). The radio's answer decides the state of the message: sent, delivered (a neighbour
 * repeated it), not confirmed, or refused because of the legal airtime limit (we try again later).
 *
 * Receive: every PRIVATE_APP packet is decoded and checked for duplicates. Reports become tasks,
 * Acks update tasks. Everything is written to the database, so a restart loses nothing.
 *
 * Tasks: [answer] sends Accept, Decline or Done as an Ack and applies it locally at once.
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
    /** Airtime budget of the send queue. The default follows the region and preset in the settings. */
    budget: AirtimeBudget = AirtimeBudget(Airtime(settings.modemPreset), settings.region),
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

    private val _airtime = MutableStateFlow(AirtimeStatus(0.0, queue.budget.limitMs))

    /** Airtime used in the last hour and the legal limit (estimated). */
    val airtime: StateFlow<AirtimeStatus> = _airtime.asStateFlow()

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
     * Puts [message] (canonical JSON shape as a map) into the send queue. Found, Request, Hazard and
     * Mayday get an id if they have none. [source] says who asked: "app" or a package name.
     */
    suspend fun submit(message: Map<String, Any?>, source: String = "app"): SubmitResult {
        val input = LinkedHashMap(message)
        val type = try {
            RescueCodec.messageType(input)
        } catch (e: CodecException) {
            return SubmitResult.Rejected(e.code, e.field, e.message ?: e.code)
        }
        if (RequestTracker.taskType(input) == null && type in TASK_TYPES && (input["id"] as? Number)?.toInt().let { it == null || it == 0 }) {
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
            )
            val rowId = messages.insert(row)
            val q = queue.enqueue(canonical, bytes)
            rowBySeq[q.seq] = rowId
            wake.trySend(Unit)
            SubmitResult.Queued(rowId, type, msgId, bytes, RescueCodec.reconstruct(canonical, settings.language))
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
        mutex.withLock {
            val task = tracker[taskKey] ?: return AnswerResult.UnknownTask
            val own = task.answers.lastOrNull { it.from == self }?.status ?: "open"
            if (status !in ALLOWED_MOVES[own].orEmpty()) return AnswerResult.InvalidMove(own, status)
            ack = RequestTracker.ackFor(task, status, etaMin, teamLabelFor(settings.teamLabel))
        }
        return when (val r = submit(ack, source = "app")) {
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
            if (client == null) return@collectLatest
            val self = nodeOf(client)
            if (self != 0L) settings.lastOwnNode = self
            sendLoop(client, self)
        }
    }

    private suspend fun receiveLoop() {
        attachedFlow.collectLatest { client ->
            if (client == null) return@collectLatest
            try {
                client.packets.collect { p ->
                    try {
                        val self = nodeOf(client).takeIf { it != 0L } ?: settings.lastOwnNode
                        mutex.withLock { onPacket(p, self) }
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
                mutex.withLock {
                    reconcile()
                    val q = queue.poll()
                    if (q != null) {
                        item = q to (rowBySeq.remove(q.seq) ?: -1L)
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
                if (next.second >= 0) withContext(NonCancellable) { dispatch(client, self, next.first, next.second) }
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

    private suspend fun dispatch(client: RadioClient, self: Long, q: QueuedMessage, rowId: Long) {
        val row = messages.get(rowId) ?: return
        val canonical = q.message
        val type = row.type
        val prio = meshPriority(RescueCodec.effectivePriority(canonical))
        val packet = MeshPacket.Builder().also { b ->
            b.to = NodeId.BROADCAST.raw
            b.channel = channelIndex(client)
            b.want_ack = true
            b.priority = prio
            b.decoded = Data.Builder().also { d ->
                d.portnum = PortNum.PRIVATE_APP
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
                    atMs = clock(),
                ),
            )
            if (key != null) {
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
                if (sawSent) finish(rowId, MessageState.NOT_CONFIRMED, "The link to the radio dropped while sending. It may have gone out.", StatNames.TX_NOT_CONFIRMED)
                else requeue(rowId, "The link to the radio dropped. Waiting.")
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
        val n = attempts + 1
        val isMayday = row.type == "mayday"
        if (n > maxRefusalRetries && !isMayday) {
            finish(rowId, MessageState.FAILED, "The radio kept refusing because of the airtime limit. Send it again later.", StatNames.TX_FAILED)
            return
        }
        val wait = if (isMayday) 20_000L else refusalDelaysMs[(n - 1).coerceAtMost(refusalDelaysMs.lastIndex)]
        val at = clock() + wait
        messages.update(
            row.copy(
                state = MessageState.REFUSED_AIRTIME, attempts = n, retryAtMs = at,
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
        messages.update(row.copy(state = MessageState.QUEUED, detail = detail, retryAtMs = null))
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

    private suspend fun onPacket(p: MeshPacket, self: Long) {
        val d = p.decoded ?: return
        if (d.portnum != PortNum.PRIVATE_APP) return
        val from = p.from.toLong() and 0xFFFFFFFFL
        if (from == self) return
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
                ),
            )
            return
        }
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
            ),
        )
        if (taskKey != null) persistTask(taskKey, self)
    }

    // ------------------------------------------------------------------- tasks

    private suspend fun persistTask(key: TaskKey, self: Long) {
        val t: Task = tracker[key] ?: return
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
            ),
        )
    }

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

    private fun channelIndex(client: RadioClient): Int {
        val fixed = settings.channelIndex
        if (fixed >= 0) return fixed
        val byName = client.channels.value?.firstOrNull { it.settings?.name == AppSettings.CHANNEL_NAME }
        return byName?.index ?: 0
    }

    companion object {
        private val TASK_TYPES = setOf("found", "request", "hazard", "mayday")
        private const val DUTY_CYCLE_LIMIT_RES = 9

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

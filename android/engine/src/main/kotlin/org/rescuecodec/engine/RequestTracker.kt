// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.engine

/** Where a task stands. */
enum class TaskState {
    /** Sent or received, nobody has answered yet. */
    OPEN,

    /** At least one team took it. */
    ACCEPTED,

    /** Only declines so far. Another team may still accept. */
    DECLINED,

    /** A team reported it done. Final. */
    DONE,

    /** We sent it, and neither the mesh nor a person confirmed anything before the timeout. It may not have arrived. */
    DELIVERY_UNKNOWN,
}

/** Identity of a task: the sender's Meshtastic node number and the sender's message id. */
data class TaskKey(val node: Long, val id: Int) {
    /** Text form from spec section 15, for example `a1b2c3d4-7`. */
    override fun toString() = "%08x-%d".format(node, id)
}

/** One answer (Ack) from a team. */
data class Answer(val from: Long, val status: String, val team: String?, val etaMin: Int?, val atMs: Long)

/** Snapshot of a task. [message] is null when only an answer has arrived so far. */
data class Task(
    val key: TaskKey,
    val type: String,
    val message: Map<String, Any?>?,
    val state: TaskState,
    val local: Boolean,
    val meshDelivered: Boolean,
    val createdMs: Long,
    val updatedMs: Long,
    val answers: List<Answer>,
)

/** What [RequestTracker.onReceived] did with a message. */
enum class Outcome {
    /** First time we see this report. */
    NEW,

    /** Same (sender, id) seen before: ignored. */
    DUPLICATE,

    /** An Ack changed or added an answer. */
    ANSWERED,

    /** An Ack that changes nothing (repeat), or a message that is not tracked (sector, track, text). */
    IGNORED,
}

/**
 * Request and answer tracking: dedupe and the state machine.
 *
 * Found, Request, Hazard and Mayday messages are tasks with key (sender node, id). Acks answer a
 * task. State rules:
 * - the newest answer of each sender counts; any `done` makes the task DONE (final, a late
 *   `accepted` or `declined` cannot reopen it);
 * - else any `accepted` makes it ACCEPTED, else any `declined` makes it DECLINED;
 * - with no answer it is OPEN, or DELIVERY_UNKNOWN when we sent it ([onSent]), the mesh did not
 *   confirm ([onMeshDelivered]) and the timeout passed ([tick]).
 *
 * Dedupe: the same (sender, id) within [dedupeMs] (24 h) is a duplicate. Messages without an id
 * can be deduped by payload within [payloadDedupeMs] (10 min) with [isDuplicatePayload].
 *
 * Answers for a task we never saw are kept as placeholders (the report may still arrive). They are bounded:
 * at most [MAX_PLACEHOLDERS], and one that no report filled in for [PLACEHOLDER_TTL_MS] is forgotten, so a flood of
 * Acks cannot grow memory. Trust: the private channel key is the only trust boundary. Anyone who holds it can send
 * an Ack for any task, and a `done` closes the task for everyone.
 *
 * Same (sender, id) means the same report again (a retransmit), never an update. A sender who changes a report
 * sends it with a new id.
 *
 * Not thread-safe: call it from one thread, or lock outside.
 */
class RequestTracker(
    private val clock: () -> Long = System::currentTimeMillis,
    /** Time without any confirmation after which a sent task becomes DELIVERY_UNKNOWN. */
    private val timeoutMs: (type: String) -> Long = { if (it == "mayday") 90_000L else 300_000L },
    val dedupeMs: Long = 24 * 3_600_000L,
    val payloadDedupeMs: Long = 10 * 60_000L,
) {
    private class Entry(val key: TaskKey, var type: String, var message: Map<String, Any?>?, val local: Boolean, val created: Long) {
        var updated = created
        var meshDelivered = false
        val latest = LinkedHashMap<Long, Answer>() // newest answer per sender
        val history = ArrayList<Answer>()
        var timedOut = false
    }

    private val tasks = LinkedHashMap<TaskKey, Entry>()
    private val payloads = HashMap<String, Long>()

    /** Registers a task we send ourselves. [selfNode] is our Meshtastic node number. */
    fun onSent(selfNode: Long, message: Map<String, Any?>): Task? {
        purge(clock())
        val type = taskType(message) ?: return null
        val id = (message["id"] as Number).toInt()
        val key = TaskKey(selfNode, id)
        val e = tasks.getOrPut(key) { Entry(key, type, message, true, clock()) }
        e.message = message
        e.type = type
        return snapshot(e)
    }

    /** The mesh confirmed delivery of our task to a neighbour (Meshtastic routing ack). */
    fun onMeshDelivered(selfNode: Long, id: Int) {
        tasks[TaskKey(selfNode, id)]?.let {
            it.meshDelivered = true
            it.updated = clock()
        }
    }

    /**
     * Handles a decoded message from [sender] (the Meshtastic `from` node number).
     * Reports become tasks, Acks answer them.
     */
    fun onReceived(sender: Long, message: Map<String, Any?>): Outcome {
        val now = clock()
        purge(now)
        message["ack"]?.let { return onAck(sender, message["ack"] as Map<*, *>, now) }
        val type = taskType(message) ?: return Outcome.IGNORED
        val key = TaskKey(sender, (message["id"] as Number).toInt())
        val existing = tasks[key]
        if (existing != null && existing.message != null) return Outcome.DUPLICATE
        if (existing != null) { // an answer arrived before the report
            existing.message = message
            existing.type = type
            existing.updated = now
            return Outcome.NEW
        }
        tasks[key] = Entry(key, type, message, false, now)
        return Outcome.NEW
    }

    private fun onAck(sender: Long, ack: Map<*, *>, now: Long): Outcome {
        val ref = (ack["ref"] as? Number)?.toInt() ?: return Outcome.IGNORED
        val node = parseNode(ack["node"] as? String) ?: return Outcome.IGNORED
        val status = ack["status"] as? String ?: return Outcome.IGNORED
        if (status != "accepted" && status != "declined" && status != "done") return Outcome.IGNORED
        val key = TaskKey(node, ref)
        val isNew = key !in tasks
        val e = tasks.getOrPut(key) { Entry(key, "unknown", null, false, now) }
        if (isNew) purgePlaceholders(now, keep = e)
        val before = e.latest[sender]
        if (before != null && before.status == status && before.team == (ack["team"] as? String)) return Outcome.IGNORED
        val a = Answer(sender, status, ack["team"] as? String, (ack["eta"] as? Number)?.toInt(), now)
        e.latest[sender] = a
        e.history.add(a)
        e.updated = now
        return Outcome.ANSWERED
    }

    /** Moves overdue sent tasks to DELIVERY_UNKNOWN. Returns the tasks that changed. Call about once a second. */
    fun tick(): List<Task> {
        val now = clock()
        val changed = ArrayList<Task>()
        for (e in tasks.values) {
            if (e.local && !e.timedOut && !e.meshDelivered && e.latest.isEmpty() && now - e.created >= timeoutMs(e.type)) {
                e.timedOut = true
                e.updated = now
                changed.add(snapshot(e))
            }
        }
        return changed
    }

    operator fun get(key: TaskKey): Task? = tasks[key]?.let(::snapshot)

    /** All tasks: Mayday first, then open ones before answered ones, newest first. */
    fun tasks(): List<Task> = tasks.values.map(::snapshot).sortedWith(
        compareBy<Task>({ it.type != "mayday" }, { it.state == TaskState.DONE }, { -it.updatedMs }),
    )

    /** True if [payload] from [sender] was seen within [payloadDedupeMs]. Records it. For messages without an id. */
    fun isDuplicatePayload(sender: Long, payload: ByteArray): Boolean {
        val now = clock()
        payloads.values.removeAll { now - it > payloadDedupeMs }
        val k = "%08x:%s".format(sender, payload.joinToString("") { "%02x".format(it) })
        if (payloads.containsKey(k)) return true
        payloads[k] = now
        return false
    }

    private fun purge(now: Long) {
        tasks.values.removeAll { now - it.created > dedupeMs && now - it.updated > dedupeMs }
        purgePlaceholders(now, keep = null)
    }

    /** Drops placeholders (answers without a report) that are too old, then the oldest ones beyond the cap. */
    private fun purgePlaceholders(now: Long, keep: Entry?) {
        tasks.values.removeAll { it.message == null && !it.local && it !== keep && now - it.updated > PLACEHOLDER_TTL_MS }
        val stubs = tasks.values.filter { it.message == null && !it.local }
        if (stubs.size > MAX_PLACEHOLDERS) {
            stubs.filter { it !== keep }.sortedBy { it.updated }.take(stubs.size - MAX_PLACEHOLDERS).forEach { tasks.remove(it.key) }
        }
    }

    private fun snapshot(e: Entry): Task {
        val answers = e.latest.values
        val state = when {
            answers.any { it.status == "done" } || e.history.any { it.status == "done" } -> TaskState.DONE
            answers.any { it.status == "accepted" } -> TaskState.ACCEPTED
            answers.any { it.status == "declined" } -> TaskState.DECLINED
            e.timedOut -> TaskState.DELIVERY_UNKNOWN
            else -> TaskState.OPEN
        }
        return Task(e.key, e.type, e.message, state, e.local, e.meshDelivered, e.created, e.updated, e.history.toList())
    }

    companion object {
        private val TASK_TYPES = setOf("found", "request", "hazard", "mayday")

        /** Most answers-without-report we keep. */
        const val MAX_PLACEHOLDERS = 500

        /** An answer whose report never came is forgotten after this long. */
        const val PLACEHOLDER_TTL_MS = 30 * 60_000L

        /** Message type if it is a task (has a body of a taskable type and an id), else null. */
        fun taskType(message: Map<String, Any?>): String? {
            val type = TASK_TYPES.firstOrNull { message.containsKey(it) } ?: return null
            return if ((message["id"] as? Number)?.toInt()?.let { it > 0 } == true) type else null
        }

        /** "!a1b2c3d4" to the node number. */
        fun parseNode(s: String?): Long? =
            if (s != null && s.length == 9 && s[0] == '!') s.substring(1).toLongOrNull(16) else null

        /** Builds the Ack message that answers [task]. [status] is accepted, declined or done. */
        fun ackFor(task: Task, status: String, etaMin: Int? = null, team: String? = null): Map<String, Any?> {
            val ack = linkedMapOf<String, Any?>("ref" to task.key.id, "node" to "!%08x".format(task.key.node), "status" to status)
            if (etaMin != null && etaMin > 0) ack["eta"] = etaMin
            if (!team.isNullOrEmpty()) ack["team"] = team
            return linkedMapOf("ack" to ack)
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.rescuecodec.app.AppGraph
import org.rescuecodec.app.core.AirtimeStatus
import org.rescuecodec.app.core.AnswerResult
import org.rescuecodec.app.core.Messenger
import org.rescuecodec.app.data.MessageEntity
import org.rescuecodec.app.data.StatNames
import org.rescuecodec.app.data.TaskEntity
import org.rescuecodec.app.radio.LinkState
import org.rescuecodec.app.radio.RadioTarget
import org.rescuecodec.app.service.RadioService
import org.rescuecodec.codec.Json
import org.rescuecodec.codec.RescueCodec

/** Radio connection: state, scan, connect, disconnect. */
class ConnectionViewModel(app: Application, private val graph: AppGraph) : AndroidViewModel(app) {
    val state: StateFlow<LinkState> = graph.link.state

    /** The radio we connected to last, or null. */
    fun lastRadio(): RadioTarget? = graph.link.lastRadio()

    fun scan() = graph.link.startScan()
    fun stopScan() = graph.link.stopScan()

    /** Starts the background service, which connects. */
    fun connect(target: RadioTarget) = RadioService.start(getApplication(), target)

    fun connectLast() = RadioService.start(getApplication(), null)

    fun disconnect() = RadioService.stop(getApplication())
}

/** One task as the screen shows it. */
data class TaskItem(
    val key: String,
    val type: String,
    val readable: String,
    val state: String,
    val local: Boolean,
    val meshDelivered: Boolean,
    val ownStatus: String?,
    val answers: List<String>,
    val updatedMs: Long,
    /** Moves the person may make now: accepted, declined, done. */
    val moves: Set<String>,
)

class TasksViewModel(app: Application, private val graph: AppGraph) : AndroidViewModel(app) {
    val tasks: StateFlow<List<TaskItem>> = graph.database.tasks().observeAll()
        .map { list -> list.map(::toItem) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun toItem(t: TaskEntity): TaskItem {
        val msg = t.json?.let { runCatching { Json.parseObject(it) }.getOrNull() }
        val readable = msg?.let { runCatching { RescueCodec.reconstruct(it, graph.settings.language) }.getOrNull() }
            ?: "Answer received, report not seen yet"
        val answers = runCatching {
            (Json.parse(t.answersJson) as? List<*>).orEmpty().mapNotNull { a ->
                (a as? Map<*, *>)?.let { m ->
                    listOfNotNull(m["status"] as? String, (m["team"] as? String)?.let { "($it)" }, (m["eta"] as? Number)?.let { "ETA $it min" }).joinToString(" ")
                }
            }
        }.getOrDefault(emptyList())
        return TaskItem(
            key = t.key, type = t.type, readable = readable, state = t.state, local = t.local,
            meshDelivered = t.meshDelivered, ownStatus = t.ownStatus, answers = answers, updatedMs = t.updatedMs,
            moves = if (t.local || t.type == "unknown") emptySet() else Messenger.ALLOWED_MOVES[t.ownStatus ?: "open"].orEmpty(),
        )
    }

    fun answer(key: String, status: String, onResult: (AnswerResult) -> Unit = {}) {
        viewModelScope.launch { onResult(graph.messenger.answer(key, status)) }
    }
}

/** Sent and received messages and the waiting queue. */
class MessagesViewModel(app: Application, private val graph: AppGraph) : AndroidViewModel(app) {
    val recent: StateFlow<List<MessageEntity>> = graph.database.messages().observeRecent(100)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val waiting: StateFlow<List<MessageEntity>> = graph.database.messages().observeWaiting()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun readable(m: MessageEntity): String =
        m.json?.let { runCatching { RescueCodec.reconstruct(Json.parseObject(it), graph.settings.language) }.getOrNull() }
            ?: "Unreadable message (${m.detail ?: "invalid"})"

    fun cancel(rowId: Long) { viewModelScope.launch { graph.messenger.cancel(rowId) } }
    fun resend(rowId: Long) { viewModelScope.launch { graph.messenger.resend(rowId) } }

    /** Queues a small test report. Used by the minimal screen. */
    fun sendTest() {
        viewModelScope.launch {
            graph.messenger.submit(
                mapOf("found" to mapOf("age" to "adult", "consciousness" to "conscious", "mobility" to "walks")),
                source = "app",
            )
        }
    }
}

/** Numbers for the stats screen. Airtime is an estimate, everything else is counted. */
data class Stats(
    val sent: Long, val received: Long, val duplicates: Long, val invalid: Long,
    val delivered: Long, val notConfirmed: Long, val refusedAirtime: Long, val failed: Long,
    val bytesSent: Long, val bytesReceived: Long, val airtimeSentMs: Long,
    val airtime: AirtimeStatus,
)

class StatsViewModel(app: Application, private val graph: AppGraph) : AndroidViewModel(app) {
    val stats: StateFlow<Stats> = combine(graph.database.stats().observeAll(), graph.messenger.airtime) { rows, air ->
        val m = rows.associate { it.name to it.value }
        Stats(
            sent = m[StatNames.TX_MESSAGES] ?: 0, received = m[StatNames.RX_MESSAGES] ?: 0,
            duplicates = m[StatNames.RX_DUPLICATES] ?: 0, invalid = m[StatNames.RX_INVALID] ?: 0,
            delivered = m[StatNames.TX_DELIVERED] ?: 0, notConfirmed = m[StatNames.TX_NOT_CONFIRMED] ?: 0,
            refusedAirtime = m[StatNames.TX_REFUSED_AIRTIME] ?: 0, failed = m[StatNames.TX_FAILED] ?: 0,
            bytesSent = m[StatNames.TX_BYTES] ?: 0, bytesReceived = m[StatNames.RX_BYTES] ?: 0,
            airtimeSentMs = m[StatNames.TX_AIRTIME_MS] ?: 0, airtime = air,
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, graph.messenger.airtime.value),
    )
}

/** Creates the ViewModels with the app graph. */
class AppViewModelFactory(private val app: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val g = AppGraph.from(app)
        return when (modelClass) {
            ConnectionViewModel::class.java -> ConnectionViewModel(app, g)
            TasksViewModel::class.java -> TasksViewModel(app, g)
            MessagesViewModel::class.java -> MessagesViewModel(app, g)
            StatsViewModel::class.java -> StatsViewModel(app, g)
            else -> throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
        } as T
    }
}

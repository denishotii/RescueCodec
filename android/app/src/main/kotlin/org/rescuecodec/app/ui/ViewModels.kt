// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rescuecodec.app.AppGraph
import org.rescuecodec.app.core.AirtimeStatus
import org.rescuecodec.app.core.AnswerResult
import org.rescuecodec.app.core.ChannelChoice
import org.rescuecodec.app.core.ChannelOption
import org.rescuecodec.app.core.Fix
import org.rescuecodec.app.core.LocationHelper
import org.rescuecodec.app.core.Messenger
import org.rescuecodec.app.core.ReportFields
import org.rescuecodec.app.core.SubmitResult
import org.rescuecodec.app.data.MessageEntity
import org.rescuecodec.app.data.StatNames
import org.rescuecodec.app.data.TaskEntity
import org.rescuecodec.app.data.add
import org.rescuecodec.app.radio.ChannelJoin
import org.rescuecodec.app.radio.ChannelLink
import org.rescuecodec.app.radio.LinkState
import org.rescuecodec.app.radio.RadioTarget
import org.rescuecodec.app.service.RadioService
import org.rescuecodec.app.voice.SpeechInput
import org.rescuecodec.app.voice.Speaker
import org.rescuecodec.codec.CodecException
import org.rescuecodec.codec.Json
import org.rescuecodec.codec.RescueCodec
import org.rescuecodec.engine.Airtime
import org.rescuecodec.engine.Extractor
import org.rescuecodec.engine.TextFit

/** Radio connection: state, scan, connect, disconnect, channel, joining a channel by link. */
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

    /** The radio channel reports go out on, or the reason there is none. */
    val channel: StateFlow<ChannelChoice> = graph.messenger.channel

    /** Channels of the connected radio the person can pick from. */
    val channelOptions: StateFlow<List<ChannelOption>> = graph.messenger.channelOptions

    private val _picked = MutableStateFlow(graph.settings.channelIndex)

    /** The channel the person picked, or -1 for automatic. */
    val pickedChannel: StateFlow<Int> = _picked.asStateFlow()

    /** Picks a channel by index, or -1 for automatic. */
    fun pickChannel(index: Int) {
        graph.settings.channelIndex = index
        _picked.value = index
        graph.messenger.refreshChannel()
    }

    /** Whether other apps may send reports through the Intent API. */
    fun allowIntentApi(): Boolean = graph.settings.allowIntentApi
    fun setAllowIntentApi(on: Boolean) { graph.settings.allowIntentApi = on }

    /** Result of reading a channel link. */
    sealed interface JoinState {
        data object Idle : JoinState
        data class Preview(val link: ChannelLink) : JoinState
        data object Working : JoinState
        data object Done : JoinState
        data class Failed(val reason: String) : JoinState
        data object NotALink : JoinState
    }

    private val _join = MutableStateFlow<JoinState>(JoinState.Idle)
    val join: StateFlow<JoinState> = _join.asStateFlow()

    /** Reads text from a QR code or a pasted link. */
    fun readChannelText(text: String) {
        val link = ChannelJoin.parse(text)
        _join.value = if (link == null) JoinState.NotALink else JoinState.Preview(link)
    }

    fun clearJoin() { _join.value = JoinState.Idle }

    /** Writes the checked channel to the connected radio. */
    fun applyJoin() {
        val preview = _join.value as? JoinState.Preview ?: return
        val client = graph.link.client.value
        if (client == null) {
            _join.value = JoinState.Failed("Connect to a radio first.")
            return
        }
        _join.value = JoinState.Working
        viewModelScope.launch {
            val problem = runCatching { ChannelJoin.apply(client, preview.link) }.getOrElse { it.message ?: "Failed" }
            _join.value = if (problem == null) JoinState.Done else JoinState.Failed(problem)
            graph.messenger.refreshChannel()
        }
    }
}

/** One task as the screen shows it. */
data class TaskItem(
    val key: String,
    val type: String,
    /** Canonical JSON of the report, or null if only an answer was seen so far. */
    val json: String?,
    val state: String,
    val local: Boolean,
    val meshDelivered: Boolean,
    val ownStatus: String?,
    val answers: List<TaskAnswer>,
    val updatedMs: Long,
    /** Moves the person may make now: accepted, declined, done. */
    val moves: Set<String>,
)

/** One answer (Accept, Decline, Done) of a team. */
data class TaskAnswer(val status: String, val team: String?, val etaMin: Int?, val atMs: Long?, val own: Boolean)

class TasksViewModel(app: Application, private val graph: AppGraph) : AndroidViewModel(app) {
    val tasks: StateFlow<List<TaskItem>> = graph.database.tasks().observeAll()
        .map { list -> list.map(::toItem) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun toItem(t: TaskEntity): TaskItem {
        val answers = runCatching {
            (Json.parse(t.answersJson) as? List<*>).orEmpty().mapNotNull { a ->
                (a as? Map<*, *>)?.let { m ->
                    TaskAnswer(
                        status = m["status"] as? String ?: return@let null,
                        team = m["team"] as? String,
                        etaMin = (m["eta"] as? Number)?.toInt(),
                        atMs = (m["at"] as? Number)?.toLong(),
                        own = (m["from"] as? Number)?.toLong() == graph.settings.lastOwnNode && graph.settings.lastOwnNode != 0L,
                    )
                }
            }
        }.getOrDefault(emptyList())
        return TaskItem(
            key = t.key, type = t.type, json = t.json, state = t.state, local = t.local,
            meshDelivered = t.meshDelivered, ownStatus = t.ownStatus, answers = answers, updatedMs = t.updatedMs,
            moves = movesFor(t),
        )
    }

    fun answer(key: String, status: String, onResult: (AnswerResult) -> Unit = {}) {
        viewModelScope.launch { onResult(graph.messenger.answer(key, status)) }
    }

    companion object {
        /**
         * Moves the person may make on [t] now. None on our own tasks and on answers without a report. None once the
         * task is done: a Done from any team is final, so Accept and Decline would change nothing.
         */
        fun movesFor(t: TaskEntity): Set<String> =
            if (t.local || t.type == "unknown" || t.state == "done") emptySet()
            else Messenger.ALLOWED_MOVES[t.ownStatus ?: "open"].orEmpty()
    }
}

/** Result of the SOS button. */
sealed interface SosResult {
    data class Queued(val withPosition: Boolean) : SosResult
    data class Failed(val message: String) : SosResult
}

/** The waiting queue, cancel and send again, read-aloud and the SOS button. The messages themselves: [ChatViewModel]. */
class MessagesViewModel(app: Application, private val graph: AppGraph) : AndroidViewModel(app) {
    val waiting: StateFlow<List<MessageEntity>> = graph.database.messages().observeWaiting()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val speaker = Speaker(app)

    override fun onCleared() {
        speaker.shutdown()
    }

    fun cancel(rowId: Long) { viewModelScope.launch { graph.messenger.cancel(rowId) } }
    fun resend(rowId: Long) { viewModelScope.launch { graph.messenger.resend(rowId) } }

    /** The SOS button: an empty Mayday with the last known position. It goes first in the send queue. */
    fun sendSos(onResult: (SosResult) -> Unit) {
        viewModelScope.launch {
            val fix = withContext(Dispatchers.IO) { LocationHelper.lastKnown(getApplication()) }
            val msg = LinkedHashMap<String, Any?>()
            fix?.let { msg["pos"] = it.toPos() }
            msg["mayday"] = emptyMap<String, Any?>()
            onResult(
                when (val r = graph.messenger.submit(msg, source = "app")) {
                    is SubmitResult.Queued -> SosResult.Queued(fix != null)
                    is SubmitResult.Rejected -> SosResult.Failed(r.message)
                },
            )
        }
    }
}

/**
 * One conversation. Today there is one: the team channel the reports go out on ([ChatChannel.TEAM]). The chat screen
 * and this view model take the channel as a parameter, so a chat list (one conversation per private channel, plus
 * direct messages) can open several later. That needs the channel of each row in the database first.
 */
data class ChatChannel(
    /** Radio channel index, or null for "the team channel picked by [ChannelChoice]". */
    val index: Int?,
    /** Shown in the top bar when the radio has not told us the channel name yet. */
    val fallbackName: String,
) {
    val key: String get() = "chat-${index ?: "team"}"

    companion object {
        val TEAM = ChatChannel(index = null, fallbackName = "Team")
    }
}

class ChatViewModel(app: Application, private val graph: AppGraph, val channel: ChatChannel) : AndroidViewModel(app) {
    /** Newest first. Null until the database answered, so the screen never flashes "No messages yet". */
    val messages: StateFlow<List<MessageEntity>?> = graph.database.messages().observeRecent(LIMIT)
        // Every row belongs to the team channel today; with a channel column this becomes a filter on [channel].
        .map<List<MessageEntity>, List<MessageEntity>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The channel name the radio reports (for example "RescueCodec"), or null. */
    val title: StateFlow<String?> = graph.messenger.channel
        .map { c -> (c as? ChannelChoice.Ready)?.takeIf { channel.index == null || it.index == channel.index }?.name?.takeIf { it.isNotBlank() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        private const val LIMIT = 200
    }
}

/** Numbers for the stats screen. Each one is either counted ("measured") or calculated ("estimated"). */
data class Stats(
    val sent: Long, val received: Long, val duplicates: Long, val invalid: Long,
    val delivered: Long, val notConfirmed: Long, val refusedAirtime: Long, val failed: Long,
    val bytesSent: Long, val bytesReceived: Long, val airtimeSentMs: Long,
    val airtime: AirtimeStatus,
    /** Reports made in the app: bytes of their text, bytes of the coded message (both counted), reports. */
    val reportTextBytes: Long = 0, val reportCodedBytes: Long = 0, val reports: Long = 0,
    /** Airtime saved by those reports, from the LoRa formula (estimated). */
    val airtimeSavedMs: Long = 0,
    /** Newest signal reading from a received packet (measured by the radio). */
    val lastRssi: Int? = null, val lastSnr: Float? = null, val lastSignalAtMs: Long? = null,
) {
    val bytesSaved: Long get() = reportTextBytes - reportCodedBytes
}

class StatsViewModel(app: Application, private val graph: AppGraph) : AndroidViewModel(app) {
    val stats: StateFlow<Stats> = combine(
        graph.database.stats().observeAll(), graph.messenger.airtime, graph.database.messages().observeLastSignal(),
    ) { rows, air, sig ->
        val m = rows.associate { it.name to it.value }
        Stats(
            sent = m[StatNames.TX_MESSAGES] ?: 0, received = m[StatNames.RX_MESSAGES] ?: 0,
            duplicates = m[StatNames.RX_DUPLICATES] ?: 0, invalid = m[StatNames.RX_INVALID] ?: 0,
            delivered = m[StatNames.TX_DELIVERED] ?: 0, notConfirmed = m[StatNames.TX_NOT_CONFIRMED] ?: 0,
            refusedAirtime = m[StatNames.TX_REFUSED_AIRTIME] ?: 0, failed = m[StatNames.TX_FAILED] ?: 0,
            bytesSent = m[StatNames.TX_BYTES] ?: 0, bytesReceived = m[StatNames.RX_BYTES] ?: 0,
            airtimeSentMs = m[StatNames.TX_AIRTIME_MS] ?: 0, airtime = air,
            reportTextBytes = m[StatNames.REPORT_TEXT_BYTES] ?: 0, reportCodedBytes = m[StatNames.REPORT_CODED_BYTES] ?: 0,
            reports = m[StatNames.REPORT_COUNT] ?: 0, airtimeSavedMs = m[StatNames.REPORT_AIRTIME_SAVED_MS] ?: 0,
            lastRssi = sig?.rssi, lastSnr = sig?.snr, lastSignalAtMs = sig?.atMs,
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, graph.messenger.airtime.value),
    )
}

/** Language, day/night, team label and what the settings screen shows. */
class SettingsViewModel(app: Application, private val graph: AppGraph) : AndroidViewModel(app) {
    val language: StateFlow<String> = graph.ui.language
    val theme: StateFlow<String> = graph.ui.theme

    fun setLanguage(l: String) = graph.ui.setLanguage(l)
    fun toggleLanguage() = graph.ui.toggleLanguage()
    fun setTheme(m: String) = graph.ui.setTheme(m)

    /** In-app sound and haptic tick for new messages and tasks. */
    val sounds: StateFlow<Boolean> = graph.ui.sounds
    fun setSounds(on: Boolean) = graph.ui.setSounds(on)

    val teamLabel: String get() = graph.settings.teamLabel
    fun setTeamLabel(v: String) { graph.settings.teamLabel = Messenger.teamLabelFor(v).orEmpty() }

    val region get() = graph.settings.region
    val preset get() = graph.settings.modemPreset

    /** State of a speech model import: null while idle, the language while working, or the outcome. */
    sealed interface ImportState {
        data object Idle : ImportState
        data class Working(val lang: String) : ImportState
        data class Done(val lang: String) : ImportState
        data class Failed(val lang: String, val reason: String) : ImportState
    }

    private val _import = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _import.asStateFlow()

    /** Bumps when a model was installed, so the settings screen asks again what is on the phone. */
    private val _modelsVersion = MutableStateFlow(0)
    val modelsVersion: StateFlow<Int> = _modelsVersion.asStateFlow()

    /** Installs a speech model from a zip the person picked. */
    fun importModel(uri: android.net.Uri, lang: String) {
        _import.value = ImportState.Working(lang)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { org.rescuecodec.app.voice.ModelImporter.import(getApplication(), uri, lang) }
            _import.value = when (r) {
                is org.rescuecodec.app.voice.ModelImporter.Result.Ok -> ImportState.Done(lang)
                is org.rescuecodec.app.voice.ModelImporter.Result.Failed -> ImportState.Failed(lang, r.reason)
            }
            _modelsVersion.update { it + 1 }
        }
    }
}

// ------------------------------------------------------------------------------------ report

/** What the Report screen shows. */
data class ReportUi(
    val text: String = "",
    /** The message that will be sent, or null while there is no text. */
    val message: Map<String, Any?>? = null,
    /** The sentence could not be coded safely; [message] is the plain text fallback. */
    val fallback: Boolean = false,
    /** The person changed the chips by hand, so the text is no longer re-read. */
    val edited: Boolean = false,
    val textBytes: Int = 0,
    /** Size of the coded message, or 0 if it cannot be coded yet. */
    val sentBytes: Int = 0,
    val readable: String = "",
    /** Why it cannot be sent yet (stable code and message), or null. */
    val problem: String? = null,
    /** True when the phone has a position that is added to the report. */
    val hasPosition: Boolean = false,
    /** The text was cut so it fits into 200 bytes with the position. It ends with "…". */
    val shortened: Boolean = false,
    val busy: Boolean = false,
)

/** One-off results the screen shows as a snackbar. */
sealed interface ReportEvent {
    data class Queued(val sentBytes: Int, val readable: String) : ReportEvent
    data class Rejected(val message: String) : ReportEvent
}

@OptIn(FlowPreview::class)
class ReportViewModel(app: Application, private val graph: AppGraph) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(ReportUi())
    val ui: StateFlow<ReportUi> = _ui.asStateFlow()

    private val textInput = MutableStateFlow("")
    private val extractor by lazy { Extractor() }
    private var fix: Fix? = null

    private val _events = MutableSharedFlow<ReportEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ReportEvent> = _events.asSharedFlow()

    val speech = SpeechInput(app, viewModelScope)

    init {
        viewModelScope.launch {
            textInput.debounce(120).distinctUntilChanged().collect { rebuild(it, force = false) }
        }
        viewModelScope.launch {
            graph.ui.language.collect { lang -> if (_ui.value.message != null) recompute(_ui.value.message, lang) }
        }
    }

    /** The person typed, or a quick chip added words. */
    fun onText(text: String) {
        _ui.update { it.copy(text = text, textBytes = text.toByteArray(Charsets.UTF_8).size) }
        textInput.value = text
    }

    /** Adds [phrase] at the end of the text. */
    fun append(phrase: String) {
        val cur = _ui.value.text.trimEnd()
        val next = if (cur.isEmpty()) phrase else if (cur.endsWith(",") || cur.endsWith(".")) "$cur $phrase" else "$cur, $phrase"
        // A chip tap is a clear intent: re-read at once and let the text win over old chip edits.
        _ui.update { it.copy(text = next, textBytes = next.toByteArray(Charsets.UTF_8).size, edited = false) }
        textInput.value = next
        viewModelScope.launch { rebuild(next, force = true) }
    }

    fun clear() {
        _ui.value = ReportUi()
        textInput.value = ""
    }

    /** Reads the words again and drops hand edits. */
    fun readAgain() {
        viewModelScope.launch { rebuild(_ui.value.text, force = true) }
    }

    private suspend fun rebuild(text: String, force: Boolean) {
        if (_ui.value.edited && !force) return
        if (text.isBlank()) {
            _ui.update { ReportUi(text = text, textBytes = text.toByteArray(Charsets.UTF_8).size) }
            return
        }
        val ex = withContext(Dispatchers.Default) { extractor.extract(text, graph.ui.language.value) }
        withContext(Dispatchers.IO) { fix = LocationHelper.lastKnown(getApplication()) }
        _ui.update { it.copy(fallback = ex.unsupported, edited = false) }
        recompute(ex.message, graph.ui.language.value, fallback = ex.unsupported)
    }

    /** Applies a chip edit. [change] gets the current message and returns the new one. */
    fun edit(change: (Map<String, Any?>) -> Map<String, Any?>) {
        val cur = _ui.value.message ?: return
        val next = change(cur)
        viewModelScope.launch {
            _ui.update { it.copy(edited = true, fallback = ReportFields.typeOf(next) == "text" && it.fallback) }
            recompute(next, graph.ui.language.value)
        }
    }

    private suspend fun recompute(message: Map<String, Any?>?, lang: String, fallback: Boolean = _ui.value.fallback) {
        if (message == null) return
        val withPos = withPosition(message)
        var sent = 0
        var problem: String? = null
        var readable = ""
        var shortened = false
        withContext(Dispatchers.Default) {
            try {
                val out = fitForSending(withPos)
                shortened = out != withPos
                // A placeholder id of 2 bytes (128 and up) gives the real size of almost every message. Every message
                // except an Ack gets an id when it is sent (spec section 14), also text and track.
                val probe = if (ReportFields.typeOf(out) != "ack" && out["id"] == null) out + ("id" to ID_PROBE) else out
                val bytes = RescueCodec.encode(probe)
                sent = bytes.size
                readable = RescueCodec.reconstruct(RescueCodec.decodeAny(bytes), lang)
            } catch (e: CodecException) {
                problem = e.message ?: e.code
            } catch (e: IllegalArgumentException) {
                problem = e.message
            }
        }
        _ui.update {
            it.copy(
                message = message, fallback = fallback, sentBytes = sent, readable = readable,
                problem = problem, hasPosition = withPos["pos"] != null, shortened = shortened,
            )
        }
    }

    private fun withPosition(message: Map<String, Any?>): Map<String, Any?> {
        if (message["pos"] != null) return message
        val f = fix ?: return message
        val out = LinkedHashMap<String, Any?>()
        (message["priority"])?.let { out["priority"] = it }
        out["pos"] = f.toPos()
        for ((k, v) in message) if (k != "priority") out[k] = v
        return out
    }

    /** Sends the report: it goes into the send queue and leaves as soon as the radio is connected. */
    fun send() {
        val s = _ui.value
        val msg = s.message ?: return
        if (s.busy || s.problem != null) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val toSend = fitForSending(withPosition(msg))
            when (val r = graph.messenger.submit(toSend, source = "app")) {
                is SubmitResult.Queued -> {
                    if (!s.fallback) countSaving(s.textBytes, r.bytes.size)
                    _events.tryEmit(ReportEvent.Queued(r.bytes.size, r.readable))
                    clear()
                }
                is SubmitResult.Rejected -> {
                    _ui.update { it.copy(busy = false) }
                    _events.tryEmit(ReportEvent.Rejected(r.message))
                }
            }
        }
    }

    private suspend fun countSaving(textBytes: Int, codedBytes: Int) {
        val st = graph.database.stats()
        st.add(StatNames.REPORT_COUNT)
        st.add(StatNames.REPORT_TEXT_BYTES, textBytes.toLong())
        st.add(StatNames.REPORT_CODED_BYTES, codedBytes.toLong())
        val air = Airtime(graph.settings.modemPreset)
        val saved = (air.millis(textBytes) - air.millis(codedBytes)).toLong()
        st.add(StatNames.REPORT_AIRTIME_SAVED_MS, saved)
    }

    // ---- voice

    /** Loads the speech model of the current language in the background. */
    fun prepareVoice() = speech.prepare(graph.ui.language.value)

    /** Hold started. Returns false when it could not start (no model yet, no microphone). */
    fun holdStart(): Boolean = speech.start(graph.ui.language.value) { spoken ->
        if (spoken.isNotBlank()) {
            val cur = _ui.value.text.trimEnd()
            onText(if (cur.isEmpty()) spoken else "$cur, $spoken")
            // The person just said it: read it at once, no waiting for the debounce.
            viewModelScope.launch { rebuild(_ui.value.text, force = true) }
        }
    }

    fun holdEnd() = speech.stop()

    override fun onCleared() {
        speech.release()
    }

    companion object {
        /** A 2-byte id (128 and up): the size of the real id that is added when the message is sent. */
        private const val ID_PROBE = 128

        /**
         * A text that is too long with the position is cut to fit ([TextFit.fitText]). It is measured with a 2-byte
         * id, the largest one [Messenger.submit] gives, so it still fits when the real id is added.
         */
        fun fitForSending(message: Map<String, Any?>): Map<String, Any?> {
            if (ReportFields.typeOf(message) != "text") return message
            if (message["id"] != null) return TextFit.fitText(message)
            return TextFit.fitText(message + ("id" to ID_PROBE)) - "id"
        }
    }
}

/** Creates the [ChatViewModel] of one channel. Use it with `viewModel(key = channel.key, factory = ...)`. */
class ChatViewModelFactory(private val app: Application, private val graph: AppGraph, private val channel: ChatChannel) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(app, graph, channel) as T
}

/** Creates the ViewModels with the app graph. */
class AppViewModelFactory(private val app: Application, private val graph: AppGraph = AppGraph.from(app)) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val g = graph
        return when (modelClass) {
            ConnectionViewModel::class.java -> ConnectionViewModel(app, g)
            TasksViewModel::class.java -> TasksViewModel(app, g)
            MessagesViewModel::class.java -> MessagesViewModel(app, g)
            StatsViewModel::class.java -> StatsViewModel(app, g)
            SettingsViewModel::class.java -> SettingsViewModel(app, g)
            ReportViewModel::class.java -> ReportViewModel(app, g)
            else -> throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
        } as T
    }
}

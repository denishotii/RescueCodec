// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.radio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.sdk.AutoReconnectConfig
import org.meshtastic.sdk.ConfigPhase
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.StorageProvider
import org.meshtastic.sdk.TransportState
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/** What the person sees while we connect. */
enum class ConnectPhase {
    /** Opening the link (Bluetooth or network). */
    OPENING,

    /** Android is pairing with the radio. The person may have to confirm and type the PIN. */
    PAIRING,

    /** Reading the radio's settings. */
    LOADING_SETTINGS,

    /** Reading the list of other radios. */
    LOADING_NODES,
}

/** The state of the link to the radio. */
sealed interface LinkState {
    /** No radio and nothing going on. */
    data object Disconnected : LinkState

    /** Looking for Bluetooth radios. [found] grows while the scan runs. */
    data class Scanning(val found: List<DiscoveredRadio> = emptyList()) : LinkState

    /** Connecting to [target]. [attempt] counts from 1 and restarts after a stable connection. */
    data class Connecting(
        val target: RadioTarget,
        val attempt: Int,
        val phase: ConnectPhase = ConnectPhase.OPENING,
        val progress: Float? = null,
    ) : LinkState

    /** Connected and ready to send. [nodeNum] is the Meshtastic node number of our radio. */
    data class Connected(val target: RadioTarget, val nodeNum: Long, val nodeName: String?) : LinkState

    /**
     * Something went wrong. If [retryAtMs] is set we try again at that time (milliseconds since
     * epoch). If it is null the person must act (for example allow Bluetooth).
     */
    data class Error(
        val target: RadioTarget?,
        val message: String,
        val attempt: Int = 0,
        val retryAtMs: Long? = null,
    ) : LinkState
}

/** Waiting time before connection attempt number [attempt] (1 is the first retry). */
class Backoff(
    private val initialMs: Long = 1_000,
    private val maxMs: Long = 60_000,
    private val factor: Double = 2.0,
    private val jitter: Double = 0.15,
    private val random: Random = Random.Default,
) {
    fun delayMs(attempt: Int): Long {
        var d = initialMs.toDouble()
        repeat((attempt - 1).coerceIn(0, 30)) { d = (d * factor).coerceAtMost(maxMs.toDouble()) }
        val spread = if (jitter > 0) 1.0 + (random.nextDouble() * 2 - 1) * jitter else 1.0
        return (d * spread).toLong().coerceIn(0, maxMs)
    }
}

/**
 * Owns the connection to the radio.
 *
 * - [state] says what is going on: disconnected, scanning, connecting, connected, error.
 * - [client] is the Meshtastic SDK client while (and only while) we are connected.
 * - After a drop or a failed attempt it tries again with growing waits (1 s, 2 s, 4 s ... 60 s) until
 *   the person calls [disconnect] or something needs the person (for example a missing permission).
 * - The last radio that worked is remembered ([lastRadio]).
 *
 * Every attempt builds a fresh [RadioClient]. That is slower than the SDK's own auto-reconnect but
 * gives one place that handles every failure the same way, for Bluetooth and TCP.
 */
class ConnectionManager(
    private val scope: CoroutineScope,
    private val transports: TransportFactory,
    private val scanner: RadioScanner,
    private val storage: StorageProvider,
    private val lastRadio: LastRadioStore,
    private val backoff: Backoff = Backoff(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Context for the SDK's own work. Null uses the SDK default (Dispatchers.Default). */
    private val clientContext: CoroutineContext? = null,
    private val connectTimeoutMs: Long = 150_000,
    /** A connection must last this long before the retry counter starts again from 1. */
    private val stableAfterMs: Long = 20_000,
    private val scanTimeoutMs: Long = 20_000,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _client = MutableStateFlow<RadioClient?>(null)

    /** The SDK client, only while [state] is [LinkState.Connected]. */
    val client: StateFlow<RadioClient?> = _client.asStateFlow()

    private val _attached = MutableStateFlow<RadioClient?>(null)

    /**
     * The SDK client from the moment it is built until it is closed, also while it connects.
     * Listen to its packets here: the radio replays missed packets right after the handshake.
     */
    val attached: StateFlow<RadioClient?> = _attached.asStateFlow()

    private var loopJob: Job? = null
    private var scanJob: Job? = null

    /** The radio we connected to last, if any. */
    fun lastRadio(): RadioTarget? = lastRadio.get()

    /** Connects to [target] and keeps the link up until [disconnect]. */
    fun connect(target: RadioTarget) {
        stopScan()
        val old = loopJob
        loopJob = scope.launch {
            old?.cancelAndJoin()
            runLoop(target)
        }
    }

    /** Connects to the remembered radio. Returns false if there is none. */
    fun connectLast(): Boolean {
        val t = lastRadio.get() ?: return false
        connect(t)
        return true
    }

    /** Stops everything. No automatic reconnect afterwards. */
    fun disconnect() {
        val job = loopJob
        loopJob = null
        stopScan()
        scope.launch {
            job?.cancelAndJoin()
            _state.value = LinkState.Disconnected
        }
    }

    /** Scans for Bluetooth radios for a short time. Results appear in [state] as [LinkState.Scanning]. */
    fun startScan() {
        val s = _state.value
        if (s is LinkState.Connected || s is LinkState.Connecting) return
        scanJob?.cancel()
        scanJob = scope.launch {
            val found = LinkedHashMap<String, DiscoveredRadio>()
            _state.value = LinkState.Scanning()
            try {
                withTimeoutOrNull(scanTimeoutMs) {
                    scanner.scan().collect { r ->
                        found[r.address] = r
                        _state.value = LinkState.Scanning(found.values.sortedByDescending { it.rssi })
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("scan failed: $e")
                _state.value = LinkState.Error(null, friendly(e))
                return@launch
            } finally {
                _state.update { if (it is LinkState.Scanning) LinkState.Disconnected else it }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    private suspend fun runLoop(target: RadioTarget) {
        var attempt = 0
        try {
            while (scope.isActive) {
                attempt++
                _state.value = LinkState.Connecting(target, attempt)
                val startedAt = clock()
                var wasConnected = false
                try {
                    attemptOnce(target, attempt) { wasConnected = true }
                    // Returned normally: the link dropped after being up.
                    _state.value = LinkState.Error(target, "Connection to ${target.label} lost.", attempt)
                } catch (e: CancellationException) {
                    if (e is TimeoutCancellationException) {
                        _state.value = LinkState.Error(target, "No answer from ${target.label}.", attempt)
                    } else {
                        throw e
                    }
                } catch (e: RadioUnavailableException) {
                    log("radio unavailable: ${e.message}")
                    _state.value = LinkState.Error(target, e.message ?: "Radio unavailable.", attempt, retryAtMs = null)
                    return
                } catch (e: Exception) {
                    log("connect failed: $e")
                    _state.value = LinkState.Error(target, friendly(e), attempt)
                }
                if (wasConnected && clock() - startedAt >= stableAfterMs) attempt = 0
                val wait = backoff.delayMs(attempt.coerceAtLeast(1))
                val err = _state.value
                if (err is LinkState.Error) _state.value = err.copy(attempt = attempt, retryAtMs = clock() + wait)
                delay(wait)
            }
        } finally {
            _client.value = null
        }
    }

    /** Connects once and returns when the link is lost. Throws if connecting fails. */
    private suspend fun attemptOnce(target: RadioTarget, attempt: Int, onConnected: () -> Unit) {
        val opened = transports.open(target)
        val transport = opened.transport
        val builder = RadioClient.Builder().transport(transport).storage(storage)
        clientContext?.let { builder.coroutineContext(it) }
        val client = builder.build()
        _attached.value = client
        val watcher = scope.launch {
            combine(client.connection, transport.state) { c, t -> c to t }.collect { (c, t) ->
                val cur = _state.value
                if (cur is LinkState.Connecting) {
                    val next = when {
                        t is TransportState.Bonding -> cur.copy(phase = ConnectPhase.PAIRING, progress = null)
                        c is ConnectionState.Configuring && c.phase == ConfigPhase.Stage2 ->
                            cur.copy(phase = ConnectPhase.LOADING_NODES, progress = c.progress)
                        c is ConnectionState.Configuring ->
                            cur.copy(phase = ConnectPhase.LOADING_SETTINGS, progress = c.progress)
                        else -> cur.copy(phase = ConnectPhase.OPENING, progress = null)
                    }
                    if (next != cur) _state.value = next
                }
            }
        }
        try {
            withTimeout(connectTimeoutMs) { client.connect() }
            val nodeNum = ownNodeNum(client)
            _client.value = client
            _state.value = LinkState.Connected(target, nodeNum, client.ownNode.value?.user?.long_name)
            lastRadio.set(target)
            onConnected()
            log("connected to ${target.label} as node !%08x".format(nodeNum))
            combine(client.connection, transport.state) { c, t ->
                c is ConnectionState.Connected && t !is TransportState.Error && t !is TransportState.Disconnected
            }.first { alive -> !alive }
        } finally {
            watcher.cancel()
            _client.value = null
            withContext(NonCancellable) {
                withTimeoutOrNull(10_000) { runCatching { client.disconnect() } }
                withTimeoutOrNull(5_000) { runCatching { opened.close() } }
                if (_attached.value === client) _attached.value = null
            }
        }
    }

    private fun ownNodeNum(client: RadioClient): Long {
        val fromInfo = client.configBundle.value?.myInfo?.my_node_num
        val n = fromInfo ?: client.ownNode.value?.num ?: 0
        return n.toLong() and 0xFFFFFFFFL
    }

    private fun friendly(e: Throwable): String = when (e) {
        is MeshtasticException.FirmwareTooOld -> "The radio firmware is too old. Update it to 2.7 or newer."
        is MeshtasticException.HandshakeTimeout -> "The radio did not finish talking to us (${e.stage}). Is it on and in range?"
        is MeshtasticException.AlreadyConnected -> "Already connected."
        is SecurityException -> "Bluetooth permission is missing. Allow Nearby devices in the app settings."
        else -> e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "Unknown error"
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.radio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
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

    /**
     * Counts every [connect] and [disconnect]. Only the loop (or scan) of the current number may write [_state],
     * so a loop that is still shutting down can never overwrite the state of the one that replaced it.
     */
    @Volatile
    private var generation = 0

    private fun setState(gen: Int, value: LinkState) {
        if (gen != generation) return
        val old = _state.getAndUpdate { value }
        if (old::class != value::class || (value is LinkState.Error && old != value)) {
            if (value !is LinkState.Scanning) log("link: ${describe(value)}")
        }
    }

    private fun describe(s: LinkState): String = when (s) {
        LinkState.Disconnected -> "disconnected"
        is LinkState.Scanning -> "scanning (${s.found.size} found)"
        is LinkState.Connecting -> "connecting to ${s.target.label}, attempt ${s.attempt}, ${s.phase}"
        is LinkState.Connected -> "connected to ${s.target.label}, node !%08x".format(s.nodeNum)
        is LinkState.Error -> "error: ${s.message} (attempt ${s.attempt}, retry ${if (s.retryAtMs == null) "no" else "at ${s.retryAtMs}"})"
    }

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
    private var wantedTarget: RadioTarget? = null
    private var scanJob: Job? = null

    /** The radio we connected to last, if any. */
    fun lastRadio(): RadioTarget? = lastRadio.get()

    /** True when the person wants the link up: set by [connect], cleared only by an explicit [disconnect]. */
    fun wantConnected(): Boolean = lastRadio.wantConnected

    /** Connects to [target] and keeps the link up until [disconnect]. */
    @Synchronized
    fun connect(target: RadioTarget) {
        // Already connected or connecting to this radio under a running loop: leave it alone. Several callers ask
        // for the same thing at app start (activity, service, debug extra), and each restart would drop a good link.
        val s = _state.value
        if (loopJob?.isActive == true && wantedTarget == target &&
            (s is LinkState.Connected || s is LinkState.Connecting)
        ) return
        wantedTarget = target
        stopScan()
        val gen = ++generation
        lastRadio.wantConnected = true
        val old = loopJob
        // Show the new attempt at once. The old loop may take seconds to shut its radio down.
        setState(gen, LinkState.Connecting(target, 1))
        loopJob = scope.launch {
            old?.cancelAndJoin()
            runLoop(gen, target)
        }
    }

    /** Connects to the remembered radio. Returns false if there is none. */
    fun connectLast(): Boolean {
        val t = lastRadio.get() ?: return false
        connect(t)
        return true
    }

    /** Stops everything. No automatic reconnect afterwards. The state is [LinkState.Disconnected] at once. */
    @Synchronized
    fun disconnect() {
        val job = loopJob
        loopJob = null
        wantedTarget = null
        lastRadio.wantConnected = false
        stopScan()
        val gen = ++generation
        _state.value = LinkState.Disconnected
        log("link: disconnected by the person")
        job?.cancel()
        // The old loop closes the radio in the background. Nothing it does can write the state any more (gen).
        if (job != null) scope.launch { job.join(); setState(gen, LinkState.Disconnected) }
    }

    /**
     * Scans for Bluetooth radios for a short time. Results appear in [state] as [LinkState.Scanning].
     * Does nothing while a connection is up or being kept up (also during the wait between retries).
     */
    @Synchronized
    fun startScan() {
        if (loopJob?.isActive == true) return
        val s = _state.value
        if (s is LinkState.Connected || s is LinkState.Connecting) return
        scanJob?.cancel()
        val gen = generation
        scanJob = scope.launch {
            val found = LinkedHashMap<String, DiscoveredRadio>()
            setState(gen, LinkState.Scanning())
            try {
                withTimeoutOrNull(scanTimeoutMs) {
                    scanner.scan().collect { r ->
                        found[r.address] = r
                        if (gen == generation && loopJob?.isActive != true) {
                            setState(gen, LinkState.Scanning(found.values.sortedByDescending { it.rssi }))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("scan failed: $e")
                setState(gen, LinkState.Error(null, friendlyError(e)))
                return@launch
            } finally {
                if (gen == generation) _state.update { if (it is LinkState.Scanning) LinkState.Disconnected else it }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    private suspend fun runLoop(gen: Int, target: RadioTarget) {
        var attempt = 0
        try {
            while (scope.isActive && gen == generation) {
                attempt++
                setState(gen, LinkState.Connecting(target, attempt))
                val startedAt = clock()
                var wasConnected = false
                try {
                    attemptOnce(gen, target, attempt) { wasConnected = true }
                    // Returned normally: the link dropped after being up.
                    setState(gen, LinkState.Error(target, "Connection to ${target.label} lost.", attempt))
                } catch (e: TimeoutCancellationException) {
                    setState(gen, LinkState.Error(target, "No answer from ${target.label}.", attempt))
                } catch (e: CancellationException) {
                    // Cancelled by connect() or disconnect(): stop. Cancelled by the SDK's own scope (for example a
                    // GATT drop during the handshake) while we are still wanted: treat it as a failed attempt and retry.
                    if (!currentCoroutineContext().isActive) throw e
                    log("connect attempt cancelled by the SDK: $e")
                    setState(gen, LinkState.Error(target, "Connection to ${target.label} was interrupted.", attempt))
                } catch (e: BluetoothOffException) {
                    // Wait quietly and carry on when Bluetooth comes back. No retry counter, no countdown.
                    setState(gen, LinkState.Error(target, e.message ?: "Bluetooth is off.", attempt, retryAtMs = null))
                    attempt = 0
                    delay(BLUETOOTH_POLL_MS)
                    continue
                } catch (e: Throwable) {
                    if (e is VirtualMachineError) throw e
                    log("connect failed: $e")
                    if (e is RadioUnavailableException || needsPerson(e)) {
                        // Retrying cannot help until the person acts (permission, Bluetooth settings).
                        setState(gen, LinkState.Error(target, friendlyError(e), attempt, retryAtMs = null))
                        return
                    }
                    setState(gen, LinkState.Error(target, friendlyError(e), attempt))
                }
                if (wasConnected && clock() - startedAt >= stableAfterMs) attempt = 0
                val wait = backoff.delayMs(attempt.coerceAtLeast(1))
                val err = _state.value
                if (gen == generation && err is LinkState.Error) setState(gen, err.copy(attempt = attempt, retryAtMs = clock() + wait))
                delay(wait)
            }
        } finally {
            if (gen == generation) _client.value = null
        }
    }

    /** Connects once and returns when the link is lost. Throws if connecting fails. */
    private suspend fun attemptOnce(gen: Int, target: RadioTarget, attempt: Int, onConnected: () -> Unit) {
        val opened = transports.open(target)
        val transport = opened.transport
        // Full handshake on every link, Bluetooth included: the radio's node list gives the names of the other radios
        // (senders, direct messages, notifications) and of the connected radio itself. Without it a sender shows as
        // "Node 7928" until that radio happens to send its NodeInfo again. In a full hall this adds a few seconds;
        // the Radio screen shows "Loading nodes N %" meanwhile.
        val builder = RadioClient.Builder().transport(transport).storage(storage)
        clientContext?.let { builder.coroutineContext(it) }
        val client = builder.build()
        _attached.value = client
        val watcher = scope.launch {
            combine(client.connection, transport.state) { c, t -> c to t }.collect { (c, t) ->
                val cur = _state.value
                if (gen == generation && cur is LinkState.Connecting) {
                    val next = when {
                        t is TransportState.Bonding -> cur.copy(phase = ConnectPhase.PAIRING, progress = null)
                        c is ConnectionState.Configuring && c.phase == ConfigPhase.Stage2 ->
                            cur.copy(phase = ConnectPhase.LOADING_NODES, progress = c.progress)
                        c is ConnectionState.Configuring ->
                            cur.copy(phase = ConnectPhase.LOADING_SETTINGS, progress = c.progress)
                        else -> cur.copy(phase = ConnectPhase.OPENING, progress = null)
                    }
                    if (next != cur) setState(gen, next)
                }
            }
        }
        try {
            withTimeout(connectTimeoutMs) { client.connect() }
            val nodeNum = ownNodeNum(client)
            if (gen != generation) throw CancellationException("replaced by a newer connect or disconnect")
            _client.value = client
            setState(gen, LinkState.Connected(target, nodeNum, client.ownNode.value?.user?.long_name))
            lastRadio.set(target)
            onConnected()
            log("connected to ${target.label} as node !%08x".format(nodeNum))
            combine(client.connection, transport.state) { c, t ->
                c is ConnectionState.Connected && t !is TransportState.Error && t !is TransportState.Disconnected
            }.first { alive -> !alive }
        } finally {
            watcher.cancel()
            if (gen == generation) _client.value = null
            withContext(NonCancellable) {
                withTimeoutOrNull(10_000) { runCatching { client.disconnect() } }
                withTimeoutOrNull(5_000) { runCatching { opened.close() } }
                if (_attached.value === client) _attached.value = null
                if (gen != generation && _client.value === client) _client.value = null
            }
        }
    }

    private fun ownNodeNum(client: RadioClient): Long {
        val fromInfo = client.configBundle.value?.myInfo?.my_node_num
        val n = fromInfo ?: client.ownNode.value?.num ?: 0
        return n.toLong() and 0xFFFFFFFFL
    }

    companion object {
        /** How often we look for Bluetooth to come back after the person switched it off. */
        const val BLUETOOTH_POLL_MS = 3_000L
    }
}

/** Bluetooth is switched off. The loop waits for it instead of counting retries. */
class BluetoothOffException(message: String = "Bluetooth is off. Turn it on to connect.") : Exception(message)

private fun causes(e: Throwable): Sequence<Throwable> = generateSequence(e) { it.cause?.takeIf { c -> c !== it } }.take(8)

/** True for errors that more retries cannot fix: the person has to allow something first. */
internal fun needsPerson(e: Throwable): Boolean = causes(e).any { it is SecurityException || it is RadioUnavailableException }

/** Plain words for the person instead of an exception text. */
internal fun friendlyError(e: Throwable): String {
    if (causes(e).any { it is SecurityException }) {
        return "Bluetooth permission is missing. Allow Nearby devices in the app settings."
    }
    if (e is RadioUnavailableException || e is BluetoothOffException) return e.message ?: "Radio unavailable."
    val typed = causes(e).firstNotNullOfOrNull {
        when (it) {
            is MeshtasticException.FirmwareTooOld -> "The radio firmware is too old. Update it to 2.7 or newer."
            is MeshtasticException.HandshakeTimeout -> "The radio did not finish talking to us (${it.stage}). Is it on and in range?"
            is MeshtasticException.AlreadyConnected -> "Already connected."
            else -> null
        }
    }
    if (typed != null) return typed
    val text = causes(e).mapNotNull { it.message }.joinToString(" | ").lowercase()
    return when {
        "bluetooth is off" in text || "adapter is off" in text || "bluetooth_off" in text || "state_off" in text ->
            "Bluetooth is off. Turn it on to connect."
        "bond" in text && ("stale" in text || "cache" in text || "removed" in text || "lost" in text || "mismatch" in text) ->
            "The phone and radio no longer trust each other. Forget the radio in Android Bluetooth settings and pair again."
        "gattstatus=5" in text || "gattstatus=15" in text || "gattstatus=8" in text || "authentication" in text ||
            "insufficient" in text || "pin" in text.split(Regex("[^a-z]+")) || "pairing" in text || "bonding failed" in text ->
            "Pairing failed. Check the PIN (T1000-E: 123456) and try again."
        "gattstatus=133" in text || "status=133" in text || "gatt_error" in text || "error 133" in text || "133" in text.split(Regex("[^0-9]+")) ->
            "Radio not found. Is it on and near the phone?"
        "bond" in text ->
            "The phone and radio no longer trust each other. Forget the radio in Android Bluetooth settings and pair again."
        "timed out" in text || "timeout" in text -> "No answer from the radio. Is it on and near the phone?"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "Unknown error"
    }
}

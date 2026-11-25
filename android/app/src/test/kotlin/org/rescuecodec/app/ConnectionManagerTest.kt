// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import org.rescuecodec.app.core.MemoryKeyValueStore
import org.rescuecodec.app.radio.Backoff
import org.rescuecodec.app.radio.BluetoothOffException
import org.rescuecodec.app.radio.friendlyError
import org.rescuecodec.app.radio.ConnectionManager
import org.rescuecodec.app.radio.DiscoveredRadio
import org.rescuecodec.app.radio.LastRadioStore
import org.rescuecodec.app.radio.LinkState
import org.rescuecodec.app.radio.OpenedTransport
import org.rescuecodec.app.radio.RadioScanner
import org.rescuecodec.app.radio.RadioTarget
import org.rescuecodec.app.radio.RadioUnavailableException
import org.rescuecodec.app.radio.TransportFactory
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionManagerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = MemoryKeyValueStore()
    private val last = LastRadioStore(store)
    private val transports = CopyOnWriteArrayList<FakeRadioTransport>()
    private var failuresLeft = 0
    private var unavailable = false
    private var scanItems = emptyList<DiscoveredRadio>()

    /** Errors the factory throws, one per open, before it works again. */
    private val openErrors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

    private val factory = TransportFactory { _ ->
        openErrors.poll()?.let { throw it }
        if (unavailable) throw RadioUnavailableException("Bluetooth permission is missing.")
        if (failuresLeft > 0) {
            failuresLeft--
            throw IOException("connection refused")
        }
        val t = fakeRadio("fake:${transports.size}", 0x1a2b3c4d)
        transports.add(t)
        OpenedTransport(t)
    }
    private val scanner = RadioScanner {
        flow<DiscoveredRadio> {
            for (r in scanItems) {
                emit(r)
                delay(10)
            }
            awaitCancellation() // a real scan does not end by itself
        }
    }

    private fun manager(scanTimeoutMs: Long = 500) = ConnectionManager(
        scope = scope, transports = factory, scanner = scanner, storage = InMemoryStorageProvider(), lastRadio = last,
        backoff = Backoff(initialMs = 100, maxMs = 400, jitter = 0.0), stableAfterMs = 60_000, scanTimeoutMs = scanTimeoutMs,
    )

    private fun runUnit(block: suspend CoroutineScope.() -> Unit) { runBlocking { block() } }

    @AfterTest
    fun tearDown() { scope.cancel() }

    @Test
    fun connectsAndRemembersTheRadio() = runUnit {
        val m = manager()
        val seen = CopyOnWriteArrayList<LinkState>()
        scope.launch { m.state.collect { seen.add(it) } }
        m.connect(RadioTarget.Tcp("10.0.2.2", 4403))
        eventually(20_000, "connected") { m.state.value is LinkState.Connected }
        val s = m.state.value as LinkState.Connected
        assertEquals(0x1a2b3c4dL, s.nodeNum)
        assertNotNull(m.client.value)
        assertEquals(RadioTarget.Tcp("10.0.2.2", 4403), last.get())
        assertTrue(seen.any { it is LinkState.Connecting }, "went through Connecting")
        m.disconnect()
        eventually(5_000, "disconnected") { m.state.value is LinkState.Disconnected }
        assertNull(m.client.value)
    }

    @Test
    fun reconnectsAfterTheLinkDrops() = runUnit {
        val m = manager()
        m.connect(RadioTarget.Tcp("h", 1))
        eventually(20_000, "connected") { m.state.value is LinkState.Connected }
        transports[0].simulateError(IOException("link lost"), recoverable = true)
        eventually(10_000, "error shown") { m.state.value is LinkState.Error || (m.state.value is LinkState.Connecting) }
        eventually(30_000, "connected again over a new transport") { m.state.value is LinkState.Connected && transports.size == 2 }
        m.disconnect()
    }

    @Test
    fun retriesAfterFailedAttemptsWithGrowingWaits() = runUnit {
        failuresLeft = 3
        val m = manager()
        val errors = CopyOnWriteArrayList<LinkState.Error>()
        scope.launch { m.state.collect { if (it is LinkState.Error) errors.add(it) } }
        m.connect(RadioTarget.Tcp("h", 1))
        eventually(30_000, "connected after 3 failures") { m.state.value is LinkState.Connected }
        val withRetry = errors.filter { it.retryAtMs != null }
        assertTrue(withRetry.size >= 3, "saw 3 errors with a retry time, got ${errors.size}")
        assertEquals(listOf(1, 2, 3), withRetry.map { it.attempt }.distinct().take(3))
        m.disconnect()
    }

    @Test
    fun missingPermissionStopsRetrying() = runUnit {
        unavailable = true
        val m = manager()
        m.connect(RadioTarget.Ble("AA:BB:CC:DD:EE:FF", "Heltec"))
        eventually(5_000, "error") { m.state.value is LinkState.Error }
        val e = m.state.value as LinkState.Error
        assertNull(e.retryAtMs, "needs the person, no automatic retry")
        delay(500)
        assertIs<LinkState.Error>(m.state.value)
        assertTrue(transports.isEmpty())
    }

    @Test
    fun scanListsRadiosThenStops() = runUnit {
        scanItems = listOf(DiscoveredRadio("AA", "Heltec V3", -70), DiscoveredRadio("BB", "T1000-E", -50))
        val m = manager(scanTimeoutMs = 2_000)
        m.startScan()
        eventually(3_000, "two radios") { (m.state.value as? LinkState.Scanning)?.found?.size == 2 }
        val found = (m.state.value as LinkState.Scanning).found
        assertEquals(listOf("BB", "AA"), found.map { it.address }, "strongest first")
        eventually(5_000, "scan ended") { m.state.value is LinkState.Disconnected }
    }

    @Test
    fun backoffGrowsAndStops() {
        val b = Backoff(initialMs = 1_000, maxMs = 60_000, jitter = 0.0)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), (1..8).map { b.delayMs(it) })
    }

    @Test
    fun lastRadioStoreRoundTrips() {
        val a = RadioTarget.Ble("AA:BB", "Heltec V3 1a2b")
        last.set(a)
        assertEquals(a, last.get())
        last.set(RadioTarget.Tcp("10.0.2.2", 4403))
        assertEquals(RadioTarget.Tcp("10.0.2.2", 4403), last.get())
    }

    // ------------------------------------------------------------ robustness (review findings)

    @Test
    fun aMissingBluetoothPermissionFromTheSdkStopsRetryingToo() = runUnit {
        openErrors.add(SecurityException("Need android.permission.BLUETOOTH_CONNECT"))
        val m = manager()
        m.connect(RadioTarget.Ble("AA:BB:CC:DD:EE:FF", "T1000-E"))
        eventually(5_000, "error") { m.state.value is LinkState.Error }
        val e = m.state.value as LinkState.Error
        assertNull(e.retryAtMs, "needs the person, no countdown")
        assertTrue(e.message.contains("Nearby devices"), e.message)
        delay(600)
        assertIs<LinkState.Error>(m.state.value)
        assertTrue(transports.isEmpty(), "no second attempt")
    }

    @Test
    fun bluetoothSwitchedOffWaitsAndConnectsWhenItIsBackOn() = runUnit {
        repeat(3) { openErrors.add(BluetoothOffException()) }
        val m = manager()
        m.connect(RadioTarget.Ble("AA:BB:CC:DD:EE:FF", "T1000-E"))
        eventually(5_000, "error") { (m.state.value as? LinkState.Error)?.message?.contains("Bluetooth is off") == true }
        assertNull((m.state.value as LinkState.Error).retryAtMs)
        eventually(30_000, "connected once Bluetooth is on") { m.state.value is LinkState.Connected }
        m.disconnect()
    }

    @Test
    fun aCancellationFromTheSdkInsideAnAttemptIsRetriedNotSwallowed() = runUnit {
        openErrors.add(kotlinx.coroutines.CancellationException("SDK scope cancelled during the handshake"))
        val m = manager()
        m.connect(RadioTarget.Tcp("h", 1))
        eventually(30_000, "connected after the interrupted attempt") { m.state.value is LinkState.Connected }
        m.disconnect()
    }

    @Test
    fun scanWhileTheLoopRetriesDoesNotTouchTheState() = runUnit {
        repeat(40) { openErrors.add(IOException("connection refused")) }
        scanItems = listOf(DiscoveredRadio("AA", "Heltec V3", -70))
        val m = manager(scanTimeoutMs = 1_000)
        m.connect(RadioTarget.Tcp("h", 1))
        eventually(5_000, "error with retry") { (m.state.value as? LinkState.Error)?.retryAtMs != null || m.state.value is LinkState.Connecting }
        val seen = CopyOnWriteArrayList<LinkState>()
        scope.launch { m.state.collect { seen.add(it) } }
        m.startScan()
        delay(1_500)
        assertTrue(seen.none { it is LinkState.Scanning }, "scan must not show while the loop runs: $seen")
        assertTrue(m.state.value !is LinkState.Disconnected, "loop state kept: ${m.state.value}")
        m.disconnect()
    }

    @Test
    fun scanWhileConnectedKeepsTheLinkUp() = runUnit {
        scanItems = listOf(DiscoveredRadio("AA", "Heltec V3", -70))
        val m = manager(scanTimeoutMs = 600)
        m.connect(RadioTarget.Tcp("h", 1))
        eventually(20_000, "connected") { m.state.value is LinkState.Connected }
        m.startScan()
        delay(1_200)
        assertIs<LinkState.Connected>(m.state.value)
        m.disconnect()
    }

    @Test
    fun disconnectIsImmediateAndALateDisconnectNeverOverwritesTheNextConnect() = runUnit {
        val m = manager()
        m.connect(RadioTarget.Tcp("h", 1))
        eventually(20_000, "connected") { m.state.value is LinkState.Connected }
        m.disconnect()
        assertIs<LinkState.Disconnected>(m.state.value, "at once, not after the radio is closed")
        assertEquals(false, m.wantConnected())
        m.connect(RadioTarget.Tcp("h", 2))
        assertIs<LinkState.Connecting>(m.state.value, "the new attempt shows at once")
        assertEquals(true, m.wantConnected())
        eventually(30_000, "connected to the new radio") { m.state.value is LinkState.Connected }
        delay(1_500)                                    // the old loop has long finished closing its radio
        assertIs<LinkState.Connected>(m.state.value)
        assertEquals(RadioTarget.Tcp("h", 2), (m.state.value as LinkState.Connected).target)
        m.disconnect()
    }

    @Test
    fun askingForTheSameRadioAgainDoesNotRestartAGoodLink() = runUnit {
        val m = manager()
        m.connect(RadioTarget.Tcp("h", 1))
        eventually(20_000, "connected") { m.state.value is LinkState.Connected }
        m.connect(RadioTarget.Tcp("h", 1))
        m.connectLast()
        delay(800)
        assertIs<LinkState.Connected>(m.state.value)
        assertEquals(1, transports.size, "no second transport was opened")
        m.disconnect()
    }

    @Test
    fun connectConnectInARowEndsWithTheLastTarget() = runUnit {
        val m = manager()
        m.connect(RadioTarget.Tcp("first", 1))
        m.connect(RadioTarget.Tcp("second", 2))
        m.connect(RadioTarget.Tcp("third", 3))
        eventually(30_000, "connected") { m.state.value is LinkState.Connected }
        delay(800)
        assertEquals(RadioTarget.Tcp("third", 3), (m.state.value as LinkState.Connected).target)
        m.disconnect()
    }

    @Test
    fun errorTextsAreInPlainWords() {
        assertEquals("Bluetooth permission is missing. Allow Nearby devices in the app settings.", friendlyError(SecurityException("x")))
        assertTrue(friendlyError(RuntimeException("wrapped", SecurityException("x"))).contains("Nearby devices"))
        assertTrue(friendlyError(IOException("BLE connect failed (gattStatus=133)")).startsWith("Radio not found"))
        assertTrue(friendlyError(IOException("GATT error: status=5 insufficient authentication")).contains("PIN"))
        assertTrue(friendlyError(IOException("bond/MTU/timing error; consider clearing the OS bond cache")).contains("Forget the radio"))
        assertEquals("Bluetooth is off. Turn it on to connect.", friendlyError(BluetoothOffException()))
        assertEquals("plain message", friendlyError(IllegalStateException("plain message")))
    }
}

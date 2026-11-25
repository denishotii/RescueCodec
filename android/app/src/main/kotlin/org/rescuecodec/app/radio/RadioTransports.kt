// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package org.rescuecodec.app.radio

import com.juul.kable.Scanner
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.transport.ble.BleConstants
import org.meshtastic.sdk.transport.ble.BleTransport
import org.meshtastic.sdk.transport.tcp.TcpTransport

/** A transport and what to do when we are done with it. */
class OpenedTransport(val transport: RadioTransport, val close: suspend () -> Unit = {})

/** Thrown when a transport cannot be opened and trying again will not help until the person acts. */
class RadioUnavailableException(message: String) : Exception(message)

/** Makes the transport for a [RadioTarget]. Tests plug in a fake transport here. */
fun interface TransportFactory {
    suspend fun open(target: RadioTarget): OpenedTransport
}

/** Finds Bluetooth radios. Tests plug in a fake list here. */
fun interface RadioScanner {
    fun scan(): Flow<DiscoveredRadio>
}

/**
 * The real transports: TCP for the emulator and Wi-Fi radios, Bluetooth LE for the phone with a
 * T1000-E, T-Watch or Heltec. Bluetooth pairing and the PIN dialog are done by Android when the
 * SDK first reads from the radio (see the SDK's BLE notes). [bluetoothReady] tells us if the
 * runtime permissions are granted, so we can say so instead of failing with a stack trace.
 */
class DefaultTransportFactory(
    private val bluetoothReady: () -> Boolean = { true },
    /** False while the Bluetooth adapter is switched off. */
    private val bluetoothOn: () -> Boolean = { true },
) : TransportFactory {
    override suspend fun open(target: RadioTarget): OpenedTransport = when (target) {
        is RadioTarget.Tcp -> OpenedTransport(TcpTransport(host = target.host, port = target.port))
        is RadioTarget.Ble -> {
            if (!bluetoothReady()) {
                throw RadioUnavailableException("Bluetooth permission is missing. Allow Nearby devices in the app settings.")
            }
            // The SDK's Android factory (BleTransport.android.kt), not the plain constructor: it adds the
            // post-connect step that asks for MTU 517 and a high connection priority for 30 s. Without it
            // the link stays at the default 23-byte MTU and the config and node list download is slow
            // enough to hit the handshake timeout in a hall full of radios.
            if (!bluetoothOn()) throw BluetoothOffException()
            val transport = BleTransport(target.address)
            OpenedTransport(transport) {
                withContext(NonCancellable) {
                    runCatching { transport.disconnect() }
                    runCatching { transport.shutdown() }
                }
            }
        }
    }
}

/** Scans for radios that offer the Meshtastic Bluetooth service. */
class BleRadioScanner : RadioScanner {
    override fun scan(): Flow<DiscoveredRadio> = flow {
        val scanner = Scanner {
            filters { match { services = listOf(BleConstants.MESH_SERVICE_UUID) } }
        }
        scanner.advertisements.collect { ad ->
            emit(DiscoveredRadio(ad.identifier, ad.name ?: ad.peripheralName, ad.rssi))
        }
    }
}

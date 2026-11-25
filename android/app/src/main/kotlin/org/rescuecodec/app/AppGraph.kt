// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.meshtastic.sdk.StorageProvider
import org.meshtastic.sdk.storage.sqldelight.AndroidContextHolder
import org.meshtastic.sdk.storage.sqldelight.SqlDelightStorageProvider
import org.rescuecodec.app.core.AppSettings
import org.rescuecodec.app.core.KeyValueStore
import org.rescuecodec.app.core.Messenger
import org.rescuecodec.app.core.PrefsKeyValueStore
import org.rescuecodec.app.core.UiPrefs
import org.rescuecodec.app.data.AppDatabase
import org.rescuecodec.app.radio.BleRadioScanner
import org.rescuecodec.app.radio.ConnectionManager
import org.rescuecodec.app.radio.DefaultTransportFactory
import org.rescuecodec.app.radio.LastRadioStore
import org.rescuecodec.app.radio.RadioScanner
import org.rescuecodec.app.radio.TransportFactory
import org.rescuecodec.app.service.MaydayNotifier
import org.rescuecodec.app.ui.Permissions
import java.io.File

/**
 * All long-lived objects of the app in one place: database, settings, connection and messenger.
 * One instance lives in [RescueApp]. Instrumented tests build their own with fakes.
 */
class AppGraph(
    context: Context,
    val database: AppDatabase = AppDatabase.open(context),
    val keyValue: KeyValueStore = PrefsKeyValueStore(context),
    transports: TransportFactory? = null,
    scanner: RadioScanner = BleRadioScanner(),
    storage: StorageProvider? = null,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val appContext: Context = context.applicationContext
    val settings = AppSettings(keyValue)
    val lastRadio = LastRadioStore(keyValue)

    /** Language and day/night, watched by the screens. */
    val ui = UiPrefs(settings, keyValue)

    val link: ConnectionManager = ConnectionManager(
        scope = scope,
        transports = transports ?: DefaultTransportFactory(
            bluetoothReady = { Permissions.bluetoothGranted(appContext) },
            bluetoothOn = { bluetoothIsOn(appContext) },
        ),
        scanner = scanner,
        storage = storage ?: defaultStorage(appContext),
        lastRadio = lastRadio,
        log = { android.util.Log.i(TAG, it) },
    )

    val messenger = Messenger(
        scope = scope,
        clientFlow = link.client,
        attachedFlow = link.attached,
        messages = database.messages(),
        tasks = database.tasks(),
        stats = database.stats(),
        settings = settings,
        log = { android.util.Log.i(TAG, it) },
        alerts = MaydayNotifier(appContext) { ui.language.value },
    )

    private var started = false

    /** Starts the messenger loops (restores queue and tasks from the database). Safe to call often. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch { messenger.start() }
    }

    /** Stops everything that was started. For tests. */
    fun shutdown() {
        link.disconnect()
    }

    companion object {
        const val TAG = "RescueCodec"

        private fun defaultStorage(context: Context): StorageProvider {
            AndroidContextHolder.context = context.applicationContext
            val dir = File(context.filesDir, "mesh").apply { mkdirs() }
            return SqlDelightStorageProvider(baseDir = dir.absolutePath)
        }

        /** False only when we know the Bluetooth adapter is off. No permission needed to read this. */
        private fun bluetoothIsOn(context: Context): Boolean = runCatching {
            context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled ?: true
        }.getOrDefault(true)

        fun from(context: Context): AppGraph = (context.applicationContext as RescueApp).graph
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.rescuecodec.app.AppGraph
import org.rescuecodec.app.MainActivity
import org.rescuecodec.app.R
import org.rescuecodec.app.radio.ConnectPhase
import org.rescuecodec.app.radio.LinkState
import org.rescuecodec.app.radio.RadioTarget
import org.rescuecodec.app.ui.Permissions

/**
 * Keeps the radio link alive with the screen off and the app in the background.
 *
 * It is a foreground service of type `connectedDevice` (required on Android 14 and newer for
 * Bluetooth links). It shows one notification with the link state and a Disconnect button, and holds
 * a partial wake lock while connected, so the phone does not sleep through incoming messages.
 *
 * The connection itself lives in [AppGraph.link]. The service only starts and stops it, and shows
 * its state. If Android kills the process, the sticky restart reconnects to the last radio.
 */
class RadioService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null
    private var watchdogJob: Job? = null
    private var lastNotificationText: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            // The person pressed Disconnect: no reconnect afterwards.
            AppGraph.from(this).link.disconnect()
            running = false
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }
        // Android wants startForeground() within 5 s of startForegroundService(). Do it first, with a notification
        // that needs nothing from the app (opening the database comes later). If Android refuses (a sticky restart
        // from the background on Android 12+, or Android 14 type rules), do not crash: keep the link running
        // inside the process and stop the service. The next time the app opens it tries again.
        if (!tryEnterForeground(startingNotification())) {
            Log.w(TAG, "foreground service refused, connecting without it")
            val graph = AppGraph.from(this)
            graph.start()
            connectFrom(graph, intent)
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        val graph = AppGraph.from(this)
        graph.start()
        running = true
        connectFrom(graph, intent)
        watch(graph)
        return START_STICKY
    }

    private fun connectFrom(graph: AppGraph, intent: Intent?) {
        val target = intent?.getStringExtra(EXTRA_TARGET)?.let { RadioTarget.decode(it) }
        val state = graph.link.state.value
        if (target != null) graph.link.connect(target)
        else if (state is LinkState.Disconnected || state is LinkState.Error) graph.link.connectLast()
    }

    private fun watch(graph: AppGraph) {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            graph.link.state.collectLatest { state ->
                updateWakeLock(state is LinkState.Connected)
                notifyState(state)
                if (state is LinkState.Error && state.retryAtMs != null) {
                    // Count down the retry seconds in the notification, in steps of 5 s (notifyState skips equal text).
                    while (true) {
                        delay(NOTIFY_STEP_MS)
                        notifyState(state)
                        if (System.currentTimeMillis() >= state.retryAtMs) break
                    }
                }
            }
        }
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var downSince = 0L
            var lockRenewed = System.currentTimeMillis()
            while (true) {
                delay(WATCHDOG_STEP_MS)
                val now = System.currentTimeMillis()
                val state = graph.link.state.value
                // Renew the wake lock while connected. It has a timeout, so a stuck state cannot drain the battery.
                if (state is LinkState.Connected && now - lockRenewed >= WAKE_LOCK_RENEW_MS) {
                    updateWakeLock(true, renew = true)
                    lockRenewed = now
                }
                // The person wants the link up but it is idle: no loop runs. Start it again.
                if (state is LinkState.Disconnected && graph.link.wantConnected()) {
                    if (downSince == 0L) downSince = now
                    else if (now - downSince >= WATCHDOG_IDLE_MS) {
                        Log.w(TAG, "watchdog: link idle for ${(now - downSince) / 1000} s, reconnecting")
                        graph.link.connectLast()
                        downSince = 0L
                    }
                } else downSince = 0L
            }
        }
    }

    override fun onDestroy() {
        running = false
        watchJob?.cancel()
        watchdogJob?.cancel()
        scope.cancel()
        updateWakeLock(false)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notification

    /** False if Android refused (not allowed from the background, or the type rules are not met). Never throws. */
    private fun tryEnterForeground(n: Notification): Boolean = try {
        createChannel()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        foregroundStarted = true
        true
    } catch (e: Exception) {
        Log.w(TAG, "startForeground failed: $e")
        false
    }

    /** Only notifies when the text changed, so the retry countdown costs one update per 5 s, not one per second. */
    private fun notifyState(state: LinkState) {
        if (!foregroundStarted) return
        val text = describe(state)
        if (text == lastNotificationText) return
        lastNotificationText = text
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(NOTIFICATION_ID, initialNotification(state)) }
    }

    private fun startingNotification(): Notification = buildNotification(getString(R.string.notif_starting), openRadioScreen = false)

    private fun initialNotification(state: LinkState): Notification =
        buildNotification(describe(state), openRadioScreen = state is LinkState.Error && state.retryAtMs == null)

    /** [openRadioScreen]: when the person has to act (permission, Bluetooth off), a tap opens the Radio screen. */
    private fun buildNotification(text: String, openRadioScreen: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (openRadioScreen) openIntent.putExtra("screen", "RADIO")
        val open = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RadioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_disconnect), stop)
            .build()
    }

    private fun describe(state: LinkState): String = when (state) {
        LinkState.Disconnected -> getString(R.string.notif_disconnected)
        is LinkState.Scanning -> getString(R.string.notif_scanning)
        is LinkState.Connecting ->
            if (state.phase == ConnectPhase.PAIRING) getString(R.string.notif_pairing, state.target.label)
            else getString(R.string.notif_connecting, state.target.label)
        is LinkState.Connected -> getString(R.string.notif_connected, state.nodeName ?: state.target.label)
        is LinkState.Error -> {
            val at = state.retryAtMs
            if (at != null) {
                val secs = ((at - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
                getString(R.string.notif_error_retry, state.message, (secs + 4) / 5 * 5)      // steps of 5 s
            }
            else getString(R.string.notif_error, state.message)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val c = NotificationChannel(CHANNEL_ID, getString(R.string.channel_radio), NotificationManager.IMPORTANCE_LOW)
            c.description = getString(R.string.channel_radio_desc)
            nm.createNotificationChannel(c)
        }
    }

    /** Holds the wake lock with a timeout of 10 minutes. The watchdog renews it every 5 minutes while connected. */
    private fun updateWakeLock(hold: Boolean, renew: Boolean = false) {
        if (hold) {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rescuecodec:radio").apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_MS)
                }
            } else if (renew) {
                wakeLock?.acquire(WAKE_LOCK_MS)
            }
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    companion object {
        const val ACTION_START = "org.rescuecodec.app.action.START_RADIO"
        const val ACTION_STOP = "org.rescuecodec.app.action.STOP_RADIO"
        const val EXTRA_TARGET = "target"
        private const val TAG = "RescueCodec"
        private const val CHANNEL_ID = "radio_link"
        private const val NOTIFY_STEP_MS = 5_000L
        private const val WATCHDOG_STEP_MS = 2_000L
        private const val WATCHDOG_IDLE_MS = 5_000L
        private const val WAKE_LOCK_MS = 10 * 60_000L
        private const val WAKE_LOCK_RENEW_MS = 5 * 60_000L
        private const val NOTIFICATION_ID = 1

        /** True while the service runs. For tests and the screen. */
        @Volatile
        var running: Boolean = false
            private set

        /**
         * Starts the service and connects to [target], or to the last radio if [target] is null.
         * Never throws. If Android does not allow a foreground service right now, or a Bluetooth radio is wanted
         * without the Nearby devices permission, the link is started inside the process without the service
         * (the permission case then shows "permission missing" at once, instead of a service that cannot connect).
         */
        fun start(context: Context, target: RadioTarget? = null) {
            val app = context.applicationContext
            val graph = AppGraph.from(app)
            val wanted = target ?: graph.lastRadio.get()
            if (wanted is RadioTarget.Ble && !Permissions.bluetoothGranted(app)) {
                connectDirect(graph, target)
                return
            }
            val i = Intent(app, RadioService::class.java).setAction(ACTION_START)
            if (target != null) i.putExtra(EXTRA_TARGET, target.encode())
            try {
                ContextCompat.startForegroundService(app, i)
            } catch (e: Exception) {          // ForegroundServiceStartNotAllowedException and friends
                Log.w(TAG, "could not start the radio service: $e")
                connectDirect(graph, target)
            }
        }

        private fun connectDirect(graph: AppGraph, target: RadioTarget?) {
            graph.start()
            if (target != null) graph.link.connect(target) else graph.link.connectLast()
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            try {
                app.startService(Intent(app, RadioService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                Log.w(TAG, "could not reach the radio service to stop it: $e")
                AppGraph.from(app).link.disconnect()
            }
        }
    }
}

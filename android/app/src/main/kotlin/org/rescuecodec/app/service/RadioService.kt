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
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = AppGraph.from(this)
        graph.start()
        val action = intent?.action ?: ACTION_START
        // Android requires startForeground() soon after startForegroundService(), even if we then stop.
        enterForeground(initialNotification(graph.link.state.value))
        when (action) {
            ACTION_STOP -> {
                graph.link.disconnect()
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                running = true
                val target = intent?.getStringExtra(EXTRA_TARGET)?.let { RadioTarget.decode(it) }
                val state = graph.link.state.value
                if (target != null) graph.link.connect(target)
                else if (state is LinkState.Disconnected || state is LinkState.Error) graph.link.connectLast()
                watch(graph)
            }
        }
        return START_STICKY
    }

    private fun watch(graph: AppGraph) {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            graph.link.state.collectLatest { state ->
                updateWakeLock(state is LinkState.Connected)
                notifyState(state)
                if (state is LinkState.Error && state.retryAtMs != null) {
                    // Count down the retry seconds in the notification.
                    while (true) {
                        delay(1_000)
                        notifyState(state)
                        if (System.currentTimeMillis() >= state.retryAtMs) break
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        running = false
        watchJob?.cancel()
        scope.cancel()
        updateWakeLock(false)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notification

    private fun enterForeground(n: Notification) {
        createChannel()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        foregroundStarted = true
    }

    private fun notifyState(state: LinkState) {
        if (!foregroundStarted) return
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(NOTIFICATION_ID, initialNotification(state)) }
    }

    private fun initialNotification(state: LinkState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RadioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(describe(state))
            .setStyle(NotificationCompat.BigTextStyle().bigText(describe(state)))
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
            if (at != null) getString(R.string.notif_error_retry, state.message, ((at - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0))
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

    private fun updateWakeLock(hold: Boolean) {
        if (hold) {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rescuecodec:radio").apply {
                    setReferenceCounted(false)
                    acquire()
                }
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
        private const val CHANNEL_ID = "radio_link"
        private const val NOTIFICATION_ID = 1

        /** True while the service runs. For tests and the screen. */
        @Volatile
        var running: Boolean = false
            private set

        /** Starts the service and connects to [target], or to the last radio if [target] is null. */
        fun start(context: Context, target: RadioTarget? = null) {
            val i = Intent(context, RadioService::class.java).setAction(ACTION_START)
            if (target != null) i.putExtra(EXTRA_TARGET, target.encode())
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RadioService::class.java).setAction(ACTION_STOP))
        }
    }
}

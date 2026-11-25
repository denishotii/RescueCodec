// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.rescuecodec.app.MainActivity
import org.rescuecodec.app.R
import org.rescuecodec.app.core.Labels
import org.rescuecodec.app.core.MaydayAlerts
import org.rescuecodec.app.ui.Permissions
import java.util.Locale

/**
 * A loud notification for a Mayday from another radio: its own channel "Mayday" with high importance, the alarm
 * sound and a strong vibration, so Android shows it as a heads-up. Text in the app's language, for example
 * "MAYDAY from !a1b2c3d4: team member injured". One notification per task key (the tag), removed when the task is
 * done. A tap opens the Actions screen. Without the notification permission (Android 13+) it does nothing. Never throws.
 */
class MaydayNotifier(
    private val context: Context,
    private val language: () -> String,
    /** The sender's name from the radio's node list, or null. */
    private val names: (Long) -> String? = { null },
) : MaydayAlerts {

    override fun show(key: String, from: Long, reason: String?, channel: String?) {
        if (!allowed()) return
        try {
            val lang = language()
            val res = resources(lang)
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            createChannel(nm, res)
            val who = names(from) ?: MaydayAlerts.nodeLabel(from)
            val title = if (reason != null) res.getString(R.string.mayday_alert_reason, who, Labels.value(lang, "mayday", reason))
            else res.getString(R.string.mayday_alert, who)
            // The channel it came on, so the person knows which team called: "On RescueCodec. Tap to open Tasks and answer."
            val body = if (channel.isNullOrBlank()) res.getString(R.string.mayday_alert_text)
            else res.getString(R.string.notif_on_channel, channel) + " " + res.getString(R.string.mayday_alert_text)
            val open = Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_MAYDAY)
                .putExtra("screen", "ACTIONS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // Own request code and action, so it never replaces the radio notification's tap intent.
            val tap = PendingIntent.getActivity(context, REQUEST_CODE, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setColor(MAYDAY_RED)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(tap)
                .build()
            nm.notify(key, NOTIFICATION_ID, n)
        } catch (e: Exception) { // SecurityException if the permission was taken away a moment ago, and friends
            Log.w(TAG, "mayday notification failed: $e")
        }
    }

    override fun clear(key: String) {
        try {
            context.getSystemService(NotificationManager::class.java)?.cancel(key, NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "could not remove the mayday notification: $e")
        }
    }

    private fun allowed(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Permissions.granted(context, Manifest.permission.POST_NOTIFICATIONS)) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Strings in the language chosen in the app, not the phone's language. */
    private fun resources(lang: String): Resources {
        val cfg = Configuration(context.resources.configuration)
        cfg.setLocale(Locale.forLanguageTag(lang))
        return context.createConfigurationContext(cfg).resources
    }

    /** Sound and vibration belong to the channel and cannot change after it exists. */
    private fun createChannel(nm: NotificationManager, res: Resources) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val c = NotificationChannel(CHANNEL_ID, res.getString(R.string.channel_mayday), NotificationManager.IMPORTANCE_HIGH)
        c.description = res.getString(R.string.channel_mayday_desc)
        c.enableVibration(true)
        c.vibrationPattern = VIBRATION
        c.enableLights(true)
        c.lightColor = MAYDAY_RED
        c.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        c.setSound(
            sound,
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
        )
        nm.createNotificationChannel(c)
    }

    companion object {
        private const val TAG = "RescueCodec"
        const val CHANNEL_ID = "mayday"
        const val ACTION_OPEN_MAYDAY = "org.rescuecodec.app.action.OPEN_MAYDAY"
        private const val NOTIFICATION_ID = 2
        private const val REQUEST_CODE = 2
        private const val MAYDAY_RED = 0xFFC40D19.toInt()

        /** Three long pulses: off, on, off, on, off, on (ms). */
        private val VIBRATION = longArrayOf(0, 1000, 400, 1000, 400, 1000)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.rescuecodec.app.MainActivity
import org.rescuecodec.app.R
import org.rescuecodec.app.core.AlertDelivery
import org.rescuecodec.app.core.AlertKind
import org.rescuecodec.app.core.ChatId
import org.rescuecodec.app.core.Labels
import org.rescuecodec.app.core.Readable
import org.rescuecodec.app.core.TeamAlerts
import org.rescuecodec.app.data.MessageDao
import org.rescuecodec.app.data.MessageEntity
import org.rescuecodec.app.data.TaskDao
import org.rescuecodec.app.ui.Permissions
import org.rescuecodec.codec.Json
import java.util.Locale

/** True while a screen of the app is in front. Set by [MainActivity] in onStart and onStop. */
object AppForeground {
    @Volatile var visible: Boolean = false
}

/**
 * Watches the message table and tells the person about new messages, tasks and answers to their own reports
 * ([TeamAlerts] decides). It only reads rows the messenger wrote; it never sends or receives. In the background it
 * posts a notification; in front it emits a [cues] event, and the screen plays a short sound with a haptic tick.
 */
class TeamWatcher(
    private val messages: MessageDao,
    private val tasks: TaskDao,
    private val notifier: TeamNotifier?,
    private val foreground: () -> Boolean,
    private val soundsOn: () -> Boolean,
) {
    private val _cues = MutableSharedFlow<AlertKind>(extraBufferCapacity = 8)

    /** Alerts while the app is in front and sounds are on. */
    val cues: SharedFlow<AlertKind> = _cues.asSharedFlow()

    private val alerted = LinkedHashSet<String>()

    suspend fun run() {
        var last = -1L
        messages.observeRecent(WINDOW).collect { rows ->
            // The first look only sets the starting point: history never alerts.
            if (last < 0) {
                last = rows.maxOfOrNull { it.id } ?: 0L
                return@collect
            }
            val fresh = rows.filter { it.id > last }.sortedBy { it.id }
            if (fresh.isEmpty()) return@collect
            last = fresh.last().id
            for (row in fresh) handle(row)
        }
    }

    private suspend fun handle(row: MessageEntity) {
        val key = TeamAlerts.keyOf(row)
        val task = row.taskKey?.let { runCatching { tasks.get(it) }.getOrNull() }
        val kind = TeamAlerts.decide(row, ackOnOwnReport = row.type == "ack" && task?.local == true, alreadyAlerted = key in alerted) ?: return
        alerted += key
        if (alerted.size > 500) alerted.remove(alerted.first())
        when (TeamAlerts.delivery(foreground(), soundsOn())) {
            AlertDelivery.NOTIFICATION -> notifier?.show(kind, row, task?.type)
            AlertDelivery.IN_APP_CUE -> _cues.tryEmit(kind)
            AlertDelivery.SILENT -> Unit
        }
    }

    companion object {
        private const val WINDOW = 50
    }
}

/**
 * Posts the notifications of [TeamWatcher]: one per message or task, grouped per Android channel. Each one names the
 * radio channel (or says it is a direct message) and the sender by the name in the radio's node list. A tap opens
 * that chat; a task opens Tasks. Never throws.
 */
class TeamNotifier(
    private val context: Context,
    private val language: () -> String,
    /** The sender's name from the radio's node list, or null. */
    private val names: (Long) -> String? = { null },
    /** The team channel's name, for rows still waiting for it. */
    private val team: () -> String = { "RescueCodec" },
) {

    fun show(kind: AlertKind, row: MessageEntity, taskType: String?) {
        if (!allowed()) return
        try {
            val lang = language()
            val res = resources(lang)
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            createChannels(nm, res)
            val who = names(row.node) ?: TeamAlerts.ackTeam(row.json) ?: res.getString(R.string.sender_node, TeamAlerts.shortNode(row.node))
            val chat = ChatId.of(row, team())
            // "on RescueCodec" or "direct message": every notification says where it came from.
            val where = when (chat) {
                is ChatId.Direct -> res.getString(R.string.notif_direct)
                is ChatId.Channel -> chat.name
            }
            val title: String
            val text: String
            val screen: String
            when (kind) {
                AlertKind.TASK -> {
                    title = res.getString(R.string.notif_title_where, Labels.head(lang, row.type), where)
                    text = res.getString(R.string.notif_from, who) + " " + (Readable.withoutPosition(row.json, lang) ?: res.getString(R.string.notif_task_text))
                    screen = "TASKS"
                }
                AlertKind.MESSAGE -> {
                    title = res.getString(R.string.notif_title_where, who, where)
                    text = textOf(row.json) ?: Readable.of(row.json, lang).orEmpty()
                    screen = "TEAM"
                }
                AlertKind.ANSWER -> {
                    val what = Labels.head(lang, taskType ?: "found")
                    title = if (TeamAlerts.ackStatus(row.json) == "done") res.getString(R.string.notif_answer_done, who, what)
                    else res.getString(R.string.notif_answer_accepted, who, what)
                    text = res.getString(R.string.notif_on_channel, where)
                    screen = "TEAM"
                }
            }
            val channel = channelOf(kind)
            val open = Intent(context, MainActivity::class.java)
                // One action per chat, so two chats never share (and overwrite) one tap intent.
                .setAction("org.rescuecodec.app.action.OPEN_$screen" + if (screen == "TEAM") "/" + chat.key else "")
                .putExtra("screen", screen)
                .putExtra("chat", chat.key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val tap = PendingIntent.getActivity(context, requestOf(kind), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setAutoCancel(true)
                .setContentIntent(tap)
                .setGroup(GROUP_PREFIX + channel)
                .setWhen(row.atMs)
                .setShowWhen(true)
                .setPriority(if (kind == AlertKind.TASK) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(if (kind == AlertKind.MESSAGE) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_EVENT)
            if (text.isNotEmpty()) b.setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            nm.notify(TeamAlerts.keyOf(row), NOTIFICATION_ID, b.build())
            // A summary per channel, so Android bundles them instead of stacking many single ones.
            val summary = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(res.getString(channelName(kind)))
                .setGroup(GROUP_PREFIX + channel)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setAutoCancel(true)
                .setContentIntent(tap)
                .build()
            nm.notify(GROUP_PREFIX + channel, SUMMARY_ID, summary)
        } catch (e: Exception) {
            Log.w(TAG, "team notification failed: $e")
        }
    }

    /** The app came to the front: message and answer notifications are read now. Task notifications stay. */
    fun clearRead() {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.activeNotifications.filter { it.notification.channelId == CH_MESSAGES || it.notification.channelId == CH_ANSWERS }
                .forEach { nm.cancel(it.tag, it.id) }
        } catch (e: Exception) {
            Log.w(TAG, "could not clear notifications: $e")
        }
    }

    private fun textOf(json: String?): String? = runCatching {
        val m = Json.parseObject(json ?: return null)
        (m["text"] as? Map<*, *>)?.get("text") as? String ?: m["text"] as? String
    }.getOrNull()

    private fun allowed(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Permissions.granted(context, Manifest.permission.POST_NOTIFICATIONS)) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun resources(lang: String): Resources {
        val cfg = Configuration(context.resources.configuration)
        cfg.setLocale(Locale.forLanguageTag(lang))
        return context.createConfigurationContext(cfg).resources
    }

    /** Sound and vibration belong to the channel. The phone's default notification sound, nothing downloaded. */
    private fun createChannels(nm: NotificationManager, res: Resources) {
        if (nm.getNotificationChannel(CH_MESSAGES) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_MESSAGES, res.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = res.getString(R.string.channel_messages_desc)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 180)
                },
            )
        }
        if (nm.getNotificationChannel(CH_TASKS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_TASKS, res.getString(R.string.channel_tasks), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = res.getString(R.string.channel_tasks_desc)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                },
            )
        }
        if (nm.getNotificationChannel(CH_ANSWERS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ANSWERS, res.getString(R.string.channel_answers), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = res.getString(R.string.channel_answers_desc)
                    enableVibration(false)
                },
            )
        }
    }

    companion object {
        private const val TAG = "RescueCodec"
        const val CH_MESSAGES = "messages"
        const val CH_TASKS = "tasks"
        const val CH_ANSWERS = "answers"
        private const val GROUP_PREFIX = "rescuecodec."
        private const val NOTIFICATION_ID = 3
        private const val SUMMARY_ID = 4

        fun channelOf(kind: AlertKind): String = when (kind) {
            AlertKind.MESSAGE -> CH_MESSAGES
            AlertKind.TASK -> CH_TASKS
            AlertKind.ANSWER -> CH_ANSWERS
        }

        private fun channelName(kind: AlertKind): Int = when (kind) {
            AlertKind.MESSAGE -> R.string.channel_messages
            AlertKind.TASK -> R.string.channel_tasks
            AlertKind.ANSWER -> R.string.channel_answers
        }

        private fun requestOf(kind: AlertKind): Int = 10 + kind.ordinal
    }
}

/** The short in-app sound: the phone's own tone generator, nothing downloaded. A task gets two beeps. */
object InAppSound {
    fun play(kind: AlertKind) {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            val (t, ms) = if (kind == AlertKind.TASK) ToneGenerator.TONE_PROP_BEEP2 to 320 else ToneGenerator.TONE_PROP_ACK to 180
            tone.startTone(t, ms)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, ms + 200L)
        } catch (e: RuntimeException) { // no audio on this device
            Log.w("RescueCodec", "in-app sound failed: $e")
        }
    }
}

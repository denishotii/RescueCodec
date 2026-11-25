// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.core

import org.rescuecodec.app.data.MessageEntity
import org.rescuecodec.app.data.MessageState
import org.rescuecodec.codec.Json

/** What kind of alert a new message gets. Each kind has its own notification channel. */
enum class AlertKind {
    /** A text (or sector status) from another team member. Channel "Messages": default sound, short vibration. */
    MESSAGE,

    /** A found person, a request or a danger. Channel "Tasks": high importance, sound and vibration. */
    TASK,

    /** A team accepted or finished MY report. Channel "Answers": gentle. */
    ANSWER,
}

/** How the alert reaches the person. */
enum class AlertDelivery {
    /** The app is not in front: a system notification. */
    NOTIFICATION,

    /** The app is in front: a short in-app sound and a haptic tick. */
    IN_APP_CUE,

    /** The app is in front and the person turned sounds off: the chat shows it, nothing else. */
    SILENT,
}

/**
 * Decides which new messages alert the person, the same way [MaydayAlerts.shouldAlert] does for a Mayday. Pure, so it
 * is unit tested. It only reads rows the messenger already wrote to the database; it never touches sending or
 * receiving. A Mayday is not handled here: [MaydayAlerts] gives it its own loud channel.
 */
object TeamAlerts {
    /** Task types that ask someone to act. */
    val TASK_TYPES = setOf("found", "request", "hazard")

    /**
     * The alert kind for [row], or null for no alert. Never for my own messages, for rows that are not a fresh
     * receive (duplicates and invalid packets), for a Mayday (own channel), for track points, or for a [key] that was
     * already alerted (a retransmission). [ackOnOwnReport] is true when [row] is an Ack that answers a report I sent.
     */
    fun decide(row: MessageEntity, ackOnOwnReport: Boolean, alreadyAlerted: Boolean): AlertKind? {
        if (row.dir != "in" || row.state != MessageState.RECEIVED || alreadyAlerted) return null
        return when (row.type) {
            in TASK_TYPES -> AlertKind.TASK
            "text", "sector" -> AlertKind.MESSAGE
            "ack" -> if (ackOnOwnReport && ackStatus(row.json) in setOf("accepted", "done")) AlertKind.ANSWER else null
            else -> null // mayday (own channel), track, unknown
        }
    }

    /** System notification when the app is in the background; an in-app cue (or nothing) when it is in front. */
    fun delivery(foreground: Boolean, soundsOn: Boolean): AlertDelivery = when {
        !foreground -> AlertDelivery.NOTIFICATION
        soundsOn -> AlertDelivery.IN_APP_CUE
        else -> AlertDelivery.SILENT
    }

    /**
     * One key per thing the person is told about, so a retransmission never alerts twice: the task key for a task,
     * task key plus status plus sender for an answer, sender plus message id for a text (row id when there is no id).
     */
    fun keyOf(row: MessageEntity): String = when {
        row.type in TASK_TYPES && row.taskKey != null -> "task:${row.taskKey}"
        row.type == "ack" -> "ack:${row.taskKey}:${ackStatus(row.json)}:${row.node}"
        row.msgId > 0 -> "msg:${row.node}:${row.msgId}"
        else -> "row:${row.id}"
    }

    /** Status of an Ack ("accepted", "declined", "done"), or null. */
    fun ackStatus(json: String?): String? = runCatching {
        (Json.parseObject(json ?: return null)["ack"] as? Map<*, *>)?.get("status") as? String
    }.getOrNull()

    /** Team label in an Ack ("Trupp 3"), or null. */
    fun ackTeam(json: String?): String? = runCatching {
        (Json.parseObject(json ?: return null)["ack"] as? Map<*, *>)?.get("team") as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Short sender label: the team label if one is known, else "1a2b" (the last four hex digits, like Meshtastic). */
    fun shortNode(node: Long): String = "%08x".format(node).takeLast(4)
}

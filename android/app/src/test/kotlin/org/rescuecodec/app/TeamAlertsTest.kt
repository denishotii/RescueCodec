// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import org.rescuecodec.app.core.AlertDelivery
import org.rescuecodec.app.core.AlertKind
import org.rescuecodec.app.core.TeamAlerts
import org.rescuecodec.app.data.MessageEntity
import org.rescuecodec.app.data.MessageState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Which new messages notify, on which channel, and how (notification, in-app cue, nothing). */
class TeamAlertsTest {
    private fun row(
        type: String, json: String?, dir: String = "in", state: String = MessageState.RECEIVED,
        node: Long = 0x0badf00dL, msgId: Int = 7, taskKey: String? = "0badf00d-7", id: Long = 1,
    ) = MessageEntity(id = id, dir = dir, node = node, type = type, msgId = msgId, bytes = ByteArray(3), json = json, atMs = 0, state = state, taskKey = taskKey)

    private val found = """{"id":7,"found":{"age":"child"}}"""
    private val text = """{"text":{"text":"On my way"}}"""
    private fun ack(status: String) = """{"ack":{"ref":7,"status":"$status","team":"Trupp 3"}}"""

    @Test
    fun aNewTaskFromTheTeamGoesToTheTasksChannel() {
        assertEquals(AlertKind.TASK, TeamAlerts.decide(row("found", found), ackOnOwnReport = false, alreadyAlerted = false))
        assertEquals(AlertKind.TASK, TeamAlerts.decide(row("request", """{"id":7,"request":{"resource":"stretcher"}}"""), false, false))
        assertEquals(AlertKind.TASK, TeamAlerts.decide(row("hazard", """{"id":7,"hazard":{"kind":"rockfall"}}"""), false, false))
    }

    @Test
    fun aTextFromATeamMemberGoesToTheMessagesChannel() {
        assertEquals(AlertKind.MESSAGE, TeamAlerts.decide(row("text", text, msgId = 0, taskKey = null), false, false))
    }

    @Test
    fun neverForMyOwnMessagesDuplicatesOrRetransmissions() {
        assertNull(TeamAlerts.decide(row("found", found, dir = "out", state = MessageState.SENT), false, false))
        assertNull(TeamAlerts.decide(row("text", text, dir = "out", state = MessageState.QUEUED), false, false))
        assertNull(TeamAlerts.decide(row("found", found, state = MessageState.INVALID), false, false))
        // The same task seen again (a retransmission) does not alert a second time.
        assertNull(TeamAlerts.decide(row("found", found, id = 2), false, alreadyAlerted = true))
        assertEquals(TeamAlerts.keyOf(row("found", found, id = 1)), TeamAlerts.keyOf(row("found", found, id = 2)))
    }

    @Test
    fun maydayAndTrackAreNotHandledHere() {
        // A Mayday has its own loud channel (MaydayAlerts); a track point is never worth a sound.
        assertNull(TeamAlerts.decide(row("mayday", """{"id":7,"mayday":{}}"""), false, false))
        assertNull(TeamAlerts.decide(row("track", """{"track":{}}"""), false, false))
    }

    @Test
    fun anAnswerAlertsOnlyWhenItAnswersMyOwnReport() {
        assertEquals(AlertKind.ANSWER, TeamAlerts.decide(row("ack", ack("accepted")), ackOnOwnReport = true, alreadyAlerted = false))
        assertEquals(AlertKind.ANSWER, TeamAlerts.decide(row("ack", ack("done")), true, false))
        assertNull(TeamAlerts.decide(row("ack", ack("declined")), true, false))
        assertNull(TeamAlerts.decide(row("ack", ack("accepted")), ackOnOwnReport = false, alreadyAlerted = false))
        // Accept and Done of the same report are two different things to tell.
        assertNotEquals(TeamAlerts.keyOf(row("ack", ack("accepted"))), TeamAlerts.keyOf(row("ack", ack("done"))))
        assertEquals("Trupp 3", TeamAlerts.ackTeam(ack("done")))
    }

    @Test
    fun inFrontTheAppPlaysACueInsteadOfANotification() {
        assertEquals(AlertDelivery.NOTIFICATION, TeamAlerts.delivery(foreground = false, soundsOn = true))
        assertEquals(AlertDelivery.NOTIFICATION, TeamAlerts.delivery(foreground = false, soundsOn = false))
        assertEquals(AlertDelivery.IN_APP_CUE, TeamAlerts.delivery(foreground = true, soundsOn = true))
        assertEquals(AlertDelivery.SILENT, TeamAlerts.delivery(foreground = true, soundsOn = false))
    }

    @Test
    fun shortNodeIsTheLastFourHexDigits() {
        assertEquals("f00d", TeamAlerts.shortNode(0x0badf00dL))
    }
}

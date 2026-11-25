// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import org.rescuecodec.app.core.MaydayAlerts
import org.rescuecodec.engine.Outcome
import org.rescuecodec.engine.TaskState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When an incoming packet rings the Mayday alarm, and when the alarm goes away. */
class MaydayAlertsTest {
    private val me = 0xa1b2c3d4L
    private val other = 0x0badf00dL

    private fun alert(
        type: String = "mayday", from: Long = other, self: Long = me, outcome: Outcome = Outcome.NEW,
        state: TaskState? = TaskState.OPEN, alreadyAlerted: Boolean = false,
    ) = MaydayAlerts.shouldAlert(type, from, self, outcome, state, alreadyAlerted)

    @Test
    fun aNewMaydayFromAnotherRadioAlerts() {
        assertTrue(alert())
        // Another team already accepted it: still alert, it is still an emergency.
        assertTrue(alert(state = TaskState.ACCEPTED))
        // Our own node is not known yet (0): a Mayday from a real node still alerts.
        assertTrue(alert(self = 0L))
    }

    @Test
    fun copiesOwnMaydaysAndOtherMessagesDoNotAlert() {
        assertFalse(alert(outcome = Outcome.DUPLICATE), "a retransmission")
        assertFalse(alert(outcome = Outcome.IGNORED))
        assertFalse(alert(alreadyAlerted = true), "one notification per task key")
        assertFalse(alert(from = me), "our own Mayday")
        assertFalse(alert(state = TaskState.DONE), "done before the report arrived")
        for (type in listOf("found", "request", "hazard", "ack", "text")) assertFalse(alert(type = type), type)
    }

    @Test
    fun aDoneMaydayIsCleared() {
        assertTrue(MaydayAlerts.shouldClear("mayday", TaskState.DONE))
        assertFalse(MaydayAlerts.shouldClear("mayday", TaskState.ACCEPTED))
        assertFalse(MaydayAlerts.shouldClear("found", TaskState.DONE))
    }

    @Test
    fun reasonAndSender() {
        assertEquals("injured", MaydayAlerts.reasonOf(mapOf("id" to 3, "mayday" to mapOf("reason" to "injured"))))
        assertNull(MaydayAlerts.reasonOf(mapOf("id" to 3, "mayday" to emptyMap<String, Any?>())))
        assertEquals("!0badf00d", MaydayAlerts.nodeLabel(other))
    }
}

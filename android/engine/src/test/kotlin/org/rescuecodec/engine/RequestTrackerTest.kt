// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.rescuecodec.codec.RescueCodec

class RequestTrackerTest {
    private var now = 0L
    private val tracker = RequestTracker({ now })
    private val me = 0xa1b2c3d4L
    private val other = 0x11223344L
    private val team = 0x55667788L

    private fun found(id: Int) = mapOf<String, Any?>("id" to id, "found" to mapOf("age" to "child"))
    private fun ack(ref: Int, node: String, status: String, team: String? = null) =
        mapOf<String, Any?>("ack" to listOfNotNull("ref" to ref, "node" to node, "status" to status, team?.let { "team" to it }).toMap())

    @Test
    fun receivedReportIsOpenThenAcceptedThenDone() {
        assertEquals(Outcome.NEW, tracker.onReceived(me, found(7)))
        val key = TaskKey(me, 7)
        assertEquals(TaskState.OPEN, tracker[key]!!.state)
        assertEquals("a1b2c3d4-7", key.toString())

        assertEquals(Outcome.ANSWERED, tracker.onReceived(team, ack(7, "!a1b2c3d4", "accepted", "Trupp 3")))
        assertEquals(TaskState.ACCEPTED, tracker[key]!!.state)
        assertEquals("Trupp 3", tracker[key]!!.answers.single().team)

        tracker.onReceived(team, ack(7, "!a1b2c3d4", "done"))
        assertEquals(TaskState.DONE, tracker[key]!!.state)
        // A late accepted cannot reopen a done task.
        tracker.onReceived(other, ack(7, "!a1b2c3d4", "accepted"))
        assertEquals(TaskState.DONE, tracker[key]!!.state)
    }

    @Test
    fun declinedCanStillBeAcceptedByAnotherTeam() {
        tracker.onReceived(me, found(7))
        tracker.onReceived(other, ack(7, "!a1b2c3d4", "declined"))
        assertEquals(TaskState.DECLINED, tracker[TaskKey(me, 7)]!!.state)
        tracker.onReceived(team, ack(7, "!a1b2c3d4", "accepted"))
        assertEquals(TaskState.ACCEPTED, tracker[TaskKey(me, 7)]!!.state)
        // The first team declines, the second one still has it.
        tracker.onReceived(other, ack(7, "!a1b2c3d4", "declined"))
        assertEquals(TaskState.ACCEPTED, tracker[TaskKey(me, 7)]!!.state)
    }

    @Test
    fun duplicatesAreIgnoredPerSenderAndId() {
        assertEquals(Outcome.NEW, tracker.onReceived(me, found(7)))
        assertEquals(Outcome.DUPLICATE, tracker.onReceived(me, found(7)))
        assertEquals(Outcome.NEW, tracker.onReceived(other, found(7)), "same id from another sender is a different task")
        assertEquals(Outcome.NEW, tracker.onReceived(me, found(8)))
        assertEquals(3, tracker.tasks().size)
        tracker.onReceived(team, ack(7, "!a1b2c3d4", "accepted"))
        assertEquals(Outcome.IGNORED, tracker.onReceived(team, ack(7, "!a1b2c3d4", "accepted")))
    }

    @Test
    fun answerBeforeReportIsKept() {
        assertEquals(Outcome.ANSWERED, tracker.onReceived(team, ack(9, "!a1b2c3d4", "accepted")))
        val key = TaskKey(me, 9)
        assertEquals(TaskState.ACCEPTED, tracker[key]!!.state)
        assertEquals(null, tracker[key]!!.message)
        assertEquals(Outcome.NEW, tracker.onReceived(me, found(9)))
        assertNotNull(tracker[key]!!.message)
        assertEquals(TaskState.ACCEPTED, tracker[key]!!.state)
    }

    @Test
    fun anAckFloodIsBoundedAndOldAnswersWithoutReportExpire() {
        tracker.onReceived(other, found(1))
        for (n in 2..(RequestTracker.MAX_PLACEHOLDERS + 200)) {
            now += 10
            tracker.onReceived(team, ack(n, "!a1b2c3d4", "accepted"))
        }
        val stubs = tracker.tasks().count { it.message == null }
        assertTrue(stubs <= RequestTracker.MAX_PLACEHOLDERS, "placeholders: $stubs")
        assertNotNull(tracker[TaskKey(other, 1)], "a real report is never dropped")
        now += RequestTracker.PLACEHOLDER_TTL_MS + 1_000
        tracker.onReceived(other, found(2))
        assertEquals(0, tracker.tasks().count { it.message == null })
    }

    @Test
    fun sentTaskBecomesDeliveryUnknownAfterTimeout() {
        tracker.onSent(me, found(7))
        assertTrue(tracker.tick().isEmpty())
        now += 299_000
        assertTrue(tracker.tick().isEmpty())
        now += 2_000
        val changed = tracker.tick()
        assertEquals(1, changed.size)
        assertEquals(TaskState.DELIVERY_UNKNOWN, tracker[TaskKey(me, 7)]!!.state)
        // A late answer fixes it.
        tracker.onReceived(team, ack(7, "!a1b2c3d4", "accepted"))
        assertEquals(TaskState.ACCEPTED, tracker[TaskKey(me, 7)]!!.state)
    }

    @Test
    fun meshDeliveryKeepsTaskOpen() {
        tracker.onSent(me, found(7))
        tracker.onMeshDelivered(me, 7)
        now += 3_600_000
        assertTrue(tracker.tick().isEmpty())
        assertEquals(TaskState.OPEN, tracker[TaskKey(me, 7)]!!.state)
    }

    @Test
    fun maydayTimesOutFasterAndSortsFirst() {
        tracker.onSent(me, found(1))
        tracker.onSent(me, mapOf("id" to 2, "mayday" to emptyMap<String, Any?>()))
        now += 100_000
        val changed = tracker.tick()
        assertEquals(listOf("mayday"), changed.map { it.type })
        assertEquals("mayday", tracker.tasks().first().type)
    }

    @Test
    fun receivedTasksNeverTimeOut() {
        tracker.onReceived(other, found(1))
        now += 3_600_000
        assertTrue(tracker.tick().isEmpty())
    }

    @Test
    fun reportsWithoutIdAreNotTasksAndPayloadDedupeWorks() {
        assertEquals(Outcome.IGNORED, tracker.onReceived(me, mapOf("text" to mapOf("text" to "hi"))))
        val bytes = byteArrayOf(1, 2, 3)
        assertEquals(false, tracker.isDuplicatePayload(me, bytes))
        assertEquals(true, tracker.isDuplicatePayload(me, bytes))
        now += 11 * 60_000
        assertEquals(false, tracker.isDuplicatePayload(me, bytes))
    }

    @Test
    fun ackForBuildsAWireValidAnswer() {
        tracker.onReceived(me, found(7))
        val task = tracker[TaskKey(me, 7)]!!
        val msg = RequestTracker.ackFor(task, "accepted", etaMin = 15, team = "Trupp 3")
        val hex = RescueCodec.encode(msg).joinToString("") { "%02x".format(it) }
        // spec vector ack-accepted
        assertEquals("6214" + "0807" + "15d4c3b2a1" + "1801" + "200f" + "2a07" + "54727570702033", hex)
    }
}

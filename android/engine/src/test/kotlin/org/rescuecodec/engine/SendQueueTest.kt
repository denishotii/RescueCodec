// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendQueueTest {
    private var now = 1_000_000L
    private fun queue(budget: AirtimeBudget = AirtimeBudget()) = SendQueue(budget, { now })

    private fun found(id: Int) = mapOf<String, Any?>("id" to id, "found" to mapOf("age" to "child"))
    private fun mayday(id: Int) = mapOf<String, Any?>("id" to id, "mayday" to emptyMap<String, Any?>())
    private fun hazard(id: Int) = mapOf<String, Any?>("id" to id, "hazard" to mapOf("kind" to "rockfall"))
    private fun sector(n: Int, status: String) = mapOf<String, Any?>("sector" to mapOf("sector" to n, "status" to status))
    private fun text(t: String) = mapOf<String, Any?>("text" to mapOf("text" to t))
    private fun track() = mapOf<String, Any?>("track" to mapOf("points" to listOf(mapOf("lat" to 50.5, "lon" to 7.5))))
    private fun request(id: Int) = mapOf<String, Any?>("id" to id, "request" to mapOf("resource" to "medic"))

    @Test
    fun airtimeMatchesSpecTable() {
        val a = Airtime()
        // spec section 9, LONG_FAST: found-pitch 11 B 0.52 s, found-typical-gps 23 B 0.60 s, track-16-max 64 B 0.89 s
        assertTrue(abs(a.millis(11) - 518) < 5, "${a.millis(11)}")
        assertTrue(abs(a.millis(23) - 600) < 5, "${a.millis(23)}")
        assertTrue(abs(a.millis(64) - 890) < 10, "${a.millis(64)}")
        assertTrue(Airtime(ModemPreset.LONG_SLOW).millis(23) > 4 * a.millis(23))
    }

    @Test
    fun sendsInPriorityOrder() {
        val q = queue()
        q.enqueue(text("hello"))
        q.enqueue(track())
        q.enqueue(sector(3, "cleared"))
        q.enqueue(hazard(4))
        q.enqueue(request(5))
        q.enqueue(found(6))
        q.enqueue(mayday(7))
        val order = ArrayList<String>()
        while (true) {
            val m = q.poll() ?: break
            order.add(m.message.keys.first { it != "id" })
        }
        assertEquals(listOf("mayday", "found", "request", "hazard", "sector", "track", "text"), order)
    }

    @Test
    fun priorityOrdersInsideOneRankAndTypeStillComesFirst() {
        val q = queue()
        q.enqueue(mapOf("id" to 4, "priority" to "normal", "found" to emptyMap<String, Any?>()))
        q.enqueue(mayday(1))
        q.enqueue(hazard(2))
        q.enqueue(mapOf("id" to 3, "priority" to "critical", "found" to emptyMap<String, Any?>()))
        q.enqueue(mapOf("id" to 5, "found" to emptyMap<String, Any?>()))
        // Mayday first, then rank 1 by priority (critical, urgent default, normal), then the hazard.
        assertEquals(listOf(1, 3, 5, 4, 2), generateSequence { q.poll() }.map { it.message["id"] }.toList())
    }

    @Test
    fun newerVersionReplacesOlder() {
        val q = queue()
        q.enqueue(sector(3, "partial"))
        q.enqueue(sector(3, "cleared"))
        q.enqueue(found(9))
        q.enqueue(found(9))
        assertEquals(2, q.size)
        assertEquals("cleared", ((q.snapshot().last().message["sector"]) as Map<*, *>)["status"])
    }

    @Test
    fun budgetHoldsEachTypeAtItsOwnLimitButNeverMaydayOrAck() {
        // 10 percent of 100 s = 10 s. Text and track: 7 s. Sector: 9 s. Found, request, hazard: 10 s.
        val budget = AirtimeBudget(windowMs = 100_000)
        val q = queue(budget)
        repeat(40) { q.enqueue(text("t$it")) }
        var sent = 0
        while (q.poll() != null) sent++
        assertTrue(budget.usedMs(now) <= 7_000, "text stays under 70 percent: ${budget.usedMs(now)}")
        assertTrue(sent > 5, "sent $sent")
        // A sector report may still use up to 90 percent, a found report up to 100.
        repeat(40) { q.enqueue(sector(it + 1, "partial")) }
        while (q.poll() != null) { /* drain */ }
        assertTrue(budget.usedMs(now) in 7_000.0..9_000.0, "sector stops at 90 percent: ${budget.usedMs(now)}")
        repeat(40) { q.enqueue(request(it + 1)) }
        while (q.poll() != null) { /* drain */ }
        assertTrue(budget.usedMs(now) in 9_000.0..10_000.0, "request stops at 100 percent: ${budget.usedMs(now)}")
        assertTrue((q.waitMs() ?: 0) > 0)
        // Mayday and Ack are never held, even over the limit.
        q.enqueue(mayday(100))
        assertEquals(100, q.poll()!!.message["id"])
        q.enqueue(mapOf("ack" to mapOf("ref" to 3, "status" to "accepted", "node" to "!0badf00d")))
        assertTrue(q.poll() != null)
    }

    @Test
    fun aHeldMessageDoesNotBlockOneThatStillFits() {
        val budget = AirtimeBudget(windowMs = 100_000)
        val q = queue(budget)
        repeat(40) { q.enqueue(text("t$it")) }
        while (q.poll() != null) { /* text is now at its 70 percent limit */ }
        q.enqueue(text("held"))
        q.enqueue(request(500))
        assertEquals(500, q.poll()!!.message["id"])
        assertNull(q.poll())
    }

    @Test
    fun budgetRecoversAfterTheWindow() {
        val budget = AirtimeBudget(windowMs = 100_000)
        val q = queue(budget)
        repeat(30) { q.enqueue(request(it + 1)) }
        while (q.poll() != null) { /* drain budget */ }
        val wait = q.waitMs()!!
        assertTrue(wait in 1..100_000)
        now += wait
        assertTrue(q.poll() != null)
    }

    @Test
    fun noLimitRegionIgnoresDutyCycle() {
        val q = queue(AirtimeBudget(region = Region.NO_LIMIT, windowMs = 1000))
        repeat(50) { q.enqueue(request(it + 1)) }
        var n = 0
        while (q.poll() != null) n++
        assertEquals(50, n)
    }

    @Test
    fun staleRoutineMessagesExpire() {
        val q = queue()
        q.enqueue(text("old"))
        q.enqueue(found(1))
        now += 16 * 60_000
        q.peek()
        assertEquals(1, q.size)
        assertEquals(1, q.poll()!!.message["id"])
        assertNull(q.poll())
    }

    @Test
    fun minGapIsKept() {
        val q = SendQueue(AirtimeBudget(), { now }, minGapMs = 2_000)
        q.enqueue(found(1)); q.enqueue(found(2))
        assertEquals(1, q.poll()!!.message["id"])
        assertNull(q.poll())
        assertEquals(2_000L, q.waitMs())
        now += 2_000
        assertEquals(2, q.poll()!!.message["id"])
    }
}

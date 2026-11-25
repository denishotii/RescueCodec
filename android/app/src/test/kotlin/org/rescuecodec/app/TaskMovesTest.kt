// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import org.rescuecodec.app.data.TaskEntity
import org.rescuecodec.app.ui.TasksViewModel
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which buttons a task card shows: Accept, Decline, Done. */
class TaskMovesTest {
    private fun task(state: String, ownStatus: String? = null, local: Boolean = false, type: String = "found") = TaskEntity(
        key = "0badf00d-21", node = 0x0badf00dL, msgId = 21, type = type, json = null, local = local,
        state = state, meshDelivered = false, createdMs = 0, updatedMs = 0, ownStatus = ownStatus,
    )

    @Test
    fun anOpenTaskCanBeAcceptedOrDeclined() {
        assertEquals(setOf("accepted", "declined"), TasksViewModel.movesFor(task("open")))
    }

    @Test
    fun aTaskAnotherTeamFinishedShowsNoButtons() {
        // Another team sent Done. We never answered, but Accept and Decline must not show.
        assertEquals(emptySet(), TasksViewModel.movesFor(task("done")))
        // We had accepted it and another team finished it: no Done or Decline either.
        assertEquals(emptySet(), TasksViewModel.movesFor(task("done", ownStatus = "accepted")))
        assertEquals(emptySet(), TasksViewModel.movesFor(task("done", ownStatus = "declined")))
        assertEquals(emptySet(), TasksViewModel.movesFor(task("done", ownStatus = "done")))
    }

    @Test
    fun ourOwnAnswerDecidesTheMovesWhileTheTaskIsNotDone() {
        // Another team accepted. We may still help.
        assertEquals(setOf("accepted", "declined"), TasksViewModel.movesFor(task("accepted")))
        assertEquals(setOf("done", "declined"), TasksViewModel.movesFor(task("accepted", ownStatus = "accepted")))
        assertEquals(setOf("accepted"), TasksViewModel.movesFor(task("declined", ownStatus = "declined")))
    }

    @Test
    fun ourOwnTasksAndAnswersWithoutAReportShowNoButtons() {
        assertEquals(emptySet(), TasksViewModel.movesFor(task("open", local = true)))
        assertEquals(emptySet(), TasksViewModel.movesFor(task("open", type = "unknown")))
    }
}

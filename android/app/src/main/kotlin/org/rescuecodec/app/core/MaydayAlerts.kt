// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.core

import org.rescuecodec.engine.Outcome
import org.rescuecodec.engine.TaskState

/**
 * Tells the person about a Mayday from another radio. The app posts a loud notification
 * ([org.rescuecodec.app.service.MaydayNotifier]); tests record the calls. [Messenger] calls it.
 */
interface MaydayAlerts {
    /**
     * A new Mayday, task [key] ("0badf00d-7"), from node [from]. [reason] is the reason code ("injured") or null.
     * [channel] is the name of the channel it came on, or null.
     */
    fun show(key: String, from: Long, reason: String?, channel: String? = null)

    /** The Mayday [key] is done: take its notification away. */
    fun clear(key: String)

    companion object {
        /** Does nothing. The default when there is no screen (tests). */
        val NONE: MaydayAlerts = object : MaydayAlerts {
            override fun show(key: String, from: Long, reason: String?, channel: String?) = Unit
            override fun clear(key: String) = Unit
        }

        /**
         * Should this packet alert? Only a Mayday from another radio, seen for the first time ([Outcome.NEW], so not a
         * retransmission or duplicate), not already done, and only once per task key.
         */
        fun shouldAlert(type: String, from: Long, self: Long, outcome: Outcome, state: TaskState?, alreadyAlerted: Boolean): Boolean =
            type == "mayday" && from != self && outcome == Outcome.NEW && state != TaskState.DONE && !alreadyAlerted

        /** A done Mayday loses its notification. */
        fun shouldClear(type: String, state: TaskState): Boolean = type == "mayday" && state == TaskState.DONE

        /** Reason code of a Mayday message ("injured"), or null. */
        fun reasonOf(message: Map<String, Any?>): String? = (message["mayday"] as? Map<*, *>)?.get("reason") as? String

        /** "!0badf00d", the way Meshtastic shows a node. */
        fun nodeLabel(node: Long): String = "!%08x".format(node)
    }
}

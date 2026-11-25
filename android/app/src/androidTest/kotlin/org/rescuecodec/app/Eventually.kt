// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Polls [check] until it is true or [timeoutMs] passes. */
suspend fun eventually(timeoutMs: Long = 10_000, what: String = "condition", check: suspend () -> Boolean) {
    try {
        withTimeout(timeoutMs) {
            while (!check()) delay(100)
        }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("Timed out after $timeoutMs ms waiting for $what")
    }
}

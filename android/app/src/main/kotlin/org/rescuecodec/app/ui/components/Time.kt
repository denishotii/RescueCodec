// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import org.rescuecodec.app.R

/** The current time, refreshed every 20 seconds, so "2 min ago" stays true. */
@Composable
fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(20_000)
        value = System.currentTimeMillis()
    }
}

/** "just now", "5 min ago", "2 h ago", "3 d ago" in the app language. */
@Composable
fun agoText(atMs: Long, nowMs: Long): String {
    val s = ((nowMs - atMs) / 1000).coerceAtLeast(0)
    return when {
        s < 45 -> stringResource(R.string.ago_now)
        s < 3600 -> stringResource(R.string.ago_min, ((s + 30) / 60).toInt())
        s < 86_400 -> stringResource(R.string.ago_hours, (s / 3600).toInt())
        else -> stringResource(R.string.ago_days, (s / 86_400).toInt())
    }
}

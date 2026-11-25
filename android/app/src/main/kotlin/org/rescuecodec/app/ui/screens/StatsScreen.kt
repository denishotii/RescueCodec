// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.rescuecodec.app.R
import org.rescuecodec.app.ui.Stats
import org.rescuecodec.app.ui.StatsViewModel
import org.rescuecodec.app.ui.components.Hairline
import org.rescuecodec.app.ui.components.MeterBar
import org.rescuecodec.app.ui.components.SectionHeader
import org.rescuecodec.app.ui.components.SourceTag
import org.rescuecodec.app.ui.components.agoText
import org.rescuecodec.app.ui.components.rememberNow
import org.rescuecodec.app.ui.theme.BarlowCondensed
import org.rescuecodec.app.ui.theme.Rc

/**
 * Stats as a ledger: label on the left, number on the right, and under each one the word
 * "measured" or "estimated". Measured means counted on this phone or read from the radio.
 * Estimated means calculated from the LoRa airtime formula.
 */
@Composable
fun StatsScreen(vm: StatsViewModel, lang: String) {
    val s by vm.stats.collectAsStateWithLifecycle()
    val now by rememberNow()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("stats_screen"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        SectionHeader(stringResource(R.string.stats_savings))
        Row3(
            stringResource(R.string.stat_bytes_saved), signed(s.bytesSaved) + " B", measured = true,
            note = if (s.reports > 0) stringResource(R.string.stat_bytes_saved_note, s.reportTextBytes, s.reportCodedBytes, s.reports) else stringResource(R.string.stat_bytes_saved_none),
            tag = "stat_bytes_saved",
        )
        Row3(
            stringResource(R.string.stat_airtime_saved), "%.1f s".format(s.airtimeSavedMs / 1000.0), measured = false,
            note = stringResource(R.string.stat_airtime_saved_note), tag = "stat_airtime_saved",
        )

        SectionHeader(stringResource(R.string.stats_delivery))
        Row3(
            stringResource(R.string.stat_delivered), "${s.delivered} / ${s.sent}", measured = true,
            note = stringResource(R.string.stat_delivered_note, s.notConfirmed, s.refusedAirtime, s.failed), tag = "stat_delivered",
        )
        Row3(
            stringResource(R.string.stat_received), s.received.toString(), measured = true,
            note = stringResource(R.string.stat_received_note, s.duplicates, s.invalid), tag = "stat_received",
        )

        SectionHeader(stringResource(R.string.stats_signal))
        val rssi = s.lastRssi
        Row3(
            stringResource(R.string.stat_last_rssi), if (rssi != null) "$rssi dBm" else "-", measured = true,
            note = if (rssi != null && s.lastSignalAtMs != null) stringResource(R.string.stat_signal_when, agoText(s.lastSignalAtMs!!, now)) else stringResource(R.string.stat_signal_none),
            tag = "stat_rssi",
        )
        Row3(
            stringResource(R.string.stat_last_snr), if (rssi != null && s.lastSnr != null) "%.1f dB".format(s.lastSnr) else "-", measured = true,
            note = stringResource(R.string.stat_snr_note), tag = "stat_snr",
        )

        SectionHeader(stringResource(R.string.stats_airtime))
        val a = s.airtime
        Row3(
            stringResource(R.string.stat_airtime_hour), "%.1f / %.0f s".format(a.usedMs / 1000.0, a.limitMs / 1000.0), measured = false,
            note = stringResource(R.string.stat_airtime_hour_note), tag = "stat_airtime_hour",
        ) {
            val frac = (a.percent / 100.0).toFloat()
            MeterBar(frac, if (frac > 0.8f) Rc.colors.mayday else if (frac > 0.5f) Rc.colors.openSolid else Rc.colors.doneSolid)
        }
        Row3(
            stringResource(R.string.stat_bytes_traffic), "${s.bytesSent} / ${s.bytesReceived} B", measured = true,
            note = stringResource(R.string.stat_bytes_traffic_note), tag = "stat_traffic",
        )
        Spacer(Modifier.height(16.dp))
    }
}

private fun signed(v: Long) = if (v > 0) "+$v" else v.toString()

@Composable
private fun Row3(label: String, value: String, measured: Boolean, note: String, tag: String, extra: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag(tag), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                SourceTag(measured)
            }
            Text(value, style = MaterialTheme.typography.displaySmall.copy(fontFamily = BarlowCondensed), color = MaterialTheme.colorScheme.onSurface)
        }
        extra?.invoke()
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Hairline(Modifier.padding(top = 6.dp))
    }
}

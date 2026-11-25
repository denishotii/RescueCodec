// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.screens

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.location.LocationManagerCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.rescuecodec.app.R
import org.rescuecodec.app.core.ChannelChoice
import org.rescuecodec.app.radio.ConnectPhase
import org.rescuecodec.app.radio.DiscoveredRadio
import org.rescuecodec.app.radio.LinkState
import org.rescuecodec.app.radio.RadioTarget
import org.rescuecodec.app.ui.ConnectionViewModel
import org.rescuecodec.app.ui.Permissions
import org.rescuecodec.app.ui.components.BigButton
import org.rescuecodec.app.ui.components.RcCard
import org.rescuecodec.app.ui.components.RcChip
import org.rescuecodec.app.ui.components.RcIcon
import org.rescuecodec.app.ui.components.Hairline
import org.rescuecodec.app.ui.components.MeterBar
import org.rescuecodec.app.ui.components.PlainTag
import org.rescuecodec.app.ui.components.SourceTag
import org.rescuecodec.app.ui.components.Tag
import androidx.compose.ui.unit.sp
import org.rescuecodec.app.ui.components.StatusDot
import org.rescuecodec.app.ui.theme.Rc

/**
 * Radio, overnight cards: the connection (radio, link, signal, region and preset, Disconnect / Other radio), the channels
 * on this radio (Team / Private / Public, Join by QR, Paste link, which channel carries reports), airtime and savings
 * (measured and estimated numbers, the airtime bar), and connecting a radio: Bluetooth scan and pairing, Wi-Fi radio
 * or simulator, permissions and the Location and battery hints.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RadioScreen(
    vm: ConnectionViewModel,
    statsVm: org.rescuecodec.app.ui.StatsViewModel,
    chats: List<org.rescuecodec.app.core.ChatSummary>,
    link: LinkState,
    snackbar: SnackbarHostState,
    lang: String,
) {
    val context = LocalContext.current
    val graph = remember { org.rescuecodec.app.AppGraph.from(context) }
    val channel by vm.channel.collectAsStateWithLifecycle()
    val options by vm.channelOptions.collectAsStateWithLifecycle()
    val picked by vm.pickedChannel.collectAsStateWithLifecycle()
    val join by vm.join.collectAsStateWithLifecycle()
    val radioChannels by graph.messenger.radioChannels.collectAsStateWithLifecycle()
    val team by graph.teamName.collectAsStateWithLifecycle()
    val showPublic by graph.ui.showPublic.collectAsStateWithLifecycle()
    val stats by statsVm.stats.collectAsStateWithLifecycle()
    var missing by remember { mutableStateOf(Permissions.missing(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { missing = Permissions.missing(context) }
    val last = remember(link) { vm.lastRadio() }
    var host by remember { mutableStateOf((last as? RadioTarget.Tcp)?.host ?: "10.0.2.2") }
    var port by remember { mutableStateOf(((last as? RadioTarget.Tcp)?.port ?: 4403).toString()) }
    var scanner by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var other by remember { mutableStateOf(false) }
    val c = Rc.colors
    val connected = link is LinkState.Connected

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).testTag("connect_screen"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.height(2.dp))

        // 1. The connection.
        OnCard {
            val target = (link as? LinkState.Connected)?.target ?: (link as? LinkState.Connecting)?.target ?: last
            val kind = when (target) {
                is RadioTarget.Ble -> stringResource(R.string.link_type_bluetooth)
                is RadioTarget.Tcp -> stringResource(R.string.link_type_network)
                null -> null
            }
            val name = (link as? LinkState.Connected)?.let { it.nodeName ?: it.target.label }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(
                    when (link) {
                        is LinkState.Connected -> c.doneSolid
                        is LinkState.Error -> c.mayday
                        LinkState.Disconnected -> MaterialTheme.colorScheme.outline
                        else -> c.openSolid
                    },
                    size = 10.dp,
                )
                CardTitle(if (name != null) listOfNotNull(name, kind).joinToString(" · ") else statusTitle(link), Modifier.testTag("link_status"))
            }
            if (!connected) Text(statusDetail(link), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (connected) {
                val rssi = stats.lastRssi
                val word = when {
                    rssi == null -> null
                    rssi >= -90 -> stringResource(R.string.signal_good)
                    rssi >= -110 -> stringResource(R.string.signal_fair)
                    else -> stringResource(R.string.signal_weak)
                }
                if (word != null) Kv(stringResource(R.string.radio_signal), "$word · $rssi dBm")
                Kv(stringResource(R.string.radio_region_preset), "${graph.settings.region.name} · ${graph.settings.modemPreset.name}")
                Text(channelText(channel), style = MaterialTheme.typography.bodyMedium, color = if (channel is ChannelChoice.Ready) MaterialTheme.colorScheme.onSurfaceVariant else c.open, modifier = Modifier.testTag("channel_status"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                if (link is LinkState.Disconnected || link is LinkState.Error) {
                    last?.let { BigButton(stringResource(R.string.connect_last, it.label), vm::connectLast, Modifier.weight(1f), icon = R.drawable.ic_refresh, height = 48.dp) }
                }
                if (link !is LinkState.Disconnected && link !is LinkState.Scanning) {
                    BigButton(stringResource(R.string.action_disconnect), vm::disconnect, Modifier.weight(1f).testTag("disconnect"), outlined = true, container = MaterialTheme.colorScheme.onSurface, height = 48.dp)
                }
                if (connected) BigButton(stringResource(R.string.radio_other), { other = !other }, Modifier.weight(1f).testTag("other_radio"), outlined = true, container = MaterialTheme.colorScheme.onSurface, height = 48.dp)
            }
        }

        if (missing.isNotEmpty()) {
            RcCard(container = c.openSoft, outline = c.open) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.titleMedium, color = c.open)
                    missing.forEach { Text(stringResource(it.reasonRes), style = MaterialTheme.typography.bodyMedium) }
                    BigButton(stringResource(R.string.action_allow), { launcher.launch(missing.map { it.permission }.toTypedArray()) }, Modifier.fillMaxWidth())
                }
            }
        }

        // 2. Connect a radio: open while there is none, behind "Other radio" while connected.
        if (!connected || other) ConnectSection(vm, link, host, port, { host = it }, { port = it })

        // 3. The channels on this radio.
        OnCard(Modifier.testTag("radio_channels")) {
            CardTitle(stringResource(R.string.radio_channels_title))
            val list = radioChannels ?: chats.mapNotNull { s -> (s.id as? org.rescuecodec.app.core.ChatId.Channel)?.let { org.rescuecodec.app.core.RadioChannel(-1, it.name, s.isPublic) } }
            if (list.isEmpty()) Text(stringResource(R.string.radio_channels_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            list.distinctBy { it.name }.forEachIndexed { i, ch ->
                if (i > 0) Hairline()
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ch.name.ifEmpty { stringResource(R.string.channel_no_name) }, style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp), color = if (ch.isPublic) c.muted else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    when {
                        ch.name == team -> Tag(stringResource(R.string.ch_team), MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.surface)
                        ch.isPublic -> Tag(stringResource(if (showPublic) R.string.ch_public else R.string.ch_public_hidden), androidx.compose.ui.graphics.Color.Transparent, MaterialTheme.colorScheme.onSurface, border = MaterialTheme.colorScheme.outlineVariant)
                        else -> PlainTag(stringResource(R.string.ch_private))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                BigButton(stringResource(R.string.join_by_qr), { scanner = true }, Modifier.weight(1f).testTag("scan_qr"), icon = R.drawable.ic_qr_code_scanner, outlined = true, container = MaterialTheme.colorScheme.onSurface, height = 48.dp)
                BigButton(stringResource(R.string.paste_link), { pasting = !pasting }, Modifier.weight(1f).testTag("paste_link"), outlined = true, container = MaterialTheme.colorScheme.onSurface, height = 48.dp)
            }
            if (pasting) OutlinedTextField(
                pasted, { pasted = it; if (it.contains("meshtastic.org/e/")) vm.readChannelText(it) },
                label = { Text(stringResource(R.string.channel_paste)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("channel_link_field"), textStyle = MaterialTheme.typography.bodyLarge, colors = fieldColors(),
            )
            JoinPanel(vm, join, connected) { pasted = ""; pasting = false }
            // Which private channel carries the reports: automatic or one by hand.
            if (options.isNotEmpty()) {
                Text(stringResource(R.string.radio_team_channel), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("channel_picker")) {
                    RcChip(stringResource(R.string.channel_auto), onClick = { vm.pickChannel(-1) }, selected = picked < 0)
                    options.filter { it.isPrivate }.forEach { o ->
                        RcChip("${o.index}  ${o.name.ifEmpty { stringResource(R.string.channel_no_name) }}", onClick = { vm.pickChannel(o.index) }, selected = picked == o.index)
                    }
                }
            }
        }

        // 4. Airtime and savings.
        AirtimeCard(stats, lang)
        Spacer(Modifier.height(12.dp))
    }

    if (scanner) {
        QrScannerDialog(onResult = { text -> scanner = false; vm.readChannelText(text) }, onClose = { scanner = false })
    }
}

/** An overnight card: card colour, one hairline, 10 dp corners. */
@Composable
private fun OnCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    RcCard(modifier = modifier, shape = MaterialTheme.shapes.medium, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

/** A card title: condensed capitals. */
@Composable
private fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, letterSpacing = 0.6.sp), modifier = modifier.padding(bottom = 2.dp))
}

/** Label on the left, value on the right. */
@Composable
private fun Kv(label: String, value: String, modifier: Modifier = Modifier, tag: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontFeatureSettings = "tnum"))
        tag?.invoke()
    }
}

/**
 * Airtime and savings, from the old Stats screen: messages sent, bytes on air (measured), what they would be as text
 * (estimated), the airtime of the last hour against the legal limit, and all other numbers behind "All numbers".
 */
@Composable
private fun AirtimeCard(s: org.rescuecodec.app.ui.Stats, lang: String) {
    val c = Rc.colors
    var all by remember { mutableStateOf(false) }
    OnCard(Modifier.testTag("stats_screen")) {
        CardTitle(stringResource(R.string.nav_stats))
        Kv(stringResource(R.string.air_sent), stringResource(R.string.air_messages, s.sent.toInt()), Modifier.testTag("stat_sent"))
        Kv(stringResource(R.string.air_on_air), "${group(s.bytesSent)} B", Modifier.testTag("stat_on_air")) { SourceTag(measured = true) }
        // The reports as their text, the rest as it went: what the same traffic would have been without the codec.
        val asText = s.bytesSent - s.reportCodedBytes + s.reportTextBytes
        val factor = if (s.bytesSent > 0) asText.toDouble() / s.bytesSent else 0.0
        Kv(
            stringResource(R.string.air_as_text),
            "${group(asText)} B" + if (factor >= 1.05) " · " + stringResource(R.string.air_more, "%.1f".format(java.util.Locale.ROOT, factor)) else "",
            Modifier.testTag("stat_as_text"),
        ) { SourceTag(measured = false) }
        val a = s.airtime
        val usedPct = a.usedMs / 3_600_000.0 * 100
        val limitPct = a.limitMs / 3_600_000.0 * 100
        Kv(stringResource(R.string.air_used), stringResource(R.string.air_used_value, "%.1f".format(java.util.Locale.ROOT, usedPct), "%.0f".format(java.util.Locale.ROOT, limitPct)), Modifier.padding(top = 4.dp).testTag("stat_airtime_hour"))
        val frac = (a.percent / 100.0).toFloat()
        MeterBar(frac, if (frac > 0.8f) c.mayday else if (frac > 0.5f) c.openSolid else c.doneSolid)
        Surface(onClick = { all = !all }, color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("stats_all")) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.air_all_numbers), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                RcIcon(if (all) R.drawable.ic_expand_less else R.drawable.ic_expand_more, null)
            }
        }
        if (all) StatsLedger(s)
    }
}

private fun group(v: Long): String = "%,d".format(java.util.Locale.ROOT, v).replace(',', ' ')

@Composable
private fun ConnectSection(vm: ConnectionViewModel, link: LinkState, host: String, port: String, onHost: (String) -> Unit, onPort: (String) -> Unit) {
    val context = LocalContext.current
    val c = Rc.colors
    OnCard(Modifier.testTag("connect_section")) {
        CardTitle(stringResource(R.string.radio_connect_title))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Bluetooth
            Text(stringResource(R.string.connect_bluetooth), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.close_other_app), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("close_other_app"))
            val scanning = link as? LinkState.Scanning
            // Android 11 and older: a Bluetooth scan finds nothing while the Location switch is off, without any error.
            val locationOff = Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && !locationSwitchOn(context)
            if (locationOff) {
                Text(stringResource(R.string.location_off_hint), style = MaterialTheme.typography.bodyMedium, color = c.open, modifier = Modifier.testTag("location_off_hint"))
                BigButton(
                    stringResource(R.string.location_open_settings),
                    { runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                    Modifier.fillMaxWidth(), outlined = true,
                )
            }
            BigButton(
                stringResource(if (scanning != null) R.string.action_scanning else R.string.action_scan),
                { if (scanning != null) vm.stopScan() else vm.scan() },
                Modifier.fillMaxWidth().testTag("scan_button"),
                icon = R.drawable.ic_bluetooth_searching,
                outlined = scanning != null,
            )
            if (scanning != null) {
                if (scanning.found.isEmpty()) Text(stringResource(R.string.scan_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                scanning.found.sortedByDescending { it.rssi }.forEach { r -> RadioRow(r) { vm.connect(RadioTarget.Ble(r.address, r.name)) } }
            } else {
                Text(stringResource(R.string.scan_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Hairline()
            // Simulator / network
            Text(stringResource(R.string.connect_network), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.connect_network_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RcChip(stringResource(R.string.connect_simulator), onClick = { onHost("10.0.2.2"); onPort("4403") }, selected = host == "10.0.2.2", modifier = Modifier.testTag("chip_simulator"))
                RcChip(stringResource(R.string.connect_wifi_radio), onClick = { if (host == "10.0.2.2") onHost("192.168.") }, selected = host != "10.0.2.2")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    host, onHost, label = { Text(stringResource(R.string.field_host)) }, singleLine = true,
                    modifier = Modifier.weight(2f).testTag("host_field"),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
                    textStyle = MaterialTheme.typography.bodyLarge, colors = fieldColors(),
                )
                OutlinedTextField(
                    port, { onPort(it.filter(Char::isDigit).take(5)) }, label = { Text(stringResource(R.string.field_port)) }, singleLine = true,
                    modifier = Modifier.weight(1f).testTag("port_field"),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.bodyLarge, colors = fieldColors(),
                )
            }
            BigButton(
                stringResource(R.string.action_connect_tcp), { vm.connect(RadioTarget.Tcp(host.trim(), port.toIntOrNull() ?: 4403)) },
                Modifier.fillMaxWidth().testTag("connect_tcp"), icon = R.drawable.ic_wifi,
            )
        }
    }
}

/** True when the system Location switch is on (or when we cannot tell). */
private fun locationSwitchOn(context: Context): Boolean = runCatching {
    LocationManagerCompat.isLocationEnabled(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
}.getOrDefault(true)

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
)

@Composable
private fun JoinPanel(vm: ConnectionViewModel, join: ConnectionViewModel.JoinState, connected: Boolean, onClear: () -> Unit) {
    val c = Rc.colors
    when (join) {
        ConnectionViewModel.JoinState.Idle -> Unit
        ConnectionViewModel.JoinState.NotALink -> Text(stringResource(R.string.join_not_link), color = c.open, style = MaterialTheme.typography.bodyMedium)
        ConnectionViewModel.JoinState.Working -> Text(stringResource(R.string.join_working), style = MaterialTheme.typography.bodyMedium)
        ConnectionViewModel.JoinState.Done -> Text(stringResource(R.string.join_done), color = c.done, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("join_done"))
        is ConnectionViewModel.JoinState.Failed -> Text(stringResource(R.string.join_failed, join.reason), color = c.maydayText, style = MaterialTheme.typography.bodyMedium)
        is ConnectionViewModel.JoinState.Preview -> {
            val link = join.link
            val plan = join.plan
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("join_preview")) {
                Text(stringResource(R.string.join_found, link.primaryName.ifEmpty { stringResource(R.string.channel_no_name) }, link.channels.size), style = MaterialTheme.typography.titleMedium)
                if (!connected || plan == null) {
                    Text(stringResource(R.string.join_need_radio), color = c.open, style = MaterialTheme.typography.bodyMedium)
                } else {
                    // Say exactly what will happen before anything is written: added, updated, kept, skipped, region.
                    Text(stringResource(R.string.join_plan_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("join_plan")) {
                        plan.add.forEach { (slot, s) -> PlanLine(stringResource(R.string.join_plan_add, s.name, slot), c.done) }
                        plan.update.forEach { (slot, s) -> PlanLine(stringResource(R.string.join_plan_update, s.name, slot), c.open) }
                        plan.already.forEach { PlanLine(stringResource(R.string.join_plan_have, it), null) }
                        plan.keptPrimary.forEach { PlanLine(stringResource(R.string.join_plan_keep_primary, it), null) }
                        plan.skippedPublic.forEach { PlanLine(stringResource(R.string.join_plan_skip_public, it), null) }
                        plan.noRoom.forEach { PlanLine(stringResource(R.string.join_plan_no_room, it), c.maydayText) }
                        if (plan.add.isEmpty() && plan.update.isEmpty()) PlanLine(stringResource(R.string.join_plan_nothing), null)
                        PlanLine(plan.lora?.let { stringResource(R.string.join_plan_region_set, it.region.name) } ?: stringResource(R.string.join_plan_region_keep), if (plan.lora != null) c.open else null)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton(stringResource(R.string.join_apply), { vm.applyJoin() }, Modifier.weight(1f).testTag("join_apply"), enabled = connected && plan?.writes == true)
                    BigButton(stringResource(R.string.action_cancel), { vm.clearJoin(); onClear() }, Modifier.weight(0.7f), outlined = true)
                }
            }
        }
    }
}

/** One line of the join plan, with a dot in the colour of its meaning (green added, amber changed, red not possible). */
@Composable
private fun PlanLine(text: String, color: androidx.compose.ui.graphics.Color?) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusDot(color ?: MaterialTheme.colorScheme.outline, Modifier.padding(top = 7.dp), size = 8.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RadioRow(r: DiscoveredRadio, onClick: () -> Unit) {
    val bars = when { r.rssi >= -60 -> 4; r.rssi >= -72 -> 3; r.rssi >= -84 -> 2; else -> 1 }
    Surface(
        onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("radio_${r.address}"),
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RcIcon(R.drawable.ic_bluetooth, null)
            Column(Modifier.weight(1f)) {
                Text(r.name ?: stringResource(R.string.radio_unnamed), style = MaterialTheme.typography.titleMedium)
                Text("${r.address}   ${r.rssi} dBm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                for (i in 1..4) Box(
                    Modifier.width(6.dp).height((6 + i * 5).dp).background(if (i <= bars) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant),
                )
            }
            Text(stringResource(R.string.action_connect), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun statusTitle(s: LinkState): String = when (s) {
    LinkState.Disconnected -> stringResource(R.string.link_title_offline)
    is LinkState.Scanning -> stringResource(R.string.link_title_scanning)
    is LinkState.Connecting -> stringResource(R.string.link_title_connecting, s.target.label)
    is LinkState.Connected -> stringResource(R.string.link_title_connected, s.nodeName ?: s.target.label)
    is LinkState.Error -> stringResource(R.string.link_title_error)
}

@Composable
private fun statusDetail(s: LinkState): String = when (s) {
    LinkState.Disconnected -> stringResource(R.string.link_detail_offline)
    is LinkState.Scanning -> stringResource(R.string.link_detail_scanning, s.found.size)
    is LinkState.Connecting -> when (s.phase) {
        ConnectPhase.OPENING -> stringResource(R.string.link_detail_opening, s.attempt)
        ConnectPhase.PAIRING -> stringResource(R.string.link_detail_pairing)
        ConnectPhase.LOADING_SETTINGS -> stringResource(R.string.link_detail_settings, ((s.progress ?: 0f) * 100).toInt())
        ConnectPhase.LOADING_NODES -> stringResource(R.string.link_detail_nodes, ((s.progress ?: 0f) * 100).toInt())
    }
    is LinkState.Connected -> stringResource(R.string.link_detail_connected, "!%08x".format(s.nodeNum), s.target.label)
    is LinkState.Error -> s.message + if (s.retryAtMs != null) " " + stringResource(R.string.link_detail_retry, s.attempt) else ""
}

@Composable
private fun channelText(c: ChannelChoice): String = when (c) {
    is ChannelChoice.Ready -> stringResource(R.string.channel_ready, c.index, c.name)
    is ChannelChoice.Waiting -> stringResource(R.string.channel_waiting)
    is ChannelChoice.Problem -> c.detail
}

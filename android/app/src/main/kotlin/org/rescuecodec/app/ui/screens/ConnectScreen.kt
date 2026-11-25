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
import org.rescuecodec.app.ui.components.SectionHeader
import org.rescuecodec.app.ui.components.StatusDot
import org.rescuecodec.app.ui.theme.Rc

@OptIn(ExperimentalLayoutApi::class)
/** Radio: status, Bluetooth scan list, simulator or network radio, channel and joining a channel by QR. */
@Composable
fun ConnectScreen(vm: ConnectionViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val link by vm.state.collectAsStateWithLifecycle()
    val channel by vm.channel.collectAsStateWithLifecycle()
    val options by vm.channelOptions.collectAsStateWithLifecycle()
    val picked by vm.pickedChannel.collectAsStateWithLifecycle()
    val join by vm.join.collectAsStateWithLifecycle()
    var missing by remember { mutableStateOf(Permissions.missing(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { missing = Permissions.missing(context) }
    val last = remember(link) { vm.lastRadio() }
    var host by remember { mutableStateOf((last as? RadioTarget.Tcp)?.host ?: "10.0.2.2") }
    var port by remember { mutableStateOf(((last as? RadioTarget.Tcp)?.port ?: 4403).toString()) }
    var scanner by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    val c = Rc.colors

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("connect_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        // Status
        val connected = link is LinkState.Connected
        RcCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusDot(
                        when (link) {
                            is LinkState.Connected -> c.doneSolid
                            is LinkState.Error -> c.mayday
                            LinkState.Disconnected -> MaterialTheme.colorScheme.outline
                            else -> c.openSolid
                        },
                    )
                    Text(statusTitle(link), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("link_status"))
                }
                Text(statusDetail(link), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (connected) {
                    Text(channelText(channel), style = MaterialTheme.typography.bodyMedium, color = if (channel is ChannelChoice.Ready) MaterialTheme.colorScheme.onSurface else c.open, modifier = Modifier.testTag("channel_status"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (link is LinkState.Disconnected || link is LinkState.Error) {
                        last?.let { BigButton(stringResource(R.string.connect_last, it.label), vm::connectLast, Modifier.weight(1f), icon = R.drawable.ic_refresh) }
                    }
                    if (link !is LinkState.Disconnected && link !is LinkState.Scanning) {
                        BigButton(stringResource(R.string.action_disconnect), vm::disconnect, Modifier.weight(1f).testTag("disconnect"), outlined = true)
                    }
                }
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

        // Bluetooth
        SectionHeader(stringResource(R.string.connect_bluetooth))
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

        // Simulator / network
        SectionHeader(stringResource(R.string.connect_network))
        RcCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.connect_network_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RcChip(stringResource(R.string.connect_simulator), onClick = { host = "10.0.2.2"; port = "4403" }, selected = host == "10.0.2.2", modifier = Modifier.testTag("chip_simulator"))
                    RcChip(stringResource(R.string.connect_wifi_radio), onClick = { if (host == "10.0.2.2") host = "192.168." }, selected = host != "10.0.2.2")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        host, { host = it }, label = { Text(stringResource(R.string.field_host)) }, singleLine = true,
                        modifier = Modifier.weight(2f).testTag("host_field"),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
                        textStyle = MaterialTheme.typography.bodyLarge, colors = fieldColors(),
                    )
                    OutlinedTextField(
                        port, { port = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.field_port)) }, singleLine = true,
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

        // Channel
        SectionHeader(stringResource(R.string.connect_channel))
        RcCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.channel_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (options.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RcChip(stringResource(R.string.channel_auto), onClick = { vm.pickChannel(-1) }, selected = picked < 0)
                        options.filter { it.isPrivate }.forEach { o ->
                            RcChip("${o.index}  ${o.name.ifEmpty { stringResource(R.string.channel_no_name) }}", onClick = { vm.pickChannel(o.index) }, selected = picked == o.index)
                        }
                    }
                }
                BigButton(stringResource(R.string.channel_scan_qr), { scanner = true }, Modifier.fillMaxWidth().testTag("scan_qr"), icon = R.drawable.ic_qr_code_scanner, outlined = true)
                OutlinedTextField(
                    pasted, { pasted = it; if (it.contains("meshtastic.org/e/")) vm.readChannelText(it) },
                    label = { Text(stringResource(R.string.channel_paste)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("channel_link_field"), textStyle = MaterialTheme.typography.bodyLarge, colors = fieldColors(),
                )
                JoinPanel(vm, join, connected) { pasted = "" }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (scanner) {
        QrScannerDialog(onResult = { text -> scanner = false; vm.readChannelText(text) }, onClose = { scanner = false })
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("join_preview")) {
                Text(stringResource(R.string.join_found, link.primaryName.ifEmpty { stringResource(R.string.channel_no_name) }, link.channels.size), style = MaterialTheme.typography.titleMedium)
                if (!link.allPrivate) Text(stringResource(R.string.join_public_warning), color = c.maydayText, style = MaterialTheme.typography.bodyMedium)
                if (!connected) Text(stringResource(R.string.join_need_radio), color = c.open, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton(stringResource(R.string.join_apply), { vm.applyJoin() }, Modifier.weight(1f).testTag("join_apply"), enabled = connected && link.allPrivate)
                    BigButton(stringResource(R.string.action_cancel), { vm.clearJoin(); onClear() }, Modifier.weight(0.7f), outlined = true)
                }
            }
        }
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

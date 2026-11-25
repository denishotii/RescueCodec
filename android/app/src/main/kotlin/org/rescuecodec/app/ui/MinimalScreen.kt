// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.rescuecodec.app.R
import org.rescuecodec.app.core.ChannelChoice
import org.rescuecodec.app.radio.ConnectPhase
import org.rescuecodec.app.radio.LinkState
import org.rescuecodec.app.radio.RadioTarget

/** One scrolling page: permissions, radio link, actions, outbox, stats. Plain on purpose. */
@Composable
fun MinimalScreen(
    connection: ConnectionViewModel,
    tasks: TasksViewModel,
    messages: MessagesViewModel,
    stats: StatsViewModel,
) {
    val context = LocalContext.current
    val link by connection.state.collectAsStateWithLifecycle()
    val taskList by tasks.tasks.collectAsStateWithLifecycle()
    val waiting by messages.waiting.collectAsStateWithLifecycle()
    val s by stats.stats.collectAsStateWithLifecycle()
    var missing by remember { mutableStateOf(Permissions.missing(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = Permissions.missing(context)
    }
    val channel by connection.channel.collectAsStateWithLifecycle()
    val channelOptions by connection.channelOptions.collectAsStateWithLifecycle()
    var picked by remember { mutableStateOf(connection.pickedChannel()) }
    var allowApi by remember { mutableStateOf(connection.allowIntentApi()) }
    var host by remember { mutableStateOf("10.0.2.2") }
    var port by remember { mutableStateOf("4403") }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("RescueCodec", style = MaterialTheme.typography.headlineMedium) }

                if (missing.isNotEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                missing.forEach { Text(stringResource(it.reasonRes)) }
                                Button(onClick = { launcher.launch(missing.map { it.permission }.toTypedArray()) }) {
                                    Text(stringResource(R.string.action_allow))
                                }
                            }
                        }
                    }
                }

                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.screen_radio), style = MaterialTheme.typography.titleMedium)
                            Text(describe(link))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = connection::scan) { Text(stringResource(R.string.action_scan)) }
                                connection.lastRadio()?.let {
                                    OutlinedButton(onClick = connection::connectLast) { Text(stringResource(R.string.action_connect_last)) }
                                }
                            }
                            if (link is LinkState.Scanning) {
                                (link as LinkState.Scanning).found.forEach { r ->
                                    OutlinedButton(onClick = { connection.connect(RadioTarget.Ble(r.address, r.name)) }) {
                                        Text("${r.name ?: r.address}  ${r.rssi} dBm")
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(host, { host = it }, label = { Text("Host") }, singleLine = true, modifier = Modifier.weight(2f))
                                OutlinedTextField(port, { port = it }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { connection.connect(RadioTarget.Tcp(host.trim(), port.toIntOrNull() ?: 4403)) }) {
                                    Text(stringResource(R.string.action_connect_tcp))
                                }
                                OutlinedButton(onClick = connection::disconnect) { Text(stringResource(R.string.action_disconnect)) }
                            }
                            Text("Report channel: " + channel.text(), style = MaterialTheme.typography.bodyMedium)
                            if (channel is ChannelChoice.Problem) {
                                Text("Messages wait until this is fixed. They are never sent on the public channel.", style = MaterialTheme.typography.bodySmall)
                            }
                            if (channelOptions.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { connection.pickChannel(-1); picked = -1 }) {
                                        Text(if (picked < 0) "Automatic (set)" else "Automatic")
                                    }
                                    channelOptions.filter { it.isPrivate }.forEach { o ->
                                        OutlinedButton(onClick = { connection.pickChannel(o.index); picked = o.index }) {
                                            Text("${o.index} ${o.name.ifEmpty { "(no name)" }}" + if (picked == o.index) " (set)" else "")
                                        }
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Switch(checked = allowApi, onCheckedChange = { allowApi = it; connection.setAllowIntentApi(it) })
                                Text("Allow other apps to send reports", style = MaterialTheme.typography.bodySmall)
                            }
                            if (Build.VERSION.SDK_INT >= 23) Text(stringResource(R.string.perm_battery), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                item { Button(onClick = messages::sendTest) { Text(stringResource(R.string.action_send_test)) } }

                item { Text(stringResource(R.string.screen_tasks), style = MaterialTheme.typography.titleMedium) }
                items(taskList, key = { it.key }) { t ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${t.type.uppercase()}  ${t.key}  [${t.state}]${if (t.local) "  (ours)" else ""}", style = MaterialTheme.typography.labelLarge)
                            Text(t.readable)
                            t.answers.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if ("accepted" in t.moves) OutlinedButton(onClick = { tasks.answer(t.key, "accepted") }) { Text(stringResource(R.string.action_accept)) }
                                if ("declined" in t.moves) OutlinedButton(onClick = { tasks.answer(t.key, "declined") }) { Text(stringResource(R.string.action_decline)) }
                                if ("done" in t.moves) OutlinedButton(onClick = { tasks.answer(t.key, "done") }) { Text(stringResource(R.string.action_done)) }
                            }
                        }
                    }
                }

                item { Text(stringResource(R.string.screen_outbox) + " (${waiting.size})", style = MaterialTheme.typography.titleMedium) }
                items(waiting, key = { it.id }) { m ->
                    Text("${m.type} ${m.state}${if (m.source != "app") " (from ${m.source})" else ""} ${m.detail.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                }

                item {
                    Column {
                        Text(stringResource(R.string.screen_stats), style = MaterialTheme.typography.titleMedium)
                        Text("Sent ${s.sent}, delivered ${s.delivered}, not confirmed ${s.notConfirmed}, refused ${s.refusedAirtime}, failed ${s.failed}")
                        Text("Received ${s.received}, duplicates ${s.duplicates}, invalid ${s.invalid}")
                        Text("Airtime last hour: %.1f s of %.0f s (estimated)".format(s.airtime.usedMs / 1000, s.airtime.limitMs / 1000))
                    }
                }
            }
        }
    }
}

private fun describe(s: LinkState): String = when (s) {
    LinkState.Disconnected -> "Not connected."
    is LinkState.Scanning -> "Looking for radios (${s.found.size} found)."
    is LinkState.Connecting -> when (s.phase) {
        ConnectPhase.OPENING -> "Connecting to ${s.target.label} (try ${s.attempt})."
        ConnectPhase.PAIRING -> "Pairing with ${s.target.label}. Confirm on the phone and enter the PIN shown on the radio (default 123456)."
        ConnectPhase.LOADING_SETTINGS -> "Reading radio settings ${((s.progress ?: 0f) * 100).toInt()}%."
        ConnectPhase.LOADING_NODES -> "Reading the node list ${((s.progress ?: 0f) * 100).toInt()}%."
    }
    is LinkState.Connected -> "Connected to ${s.nodeName ?: s.target.label} (!%08x).".format(s.nodeNum)
    is LinkState.Error -> s.message + if (s.retryAtMs != null) " Trying again soon (try ${s.attempt})." else ""
}

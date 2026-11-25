// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.rescuecodec.app.R
import org.rescuecodec.app.core.Labels
import org.rescuecodec.app.core.Readable
import org.rescuecodec.app.data.MessageEntity
import org.rescuecodec.app.data.MessageState
import org.rescuecodec.app.ui.MessagesViewModel
import org.rescuecodec.app.ui.components.BigButton
import org.rescuecodec.app.ui.components.RcCard
import org.rescuecodec.app.ui.components.SourceTag
import org.rescuecodec.app.ui.components.Tag
import org.rescuecodec.app.ui.components.agoText
import org.rescuecodec.app.ui.components.rememberNow
import org.rescuecodec.app.ui.theme.Rc
import org.rescuecodec.app.voice.SpeakState

/** Feed: every message as a card with the decoded text in the chosen language and a read-aloud button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedScreen(vm: MessagesViewModel, snackbar: SnackbarHostState, lang: String) {
    val messages by vm.recent.collectAsStateWithLifecycle()
    val speak by vm.speaker.state.collectAsStateWithLifecycle()
    val now by rememberNow()
    val unavailable = stringResource(R.string.speak_unavailable, if (lang == "de") stringResource(R.string.lang_german) else stringResource(R.string.lang_english))
    LaunchedEffect(speak) {
        if (speak is SpeakState.Unavailable) {
            snackbar.showSnackbar(unavailable)
            vm.speaker.clearUnavailable()
        }
    }
    if (messages.isEmpty()) {
        EmptyState(R.string.feed_empty_title, R.string.feed_empty_body, R.drawable.ic_rss_feed)
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("feed_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(messages, key = { it.id }) { m ->
            MessageCard(m, lang, now, speaking = (speak as? SpeakState.Speaking)?.id == "m${m.id}", vm = vm)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageCard(m: MessageEntity, lang: String, now: Long, speaking: Boolean, vm: MessagesViewModel) {
    val c = Rc.colors
    val readable = remember(m.json, lang) { Readable.of(m.json, lang) }
    val incoming = m.dir == "in"
    RcCard(
        container = if (m.type == "mayday") c.maydaySoft else MaterialTheme.colorScheme.surfaceContainerLow,
        outline = if (m.type == "mayday") c.mayday else MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.testTag("msg_${m.id}"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (m.type == "mayday") Tag(Labels.head(lang, "mayday"), c.mayday, c.onMayday, icon = R.drawable.ic_warning)
                    else Tag(if (m.type == "ack") stringResource(R.string.tag_answer) else Labels.head(lang, m.type), MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface)
                    Tag(
                        stringResource(if (incoming) R.string.feed_received else R.string.feed_sent_by_you),
                        MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!incoming) OutState(m.state)
                }
                Text(agoText(m.atMs, now), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                readable ?: stringResource(R.string.feed_unreadable),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 28.sp),
                color = if (readable == null) MaterialTheme.colorScheme.onSurfaceVariant else if (m.type == "mayday") c.maydayText else MaterialTheme.colorScheme.onSurface,
            )
            val meta = buildList {
                add(stringResource(R.string.feed_bytes, m.bytes.size))
                if (incoming) add(stringResource(R.string.feed_from, "!%08x".format(m.node)))
                if (m.hops != null && incoming) add(stringResource(R.string.feed_hops, m.hops))
            }
            Text(meta.joinToString("   "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (incoming && m.rssi != null && m.rssi != 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.feed_signal, m.rssi, m.snr ?: 0f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SourceTag(measured = true)
                }
            }
            val note = when {
                incoming -> null
                m.state == MessageState.NOT_CONFIRMED -> stringResource(R.string.ms_not_confirmed_note)
                m.state == MessageState.SENT || m.state == MessageState.DELIVERED || m.state == MessageState.CANCELLED -> null
                else -> m.detail // reasons written by the radio layer (channel problem, airtime limit)
            }
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (readable != null) BigButton(
                    if (speaking) stringResource(R.string.action_stop) else stringResource(R.string.action_read_aloud),
                    onClick = { if (speaking) vm.speaker.stop() else vm.speaker.speak("m${m.id}", readable, lang) },
                    modifier = Modifier.weight(1f).testTag("speak_${m.id}"),
                    icon = if (speaking) R.drawable.ic_stop else R.drawable.ic_volume_up,
                    outlined = true, height = 48.dp,
                    container = if (m.type == "mayday") c.maydayText else MaterialTheme.colorScheme.onSurface,
                )
                if (!incoming && m.state in MessageState.WAITING) BigButton(
                    stringResource(R.string.action_cancel), onClick = { vm.cancel(m.id) }, outlined = true, height = 48.dp,
                    container = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.7f),
                )
                if (!incoming && (m.state == MessageState.NOT_CONFIRMED || m.state == MessageState.FAILED || m.state == MessageState.EXPIRED || m.state == MessageState.CANCELLED)) BigButton(
                    stringResource(R.string.action_send_again), onClick = { vm.resend(m.id) }, height = 48.dp,
                    modifier = Modifier.weight(1.2f),
                )
            }
        }
    }
}

@Composable
private fun OutState(state: String) {
    val c = Rc.colors
    val (bg, fg, label) = when (state) {
        MessageState.DELIVERED -> Triple(c.doneSoft, c.done, stringResource(R.string.ms_delivered))
        MessageState.SENT -> Triple(c.acceptedSoft, c.accepted, stringResource(R.string.ms_sent))
        MessageState.NOT_CONFIRMED -> Triple(c.openSoft, c.open, stringResource(R.string.ms_not_confirmed))
        MessageState.FAILED -> Triple(c.maydaySoft, c.maydayText, stringResource(R.string.ms_failed))
        MessageState.REFUSED_AIRTIME -> Triple(c.openSoft, c.open, stringResource(R.string.ms_refused))
        MessageState.HELD -> Triple(c.openSoft, c.open, stringResource(R.string.ms_held))
        MessageState.QUEUED -> Triple(c.openSoft, c.open, stringResource(R.string.ms_queued))
        MessageState.EXPIRED -> Triple(c.declinedSoft, c.declined, stringResource(R.string.ms_expired))
        MessageState.CANCELLED -> Triple(c.declinedSoft, c.declined, stringResource(R.string.ms_cancelled))
        else -> Triple(c.declinedSoft, c.declined, state)
    }
    Tag(label, bg, fg)
}

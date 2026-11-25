// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.widthIn
import org.rescuecodec.app.data.MessageState
import org.rescuecodec.app.ui.TaskItem
import org.rescuecodec.app.ui.components.SectionHeader
import org.rescuecodec.app.ui.components.StateTag
import org.rescuecodec.app.ui.components.Tag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.rescuecodec.app.R
import org.rescuecodec.app.core.ChatId
import org.rescuecodec.app.core.ChatSummary
import org.rescuecodec.app.core.Labels
import org.rescuecodec.app.core.NodeName
import org.rescuecodec.app.core.TeamAlerts
import org.rescuecodec.app.data.MessageEntity
import org.rescuecodec.app.ui.ChatListViewModel
import org.rescuecodec.app.ui.components.Hairline
import org.rescuecodec.app.ui.components.RcIcon
import org.rescuecodec.app.ui.theme.Rc
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Chats: the chat list as overnight cards. "Channels on your radio": the team channel pinned on top (team icon), then
 * the other private channels (radio icon), and the public channel when it is switched on. "Direct": one row per
 * person, then "+ Direct message", which picks a radio from the node list. Each row: name, newest message, time,
 * unread count (red while a Mayday is open there) and an Open tag while the newest report waits for a team.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatListScreen(vm: ChatListViewModel, tasks: List<TaskItem>, lang: String, onOpen: (ChatId) -> Unit) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    val candidates by vm.directCandidates.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    val list = chats ?: return
    val byKey = remember(tasks) { tasks.associateBy { it.key } }
    val channels = list.filter { it.id is ChatId.Channel }
    val direct = list.filter { it.id is ChatId.Direct }
    LazyColumn(
        Modifier.fillMaxSize().testTag("chat_list_screen"),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "h_channels") { SectionHeader(stringResource(R.string.chats_channels), note = stringResource(R.string.chats_on_radio), plain = true) }
        items(channels, key = { it.id.key }) { s -> ChatListRow(s, s.last?.taskKey?.let { byKey[it] }, lang) { onOpen(s.id) } }
        item(key = "h_direct") { SectionHeader(stringResource(R.string.chats_direct), plain = true) }
        items(direct, key = { it.id.key }) { s -> ChatListRow(s, null, lang) { onOpen(s.id) } }
        item(key = "new_direct") {
            Surface(
                onClick = { picking = true },
                color = Color.Transparent,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp).testTag("new_direct"),
            ) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RcIcon(R.drawable.ic_add, null, size = 20.dp)
                    Text(stringResource(R.string.chat_direct_message), style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp))
                }
            }
        }
    }
    if (picking) NodePicker(candidates, onPick = { picking = false; onOpen(ChatId.Direct(it.num)) }, onDismiss = { picking = false })
}

@Composable
private fun ChatListRow(s: ChatSummary, task: TaskItem?, lang: String, onClick: () -> Unit) {
    val c = Rc.colors
    val title = chatTitle(s)
    val unreadDesc = if (s.unread > 0) stringResource(R.string.chat_unread, s.unread) else ""
    val maydayDesc = if (s.mayday) stringResource(R.string.chat_mayday_open) else ""
    val mine = s.last?.dir == "out"
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("chat_row_${s.id.key}")
            .semantics { contentDescription = listOf(title, unreadDesc, maydayDesc).filter { it.isNotEmpty() }.joinToString(". ") },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(s, title)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    s.last?.let { Text(listTime(it.atMs), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = c.muted) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (task != null && task.state == "open") StateTag("open")
                    Text(
                        preview(s.last, lang),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = c.ink3,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (s.unread > 0) {
                        Box(
                            Modifier.heightIn(min = 24.dp).widthIn(min = 24.dp).background(if (s.mayday) c.mayday else MaterialTheme.colorScheme.onSurface, RoundedCornerShape(12.dp)).padding(horizontal = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (s.unread > 99) "99+" else s.unread.toString(), style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), color = if (s.mayday) Color.White else MaterialTheme.colorScheme.surface)
                        }
                    } else if (s.mayday) {
                        Box(Modifier.size(12.dp).background(c.mayday, CircleShape))
                    } else if (mine && s.id is ChatId.Direct) {
                        MiniState(s.last!!.state)
                    }
                }
            }
        }
    }
}

/** The state of my newest direct message as a small tag: Delivered, Sent, Waiting. */
@Composable
private fun MiniState(state: String) {
    val c = Rc.colors
    when (state) {
        MessageState.DELIVERED -> Tag(stringResource(R.string.ms_delivered_short), c.doneSoft, c.done)
        MessageState.SENT -> Tag(stringResource(R.string.ms_sent_short), MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface)
        in MessageState.WAITING -> Tag(stringResource(R.string.ms_waiting_short), c.openSoft, c.open)
        else -> Unit
    }
}

/** The icon of a chat: the team icon on ink for the team channel, a radio for other channels, the short name for a person. */
@Composable
private fun Avatar(s: ChatSummary, title: String) {
    val team = s.isTeam
    Box(
        Modifier.size(42.dp).background(if (team) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when (val id = s.id) {
            is ChatId.Direct -> Text(
                (s.shortName ?: title.split(' ', '-', '_').filter { it.isNotBlank() }.let { p -> if (p.size >= 2) "${p[0].first()}${p.last().first()}" else title.take(2) }).take(4).uppercase(),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp), color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
            )
            is ChatId.Channel -> RcIcon(if (team) R.drawable.ic_group else R.drawable.ic_channel, null, tint = if (team) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface, size = 22.dp)
        }
    }
}

/** The chat's name: the channel ("Public (LongFast)" for the public one), or the node's name, else "Node 1a2b". */
@Composable
fun chatTitle(s: ChatSummary): String = when (val id = s.id) {
    is ChatId.Channel -> if (s.isPublic) stringResource(R.string.chat_public_title, id.name) else id.name
    is ChatId.Direct -> s.title ?: stringResource(R.string.sender_node, TeamAlerts.shortNode(id.node))
}

@Composable
private fun preview(m: MessageEntity?, lang: String): String {
    if (m == null) return stringResource(R.string.chat_empty_title)
    val words = when {
        m.type == "ack" -> TeamAlerts.ackStatus(m.json)?.let { Labels.head(lang, "ack") + ": " + it } ?: Labels.head(lang, "ack")
        m.type in CARD_TYPES -> Labels.head(lang, m.type)
        else -> wordsOf(m, lang).orEmpty()
    }
    val line = words.replace('\n', ' ')
    return if (m.dir == "out") stringResource(R.string.chat_you, line) else line
}

/** "14:05" today, else "24.11". */
private fun listTime(ms: Long): String {
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(ms).atZone(zone)
    return if (d.toLocalDate() == LocalDate.now(zone)) timeOf(ms) else "%02d.%02d".format(d.dayOfMonth, d.monthValue)
}

/** Picks a radio from the node list for a new direct message. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodePicker(nodes: List<NodeName>, onPick: (NodeName) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(bottom = 24.dp).testTag("node_picker")) {
            Text(stringResource(R.string.chat_pick_node), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (nodes.isEmpty()) {
                Text(stringResource(R.string.chat_no_nodes), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
            }
            LazyColumn {
                items(nodes, key = { it.num }) { n ->
                    Surface(onClick = { onPick(n) }, color = Color.Transparent, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("node_%08x".format(n.num))) {
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Box(Modifier.size(44.dp).background(senderColor(n.num), CircleShape), contentAlignment = Alignment.Center) {
                                Text(n.shortName.ifBlank { n.label.orEmpty().take(2) }.take(4), style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(n.label ?: "!%08x".format(n.num), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("!%08x".format(n.num), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

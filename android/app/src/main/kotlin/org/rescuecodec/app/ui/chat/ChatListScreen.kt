// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.chat

import androidx.compose.foundation.background
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
 * Team: the chat list, like any messenger. The team channel pinned on top, then the other private channels of the
 * radio, then direct messages, then the public channel when it is switched on. Each row: name, newest message, time,
 * unread count, a red dot while a Mayday is open there. "New direct message" picks a radio from the node list.
 */
@Composable
fun ChatListScreen(vm: ChatListViewModel, lang: String, onOpen: (ChatId) -> Unit) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    val candidates by vm.directCandidates.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    val list = chats ?: return
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("chat_list_screen"), contentPadding = PaddingValues(bottom = 88.dp)) {
            items(list, key = { it.id.key }) { s ->
                ChatListRow(s, lang) { onOpen(s.id) }
                Hairline(Modifier.padding(start = 76.dp))
            }
            item(key = "new_direct") {
                Surface(
                    onClick = { picking = true },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("new_direct"),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { RcIcon(R.drawable.ic_add, null, tint = MaterialTheme.colorScheme.primary) }
                        Text(stringResource(R.string.chat_new_direct), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    if (picking) NodePicker(candidates, onPick = { picking = false; onOpen(ChatId.Direct(it.num)) }, onDismiss = { picking = false })
}

@Composable
private fun ChatListRow(s: ChatSummary, lang: String, onClick: () -> Unit) {
    val c = Rc.colors
    val title = chatTitle(s)
    val unreadDesc = if (s.unread > 0) stringResource(R.string.chat_unread, s.unread) else ""
    val maydayDesc = if (s.mayday) stringResource(R.string.chat_mayday_open) else ""
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("chat_row_${s.id.key}")
            .semantics { contentDescription = listOf(title, unreadDesc, maydayDesc).filter { it.isNotEmpty() }.joinToString(". ") },
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Avatar(s, title)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (s.mayday) Box(Modifier.size(10.dp).background(c.mayday, CircleShape))
                    Box(Modifier.weight(1f))
                    s.last?.let { Text(listTime(it.atMs), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = if (s.unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        preview(s.last, lang),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (s.unread > 0) {
                        Box(
                            Modifier.heightIn(min = 22.dp).background(if (s.mayday) c.mayday else MaterialTheme.colorScheme.primary, RoundedCornerShape(11.dp)).padding(horizontal = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (s.unread > 99) "99+" else s.unread.toString(), style = MaterialTheme.typography.labelMedium, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/** A round avatar: "#" for a channel, the node's short name for a direct message. */
@Composable
private fun Avatar(s: ChatSummary, title: String) {
    val c = Rc.colors
    val (bg, text) = when (val id = s.id) {
        is ChatId.Direct -> senderColor(id.node) to (s.shortName ?: title.take(2)).take(4)
        is ChatId.Channel -> (if (s.isTeam) MaterialTheme.colorScheme.primary else if (s.isPublic) MaterialTheme.colorScheme.outline else c.senders[(id.name.hashCode() and 0x7fffffff) % c.senders.size]) to "#"
    }
    Box(Modifier.size(48.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1)
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

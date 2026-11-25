// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import org.rescuecodec.app.core.Readable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rescuecodec.app.R
import org.rescuecodec.app.core.AnswerResult
import org.rescuecodec.app.core.ChannelChoice
import org.rescuecodec.app.core.LocationHelper
import org.rescuecodec.app.core.TeamAlerts
import org.rescuecodec.app.data.MessageEntity
import org.rescuecodec.app.data.MessageState
import org.rescuecodec.app.radio.LinkState
import org.rescuecodec.app.ui.ChatViewModel
import org.rescuecodec.app.ui.MessagesViewModel
import org.rescuecodec.app.ui.ReportEvent
import org.rescuecodec.app.ui.ReportViewModel
import org.rescuecodec.app.ui.TaskItem
import org.rescuecodec.app.ui.TasksViewModel
import org.rescuecodec.app.ui.components.BigButton
import org.rescuecodec.app.ui.components.RcIcon
import org.rescuecodec.app.ui.components.SourceTag
import org.rescuecodec.app.ui.theme.Rc
import org.rescuecodec.app.voice.SpeakState
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** One entry of the chat list: a message or a day separator. Newest first, because the list is reversed. */
private sealed interface Entry {
    val key: Any
    data class Msg(val m: MessageEntity) : Entry { override val key: Any get() = m.id }
    data class Day(val day: LocalDate) : Entry { override val key: Any get() = "day$day" }
}

/**
 * One conversation: a channel or a direct message. Newest at the bottom, like any messenger. My messages on the right with
 * status ticks, others on the left with their name. Reports are cards with Accept and Done, answers are small
 * centred lines. The composer sits at the bottom.
 */
@Composable
fun ChatScreen(
    chat: ChatViewModel,
    messagesVm: MessagesViewModel,
    tasksVm: TasksViewModel,
    reportVm: ReportViewModel,
    tasks: List<TaskItem>,
    link: LinkState,
    radioChannel: ChannelChoice,
    snackbar: SnackbarHostState,
    lang: String,
    onOpenRadio: () -> Unit,
) {
    val loaded by chat.messages.collectAsStateWithLifecycle()
    val messages = loaded.orEmpty()
    val names by chat.names.collectAsStateWithLifecycle()
    val isTeam by chat.isTeam.collectAsStateWithLifecycle()
    val isPublic by chat.isPublic.collectAsStateWithLifecycle()
    // The composer sends to this chat: reports on a channel, text only in a direct message or on the public channel.
    LaunchedEffect(chat.chat, isTeam, isPublic) { reportVm.setChat(chat.chat, isTeam, isPublic) }
    val speak by messagesVm.speaker.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var sheetFor by remember { mutableStateOf<MessageEntity?>(null) }

    // Own position, for "1.2 km away" on cards. Read once; no permission means no distance.
    val fix by produceState<org.rescuecodec.app.core.Fix?>(null) { value = withContext(Dispatchers.IO) { LocationHelper.lastKnown(context) } }

    val tasksByKey = remember(tasks) { tasks.associateBy { it.key } }
    // A sender's team label, learned from their answers ("Trupp 3"); else "Node 1a2b".
    val teams = remember(messages) {
        messages.filter { it.type == "ack" && it.dir == "in" }.mapNotNull { m -> TeamAlerts.ackTeam(m.json)?.let { m.node to it } }.toMap()
    }
    val entries = remember(messages) {
        val out = ArrayList<Entry>(messages.size + 4)
        val zone = ZoneId.systemDefault()
        messages.forEachIndexed { i, m ->
            out += Entry.Msg(m)
            val day = Instant.ofEpochMilli(m.atMs).atZone(zone).toLocalDate()
            val older = messages.getOrNull(i + 1)?.let { Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() }
            if (older != day) out += Entry.Day(day)
        }
        out
    }

    // New message: follow it when the newest is in view, else show the "New messages" pill.
    var newestSeen by remember { mutableLongStateOf(-1L) }
    var unseen by remember { mutableIntStateOf(0) }
    val newest = messages.firstOrNull()?.id ?: 0L
    // The person sees this chat: what is in it now is read.
    LaunchedEffect(newest, loaded != null) { if (loaded != null) chat.markRead() }
    LaunchedEffect(newest) {
        if (newestSeen < 0) { newestSeen = newest; return@LaunchedEffect }
        if (newest > newestSeen) {
            val fresh = messages.count { it.id > newestSeen }
            val mineOnly = messages.filter { it.id > newestSeen }.all { it.dir == "out" }
            newestSeen = newest
            if (list.firstVisibleItemIndex <= 1 || mineOnly) list.animateScrollToItem(0) else unseen += fresh
        }
    }
    LaunchedEffect(list) { snapshotFlow { list.firstVisibleItemIndex }.collect { if (it == 0) unseen = 0 } }
    LaunchedEffect(Unit) {
        reportVm.events.collect { e ->
            when (e) {
                is ReportEvent.Queued -> list.animateScrollToItem(0)
                is ReportEvent.Rejected -> snackbar.showSnackbar(context.getString(R.string.report_rejected, e.message))
            }
        }
    }
    val unavailable = stringResource(R.string.speak_unavailable, stringResource(if (lang == "de") R.string.lang_german else R.string.lang_english))
    LaunchedEffect(speak) {
        if (speak is SpeakState.Unavailable) { snackbar.showSnackbar(unavailable); messagesVm.speaker.clearUnavailable() }
    }

    val errorText = stringResource(R.string.answer_failed)
    val copied = stringResource(R.string.copied)
    val actions = remember(lang) {
        ChatActions(
            answer = { t, status -> tasksVm.answer(t.key, status) { r -> if (r !is AnswerResult.Sent) scope.launch { snackbar.showSnackbar(errorText.format(r.toString())) } } },
            speak = { m, words -> messagesVm.speaker.speak("m${m.id}", words, lang) },
            menu = { sheetFor = it },
        )
    }
    val copy: (String) -> Unit = { words ->
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("RescueCodec", words))
        scope.launch { snackbar.showSnackbar(copied) }
    }

    Column(Modifier.fillMaxSize().background(Rc.colors.chatGround)) {
        RadioBanner(link, radioChannel, onOpenRadio)
        if (chat.chat is org.rescuecodec.app.core.ChatId.Direct || isPublic) ChatHint(stringResource(if (isPublic) R.string.chat_public_hint else R.string.chat_direct_hint))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (loaded == null) {
                // Still reading the database: show nothing rather than a false "No messages yet".
            } else if (messages.isEmpty()) {
                EmptyChat()
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().testTag("chat_list"),
                    state = list,
                    reverseLayout = true,
                    contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
                ) {
                    items(entries, key = { it.key }, contentType = { if (it is Entry.Day) 0 else 1 }) { e ->
                        when (e) {
                            is Entry.Day -> DaySeparator(e.day)
                            is Entry.Msg -> {
                                val m = e.m
                                val task = m.taskKey?.let { tasksByKey[it] }
                                val name = names[m.node]?.label ?: teams[m.node] ?: stringResource(R.string.sender_node, TeamAlerts.shortNode(m.node))
                                ChatRow(m, task, task?.type, lang, name, fix, actions)
                            }
                        }
                    }
                }
            }
            if (unseen > 0) {
                Surface(
                    onClick = { scope.launch { list.animateScrollToItem(0) }; unseen = 0 },
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).heightIn(min = 48.dp).testTag("new_messages_pill"),
                ) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RcIcon(R.drawable.ic_arrow_downward, null, tint = MaterialTheme.colorScheme.onPrimary, size = 20.dp)
                        Text("${stringResource(R.string.new_messages)} · $unseen", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        Composer(reportVm, lang, textOnly = chat.chat is org.rescuecodec.app.core.ChatId.Direct || isPublic, onSent = {})
    }

    sheetFor?.let { m ->
        MessageSheet(m, lang, messagesVm, onSpeak = { w -> actions.speak(m, w) }, onCopy = copy, onDismiss = { sheetFor = null })
    }
}

/** "No radio connected. Messages wait. [Connect]" at the top of the chat, or the channel problem when there is one. */
@Composable
private fun RadioBanner(link: LinkState, channel: ChannelChoice, onOpenRadio: () -> Unit) {
    val c = Rc.colors
    val text = when {
        link is LinkState.Disconnected || (link is LinkState.Error && link.retryAtMs == null) -> stringResource(R.string.no_radio_banner)
        link is LinkState.Connected && channel is ChannelChoice.Problem -> channel.detail
        else -> return
    }
    Surface(color = c.openSoft, contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().testTag("no_radio_banner")) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RcIcon(R.drawable.ic_error_outline, null, tint = c.open, size = 22.dp)
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenRadio, modifier = Modifier.heightIn(min = 48.dp).testTag("banner_connect")) {
                Text(stringResource(R.string.action_connect), style = MaterialTheme.typography.labelLarge, color = c.open)
            }
        }
    }
}

/** One quiet line under the top bar: what this chat carries (direct message or public channel: text only). */
@Composable
private fun ChatHint(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag("chat_hint")) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
private fun DaySeparator(day: LocalDate) {
    val today = LocalDate.now()
    val text = when (day) {
        today -> stringResource(R.string.day_today)
        today.minusDays(1) -> stringResource(R.string.day_yesterday)
        else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant()))
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(text.uppercase(), style = MaterialTheme.typography.titleLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp), color = Rc.colors.muted)
    }
}

@Composable
private fun EmptyChat() {
    Box(Modifier.fillMaxSize().padding(32.dp).testTag("chat_empty"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RcIcon(R.drawable.ic_forum, null, tint = MaterialTheme.colorScheme.outline, size = 40.dp)
            Text(stringResource(R.string.chat_empty_title), style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp))
            Text(stringResource(R.string.chat_empty_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Long-press a message: a bottom sheet with Read aloud, Show position (Grid), Copy text and Details (bytes on air and
 * as text, signal, hops, hex), and Cancel or Send again for my own waiting or failed messages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageSheet(m: MessageEntity, lang: String, vm: MessagesViewModel, onSpeak: (String) -> Unit, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val words = remember(m.json, lang) { wordsOf(m, lang) }
    val grid = remember(m.json) { Readable.gridRef(m.json) }
    val incoming = m.dir == "in"
    var full by remember { mutableStateOf(false) }
    val asText = remember(m.json, lang) { (textBody(m.json) ?: Readable.of(m.json, lang))?.toByteArray(Charsets.UTF_8)?.size }
    val packed = remember(m.json) { org.rescuecodec.app.core.MeshText.isPacked(m) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)) {
        Column(Modifier.semantics { testTagsAsResourceId = true }.padding(horizontal = 16.dp).padding(bottom = 20.dp).verticalScroll(rememberScrollState()).testTag("message_sheet")) {
            if (words != null) SheetItem(R.drawable.ic_volume_up, stringResource(R.string.action_read_aloud), "sheet_speak") { onSpeak(words); onDismiss() }
            if (grid != null) SheetItem(R.drawable.ic_location_on, stringResource(R.string.action_show_position, grid), "sheet_position") {
                // A map app if there is one (offline maps work too), else the grid reference goes to the clipboard.
                val pos = runCatching { org.rescuecodec.codec.Json.parseObject(m.json!!)["pos"] as Map<*, *> }.getOrNull()
                val ok = pos != null && runCatching {
                    val lat = (pos["lat"] as Number).toDouble(); val lon = (pos["lon"] as Number).toDouble()
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:$lat,$lon?q=$lat,$lon")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                if (!ok) onCopy(grid)
                onDismiss()
            }
            if (words != null) SheetItem(R.drawable.ic_content_copy, stringResource(R.string.action_copy_text), "sheet_copy") { onCopy(words); onDismiss() }
            SheetItem(R.drawable.ic_info, stringResource(R.string.action_details), "sheet_details", last = true) { full = !full }
            // The short details, always: bytes on air and as text, signal and hops, the first bytes in hex.
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp).testTag("details_box"),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val small = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontFeatureSettings = "tnum")
                Text(if (asText != null) stringResource(R.string.details_compact, m.bytes.size, asText) else stringResource(R.string.feed_bytes, m.bytes.size), style = small, color = Rc.colors.ink3)
                // A packed text (spec section 3.5): the words above are unpacked, the bytes on air are compressed.
                if (packed) Text(stringResource(R.string.details_packed), style = small, color = Rc.colors.ink3, modifier = Modifier.testTag("details_packed"))
                if (incoming && m.rssi != null && m.rssi != 0) {
                    Text(
                        stringResource(R.string.details_signal_line, m.rssi, m.snr ?: 0f) + (m.hops?.let { " · " + stringResource(R.string.feed_hops, it) } ?: ""),
                        style = small, color = Rc.colors.ink3,
                    )
                }
                val hex = m.bytes.joinToString(" ") { "%02x".format(it) }
                Text(
                    if (full || m.bytes.size <= 15) hex else m.bytes.take(15).joinToString(" ") { "%02x".format(it) } + " …",
                    style = small.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp), color = Rc.colors.ink3,
                )
            }
            if (full) {
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailRow(stringResource(R.string.details_state), if (incoming) stringResource(R.string.feed_received) else stateLabel(m.state))
                    DetailRow(stringResource(R.string.details_time), SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(m.atMs)))
                    DetailRow(stringResource(R.string.details_size), stringResource(R.string.feed_bytes, m.bytes.size))
                    if (incoming) DetailRow(stringResource(R.string.feed_received), stringResource(R.string.feed_from, "!%08x".format(m.node)))
                    if (incoming && m.rssi != null && m.rssi != 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DetailRow(stringResource(R.string.details_signal), stringResource(R.string.feed_signal, m.rssi, m.snr ?: 0f), Modifier.weight(1f))
                            SourceTag(measured = true)
                        }
                    }
                    if (incoming && m.hops != null) DetailRow(stringResource(R.string.details_route), stringResource(R.string.feed_hops, m.hops))
                    if (!incoming && !m.detail.isNullOrBlank()) Text(m.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!incoming && m.state in MessageState.WAITING) BigButton(
                stringResource(R.string.action_cancel), onClick = { vm.cancel(m.id); onDismiss() }, outlined = true,
                container = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("details_cancel"),
            )
            if (!incoming && m.state in setOf(MessageState.NOT_CONFIRMED, MessageState.FAILED, MessageState.EXPIRED, MessageState.CANCELLED)) BigButton(
                stringResource(R.string.action_send_again), onClick = { vm.resend(m.id); onDismiss() }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("details_resend"),
            )
        }
    }
}

/** One row of the long-press sheet: icon and words, 52 dp, a hairline under it. */
@Composable
private fun SheetItem(icon: Int, text: String, tag: String, last: Boolean = false, onClick: () -> Unit) {
    Column {
        Surface(onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag)) {
            Row(Modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RcIcon(icon, null, size = 22.dp)
                Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), maxLines = 2)
            }
        }
        if (!last) org.rescuecodec.app.ui.components.Hairline()
    }
}

@Composable
private fun stateLabel(state: String): String = stringResource(
    when (state) {
        MessageState.QUEUED -> R.string.ms_queued
        MessageState.HELD -> R.string.ms_held
        MessageState.REFUSED_AIRTIME -> R.string.ms_refused
        MessageState.SENT -> R.string.ms_sent
        MessageState.DELIVERED -> R.string.ms_delivered
        MessageState.NOT_CONFIRMED -> R.string.ms_not_confirmed
        MessageState.FAILED -> R.string.ms_failed
        MessageState.EXPIRED -> R.string.ms_expired
        else -> R.string.ms_cancelled
    },
)

@Composable
private fun DetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(0.6f))
    }
}

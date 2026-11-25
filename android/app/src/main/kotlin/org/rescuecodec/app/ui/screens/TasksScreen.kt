// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.rescuecodec.app.R
import org.rescuecodec.app.core.AnswerResult
import org.rescuecodec.app.core.Labels
import org.rescuecodec.app.core.Readable
import org.rescuecodec.app.ui.TaskAnswer
import org.rescuecodec.app.ui.TaskItem
import org.rescuecodec.app.ui.TasksViewModel
import org.rescuecodec.app.ui.components.BigButton
import org.rescuecodec.app.ui.components.RcCard
import org.rescuecodec.app.ui.components.RcIcon
import org.rescuecodec.app.ui.components.SectionHeader
import org.rescuecodec.app.ui.components.StateTag
import org.rescuecodec.app.ui.components.Tag
import org.rescuecodec.app.ui.components.PlainTag
import org.rescuecodec.app.ui.components.GridChip
import org.rescuecodec.app.ui.components.agoText
import org.rescuecodec.app.ui.components.rememberNow
import org.rescuecodec.app.ui.theme.BarlowCondensed
import org.rescuecodec.app.ui.theme.Rc
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle

/**
 * Tasks: Mayday first, then open, then the ones I accepted, then the ones other teams took, then done (folded away).
 * Accept and Done are the big buttons. A finished task has no Accept.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TasksScreen(vm: TasksViewModel, tasks: List<TaskItem>, snackbar: SnackbarHostState, lang: String) {
    val scope = rememberCoroutineScope()
    val now by rememberNow()
    // With more than one private channel, each card names the channel it came on (its answers go there too).
    val showChannel by vm.showChannel.collectAsStateWithLifecycle()
    var showDone by rememberSaveable { mutableStateOf(false) }
    val mayday = tasks.filter { it.type == "mayday" && it.state != "done" }
    val rest = tasks - mayday.toSet()
    val open = rest.filter { it.state == "open" || it.state == "declined" || it.state == "delivery_unknown" }
    val mine = rest.filter { it.state == "accepted" && it.ownStatus == "accepted" }
    val others = rest.filter { it.state == "accepted" && it.ownStatus != "accepted" }
    val done = rest.filter { it.state == "done" }

    val errorText = stringResource(R.string.answer_failed)
    val onAnswer: (TaskItem, String) -> Unit = { t, status ->
        vm.answer(t.key, status) { r ->
            if (r !is AnswerResult.Sent) scope.launch { snackbar.showSnackbar(errorText.format(r.toString())) }
        }
    }

    if (tasks.isEmpty()) {
        EmptyState(R.string.actions_empty_title, R.string.actions_empty_body, R.drawable.ic_assignment_turned_in)
        return
    }
    val maydayColor = Rc.colors.maydayText
    LazyColumn(
        Modifier.fillMaxSize().testTag("actions_list"),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        section(R.string.lane_mayday, mayday, maydayColor, lang, now, showChannel, onAnswer)
        section(R.string.lane_open, open, null, lang, now, showChannel, onAnswer)
        section(R.string.lane_mine_short, mine, null, lang, now, showChannel, onAnswer, plain = true)
        section(R.string.lane_others, others, null, lang, now, showChannel, onAnswer, plain = true)
        if (done.isNotEmpty()) {
            item(key = "done_header") {
                Surface(
                    onClick = { showDone = !showDone },
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("lane_done_toggle"),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(stringResource(R.string.lane_done), count = done.size, modifier = Modifier.weight(1f), plain = true)
                        Text(stringResource(if (showDone) R.string.lane_hide else R.string.lane_show), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        RcIcon(if (showDone) R.drawable.ic_expand_less else R.drawable.ic_expand_more, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (showDone) items(done, key = { it.key }) { t -> TaskCard(t, lang, now, showChannel, onAnswer) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: Int, list: List<TaskItem>, color: androidx.compose.ui.graphics.Color?, lang: String, now: Long, showChannel: Boolean, onAnswer: (TaskItem, String) -> Unit,
    plain: Boolean = false,
) {
    if (list.isEmpty()) return
    item(key = "h$title") { SectionHeader(stringResource(title), count = list.size, color = color ?: MaterialTheme.colorScheme.onSurface, plain = plain) }
    items(list, key = { it.key }) { t -> TaskCard(t, lang, now, showChannel, onAnswer) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskCard(t: TaskItem, lang: String, now: Long, showChannel: Boolean, onAnswer: (TaskItem, String) -> Unit) {
    val c = Rc.colors
    val isMayday = t.type == "mayday"
    val channelDesc = stringResource(R.string.task_channel, t.channelName.orEmpty())
    val readable = remember(t.json, lang) { org.rescuecodec.app.ui.chat.cardLine(t.json, t.type, lang) }
    val gridRef = remember(t.json) { Readable.gridRef(t.json) }
    RcCard(
        container = if (isMayday) c.maydaySoft else MaterialTheme.colorScheme.surface,
        outline = if (isMayday) c.mayday else MaterialTheme.colorScheme.outlineVariant,
        padding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
        modifier = Modifier.testTag("task_${t.key}"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (isMayday) Tag("⚠ " + Labels.head(lang, "mayday").uppercase(), c.mayday, c.onMayday)
                    else PlainTag(Labels.head(lang, t.type.takeIf { it != "unknown" } ?: "unknown"))
                    StateTag(t.state)
                    if (t.local) PlainTag(stringResource(R.string.tag_ours))
                    if (showChannel && t.channelName != null) Tag(
                        t.channelName, androidx.compose.ui.graphics.Color.Transparent, MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { contentDescription = channelDesc }.testTag("task_channel_${t.key}"),
                        border = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                Text(agoText(t.updatedMs, now), style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp), color = c.muted)
            }
            Text(
                readable ?: stringResource(R.string.task_unknown_report),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 23.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (gridRef != null) GridChip(gridRef)
            if (t.local && t.state == "open") {
                Text(
                    stringResource(if (t.meshDelivered) R.string.task_local_delivered else R.string.task_local_waiting),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (t.state == "delivery_unknown") {
                Text(stringResource(R.string.task_delivery_unknown), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            t.answers.forEach { AnswerLine(it) }
            if (t.moves.isNotEmpty()) org.rescuecodec.app.ui.chat.AnswerButtons(t, onAnswer)
        }
    }
}

@Composable
private fun AnswerLine(a: TaskAnswer) {
    val c = Rc.colors
    val color = when (a.status) { "accepted" -> c.accepted; "done" -> c.done; else -> c.declined }
    val who = a.team ?: if (a.own) stringResource(R.string.answer_you) else stringResource(R.string.answer_other_team)
    val word = when {
        a.own && a.team == null -> stringResource(when (a.status) { "accepted" -> R.string.answer_accepted_by_you; "done" -> R.string.answer_done_by_you; else -> R.string.answer_declined_by_you })
        a.status == "accepted" -> stringResource(R.string.answer_accepted_by, who)
        a.status == "done" -> stringResource(R.string.answer_done_by, who)
        else -> stringResource(R.string.answer_declined_by, who)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RcIcon(if (a.status == "done") R.drawable.ic_check_circle else if (a.status == "accepted") R.drawable.ic_check else R.drawable.ic_close, null, tint = color, size = 20.dp)
        Text(
            word + (a.etaMin?.let { " " + stringResource(R.string.answer_eta, it) } ?: ""),
            style = MaterialTheme.typography.bodyMedium, color = color,
        )
    }
}

/** An empty list that says what will appear here and what to do. */
@Composable
fun EmptyState(title: Int, body: Int, icon: Int) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RcIcon(icon, null, tint = MaterialTheme.colorScheme.outline, size = 48.dp)
            Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/**
 * The red Mayday banner under the top bar, on every screen (overnight): the warning sign, MAYDAY in condensed capitals,
 * who and why with the age on a second line, and a white Accept (or Done) button. It shows our own Mayday (waiting,
 * sent, answered) or one we received. Tap it to open the Tasks tab.
 */
@Composable
fun MaydayBanner(
    vm: TasksViewModel,
    tasks: List<TaskItem>,
    waiting: List<org.rescuecodec.app.data.MessageEntity>,
    lang: String,
    names: Map<Long, org.rescuecodec.app.core.NodeName> = emptyMap(),
    onOpen: () -> Unit,
) {
    val c = Rc.colors
    val active = tasks.filter { it.type == "mayday" && (it.state == "open" || it.state == "accepted" || it.state == "delivery_unknown") }
        .maxByOrNull { it.updatedMs }
    val queuedOwn = waiting.any { it.type == "mayday" }
    if (active == null && !queuedOwn) return

    val now by rememberNow()
    val persons = remember(active?.json) { Readable.maydayPersons(active?.json) }
    val reason = remember(active?.json, lang) {
        runCatching {
            val body = org.rescuecodec.codec.Json.parseObject(active!!.json!!)["mayday"] as Map<*, *>
            (body["reason"] as? String)?.let { org.rescuecodec.app.core.Labels.value(lang, "mayday", it) }
        }.getOrNull()
    }
    val node = active?.key?.substringBefore('-')?.toLongOrNull(16)
    val who = node?.let { names[it]?.label ?: stringResource(R.string.sender_node, org.rescuecodec.app.core.TeamAlerts.shortNode(it)) }
    val status = when {
        active == null -> stringResource(R.string.banner_queued)
        active.local && active.state == "accepted" -> stringResource(R.string.banner_ours_accepted, active.answers.lastOrNull()?.team ?: stringResource(R.string.answer_other_team))
        active.local && active.state == "delivery_unknown" -> stringResource(R.string.banner_ours_unknown)
        active.local && active.meshDelivered -> stringResource(R.string.banner_ours_delivered)
        active.local -> stringResource(R.string.banner_ours_sent)
        active.state == "accepted" -> stringResource(R.string.banner_theirs_accepted)
        else -> null
    }
    // Two short lines: who and why, then the age. Our own Mayday: its state.
    val line1 = status ?: listOfNotNull(who, reason ?: persons?.let { stringResource(R.string.banner_persons, it) }).joinToString(" · ")
    val line2 = if (status == null && active != null) agoText(active.updatedMs, now) else null
    val word = Labels.head(lang, "mayday").uppercase()
    Surface(onClick = onOpen, color = c.mayday, contentColor = c.onMayday, modifier = Modifier.fillMaxWidth().testTag("mayday_banner")) {
        Row(
            Modifier.heightIn(min = 60.dp).padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RcIcon(R.drawable.ic_warning, null, tint = c.onMayday, size = 26.dp)
            Text(word, style = MaterialTheme.typography.titleLarge.copy(fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = 0.4.sp), color = c.onMayday)
            Column(Modifier.weight(1f)) {
                Text(line1, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 18.sp), color = c.onMayday, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (line2 != null) Text(line2, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 18.sp), color = c.onMayday, maxLines = 1)
            }
            if (active != null) {
                if ("accepted" in active.moves) BigButton(
                    stringResource(R.string.action_accept), { vm.answer(active.key, "accepted") }, Modifier.testTag("banner_accept"),
                    container = c.onMayday, content = c.mayday, height = 48.dp,
                )
                else if ("done" in active.moves) BigButton(
                    stringResource(R.string.action_done), { vm.answer(active.key, "done") }, Modifier.testTag("banner_done"),
                    container = c.onMayday, content = c.mayday, height = 48.dp,
                )
            }
        }
    }
}

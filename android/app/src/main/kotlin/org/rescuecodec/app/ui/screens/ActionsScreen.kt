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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
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

/** Tasks: Mayday first, then open, accepted and done. Accept and Done are the big buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionsScreen(vm: TasksViewModel, tasks: List<TaskItem>, snackbar: SnackbarHostState, lang: String) {
    val scope = rememberCoroutineScope()
    val now by rememberNow()
    val mayday = tasks.filter { it.type == "mayday" && it.state != "done" }
    val rest = tasks - mayday.toSet()
    val open = rest.filter { it.state == "open" || it.state == "declined" || it.state == "delivery_unknown" }
    val accepted = rest.filter { it.state == "accepted" }
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
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        section(R.string.lane_mayday, mayday, maydayColor, lang, now, onAnswer)
        section(R.string.lane_open, open, null, lang, now, onAnswer)
        section(R.string.lane_accepted, accepted, null, lang, now, onAnswer)
        section(R.string.lane_done, done, null, lang, now, onAnswer)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: Int, list: List<TaskItem>, color: androidx.compose.ui.graphics.Color?, lang: String, now: Long, onAnswer: (TaskItem, String) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = "h$title") { SectionHeader(stringResource(title), count = list.size, color = color ?: MaterialTheme.colorScheme.onSurface) }
    items(list, key = { it.key }) { t -> TaskCard(t, lang, now, onAnswer) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskCard(t: TaskItem, lang: String, now: Long, onAnswer: (TaskItem, String) -> Unit) {
    val c = Rc.colors
    val isMayday = t.type == "mayday"
    val readable = remember(t.json, lang) { Readable.withoutPosition(t.json, lang) }
    val gridRef = remember(t.json) { Readable.gridRef(t.json) }
    RcCard(
        container = if (isMayday) c.maydaySoft else MaterialTheme.colorScheme.surfaceContainerLow,
        outline = if (isMayday) c.mayday else MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.testTag("task_${t.key}"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isMayday) Tag(Labels.head(lang, "mayday"), c.mayday, c.onMayday, icon = R.drawable.ic_warning)
                    else Tag(Labels.head(lang, t.type.takeIf { it != "unknown" } ?: "unknown"), MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface)
                    StateTag(t.state)
                    if (t.local) Tag(stringResource(R.string.tag_ours), MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(agoText(t.updatedMs, now), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                readable ?: stringResource(R.string.task_unknown_report),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 28.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (gridRef != null) GridRefChip(gridRef)
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
            if (t.moves.isNotEmpty()) {
                val hasMain = "accepted" in t.moves || "done" in t.moves
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if ("accepted" in t.moves) BigButton(
                        stringResource(R.string.action_accept), { onAnswer(t, "accepted") }, Modifier.weight(0.6f).testTag("accept_${t.key}"),
                        icon = R.drawable.ic_check, container = c.acceptSolid, content = c.onSolid,
                    )
                    if ("done" in t.moves) BigButton(
                        stringResource(R.string.action_done), { onAnswer(t, "done") }, Modifier.weight(0.6f).testTag("done_${t.key}"),
                        icon = R.drawable.ic_check_circle, container = c.doneSolid, content = c.onSolid,
                    )
                    // Decline is smaller and far from Accept, so a glove does not hit it by mistake.
                    if ("declined" in t.moves) BigButton(
                        stringResource(R.string.action_decline), { onAnswer(t, "declined") }, Modifier.weight(if (hasMain) 0.4f else 1f).testTag("decline_${t.key}"),
                        outlined = true, container = MaterialTheme.colorScheme.onSurfaceVariant, height = 48.dp,
                    )
                }
            }
        }
    }
}

/** The UTM grid reference as a chip, in monospace, the same as the dashboard. */
@Composable
private fun GridRefChip(ref: String) {
    Row(
        Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 6.dp).testTag("grid_ref"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.task_grid_ref), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(ref, style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun AnswerLine(a: TaskAnswer) {
    val c = Rc.colors
    val color = when (a.status) { "accepted" -> c.accepted; "done" -> c.done; else -> c.declined }
    val who = a.team ?: if (a.own) stringResource(R.string.answer_you) else stringResource(R.string.answer_other_team)
    val word = when (a.status) {
        "accepted" -> stringResource(R.string.answer_accepted_by, who)
        "done" -> stringResource(R.string.answer_done_by, who)
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
 * The red Mayday banner under the top bar, on every screen: one line, 64 dp. It shows our own
 * Mayday (waiting, sent, answered) or one we received. Tap it to open the task list. Received
 * ones have the Accept or Done button right here; the full text is on the card in Actions.
 */
@Composable
fun MaydayBanner(
    vm: TasksViewModel,
    tasks: List<TaskItem>,
    waiting: List<org.rescuecodec.app.data.MessageEntity>,
    lang: String,
    onOpen: () -> Unit,
) {
    val c = Rc.colors
    val active = tasks.filter { it.type == "mayday" && (it.state == "open" || it.state == "accepted" || it.state == "delivery_unknown") }
        .maxByOrNull { it.updatedMs }
    val queuedOwn = waiting.any { it.type == "mayday" }
    if (active == null && !queuedOwn) return

    val now by rememberNow()
    val persons = remember(active?.json) { Readable.maydayPersons(active?.json) }
    val status = when {
        active == null -> stringResource(R.string.banner_queued)
        active.local && active.state == "accepted" -> stringResource(R.string.banner_ours_accepted, active.answers.lastOrNull()?.team ?: stringResource(R.string.answer_other_team))
        active.local && active.state == "delivery_unknown" -> stringResource(R.string.banner_ours_unknown)
        active.local && active.meshDelivered -> stringResource(R.string.banner_ours_delivered)
        active.local -> stringResource(R.string.banner_ours_sent)
        active.state == "accepted" -> stringResource(R.string.banner_theirs_accepted)
        else -> null
    }
    // One short line: persons and age for a Mayday we received, the state for our own.
    val line = if (status != null) status else buildList {
        persons?.let { add(stringResource(R.string.banner_persons, it)) }
        active?.let { add(agoText(it.updatedMs, now)) }
    }.joinToString(", ")
    val word = Labels.head(lang, "mayday").uppercase()
    Surface(onClick = onOpen, color = c.mayday, contentColor = c.onMayday, modifier = Modifier.fillMaxWidth().testTag("mayday_banner")) {
        Row(
            Modifier.heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RcIcon(R.drawable.ic_warning, null, tint = c.onMayday, size = 28.dp)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 24.sp)) { append(word) }
                    if (line.isNotEmpty()) { append("  "); append(line) }
                },
                style = MaterialTheme.typography.titleMedium, color = c.onMayday, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (active != null) {
                if ("accepted" in active.moves) BigButton(
                    stringResource(R.string.action_accept), { vm.answer(active.key, "accepted") }, Modifier.width(112.dp).testTag("banner_accept"),
                    container = c.onMayday, content = c.mayday, height = 56.dp,
                )
                else if ("done" in active.moves) BigButton(
                    stringResource(R.string.action_done), { vm.answer(active.key, "done") }, Modifier.width(112.dp).testTag("banner_done"),
                    container = c.onMayday, content = c.mayday, height = 56.dp,
                )
            }
        }
    }
}

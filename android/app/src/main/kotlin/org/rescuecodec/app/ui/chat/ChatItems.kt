// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rescuecodec.app.R
import org.rescuecodec.app.core.FieldKind
import org.rescuecodec.app.core.Fix
import org.rescuecodec.app.core.Labels
import org.rescuecodec.app.core.Readable
import org.rescuecodec.app.core.ReportFields
import org.rescuecodec.app.core.TeamAlerts
import org.rescuecodec.app.data.MessageEntity
import org.rescuecodec.app.data.MessageState
import org.rescuecodec.app.ui.TaskItem
import org.rescuecodec.app.ui.components.BigButton
import org.rescuecodec.app.ui.components.RcIcon
import org.rescuecodec.app.ui.components.StateTag
import org.rescuecodec.app.ui.components.Tag
import org.rescuecodec.app.ui.screens.numberLabel
import org.rescuecodec.app.ui.theme.Rc
import org.rescuecodec.codec.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** What a chat row can do. One object, so every bubble and card gets the same actions. */
class ChatActions(
    val answer: (TaskItem, String) -> Unit,
    val speak: (MessageEntity, String) -> Unit,
    val copy: (String) -> Unit,
    val details: (MessageEntity) -> Unit,
)

/** Report types that render as a card in the stream. */
val CARD_TYPES = setOf("found", "request", "hazard", "sector", "mayday")

internal fun timeOf(ms: Long): String = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(ms))

/** The text body of a text message, or null. */
internal fun textBody(json: String?): String? = runCatching {
    val m = Json.parseObject(json ?: return null)
    (m["text"] as? Map<*, *>)?.get("text") as? String ?: m["text"] as? String
}.getOrNull()

/** The words a person would read for this row (text, or the readable sentence). Used by Copy and Read aloud. */
internal fun wordsOf(m: MessageEntity, lang: String): String? = textBody(m.json) ?: Readable.of(m.json, lang)

/** A stable colour for a sender, from the palette that never uses state colours. */
@Composable
internal fun senderColor(node: Long): Color {
    val p = Rc.colors.senders
    return p[(((node % p.size) + p.size) % p.size).toInt()]
}

/** Metres between two points (haversine). */
internal fun distanceM(a: Fix, lat: Double, lon: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat - a.lat)
    val dLon = Math.toRadians(lon - a.lon)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(lat)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}

internal fun distanceText(m: Double): String = if (m < 1000) "${(m / 10).toInt() * 10} m" else "%.1f km".format(Locale.ROOT, m / 1000)

private fun bubbleShape(mine: Boolean) = if (mine) RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)

@Composable
private fun maxBubbleWidth() = (LocalConfiguration.current.screenWidthDp * 0.84f).dp

/** A row that holds one bubble or card, on the right for mine and on the left for others. */
@Composable
private fun Side(mine: Boolean, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) { content() }
}

/** The long-press menu: Read aloud, Copy, Details. */
@Composable
private fun RowMenu(open: Boolean, words: String?, m: MessageEntity, actions: ChatActions, onClose: () -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onClose) {
        if (words != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_read_aloud), style = MaterialTheme.typography.bodyLarge) },
                leadingIcon = { RcIcon(R.drawable.ic_volume_up, null) },
                onClick = { onClose(); actions.speak(m, words) },
                modifier = Modifier.heightIn(min = 52.dp).testTag("menu_speak"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_copy), style = MaterialTheme.typography.bodyLarge) },
                leadingIcon = { RcIcon(R.drawable.ic_content_copy, null) },
                onClick = { onClose(); actions.copy(words) },
                modifier = Modifier.heightIn(min = 52.dp).testTag("menu_copy"),
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_details), style = MaterialTheme.typography.bodyLarge) },
            leadingIcon = { RcIcon(R.drawable.ic_info, null) },
            onClick = { onClose(); actions.details(m) },
            modifier = Modifier.heightIn(min = 52.dp).testTag("menu_details"),
        )
    }
}

/** One message in the stream: a text bubble, a report card, or a small centred line (answers, track points). */
@Composable
fun ChatRow(
    m: MessageEntity,
    task: TaskItem?,
    taskType: String?,
    lang: String,
    senderName: String,
    fix: Fix?,
    actions: ChatActions,
) {
    val mine = m.dir == "out"
    when {
        m.json == null || m.state == MessageState.INVALID -> SystemLine(stringResource(R.string.feed_unreadable), R.drawable.ic_error_outline, Modifier.testTag("msg_${m.id}"))
        m.type == "ack" -> AckLine(m, mine, taskType, lang, senderName)
        m.type in CARD_TYPES -> Side(mine) { ReportCard(m, task, lang, mine, senderName, fix, actions) }
        m.type == "track" -> SystemLine("${Labels.head(lang, "track")} · $senderName", R.drawable.ic_location_on, Modifier.testTag("msg_${m.id}"))
        else -> Side(mine) { TextBubble(m, lang, mine, senderName, actions) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TextBubble(m: MessageEntity, lang: String, mine: Boolean, senderName: String, actions: ChatActions) {
    val c = Rc.colors
    val words = remember(m.json, lang) { textBody(m.json) ?: Readable.of(m.json, lang) }
    val grid = remember(m.json) { Readable.gridRef(m.json) }
    var menu by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = bubbleShape(mine),
            color = if (mine) c.mine else MaterialTheme.colorScheme.surface,
            contentColor = if (mine) c.onMine else MaterialTheme.colorScheme.onSurface,
            border = if (mine) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.widthIn(max = maxBubbleWidth())
                .combinedClickable(onClick = { if (mine) expanded = !expanded }, onLongClick = { menu = true })
                .testTag("msg_${m.id}"),
        ) {
            Column(Modifier.padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)) {
                if (!mine) Text(senderName, style = MaterialTheme.typography.labelLarge, color = senderColor(m.node))
                Text(words.orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("msg_text_${m.id}"))
                if (grid != null) PositionLine(grid, null)
                Footer(m, mine, expanded, lang, saving = false)
            }
        }
        RowMenu(menu, words, m, actions) { menu = false }
    }
}

/** Time, and for my messages the status ticks and the byte caption. Tap a bubble of mine to read what the ticks mean. */
@Composable
private fun ColumnScope.Footer(m: MessageEntity, mine: Boolean, expanded: Boolean, lang: String, saving: Boolean) {
    val c = Rc.colors
    val muted = if (mine) c.onMine.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.padding(top = 2.dp).align(Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (mine) {
            val caption = remember(m.json, m.bytes.size, lang, saving) { captionOf(m, lang, saving) }
            Text(
                if (caption.second != null) stringResource(R.string.caption_saving, caption.first, caption.second!!) else stringResource(R.string.caption_bytes, caption.first),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = muted,
                modifier = Modifier.testTag("caption_${m.id}"),
            )
            Text("·", style = MaterialTheme.typography.bodySmall, color = muted)
        }
        Text(timeOf(m.atMs), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = muted)
        if (mine) Ticks(m.state)
    }
    if (mine) {
        val word = stateWord(m.state)
        // A state that needs attention is always in words; a normal one (sent, relayed) only when tapped.
        val loud = m.state !in setOf(MessageState.SENT, MessageState.DELIVERED)
        if (loud || expanded) {
            Text(word, style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp), color = stateColor(m.state, muted), modifier = Modifier.align(Alignment.End).testTag("state_${m.id}"))
        }
        if (expanded) {
            val note = when (m.state) {
                MessageState.NOT_CONFIRMED -> stringResource(R.string.ms_not_confirmed_note)
                MessageState.DELIVERED -> stringResource(R.string.stat_delivered)
                else -> m.detail
            }
            if (!note.isNullOrBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.align(Alignment.End))
        }
    }
}

/** (bytes sent, "4.6" times smaller) for my own message. The plain sentence of the same report is the text it is measured against. */
private fun captionOf(m: MessageEntity, lang: String, report: Boolean): Pair<Int, String?> {
    val sent = m.bytes.size
    if (!report || sent == 0) return sent to null
    val asText = Readable.of(m.json, lang)?.toByteArray(Charsets.UTF_8)?.size ?: return sent to null
    val factor = asText.toDouble() / sent
    return sent to (if (factor >= 1.5) "%.1f".format(Locale.ROOT, factor) else null)
}

@Composable
private fun stateWord(state: String): String = stringResource(
    when (state) {
        MessageState.QUEUED -> R.string.ms_queued
        MessageState.HELD -> R.string.ms_held
        MessageState.REFUSED_AIRTIME -> R.string.ms_refused
        MessageState.SENT -> R.string.ms_sent
        MessageState.DELIVERED -> R.string.ms_delivered
        MessageState.NOT_CONFIRMED -> R.string.ms_not_confirmed
        MessageState.FAILED -> R.string.ms_failed
        MessageState.EXPIRED -> R.string.ms_expired
        MessageState.CANCELLED -> R.string.ms_cancelled
        else -> R.string.feed_received
    },
)

@Composable
private fun stateColor(state: String, muted: Color): Color = when (state) {
    MessageState.FAILED -> Rc.colors.maydayText
    MessageState.NOT_CONFIRMED, MessageState.QUEUED, MessageState.HELD, MessageState.REFUSED_AIRTIME -> Rc.colors.open
    else -> muted
}

/** Status ticks of my message: clock (waiting), one tick (sent), two ticks (relayed), ! (not confirmed or failed). */
@Composable
private fun Ticks(state: String) {
    val c = Rc.colors
    val muted = c.onMine.copy(alpha = 0.72f)
    val (icon, tint) = when (state) {
        MessageState.QUEUED, MessageState.HELD, MessageState.REFUSED_AIRTIME -> R.drawable.ic_schedule to c.open
        MessageState.SENT -> R.drawable.ic_check to muted
        MessageState.DELIVERED -> R.drawable.ic_done_all to c.accepted
        MessageState.NOT_CONFIRMED -> R.drawable.ic_error_outline to c.open
        MessageState.FAILED -> R.drawable.ic_error_outline to c.maydayText
        else -> R.drawable.ic_close to muted
    }
    RcIcon(icon, stateWord(state), tint = tint, size = 18.dp)
}

@Composable
private fun PositionLine(grid: String, distance: String?) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RcIcon(R.drawable.ic_location_on, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp)
        Text(grid, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (distance != null) Text("· $distance", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Icon of a report type. A Mayday has the warning triangle, a danger the octagon. */
internal fun typeIcon(type: String): Int = when (type) {
    "found" -> R.drawable.ic_person
    "request" -> R.drawable.ic_medical_services
    "hazard" -> R.drawable.ic_report
    "mayday" -> R.drawable.ic_warning
    "sector" -> R.drawable.ic_flag
    else -> R.drawable.ic_info
}

/** The field values of a report as short words: "Child", "Conscious", "Hypothermia", "Stretcher". */
@Composable
internal fun fieldWords(msg: Map<String, Any?>, lang: String): List<String> {
    val body = ReportFields.body(msg)
    val out = ArrayList<String>()
    for (def in ReportFields.present(msg)) when (def.kind) {
        FieldKind.MANY -> (body[def.key] as? List<*>).orEmpty().forEach { out += Labels.value(lang, def.group, it.toString()).replaceFirstChar { ch -> ch.uppercase() } }
        FieldKind.ONE -> out += Labels.value(lang, def.group, body[def.key].toString()).replaceFirstChar { ch -> ch.uppercase() }
        FieldKind.NUMBER -> out += numberLabel(def, (body[def.key] as? Number)?.toInt() ?: 0)
    }
    return out
}

/**
 * A report in the stream: icon and plain title ("Person found"), the field words, position and distance, the task
 * state and the answer buttons when the person may answer. A Mayday is a red card.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ReportCard(m: MessageEntity, task: TaskItem?, lang: String, mine: Boolean, senderName: String, fix: Fix?, actions: ChatActions) {
    val c = Rc.colors
    val isMayday = m.type == "mayday"
    val msg = remember(m.json) { runCatching { Json.parseObject(m.json!!) }.getOrNull() ?: emptyMap() }
    val words = fieldWords(msg, lang)
    val grid = remember(m.json) { Readable.gridRef(m.json) }
    val distance = remember(m.json, fix) {
        val pos = msg["pos"] as? Map<*, *>
        if (fix == null || pos == null) null
        else distanceText(distanceM(fix, (pos["lat"] as Number).toDouble(), (pos["lon"] as Number).toDouble()))
    }
    val readable = remember(m.json, lang) { wordsOf(m, lang) }
    var menu by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val prio = ReportFields.priorityOf(msg)
    val container = when {
        isMayday -> c.maydaySoft
        mine -> c.mine
        else -> MaterialTheme.colorScheme.surface
    }
    val onContainer = if (mine && !isMayday) c.onMine else MaterialTheme.colorScheme.onSurface
    Box {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = container,
            contentColor = onContainer,
            border = when {
                isMayday -> BorderStroke(2.dp, c.mayday)
                mine -> null
                else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            },
            modifier = Modifier.widthIn(max = maxBubbleWidth()).fillMaxWidth()
                .combinedClickable(onClick = { if (mine) expanded = !expanded }, onLongClick = { menu = true })
                .testTag("msg_${m.id}"),
        ) {
            Column(Modifier.padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!mine) Text(senderName, style = MaterialTheme.typography.labelLarge, color = senderColor(m.node))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RcIcon(typeIcon(m.type), null, tint = if (isMayday) c.maydayText else onContainer, size = 24.dp)
                    Text(
                        if (isMayday) Labels.head(lang, "mayday").uppercase() else Labels.head(lang, m.type),
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                        color = if (isMayday) c.maydayText else onContainer,
                        modifier = Modifier.weight(1f, fill = false).testTag("card_title_${m.id}"),
                    )
                    if (!isMayday && (prio == "critical" || prio == "urgent") && msg["priority"] != null) {
                        Tag(Labels.value(lang, "priority", prio).replaceFirstChar { it.uppercase() }, if (prio == "critical") c.mayday else c.openSoft, if (prio == "critical") c.onMayday else c.open)
                    }
                }
                if (words.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        words.forEach { w ->
                            Tag(w, if (mine && !isMayday) Color.White.copy(alpha = if (c.dark) 0.10f else 0.6f) else MaterialTheme.colorScheme.surfaceContainerHigh, onContainer)
                        }
                    }
                }
                if (grid != null) PositionLine(grid, distance?.let { stringResource(R.string.distance_away, it) })
                if (task != null) TaskStatus(task, lang)
                if (task != null && task.moves.isNotEmpty()) AnswerButtons(task, actions.answer, isMayday)
                Footer(m, mine, expanded, lang, saving = true)
            }
        }
        RowMenu(menu, readable, m, actions) { menu = false }
    }
}

/** "Open", "Accepted by Trupp 3", "Done": the task line on a card. */
@Composable
private fun TaskStatus(t: TaskItem, lang: String) {
    val last = t.answers.lastOrNull { it.status == t.state || (t.state == "accepted" && it.status == "accepted") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("task_state_${t.key}")) {
        StateTag(t.state)
        if (last != null) {
            val who = last.team ?: if (last.own) stringResource(R.string.answer_you) else stringResource(R.string.answer_other_team)
            Text(who, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Accept (blue) and Done (green) at 56 dp, Decline smaller and apart. The same rules as the Tasks tab. */
@Composable
internal fun AnswerButtons(t: TaskItem, onAnswer: (TaskItem, String) -> Unit, onRed: Boolean = false) {
    val c = Rc.colors
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if ("accepted" in t.moves) BigButton(
            stringResource(R.string.action_accept), { onAnswer(t, "accepted") }, Modifier.weight(1f).testTag("accept_${t.key}"),
            icon = R.drawable.ic_check, container = if (onRed) c.mayday else c.acceptSolid, content = c.onSolid,
        )
        if ("done" in t.moves) BigButton(
            stringResource(R.string.action_done), { onAnswer(t, "done") }, Modifier.weight(1f).testTag("done_${t.key}"),
            icon = R.drawable.ic_check_circle, container = c.doneSolid, content = c.onSolid,
        )
        if ("declined" in t.moves) BigButton(
            stringResource(R.string.action_decline), { onAnswer(t, "declined") }, Modifier.testTag("decline_${t.key}"),
            outlined = true, container = MaterialTheme.colorScheme.onSurfaceVariant, height = 48.dp,
        )
    }
}

/** "Trupp 3 accepted · Person found": an answer as a small centred line. The card above shows the new state. */
@Composable
private fun AckLine(m: MessageEntity, mine: Boolean, taskType: String?, lang: String, senderName: String) {
    val status = remember(m.json) { TeamAlerts.ackStatus(m.json) }
    val who = if (mine) stringResource(R.string.answer_you) else (TeamAlerts.ackTeam(m.json) ?: senderName)
    val what = taskType?.takeIf { it != "unknown" }?.let { Labels.head(lang, it) } ?: stringResource(R.string.report_word)
    val text = when (status) {
        "accepted" -> stringResource(R.string.ack_line_accepted, who, what)
        "done" -> stringResource(R.string.ack_line_done, who, what)
        else -> stringResource(R.string.ack_line_declined, who, what)
    }
    val icon = when (status) { "done" -> R.drawable.ic_check_circle; "accepted" -> R.drawable.ic_check; else -> R.drawable.ic_close }
    val suffix = if (mine && m.state in MessageState.WAITING) " · " + stringResource(R.string.ms_queued) else ""
    SystemLine(text + suffix, icon, Modifier.testTag("ack_${m.id}"))
}

/** A small centred line on the chat ground: answers, day changes, track points. */
@Composable
fun SystemLine(text: String, icon: Int? = null, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) RcIcon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

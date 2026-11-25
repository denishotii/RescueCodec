// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
import org.rescuecodec.app.ui.components.GridChip
import org.rescuecodec.app.ui.components.PlainTag
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
    /** Long-press: the sheet with Read aloud, Show position, Copy text, Details. */
    val menu: (MessageEntity) -> Unit,
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

/**
 * The line of a report card, without the position (the Grid chip shows it) and without the head word (the type tag
 * shows it): "Child, conscious, hypothermia. Needs stretcher."
 */
internal fun cardLine(json: String?, type: String, lang: String): String? {
    val full = Readable.withoutPosition(json, lang) ?: return null
    val head = Labels.head(lang, type)
    val rest = if (type != "mayday" && full.startsWith(head, ignoreCase = true)) full.substring(head.length).trimStart(':', ' ', ',', '.') else full
    return rest.ifBlank { full }.replaceFirstChar { it.uppercase() }
}

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

/** Compass direction from [a] to the point: N, NE, E, ... */
internal fun bearingWord(a: Fix, lat: Double, lon: Double): String {
    val p1 = Math.toRadians(a.lat)
    val p2 = Math.toRadians(lat)
    val dl = Math.toRadians(lon - a.lon)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    val deg = (Math.toDegrees(atan2(y, x)) + 360) % 360
    return listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((deg + 22.5) / 45).toInt() % 8]
}

internal fun distanceText(m: Double): String = if (m < 1000) "${(m / 10).toInt() * 10} m" else "%.1f km".format(Locale.ROOT, m / 1000)

/** "450 m NE" from our position to the report, or null without a position. */
internal fun distanceOf(json: String?, fix: Fix?): String? {
    if (fix == null || json == null) return null
    return runCatching {
        val pos = Json.parseObject(json)["pos"] as? Map<*, *> ?: return null
        val lat = (pos["lat"] as Number).toDouble()
        val lon = (pos["lon"] as Number).toDouble()
        "${distanceText(distanceM(fix, lat, lon))} ${bearingWord(fix, lat, lon)}"
    }.getOrNull()
}

/** Overnight bubbles: 10 dp corners, the corner at the sender's side almost square. */
private fun bubbleShape(mine: Boolean) = if (mine) RoundedCornerShape(10.dp, 3.dp, 10.dp, 10.dp) else RoundedCornerShape(3.dp, 10.dp, 10.dp, 10.dp)

@Composable
private fun maxBubbleWidth() = (LocalConfiguration.current.screenWidthDp * 0.86f).dp

/** A row that holds one bubble or card, on the right for mine and on the left for others. */
@Composable
private fun Side(mine: Boolean, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) { content() }
}

/**
 * A 48 dp touch target that takes only [visual] of height in the layout, so a small icon in a text line stays easy to
 * hit with a glove without making the line taller.
 */
private fun Modifier.compactTouch(visual: Dp): Modifier = this.layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val h = visual.roundToPx()
    layout(p.width, h) { p.place(0, (h - p.height) / 2) }
}

/** The small speaker: tap to hear the message read aloud. */
@Composable
private fun SpeakerIcon(words: String?, m: MessageEntity, actions: ChatActions) {
    if (words == null) return
    val desc = stringResource(R.string.action_read_aloud)
    Box(
        Modifier.compactTouch(20.dp).size(44.dp).clickable(role = Role.Button, onClickLabel = desc) { actions.speak(m, words) }.testTag("speak_${m.id}"),
        contentAlignment = Alignment.Center,
    ) { RcIcon(R.drawable.ic_volume_up, desc, tint = Rc.colors.muted, size = 18.dp) }
}

/** One message in the stream: a text bubble, a report card, or a small line (answers, track points). */
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
        m.type == "ack" -> AckLine(m, mine, task, taskType, lang, senderName)
        m.type in CARD_TYPES -> Side(mine) { ReportCard(m, task, lang, mine, senderName, fix, actions) }
        m.type == "track" -> SystemLine("${Labels.head(lang, "track")} · $senderName", R.drawable.ic_location_on, Modifier.testTag("msg_${m.id}"))
        else -> Side(mine) { TextBubble(m, lang, mine, senderName, actions) }
    }
}

/** "D-Node 7  12:02  [speaker]": the first line of a message from someone else. */
@Composable
private fun WhoLine(name: String, m: MessageEntity, words: String?, actions: ChatActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), color = senderColor(m.node), maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        Text(timeOf(m.atMs), style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp), color = Rc.colors.muted)
        Box(Modifier.weight(1f))
        SpeakerIcon(words, m, actions)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TextBubble(m: MessageEntity, lang: String, mine: Boolean, senderName: String, actions: ChatActions) {
    val c = Rc.colors
    val words = remember(m.json, lang) { textBody(m.json) ?: Readable.of(m.json, lang) }
    val grid = remember(m.json) { Readable.gridRef(m.json) }
    Surface(
        shape = bubbleShape(mine),
        color = if (mine) c.mine else MaterialTheme.colorScheme.surface,
        contentColor = if (mine) c.onMine else MaterialTheme.colorScheme.onSurface,
        border = if (mine) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.widthIn(max = maxBubbleWidth())
            .combinedClickable(onClick = {}, onLongClick = { actions.menu(m) })
            .testTag("msg_${m.id}"),
    ) {
        Column(Modifier.padding(start = 11.dp, end = 11.dp, top = 8.dp, bottom = 8.dp)) {
            if (!mine) WhoLine(senderName, m, words, actions)
            Text(words.orEmpty(), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp), modifier = Modifier.testTag("msg_text_${m.id}"))
            if (grid != null) GridChip(grid)
            if (mine) MineFooter(m, words, actions)
            else if (org.rescuecodec.app.core.MeshText.isPlain(m)) ViaMeshtastic(m)
        }
    }
}

@Composable
private fun ViaMeshtastic(m: MessageEntity) {
    Text(stringResource(R.string.via_meshtastic), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = Rc.colors.muted, modifier = Modifier.padding(top = 2.dp).testTag("via_meshtastic_${m.id}"))
}

/** Under my own message: speaker, time and the state as a tag (Delivered, Sent, Waiting, Not confirmed). */
@Composable
private fun MineFooter(m: MessageEntity, words: String?, actions: ChatActions) {
    Row(
        Modifier.fillMaxWidth().padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
    ) {
        if (org.rescuecodec.app.core.MeshText.isPlain(m)) {
            Text(stringResource(R.string.via_meshtastic), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = Rc.colors.muted, modifier = Modifier.testTag("via_meshtastic_${m.id}"))
        }
        SpeakerIcon(words, m, actions)
        Text(timeOf(m.atMs), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = Rc.colors.muted)
        StateWordTag(m.state, Modifier.testTag("state_${m.id}"))
    }
}

/** The state of my message as a small tag. Colour and words: green relayed, grey sent, amber waiting, red failed. */
@Composable
fun StateWordTag(state: String, modifier: Modifier = Modifier) {
    val c = Rc.colors
    val grey = MaterialTheme.colorScheme.surfaceContainerHighest
    val ink = MaterialTheme.colorScheme.onSurface
    when (state) {
        MessageState.DELIVERED -> Tag(stringResource(R.string.ms_delivered_short), c.doneSoft, c.done, modifier)
        MessageState.SENT -> Tag(stringResource(R.string.ms_sent_short), grey, ink, modifier)
        MessageState.QUEUED, MessageState.HELD, MessageState.REFUSED_AIRTIME -> Tag(stringResource(R.string.ms_waiting_short), c.openSoft, c.open, modifier)
        MessageState.NOT_CONFIRMED -> Tag(stringResource(R.string.ms_not_confirmed), c.openSoft, c.open, modifier)
        MessageState.FAILED -> Tag(stringResource(R.string.ms_failed_short), c.mayday, c.onMayday, modifier)
        MessageState.EXPIRED -> Tag(stringResource(R.string.ms_expired), grey, ink, modifier)
        MessageState.CANCELLED -> Tag(stringResource(R.string.ms_cancelled), grey, ink, modifier)
        else -> Unit
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

/** The field values of a report as short words: "child", "conscious", "hypothermia", "stretcher". */
@Composable
internal fun fieldWords(msg: Map<String, Any?>, lang: String): List<String> {
    val body = ReportFields.body(msg)
    val out = ArrayList<String>()
    for (def in ReportFields.present(msg)) when (def.kind) {
        FieldKind.MANY -> (body[def.key] as? List<*>).orEmpty().forEach { out += Labels.value(lang, def.group, it.toString()) }
        FieldKind.ONE -> out += Labels.value(lang, def.group, body[def.key].toString())
        FieldKind.NUMBER -> out += numberLabel(def, (body[def.key] as? Number)?.toInt() ?: 0)
    }
    return out
}

/**
 * A report in the stream, as an overnight card: the type and state tags, the readable line, the Grid chip with
 * distance, and Accept and Decline when the person may answer. A Mayday is a red card.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ReportCard(m: MessageEntity, task: TaskItem?, lang: String, mine: Boolean, senderName: String, fix: Fix?, actions: ChatActions) {
    val c = Rc.colors
    val isMayday = m.type == "mayday"
    val msg = remember(m.json) { runCatching { Json.parseObject(m.json!!) }.getOrNull() ?: emptyMap() }
    val line = remember(m.json, lang) { cardLine(m.json, m.type, lang) }
    val grid = remember(m.json) { Readable.gridRef(m.json) }
    val distance = remember(m.json, fix) { distanceOf(m.json, fix) }
    val readable = remember(m.json, lang) { wordsOf(m, lang) }
    val prio = ReportFields.priorityOf(msg)
    val container = when {
        isMayday -> c.maydaySoft
        mine -> c.mine
        else -> MaterialTheme.colorScheme.surface
    }
    Surface(
        shape = bubbleShape(mine),
        color = container,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = when {
            isMayday -> BorderStroke(1.5.dp, c.mayday)
            mine -> null
            else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        modifier = Modifier.widthIn(max = maxBubbleWidth()).fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = { actions.menu(m) })
            .testTag("msg_${m.id}"),
    ) {
        Column(Modifier.padding(start = 11.dp, end = 11.dp, top = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (!mine) WhoLine(senderName, m, readable, actions)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (isMayday) Tag("⚠ " + Labels.head(lang, "mayday").uppercase(), c.mayday, c.onMayday, Modifier.testTag("card_title_${m.id}"))
                else PlainTag(Labels.head(lang, m.type), Modifier.testTag("card_title_${m.id}"))
                if (task != null) StateTag(task.state, Modifier.testTag("task_state_${task.key}"))
                if (!isMayday && (prio == "critical" || prio == "urgent") && msg["priority"] != null) {
                    Tag(Labels.value(lang, "priority", prio).replaceFirstChar { it.uppercase() }, if (prio == "critical") c.mayday else c.openSoft, if (prio == "critical") c.onMayday else c.open)
                }
            }
            if (line != null) Text(line, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp))
            if (grid != null) GridChip(grid, distance = distance)
            if (task != null) TaskWho(task)
            if (task != null && task.moves.isNotEmpty()) AnswerButtons(task, actions.answer)
            if (mine) MineFooter(m, readable, actions)
        }
    }
}

/** "Accepted by Trupp 3" under a card, when someone answered. */
@Composable
private fun TaskWho(t: TaskItem) {
    val last = t.answers.lastOrNull { it.status == t.state } ?: return
    if (t.state == "open") return
    val who = last.team ?: if (last.own) stringResource(R.string.answer_you) else stringResource(R.string.answer_other_team)
    val word = when (t.state) {
        "accepted" -> stringResource(R.string.answer_accepted_by, who)
        "done" -> stringResource(R.string.answer_done_by, who)
        else -> stringResource(R.string.answer_declined_by, who)
    }
    Text(word, style = MaterialTheme.typography.bodyMedium, color = Rc.colors.muted)
}

/** Accept (blue) and Done (ink) at 48 dp, Decline as an outline beside them. The same rules as the Tasks tab. */
@Composable
internal fun AnswerButtons(t: TaskItem, onAnswer: (TaskItem, String) -> Unit) {
    val c = Rc.colors
    val hasMain = "accepted" in t.moves || "done" in t.moves
    Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if ("accepted" in t.moves) BigButton(
            stringResource(R.string.action_accept), { onAnswer(t, "accepted") }, Modifier.weight(1f).testTag("accept_${t.key}"),
            icon = R.drawable.ic_check, container = c.acceptSolid, content = c.onSolid, height = 48.dp,
        )
        if ("done" in t.moves) BigButton(
            stringResource(R.string.action_done), { onAnswer(t, "done") }, Modifier.weight(1f).testTag("done_${t.key}"),
            icon = R.drawable.ic_check, container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary, height = 48.dp,
        )
        // Decline is narrower and apart, so a glove does not hit it by mistake.
        if ("declined" in t.moves) BigButton(
            stringResource(R.string.action_decline), { onAnswer(t, "declined") }, Modifier.weight(if (hasMain) 0.6f else 1f).testTag("decline_${t.key}"),
            outlined = true, container = c.ink3, height = 48.dp,
        )
    }
}

/**
 * An answer as one small line: green with a check for accepted and done, grey for declined.
 * "Base accepted your Hazard · ETA 10 min".
 */
@Composable
private fun AckLine(m: MessageEntity, mine: Boolean, task: TaskItem?, taskType: String?, lang: String, senderName: String) {
    val c = Rc.colors
    val status = remember(m.json) { TeamAlerts.ackStatus(m.json) }
    val eta = remember(m.json) { runCatching { ((Json.parseObject(m.json!!)["ack"] as? Map<*, *>)?.get("eta") as? Number)?.toInt() }.getOrNull() }
    val who = if (mine) stringResource(R.string.answer_you) else (TeamAlerts.ackTeam(m.json) ?: senderName)
    val what = taskType?.takeIf { it != "unknown" }?.let { Labels.head(lang, it) } ?: stringResource(R.string.report_word)
    val text = when {
        status == "accepted" && !mine && task?.local == true -> stringResource(R.string.ack_line_yours, who, what)
        status == "accepted" -> stringResource(R.string.ack_line_accepted, who, what)
        status == "done" -> stringResource(R.string.ack_line_done, who, what)
        else -> stringResource(R.string.ack_line_declined, who, what)
    } + (eta?.takeIf { it > 0 && status == "accepted" }?.let { " · " + stringResource(R.string.ack_eta, it) } ?: "")
    val suffix = if (mine && m.state in MessageState.WAITING) " · " + stringResource(R.string.ms_waiting_short) else ""
    val color = if (status == "declined") c.muted else c.done
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).testTag("ack_${m.id}"),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RcIcon(if (status == "declined") R.drawable.ic_close else R.drawable.ic_check, null, tint = color, size = 16.dp)
        Text(text + suffix, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = color, textAlign = TextAlign.Center)
    }
}

/** A small centred line on the chat ground: day changes, track points. */
@Composable
fun SystemLine(text: String, icon: Int? = null, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) RcIcon(icon, null, tint = Rc.colors.ink3, size = 16.dp)
            Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = Rc.colors.ink3, textAlign = TextAlign.Center)
        }
    }
}

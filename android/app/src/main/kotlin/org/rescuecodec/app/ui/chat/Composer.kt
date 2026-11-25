// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.chat

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.rescuecodec.app.R
import org.rescuecodec.app.core.FieldKind
import org.rescuecodec.app.core.Labels
import org.rescuecodec.app.core.ReportFields
import org.rescuecodec.app.ui.Permissions
import org.rescuecodec.app.ui.ReportUi
import org.rescuecodec.app.ui.ReportViewModel
import org.rescuecodec.app.ui.components.ComposerMic
import org.rescuecodec.app.ui.components.RcChip
import org.rescuecodec.app.ui.components.RcIcon
import org.rescuecodec.app.ui.screens.Editor
import org.rescuecodec.app.ui.screens.FieldEditorSheet
import org.rescuecodec.app.ui.screens.VoiceHelpDialog
import org.rescuecodec.app.ui.screens.numberLabel
import org.rescuecodec.app.ui.theme.Rc
import org.rescuecodec.app.voice.VoiceState

/**
 * The composer at the bottom of every chat, overnight style. While the field is empty: the quick buttons
 * (+ Person found, + Need, + Hazard, + Sector) above it and the dark mic with its ring (hold to talk) beside it.
 * As soon as the text becomes a report: "What will be sent" with the chips (type in ink with a pencil, the fields with
 * a cross, + Add), "Send as plain text instead" and the green byte line ("105 bytes as text → 23 bytes sent"; for a
 * text "Compressed for RescueCodec · 39 B instead of 81 B" or "Normal Meshtastic text · readable by everyone"); Send
 * beside the field. [textOnly] (direct messages, the public channel): plain text only, no quick buttons, no chips.
 */
@Composable
fun Composer(vm: ReportViewModel, lang: String, textOnly: Boolean, onSent: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val voice by vm.speech.state.collectAsStateWithLifecycle()
    val heard by vm.speech.text.collectAsStateWithLifecycle()
    val level by vm.speech.level.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editor by remember { mutableStateOf<Editor?>(null) }
    var showVoiceHelp by remember { mutableStateOf(false) }

    LaunchedEffect(lang) { vm.prepareVoice() }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.prepareVoice() }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val listening = voice is VoiceState.Listening
    val micReady = voice is VoiceState.Ready || listening
    val msg = ui.message
    val hasText = ui.text.isNotBlank()

    Column(Modifier.fillMaxWidth().testTag("composer")) {
        when {
            hasText && msg != null && !listening && !textOnly -> WhatWillBeSent(ui, lang, onEdit = { editor = it }, onPlain = {
                vm.edit { m -> ReportFields.setType(m, "text", vm.ui.value.text) }
            })
            hasText && msg != null && !listening -> ByteLine(ui, text = true)
            !hasText && !listening && !textOnly -> QuickButtons(onPick = vm::append)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = if (hasText) Alignment.Bottom else Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val shown = if (listening) heard else ui.text
            val ink = MaterialTheme.colorScheme.onSurface
            BasicTextField(
                value = shown,
                onValueChange = { if (!listening) vm.onText(it) },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("report_text"),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 23.sp, color = ink),
                cursorBrush = SolidColor(ink),
                maxLines = 5,
                decorationBox = { inner ->
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                            .border(2.dp, ink, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (shown.isEmpty()) Text(
                            stringResource(if (listening) R.string.composer_listening else R.string.composer_hint),
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), color = Rc.colors.muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        inner()
                    }
                },
            )
            if (!hasText || listening) {
                ComposerMic(
                    listening = listening, level = level, ready = micReady,
                    contentDescription = stringResource(R.string.mic_hold),
                    onPress = {
                        val hasMic = Permissions.granted(context, Manifest.permission.RECORD_AUDIO)
                        when {
                            !hasMic -> {
                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                if (!Permissions.granted(context, Manifest.permission.ACCESS_FINE_LOCATION)) locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                            !micReady -> showVoiceHelp = true
                            else -> if (!vm.holdStart()) showVoiceHelp = true
                        }
                    },
                    onRelease = { if (listening) vm.holdEnd() },
                )
            } else {
                val enabled = msg != null && ui.problem == null && !ui.busy
                SendButton(enabled) { vm.send(); onSent() }
            }
        }
    }

    editor?.let { e -> FieldEditorSheet(e, ui.message.orEmpty(), lang, vm, onClose = { editor = null }, onNext = { editor = it }) }
    if (showVoiceHelp) VoiceHelpDialog(voice, onDismiss = { showVoiceHelp = false })
}

/** The ink Send button: arrow and the word, 52 dp. */
@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val bg = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val label = stringResource(R.string.action_send)
    Surface(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(10.dp), color = bg,
        modifier = Modifier.heightIn(min = 52.dp).semantics { contentDescription = label }.testTag("send_button"),
    ) {
        Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RcIcon(R.drawable.ic_send, null, tint = fg, size = 22.dp)
            Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 18.sp), color = fg)
        }
    }
}

/**
 * "105 bytes as text → 23 bytes sent", in green. For a text how it goes (spec section 2.3): "Compressed for
 * RescueCodec · 39 B instead of 81 B" or "Normal Meshtastic text · readable by everyone".
 */
@Composable
private fun ByteLine(ui: ReportUi, text: Boolean) {
    if (ui.sentBytes <= 0) return
    val line = when {
        text && ui.packed -> stringResource(R.string.text_mode_packed, ui.sentBytes, ui.plainBytes)
        text -> stringResource(R.string.text_mode_plain)
        else -> stringResource(R.string.counter_line, ui.textBytes, ui.sentBytes)
    }
    Text(
        line,
        style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        color = Rc.colors.done,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp).semantics { contentDescription = line }.testTag("byte_counter"),
    )
}

/** What will be sent, with chips to change it, like the overnight Report screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhatWillBeSent(ui: ReportUi, lang: String, onEdit: (Editor) -> Unit, onPlain: () -> Unit) {
    val c = Rc.colors
    val msg = ui.message ?: return
    val type = ReportFields.typeOf(msg)
    val isText = type == "text"
    Column(Modifier.fillMaxWidth().testTag("preview_strip")) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.composer_what),
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 23.sp),
                modifier = Modifier.padding(top = 6.dp, start = 2.dp).testTag("preview_title"),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RcChip(
                    if (isText) stringResource(R.string.preview_text) else Labels.head(lang, type), onClick = { onEdit(Editor.Type) },
                    selected = true, emphasis = if (type == "mayday") c.mayday else null, trailing = R.drawable.ic_edit,
                    modifier = Modifier.testTag("chip_type"),
                )
                if (!isText) {
                    val prio = ReportFields.priorityOf(msg)
                    if (msg["priority"] != null) RcChip(
                        Labels.value(lang, "priority", prio).uppercase(), onClick = { onEdit(Editor.Priority) },
                        selected = prio == "critical" || prio == "urgent", emphasis = if (prio == "critical") c.mayday else if (prio == "urgent") c.openSolid else null,
                        trailing = R.drawable.ic_close, modifier = Modifier.testTag("chip_priority"),
                    )
                    val body = ReportFields.body(msg)
                    ReportFields.present(msg).forEach { def ->
                        when (def.kind) {
                            FieldKind.MANY -> (body[def.key] as? List<*>).orEmpty().forEach { code ->
                                RcChip(Labels.value(lang, def.group, code.toString()), onClick = { onEdit(Editor.Field(def)) }, trailing = R.drawable.ic_close, modifier = Modifier.testTag("chip_${def.key}_$code"))
                            }
                            FieldKind.ONE -> RcChip(Labels.value(lang, def.group, body[def.key].toString()), onClick = { onEdit(Editor.Field(def)) }, trailing = R.drawable.ic_close, modifier = Modifier.testTag("chip_${def.key}"))
                            FieldKind.NUMBER -> RcChip(numberLabel(def, (body[def.key] as? Number)?.toInt() ?: 0), onClick = { onEdit(Editor.Field(def)) }, trailing = R.drawable.ic_close, modifier = Modifier.testTag("chip_${def.key}"))
                        }
                    }
                    AddChip { onEdit(Editor.Add) }
                }
            }
            if (!isText) {
                Text(
                    stringResource(R.string.send_as_text),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, textDecoration = TextDecoration.Underline),
                    color = c.ink3,
                    modifier = Modifier.heightIn(min = 40.dp).clickable(role = Role.Button, onClick = onPlain).padding(horizontal = 2.dp, vertical = 10.dp).testTag("send_as_text"),
                )
            }
            val notes = buildList {
                if (ui.shortened) add(stringResource(R.string.report_text_shortened) to false)
                if (ui.fallback && isText) add(stringResource(R.string.preview_fallback) to false)
                ui.problem?.let { add(stringResource(R.string.report_problem, it) to true) }
            }
            notes.forEach { (n, bad) ->
                Text(n, style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp), color = if (bad) c.maydayText else c.ink3, modifier = Modifier.padding(horizontal = 2.dp).testTag(if (bad) "report_problem" else "text_shortened"))
            }
        }
        ByteLine(ui, text = isText)
    }
}

/** "+ Add" with a dashed outline: adds a field the text did not have. */
@Composable
private fun AddChip(onClick: () -> Unit) {
    val ring = Rc.colors.ring
    Surface(
        onClick = onClick, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.heightIn(min = 48.dp).drawBehind {
            drawRoundRect(ring, cornerRadius = CornerRadius(size.height / 2), style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
        }.testTag("chip_add"),
    ) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RcIcon(R.drawable.ic_add, null, size = 18.dp)
            Text(stringResource(R.string.report_add_field), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Quick buttons while the field is empty: one tap starts a report of that kind, the extractor reads it like typed text. */
@Composable
private fun QuickButtons(onPick: (String) -> Unit) {
    val quick = listOf(R.string.quick_person_found, R.string.quick_need, R.string.quick_hazard, R.string.quick_sector).map { stringResource(it) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp).testTag("quick_chips"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        quick.forEach { phrase -> RcChip(phrase, onClick = { onPick(phrase) }, leading = R.drawable.ic_add) }
    }
}

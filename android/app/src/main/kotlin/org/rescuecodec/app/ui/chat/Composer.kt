// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.chat

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.rescuecodec.app.R
import org.rescuecodec.app.core.FieldKind
import org.rescuecodec.app.core.Labels
import org.rescuecodec.app.core.ReportFields
import org.rescuecodec.app.ui.Permissions
import org.rescuecodec.app.ui.ReportViewModel
import org.rescuecodec.app.ui.components.ComposerMic
import org.rescuecodec.app.ui.components.RcChip
import org.rescuecodec.app.ui.components.RcIcon
import org.rescuecodec.app.ui.components.SosButton
import org.rescuecodec.app.ui.components.SosHoldStrip
import org.rescuecodec.app.ui.screens.Editor
import org.rescuecodec.app.ui.screens.FieldEditorSheet
import org.rescuecodec.app.ui.screens.VoiceHelpDialog
import org.rescuecodec.app.ui.screens.numberLabel
import org.rescuecodec.app.ui.theme.Rc
import org.rescuecodec.app.voice.VoiceState

/**
 * The composer: SOS on the left, one field ("Message or report"), and on the right the mic (hold to talk) while the
 * field is empty or Send once there is text. While typing, a slim strip above the field shows how it will go out:
 * "Report · Person found · 23 B (as text 105 B)" with chips to change or remove a field, or "Text · 37 B".
 */
@Composable
fun Composer(vm: ReportViewModel, lang: String, onSos: () -> Unit, onSent: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val voice by vm.speech.state.collectAsStateWithLifecycle()
    val heard by vm.speech.text.collectAsStateWithLifecycle()
    val level by vm.speech.level.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editor by remember { mutableStateOf<Editor?>(null) }
    var showVoiceHelp by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val sosProgress = remember { mutableFloatStateOf(0f) }
    var sosHolding by remember { mutableStateOf(false) }

    LaunchedEffect(lang) { vm.prepareVoice() }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.prepareVoice() }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val listening = voice is VoiceState.Listening
    val micReady = voice is VoiceState.Ready || listening
    val msg = ui.message
    val hasText = ui.text.isNotBlank()

    Column(Modifier.fillMaxWidth().testTag("composer")) {
        when {
            sosHolding || sosProgress.floatValue >= 1f -> SosHoldStrip(sosProgress.floatValue)
            hasText && msg != null && !listening -> PreviewStrip(ui, lang, onEdit = { editor = it })
            focused && !hasText && !listening -> QuickPhrases(onPick = vm::append)
        }
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SosButton(onConfirm = onSos, progress = sosProgress, onHoldingChange = { sosHolding = it })
                val shown = if (listening) heard else ui.text
                TextField(
                    value = shown,
                    onValueChange = { if (!listening) vm.onText(it) },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).onFocusChanged { focused = it.isFocused }.testTag("report_text"),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    placeholder = {
                        Text(
                            stringResource(if (listening) R.string.composer_listening else R.string.composer_hint),
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    maxLines = 5,
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
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
    }

    editor?.let { e -> FieldEditorSheet(e, ui.message.orEmpty(), lang, vm, onClose = { editor = null }, onNext = { editor = it }) }
    if (showVoiceHelp) VoiceHelpDialog(voice, onDismiss = { showVoiceHelp = false })
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val bg = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val label = stringResource(R.string.action_send)
    Surface(
        onClick = onClick, enabled = enabled, shape = CircleShape, color = bg,
        modifier = Modifier.size(56.dp).semantics { contentDescription = label }.testTag("send_button"),
    ) {
        Box(contentAlignment = Alignment.Center) { RcIcon(R.drawable.ic_send, null, tint = fg, size = 26.dp) }
    }
}

/** How the message will go out, with chips to change it. Tap the first line to see what the others will read. */
@Composable
private fun PreviewStrip(ui: org.rescuecodec.app.ui.ReportUi, lang: String, onEdit: (Editor) -> Unit) {
    val c = Rc.colors
    val msg = ui.message ?: return
    val type = ReportFields.typeOf(msg)
    val isText = type == "text"
    var showRead by remember { mutableStateOf(false) }
    val title = if (isText) stringResource(R.string.preview_text) else stringResource(R.string.preview_report, Labels.head(lang, type))
    val size = when {
        ui.sentBytes <= 0 -> ""
        isText -> stringResource(R.string.preview_bytes_only, ui.sentBytes)
        else -> stringResource(R.string.preview_bytes, ui.sentBytes, ui.textBytes)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag("preview_strip")) {
        Column(Modifier.padding(top = 6.dp, bottom = 6.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { showRead = !showRead }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RcIcon(if (isText) R.drawable.ic_forum else typeIcon(type), null, tint = if (type == "mayday") c.maydayText else MaterialTheme.colorScheme.onSurface, size = 20.dp)
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = if (type == "mayday") c.maydayText else MaterialTheme.colorScheme.onSurface)) { append(title) }
                        if (size.isNotEmpty()) append(" · $size")
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("preview_title"),
                )
                RcIcon(if (showRead) R.drawable.ic_expand_more else R.drawable.ic_expand_less, stringResource(if (showRead) R.string.preview_hide_read else R.string.preview_show_read), tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 22.dp)
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RcChip(
                    if (isText) stringResource(R.string.preview_text) else Labels.head(lang, type), onClick = { onEdit(Editor.Type) },
                    selected = true, emphasis = if (type == "mayday") c.mayday else null, trailing = R.drawable.ic_edit,
                    modifier = Modifier.testTag("chip_type"),
                )
                if (!isText) {
                    val prio = ReportFields.priorityOf(msg)
                    if (msg["priority"] != null) RcChip(
                        Labels.value(lang, "priority", prio).replaceFirstChar { it.uppercase() }, onClick = { onEdit(Editor.Priority) },
                        selected = prio == "critical" || prio == "urgent", emphasis = if (prio == "critical") c.mayday else if (prio == "urgent") c.openSolid else null,
                        modifier = Modifier.testTag("chip_priority"),
                    )
                    val body = ReportFields.body(msg)
                    ReportFields.present(msg).forEach { def ->
                        when (def.kind) {
                            FieldKind.MANY -> (body[def.key] as? List<*>).orEmpty().forEach { code ->
                                RcChip(Labels.value(lang, def.group, code.toString()).replaceFirstChar { it.uppercase() }, onClick = { onEdit(Editor.Field(def)) }, modifier = Modifier.testTag("chip_${def.key}_$code"))
                            }
                            FieldKind.ONE -> RcChip(Labels.value(lang, def.group, body[def.key].toString()).replaceFirstChar { it.uppercase() }, onClick = { onEdit(Editor.Field(def)) }, modifier = Modifier.testTag("chip_${def.key}"))
                            FieldKind.NUMBER -> RcChip(numberLabel(def, (body[def.key] as? Number)?.toInt() ?: 0), onClick = { onEdit(Editor.Field(def)) }, modifier = Modifier.testTag("chip_${def.key}"))
                        }
                    }
                    if (msg["priority"] == null) RcChip(
                        stringResource(R.string.sheet_priority), onClick = { onEdit(Editor.Priority) }, modifier = Modifier.testTag("chip_priority"),
                    )
                    if (ReportFields.missing(msg).isNotEmpty()) {
                        RcChip(stringResource(R.string.report_add_field), onClick = { onEdit(Editor.Add) }, leading = R.drawable.ic_add, modifier = Modifier.testTag("chip_add"))
                    }
                }
            }
            val notes = buildList {
                if (ui.shortened) add(stringResource(R.string.report_text_shortened) to false)
                ui.problem?.let { add(stringResource(R.string.report_problem, it) to true) }
            }
            notes.forEach { (n, bad) ->
                Text(n, style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp), color = if (bad) c.maydayText else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag(if (bad) "report_problem" else "text_shortened"))
            }
            if (showRead && ui.readable.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.report_receivers_read), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(ui.readable, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("report_readable"))
                }
            }
        }
    }
}

/** Quick phrases while the field is empty: one tap adds the words, the extractor reads them like typed text. */
@Composable
private fun QuickPhrases(onPick: (String) -> Unit) {
    val quick = listOf(
        R.string.quick_child, R.string.quick_adult, R.string.quick_conscious, R.string.quick_not_breathing,
        R.string.quick_hypothermia, R.string.quick_cannot_walk, R.string.quick_stretcher, R.string.quick_helicopter,
        R.string.quick_medic, R.string.quick_path_blocked,
    ).map { stringResource(it) }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp).testTag("quick_chips"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        quick.forEach { phrase -> RcChip(phrase, onClick = { onPick(phrase) }, leading = R.drawable.ic_add) }
    }
}

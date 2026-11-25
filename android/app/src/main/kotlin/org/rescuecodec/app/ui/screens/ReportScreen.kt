// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.rescuecodec.app.R
import org.rescuecodec.app.core.FieldDef
import org.rescuecodec.app.core.FieldKind
import org.rescuecodec.app.core.Labels
import org.rescuecodec.app.core.ReportFields
import org.rescuecodec.app.ui.Permissions
import org.rescuecodec.app.ui.ReportEvent
import org.rescuecodec.app.ui.ReportViewModel
import org.rescuecodec.app.ui.components.BigButton
import org.rescuecodec.app.ui.components.MicButton
import org.rescuecodec.app.ui.components.MeterBar
import org.rescuecodec.app.ui.components.RcCard
import org.rescuecodec.app.ui.components.RcChip
import org.rescuecodec.app.ui.components.RcIcon
import org.rescuecodec.app.ui.theme.BarlowCondensed
import org.rescuecodec.app.ui.theme.Rc
import org.rescuecodec.app.voice.VoiceState

/**
 * Report: say or type what you found, check the fields, send.
 * The bottom row holds the two big controls (hold to talk, Send) where a thumb reaches them.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(vm: ReportViewModel, snackbar: SnackbarHostState, lang: String) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val voice by vm.speech.state.collectAsStateWithLifecycle()
    val heard by vm.speech.text.collectAsStateWithLifecycle()
    val level by vm.speech.level.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editor by remember { mutableStateOf<Editor?>(null) }
    var showVoiceHelp by remember { mutableStateOf(false) }

    LaunchedEffect(lang) { vm.prepareVoice() }

    val queuedText = stringResource(R.string.report_queued)
    val rejectedText = stringResource(R.string.report_rejected)
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is ReportEvent.Queued -> snackbar.showSnackbar(queuedText.format(e.sentBytes))
                is ReportEvent.Rejected -> snackbar.showSnackbar(rejectedText.format(e.message))
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.prepareVoice()
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val msg = ui.message
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // With the keyboard up there is little room: the panel shrinks to the counter and Send.
    val imeUp = WindowInsets.isImeVisible
    val listening = voice is VoiceState.Listening
    val micReady = voice is VoiceState.Ready || listening

    Column(Modifier.fillMaxSize()) {
        // The words stay in view while the fields below scroll.
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
            // 1. The words
            val shown = if (listening) heard else ui.text
            OutlinedTextField(
                value = shown,
                onValueChange = { if (!listening) vm.onText(it) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp).testTag("report_text"),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 21.sp, lineHeight = 29.sp),
                placeholder = {
                    Text(stringResource(if (listening) R.string.report_listening else R.string.report_hint), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                minLines = 2,
                maxLines = 4,
                shape = MaterialTheme.shapes.large,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
                trailingIcon = {
                    if (shown.isNotEmpty() && !listening) {
                        Surface(onClick = vm::clear, modifier = Modifier.size(48.dp).testTag("report_clear"), color = androidx.compose.ui.graphics.Color.Transparent, shape = MaterialTheme.shapes.medium) {
                            Box(contentAlignment = Alignment.Center) { RcIcon(R.drawable.ic_close, stringResource(R.string.action_clear)) }
                        }
                    }
                },
            )
        }
        // 2. Quick chips: one row that scrolls sideways, with a fade at the end so a cut chip reads as "more".
        val quick = listOf(
            R.string.quick_child, R.string.quick_adult, R.string.quick_conscious, R.string.quick_not_breathing,
            R.string.quick_hypothermia, R.string.quick_cannot_walk, R.string.quick_stretcher, R.string.quick_helicopter,
            R.string.quick_medic, R.string.quick_path_blocked,
        ).map { stringResource(it) }
        val fade = MaterialTheme.colorScheme.background
        Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("quick_chips"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                quick.forEach { phrase -> RcChip(phrase, onClick = { vm.append(phrase) }, leading = R.drawable.ic_add) }
            }
            Box(
                Modifier.align(Alignment.CenterEnd).width(40.dp).height(48.dp)
                    .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(fade.copy(alpha = 0f), fade))),
            )
        }

        // 3. What will be sent: the region that scrolls and takes the room that is left.
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (msg != null) {
                WhatWillBeSent(ui.edited, ui.fallback, ui.problem, ui.readable, ui.sentBytes, lang, msg, onEdit = { focus.clearFocus(); editor = it }, onReadAgain = vm::readAgain)
            }
            Spacer(Modifier.height(4.dp))
        }

        // Bottom controls: the size on one line, then the two big controls where a thumb reaches them.
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
          Column {
            ByteCounter(ui.textBytes, ui.sentBytes, Modifier.padding(horizontal = 16.dp), compact = true)
            if (ui.shortened) {
                Text(
                    stringResource(R.string.report_text_shortened),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("text_shortened"),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!imeUp) {
                    val micLabel = stringResource(R.string.mic_hold)
                    MicButton(
                        listening = listening, level = level, ready = micReady,
                        contentDescription = micLabel,
                        label = when (voice) {
                            is VoiceState.Listening -> stringResource(R.string.mic_listening)
                            is VoiceState.Loading -> stringResource(R.string.mic_loading)
                            is VoiceState.NoModel -> stringResource(R.string.mic_no_model)
                            is VoiceState.NoMicrophone, is VoiceState.Failed -> stringResource(R.string.mic_failed)
                            else -> micLabel
                        },
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
                }
                BigButton(
                    text = stringResource(if (msg == null) R.string.counter_empty else R.string.action_send),
                    onClick = { keyboard?.hide(); focus.clearFocus(); vm.send() },
                    icon = if (msg == null) null else R.drawable.ic_send,
                    enabled = msg != null && ui.problem == null && !ui.busy,
                    modifier = Modifier.weight(1f).testTag("send_button"),
                    height = if (imeUp) 64.dp else 72.dp,
                )
            }
          }
        }
    }

    editor?.let { e ->
        FieldEditorSheet(e, ui.message.orEmpty(), lang, vm, onClose = { editor = null }, onNext = { editor = it })
    }

    if (showVoiceHelp) {
        VoiceHelpDialog(voice, onDismiss = { showVoiceHelp = false })
    }
}

/** What is open in the bottom sheet. */
sealed interface Editor {
    data object Type : Editor
    data object Priority : Editor
    data object Add : Editor
    data class Field(val def: FieldDef) : Editor
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhatWillBeSent(
    edited: Boolean, fallback: Boolean, problem: String?, readable: String, sentBytes: Int, lang: String,
    msg: Map<String, Any?>, onEdit: (Editor) -> Unit, onReadAgain: () -> Unit,
) {
    val type = ReportFields.typeOf(msg)
    val c = Rc.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.report_will_send), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (edited) TextButton(onClick = onReadAgain, modifier = Modifier.heightIn(min = 48.dp).testTag("read_again")) {
                Text(stringResource(R.string.report_read_again), style = MaterialTheme.typography.labelLarge)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val maydayType = type == "mayday"
            RcChip(
                Labels.head(lang, type), onClick = { onEdit(Editor.Type) }, selected = true,
                emphasis = if (maydayType) c.mayday else null, trailing = R.drawable.ic_edit,
                modifier = Modifier.testTag("chip_type"),
            )
            val prio = ReportFields.priorityOf(msg)
            val explicit = msg["priority"] != null
            RcChip(
                Labels.value(lang, "priority", prio).replaceFirstChar { it.uppercase() }, onClick = { onEdit(Editor.Priority) },
                selected = explicit && (prio == "critical" || prio == "urgent"),
                emphasis = if (prio == "critical") c.mayday else if (prio == "urgent") c.openSolid.copy() else null,
                modifier = Modifier.testTag("chip_priority"),
            )
            ReportFields.present(msg).forEach { def ->
                val body = ReportFields.body(msg)
                when (def.kind) {
                    FieldKind.MANY -> (body[def.key] as? List<*>).orEmpty().forEach { code ->
                        RcChip(Labels.value(lang, def.group, code.toString()), onClick = { onEdit(Editor.Field(def)) }, modifier = Modifier.testTag("chip_${def.key}_$code"))
                    }
                    FieldKind.ONE -> RcChip(Labels.value(lang, def.group, body[def.key].toString()), onClick = { onEdit(Editor.Field(def)) }, modifier = Modifier.testTag("chip_${def.key}"))
                    FieldKind.NUMBER -> RcChip(numberLabel(def, (body[def.key] as? Number)?.toInt() ?: 0), onClick = { onEdit(Editor.Field(def)) }, modifier = Modifier.testTag("chip_${def.key}"))
                }
            }
            if (ReportFields.missing(msg).isNotEmpty()) {
                RcChip(stringResource(R.string.report_add_field), onClick = { onEdit(Editor.Add) }, leading = R.drawable.ic_add, modifier = Modifier.testTag("chip_add"))
            }
        }
        if (fallback) {
            Note(stringResource(R.string.report_fallback), warn = true)
        }
        if (problem != null) {
            Note(stringResource(R.string.report_problem).format(problem), warn = true)
        } else if (readable.isNotEmpty()) {
            RcCard(container = MaterialTheme.colorScheme.surfaceContainerHigh, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                Text(stringResource(R.string.report_receivers_read), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(readable, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 28.sp), modifier = Modifier.testTag("report_readable"))
            }
        }
    }
}

@Composable
private fun Note(text: String, warn: Boolean) {
    val c = Rc.colors
    Row(
        Modifier.fillMaxWidth().then(Modifier),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RcIcon(R.drawable.ic_info, null, tint = if (warn) c.open else MaterialTheme.colorScheme.onSurfaceVariant, size = 22.dp, modifier = Modifier.padding(top = 2.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (warn) c.open else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * "51 bytes as text -> 25 bytes sent": the whole point of the app on one 40 dp line.
 */
@Composable
fun ByteCounter(textBytes: Int, sentBytes: Int, modifier: Modifier = Modifier, compact: Boolean = true) {
    val c = Rc.colors
    val active = textBytes > 0 && sentBytes > 0
    val desc = if (active) stringResource(R.string.counter_desc, textBytes, sentBytes) else stringResource(R.string.counter_empty)
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp).semantics(mergeDescendants = true) { contentDescription = desc }.testTag("byte_counter"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (active) stringResource(R.string.counter_line, textBytes, sentBytes) else stringResource(R.string.counter_empty),
            style = MaterialTheme.typography.titleSmall,
            color = if (active) c.done else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun numberLabel(def: FieldDef, n: Int): String = when ("${def.body}.${def.key}") {
    "found.count" -> stringResource(R.string.chip_people, n)
    "found.responders" -> stringResource(R.string.chip_helpers, n)
    "request.count" -> stringResource(R.string.chip_times, n)
    "hazard.radius" -> stringResource(R.string.chip_radius, n)
    "mayday.persons" -> stringResource(R.string.chip_persons, n)
    "sector.sector" -> stringResource(R.string.chip_sector, n)
    "sector.coverage" -> stringResource(R.string.chip_coverage, n)
    else -> n.toString()
}

@Composable
private fun fieldName(def: FieldDef): String = stringResource(
    when ("${def.body}.${def.key}") {
        "found.count" -> R.string.f_found_count
        "found.age" -> R.string.f_found_age
        "found.consciousness" -> R.string.f_found_consciousness
        "found.breathing" -> R.string.f_found_breathing
        "found.mobility" -> R.string.f_found_mobility
        "found.injuries" -> R.string.f_found_injuries
        "found.needs" -> R.string.f_found_needs
        "found.responders" -> R.string.f_found_responders
        "request.resource" -> R.string.f_request_resource
        "request.count" -> R.string.f_request_count
        "request.urgency" -> R.string.f_request_urgency
        "hazard.kind" -> R.string.f_hazard_kind
        "hazard.severity" -> R.string.f_hazard_severity
        "hazard.radius" -> R.string.f_hazard_radius
        "mayday.reason" -> R.string.f_mayday_reason
        "mayday.persons" -> R.string.f_mayday_persons
        "sector.sector" -> R.string.f_sector_number
        "sector.status" -> R.string.f_sector_status
        else -> R.string.f_sector_coverage
    },
)

/** Bottom sheet that edits one thing: the type, the priority, a field, or lists the fields to add. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldEditorSheet(editor: Editor, msg: Map<String, Any?>, lang: String, vm: ReportViewModel, onClose: () -> Unit, onNext: (Editor) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (editor) {
                Editor.Type -> {
                    SheetTitle(stringResource(R.string.sheet_type))
                    val cur = ReportFields.typeOf(msg)
                    ReportFields.TYPES.forEach { t ->
                        OptionRow(Labels.head(lang, t), selected = t == cur, tag = "opt_type_$t") {
                            vm.edit { m -> ReportFields.setType(m, t, vm.ui.value.text) }
                            onClose()
                        }
                    }
                }
                Editor.Priority -> {
                    SheetTitle(stringResource(R.string.sheet_priority))
                    val explicit = msg["priority"] as? String
                    OptionRow(stringResource(R.string.priority_auto), selected = explicit == null, tag = "opt_priority_auto") { vm.edit { ReportFields.setPriority(it, null) }; onClose() }
                    ReportFields.PRIORITIES.reversed().forEach { p ->
                        OptionRow(Labels.value(lang, "priority", p).replaceFirstChar { it.uppercase() }, selected = explicit == p, tag = "opt_priority_$p") { vm.edit { m -> ReportFields.setPriority(m, p) }; onClose() }
                    }
                }
                Editor.Add -> {
                    SheetTitle(stringResource(R.string.sheet_add))
                    ReportFields.missing(msg).forEach { d ->
                        OptionRow(fieldName(d), selected = false, tag = "opt_add_${d.key}") {
                            if (d.kind == FieldKind.NUMBER) vm.edit { m -> ReportFields.set(m, d, d.min.coerceAtLeast(1)) }
                            onNext(Editor.Field(d))
                        }
                    }
                }
                is Editor.Field -> {
                    val d = editor.def
                    SheetTitle(fieldName(d))
                    val body = ReportFields.body(msg)
                    when (d.kind) {
                        FieldKind.ONE -> d.options.forEach { code ->
                            OptionRow(Labels.value(lang, d.group, code), selected = body[d.key] == code, tag = "opt_${d.key}_$code") {
                                vm.edit { m -> ReportFields.set(m, d, code) }
                                onClose()
                            }
                        }
                        FieldKind.MANY -> {
                            val chosen = (body[d.key] as? List<*>).orEmpty().map { it.toString() }
                            d.options.forEach { code ->
                                OptionRow(Labels.value(lang, d.group, code), selected = code in chosen, check = true, tag = "opt_${d.key}_$code") {
                                    vm.edit { m -> ReportFields.toggle(m, d, code) }
                                }
                            }
                            BigButton(stringResource(R.string.action_done), onClick = onClose, modifier = Modifier.fillMaxWidth())
                        }
                        FieldKind.NUMBER -> {
                            val n = (body[d.key] as? Number)?.toInt() ?: d.min
                            Stepper(n, d) { v -> vm.edit { m -> ReportFields.set(m, d, v) } }
                            BigButton(stringResource(R.string.action_done), onClick = onClose, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (!d.required && body[d.key] != null) {
                        BigButton(stringResource(R.string.action_remove), onClick = { vm.edit { m -> ReportFields.remove(m, d) }; onClose() }, outlined = true, modifier = Modifier.fillMaxWidth(), container = Rc.colors.maydayText)
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun OptionRow(label: String, selected: Boolean, tag: String, check: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 16.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (selected) RcIcon(R.drawable.ic_check, null, tint = MaterialTheme.colorScheme.onPrimary)
            else if (check) RcIcon(R.drawable.ic_radio_button_unchecked, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun Stepper(value: Int, def: FieldDef, onChange: (Int) -> Unit) {
    val step = when { def.max >= 1000 -> 10; else -> 1 }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        BigButton("-", onClick = { onChange((value - step).coerceAtLeast(def.min)) }, modifier = Modifier.width(88.dp).testTag("step_minus"), outlined = true, height = 72.dp)
        Text(value.toString(), style = MaterialTheme.typography.displayMedium.copy(fontFamily = BarlowCondensed), modifier = Modifier.testTag("step_value"))
        BigButton("+", onClick = { onChange((value + step).coerceAtMost(def.max)) }, modifier = Modifier.width(88.dp).testTag("step_plus"), outlined = true, height = 72.dp)
    }
}

@Composable
private fun VoiceHelpDialog(state: VoiceState, onDismiss: () -> Unit) {
    val text = when (state) {
        is VoiceState.NoModel -> stringResource(R.string.voice_help_no_model, if (state.lang == "de") stringResource(R.string.lang_german) else stringResource(R.string.lang_english), state.hint)
        is VoiceState.Loading -> stringResource(R.string.voice_help_loading)
        VoiceState.NoMicrophone -> stringResource(R.string.voice_help_no_mic)
        is VoiceState.Failed -> stringResource(R.string.voice_help_failed, state.message)
        else -> stringResource(R.string.voice_help_generic)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_help_title), style = MaterialTheme.typography.headlineSmall) },
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_ok), style = MaterialTheme.typography.labelLarge) } },
    )
}

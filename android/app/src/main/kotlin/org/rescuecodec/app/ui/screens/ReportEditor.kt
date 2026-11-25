// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.screens

// The report editor: bottom sheets that change the type, the priority or one field of the message in the composer.

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
import org.rescuecodec.app.ui.components.RcCard
import org.rescuecodec.app.ui.components.RcChip
import org.rescuecodec.app.ui.components.RcIcon
import org.rescuecodec.app.ui.theme.BarlowCondensed
import org.rescuecodec.app.ui.theme.Rc
import org.rescuecodec.app.voice.VoiceState

/** What is open in the bottom sheet. */
sealed interface Editor {
    data object Type : Editor
    data object Priority : Editor
    data object Add : Editor
    data class Field(val def: FieldDef) : Editor
}

/** "3 people", "Sector 4": the words of a number field. */
@Composable
fun numberLabel(def: FieldDef, n: Int): String = when ("${def.body}.${def.key}") {
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
fun FieldEditorSheet(editor: Editor, msg: Map<String, Any?>, lang: String, vm: ReportViewModel, onClose: () -> Unit, onNext: (Editor) -> Unit) {
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
                    if (msg["priority"] == null) OptionRow(stringResource(R.string.sheet_priority), selected = false, tag = "opt_add_priority") { onNext(Editor.Priority) }
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
fun VoiceHelpDialog(state: VoiceState, onDismiss: () -> Unit) {
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

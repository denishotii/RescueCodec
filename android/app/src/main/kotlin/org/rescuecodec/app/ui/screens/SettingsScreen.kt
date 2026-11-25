// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.rescuecodec.app.AppGraph
import org.rescuecodec.app.BuildConfig
import org.rescuecodec.app.R
import org.rescuecodec.app.ui.ConnectionViewModel
import org.rescuecodec.app.ui.Permissions
import org.rescuecodec.app.ui.SettingsViewModel
import org.rescuecodec.app.ui.components.BigButton
import org.rescuecodec.app.ui.components.Hairline
import org.rescuecodec.app.ui.components.RcCard
import org.rescuecodec.app.ui.components.SectionHeader
import org.rescuecodec.app.voice.ModelStatus
import org.rescuecodec.app.voice.VoskModels
import org.rescuecodec.engine.Airtime

/** Settings: language, day/night, team, radio and region info, speech models, other apps, licences. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, connection: ConnectionViewModel, lang: String) {
    val context = LocalContext.current
    val theme by vm.theme.collectAsStateWithLifecycle()
    var team by remember { mutableStateOf(vm.teamLabel) }
    var allowApi by remember { mutableStateOf(connection.allowIntentApi()) }
    val appSettings = remember { AppGraph.from(context).settings }
    var plainText by remember { mutableStateOf(appSettings.plainTextAsMeshtastic) }
    var licences by remember { mutableStateOf(false) }
    val region = vm.region
    val preset = vm.preset
    val impState by vm.importState.collectAsStateWithLifecycle()
    val imp = impState
    val modelsVersion by vm.modelsVersion.collectAsStateWithLifecycle()
    var pickLang by remember { mutableStateOf("en") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importModel(uri, pickLang) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("settings_screen"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        SectionHeader(stringResource(R.string.set_language))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("en" to R.string.lang_english, "de" to R.string.lang_german).forEachIndexed { i, (code, label) ->
                SegmentedButton(
                    selected = lang == code, onClick = { vm.setLanguage(code) },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                    modifier = Modifier.heightIn(min = 56.dp).testTag("lang_$code"),
                    colors = segColors(),
                ) { Text(stringResource(label), style = MaterialTheme.typography.labelLarge) }
            }
        }
        Text(stringResource(R.string.set_language_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionHeader(stringResource(R.string.set_theme))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("auto" to R.string.theme_auto, "day" to R.string.theme_day, "night" to R.string.theme_night).forEachIndexed { i, (code, label) ->
                SegmentedButton(
                    selected = theme == code, onClick = { vm.setTheme(code) },
                    shape = SegmentedButtonDefaults.itemShape(i, 3),
                    modifier = Modifier.heightIn(min = 56.dp).testTag("theme_$code"),
                    colors = segColors(),
                ) { Text(stringResource(label), style = MaterialTheme.typography.labelLarge) }
            }
        }

        SectionHeader(stringResource(R.string.set_sounds))
        val sounds by vm.sounds.collectAsStateWithLifecycle()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("sounds_row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.set_sounds_switch), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = sounds, onCheckedChange = vm::setSounds, modifier = Modifier.testTag("sounds_switch"))
        }
        Text(stringResource(R.string.set_sounds_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionHeader(stringResource(R.string.set_team))
        OutlinedTextField(
            team, { team = it; vm.setTeamLabel(it) }, singleLine = true,
            label = { Text(stringResource(R.string.set_team_label)) },
            supportingText = { Text(stringResource(R.string.set_team_note)) },
            modifier = Modifier.fillMaxWidth().testTag("team_field"),
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = MaterialTheme.colorScheme.outline),
        )

        SectionHeader(stringResource(R.string.set_radio))
        RcCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow(stringResource(R.string.set_region), region.name)
                InfoRow(stringResource(R.string.set_preset), preset.name)
                InfoRow(stringResource(R.string.set_duty), "%d %%".format((region.dutyCycle * 100).toInt()))
                InfoRow(stringResource(R.string.set_airtime_11), "%.2f s".format(Airtime(preset).millis(11) / 1000.0))
                Text(stringResource(R.string.set_radio_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.set_plain_text), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.set_plain_text_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = plainText, onCheckedChange = { plainText = it; appSettings.plainTextAsMeshtastic = it }, modifier = Modifier.testTag("plain_text_switch"))
        }

        SectionHeader(stringResource(R.string.set_voice))
        RcCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("en" to R.string.lang_english, "de" to R.string.lang_german).forEach { (code, label) ->
                    val st = remember(modelsVersion) { VoskModels.status(context, code) }
                    val text = when (st) {
                        is ModelStatus.Ready -> stringResource(R.string.model_ready)
                        ModelStatus.Bundled -> stringResource(R.string.model_bundled)
                        is ModelStatus.Missing -> stringResource(R.string.model_missing)
                    }
                    InfoRow(stringResource(label), text, ok = st !is ModelStatus.Missing)
                    if (st is ModelStatus.Missing || imp is SettingsViewModel.ImportState.Failed && imp.lang == code) {
                        BigButton(
                            stringResource(R.string.model_import, stringResource(label)),
                            { pickLang = code; picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                            Modifier.fillMaxWidth().testTag("import_$code"), outlined = true, height = 48.dp,
                        )
                    }
                }
                when (imp) {
                    is SettingsViewModel.ImportState.Working -> Text(stringResource(R.string.model_importing), style = MaterialTheme.typography.bodyMedium)
                    is SettingsViewModel.ImportState.Failed -> Text(stringResource(R.string.model_import_failed, imp.reason), style = MaterialTheme.typography.bodyMedium, color = org.rescuecodec.app.ui.theme.Rc.colors.maydayText)
                    is SettingsViewModel.ImportState.Done -> Text(stringResource(R.string.model_import_done), style = MaterialTheme.typography.bodyMedium, color = org.rescuecodec.app.ui.theme.Rc.colors.done)
                    SettingsViewModel.ImportState.Idle -> Unit
                }
                Text(stringResource(R.string.set_voice_note, VoskModels.externalDir(context, "de")?.parentFile?.absolutePath ?: "files/vosk"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SectionHeader(stringResource(R.string.set_other_apps))
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.set_intent_api), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.set_intent_api_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = allowApi, onCheckedChange = { allowApi = it; connection.setAllowIntentApi(it) }, modifier = Modifier.testTag("intent_switch"))
        }
        BigButton(stringResource(R.string.set_battery), { context.startActivity(Permissions.batterySettingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, Modifier.fillMaxWidth(), outlined = true)
        Text(stringResource(R.string.perm_battery), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionHeader(stringResource(R.string.set_about))
        RcCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow(stringResource(R.string.set_version), BuildConfig.VERSION_NAME)
                BigButton(stringResource(R.string.set_licences), { licences = true }, Modifier.fillMaxWidth().testTag("licences_button"), outlined = true)
            }
        }
        Spacer(Modifier.height(88.dp)) // room for the SOS button
    }
    if (licences) LicencesDialog { licences = false }
}

@Composable
private fun segColors() = SegmentedButtonDefaults.colors(
    activeContainerColor = MaterialTheme.colorScheme.primary,
    activeContentColor = MaterialTheme.colorScheme.onPrimary,
    inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    inactiveContentColor = MaterialTheme.colorScheme.onSurface,
    activeBorderColor = MaterialTheme.colorScheme.primary,
    inactiveBorderColor = MaterialTheme.colorScheme.outline,
)

@Composable
private fun InfoRow(label: String, value: String, ok: Boolean? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(
            value, style = MaterialTheme.typography.titleMedium,
            color = when (ok) { true -> org.rescuecodec.app.ui.theme.Rc.colors.done; false -> org.rescuecodec.app.ui.theme.Rc.colors.open; null -> MaterialTheme.colorScheme.onSurface },
        )
    }
}

private val LICENCES = listOf(
    "RescueCodec app" to "GPL-3.0-or-later",
    "RescueCodec spec, :codec and :engine libraries" to "Apache-2.0",
    "Meshtastic Kotlin SDK 0.2.0 and protobufs" to "GPL-3.0-or-later",
    "Vosk speech recognition (Alpha Cephei)" to "Apache-2.0",
    "Vosk small models, German 0.15 and English US 0.15" to "Apache-2.0",
    "Barlow and Barlow Condensed (fonts)" to "SIL OFL 1.1",
    "Material icons (Google)" to "Apache-2.0",
    "Jetpack Compose, CameraX, Room, AndroidX" to "Apache-2.0",
    "ZXing core (QR reading)" to "Apache-2.0",
    "Kable, kotlinx.coroutines" to "Apache-2.0",
)

@Composable
private fun LicencesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_licences), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LICENCES.forEach { (name, licence) ->
                    Column {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Text(licence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Hairline()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_ok), style = MaterialTheme.typography.labelLarge) } },
    )
}

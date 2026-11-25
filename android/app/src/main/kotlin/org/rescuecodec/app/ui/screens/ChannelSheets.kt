// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.rescuecodec.app.R
import org.rescuecodec.app.core.RadioChannel
import org.rescuecodec.app.radio.ChannelAdmin
import org.rescuecodec.app.radio.NameProblem
import org.rescuecodec.app.ui.ConnectionViewModel
import org.rescuecodec.app.ui.ConnectionViewModel.CreateState
import org.rescuecodec.app.ui.components.BigButton
import org.rescuecodec.app.ui.components.Hairline
import org.rescuecodec.app.ui.components.PlainTag
import org.rescuecodec.app.ui.components.RcIcon
import org.rescuecodec.app.ui.theme.Rc

/** A sheet title: condensed capitals, like the card titles. */
@Composable
private fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, letterSpacing = 0.6.sp), modifier = modifier)
}

/** A line with an icon in a meaning colour: amber for "act", red for errors. Colour is never the only signal. */
@Composable
private fun NoteLine(icon: Int, text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RcIcon(icon, null, tint = color, size = 20.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/**
 * New channel: a name (at most 11 bytes, "Team 2" suggested), a short explanation and Create. Writes a private channel
 * with a fresh key into a free secondary slot of the connected radio. The QR sheet opens after it worked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChannelSheet(vm: ConnectionViewModel, state: CreateState, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val c = Rc.colors
    var name by remember { mutableStateOf(vm.suggestName()) }
    val canWrite = remember(state) { vm.canWrite() }
    val bytes = ChannelAdmin.nameBytes(name)
    val problem = vm.checkName(name)
    val working = state == CreateState.Working
    val create = { if (problem == null && canWrite && !working) vm.createChannel(name) }
    ModalBottomSheet(
        onDismissRequest = { if (!working) onDismiss() }, sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 20.dp).verticalScroll(rememberScrollState()).testTag("new_channel_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetTitle(stringResource(R.string.channel_new))
            Text(stringResource(R.string.channel_new_explain), style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                name, { name = it; if (state !is CreateState.Idle && !working) vm.clearCreate() },
                label = { Text(stringResource(R.string.channel_name_label)) },
                singleLine = true, enabled = !working, isError = problem != null,
                supportingText = {
                    Text(
                        when (problem) {
                            null -> stringResource(R.string.channel_name_bytes, bytes, ChannelAdmin.MAX_NAME_BYTES)
                            NameProblem.EMPTY -> stringResource(R.string.channel_name_empty)
                            NameProblem.TOO_LONG -> stringResource(R.string.channel_name_too_long, bytes, ChannelAdmin.MAX_NAME_BYTES)
                            NameProblem.TAKEN -> stringResource(R.string.channel_name_taken)
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    )
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { create() }),
                modifier = Modifier.fillMaxWidth().testTag("new_channel_name"),
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
            )
            when (state) {
                CreateState.Working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("new_channel_working")) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.channel_creating), style = MaterialTheme.typography.bodyLarge)
                }
                CreateState.NoRadio -> NoteLine(R.drawable.ic_error_outline, stringResource(R.string.join_need_radio), c.maydayText, Modifier.testTag("new_channel_error"))
                CreateState.NoFreeSlot -> NoteLine(R.drawable.ic_error_outline, stringResource(R.string.channel_no_slot), c.maydayText, Modifier.testTag("new_channel_error"))
                is CreateState.Refused -> NoteLine(R.drawable.ic_error_outline, stringResource(R.string.channel_refused, state.detail), c.maydayText, Modifier.testTag("new_channel_error"))
                is CreateState.BadName, CreateState.Idle, is CreateState.Done -> if (!canWrite) NoteLine(R.drawable.ic_warning, stringResource(R.string.join_need_radio), c.open)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton(
                    stringResource(R.string.channel_create), create, Modifier.weight(1f).testTag("new_channel_create"),
                    icon = R.drawable.ic_add, enabled = problem == null && canWrite && !working,
                )
                BigButton(
                    stringResource(R.string.action_cancel), onDismiss, Modifier.weight(0.7f), outlined = true,
                    container = MaterialTheme.colorScheme.onSurface, enabled = !working,
                )
            }
        }
    }
}

/** Tap a channel row: Show QR code, Remove channel (not for the primary). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelMenuSheet(channel: RadioChannel, removable: Boolean, onQr: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 20.dp).testTag("channel_menu")) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                SheetTitle(channel.name, Modifier.weight(1f, fill = false))
                PlainTag(stringResource(R.string.ch_private))
            }
            MenuItem(R.drawable.ic_qr_code, stringResource(R.string.channel_show_qr), "channel_menu_qr", MaterialTheme.colorScheme.onSurface, last = !removable) { onQr() }
            if (removable) {
                MenuItem(R.drawable.ic_delete_outline, stringResource(R.string.channel_remove), "channel_menu_remove", Rc.colors.maydayText, last = true) { onRemove() }
            } else {
                Text(stringResource(R.string.channel_primary_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** One row of a sheet menu: icon and words, 52 dp, a hairline under it. */
@Composable
private fun MenuItem(icon: Int, text: String, tag: String, color: Color, last: Boolean = false, onClick: () -> Unit) {
    Column {
        Surface(onClick = onClick, color = Color.Transparent, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag)) {
            Row(Modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RcIcon(icon, null, tint = color, size = 22.dp)
                Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = color)
            }
        }
        if (!last) Hairline()
    }
}

/**
 * The QR code of one channel: a big code of a link with only that channel and the radio's LoRa settings (the format the
 * Meshtastic app and Join by QR read), the name, Share link, and the warning that the code is the key.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelQrSheet(share: ConnectionViewModel.ChannelShare?, fallbackName: String, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val c = Rc.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 20.dp).verticalScroll(rememberScrollState()).testTag("channel_qr_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetTitle(share?.name ?: fallbackName, Modifier.weight(1f, fill = false))
                PlainTag(stringResource(R.string.ch_private))
            }
            if (share == null) {
                NoteLine(R.drawable.ic_warning, stringResource(R.string.channel_qr_unavailable), c.open)
            } else {
                val matrix = remember(share.link) { qrMatrix(share.link) }
                // Black on white with a quiet zone in day and night: scanners need it.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f).background(Color.White, RoundedCornerShape(10.dp)).padding(18.dp)
                            .semantics { contentDescription = share.name }.testTag("channel_qr"),
                    ) {
                        if (matrix != null) QrCanvas(matrix)
                    }
                }
                Text(stringResource(R.string.channel_qr_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier.fillMaxWidth().background(c.openSoft, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp).testTag("channel_qr_warning"),
                    verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RcIcon(R.drawable.ic_warning, null, tint = c.open, size = 20.dp)
                    Text(stringResource(R.string.channel_qr_warning), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (share != null) {
                    val title = stringResource(R.string.channel_share_link)
                    BigButton(
                        title,
                        {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, share.name).putExtra(Intent.EXTRA_TEXT, share.link)
                            runCatching { context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        },
                        Modifier.weight(1f).testTag("channel_share"), icon = R.drawable.ic_share,
                    )
                }
                BigButton(stringResource(R.string.action_done), onDismiss, Modifier.weight(if (share != null) 0.7f else 1f).testTag("channel_qr_close"), outlined = true, container = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** The QR modules of [text], no margin (the white box is the quiet zone). Null if it cannot be encoded. */
internal fun qrMatrix(text: String): BitMatrix? = runCatching {
    QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
}.getOrNull()

/** Draws [m] module by module: sharp at any size. */
@Composable
private fun QrCanvas(m: BitMatrix) {
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
        val n = m.width
        val cell = size.minDimension / n
        for (y in 0 until n) for (x in 0 until n) {
            if (m[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
        }
    }
}

/**
 * Remove a channel: names it and says it cannot be undone without its QR. For the team channel in use, a second step
 * asks again.
 */
@Composable
fun RemoveChannelDialog(channel: RadioChannel, isTeam: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var step by remember { mutableIntStateOf(1) }
    val c = Rc.colors
    val second = isTeam && step == 2
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(if (second) "remove_channel_team" else "remove_channel_dialog"),
        icon = { RcIcon(R.drawable.ic_delete_outline, null, tint = c.maydayText) },
        title = {
            Text(
                if (second) stringResource(R.string.channel_remove_team_title) else stringResource(R.string.channel_remove_title, channel.name),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (second) {
                    Text(stringResource(R.string.channel_remove_team_body), style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text(stringResource(R.string.channel_remove_body), style = MaterialTheme.typography.bodyLarge)
                    if (isTeam) NoteLine(R.drawable.ic_warning, stringResource(R.string.channel_remove_team_note), c.open)
                }
            }
        },
        confirmButton = {
            BigButton(
                when {
                    second -> stringResource(R.string.channel_remove_team_confirm)
                    isTeam -> stringResource(R.string.channel_next)
                    else -> stringResource(R.string.channel_remove_confirm)
                },
                { if (isTeam && step == 1) step = 2 else onConfirm() },
                Modifier.testTag("remove_channel_confirm"), container = c.mayday, content = c.onMayday, height = 48.dp,
            )
        },
        dismissButton = {
            BigButton(stringResource(R.string.action_cancel), onDismiss, Modifier.testTag("remove_channel_cancel"), outlined = true, container = MaterialTheme.colorScheme.onSurface, height = 48.dp)
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

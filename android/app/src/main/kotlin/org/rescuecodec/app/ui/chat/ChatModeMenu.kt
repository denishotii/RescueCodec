// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rescuecodec.app.R
import org.rescuecodec.app.ui.components.RcIcon
import org.rescuecodec.app.ui.theme.Rc
import org.rescuecodec.engine.TextMode

/**
 * The ⋮ in a chat's top bar: how texts go out in this chat (spec section 2.3). Auto (compressed when everyone here
 * uses RescueCodec), Always compressed, Always normal. The pick is saved per chat.
 */
@Composable
fun ChatModeMenu(mode: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = listOf(
        Triple(TextMode.AUTO, R.string.text_mode_auto, R.string.text_mode_auto_note),
        Triple(TextMode.COMPRESSED, R.string.text_mode_compressed, R.string.text_mode_compressed_note),
        Triple(TextMode.NORMAL, R.string.text_mode_normal, R.string.text_mode_normal_note),
    )
    Box {
        Surface(onClick = { open = true }, color = Color.Transparent, shape = MaterialTheme.shapes.medium, modifier = Modifier.size(48.dp).testTag("chat_menu")) {
            Box(contentAlignment = Alignment.Center) { RcIcon(R.drawable.ic_more_vert, stringResource(R.string.text_mode_menu)) }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.testTag("text_mode_menu")) {
            Text(
                stringResource(R.string.text_mode_title), style = MaterialTheme.typography.labelLarge, color = Rc.colors.ink3,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            options.forEach { (code, label, note) ->
                val on = mode == code
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal))
                            Text(stringResource(note), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = Rc.colors.muted)
                        }
                    },
                    leadingIcon = { RcIcon(if (on) R.drawable.ic_check else R.drawable.ic_radio_button_unchecked, null, size = 20.dp) },
                    onClick = { open = false; onPick(code) },
                    modifier = Modifier.heightIn(min = 56.dp).testTag("text_mode_$code"),
                )
            }
        }
    }
}

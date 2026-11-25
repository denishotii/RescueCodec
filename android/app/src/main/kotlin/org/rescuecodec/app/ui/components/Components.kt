// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rescuecodec.app.R
import org.rescuecodec.app.ui.theme.Rc

/** A drawn icon from res/drawable (Material icons, Apache-2.0). */
@Composable
fun RcIcon(@DrawableRes id: Int, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface, size: Dp = 24.dp) {
    Icon(painterResource(id), contentDescription, modifier.size(size), tint)
}

/** The one card of the app: tonal surface, one hairline, no shadow. */
@Composable
fun RcCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    outline: Color = MaterialTheme.colorScheme.outlineVariant,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = container,
        border = BorderStroke(1.dp, outline),
    ) {
        Column(Modifier.padding(padding)) { content() }
    }
}

/** A filled or outlined button at glove size: 56 dp tall, label in 18 sp. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
    outlined: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 56.dp,
) {
    val bg = if (outlined) Color.Transparent else if (enabled) container else MaterialTheme.colorScheme.surfaceContainerLow
    val fg = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant else if (outlined) container else content
    val border = if (outlined) BorderStroke(2.dp, if (enabled) container else MaterialTheme.colorScheme.outline)
        else if (!enabled) BorderStroke(2.dp, MaterialTheme.colorScheme.outline) else null
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = height),
        shape = MaterialTheme.shapes.medium,
        color = bg,
        contentColor = fg,
        border = border,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp).heightIn(min = height),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                RcIcon(icon, null, tint = fg)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize), color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A tappable chip, 48 dp tall. [selected] fills it with ink. [dashed] marks a value that is only a guess. */
@Composable
fun RcChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    @DrawableRes leading: Int? = null,
    @DrawableRes trailing: Int? = null,
    emphasis: Color? = null,
    enabled: Boolean = true,
) {
    val container = when {
        selected -> emphasis ?: MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val fg = when {
        selected -> if (emphasis != null) Rc.colors.onSolid else MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val border = if (selected) null else BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(24.dp),
        color = container,
        contentColor = fg,
        border = border,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (leading != null) RcIcon(leading, null, tint = fg, size = 20.dp)
            Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (trailing != null) RcIcon(trailing, null, tint = fg, size = 20.dp)
        }
    }
}

/** Small read-only label: message type, state. Colour plus words, never colour alone. */
@Composable
fun Tag(text: String, background: Color, content: Color, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    Row(
        modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) RcIcon(icon, null, tint = content, size = 16.dp)
        Text(text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
    }
}

/** State of a task: open (amber), accepted (blue), declined (grey), done (green), delivery unknown (grey, dashed). */
@Composable
fun StateTag(state: String, modifier: Modifier = Modifier) {
    val c = Rc.colors
    // Solid tonal fills with ink text: they read in sunlight and in the dark.
    val ink = Color(0xFF17231C)
    val (bg, fg, label) = when (state) {
        "open" -> Triple(Color(0xFFF2C14E), ink, stringResource(R.string.state_open))
        "accepted" -> Triple(Color(0xFF9CC9EC), ink, stringResource(R.string.state_accepted))
        "done" -> Triple(Color(0xFFA6D8B2), ink, stringResource(R.string.state_done))
        "declined" -> Triple(Color(0xFFC9CEC9), ink, stringResource(R.string.state_declined))
        else -> Triple(Color(0xFFC9CEC9), ink, stringResource(R.string.state_delivery_unknown))
    }
    Tag(label, bg, fg, modifier, icon = if (state == "done") R.drawable.ic_check else null)
}

/**
 * "measured" or "estimated". Measured is a solid outline with a check. Estimated is a dashed
 * outline. The word is always there.
 */
@Composable
fun SourceTag(measured: Boolean, modifier: Modifier = Modifier) {
    val c = Rc.colors
    val color = if (measured) c.measured else c.estimated
    val label = stringResource(if (measured) R.string.tag_measured else R.string.tag_estimated)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val dash = with(density) { floatArrayOf(4.dp.toPx(), 3.dp.toPx()) }
    Row(
        modifier
            .drawBehind {
                drawRoundRect(
                    color = color, size = Size(size.width, size.height), cornerRadius = CornerRadius(6.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = if (measured) null else PathEffect.dashPathEffect(dash)),
                )
            }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (measured) RcIcon(R.drawable.ic_check, null, tint = color, size = 14.dp)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, count: Int? = null, color: Color = MaterialTheme.colorScheme.onSurface) {
    // Same signature as the dashboard: condensed capitals with a 2 dp amber rule underneath.
    Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text.uppercase(), style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 0.8.sp), color = color, modifier = Modifier.semantics { contentDescription = text })
            if (count != null) Text(count.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Rc.colors.openSolid))
    }
}

/** A coloured dot with a word beside it: connection status. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** A bar: [fraction] 0..1 of the track in [color]. Used for the airtime budget and byte comparison. */
@Composable
fun MeterBar(fraction: Float, color: Color, modifier: Modifier = Modifier, track: Color = MaterialTheme.colorScheme.surfaceContainerHighest, height: Dp = 10.dp) {
    Box(modifier.fillMaxWidth().height(height).background(track, RoundedCornerShape(height / 2))) {
        if (fraction > 0f) Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(height).background(color, RoundedCornerShape(height / 2)),
        )
    }
}

/** Marks a clickable surface as a button for screen readers. */
fun Modifier.clickableButton(onClick: () -> Unit): Modifier = this.clickable(role = Role.Button, onClick = onClick)

/** A hairline in the outline colour. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

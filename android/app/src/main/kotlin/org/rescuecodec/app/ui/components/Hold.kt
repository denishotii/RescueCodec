// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.rescuecodec.app.R
import org.rescuecodec.app.ui.theme.Rc

/**
 * Press and hold for [holdMs], then [onConfirm]. Let go early and nothing happens.
 *
 * Time comes from the frame clock, not from an animation, so the system setting "Remove
 * animations" cannot make the hold instant. A screen reader gets the same action as a long press.
 */
@Composable
fun Modifier.holdToConfirm(
    holdMs: Long,
    progress: MutableFloatState,
    onConfirm: () -> Unit,
    onHoldingChange: (Boolean) -> Unit = {},
    longClickLabel: String? = null,
): Modifier {
    val scope = rememberCoroutineScope()
    val confirm = rememberUpdatedState(onConfirm)
    val holding = rememberUpdatedState(onHoldingChange)
    val haptic = LocalHapticFeedback.current
    return this
        .semantics { onLongClick(label = longClickLabel) { confirm.value(); true } }
        .pointerInput(holdMs) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                holding.value(true)
                var job: Job? = null
                var fired = false
                job = scope.launch {
                    var start = -1L
                    var ticked = 0
                    while (true) {
                        val now = androidx.compose.runtime.withFrameMillis { it }
                        if (start < 0) start = now
                        val p = ((now - start).toFloat() / holdMs).coerceIn(0f, 1f)
                        progress.floatValue = p
                        val third = (p * 3).toInt()
                        if (third > ticked && p < 1f) { ticked = third; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                        if (p >= 1f) {
                            fired = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            confirm.value()
                            break
                        }
                    }
                }
                waitForUpOrCancellation()
                job.cancel()
                holding.value(false)
                if (fired) {
                    scope.launch { kotlinx.coroutines.delay(400); progress.floatValue = 0f }
                } else {
                    progress.floatValue = 0f
                }
            }
        }
}

/**
 * The SOS strip, on every screen above the navigation bar. Hold 2 seconds: it fills with red from
 * left to right and then sends a Mayday with the last known position.
 */
@Composable
fun SosBar(onConfirm: () -> Unit, modifier: Modifier = Modifier, holdMs: Long = 2000) {
    val c = Rc.colors
    val progress = remember { mutableFloatStateOf(0f) }
    var holding by remember { mutableStateOf(false) }
    val p = progress.floatValue
    val label = stringResource(R.string.sos_hold)
    // A solid red control with white text, so it reads as a button and not as a warning note.
    val solid = Color(0xFFC30B1A)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(solid)
            .holdToConfirm(holdMs, progress, onConfirm, { holding = it }, longClickLabel = stringResource(R.string.sos_send))
            .testTag("sos_bar"),
        contentAlignment = Alignment.CenterStart,
    ) {
        // The fill: a darker red that grows with the hold.
        Box(
            Modifier
                .fillMaxWidth(p)
                .heightIn(min = 56.dp)
                .background(Color(0xFF6E0510)),
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp).heightIn(min = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RcIcon(R.drawable.ic_warning, null, tint = Color.White)
            Text(
                if (holding && p < 1f) stringResource(R.string.sos_keep_holding) else if (p >= 1f) stringResource(R.string.sos_sent) else label,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // The hold ring: fills as you hold.
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(44.dp)) {
                    val w = 4.dp.toPx()
                    val inset = w / 2
                    drawCircle(Color.White.copy(alpha = 0.45f), radius = size.minDimension / 2 - inset, style = Stroke(width = w))
                    drawArc(
                        Color.White, startAngle = -90f, sweepAngle = 360f * p, useCenter = false,
                        topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                        style = Stroke(width = w, cap = StrokeCap.Round),
                    )
                }
                Text("SOS", style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
        }
    }
}

/**
 * The hold-to-talk button. Press to listen, let go to stop. Three contour rings around the disc
 * breathe with the loudness of the voice, like lines on a map.
 */
@Composable
fun MicButton(
    listening: Boolean,
    level: Float,
    ready: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String,
    label: String = contentDescription,
) {
    val ink = MaterialTheme.colorScheme.primary
    val onInk = MaterialTheme.colorScheme.onPrimary
    val c = Rc.colors
    val on = ready || listening
    val disc = if (listening) c.mayday else if (on) ink else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (listening) c.onMayday else if (on) onInk else MaterialTheme.colorScheme.onSurfaceVariant
    val press = rememberUpdatedState(onPress)
    val release = rememberUpdatedState(onRelease)
    // The hero of the Report screen: a 96 dp ink disc in a 112 dp touch area.
    Box(
        modifier
            .size(112.dp)
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    press.value()
                    waitForUpOrCancellation()
                    release.value()
                }
            }
            .testTag("mic_button"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(112.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val lineColor = if (listening) c.mayday else ink
            // A solid ring around the disc, always visible.
            drawCircle(lineColor.copy(alpha = 0.5f), radius = 52.dp.toPx(), center = center, style = Stroke(width = 3.dp.toPx()))
            if (listening) for (i in 1..2) {
                val r = 52.dp.toPx() + i * 2.dp.toPx() + level * i * 3.dp.toPx()
                drawCircle(lineColor.copy(alpha = 0.5f / i), radius = r.coerceAtMost(size.minDimension / 2), center = center, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Column(
            Modifier.size(96.dp).clip(CircleShape).background(disc).padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RcIcon(if (on) R.drawable.ic_mic else R.drawable.ic_mic_off, null, tint = fg, size = 34.dp)
            Text(
                label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 15.sp), color = fg,
                maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

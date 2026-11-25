// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.components

import androidx.compose.material3.Surface
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
 * Hold to talk, in the composer: a 52 dp ink disc with a grey ring around it (overnight). Press to listen, let go to
 * stop. While listening it turns red and contour rings around it grow with the loudness of the voice.
 */
@Composable
fun ComposerMic(
    listening: Boolean,
    level: Float,
    ready: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val c = Rc.colors
    val ink = MaterialTheme.colorScheme.primary
    val disc = if (listening) c.mayday else if (ready) ink else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (listening) c.onMayday else if (ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val press = rememberUpdatedState(onPress)
    val release = rememberUpdatedState(onRelease)
    Box(
        modifier
            .size(64.dp)
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
        Canvas(Modifier.size(64.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            // The grey ring, 3 dp off the disc, always there.
            drawCircle(if (listening) c.mayday else c.ring, radius = 30.dp.toPx(), center = center, style = Stroke(width = 2.dp.toPx()))
            if (listening) for (i in 1..2) {
                val r = (30.dp.toPx() + i * 1.dp.toPx() + level * i * 1.dp.toPx()).coerceAtMost(size.minDimension / 2)
                drawCircle(c.mayday.copy(alpha = 0.5f / i), radius = r, center = center, style = Stroke(width = 1.5.dp.toPx()))
            }
        }
        Box(Modifier.size(52.dp).clip(CircleShape).background(disc), contentAlignment = Alignment.Center) {
            RcIcon(if (ready || listening) R.drawable.ic_mic else R.drawable.ic_mic_off, null, tint = fg, size = 24.dp)
        }
    }
}

/**
 * The SOS strip (overnight), on every screen above the navigation bar: red, "Hold 2 seconds for SOS" and a ring with
 * SOS. Hold 2 seconds: it fills with dark red from left to right and the ring closes, then it sends a Mayday with the
 * last known position. A tap does nothing. [slim] is the thinner strip that rides on the keyboard while typing.
 */
@Composable
fun SosBar(onConfirm: () -> Unit, modifier: Modifier = Modifier, slim: Boolean = false, holdMs: Long = 2000) {
    val progress = remember { mutableFloatStateOf(0f) }
    var holding by remember { mutableStateOf(false) }
    val p = progress.floatValue
    val h = if (slim) 40.dp else 52.dp
    val ring = if (slim) 32.dp else 42.dp
    // A solid red control with white text, so it reads as a button and not as a warning note.
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = h)
            .background(Rc.colors.mayday)
            .testTag("sos_bar"),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth(p).heightIn(min = h).background(Color(0xFF6E0510)))
        Row(
            Modifier.fillMaxWidth().heightIn(min = h)
                .semantics { contentDescription = "SOS" }
                .holdToConfirm(holdMs, progress, onConfirm, { holding = it }, longClickLabel = stringResource(R.string.sos_send))
                .testTag("sos_button")
                .padding(start = 14.dp, end = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RcIcon(R.drawable.ic_warning, null, tint = Color.White, size = if (slim) 20.dp else 24.dp)
            Text(
                if (holding && p < 1f) stringResource(R.string.sos_keep_holding) else if (p >= 1f) stringResource(R.string.sos_sent) else stringResource(R.string.sos_hold),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = if (slim) 15.sp else 17.sp),
                color = Color.White,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(ring)) {
                    val w = (if (slim) 2 else 3).dp.toPx()
                    val inset = w / 2
                    drawCircle(Color.White.copy(alpha = 0.55f), radius = size.minDimension / 2 - inset, style = Stroke(width = w))
                    if (p > 0f) drawArc(
                        Color.White, startAngle = -90f, sweepAngle = 360f * p, useCenter = false,
                        topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                        style = Stroke(width = w, cap = StrokeCap.Round),
                    )
                }
                Text(
                    stringResource(R.string.sos_short),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = if (slim) 12.sp else 14.sp, letterSpacing = 0.6.sp),
                    color = Color.White,
                )
            }
        }
    }
}

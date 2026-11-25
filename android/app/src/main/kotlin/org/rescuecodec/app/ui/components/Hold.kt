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
 * The SOS button at the start of the composer: a red disc with the word SOS. Hold 2 seconds: a ring fills around it
 * and the composer shows a red strip that fills too (the thumb hides the button itself), then it sends a Mayday with
 * the last known position. A tap does nothing. [progress] and [onHoldingChange] let the composer draw that strip.
 */
@Composable
fun SosButton(
    onConfirm: () -> Unit,
    progress: MutableFloatState,
    onHoldingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    holdMs: Long = 2000,
) {
    val c = Rc.colors
    val p = progress.floatValue
    Box(
        modifier
            .size(56.dp)
            .semantics { contentDescription = "SOS" }
            .holdToConfirm(holdMs, progress, onConfirm, onHoldingChange, longClickLabel = stringResource(R.string.sos_send))
            .testTag("sos_button"),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(c.mayday), contentAlignment = Alignment.Center) {
            Text("SOS", style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp, letterSpacing = 0.5.sp), color = c.onMayday)
        }
        Canvas(Modifier.size(56.dp)) {
            val w = 4.dp.toPx()
            if (p > 0f) drawArc(
                c.maydayText, startAngle = -90f, sweepAngle = 360f * p, useCenter = false,
                topLeft = Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                style = Stroke(width = w, cap = StrokeCap.Round),
            )
        }
    }
}

/** The strip the composer shows while SOS is held: it fills from left to right, with words, so the hold is never silent. */
@Composable
fun SosHoldStrip(progress: Float, modifier: Modifier = Modifier) {
    val c = Rc.colors
    Box(modifier.fillMaxWidth().heightIn(min = 56.dp).background(c.mayday).testTag("sos_hold_strip"), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).heightIn(min = 56.dp).background(Color.Black.copy(alpha = 0.28f)))
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RcIcon(R.drawable.ic_warning, null, tint = c.onMayday)
            Text(
                stringResource(if (progress >= 1f) R.string.sos_sent else R.string.sos_keep_holding),
                style = MaterialTheme.typography.titleMedium, color = c.onMayday,
            )
        }
    }
}

/**
 * Hold to talk, in the composer: a 56 dp disc. Press to listen, let go to stop. While listening it turns red and
 * contour rings around it grow with the loudness of the voice.
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
    val accent = MaterialTheme.colorScheme.primary
    val disc = if (listening) c.mayday else if (ready) accent else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (listening) c.onMayday else if (ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val press = rememberUpdatedState(onPress)
    val release = rememberUpdatedState(onRelease)
    Box(
        modifier
            .size(56.dp)
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
        if (listening) Canvas(Modifier.size(56.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            for (i in 1..3) {
                val r = 26.dp.toPx() + i * 5.dp.toPx() + level * i * 5.dp.toPx()
                drawCircle(c.mayday.copy(alpha = 0.55f / i), radius = r, center = center, style = Stroke(width = 2.dp.toPx()))
            }
        }
        Box(Modifier.size(52.dp).clip(CircleShape).background(disc), contentAlignment = Alignment.Center) {
            RcIcon(if (ready || listening) R.drawable.ic_mic else R.drawable.ic_mic_off, null, tint = fg, size = 26.dp)
        }
    }
}

/**
 * SOS on the screens without the composer (chat list, Tasks, menu screens): a slim bar under the content with the same
 * red disc at the bottom left, where it sits in the composer too. It covers nothing. While it is held, the hold strip
 * shows above it.
 */
@Composable
fun SosBar(onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    val progress = remember { mutableFloatStateOf(0f) }
    var holding by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().testTag("sos_bar")) {
        if (holding || progress.floatValue >= 1f) SosHoldStrip(progress.floatValue)
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                Hairline()
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SosButton(onConfirm, progress, { holding = it })
                    Text(stringResource(R.string.sos_hold), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * SOS on the screens without the composer (Tasks and the menu screens): the same red disc at the bottom left, where
 * the left thumb reaches it, with the hold strip across the bottom while it is held.
 */
@Composable
fun SosDock(onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    val progress = remember { mutableFloatStateOf(0f) }
    var holding by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
        if (holding || progress.floatValue >= 1f) SosHoldStrip(progress.floatValue, Modifier.padding(bottom = 72.dp))
        // A ring of the page colour lifts the disc off the cards that scroll under it.
        Box(Modifier.padding(start = 6.dp, bottom = 6.dp).size(60.dp).background(MaterialTheme.colorScheme.background, CircleShape), contentAlignment = Alignment.Center) {
            SosButton(onConfirm, progress, { holding = it })
        }
    }
}

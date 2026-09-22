package io.snailrun.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import io.snailrun.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * A control that only fires once it has been held.
 *
 * For the one action in this app that cannot be taken back. Finishing a run closes it and
 * drops the recorder's state; there is no undo, and the button sits under the thumb of
 * somebody who is out of breath and looking at the pavement. A tap is the wrong gesture
 * for that — a confirmation dialog is the usual answer and is worse, because it puts a
 * second target on screen for a shaking hand and trains people to dismiss it.
 *
 * Holding is the answer because it cannot happen by accident and needs no second target.
 * The bar filling underneath is the whole affordance: it starts the moment the finger
 * lands, so the gesture explains itself the first time somebody brushes the control, and
 * releasing early visibly loses the progress rather than silently doing nothing.
 */
@Composable
fun HoldButton(
    label: String,
    icon: Painter,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    holdMillis: Long = 1_500,
    contentColor: Color = MaterialTheme.colorScheme.secondary,
    iconSize: Dp = 18.dp,
) {
    var holding by remember { mutableStateOf(false) }
    val confirm by rememberUpdatedState(onConfirmed)
    val view = LocalView.current

    // Animated rather than stepped so the bar is smooth without a frame loop of its own,
    // and instant on release so an abandoned hold reads as abandoned.
    val progress by animateFloatAsState(
        targetValue = if (holding) 1f else 0f,
        animationSpec = tween(durationMillis = if (holding) holdMillis.toInt() else 120),
        label = "hold",
    )

    LaunchedEffect(holding) {
        if (!holding) return@LaunchedEffect
        delay(holdMillis)
        // A buzz at the moment it takes, because by then the runner is as likely to be
        // looking at the road as at the phone.
        @Suppress("DEPRECATION")
        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        holding = false
        confirm()
    }

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .pointerInput(holdMillis) {
                detectTapGestures(
                    onPress = {
                        holding = true
                        // Returns when the finger lifts or the gesture is cancelled,
                        // which is the only signal that the hold was abandoned.
                        tryAwaitRelease()
                        holding = false
                    },
                )
            }
            // One target to screen readers, and a plain activation for them: holding is a
            // guard against a slip, and someone driving this by voice or switch is not
            // going to make one.
            .semantics(mergeDescendants = true) {
                contentDescription = "$label. Hold to confirm."
                onClick(label = label) { confirm(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Behind the label rather than under it: a separate progress bar would take a row
        // of its own on the screen with the least room to spare.
        Box(
            Modifier
                .matchParentSize()
                .fillFraction(progress)
                .background(contentColor.copy(alpha = 0.18f)),
        )

        Row(
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(iconSize),
            )
            Spacer(Modifier.size(Spacing.s))
            Text(
                text = if (progress > 0.02f) "KEEP HOLDING" else label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = contentColor,
            )
        }
    }
}

/**
 * A fraction of the available width, grown from the left.
 *
 * The child is *measured* narrow rather than merely reported narrow — that distinction is
 * the whole function. Reporting a smaller size out of `layout` changes what the parent
 * lays out around, and nothing about what the child draws, so a background modifier would
 * happily paint the full width behind a box claiming to be a tenth of it.
 *
 * The slot reported back is always the full width, so the label behind is not nudged
 * about as the bar fills.
 */
private fun Modifier.fillFraction(fraction: Float): Modifier =
    layout { measurable, constraints ->
        val width = (constraints.maxWidth * fraction.coerceIn(0f, 1f)).toInt()
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
    }

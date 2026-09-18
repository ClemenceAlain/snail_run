package io.snailrun.ui.record

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.painterResource
import io.snailrun.R
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.snailrun.domain.coach.SegmentKind
import io.snailrun.domain.coach.Workout
import io.snailrun.domain.coach.WorkoutProgress
import io.snailrun.domain.model.GpsQuality
import io.snailrun.domain.model.RunStatus
import io.snailrun.tracking.RecordingState
import io.snailrun.ui.components.MetricReadout
import io.snailrun.ui.components.SnailCard
import io.snailrun.ui.format.RunFormat
import io.snailrun.ui.theme.SnailType
import io.snailrun.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * The screen a runner looks at mid-stride: one hero number, two supporting ones, and a
 * control big enough to hit without stopping. Everything else is whitespace.
 */
@Composable
fun RecordScreen(
    state: RecordingState,
    gpsEnabled: Boolean,
    demoMode: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    todaysSession: Workout? = null,
    armedSession: Workout? = null,
    onArmSession: (Workout?) -> Unit = {},
    onNextSegment: () -> Unit = {},
    onEndSession: () -> Unit = {},
) {
    val active = state as? RecordingState.Active

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusRow(
            isRecording = active != null && !active.isPaused,
            isPaused = active?.isPaused == true,
            isAutoPaused = active?.metrics?.status == RunStatus.PAUSED_AUTO,
            quality = active?.metrics?.gpsQuality ?: GpsQuality.NO_FIX,
            gpsEnabled = gpsEnabled,
            demoMode = demoMode,
        )

        Spacer(Modifier.height(Spacing.l))

        if (active?.workout != null) {
            SessionPanel(progress = active.workout, onNext = onNextSegment, onEnd = onEndSession)
            Spacer(Modifier.height(Spacing.l))
        } else if (active == null && todaysSession != null) {
            TodaysSession(
                session = todaysSession,
                armed = armedSession != null,
                onArm = { onArmSession(if (armedSession == null) todaysSession else null) },
            )
            Spacer(Modifier.height(Spacing.l))
        }

        MetricReadout(
            value = RunFormat.duration(active?.metrics?.activeDurationMs ?: 0L),
            caption = "elapsed",
            valueStyle = SnailType.metricHero,
        )

        Spacer(Modifier.height(Spacing.section))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            MetricReadout(
                value = RunFormat.distanceKm(active?.metrics?.distanceMeters ?: 0.0),
                caption = "km",
            )
            MetricReadout(
                value = RunFormat.pace(active?.metrics?.paceSecPerKm),
                caption = "/km",
            )
        }

        Spacer(Modifier.height(Spacing.xxl))

        // The flexible space is the design: it keeps the numbers high, where they are
        // read, and the control low, where the thumb is.
        Spacer(Modifier.weight(1f))

        Controls(
            isRecording = active != null,
            isPaused = active?.isPaused == true,
            onStart = onStart,
            onPause = onPause,
            onResume = onResume,
            onFinish = onFinish,
        )

        Spacer(Modifier.height(Spacing.huge))
    }
}

/**
 * What the coach has down for today, before the run starts.
 *
 * Loading it is a separate press from starting, because opening the app to go for an easy
 * half hour should not mean fighting off a tempo you never asked for.
 */
@Composable
private fun TodaysSession(session: Workout, armed: Boolean, onArm: () -> Unit) {
    SnailCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = if (armed) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        val content = if (armed) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (armed) "Loaded: ${session.type.label}" else "Today: ${session.type.label}",
                    style = MaterialTheme.typography.titleMedium,
                    color = content,
                )
                Text(
                    text = sessionLine(session),
                    style = MaterialTheme.typography.bodyMedium,
                    color = content,
                )
            }
            TextButton(onClick = onArm) { Text(if (armed) "Unload" else "Load") }
        }
    }
}

/** Where the runner is in the session, while they are in it. */
@Composable
private fun SessionPanel(progress: WorkoutProgress, onNext: () -> Unit, onEnd: () -> Unit) {
    SnailCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = when {
            progress.complete -> MaterialTheme.colorScheme.surfaceContainer
            progress.segment.kind == SegmentKind.Work -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
    ) {
        val content = when {
            progress.complete -> MaterialTheme.colorScheme.onSurface
            progress.segment.kind == SegmentKind.Work -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSecondaryContainer
        }

        if (progress.complete) {
            Text("Session done", style = MaterialTheme.typography.titleMedium, color = content)
            Text(
                text = "Keep running as long as you like.",
                style = MaterialTheme.typography.bodyMedium,
                color = content,
            )
            return@SnailCard
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = buildString {
                        append(progress.segment.label)
                        if (progress.segment.isRep) {
                            append("  ${progress.segment.repIndex}/${progress.segment.repCount}")
                        }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = content,
                )
                progress.segment.paceSecPerKm?.let { band ->
                    Text(
                        text = paceBand(band),
                        style = MaterialTheme.typography.bodyMedium,
                        color = content,
                    )
                }
            }
            // What is left, in whatever the step was prescribed in. A step given a time
            // counts down; one given a distance counts down in metres.
            Text(
                text = progress.remainingMs?.let { RunFormat.duration(it) }
                    ?: progress.remainingM?.let { "${it.roundToInt()}\u00A0m" }
                    ?: "",
                style = SnailType.metricSmall,
                color = content,
                maxLines = 1,
                softWrap = false,
            )
        }

        progress.next?.let { next ->
            Text(
                text = "then ${next.label.lowercase()}",
                style = MaterialTheme.typography.bodyMedium,
                color = content,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            TextButton(onClick = onNext) { Text("NEXT") }
            TextButton(onClick = onEnd) { Text("END SESSION") }
        }
    }
}

private fun sessionLine(session: Workout): String {
    val work = session.steps.firstOrNull { it.repeats > 1 }
        ?: return "${RunFormat.distanceKm(session.totalMeters)}\u00A0km"
    val each = work.durationMs?.let { "${(it / 60_000.0).roundToInt()}\u00A0min" }
        ?: work.distanceM?.let { "${it.roundToInt()}\u00A0m" }
        ?: ""
    return "${work.repeats} × $each · ${RunFormat.distanceKm(session.totalMeters)}\u00A0km"
}

private fun paceBand(range: ClosedFloatingPointRange<Double>): String =
    if (range.start == range.endInclusive) {
        "${RunFormat.pace(range.start)}\u00A0/km"
    } else {
        "${RunFormat.pace(range.start)}–${RunFormat.pace(range.endInclusive)}\u00A0/km"
    }

@Composable
private fun StatusRow(
    isRecording: Boolean,
    isPaused: Boolean,
    isAutoPaused: Boolean,
    quality: GpsQuality,
    gpsEnabled: Boolean,
    demoMode: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.l),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                // Said plainly: a runner who sees "Paused" without having pressed
                // anything needs to know the app did it, not that they mis-tapped.
                isAutoPaused -> "Auto-paused"
                isPaused -> "Paused"
                isRecording -> "Recording"
                else -> "Ready"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        GpsIndicator(quality = quality, gpsEnabled = gpsEnabled, demoMode = demoMode)
    }
}

@Composable
private fun GpsIndicator(quality: GpsQuality, gpsEnabled: Boolean, demoMode: Boolean) {
    val color = when {
        // Loud on purpose: a demo run is saved like any other, and the only thing that
        // stops it being mistaken for a real one is being told, every second of it.
        demoMode -> MaterialTheme.colorScheme.error
        !gpsEnabled -> MaterialTheme.colorScheme.error
        quality == GpsQuality.GOOD -> MaterialTheme.colorScheme.primary
        quality == GpsQuality.OK -> MaterialTheme.colorScheme.tertiary
        quality == GpsQuality.POOR -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }
    val label = when {
        demoMode -> "Demo run — not real"
        !gpsEnabled -> "Location off"
        quality == GpsQuality.GOOD -> "GPS good"
        quality == GpsQuality.OK -> "GPS ok"
        quality == GpsQuality.POOR -> "GPS weak"
        else -> "Finding GPS"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.size(Spacing.s))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Controls(
    isRecording: Boolean,
    isPaused: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "press",
    )
    val containerColor by animateColorAsState(
        targetValue = when {
            isPaused -> MaterialTheme.colorScheme.primary
            isRecording -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.primary
        },
        label = "control",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = when {
                !isRecording -> onStart
                isPaused -> onResume
                else -> onPause
            },
            modifier = Modifier
                .size(120.dp)
                .scale(scale),
            shape = CircleShape,
            interactionSource = interaction,
            colors = ButtonDefaults.buttonColors(containerColor = containerColor),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Icon(
                painter = painterResource(
                    if (!isRecording || isPaused) R.drawable.ic_play else R.drawable.ic_pause
                ),
                contentDescription = when {
                    !isRecording -> "Start run"
                    isPaused -> "Resume run"
                    else -> "Pause run"
                },
                modifier = Modifier.size(44.dp),
                tint = if (isRecording && !isPaused) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
            )
        }

        Spacer(Modifier.height(Spacing.l))

        if (isRecording) {
            TextButton(onClick = onFinish) {
                Icon(
                    painter = painterResource(R.drawable.ic_stop),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(Spacing.s))
                Text(
                    text = "FINISH",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        } else {
            Text(
                text = "START",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import io.snailrun.domain.coach.SegmentKind
import io.snailrun.domain.coach.Workout
import io.snailrun.domain.coach.WorkoutProgress
import io.snailrun.domain.coach.WorkoutSegment
import io.snailrun.domain.coach.WorkoutType
import io.snailrun.domain.model.GpsQuality
import io.snailrun.domain.model.RunStatus
import io.snailrun.tracking.RecordingState
import io.snailrun.ui.components.Badge
import io.snailrun.ui.components.BadgeTone
import io.snailrun.ui.components.HoldButton
import io.snailrun.ui.components.MetricReadout
import io.snailrun.ui.components.SessionDetail
import io.snailrun.ui.components.SessionSheet
import io.snailrun.ui.components.SnailCard
import io.snailrun.ui.format.NBSP
import io.snailrun.ui.format.RunFormat
import io.snailrun.ui.format.SessionFormat
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
    todaysStrength: Workout? = null,
    onStartStrength: (Workout) -> Unit = {},
    armedSession: Workout? = null,
    onArmSession: (Workout?) -> Unit = {},
    onNextSegment: () -> Unit = {},
    onEndSession: () -> Unit = {},
) {
    val active = state as? RecordingState.Active

    // One sheet for the whole screen, whichever card opened it. The session being run
    // and the session being considered are the same thing said at two different moments,
    // and they read better as one component than as two that have to be kept in step.
    var detail by remember { mutableStateOf<SessionDetailRequest?>(null) }
    detail?.let { request ->
        SessionSheet(
            workout = request.workout,
            onDismiss = { detail = null },
            segments = request.segments,
            currentSegment = request.currentSegment,
            subtitle = request.subtitle,
        )
    }

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
            SessionPanel(
                progress = active.workout,
                onNext = onNextSegment,
                onEnd = onEndSession,
                onShowDetail = {
                    detail = SessionDetailRequest(
                        // The workout itself is gone by now — see the comment on
                        // RecordingState.Active.workoutSegments — so the sheet is given
                        // the sequence directly and a shell to title it with.
                        workout = Workout(
                            type = active.workoutType ?: WorkoutType.Easy,
                            totalMeters = 0.0,
                            steps = emptyList(),
                            reason = "",
                        ),
                        segments = active.workoutSegments,
                        currentSegment = active.workout.segment.index,
                        subtitle = "step ${active.workout.segmentsDone + 1} of " +
                            "${active.workout.segmentCount}",
                    )
                },
            )
            Spacer(Modifier.height(Spacing.l))
        } else if (active == null && (todaysSession != null || todaysStrength != null)) {
            TodaysSession(
                session = todaysSession,
                strength = todaysStrength,
                armed = armedSession != null,
                onArm = { onArmSession(if (armedSession == null) todaysSession else null) },
                onShowDetail = { workout ->
                    detail = SessionDetailRequest(workout = workout)
                },
                onStartStrength = onStartStrength,
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

/** What the sheet at the top of this screen is currently showing. */
private data class SessionDetailRequest(
    val workout: Workout,
    val segments: List<WorkoutSegment>? = null,
    val currentSegment: Int? = null,
    val subtitle: String? = null,
)

/**
 * What the coach has down for today, before the run starts.
 *
 * Loading it is a separate press from starting, because opening the app to go for an easy
 * half hour should not mean fighting off a tempo you never asked for.
 *
 * The strength session, when there is one, is a line rather than a button. There is
 * nothing to record and nothing to arm — it is here so that a rest day with twenty
 * minutes of squats on it does not look like a rest day with nothing on it.
 */
@Composable
private fun TodaysSession(
    session: Workout?,
    strength: Workout?,
    armed: Boolean,
    onArm: () -> Unit,
    onShowDetail: (Workout) -> Unit,
    onStartStrength: (Workout) -> Unit,
) {
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

        if (session != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onShowDetail(session) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (armed) "Loaded: ${session.name}" else "Today: ${session.name}",
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

            // Every step of it, right here. A session is read in the thirty seconds
            // before it starts, and asking the runner to open something to find out what
            // they are about to do is thirty seconds they do not have.
            //
            // Unless there is only one step, in which case the line above has already
            // said it and a second copy of "8.0 km easy" reads as a rendering bug.
            if (session.steps.size > 1) {
                Spacer(Modifier.height(Spacing.m))
                SessionDetail(workout = session)
            }
        }

        strength?.let { extra ->
            if (session != null) {
                Spacer(Modifier.height(Spacing.m))
                HorizontalDivider()
                Spacer(Modifier.height(Spacing.m))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f).clickable { onShowDetail(extra) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Badge("Strength", BadgeTone.Other)
                        Spacer(Modifier.size(Spacing.s))
                        Text(
                            text = extra.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = content,
                        )
                    }
                    Text(
                        text = "${extra.steps.size} exercises" +
                            (SessionFormat.estimate(extra)?.let { ", $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = content,
                    )
                }
                // Its own button rather than the Load/Unload the running session gets:
                // there is nothing to arm, because nothing is being recorded. Pressing
                // this goes straight to a screen that counts you through it.
                TextButton(onClick = { onStartStrength(extra) }) { Text("Start") }
            }
        }
    }
}

/** Where the runner is in the session, while they are in it. */
@Composable
private fun SessionPanel(
    progress: WorkoutProgress,
    onNext: () -> Unit,
    onEnd: () -> Unit,
    onShowDetail: () -> Unit,
) {
    SnailCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onShowDetail),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_snail),
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(Spacing.s))
                Column {
                    Text(
                        text = "Session done",
                        style = MaterialTheme.typography.titleMedium,
                        color = content,
                    )
                    Text(
                        text = "Keep running as long as you like.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = content,
                    )
                }
            }
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
                    ?: progress.remainingM?.let { "${it.roundToInt()}${NBSP}m" }
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

        Text(
            text = "step ${progress.segmentsDone + 1} of ${progress.segmentCount} · tap for the session",
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            TextButton(onClick = onNext) { Text("NEXT") }
            TextButton(onClick = onEnd) { Text("END SESSION") }
        }
    }
}

private fun sessionLine(session: Workout): String {
    val total = SessionFormat.kmWithUnit(session.totalMeters)
    val work = session.steps.firstOrNull { it.repeats > 1 } ?: return total
    val each = work.durationMs?.let { SessionFormat.duration(it) }
        ?: work.distanceM?.let { SessionFormat.distance(it) }
        ?: return total
    return "${work.repeats} × $each · $total"
}

private fun paceBand(range: ClosedFloatingPointRange<Double>): String = SessionFormat.pace(range)

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
        Badge(
            // Said plainly: a runner who sees "Paused" without having pressed anything
            // needs to know the app did it, not that they mis-tapped.
            text = when {
                isAutoPaused -> "Auto-paused"
                isPaused -> "Paused"
                isRecording -> "Recording"
                else -> "Ready"
            },
            tone = when {
                isAutoPaused || isPaused -> BadgeTone.Long
                isRecording -> BadgeTone.Hard
                else -> BadgeTone.Quiet
            },
        )
        GpsIndicator(quality = quality, gpsEnabled = gpsEnabled, demoMode = demoMode)
    }
}

/**
 * The fix quality, as a pill.
 *
 * A demo run says so here rather than anywhere quieter, and in the loudest tone the app
 * has: a demo run is saved like any other, and the only thing stopping it being mistaken
 * for a real one is being told, every second of it.
 */
@Composable
private fun GpsIndicator(quality: GpsQuality, gpsEnabled: Boolean, demoMode: Boolean) {
    if (demoMode) {
        Badge("Demo — not real", BadgeTone.Long)
        return
    }
    if (!gpsEnabled) {
        Badge("Location off", BadgeTone.Long)
        return
    }
    Badge(
        text = when (quality) {
            GpsQuality.GOOD -> "GPS good"
            GpsQuality.OK -> "GPS ok"
            GpsQuality.POOR -> "GPS weak"
            else -> "Finding GPS"
        },
        tone = if (quality == GpsQuality.GOOD) BadgeTone.Easy else BadgeTone.Quiet,
    )
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
            // Held, not tapped. Finishing writes the run and drops the recorder's state,
            // there is no undo, and the control sits under the thumb of somebody out of
            // breath looking at the pavement.
            HoldButton(
                label = "FINISH",
                icon = painterResource(R.drawable.ic_stop),
                onConfirmed = onFinish,
            )
        } else {
            Text(
                text = "START",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

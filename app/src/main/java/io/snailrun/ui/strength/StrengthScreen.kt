package io.snailrun.ui.strength

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.snailrun.R
import io.snailrun.domain.coach.StrengthProgress
import io.snailrun.domain.coach.StrengthStage
import io.snailrun.domain.coach.StrengthStageKind
import io.snailrun.ui.components.Badge
import io.snailrun.ui.components.BadgeTone
import io.snailrun.ui.components.MetricReadout
import io.snailrun.ui.components.SnailCard
import io.snailrun.ui.theme.SnailType
import io.snailrun.ui.theme.Spacing

/**
 * One exercise at a time, in letters you can read from the floor.
 *
 * The constraint that shapes all of it: the phone is on a mat two feet away and the
 * reader is upside down in a plank or halfway through a squat. So there is exactly one
 * thing at hero size — what to do right now — and everything else is a whisper around it.
 * No metrics row, because there is nothing to measure; no map, no pace, no distance.
 *
 * The screen is also a different colour depending on whether you are working or resting,
 * which is the one piece of state readable without focusing on anything.
 */
@Composable
fun StrengthScreen(
    state: StrengthUiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val workout = state.workout ?: run { Box(modifier.fillMaxSize()); return }
    val progress = state.progress

    val resting = progress?.stage?.kind == StrengthStageKind.Rest
    val background by animateColorAsState(
        targetValue = when {
            state.complete -> MaterialTheme.colorScheme.surface
            resting -> MaterialTheme.colorScheme.secondaryContainer
            state.running -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surface
        },
        label = "stage",
    )
    val content = when {
        state.complete -> MaterialTheme.colorScheme.onSurface
        resting -> MaterialTheme.colorScheme.onSecondaryContainer
        state.running -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .padding(horizontal = Spacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(Spacing.l))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(workout.name, style = MaterialTheme.typography.titleMedium, color = content)
            if (progress != null && !state.complete) {
                Badge(
                    "${progress.stagesDone + 1} of ${progress.stageCount}",
                    BadgeTone.Quiet,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        when {
            state.complete -> Finished(workout.name)
            progress != null -> Stage(progress, content)
        }

        Spacer(Modifier.weight(1f))

        Controls(
            state = state,
            progress = progress,
            onStart = onStart,
            onPause = onPause,
            onNext = onNext,
            onDone = onDone,
        )
        Spacer(Modifier.height(Spacing.huge))
    }
}

/** What to do now, and what is after it. */
@Composable
private fun Stage(progress: StrengthProgress, content: androidx.compose.ui.graphics.Color) {
    val stage = progress.stage
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Badge(
            text = if (stage.kind == StrengthStageKind.Rest) "Rest" else "Set ${stage.set} of ${stage.setCount}",
            tone = if (stage.kind == StrengthStageKind.Rest) BadgeTone.Easy else BadgeTone.Hard,
        )
        Spacer(Modifier.height(Spacing.l))

        Text(
            text = stage.exercise,
            style = MaterialTheme.typography.headlineMedium,
            color = content,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.m))

        // The hero: a countdown on anything held, the rep count on anything counted.
        // Never both, because a set is one or the other and showing a timer beside
        // "12 squats" invites somebody to race it.
        MetricReadout(
            value = progress.remainingMs?.let { seconds(it) } ?: "${stage.reps}",
            caption = if (progress.remainingMs != null) "seconds left" else "reps",
            valueStyle = SnailType.metricHero,
        )

        if (stage.perSide) {
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = "each side",
                style = MaterialTheme.typography.bodyLarge,
                color = content,
            )
        }

        progress.next?.let { next ->
            Spacer(Modifier.height(Spacing.xl))
            Text(
                text = "then ${describe(next)}",
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Finished(name: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            painter = painterResource(R.drawable.ic_snail),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(Spacing.l))
        Text("$name done", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(Spacing.s))
        Text(
            // Said plainly, because it is the one thing about this session somebody might
            // otherwise go looking for: nothing was recorded, and nothing was meant to be.
            text = "Nothing was recorded — there is no distance to record. Your running " +
                "week is unchanged.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Controls(
    state: StrengthUiState,
    progress: StrengthProgress?,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onDone: () -> Unit,
) {
    if (state.complete) {
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        return
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // The big one is whichever action the moment calls for. On a counted set that is
        // "I have finished these ten", which is also the only way the set can end — so it
        // is the primary control rather than a secondary one beside a play button.
        val counted = state.running && progress?.remainingMs == null && !state.complete
        Button(
            onClick = when {
                !state.running -> onStart
                counted -> onNext
                else -> onPause
            },
            modifier = Modifier.size(140.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            if (counted) {
                Text("DONE", style = MaterialTheme.typography.labelLarge)
            } else {
                Icon(
                    painter = painterResource(
                        if (state.running) R.drawable.ic_pause else R.drawable.ic_play
                    ),
                    contentDescription = if (state.running) "Pause" else "Start",
                    modifier = Modifier.size(44.dp),
                )
            }
        }

        Spacer(Modifier.height(Spacing.m))
        if (state.running && !counted) {
            TextButton(onClick = onNext) { Text("SKIP") }
        } else if (!state.running) {
            Text(
                text = "START",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

private fun describe(stage: StrengthStage): String = when {
    stage.kind == StrengthStageKind.Rest -> "rest ${stage.seconds} s"
    stage.seconds != null -> "${stage.exercise.lowercase()}, ${stage.seconds} s"
    stage.reps != null -> "${stage.exercise.lowercase()}, ${stage.reps}"
    else -> stage.exercise.lowercase()
}

/** Whole seconds, rounded up: a countdown that shows 0 for a second has already lied. */
private fun seconds(millis: Long): String = "${(millis + 999) / 1000}"


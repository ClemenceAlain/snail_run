package io.snailrun.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.snailrun.domain.coach.Workout
import io.snailrun.domain.coach.WorkoutSegment
import io.snailrun.domain.coach.WorkoutSegments
import io.snailrun.domain.coach.WorkoutType
import io.snailrun.ui.format.SessionBlock
import io.snailrun.ui.format.SessionFormat
import io.snailrun.ui.theme.Spacing

/**
 * A session, written out in the order it is run.
 *
 * The same component on the Coach tab and on the Record screen, on purpose. A runner who
 * reads "6 × 45 s uphill" on Monday and then sees a differently worded version of it on
 * Thursday with the watch running has to work out whether the two are the same session,
 * and mid-warm-up is the worst possible moment to be asking that question.
 *
 * [currentSegment] is the index the recorder is on, or null when nothing is running. It
 * lights one row and marks the ones behind it as done, which is the whole difference
 * between a plan and a place in a plan.
 */
@Composable
fun SessionDetail(
    workout: Workout,
    modifier: Modifier = Modifier,
    segments: List<WorkoutSegment> = remembered(workout),
    currentSegment: Int? = null,
) {
    if (workout.type == WorkoutType.Strength) {
        StrengthDetail(workout, modifier)
        return
    }

    val blocks = SessionFormat.blocks(segments)
    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(Spacing.s))
            BlockRow(
                block = block,
                current = currentSegment != null && currentSegment in block.range,
                done = currentSegment != null && currentSegment > block.range.last,
                // Which rep of the block, counted from where the block starts rather
                // than read off the segment: a jog sits between every pair, so the rep
                // number is not the offset.
                repDone = currentSegment
                    ?.takeIf { it in block.range }
                    ?.let { segments.getOrNull(it)?.repIndex },
            )
        }
    }
}

@Composable
private fun BlockRow(block: SessionBlock, current: Boolean, done: Boolean, repDone: Int?) {
    val faded = done && !current
    val accent = MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (current) accent.copy(alpha = 0.14f) else Color.Transparent)
            .padding(vertical = Spacing.xs, horizontal = Spacing.s),
        verticalAlignment = Alignment.Top,
    ) {
        // The pill says what kind of running this is, where a coloured bar only hinted
        // at it and needed a legend nobody was given.
        Badge(
            text = block.kind.label,
            tone = if (faded) BadgeTone.Quiet else block.kind.badgeTone,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.width(Spacing.m))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = block.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (faded) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (repDone != null) {
                    Spacer(Modifier.width(Spacing.s))
                    Badge("on $repDone", BadgeTone.Hard)
                }
            }
            if (block.detail.isNotEmpty()) {
                Text(
                    text = block.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            block.recovery?.let {
                Text(
                    text = "↳ $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The reinforcement session: sets and repetitions, which are neither of the above. */
@Composable
private fun StrengthDetail(workout: Workout, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        workout.steps.forEach { step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary),
                )
                Spacer(Modifier.width(Spacing.m))
                Text(
                    text = SessionFormat.strengthStep(step),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

/**
 * Unrolling is a loop over a handful of steps, so this is cheap — but it happens on every
 * recomposition of a sheet the user is scrolling, and the result is keyed by a value that
 * changes only when the session does.
 */
@Composable
private fun remembered(workout: Workout): List<WorkoutSegment> =
    androidx.compose.runtime.remember(workout) { WorkoutSegments.of(workout) }

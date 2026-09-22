package io.snailrun.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.snailrun.R
import io.snailrun.domain.coach.Workout
import io.snailrun.domain.coach.WorkoutSegment
import io.snailrun.domain.coach.WorkoutType
import io.snailrun.ui.format.SessionFormat
import io.snailrun.ui.theme.SnailType
import io.snailrun.ui.theme.Spacing

/**
 * The whole session, on a sheet.
 *
 * A sheet rather than a screen because a session is read in the thirty seconds before it
 * is run, and a push that has to be navigated back out of is a worse fit for that than
 * something dismissed by dragging it away. A sheet rather than an inline expansion
 * because a ten-rep hill session is twenty rows, and twenty rows opening inside a column
 * the runner can drag days around in moves the ground under their finger.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheet(
    workout: Workout,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    segments: List<WorkoutSegment>? = null,
    currentSegment: Int? = null,
    strength: Workout? = null,
    action: Pair<String, () -> Unit>? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(
                    start = Spacing.screen,
                    end = Spacing.screen,
                    bottom = Spacing.huge,
                ),
        ) {
            SessionHeader(workout, subtitle)

            if (workout.type != WorkoutType.Rest) {
                Spacer(Modifier.height(Spacing.l))
                SessionDetail(
                    workout = workout,
                    segments = segments ?: io.snailrun.domain.coach.WorkoutSegments.of(workout),
                    currentSegment = currentSegment,
                )
            }

            // A session opened mid-run has no reason attached: the plan it came from is
            // four days old and the runner is already doing it.
            if (workout.reason.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.l))
                Text(
                    text = workout.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            strength?.let { extra ->
                Spacer(Modifier.height(Spacing.l))
                HorizontalDivider()
                Spacer(Modifier.height(Spacing.l))
                SessionHeader(extra, subtitle = null)
                Spacer(Modifier.height(Spacing.m))
                SessionDetail(workout = extra)
                Spacer(Modifier.height(Spacing.m))
                Text(
                    text = extra.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            action?.let { (label, onClick) ->
                Spacer(Modifier.height(Spacing.xl))
                Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
        }
    }
}

@Composable
private fun SessionHeader(workout: Workout, subtitle: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = workout.name, style = MaterialTheme.typography.headlineSmall)
            val under = subtitle ?: SessionFormat.estimate(workout)
            if (under != null) {
                Text(
                    text = under,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when {
            workout.totalMeters > 0.0 -> Text(
                text = SessionFormat.kmWithUnit(workout.totalMeters),
                style = SnailType.metricSmall,
                maxLines = 1,
                softWrap = false,
            )
            // The one session with nothing to measure in kilometres gets the snail
            // instead of an empty column. It is also the only place in the app a rest
            // day and a strength day can be told apart at arm's length.
            workout.type == WorkoutType.Strength -> Icon(
                painter = painterResource(R.drawable.ic_snail),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(28.dp),
            )
            // A live session: its total is not carried through the recorder, and a zero
            // beside a session somebody is halfway through would be read as one.
            else -> Unit
        }
    }
}

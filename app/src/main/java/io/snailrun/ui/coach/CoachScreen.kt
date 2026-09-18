package io.snailrun.ui.coach

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.snailrun.domain.coach.Confidence
import io.snailrun.domain.coach.PlannedDay
import io.snailrun.domain.coach.Races
import io.snailrun.domain.coach.WeekPlan
import io.snailrun.domain.coach.WorkoutStep
import io.snailrun.domain.coach.WorkoutType
import io.snailrun.ui.components.SnailCard
import io.snailrun.ui.format.RunFormat
import io.snailrun.ui.theme.SnailType
import io.snailrun.ui.theme.Spacing
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

// Built per call: a formatter cached at class-init keeps the locale the app
// started with, which is wrong after the user changes the system language.
private fun dayformat() = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

@Composable
fun CoachScreen(
    state: CoachUiState,
    onExpand: (LocalDate) -> Unit,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    val plan = state.plan
    if (!state.loaded || plan == null) {
        Box(modifier.fillMaxSize())
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screen,
            end = Spacing.screen,
            top = Spacing.l,
            bottom = Spacing.huge,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        item {
            Text(
                text = "Coach",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = Spacing.s),
            )
        }

        item { WeekSummary(plan) }

        if (state.goal != null) {
            item { RaceCard(plan, state) }
        }

        items(plan.days, key = { it.date.toString() }) { day ->
            DayRow(
                day = day,
                today = today,
                expanded = state.expanded == day.date,
                onClick = { onExpand(day.date) },
            )
        }

        item { FitnessFooter(plan) }
    }
}

/**
 * The week's size and the sentence that decided it.
 *
 * The note is the whole feature. Anyone can print "32 km"; what makes a plan followable
 * is knowing it is 32 because last week was 30.
 */
@Composable
private fun WeekSummary(plan: WeekPlan) {
    SnailCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text("This week", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Last week ${km(plan.lastWeekMeters)} · " +
                        "four-week average ${km(plan.chronicWeeklyMeters)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(text = km(plan.plannedMeters), style = SnailType.metricSmall)
        }

        Spacer(Modifier.height(Spacing.m))
        Text(
            text = plan.note,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RaceCard(plan: WeekPlan, state: CoachUiState) {
    val goal = state.goal ?: return
    SnailCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        val content = MaterialTheme.colorScheme.onSecondaryContainer
        val weeks = Races.weeksTo(goal, plan.weekStart)
        Text(
            text = "${distanceName(goal.distanceMeters)} on ${dayformat().format(goal.date)}",
            style = MaterialTheme.typography.titleMedium,
            color = content,
        )
        Text(
            text = buildString {
                append(if (weeks <= 0) "Race week" else "$weeks weeks away")
                plan.phase?.let { append(" · ${it.label}") }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = content,
        )
        plan.predictedTimeMs?.let { predicted ->
            Spacer(Modifier.height(Spacing.s))
            Text(
                // Deliberately not called a goal time. It is what today's fitness is
                // worth on a flat course on a good day, and exactly one of those three
                // is usually true.
                text = "On today's fitness, ${RunFormat.duration(predicted)}.",
                style = MaterialTheme.typography.bodyMedium,
                color = content,
            )
        }
    }
}

@Composable
private fun DayRow(day: PlannedDay, today: LocalDate, expanded: Boolean, onClick: () -> Unit) {
    val rest = day.workout.type == WorkoutType.Rest
    SnailCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (rest) Modifier else Modifier.clickable(onClick = onClick)),
        containerColor = when {
            day.date == today -> MaterialTheme.colorScheme.primaryContainer
            rest -> MaterialTheme.colorScheme.surfaceContainerLow
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        val content = when {
            day.date == today -> MaterialTheme.colorScheme.onPrimaryContainer
            rest -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                style = SnailType.metricCaption,
                color = content,
                modifier = Modifier.width(44.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = day.workout.type.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = content,
                    // A struck-through session is one already run. Nothing is stored to
                    // say so: there is a run on that day, and that is the whole test.
                    textDecoration = if (day.done) TextDecoration.LineThrough else null,
                )
                if (!rest) {
                    Text(
                        text = summaryOf(day),
                        style = MaterialTheme.typography.bodyMedium,
                        color = content,
                    )
                }
            }
            if (!rest) {
                Text(text = km(day.workout.totalMeters), style = SnailType.metricSmall, color = content)
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(Spacing.m))
                day.workout.steps.forEach { step ->
                    Text(
                        text = describe(step),
                        style = MaterialTheme.typography.bodyMedium,
                        color = content,
                    )
                }
                Spacer(Modifier.height(Spacing.s))
                Text(
                    text = day.workout.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FitnessFooter(plan: WeekPlan) {
    val fitness = plan.fitness
    SnailCard(modifier = Modifier.fillMaxWidth()) {
        if (fitness == null) {
            Text("No fitness estimate yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = "Paces come from the fastest stretches inside your own runs. Until " +
                    "there are some from the last ten weeks, this week is easy running only — " +
                    "which is where it would start anyway.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SnailCard
        }

        Text(
            text = "Fitness ${fitness.vdot.roundToInt()} VDOT",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "From your ${distanceName(fitness.fromDistanceM)} on " +
                dayformat().format(fitness.fromDate),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(Spacing.m))
        val paces = fitness.paces
        PaceLine("Easy", "${RunFormat.pace(paces.easySecPerKm.start)}–" +
            "${RunFormat.pace(paces.easySecPerKm.endInclusive)}")
        PaceLine("Marathon", RunFormat.pace(paces.marathonSecPerKm))
        PaceLine("Threshold", RunFormat.pace(paces.thresholdSecPerKm))
        if (fitness.confidence == Confidence.Solid) {
            PaceLine("Interval", RunFormat.pace(paces.intervalSecPerKm))
            PaceLine("Repetition", RunFormat.pace(paces.repetitionSecPerKm))
        } else {
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = "Your fastest recent efforts are all under 5 km, which is too short to " +
                    "price interval pace from without overstating it. Run five kilometres hard " +
                    "and the rest unlocks.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PaceLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = "$value /km", style = SnailType.metricSmall)
    }
}

/** One line under the session name: what it is, before it is expanded. */
private fun summaryOf(day: PlannedDay): String {
    val work = day.workout.steps.firstOrNull { it.repeats > 1 }
    if (work != null) return describe(work)
    val pace = day.workout.steps.firstOrNull()?.paceSecPerKm ?: return ""
    return paceText(pace)
}

private fun describe(step: WorkoutStep): String = buildString {
    if (step.repeats > 1) append("${step.repeats} × ")
    when {
        step.distanceM != null && step.repeats > 1 -> append("${step.distanceM.roundToInt()} m")
        step.durationMs != null -> append("${(step.durationMs / 60_000.0).roundToInt()} min")
        step.distanceM != null -> append(km(step.distanceM))
    }
    append(" ")
    append(step.label.lowercase())
    step.paceSecPerKm?.let { append(" at ${paceText(it)}") }
}

private fun paceText(range: ClosedFloatingPointRange<Double>): String =
    if (range.start == range.endInclusive) {
        "${RunFormat.pace(range.start)}/km"
    } else {
        "${RunFormat.pace(range.start)}–${RunFormat.pace(range.endInclusive)}/km"
    }

private fun km(meters: Double): String = "${RunFormat.distanceKm(meters)} km"

private fun distanceName(meters: Int) = when (meters) {
    21_097 -> "half marathon"
    42_195 -> "marathon"
    else -> "${meters / 1000} km"
}

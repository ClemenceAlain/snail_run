package io.snailrun.ui.coach

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.snailrun.domain.coach.Confidence
import io.snailrun.domain.coach.PlannedDay
import io.snailrun.domain.coach.Races
import io.snailrun.domain.coach.WeekPlan
import io.snailrun.domain.coach.Workout
import io.snailrun.domain.coach.WorkoutStep
import io.snailrun.domain.coach.WorkoutType
import io.snailrun.R
import io.snailrun.ui.components.HelpButton
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
private fun rangeformat() = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

/**
 * A no-break space between a number and its unit.
 *
 * "15,00 km" is one thing to read, and a column narrow enough to break it leaves "15,00"
 * over "km" — which looks like a layout bug even when the arithmetic is right.
 */
private const val NBSP = ' '

private val CoachHelp = listOf(
    "Four weeks planned from the runs you have already done. Each week is built on the " +
        "one before it, so the block climbs rather than repeating.",
    "Hold a day to drag it somewhere else; the days it passes shift along by one. Only " +
        "the rearrangement is remembered — the plan itself is worked out afresh every " +
        "time, from your runs.",
    "Volume rises at most a tenth on last week and never past 1.3 times your four-week " +
        "average. The long run cannot grow more than a tenth either. Tap a day for why " +
        "it is the length it is.",
)

private val PacesHelp = listOf(
    "Paces come from the fastest stretches inside your own runs, put through Daniels " +
        "and Gilbert's equations. Nothing is guessed and nothing is looked up.",
    "Only efforts of 5 km and longer are trusted. A fast kilometre inside an easy run " +
        "is usually a surge, and read as a time trial it would make every pace too fast.",
    "So until there is a recent long effort, the coach writes easy and threshold work " +
        "and refuses to price an interval session at all.",
)

@Composable
fun CoachScreen(
    state: CoachUiState,
    onExpand: (LocalDate) -> Unit,
    onMove: (LocalDate, Int, Int) -> Unit,
    onResetWeek: (LocalDate) -> Unit,
    onShowWeek: (Int) -> Unit,
    onRunSession: (Workout) -> Unit,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    val plan = state.week
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Coach",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                HelpButton(title = "Coach", body = CoachHelp)
            }
        }

        item {
            WeekBar(
                plan = plan,
                index = state.weekIndex,
                count = state.weeks.size,
                today = today,
                onShowWeek = onShowWeek,
            )
        }

        if (state.goal != null) {
            item { RaceCard(plan, state) }
        }

        item(key = "days-${plan.weekStart}") {
            DraggableWeek(
                plan = plan,
                today = today,
                expanded = state.expanded,
                onExpand = onExpand,
                onMove = { from, to -> onMove(plan.weekStart, from, to) },
                onRunSession = onRunSession,
            )
        }

        if (plan.order != null) {
            item {
                TextButton(onClick = { onResetWeek(plan.weekStart) }) { Text("Put the week back") }
            }
        }

        item { FitnessCard(state) }
    }
}

// ---- the week ------------------------------------------------------------------------

/**
 * Seven cards that can be dragged past one another.
 *
 * They live in a plain `Column` inside one lazy item rather than as seven lazy items. A
 * week is seven rows and always will be, so nothing is saved by making them lazy, and
 * keeping them in one layout means the drag arithmetic is offsets within a column rather
 * than a negotiation with a scrolling viewport.
 */
@Composable
private fun DraggableWeek(
    plan: WeekPlan,
    today: LocalDate,
    expanded: LocalDate?,
    onExpand: (LocalDate) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRunSession: (Workout) -> Unit,
) {
    val heights = remember(plan.weekStart) { mutableStateMapOf<Int, Int>() }
    var dragFrom by remember(plan.weekStart) { mutableIntStateOf(-1) }
    var dragOffset by remember(plan.weekStart) { mutableFloatStateOf(0f) }
    // A drag ends with the finger lifting off the card, which is also what a tap looks
    // like. The long-press detector consumes the gesture and should stop the click on its
    // own; this makes sure of it, because a session that expands every time it is dropped
    // is a drag that feels broken.
    var swallowClick by remember(plan.weekStart) { mutableStateOf(false) }
    val gapPx = with(LocalDensity.current) { Spacing.s.toPx() }

    fun target() = targetIndex(dragFrom, dragOffset, heights, plan.days.size, gapPx)

    val dragging = dragFrom >= 0
    val target = if (dragging) target() else -1
    val slot = (heights[dragFrom] ?: 0).toFloat() + gapPx

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        plan.days.forEachIndexed { index, day ->
            val isDragged = index == dragFrom
            // Everything the dragged card has passed steps back by one slot, so the gap
            // it will land in opens up as the finger moves rather than on release.
            val shift = when {
                !dragging -> 0f
                isDragged -> dragOffset
                index in (dragFrom + 1)..target -> -slot
                index in target until dragFrom -> slot
                else -> 0f
            }

            DayRow(
                day = day,
                today = today,
                // Collapsed while anything in the week is being dragged: a card that
                // changes height mid-drag moves the ground under the finger.
                expanded = !dragging && expanded == day.date,
                lifted = isDragged,
                onRunSession = onRunSession,
                onClick = {
                    if (swallowClick) swallowClick = false else onExpand(day.date)
                },
                modifier = Modifier
                    .onGloballyPositioned { heights[index] = it.size.height }
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer { translationY = shift }
                    .pointerInput(plan.weekStart, index) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragFrom = index
                                dragOffset = 0f
                                swallowClick = true
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                            },
                            onDragEnd = {
                                // Recomputed here rather than read from the composition:
                                // the value captured when this lambda was built belongs
                                // to the frame the drag started on.
                                val to = target()
                                if (dragFrom >= 0 && to != dragFrom) onMove(dragFrom, to)
                                dragFrom = -1
                                dragOffset = 0f
                            },
                            onDragCancel = {
                                dragFrom = -1
                                dragOffset = 0f
                            },
                        )
                    },
            )
        }
    }
}

/**
 * Which slot the dragged card is currently over.
 *
 * Measured heights rather than an assumed row height, because an expanded card is twice
 * the size of a rest day and a fixed step would have the card land a day out.
 */
private fun targetIndex(
    from: Int,
    offset: Float,
    heights: Map<Int, Int>,
    count: Int,
    gapPx: Float,
): Int {
    if (from < 0) return -1
    var target = from
    var passed = 0f
    if (offset > 0) {
        var i = from + 1
        while (i < count) {
            val step = (heights[i] ?: 0).toFloat() + gapPx
            if (offset - passed < step / 2) break
            passed += step
            target = i
            i++
        }
    } else if (offset < 0) {
        var i = from - 1
        while (i >= 0) {
            val step = (heights[i] ?: 0).toFloat() + gapPx
            if (-offset - passed < step / 2) break
            passed += step
            target = i
            i--
        }
    }
    return target
}

// ---- cards ---------------------------------------------------------------------------

/**
 * The week on screen, with an arrow either side of it.
 *
 * One week at a time rather than four down a scroll. Four weeks of seven cards is
 * twenty-eight things to scroll past to reach the one you were looking for, and the week
 * you actually care about is nearly always the one you are in.
 */
@Composable
private fun WeekBar(
    plan: WeekPlan,
    index: Int,
    count: Int,
    today: LocalDate,
    onShowWeek: (Int) -> Unit,
) {
    val current = !today.isBefore(plan.weekStart) && today.isBefore(plan.weekStart.plusDays(7))
    SnailCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = if (current) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Arrow(
                back = true,
                enabled = index > 0,
                onClick = { onShowWeek(-1) },
            )
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (current) "This week" else "In $index weeks",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = weekRange(plan.weekStart),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Arrow(
                back = false,
                enabled = index < count - 1,
                onClick = { onShowWeek(1) },
            )
        }

        Spacer(Modifier.height(Spacing.m))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = plan.note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(end = Spacing.s),
            )
            Text(
                text = km(plan.plannedMeters),
                style = SnailType.metricSmall,
                maxLines = 1,
                softWrap = false,
            )
        }

        plan.conflicts.forEach { warning ->
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = warning,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** One arrow, drawn once and mirrored for the other direction. */
@Composable
private fun Arrow(back: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            painter = painterResource(R.drawable.ic_back),
            contentDescription = if (back) "Previous week" else "Next week",
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            },
            modifier = if (back) Modifier else Modifier.graphicsLayer { scaleX = -1f },
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
private fun DayRow(
    day: PlannedDay,
    today: LocalDate,
    expanded: Boolean,
    lifted: Boolean,
    onRunSession: (Workout) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rest = day.workout.type == WorkoutType.Rest
    SnailCard(
        modifier = modifier
            .fillMaxWidth()
            .shadow(if (lifted) 8.dp else 0.dp, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        containerColor = when {
            lifted -> MaterialTheme.colorScheme.surfaceContainerHighest
            day.date == today -> MaterialTheme.colorScheme.primaryContainer
            rest -> MaterialTheme.colorScheme.surfaceContainerLow
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        val content = when {
            day.date == today && !lifted -> MaterialTheme.colorScheme.onPrimaryContainer
            rest -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                style = SnailType.metricCaption,
                color = content,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(40.dp),
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
                Text(
                    text = km(day.workout.totalMeters),
                    style = SnailType.metricSmall,
                    color = content,
                    maxLines = 1,
                    softWrap = false,
                )
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
                if (!rest) {
                    TextButton(onClick = { onRunSession(day.workout) }) { Text("Run this") }
                }
            }
        }
    }
}

@Composable
private fun FitnessCard(state: CoachUiState) {
    val fitness = state.fitness
    SnailCard(modifier = Modifier.fillMaxWidth()) {
        if (fitness == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "No fitness estimate yet",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                HelpButton(title = "Paces", body = PacesHelp)
            }
            Text(
                text = "Easy running only until you have run a hard five kilometres.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SnailCard
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Fitness ${fitness.vdot.roundToInt()} VDOT",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            HelpButton(title = "Paces", body = PacesHelp)
        }
        Text(
            text = "From your ${distanceName(fitness.fromDistanceM)} on " +
                dayformat().format(fitness.fromDate),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(Spacing.m))
        val paces = fitness.paces
        PaceLine(
            "Easy",
            "${RunFormat.pace(paces.easySecPerKm.start)}–" +
                RunFormat.pace(paces.easySecPerKm.endInclusive),
        )
        PaceLine("Marathon", RunFormat.pace(paces.marathonSecPerKm))
        PaceLine("Threshold", RunFormat.pace(paces.thresholdSecPerKm))
        if (fitness.confidence == Confidence.Solid) {
            PaceLine("Interval", RunFormat.pace(paces.intervalSecPerKm))
            PaceLine("Repetition", RunFormat.pace(paces.repetitionSecPerKm))
        } else {
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = "Interval and repetition pace need a hard 5 km to price them from.",
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
        Text(
            text = "$value$NBSP/km",
            style = SnailType.metricSmall,
            maxLines = 1,
            softWrap = false,
        )
    }
}

// ---- text ----------------------------------------------------------------------------

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
        step.distanceM != null && step.repeats > 1 -> append("${step.distanceM.roundToInt()}${NBSP}m")
        step.durationMs != null -> append("${(step.durationMs / 60_000.0).roundToInt()}${NBSP}min")
        step.distanceM != null -> append(km(step.distanceM))
    }
    append(" ")
    append(step.label.lowercase())
    step.paceSecPerKm?.let { append(" at ${paceText(it)}") }
}

private fun paceText(range: ClosedFloatingPointRange<Double>): String =
    if (range.start == range.endInclusive) {
        "${RunFormat.pace(range.start)}$NBSP/km"
    } else {
        "${RunFormat.pace(range.start)}–${RunFormat.pace(range.endInclusive)}$NBSP/km"
    }

private fun km(meters: Double): String = "${RunFormat.distanceKm(meters)}${NBSP}km"

private fun weekRange(start: LocalDate): String =
    "${rangeformat().format(start)} – ${rangeformat().format(start.plusDays(6))}"

private fun distanceName(meters: Int) = when (meters) {
    21_097 -> "half marathon"
    42_195 -> "marathon"
    else -> "${meters / 1000} km"
}

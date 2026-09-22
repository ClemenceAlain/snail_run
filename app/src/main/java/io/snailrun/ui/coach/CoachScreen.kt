package io.snailrun.ui.coach

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.snailrun.domain.coach.CoachBaseline
import io.snailrun.domain.coach.PlannedDay
import io.snailrun.domain.coach.Races
import io.snailrun.domain.coach.WeekPlan
import io.snailrun.domain.coach.Workout
import io.snailrun.domain.coach.WorkoutStep
import io.snailrun.domain.coach.WorkoutType
import io.snailrun.R
import io.snailrun.ui.components.Badge
import io.snailrun.ui.components.BadgeTone
import io.snailrun.ui.components.HelpButton
import io.snailrun.ui.components.badgeTone
import io.snailrun.ui.components.SessionSheet
import io.snailrun.ui.components.SnailCard
import io.snailrun.ui.format.UiLocale
import io.snailrun.ui.format.RunFormat
import io.snailrun.ui.format.SessionFormat
import io.snailrun.ui.theme.SnailType
import io.snailrun.ui.theme.Spacing
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// Built per call: a formatter cached at class-init keeps the locale the app
// started with, which is wrong after the user changes the system language.
private fun dayformat() = DateTimeFormatter.ofPattern("EEE d MMM", UiLocale)
private fun rangeformat() = DateTimeFormatter.ofPattern("d MMM", UiLocale)

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
        "average. The long run cannot grow more than a tenth either. Tap a day to see " +
        "the session written out and why it is the length it is.",
    "Two strength sessions a week sit beside the running rather than instead of it. " +
        "They carry no distance, so they never cost you a kilometre, and they are kept " +
        "off the day before anything hard.",
    "The paces these sessions are written in live under Runs → Records, where the rest " +
        "of what you are currently capable of is.",
)

@Composable
fun CoachScreen(
    state: CoachUiState,
    onExpand: (LocalDate) -> Unit,
    onMove: (LocalDate, Int, Int) -> Unit,
    onResetWeek: (LocalDate) -> Unit,
    onShowWeek: (Int) -> Unit,
    onRunSession: (Workout) -> Unit,
    onStartStrength: (Workout) -> Unit,
    onEditBaseline: (Boolean) -> Unit,
    onSaveBaseline: (CoachBaseline?) -> Unit,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    val plan = state.week
    if (!state.loaded || plan == null) {
        Box(modifier.fillMaxSize())
        return
    }

    plan.days.firstOrNull { it.date == state.expanded }?.let { day ->
        SessionSheet(
            workout = if (day.workout.type == WorkoutType.Rest && day.strength != null) {
                day.strength
            } else {
                day.workout
            },
            onDismiss = { onExpand(day.date) },
            subtitle = dayformat().format(day.date),
            // A rest day with strength on it has the strength as its only content, so it
            // is promoted rather than shown under an empty "Rest".
            strength = day.strength.takeIf { day.workout.type != WorkoutType.Rest },
            // A rest day with strength on it has only one thing to offer, so the
            // button offers that rather than nothing.
            action = when {
                day.workout.type != WorkoutType.Rest -> "Run this" to {
                    onExpand(day.date)
                    onRunSession(day.workout)
                }
                day.strength != null -> "Start this" to {
                    onExpand(day.date)
                    onStartStrength(day.strength)
                }
                else -> null
            },
        )
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

        // Above the week, while it is the thing most worth doing: a plan built from
        // nothing is the one the runner is least likely to follow.
        if (state.askBaseline || state.editingBaseline) {
            item {
                BaselineCard(
                    baseline = state.baseline,
                    editing = state.editingBaseline,
                    today = today,
                    onEdit = onEditBaseline,
                    onSave = onSaveBaseline,
                )
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
                onExpand = onExpand,
                onMove = { from, to -> onMove(plan.weekStart, from, to) },
            )
        }

        if (plan.order != null) {
            item {
                TextButton(onClick = { onResetWeek(plan.weekStart) }) { Text("Put the week back") }
            }
        }

        // Below everything otherwise: a footnote about where the numbers above came
        // from — and the way back in for a runner who has history but still wants to
        // tell the coach about a race it never saw.
        if (!state.askBaseline && !state.editingBaseline) {
            item {
                BaselineCard(
                    baseline = state.baseline,
                    editing = false,
                    today = today,
                    onEdit = onEditBaseline,
                    onSave = onSaveBaseline,
                )
            }
        }
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
    onExpand: (LocalDate) -> Unit,
    onMove: (Int, Int) -> Unit,
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
                lifted = isDragged,
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

        // No note and no weekly total. The week is seven cards immediately below, each
        // with its own distance on it, and a paragraph restating their sum was the one
        // thing on this screen nobody read.

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
    lifted: Boolean,
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
                text = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, UiLocale),
                style = SnailType.metricCaption,
                color = content,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(40.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                // The badges carry the day, and the name under them carries the detail.
                // A week of pills reads in one pass without a word of it being read:
                // filled green is a day that will hurt, pale green is one that will not.
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Badge(day.workout.type.label, day.workout.type.badgeTone)
                    if (day.strength != null) Badge("Strength", BadgeTone.Other)
                    // Replaces a strikethrough, which is easy to miss at a glance and
                    // reads as an error the rest of the time. Nothing is stored to say
                    // the day is done: there is a run on it, and that is the whole test.
                    if (day.done) Badge("Done", BadgeTone.Quiet)
                }
                if (!rest) {
                    Spacer(Modifier.height(Spacing.xs))
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

        // One line, and only where the badge above is not already the whole story: a rest
        // day's strength session is the day, so it gets named.
        day.strength?.let { strength ->
            if (rest) {
                Spacer(Modifier.height(Spacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(40.dp))
                    Text(
                        text = "${strength.name} · " +
                            (SessionFormat.estimate(strength) ?: "${strength.steps.size} exercises"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = content,
                    )
                }
            }
        }
    }
}

// ---- text ----------------------------------------------------------------------------

/** One line under the session name: what it is, before the sheet is opened. */
private fun summaryOf(day: PlannedDay): String {
    val work = day.workout.steps.firstOrNull { it.repeats > 1 }
    if (work != null) return SessionFormat.step(work)
    val pace = day.workout.steps.firstOrNull()?.paceSecPerKm ?: return ""
    return SessionFormat.pace(pace)
}

// One decimal, not two. A prescribed distance is a decision and the coach never makes
// one to the metre, so "6.3 km" is the whole of what it has to say; "6.31 km" claims a
// precision the plan does not have.
private fun km(meters: Double): String = SessionFormat.kmWithUnit(meters)

private fun weekRange(start: LocalDate): String =
    "${rangeformat().format(start)} – ${rangeformat().format(start.plusDays(6))}"

private fun distanceName(meters: Int) = when (meters) {
    21_097 -> "half marathon"
    42_195 -> "marathon"
    else -> "${meters / 1000} km"
}

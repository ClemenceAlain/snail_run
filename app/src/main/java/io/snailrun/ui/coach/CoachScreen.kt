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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.saveable.rememberSaveable
import io.snailrun.domain.coach.CoachText
import io.snailrun.domain.coach.Pairing
import io.snailrun.domain.coach.Partner
import io.snailrun.domain.coach.SharedSession
import io.snailrun.domain.coach.PlannedDay
import io.snailrun.domain.coach.RaceGoal
import io.snailrun.domain.coach.Vma
import io.snailrun.domain.coach.VmaOrigin
import io.snailrun.domain.coach.VmaSource
import io.snailrun.domain.coach.Vmas
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
import io.snailrun.ui.components.PartnerTag
import io.snailrun.ui.format.UiLocale
import io.snailrun.ui.format.RunFormat
import io.snailrun.ui.format.SessionFormat
import io.snailrun.ui.format.PartnerFormat
import io.snailrun.ui.theme.SnailType
import io.snailrun.ui.theme.Spacing
import java.time.DayOfWeek
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
    "Your plan, a week at a time. Start one with your VMA, your race and how many " +
        "sessions a week; the weeks ahead are worked out from those, and the weeks " +
        "behind are kept as they were.",
    "Every fast pace is a percentage of your VMA. Don't know it? The plan opens with " +
        "the six-minute test and prices itself from the result.",
    "Hold a day to drag it somewhere else; the days it passes shift along by one. " +
        "Tap a day to see the session written out, and why each number is what it is.",
    "Two strength sessions a week sit beside the running rather than instead of it. " +
        "They carry no distance, and they are kept off the day before anything hard.",
    "Running a session with someone? Add them by VMA under Settings → Coach, then open the " +
        "day and pick them. Their paces appear under yours; switch on regrouping and the " +
        "session is timed so you keep meeting, and the faster one runs the gaps.",
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
    onCreating: (Boolean) -> Unit,
    onStartPlan: (RaceGoal?, Long?, Int, Vma?) -> Unit,
    onEditVma: (Boolean) -> Unit,
    onSetVma: (Double, VmaSource) -> Unit,
    onDismissMessage: () -> Unit,
    today: LocalDate,
    firstDayOfWeek: DayOfWeek,
    modifier: Modifier = Modifier,
    onPair: (LocalDate, Pairing?) -> Unit = { _, _ -> },
    onShareText: (String) -> Unit = {},
) {
    if (!state.loaded) {
        Box(modifier.fillMaxSize())
        return
    }
    val plan = state.week

    plan?.days?.firstOrNull { it.date == state.expanded }?.let { day ->
        val pairing = state.pairings[day.date]
        val herVdot = plan.fitness?.vdot ?: state.fitness?.vdot
        val shared = remember(day.workout, herVdot, pairing, state.partners) {
            SharedSession.of(
                workout = day.workout,
                herVdot = herVdot,
                partner = state.partners.firstOrNull { it.id == pairing?.partnerId },
                mode = pairing?.mode,
            )
        }
        // In Together mode the runner's own session can change too — a shared jog pace,
        // a longer jog where she is the quicker one — and that version is the one run.
        val herWorkout = shared?.her ?: day.workout
        SessionSheet(
            workout = if (day.workout.type == WorkoutType.Rest && day.strength != null) {
                day.strength
            } else {
                herWorkout
            },
            onDismiss = { onExpand(day.date) },
            partner = shared?.partner,
            partnerLines = remember(shared) { shared?.let { PartnerFormat.lines(it) }.orEmpty() },
            sharing = if (day.workout.type.isRun && !day.done && !plan.frozen) {
                {
                    PairingControls(
                        partners = state.partners,
                        pairing = pairing,
                        shared = shared,
                        canTranslate = herVdot != null,
                        onPair = { onPair(day.date, it) },
                        onShare = {
                            shared?.let { onShareText(PartnerFormat.shareText(it, dayformat().format(day.date))) }
                        },
                    )
                }
            } else {
                null
            },
            subtitle = dayformat().format(day.date),
            // A rest day with strength on it has the strength as its only content, so it
            // is promoted rather than shown under an empty "Rest".
            strength = day.strength.takeIf { day.workout.type != WorkoutType.Rest },
            onStartStrength = day.strength
                ?.takeIf { day.workout.type != WorkoutType.Rest && !plan.frozen }
                ?.let { strength ->
                    {
                        onExpand(day.date)
                        onStartStrength(strength)
                    }
                },
            // A saved week is history: there is nothing in it left to start.
            action = when {
                plan.frozen -> null
                day.workout.type != WorkoutType.Rest -> "Run this" to {
                    onExpand(day.date)
                    onRunSession(herWorkout)
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

        state.message?.let { message ->
            item { MessageCard(message, onDismissMessage) }
        }
        if (state.shortTestRunId != null) {
            item { ShortTestCard(onSetVma = onSetVma, onDismiss = onDismissMessage) }
        }

        if (state.plan == null || state.creating) {
            item {
                PlanWizard(
                    basis = state.basis,
                    savedVma = state.vma,
                    prefill = state.plan?.race ?: state.legacyGoal,
                    today = today,
                    firstDayOfWeek = firstDayOfWeek,
                    canCancel = state.plan != null,
                    onCancel = { onCreating(false) },
                    onStart = onStartPlan,
                )
            }
            // Nothing else until there is a plan: the wizard is the whole screen.
            if (state.plan == null) return@LazyColumn
        }

        item {
            PlanCard(
                state = state,
                onNewPlan = { onCreating(true) },
                onEditVma = { onEditVma(true) },
            )
        }

        if (state.editingVma) {
            item { VmaCard(current = state.vma, onSave = onSetVma, onCancel = { onEditVma(false) }) }
        }

        item {
            WeekBar(state = state, today = today, onShowWeek = onShowWeek)
        }

        if (plan == null) {
            item { NoWeekCard(state) }
        } else {
            item(key = "days-${plan.weekStart}") {
                DraggableWeek(
                    plan = plan,
                    today = today,
                    partnerOn = { date ->
                        state.pairings[date]?.let { p -> state.partners.firstOrNull { it.id == p.partnerId } }
                    },
                    onExpand = onExpand,
                    onMove = { from, to -> onMove(plan.weekStart, from, to) },
                )
            }

            if (plan.order != null && !plan.frozen) {
                item {
                    TextButton(onClick = { onResetWeek(plan.weekStart) }) { Text("Put the week back") }
                }
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
    partnerOn: (LocalDate) -> Partner?,
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
                partner = partnerOn(day.date),
                lifted = isDragged,
                onClick = {
                    if (swallowClick) swallowClick = false else onExpand(day.date)
                },
                modifier = Modifier
                    .onGloballyPositioned { heights[index] = it.size.height }
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer { translationY = shift }
                    .pointerInput(plan.weekStart, index, plan.frozen) {
                        // A saved week is history, and history is not rearranged.
                        if (plan.frozen) return@pointerInput
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
 * One week at a time. Back goes through every week the coach has kept; forward goes as
 * far as the plan does — race day, or half a year for a plan with no race.
 */
@Composable
private fun WeekBar(state: CoachUiState, today: LocalDate, onShowWeek: (Int) -> Unit) {
    val start = state.weekStart ?: return
    val plan = state.week
    val current = state.weeksAway == 0L
    SnailCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = if (current) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Arrow(back = true, enabled = state.canGoBack, onClick = { onShowWeek(-1) })
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = weekLabel(state.weeksAway), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = weekRange(start),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Arrow(back = false, enabled = state.canGoForward, onClick = { onShowWeek(1) })
        }

        plan?.note?.takeIf { it.isNotEmpty() }?.let { note ->
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = if (plan.frozen) "$note Kept as it was planned." else note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        plan?.conflicts?.forEach { warning ->
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

/**
 * The plan in two lines, its VMA, and the explanation of the whole thing.
 *
 * The explanation is folded away: it is read once, carefully, and then it is in the way.
 */
@Composable
private fun PlanCard(state: CoachUiState, onNewPlan: () -> Unit, onEditVma: () -> Unit) {
    val plan = state.plan ?: return
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    SnailCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        val content = MaterialTheme.colorScheme.onSecondaryContainer
        val race = plan.race
        Text(
            text = race?.let { "${distanceName(it.distanceMeters)} on ${dayformat().format(it.date)}" }
                ?: "No race · a four-week cycle",
            style = MaterialTheme.typography.titleMedium,
            color = content,
        )
        Text(
            text = buildString {
                append("${plan.sessionsPerWeek} sessions a week")
                plan.targetTimeMs?.let { append(" · target ${CoachText.clock(it)}") }
                    ?: state.week?.predictedTimeMs?.let { append(" · on today's fitness ${RunFormat.duration(it)}") }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = content,
        )
        Spacer(Modifier.height(Spacing.s))
        Text(
            text = vmaLine(state),
            style = MaterialTheme.typography.bodyMedium,
            color = content,
        )

        AnimatedVisibility(visible = aboutOpen) {
            Column {
                state.about.forEach { section ->
                    Spacer(Modifier.height(Spacing.m))
                    Text(section.title, style = MaterialTheme.typography.titleSmall, color = content)
                    Text(section.body, style = MaterialTheme.typography.bodyMedium, color = content)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            TextButton(onClick = { aboutOpen = !aboutOpen }) {
                Text(if (aboutOpen) "Hide" else "About this plan")
            }
            TextButton(onClick = onEditVma) { Text("Change VMA") }
            TextButton(onClick = onNewPlan) { Text("New plan") }
        }
    }
}

private fun vmaLine(state: CoachUiState): String {
    val basis = state.basis
    val vma = basis?.vmaKmh
    return when (basis?.origin) {
        VmaOrigin.Test -> "VMA ${CoachText.kmh(vma!!)}, measured ${dayformat().format(basis.measuredOn)}"
        VmaOrigin.Typed -> "VMA ${CoachText.kmh(vma!!)}, entered ${dayformat().format(basis.measuredOn)}"
        VmaOrigin.Estimated -> "VMA about ${CoachText.kmh(vma!!)}, estimated from your running. " +
            "The six-minute test will measure it."
        else -> "No VMA yet. The six-minute test is in your plan; fast sessions are by feel until then."
    }
}

/** A week the plan does not reach: before it starts, or after the race. */
@Composable
private fun NoWeekCard(state: CoachUiState) {
    val plan = state.plan ?: return
    val start = state.weekStart ?: return
    SnailCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = when {
                start.isBefore(plan.startWeek) ->
                    "Your plan starts on ${dayformat().format(plan.startWeek)}. Run easy until then."
                else -> "The plan is over. Start a new one when you are ready."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MessageCard(message: String, onDismiss: () -> Unit) {
    SnailCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
        TextButton(onClick = onDismiss) { Text("OK") }
    }
}

/** The test stopped before six minutes. Asked rather than guessed. */
@Composable
private fun ShortTestCard(onSetVma: (Double, VmaSource) -> Unit, onDismiss: () -> Unit) {
    var meters by rememberSaveable { mutableStateOf("") }
    SnailCard(modifier = Modifier.fillMaxWidth()) {
        Text("Your VMA test stopped early", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "The app reads the test off a full six minutes, and this one was shorter. " +
                "If you know how far you got in six minutes, enter it here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.s))
        NumberField(meters, { meters = it }, "Metres in six minutes", decimal = false)
        Row {
            val value = PlanInputs.testMeters(meters)
            TextButton(
                onClick = { value?.let { onSetVma(Vmas.fromTestDistance(it), VmaSource.Test) } },
                enabled = value != null,
            ) { Text("Save") }
            TextButton(onClick = onDismiss) { Text("Not now") }
        }
    }
}

@Composable
private fun VmaCard(current: Vma?, onSave: (Double, VmaSource) -> Unit, onCancel: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current?.kmh?.toString().orEmpty()) }
    SnailCard(modifier = Modifier.fillMaxWidth()) {
        Text("Your VMA", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Every fast pace in the plan moves with it, this week and every week ahead. " +
                "Weeks already behind you keep the paces they had.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.s))
        NumberField(text, { text = it }, "VMA, km/h", decimal = true)
        Row {
            val value = PlanInputs.vma(text)
            TextButton(onClick = { value?.let { onSave(it, VmaSource.Typed) } }, enabled = value != null) { Text("Save") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun DayRow(
    day: PlannedDay,
    today: LocalDate,
    partner: Partner?,
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
                    if (partner != null && !rest) PartnerTag(partner.id, partner.name)
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

private fun weekLabel(weeksAway: Long): String = when {
    weeksAway == 0L -> "This week"
    weeksAway == 1L -> "Next week"
    weeksAway == -1L -> "Last week"
    weeksAway > 1L -> "In $weeksAway weeks"
    else -> "${-weeksAway} weeks ago"
}

private fun weekRange(start: LocalDate): String =
    "${rangeformat().format(start)} – ${rangeformat().format(start.plusDays(6))}"

private fun distanceName(meters: Int) = when (meters) {
    21_097 -> "half marathon"
    42_195 -> "marathon"
    else -> "${meters / 1000} km"
}

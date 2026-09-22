package io.snailrun.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.snailrun.domain.analysis.CalendarCell
import io.snailrun.domain.analysis.CalendarMonth
import io.snailrun.ui.components.MetricReadout
import io.snailrun.ui.components.SnailCard
import io.snailrun.ui.format.UiLocale
import io.snailrun.ui.format.RunFormat
import io.snailrun.ui.theme.SnailType
import io.snailrun.ui.theme.Spacing
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Built per call: a formatter cached at class-init keeps the locale the app
// started with, which is wrong after the user changes the system language.
private fun monthFormat() = DateTimeFormatter.ofPattern("LLLL yyyy", UiLocale)

/**
 * A month of running at a glance.
 *
 * A day is tinted in proportion to how far it covered, against the busiest day of the
 * month rather than a fixed scale — the question the grid answers is what the shape of
 * this month was, not how it compares to a target nobody set.
 */
@Composable
fun CalendarView(
    month: CalendarMonth,
    selectedDate: LocalDate?,
    today: LocalDate,
    onSelectDate: (LocalDate?) -> Unit,
    onShowMonth: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onShowMonth(-1) }) { Text("‹") }
            Text(
                text = monthFormat().format(month.yearMonth),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = { onShowMonth(1) }) { Text("›") }
        }

        Spacer(Modifier.height(Spacing.m))

        MonthTotals(month)

        Spacer(Modifier.height(Spacing.l))

        Row(modifier = Modifier.fillMaxWidth()) {
            month.dayOfWeekOrder.forEach { day ->
                Text(
                    text = day.shortLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(Spacing.xs))

        month.weeks.forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    DayCell(
                        cell = cell,
                        busiestMeters = month.busiestDayMeters,
                        isToday = cell.date == today,
                        isSelected = cell.date != null && cell.date == selectedDate,
                        onClick = { cell.date?.let(onSelectDate) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

    }
}

/**
 * What the month came to, above the grid rather than under it.
 *
 * Above, because the number is the answer and the grid is the working: a reader wants to
 * know they ran 42 km before they want to know which Tuesdays.
 *
 * Distance leads at hero size and the rest support it. Four equal columns gave the same
 * weight to the kilometres and to a bare count of active days, which is the one figure
 * nobody opens this screen for. Average pace replaces it — it is the thing a month of
 * running actually says about you, and it was not shown anywhere.
 */
@Composable
private fun MonthTotals(month: CalendarMonth, modifier: Modifier = Modifier) {
    SnailCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            MetricReadout(
                value = RunFormat.distanceKm(month.meters),
                caption = "km",
                valueStyle = SnailType.metricLarge,
                alignment = Alignment.Start,
            )
            MetricReadout(
                value = RunFormat.duration(month.movingMs),
                caption = "moving",
                valueStyle = SnailType.metricSmall,
                alignment = Alignment.End,
            )
            MetricReadout(
                value = RunFormat.pace(averagePace(month)),
                caption = "/km",
                valueStyle = SnailType.metricSmall,
                alignment = Alignment.End,
            )
        }

        Spacer(Modifier.height(Spacing.s))
        Text(
            text = when (month.runCount) {
                0 -> "No runs this month"
                1 -> "1 run"
                else -> "${month.runCount} runs on ${month.activeDays} days"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Seconds per kilometre across the whole month, or null before there is a month. */
private fun averagePace(month: CalendarMonth): Double? {
    if (month.meters <= 0.0 || month.movingMs <= 0L) return null
    return month.movingMs / 1000.0 / (month.meters / 1000.0)
}

@Composable
private fun DayCell(
    cell: CalendarCell,
    busiestMeters: Double,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val date = cell.date
    if (date == null) {
        Box(modifier.aspectRatio(1f))
        return
    }

    // A floor under the tint so the lightest run is still visibly a run: a day that was
    // run is never the same colour as a day that was not.
    val share = if (busiestMeters <= 0.0) 0.0 else (cell.meters / busiestMeters)
    val fill = when {
        isSelected -> MaterialTheme.colorScheme.primary
        cell.hasRun -> MaterialTheme.colorScheme.primary.copy(alpha = (0.25 + 0.6 * share).toFloat())
        else -> Color.Transparent
    }
    val content = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        cell.hasRun && share > 0.55 -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(Spacing.xs)
            .clip(CircleShape)
            .background(fill)
            .then(
                if (isToday && !isSelected) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                } else {
                    Modifier
                }
            )
            .clickable(enabled = cell.hasRun, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${date.dayOfMonth}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (cell.hasRun) FontWeight.SemiBold else FontWeight.Normal,
            color = if (cell.hasRun || isSelected) content
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

package io.snailrun.ui.history

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import io.snailrun.data.db.RunEntity
import io.snailrun.data.repo.SOURCE_DEMO
import io.snailrun.ui.components.SnailCard
import io.snailrun.ui.format.RunFormat
import io.snailrun.ui.theme.SnailType
import io.snailrun.ui.theme.Spacing
import io.snailrun.domain.analysis.ProgressPeriod
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Built per call: a formatter cached at class-init keeps the locale the app
// started with, which is wrong after the user changes the system language.
private fun dayformat() = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
// Built per call: a formatter cached at class-init keeps the locale the app
// started with, which is wrong after the user changes the system language.
private fun timeformat() = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onOpenRun: (Long) -> Unit,
    onSetMode: (HistoryMode) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onShowMonth: (Long) -> Unit,
    onSetProgressPeriod: (ProgressPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.runs.isEmpty()) {
        EmptyHistory(modifier)
        return
    }

    val runs = when (state.mode) {
        HistoryMode.List -> state.runs
        HistoryMode.Calendar -> state.visibleRuns
        // The chart is the answer in progress mode; a list under it would only repeat
        // the bars in words.
        HistoryMode.Progress -> emptyList()
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
                text = "Runs",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = Spacing.s),
            )
            ModeToggle(mode = state.mode, onSetMode = onSetMode)
        }

        if (state.mode == HistoryMode.Calendar && state.month != null) {
            item {
                CalendarView(
                    month = state.month,
                    selectedDate = state.selectedDate,
                    today = LocalDate.now(),
                    onSelectDate = { date -> date?.let(onSelectDate) },
                    onShowMonth = onShowMonth,
                )
            }
            item {
                Text(
                    text = when {
                        state.selectedDate != null -> "That day"
                        runs.isEmpty() -> "Nothing this month"
                        else -> "This month"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = Spacing.s),
                )
            }
        }

        if (state.mode == HistoryMode.Progress) {
            item {
                ProgressView(
                    buckets = state.progress,
                    period = state.progressPeriod,
                    onSetPeriod = onSetProgressPeriod,
                )
            }
        }

        items(runs, key = { it.id }) { run ->
            RunRow(run = run, onClick = { onOpenRun(run.id) })
        }
    }
}

/** Two words, not icons: the difference between a list and a calendar is worth saying. */
@Composable
private fun ModeToggle(mode: HistoryMode, onSetMode: (HistoryMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        HistoryMode.entries.forEach { option ->
            FilterChip(
                selected = mode == option,
                onClick = { onSetMode(option) },
                label = { Text(option.name) },
            )
        }
    }
}

@Composable
private fun RunRow(run: RunEntity, onClick: () -> Unit) {
    SnailCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        val zone = runCatching { ZoneId.of(run.timeZoneId) }.getOrDefault(ZoneId.systemDefault())
        val started = Instant.ofEpochMilli(run.startedAtEpochMs).atZone(zone)

        Text(
            text = run.title ?: dayformat().format(started),
            style = MaterialTheme.typography.titleMedium,
        )
        if (run.source == SOURCE_DEMO) {
            Text(
                text = "DEMO",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = timeformat().format(started),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(Spacing.l))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Stat(value = RunFormat.distanceKm(run.distanceMeters), caption = "km")
            Stat(value = RunFormat.duration(run.movingTimeMs), caption = "time")
            Stat(
                value = RunFormat.pace(run.avgPaceSecPerKm.takeIf { it > 0 }),
                caption = "/km",
            )
        }
    }
}

@Composable
private fun Stat(value: String, caption: String) {
    Column {
        Text(text = value, style = SnailType.metricSmall)
        Text(
            text = caption.uppercase(),
            style = SnailType.metricCaption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyHistory(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(Spacing.section),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No runs yet",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = "Your first run will appear here once you finish it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

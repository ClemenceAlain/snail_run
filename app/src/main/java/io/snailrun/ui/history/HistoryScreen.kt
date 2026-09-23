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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.snailrun.R
import io.snailrun.data.db.RunEntity
import io.snailrun.data.repo.SOURCE_DEMO
import io.snailrun.data.repo.SOURCE_MANUAL
import io.snailrun.ui.components.Badge
import io.snailrun.ui.components.BadgeTone
import io.snailrun.ui.components.SnailCard
import io.snailrun.ui.format.UiLocale
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
private fun dayformat() = DateTimeFormatter.ofPattern("EEE d MMM", UiLocale)
// Built per call: a formatter cached at class-init keeps the locale the app
// started with, which is wrong after the user changes the system language.
private fun timeformat() = DateTimeFormatter.ofPattern("HH:mm", UiLocale)

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onOpenRun: (Long) -> Unit,
    onSetMode: (HistoryMode) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onShowMonth: (Long) -> Unit,
    onSetProgressPeriod: (ProgressPeriod) -> Unit,
    onTogglePaces: () -> Unit,
    onAddRun: (ManualRun) -> Unit,
    modifier: Modifier = Modifier,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    if (adding) {
        ManualRunDialog(
            onDismiss = { adding = false },
            onSave = { run ->
                adding = false
                onAddRun(run)
            },
        )
    }

    if (state.runs.isEmpty()) {
        EmptyHistory(onAddRun = { adding = true }, modifier = modifier)
        return
    }

    val runs = when (state.mode) {
        HistoryMode.Calendar -> state.visibleRuns
        // The chart is the answer in progress mode; a list under it would only repeat
        // the bars in words. Records are their own list, of distances rather than runs.
        HistoryMode.Progress, HistoryMode.Records -> emptyList()
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
                    text = "Runs",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { adding = true }) { Text("Add a run") }
            }
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

        if (state.mode == HistoryMode.Records) {
            // Above the records, because it is the same question asked forwards: these
            // are the times you have run, that is what they say you can run.
            item {
                PacesCard(
                    fitness = state.fitness,
                    expanded = state.pacesExpanded,
                    onToggle = onTogglePaces,
                )
            }
            item {
                Text(
                    text = "Your fastest time over each distance, across every run.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Spacing.xs),
                )
            }
            items(state.records, key = { it.distanceMeters }) { record ->
                RecordRow(
                    record = record,
                    onClick = record.record?.let { held -> { onOpenRun(held.runId) } },
                )
            }
        }

        items(runs, key = { it.id }) { run ->
            RunRow(run = run, onClick = { onOpenRun(run.id) })
        }
    }
}

/** Words, not icons: the difference between a calendar and a chart is worth saying. */
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

/**
 * One distance and the best it has ever been run.
 *
 * Distances nobody has covered yet are still shown, greyed. A record list that simply
 * stopped at 10 km would read as the app having no opinion about a half marathon,
 * rather than as a half marathon not having been run.
 */
@Composable
private fun RecordRow(record: DistanceRecord, onClick: (() -> Unit)?) {
    SnailCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        containerColor = if (record.record != null) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        val held = record.record
        val content = if (held != null) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = distanceLabel(record.distanceMeters),
                    style = MaterialTheme.typography.titleMedium,
                    color = content,
                )
                Text(
                    text = held?.let { effort ->
                        val zone = ZoneId.systemDefault()
                        val on = dayformat().format(Instant.ofEpochMilli(effort.startedAtEpochMs).atZone(zone))
                        "$on · ${RunFormat.pace(paceOf(record.distanceMeters, effort.durationMs))}/km"
                    } ?: "Not run yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = content,
                )
            }
            Text(
                text = held?.let { RunFormat.duration(it.durationMs) } ?: "—",
                style = SnailType.metricSmall,
                color = content,
            )
        }
    }
}

/** Seconds per kilometre for a record, which stores only the distance and the time. */
private fun paceOf(distanceMeters: Int, durationMs: Long): Double? {
    if (distanceMeters <= 0 || durationMs <= 0L) return null
    return durationMs / 1000.0 / (distanceMeters / 1000.0)
}

private fun distanceLabel(distanceMeters: Int): String = when (distanceMeters) {
    21_097 -> "Half marathon"
    42_195 -> "Marathon"
    else -> "${distanceMeters / 1000} km"
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = timeformat().format(started),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (run.source == SOURCE_DEMO) {
                Spacer(Modifier.size(Spacing.s))
                Badge("Demo", BadgeTone.Long)
            }
            if (run.source == SOURCE_MANUAL) {
                Spacer(Modifier.size(Spacing.s))
                Badge("Manual")
            }
        }

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
private fun EmptyHistory(onAddRun: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(Spacing.section),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.ic_snail),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(Spacing.l))
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
            Spacer(Modifier.height(Spacing.m))
            TextButton(onClick = onAddRun) { Text("Add a run you did without the app") }
        }
    }
}

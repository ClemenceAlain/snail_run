package io.snailrun.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.snailrun.data.db.RunEntity
import io.snailrun.domain.model.LatLon
import io.snailrun.domain.model.Split
import io.snailrun.ui.components.MetricRow
import io.snailrun.ui.components.RouteTrace
import io.snailrun.ui.components.SnailCard
import io.snailrun.ui.format.RunFormat
import io.snailrun.ui.theme.SnailType
import io.snailrun.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Built per call: a formatter cached at class-init keeps the locale the app
// started with, which is wrong after the user changes the system language.
private fun headerformat() = DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm", Locale.getDefault())

data class RunDetailUiState(
    val run: RunEntity? = null,
    val segments: List<List<LatLon>> = emptyList(),
    val splits: List<Split> = emptyList(),
    /** Distances for which this run currently holds the record. */
    val records: List<Pair<Int, Long>> = emptyList(),
)

@Composable
fun RunDetailScreen(state: RunDetailUiState, modifier: Modifier = Modifier) {
    val run = state.run ?: return

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = Spacing.screen,
            end = Spacing.screen,
            bottom = Spacing.huge,
        ),
    ) {
        item {
            val zone = runCatching { ZoneId.of(run.timeZoneId) }.getOrDefault(ZoneId.systemDefault())
            Text(
                text = run.title ?: "Run",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = headerformat().format(Instant.ofEpochMilli(run.startedAtEpochMs).atZone(zone)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xxl))
        }

        if (state.segments.isNotEmpty()) {
            item {
                RouteTrace(
                    segments = state.segments,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 10f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                )
                Spacer(Modifier.height(Spacing.xxl))
            }
        }

        item {
            MetricRow(
                metrics = listOf(
                    RunFormat.distanceKm(run.distanceMeters) to "km",
                    RunFormat.duration(run.movingTimeMs) to "time",
                    RunFormat.pace(run.avgPaceSecPerKm.takeIf { it > 0 }) to "/km",
                ),
            )
            Spacer(Modifier.height(Spacing.section))
        }

        items(state.records) { (distance, durationMs) ->
            RecordCard(distanceMeters = distance, durationMs = durationMs)
            Spacer(Modifier.height(Spacing.m))
        }

        if (state.splits.isNotEmpty()) {
            item {
                Spacer(Modifier.height(Spacing.l))
                Text(text = "Splits", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Spacing.m))
            }
            val fastest = state.splits.filter { !it.isPartial }.minByOrNull { it.paceSecPerKm }
            val slowest = state.splits.maxOfOrNull { it.paceSecPerKm } ?: 1.0
            items(state.splits) { split ->
                SplitRow(split = split, slowestPace = slowest, isFastest = split == fastest)
            }
        }

        item {
            Spacer(Modifier.height(Spacing.section))
            Text(
                text = "Elevation  +${RunFormat.elevation(run.elevationGainM)} m " +
                    "/ −${RunFormat.elevation(run.elevationLossM)} m (approximate)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The one warm note in the app: coral, and only for a record. */
@Composable
private fun RecordCard(distanceMeters: Int, durationMs: Long) {
    SnailCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentPadding = PaddingValues(Spacing.l),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Fastest ${label(distanceMeters)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = RunFormat.duration(durationMs),
                style = SnailType.metricSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun SplitRow(split: Split, slowestPace: Double, isFastest: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            text = "${split.index + 1}",
            style = SnailType.metricSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(24.dp),
        )
        Text(
            text = RunFormat.pace(split.paceSecPerKm),
            style = SnailType.metricSmall,
            modifier = Modifier.width(56.dp),
        )
        // Bar length tracks pace, so the shape of the run is readable at a glance.
        val fraction = (split.paceSecPerKm / slowestPace).coerceIn(0.05, 1.0).toFloat()
        Box(
            Modifier
                .weight(1f)
                .height(10.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(10.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(
                        when {
                            split.isPartial -> MaterialTheme.colorScheme.outlineVariant
                            isFastest -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.primaryContainer
                        }
                    ),
            )
        }
        if (split.isPartial) {
            Text(
                text = RunFormat.distanceKm(split.distanceMeters),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun label(distanceMeters: Int): String = when (distanceMeters) {
    21_097 -> "half marathon"
    42_195 -> "marathon"
    else -> "${distanceMeters / 1000} km"
}

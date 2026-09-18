package io.snailrun.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import io.snailrun.data.db.RunEntity
import io.snailrun.domain.analysis.ProfileSample
import io.snailrun.domain.analysis.ProfileSelection
import io.snailrun.domain.model.LatLon
import io.snailrun.ui.components.MetricRow
import io.snailrun.ui.components.PaceProfileChart
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
    /** Pace and elevation along the run, one entry per column of the graph. */
    val profile: List<ProfileSample> = emptyList(),
    /** The stretch the reader has dragged out on the graph, if any. */
    val selection: ProfileSelection? = null,
    /** Distances for which this run currently holds the record. */
    val records: List<Pair<Int, Long>> = emptyList(),
)

@Composable
fun RunDetailScreen(
    state: RunDetailUiState,
    onSelect: (Double, Double) -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
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

        if (state.profile.size >= 2) {
            item {
                Spacer(Modifier.height(Spacing.l))
                Text(text = "Pace", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Drag across the graph for the average over a stretch.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.m))

                PaceProfileChart(
                    samples = state.profile,
                    selection = state.selection?.let { it.fromM..it.toM },
                    onSelectionChange = { range ->
                        if (range == null) onClearSelection()
                        else onSelect(range.start, range.endInclusive)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.s))
                DistanceAxis(totalM = state.profile.last().distanceM)
                Spacer(Modifier.height(Spacing.m))
                SelectionSummary(state.selection)
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

/**
 * What the dragged stretch came to. Present but empty before anything is selected, so
 * the screen does not jump when a selection appears.
 */
@Composable
private fun SelectionSummary(selection: ProfileSelection?) {
    SnailCard(modifier = Modifier.fillMaxWidth()) {
        if (selection == null) {
            Text(
                text = "No stretch selected",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SnailCard
        }

        Text(
            text = "${RunFormat.distanceKm(selection.fromM)} – " +
                "${RunFormat.distanceKm(selection.toM)} km",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(Spacing.m))
        MetricRow(
            metrics = listOf(
                RunFormat.pace(selection.paceSecPerKm) to "/km",
                RunFormat.duration(selection.durationMs) to "time",
                RunFormat.distanceKm(selection.distanceM) to "km",
                "+${RunFormat.elevation(selection.elevationGainM)}" to "climb",
            ),
        )
    }
}

/** Just the two ends. A run has no interesting tick marks in between. */
@Composable
private fun DistanceAxis(totalM: Double) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        listOf("0", "${RunFormat.distanceKm(totalM)} km").forEach { label ->
            Text(
                text = label,
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

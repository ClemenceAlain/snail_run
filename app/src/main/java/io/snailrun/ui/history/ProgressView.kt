package io.snailrun.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.snailrun.domain.analysis.Progress
import io.snailrun.domain.analysis.ProgressBucket
import io.snailrun.domain.analysis.ProgressPeriod
import io.snailrun.ui.components.MetricRow
import io.snailrun.ui.components.SnailCard
import io.snailrun.ui.format.RunFormat
import io.snailrun.ui.theme.Spacing
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

// Built per call: a formatter cached at class-init keeps the locale the app
// started with, which is wrong after the user changes the system language.
private fun weekLabel() = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private fun monthLabel() = DateTimeFormatter.ofPattern("LLL", Locale.getDefault())

/**
 * How much running, over time.
 *
 * Bars for the distance each week or month, and a trailing four-period mean over them.
 * The mean is what makes the chart worth having: week-to-week distance is noisy enough
 * that the bars alone say nothing about whether the training is going anywhere.
 */
@Composable
fun ProgressView(
    buckets: List<ProgressBucket>,
    period: ProgressPeriod,
    onSetPeriod: (ProgressPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            ProgressPeriod.entries.forEach { option ->
                FilterChip(
                    selected = period == option,
                    onClick = { onSetPeriod(option) },
                    label = { Text(if (option == ProgressPeriod.Week) "Weekly" else "Monthly") },
                )
            }
        }

        Spacer(Modifier.height(Spacing.l))

        if (buckets.isEmpty()) return@Column

        val trend = Progress.trend(buckets)
        val tallest = max(
            buckets.maxOf { it.meters },
            trend.filterNotNull().maxOrNull() ?: 0.0,
        )

        BarChart(buckets = buckets, trend = trend, tallest = tallest)

        Spacer(Modifier.height(Spacing.s))
        Row(modifier = Modifier.fillMaxWidth()) {
            // Only the ends are labelled: a phone-width chart of twelve bars has no room
            // for twelve dates, and the middle ones are read off the shape anyway.
            listOf(buckets.first(), buckets.last()).forEachIndexed { i, bucket ->
                Text(
                    text = when (period) {
                        ProgressPeriod.Week -> weekLabel().format(bucket.start)
                        ProgressPeriod.Month -> monthLabel().format(bucket.start)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(Spacing.l))

        val latest = buckets.last()
        SnailCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (period == ProgressPeriod.Week) "This week, so far" else "This month, so far",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(Spacing.m))
            MetricRow(
                metrics = listOf(
                    RunFormat.distanceKm(latest.meters) to "km",
                    RunFormat.duration(latest.movingMs) to "time",
                    "${latest.runCount}" to "runs",
                ),
            )
        }

        Spacer(Modifier.height(Spacing.m))

        SnailCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Over these ${buckets.size} " +
                    if (period == ProgressPeriod.Week) "weeks" else "months",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(Spacing.m))
            MetricRow(
                metrics = listOf(
                    RunFormat.distanceKm(buckets.sumOf { it.meters }) to "km",
                    RunFormat.duration(buckets.sumOf { it.movingMs }) to "time",
                    "${buckets.sumOf { it.runCount }}" to "runs",
                ),
            )
        }
    }
}

@Composable
private fun BarChart(buckets: List<ProgressBucket>, trend: List<Double?>, tallest: Double) {
    val barColor = MaterialTheme.colorScheme.primaryContainer
    val latestColor = MaterialTheme.colorScheme.primary
    val trendColor = MaterialTheme.colorScheme.secondary
    val baselineColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
        if (tallest <= 0.0) return@Canvas

        val slot = size.width / buckets.size
        val barWidth = slot * 0.62f
        val gutter = (slot - barWidth) / 2f

        buckets.forEachIndexed { i, bucket ->
            val height = (bucket.meters / tallest).toFloat() * size.height
            drawRect(
                // The last bar is the period still being run, so it is drawn as the one
                // number on the chart that is not final.
                color = if (i == buckets.lastIndex) latestColor else barColor,
                topLeft = Offset(i * slot + gutter, size.height - height),
                size = Size(barWidth, height),
            )
        }

        val path = Path()
        var started = false
        trend.forEachIndexed { i, value ->
            if (value == null) return@forEachIndexed
            val x = i * slot + slot / 2f
            val y = size.height - (value / tallest).toFloat() * size.height
            if (started) path.lineTo(x, y) else path.moveTo(x, y).also { started = true }
        }
        drawPath(
            path = path,
            color = trendColor,
            style = Stroke(
                width = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            ),
        )

        drawLine(
            color = baselineColor,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1f,
        )
    }
}

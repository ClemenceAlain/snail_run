package io.snailrun.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import io.snailrun.domain.analysis.ProfileSample
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The shape of a run: pace as a line, elevation as the ground under it, distance along
 * the bottom.
 *
 * Dragging across it selects a stretch, which is the point of the thing — per-kilometre
 * splits answer "how fast was kilometre four", and this answers "how fast was that
 * climb", which is the question a runner actually has.
 *
 * Selection is carried in metres rather than pixels so it survives a rotation, and the
 * gesture is horizontal-only so the screen it sits in can still be scrolled through.
 *
 * Pace is drawn upside down: a faster pace is a smaller number, and a runner reads
 * higher as better.
 */
@Composable
fun PaceProfileChart(
    samples: List<ProfileSample>,
    selection: ClosedFloatingPointRange<Double>?,
    onSelectionChange: (ClosedFloatingPointRange<Double>?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (samples.size < 2) return

    val totalM = samples.last().distanceM
    if (totalM <= 0.0) return

    var widthPx by remember { mutableFloatStateOf(0f) }
    var anchorM by remember { mutableFloatStateOf(0f) }

    val paceColor = MaterialTheme.colorScheme.primary
    val elevationColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val edgeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    val baselineColor = MaterialTheme.colorScheme.outlineVariant

    fun metresAt(x: Float): Double =
        if (widthPx <= 0f) 0.0 else (x / widthPx).coerceIn(0f, 1f) * totalM

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(totalM) {
                detectTapGestures(onTap = { onSelectionChange(null) })
            }
            .pointerInput(totalM) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> anchorM = metresAt(offset.x).toFloat() },
                    onHorizontalDrag = { change, _ ->
                        val here = metresAt(change.position.x)
                        val from = min(anchorM.toDouble(), here)
                        val to = max(anchorM.toDouble(), here)
                        // A stray tap that drifts a few pixels is not a selection.
                        onSelectionChange(if (to - from < 20.0) null else from..to)
                        change.consume()
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
            val paces = samples.mapNotNull { it.paceSecPerKm }
            if (paces.isEmpty()) return@Canvas

            // Clipped to a high percentile: one stretch of walking would otherwise
            // flatten the whole run into a line along the top of the chart.
            val fastest = paces.min()
            val slowest = paces.sorted()[(paces.size * 0.95).toInt().coerceAtMost(paces.size - 1)]
            val paceSpan = max(slowest - fastest, 1.0)

            drawElevation(samples, totalM, elevationColor)
            drawSelection(selection, totalM, selectionColor, edgeColor)

            drawLine(
                color = baselineColor,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1f,
            )

            val path = Path()
            var started = false
            samples.forEach { sample ->
                val pace = sample.paceSecPerKm
                if (pace == null) {
                    // A hole in the line is honest: there was no pace to draw here.
                    started = false
                    return@forEach
                }
                val x = (sample.distanceM / totalM).toFloat() * size.width
                val normalised = ((pace - fastest) / paceSpan).coerceIn(0.0, 1.0)
                val y = (0.08f + 0.62f * normalised.toFloat()) * size.height
                if (started) path.lineTo(x, y) else path.moveTo(x, y).also { started = true }
            }
            drawPath(path, color = paceColor, style = Stroke(width = 2.5.dp.toPx()))
        }
    }
}

/** The ground: a filled area across the bottom third, deliberately quiet. */
private fun DrawScope.drawElevation(
    samples: List<ProfileSample>,
    totalM: Double,
    color: Color,
) {
    val elevations = samples.mapNotNull { it.elevationM }
    if (elevations.size < 2) return
    val low = elevations.min()
    val high = elevations.max()
    // A flat run has no profile to show, and stretching a metre of noise to full height
    // would invent a mountain.
    if (abs(high - low) < 2.0) return

    val path = Path()
    path.moveTo(0f, size.height)
    samples.forEach { sample ->
        val elevation = sample.elevationM ?: return@forEach
        val x = (sample.distanceM / totalM).toFloat() * size.width
        val normalised = ((elevation - low) / (high - low)).toFloat()
        path.lineTo(x, size.height - (0.08f + 0.26f * normalised) * size.height)
    }
    path.lineTo(size.width, size.height)
    path.close()
    drawPath(path, color = color)
}

private fun DrawScope.drawSelection(
    selection: ClosedFloatingPointRange<Double>?,
    totalM: Double,
    fill: Color,
    edge: Color,
) {
    if (selection == null) return
    val left = (selection.start / totalM).toFloat() * size.width
    val right = (selection.endInclusive / totalM).toFloat() * size.width

    drawRect(color = fill, topLeft = Offset(left, 0f), size = Size(right - left, size.height))
    listOf(left, right).forEach { x ->
        drawLine(
            color = edge,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 2f,
        )
    }
}

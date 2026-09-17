package io.snailrun.ui.components

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.snailrun.domain.geo.Projection
import io.snailrun.domain.geo.Simplify
import io.snailrun.domain.model.LatLon
import io.snailrun.ui.theme.SnailTheme

/**
 * Draws a run from its own GPS points, with no map and no network.
 *
 * This is the app's only renderer until a basemap file is present on the phone, and it
 * stays the fallback afterwards: a run outside the bundled region still has to be
 * visible. Segments are drawn as separate sub-paths, so a pause never becomes a
 * straight line across town.
 */
@Composable
fun RouteTrace(
    segments: List<List<LatLon>>,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 4.dp,
    padding: Dp = 12.dp,
    animateOnFirstShow: Boolean = true,
    showEndpoints: Boolean = true,
    maxPoints: Int = 600,
) {
    val traceColor = SnailTheme.extended.trace
    val startColor = MaterialTheme.colorScheme.primary
    val endColor = MaterialTheme.colorScheme.secondary

    val simplified = remember(segments, maxPoints) {
        segments.filter { it.size >= 2 }.map { Simplify.toAtMost(it, maxPoints) }
    }

    var played by remember { mutableStateOf(!animateOnFirstShow) }
    LaunchedEffect(simplified) { played = true }
    // One-shot: opening a run draws the route in, and it never replays on recomposition.
    val progress by animateFloatAsState(
        targetValue = if (played) 1f else 0f,
        animationSpec = tween(durationMillis = 600, easing = LinearOutSlowInEasing),
        label = "trace",
    )

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (simplified.isEmpty()) return@Canvas

            // One projection over every segment, so they share a frame of reference.
            val flattened = simplified.flatten()
            val projected = Projection.fit(flattened, size.width, size.height, padding.toPx())

            var index = 0
            val strokePx = strokeWidth.toPx()
            var firstPoint: Offset? = null
            var lastPoint: Offset? = null

            simplified.forEach { segment ->
                val path = Path()
                segment.indices.forEach { i ->
                    val point = projected[index + i]
                    val offset = Offset(point.x, point.y)
                    if (i == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
                    if (firstPoint == null) firstPoint = offset
                    lastPoint = offset
                }
                index += segment.size

                val drawn = if (progress >= 1f) path else path.trimmed(progress)
                drawPath(
                    path = drawn,
                    color = traceColor,
                    style = Stroke(
                        width = strokePx,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }

            if (showEndpoints && progress >= 1f) {
                firstPoint?.let { drawCircle(startColor, radius = strokePx * 1.4f, center = it) }
                lastPoint?.let { drawCircle(endColor, radius = strokePx * 1.4f, center = it) }
            }
        }
    }
}

private fun Path.trimmed(fraction: Float): Path {
    val measure = PathMeasure().apply { setPath(this@trimmed, false) }
    val destination = Path()
    measure.getSegment(0f, measure.length * fraction, destination, true)
    return destination
}

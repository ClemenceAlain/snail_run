package io.snailrun.ui.components

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.snailrun.domain.geo.LatLonBounds
import io.snailrun.domain.geo.MapCamera
import io.snailrun.domain.geo.Projection
import io.snailrun.domain.geo.Simplify
import io.snailrun.domain.geo.WebMercator
import io.snailrun.domain.model.LatLon
import io.snailrun.ui.theme.SnailTheme
import kotlin.math.pow

/**
 * Draws a run from its own GPS points, over a basemap when the phone has one.
 *
 * With no basemap this is the whole renderer, and it stays the fallback afterwards: a
 * run outside the region the tile file covers still has to be visible, and it is — the
 * trace simply draws over blank. Segments are drawn as separate sub-paths, so a pause
 * never becomes a straight line across town.
 *
 * With a basemap the projection switches to Web Mercator, because that is what tiles are
 * cut to. Keeping the app's own equirectangular projection here would put the trace
 * visibly beside the road it was run on.
 *
 * This one is fixed to the run's own bounds and does not move. [RouteMapScreen] is the
 * same trace under a camera you can drag.
 */
@Composable
fun RouteTrace(
    segments: List<List<LatLon>>,
    modifier: Modifier = Modifier,
    /**
     * A stretch of the run to pick out, dragged on the pace graph. Drawn over the trace
     * in the same frame, so the two cannot disagree about where it is.
     */
    highlight: List<List<LatLon>> = emptyList(),
    basemap: BasemapLayer? = null,
    strokeWidth: Dp = 4.dp,
    padding: Dp = 12.dp,
    animateOnFirstShow: Boolean = true,
    showEndpoints: Boolean = true,
    maxPoints: Int = 600,
) {
    val traceColor = SnailTheme.extended.trace
    val startColor = MaterialTheme.colorScheme.primary
    val endColor = MaterialTheme.colorScheme.secondary
    val highlightColor = MaterialTheme.colorScheme.secondary

    val simplified = rememberSimplified(segments, maxPoints)
    val selected = rememberSimplified(highlight, maxPoints)
    val bounds = remember(simplified) { LatLonBounds.of(simplified.flatten()) }

    var played by remember { mutableStateOf(!animateOnFirstShow) }
    LaunchedEffect(simplified) { played = true }
    // One-shot: opening a run draws the route in, and it never replays on recomposition.
    val progress by animateFloatAsState(
        targetValue = if (played) 1f else 0f,
        animationSpec = tween(durationMillis = 600, easing = LinearOutSlowInEasing),
        label = "trace",
    )

    BoxWithConstraints(modifier = modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val paddingPx = with(LocalDensity.current) { padding.toPx() }
        val strokePx = with(LocalDensity.current) { strokeWidth.toPx() }

        val layer = basemap?.takeIf { it.covers(bounds) }

        val viewport = remember(bounds, layer, widthPx, heightPx, paddingPx) {
            if (layer == null || bounds == null) return@remember null
            WebMercator.fit(
                bounds = bounds,
                widthPx = widthPx,
                heightPx = heightPx,
                paddingPx = paddingPx,
                minZoom = layer.minZoom,
                maxZoom = layer.maxZoom,
            )
        }

        val range = remember(viewport, widthPx, heightPx) {
            viewport?.let { WebMercator.tilesFor(it, widthPx, heightPx) }
        }
        val tiles = rememberBasemapTiles(layer, range)

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (simplified.isEmpty()) return@Canvas

            viewport?.let { drawBasemap(it, tiles, size.width, size.height) }

            // One projection over every segment, so they share a frame of reference —
            // and the same one over the highlight, which is cut from the same track.
            val flattened = simplified.flatten()
            val project: (LatLon) -> Offset = viewport?.let { view ->
                { point -> view.project(point).let { (x, y) -> Offset(x, y) } }
            } ?: Projection.fitting(flattened, size.width, size.height, paddingPx)
                .let { fitted -> { point -> fitted(point).let { Offset(it.x, it.y) } } }

            drawTrace(
                segments = simplified,
                projected = flattened.map(project),
                traceColor = if (selected.isEmpty()) traceColor else traceColor.copy(alpha = 0.35f),
                startColor = startColor,
                endColor = endColor,
                strokePx = strokePx,
                progress = progress,
                showEndpoints = showEndpoints,
            )

            // Over the top, at full strength against a faded run. Dimming the rest
            // rather than only brightening the stretch is what makes a selection
            // readable on a trace that doubles back over itself.
            if (selected.isNotEmpty() && progress >= 1f) {
                drawTrace(
                    segments = selected,
                    projected = selected.flatten().map(project),
                    traceColor = highlightColor,
                    startColor = highlightColor,
                    endColor = highlightColor,
                    strokePx = strokePx * 1.5f,
                    progress = 1f,
                    showEndpoints = true,
                )
            }
        }
    }
}

/**
 * The points actually drawn. Thinning them is what keeps an hour's run — three and a
 * half thousand fixes — from being a path the rasteriser walks on every frame of a drag.
 */
@Composable
internal fun rememberSimplified(
    segments: List<List<LatLon>>,
    maxPoints: Int,
): List<List<LatLon>> = remember(segments, maxPoints) {
    segments.filter { it.size >= 2 }.map { Simplify.toAtMost(it, maxPoints) }
}

/**
 * The run itself, over whatever is beneath it.
 *
 * [projected] is every point of every segment, already flattened and in the same order,
 * so one projection pass serves all of them and the segments cannot drift apart.
 */
internal fun DrawScope.drawTrace(
    segments: List<List<LatLon>>,
    projected: List<Offset>,
    traceColor: Color,
    startColor: Color,
    endColor: Color,
    strokePx: Float,
    progress: Float,
    showEndpoints: Boolean,
) {
    var index = 0
    var firstPoint: Offset? = null
    var lastPoint: Offset? = null

    segments.forEach { segment ->
        val path = Path()
        segment.indices.forEach { i ->
            val offset = projected[index + i]
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

private fun Path.trimmed(fraction: Float): Path {
    val measure = PathMeasure().apply { setPath(this@trimmed, false) }
    val destination = Path()
    measure.getSegment(0f, measure.length * fraction, destination, true)
    return destination
}

/**
 * The run's points in world pixels, held once and scaled per frame.
 *
 * Projecting latitude costs an `asinh` and a `tan`, and a dragged map would pay it for
 * every point on every frame. Web Mercator scales linearly with zoom, though, so the
 * expensive part can be done once at a reference zoom and every later frame is a
 * multiply and a subtract.
 */
internal class TraceProjector(points: List<LatLon>) {

    private val worldX = DoubleArray(points.size) {
        WebMercator.worldX(points[it].lon, REFERENCE_ZOOM)
    }
    private val worldY = DoubleArray(points.size) {
        WebMercator.worldY(points[it].lat, REFERENCE_ZOOM)
    }

    fun project(camera: MapCamera, widthPx: Float, heightPx: Float): List<Offset> {
        val factor = 2.0.pow(camera.zoom - REFERENCE_ZOOM)
        val originX = WebMercator.worldX(camera.centerLon, camera.zoom) - widthPx / 2.0
        val originY = WebMercator.worldY(camera.centerLat, camera.zoom) - heightPx / 2.0
        return List(worldX.size) { i ->
            Offset(
                (worldX[i] * factor - originX).toFloat(),
                (worldY[i] * factor - originY).toFloat(),
            )
        }
    }

    private companion object {
        /** Deep enough that the rounding never shows, shallow enough to stay exact. */
        const val REFERENCE_ZOOM = 22.0
    }
}

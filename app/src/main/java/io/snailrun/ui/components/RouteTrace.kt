package io.snailrun.ui.components

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.snailrun.domain.geo.LatLonBounds
import io.snailrun.domain.geo.MapViewport
import io.snailrun.domain.geo.Point2D
import io.snailrun.domain.geo.Projection
import io.snailrun.domain.geo.Simplify
import io.snailrun.domain.geo.WebMercator
import io.snailrun.domain.model.LatLon
import kotlin.math.roundToInt
import io.snailrun.ui.theme.SnailTheme

/** Tiles to draw under a trace, and the zoom levels the file actually holds. */
data class BasemapLayer(
    val minZoom: Int,
    val maxZoom: Int,
    val tile: suspend (zoom: Int, x: Int, y: Int) -> ImageBitmap?,
)

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
 */
@Composable
fun RouteTrace(
    segments: List<List<LatLon>>,
    modifier: Modifier = Modifier,
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

    BoxWithConstraints(modifier = modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val paddingPx = with(androidx.compose.ui.platform.LocalDensity.current) { padding.toPx() }

        val viewport = remember(simplified, basemap, widthPx, heightPx) {
            if (basemap == null || simplified.isEmpty()) return@remember null
            LatLonBounds.of(simplified.flatten())?.let { bounds ->
                WebMercator.fit(
                    bounds = bounds,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    paddingPx = paddingPx,
                    minZoom = basemap.minZoom,
                    maxZoom = basemap.maxZoom,
                )
            }
        }

        val tiles = remember(viewport) { mutableStateMapOf<String, ImageBitmap>() }
        LaunchedEffect(viewport) {
            val view = viewport ?: return@LaunchedEffect
            val layer = basemap ?: return@LaunchedEffect
            val range = WebMercator.tilesFor(view, widthPx, heightPx)
            // Loaded one at a time and published as they arrive, so a big file draws
            // progressively instead of holding a blank box until the last tile decodes.
            for (x in range.minX..range.maxX) {
                for (y in range.minY..range.maxY) {
                    layer.tile(range.zoom, x, y)?.let { tiles["$x/$y"] = it }
                }
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (simplified.isEmpty()) return@Canvas

            viewport?.let { drawTiles(it, tiles, size.width, size.height) }

            // One projection over every segment, so they share a frame of reference.
            val flattened = simplified.flatten()
            val projected = viewport?.let { view ->
                flattened.map { point ->
                    val (x, y) = view.project(point)
                    Point2D(x, y)
                }
            } ?: Projection.fit(flattened, size.width, size.height, padding.toPx())

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

/**
 * The map under the trace. Tiles that have not arrived are simply not drawn: a gap that
 * fills in a moment later reads better than a spinner over a route.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTiles(
    viewport: MapViewport,
    tiles: Map<String, ImageBitmap>,
    widthPx: Float,
    heightPx: Float,
) {
    val range = WebMercator.tilesFor(viewport, widthPx, heightPx)
    val drawn = viewport.drawnTileSize.roundToInt()
    for (x in range.minX..range.maxX) {
        for (y in range.minY..range.maxY) {
            val bitmap = tiles["$x/$y"] ?: continue
            val (left, top) = viewport.tileTopLeft(x, y)
            drawImage(
                image = bitmap,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(bitmap.width, bitmap.height),
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(drawn, drawn),
                // Tiles are upscaled by at most two, and bilinear beats the blocky
                // nearest-neighbour default at that ratio.
                filterQuality = FilterQuality.Low,
            )
        }
    }
}

private fun Path.trimmed(fraction: Float): Path {
    val measure = PathMeasure().apply { setPath(this@trimmed, false) }
    val destination = Path()
    measure.getSegment(0f, measure.length * fraction, destination, true)
    return destination
}

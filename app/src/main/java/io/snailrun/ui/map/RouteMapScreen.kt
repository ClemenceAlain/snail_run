package io.snailrun.ui.map

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.snailrun.R
import io.snailrun.domain.geo.LatLonBounds
import io.snailrun.domain.geo.MapCamera
import io.snailrun.domain.geo.WebMercator
import io.snailrun.domain.model.LatLon
import io.snailrun.ui.components.BasemapLayer
import io.snailrun.ui.components.TraceProjector
import io.snailrun.ui.components.drawBasemap
import io.snailrun.ui.components.drawTrace
import io.snailrun.ui.components.rememberBasemapTiles
import io.snailrun.ui.components.rememberSimplified
import io.snailrun.ui.theme.SnailTheme
import io.snailrun.ui.theme.Spacing
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The run on a map you can drag, pinch and double-tap.
 *
 * The small trace on the run's own screen is fitted to the run and deliberately fixed —
 * it is a picture of the shape, read at a glance. This one is the other thing you want
 * from a map, which is to look closely at one corner of it, and the two have different
 * enough jobs that trying to be both would make the card on the detail screen move
 * whenever a thumb brushed it.
 *
 * It works with no map file at all: the camera projects the trace whether or not there
 * are tiles under it, so zooming into a corner of a run is not something you have to
 * supply hundreds of megabytes to be allowed to do.
 */
@Composable
fun RouteMapScreen(
    segments: List<List<LatLon>>,
    modifier: Modifier = Modifier,
    basemap: BasemapLayer? = null,
    maxPoints: Int = 1_200,
) {
    val simplified = rememberSimplified(segments, maxPoints)
    val points = remember(simplified) { simplified.flatten() }
    val bounds = remember(points) { LatLonBounds.of(points) }
    val projector = remember(points) { TraceProjector(points) }

    val traceColor = SnailTheme.extended.trace
    val startColor = MaterialTheme.colorScheme.primary
    val endColor = MaterialTheme.colorScheme.secondary

    BoxWithConstraints(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val paddingPx = with(density) { Spacing.section.toPx() }
        val strokePx = with(density) { 5.dp.toPx() }

        val layer = basemap?.takeIf { it.covers(bounds) }

        // How far out is worth going, and how far in. Out is three doublings past the
        // whole run, which is enough to see where it sits without losing it in the
        // ocean. In stops two past the deepest tiles, where enlargement is all that is
        // left to give.
        // Deliberately not keyed on the layer, though the zoom range below is. The map
        // file is read off the disk a moment after this screen opens, and keying the fit
        // on it would reset the camera — throwing away a drag the reader had already
        // started. Where to frame the run does not depend on what is under it.
        val fitted = remember(bounds, widthPx, heightPx, paddingPx) {
            bounds?.let {
                MapCamera.fitting(it, widthPx, heightPx, paddingPx, WIDEST_ZOOM..DEEPEST_ZOOM)
            }
        }
        val zoomRange = remember(fitted, layer) {
            val deepest = (layer?.maxZoom?.toDouble() ?: DEFAULT_DEEPEST_TILE_ZOOM) + 2.0
            val widest = ((fitted?.zoom ?: deepest) - 3.0).coerceAtLeast(WIDEST_ZOOM)
            widest..maxOf(deepest, fitted?.zoom ?: deepest)
        }

        var camera by remember(fitted) { mutableStateOf(fitted) }
        val current = camera

        val viewport = remember(current, widthPx, heightPx, layer) {
            current?.viewport(
                widthPx = widthPx,
                heightPx = heightPx,
                minTileZoom = layer?.minZoom ?: 0,
                maxTileZoom = layer?.maxZoom ?: MAX_TILE_ZOOM,
            )
        }
        // Tiles are keyed on the range, not the viewport: a range changes when the map
        // crosses a tile edge, a viewport changes on every frame of a drag.
        val range = remember(viewport, widthPx, heightPx, layer) {
            if (layer == null || viewport == null) null
            else WebMercator.tilesFor(viewport, widthPx, heightPx)
        }
        val tiles = rememberBasemapTiles(layer, range)

        val scope = rememberCoroutineScope()
        var animation by remember { mutableStateOf<Job?>(null) }

        fun zoomTo(target: Double, focus: Offset) {
            val from = camera ?: return
            animation?.cancel()
            animation = scope.launch {
                // Every step is measured from the camera the gesture started on, so the
                // point under the finger stays under it for the whole animation rather
                // than creeping as the error compounds.
                animate(
                    initialValue = from.zoom.toFloat(),
                    targetValue = target.coerceIn(zoomRange).toFloat(),
                    animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                ) { value, _ ->
                    camera = from.zoomedTo(
                        newZoom = value.toDouble(),
                        focusX = focus.x,
                        focusY = focus.y,
                        widthPx = widthPx,
                        heightPx = heightPx,
                        zoomRange = zoomRange,
                    )
                }
            }
        }

        fun recentre() {
            val target = fitted ?: return
            val from = camera ?: return
            animation?.cancel()
            animation = scope.launch {
                animate(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                ) { fraction, _ -> camera = from.towards(target, fraction) }
            }
        }

        val centre = remember(widthPx, heightPx) { Offset(widthPx / 2f, heightPx / 2f) }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(zoomRange, widthPx, heightPx) {
                    detectTapGestures(
                        onDoubleTap = { focus -> zoomTo((camera?.zoom ?: 0.0) + 1.0, focus) },
                    )
                }
                .pointerInput(zoomRange, widthPx, heightPx) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        animation?.cancel()
                        val panned = camera?.panned(pan.x, pan.y) ?: return@detectTransformGestures
                        camera = if (gestureZoom == 1f) {
                            panned
                        } else {
                            panned.zoomedTo(
                                newZoom = panned.zoom + log2(gestureZoom.toDouble()),
                                focusX = centroid.x,
                                focusY = centroid.y,
                                widthPx = widthPx,
                                heightPx = heightPx,
                                zoomRange = zoomRange,
                            )
                        }
                    }
                }
                .semantics {
                    contentDescription = "Map of the run. Drag to move, pinch to zoom."
                },
        ) {
            val view = viewport ?: return@Canvas
            val looking = camera ?: return@Canvas

            if (layer != null) drawBasemap(view, tiles, size.width, size.height)

            drawTrace(
                segments = simplified,
                projected = projector.project(looking, size.width, size.height),
                traceColor = traceColor,
                startColor = startColor,
                endColor = endColor,
                strokePx = strokePx,
                progress = 1f,
                showEndpoints = true,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            MapButton(R.drawable.ic_zoom_in, "Zoom in") {
                zoomTo((camera?.zoom ?: 0.0) + 1.0, centre)
            }
            MapButton(R.drawable.ic_zoom_out, "Zoom out") {
                zoomTo((camera?.zoom ?: 0.0) - 1.0, centre)
            }
            MapButton(R.drawable.ic_recenter, "Show the whole run") { recentre() }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            current?.let { ScaleBar(camera = it) }
            layer?.attribution?.let { credit ->
                Text(
                    text = credit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A round control over the map, legible whatever the tiles under it happen to be. */
@Composable
private fun MapButton(icon: Int, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp,
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = description,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * How long a stretch of the screen is on the ground.
 *
 * Mercator's scale changes with latitude, so this is computed from where the camera is
 * looking rather than fixed once: the same bar means a different distance in Oslo and in
 * Nairobi, and a bar that ignored that would be wrong everywhere but the equator.
 */
@Composable
private fun ScaleBar(camera: MapCamera) {
    val density = LocalDensity.current
    val metresPerPixel = remember(camera.zoom, camera.centerLat) {
        EQUATOR_METRES * cos(Math.toRadians(camera.centerLat)) /
            (WebMercator.DEFAULT_TILE_SIZE * 2.0.pow(camera.zoom))
    }

    val maxWidthPx = with(density) { 96.dp.toPx() }
    val metres = remember(metresPerPixel, maxWidthPx) { niceDistance(metresPerPixel * maxWidthPx) }
    val widthDp = with(density) { (metres / metresPerPixel).toFloat().toDp() }

    Column {
        Text(
            text = if (metres >= 1_000) "${(metres / 1_000).roundToInt()} km" else "${metres.roundToInt()} m",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(width = widthDp, height = 3.dp)
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    RoundedCornerShape(2.dp),
                ),
        )
    }
}

/**
 * The roundest distance no longer than [limit]: 1, 2 or 5 times a power of ten.
 *
 * Floored at a metre, which the deepest zoom this app reaches never gets near — a bar
 * labelled in centimetres would be arithmetically right and no use to a runner.
 */
internal fun niceDistance(limit: Double): Double {
    if (limit <= 1.0) return 1.0
    val magnitude = 10.0.pow(kotlin.math.floor(log10(limit)))
    return listOf(5.0, 2.0, 1.0).map { it * magnitude }.first { it <= limit }
}

/**
 * Part of the way from one camera to another.
 *
 * Interpolating the zoom linearly and the centre with it is the wrong curve for a long
 * flight across the world and exactly right for the only move this makes: back to a run
 * that is already most of the way on screen.
 */
private fun MapCamera.towards(target: MapCamera, fraction: Float): MapCamera {
    val t = fraction.toDouble().coerceIn(0.0, 1.0)
    return MapCamera(
        centerLat = centerLat + (target.centerLat - centerLat) * t,
        centerLon = centerLon + (target.centerLon - centerLon) * t,
        zoom = zoom + (target.zoom - zoom) * t,
    )
}

/** Metres round the equator, which is what a Mercator pixel is measured against. */
private const val EQUATOR_METRES = 40_075_016.686

private const val WIDEST_ZOOM = 1.0
private const val DEEPEST_ZOOM = 22.0
private const val DEFAULT_DEEPEST_TILE_ZOOM = 17.0
private const val MAX_TILE_ZOOM = 22

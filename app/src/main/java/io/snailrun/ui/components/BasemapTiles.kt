package io.snailrun.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.snailrun.domain.geo.LatLonBounds
import io.snailrun.domain.geo.MapViewport
import io.snailrun.domain.geo.TileRange
import io.snailrun.domain.geo.WebMercator
import kotlin.math.roundToInt

/**
 * Tiles to draw under a trace, and the zoom levels the file actually holds.
 *
 * [coverage] is the ground the file has tiles for, or null for a file that should be
 * used wherever a run happens to be. The map the app ships with sets it, because it
 * covers one small demo loop and nothing else: outside that, a run is better drawn the
 * way it was before any map existed than over a blank Mercator grid. A map the user
 * chose leaves it null — they picked it for their runs, and a hole in it is theirs to
 * see.
 */
data class BasemapLayer(
    val minZoom: Int,
    val maxZoom: Int,
    val tile: suspend (zoom: Int, x: Int, y: Int) -> ImageBitmap?,
    val coverage: LatLonBounds? = null,
    val attribution: String? = null,
) {
    /** Whether this layer has anything to say about a run over [bounds]. */
    fun covers(bounds: LatLonBounds?): Boolean {
        val area = coverage ?: return true
        return bounds != null && area.intersects(bounds)
    }
}

/** One tile's address. Held as a key, so it is worth being a value rather than a string. */
data class TileKey(val zoom: Int, val x: Int, val y: Int)

/**
 * How far up the pyramid to look for something to draw in a tile's place. Three levels
 * is an eight-fold enlargement — blurry, but a blurry map that pans is worth more than a
 * sharp one that flashes white every time it is touched.
 */
private const val MAX_ANCESTOR_FALLBACK = 3

/** Levels either side of the current one worth keeping decoded, for that fallback. */
private const val KEPT_LEVELS = 2

/**
 * The decoded tiles for a range, loaded as the map moves.
 *
 * Keyed by the tile range rather than by the viewport, because a viewport changes on
 * every frame of a drag and a range changes only when the map crosses a tile boundary.
 * Keying this on the viewport would restart the load continuously and never finish one.
 *
 * Tiles already decoded are kept across the change, so panning back over ground you have
 * already seen costs nothing, and a zoom has its parents to fall back on while the new
 * level arrives.
 */
@Composable
fun rememberBasemapTiles(
    layer: BasemapLayer?,
    range: TileRange?,
): SnapshotStateMap<TileKey, ImageBitmap> {
    val tiles = remember(layer) { mutableStateMapOf<TileKey, ImageBitmap>() }

    LaunchedEffect(layer, range) {
        if (layer == null || range == null) return@LaunchedEffect

        // The level above first: a quarter as many tiles, and it is what the fallback
        // draws while this level is still arriving.
        val parent = range.parent()
        for (target in listOfNotNull(parent, range)) {
            for (x in target.minX..target.maxX) {
                for (y in target.minY..target.maxY) {
                    val key = TileKey(target.zoom, x, y)
                    if (tiles.containsKey(key)) continue
                    // Published one at a time, so a big file fills in progressively
                    // instead of holding a blank box until the last tile decodes.
                    layer.tile(target.zoom, x, y)?.let { tiles[key] = it }
                }
            }
        }

        // Anything far from the current level can no longer be drawn or fallen back to,
        // and holding it would keep bitmaps alive past the store's own cache budget.
        tiles.keys
            .filter { kotlin.math.abs(it.zoom - range.zoom) > KEPT_LEVELS }
            .forEach { tiles.remove(it) }
    }

    return tiles
}

private fun TileRange.parent(): TileRange? {
    if (zoom <= 0) return null
    return TileRange(zoom - 1, minX / 2, maxX / 2, minY / 2, maxY / 2)
}

/**
 * The map under the trace.
 *
 * A tile that has not arrived is drawn from the nearest ancestor that has, enlarged.
 * With nothing at all to draw the space is simply left blank: a gap that fills in a
 * moment later reads better than a spinner over a route.
 */
fun DrawScope.drawBasemap(
    viewport: MapViewport,
    tiles: Map<TileKey, ImageBitmap>,
    widthPx: Float,
    heightPx: Float,
) {
    val range = WebMercator.tilesFor(viewport, widthPx, heightPx)
    val drawn = viewport.drawnTileSize
    for (x in range.minX..range.maxX) {
        for (y in range.minY..range.maxY) {
            val (left, top) = viewport.tileTopLeft(x, y)
            val destination = IntOffset(left.roundToInt(), top.roundToInt())
            // Sized from the far edge rather than by rounding the width, or neighbouring
            // tiles disagree by a pixel and the map is drawn with a grid of seams in it.
            val size = IntSize(
                (left + drawn).roundToInt() - destination.x,
                (top + drawn).roundToInt() - destination.y,
            )

            val bitmap = tiles[TileKey(range.zoom, x, y)]
            if (bitmap == null) {
                drawAncestor(range.zoom, x, y, tiles, destination, size)
            } else {
                drawImage(
                    image = bitmap,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(bitmap.width, bitmap.height),
                    dstOffset = destination,
                    dstSize = size,
                    filterQuality = FilterQuality.Low,
                )
            }
        }
    }
}

/** The same ground from a shallower level, cropped to the part this tile covers. */
private fun DrawScope.drawAncestor(
    zoom: Int,
    x: Int,
    y: Int,
    tiles: Map<TileKey, ImageBitmap>,
    destination: IntOffset,
    size: IntSize,
) {
    for (levels in 1..MAX_ANCESTOR_FALLBACK) {
        if (zoom - levels < 0) return
        val bitmap = tiles[TileKey(zoom - levels, x shr levels, y shr levels)] ?: continue

        val divisions = 1 shl levels
        val sourceWidth = bitmap.width / divisions
        val sourceHeight = bitmap.height / divisions
        if (sourceWidth < 1 || sourceHeight < 1) return

        drawImage(
            image = bitmap,
            srcOffset = IntOffset(
                (x and (divisions - 1)) * sourceWidth,
                (y and (divisions - 1)) * sourceHeight,
            ),
            srcSize = IntSize(sourceWidth, sourceHeight),
            dstOffset = destination,
            dstSize = size,
            filterQuality = FilterQuality.Low,
        )
        return
    }
}

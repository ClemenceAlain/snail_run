package io.snailrun.domain.geo

import io.snailrun.domain.model.LatLon
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow

/**
 * Where the map is looking: the point on Earth under the middle of the canvas, and a
 * continuous zoom.
 *
 * A fitted [MapViewport] is what the renderer needs and the wrong thing to hold across a
 * gesture: it is expressed in world pixels at one integer zoom, so a pinch would have to
 * rewrite its origin and its scale together and keep them consistent. A centre and a
 * zoom survive the gesture unchanged in meaning, and the viewport is derived from them
 * each frame.
 *
 * The useful identity here is that at the camera's own zoom, one world pixel is one
 * screen pixel — that is what a fractional zoom buys. So a drag is a subtraction in
 * world pixels, with no scale factor anywhere in it.
 */
data class MapCamera(
    val centerLat: Double,
    val centerLon: Double,
    val zoom: Double,
) {

    /**
     * The tile grid to draw this camera with.
     *
     * Tiles exist only at integer zooms, so the integer part picks the level and the
     * fraction becomes [MapViewport.scale]. Clamping the level to what the file holds is
     * what lets the camera zoom past the deepest tiles: the level stops, the scale keeps
     * going, and the tiles are drawn larger rather than not at all.
     */
    fun viewport(
        widthPx: Float,
        heightPx: Float,
        minTileZoom: Int,
        maxTileZoom: Int,
        tileSize: Int = WebMercator.DEFAULT_TILE_SIZE,
    ): MapViewport {
        val tileZoom = kotlin.math.floor(zoom).toInt().coerceIn(minTileZoom, maxTileZoom)
        val scale = 2.0.pow(zoom - tileZoom)
        val centreX = WebMercator.worldX(centerLon, tileZoom, tileSize)
        val centreY = WebMercator.worldY(centerLat, tileZoom, tileSize)
        return MapViewport(
            zoom = tileZoom,
            scale = scale,
            originX = centreX - widthPx / 2.0 / scale,
            originY = centreY - heightPx / 2.0 / scale,
            tileSize = tileSize,
        )
    }

    /** What is under a point on the canvas. The inverse of drawing. */
    fun unproject(
        x: Float,
        y: Float,
        widthPx: Float,
        heightPx: Float,
        tileSize: Int = WebMercator.DEFAULT_TILE_SIZE,
    ): LatLon {
        val worldX = WebMercator.worldX(centerLon, zoom, tileSize) + (x - widthPx / 2.0)
        val worldY = WebMercator.worldY(centerLat, zoom, tileSize) + (y - heightPx / 2.0)
        return LatLon(
            lat = WebMercator.latAt(worldY, zoom, tileSize),
            lon = WebMercator.lonAt(worldX, zoom, tileSize),
        )
    }

    /**
     * Dragged by a gesture. The finger and the map move together, so the centre moves
     * against the drag.
     */
    fun panned(
        dxPx: Float,
        dyPx: Float,
        tileSize: Int = WebMercator.DEFAULT_TILE_SIZE,
    ): MapCamera {
        val worldX = WebMercator.worldX(centerLon, zoom, tileSize) - dxPx
        val worldY = WebMercator.worldY(centerLat, zoom, tileSize) - dyPx
        return copy(
            centerLat = WebMercator.latAt(worldY, zoom, tileSize)
                .coerceIn(-WebMercator.MAX_LATITUDE, WebMercator.MAX_LATITUDE),
            centerLon = WebMercator.lonAt(worldX, zoom, tileSize),
        )
    }

    /**
     * Zoomed about a point on the canvas, keeping whatever is under that point under it.
     *
     * Pinching about the midpoint of two fingers and double-tapping a junction are the
     * same operation, and both are wrong if the map zooms about its own centre instead:
     * the thing you aimed at slides away while you enlarge it.
     */
    fun zoomedTo(
        newZoom: Double,
        focusX: Float,
        focusY: Float,
        widthPx: Float,
        heightPx: Float,
        zoomRange: ClosedFloatingPointRange<Double>,
        tileSize: Int = WebMercator.DEFAULT_TILE_SIZE,
    ): MapCamera {
        val target = newZoom.coerceIn(zoomRange)
        val anchor = unproject(focusX, focusY, widthPx, heightPx, tileSize)
        val anchorX = WebMercator.worldX(anchor.lon, target, tileSize)
        val anchorY = WebMercator.worldY(anchor.lat, target, tileSize)
        val centreX = anchorX - (focusX - widthPx / 2.0)
        val centreY = anchorY - (focusY - heightPx / 2.0)
        return MapCamera(
            centerLat = WebMercator.latAt(centreY, target, tileSize)
                .coerceIn(-WebMercator.MAX_LATITUDE, WebMercator.MAX_LATITUDE),
            centerLon = WebMercator.lonAt(centreX, target, tileSize),
            zoom = target,
        )
    }

    companion object {

        /**
         * The camera that frames [bounds] in a canvas of this size.
         *
         * Zoom is solved rather than searched: the span of the bounds in world pixels
         * doubles with every level, so the level that makes it fit is one logarithm.
         */
        fun fitting(
            bounds: LatLonBounds,
            widthPx: Float,
            heightPx: Float,
            paddingPx: Float,
            zoomRange: ClosedFloatingPointRange<Double>,
            tileSize: Int = WebMercator.DEFAULT_TILE_SIZE,
        ): MapCamera {
            val usableW = (widthPx - 2 * paddingPx).coerceAtLeast(1f).toDouble()
            val usableH = (heightPx - 2 * paddingPx).coerceAtLeast(1f).toDouble()

            // Spans at zoom 0, where the whole world is one tile. A run that stood still
            // has no span at all, and a metre of one is enough to take a logarithm of.
            val spanX = abs(
                WebMercator.worldX(bounds.maxLon, 0.0, tileSize) -
                    WebMercator.worldX(bounds.minLon, 0.0, tileSize)
            ).coerceAtLeast(1e-9)
            val spanY = abs(
                WebMercator.worldY(bounds.minLat, 0.0, tileSize) -
                    WebMercator.worldY(bounds.maxLat, 0.0, tileSize)
            ).coerceAtLeast(1e-9)

            val zoom = min(log2(usableW / spanX), log2(usableH / spanY)).coerceIn(zoomRange)

            // The vertical centre is the Mercator midpoint, not the average latitude.
            // Averaging degrees puts the track off centre, by more the further north it
            // was run.
            val midY = (
                WebMercator.worldY(bounds.minLat, 0.0, tileSize) +
                    WebMercator.worldY(bounds.maxLat, 0.0, tileSize)
                ) / 2.0

            return MapCamera(
                centerLat = WebMercator.latAt(midY, 0.0, tileSize),
                centerLon = (bounds.minLon + bounds.maxLon) / 2.0,
                zoom = zoom,
            )
        }
    }
}

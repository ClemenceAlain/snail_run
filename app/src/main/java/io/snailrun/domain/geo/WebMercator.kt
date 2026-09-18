package io.snailrun.domain.geo

import io.snailrun.domain.model.LatLon
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** The area a track covers, in degrees. */
data class LatLonBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
) {
    companion object {
        fun of(points: List<LatLon>): LatLonBounds? {
            if (points.isEmpty()) return null
            return LatLonBounds(
                minLat = points.minOf { it.lat },
                maxLat = points.maxOf { it.lat },
                minLon = points.minOf { it.lon },
                maxLon = points.maxOf { it.lon },
            )
        }
    }

    /**
     * Whether two areas overlap at all. Used to decide if a map file has anything to say
     * about a run, before its tiles are drawn under one.
     */
    fun intersects(other: LatLonBounds): Boolean =
        minLat <= other.maxLat && maxLat >= other.minLat &&
            minLon <= other.maxLon && maxLon >= other.minLon
}

/** Which tiles a viewport needs, inclusive at both ends. */
data class TileRange(val zoom: Int, val minX: Int, val maxX: Int, val minY: Int, val maxY: Int) {
    val count: Int get() = (maxX - minX + 1) * (maxY - minY + 1)
}

/**
 * A fitted map: which zoom's tiles to fetch, and where anything on Earth lands on the
 * canvas.
 *
 * [scale] is the extra factor applied on top of the zoom's native pixel size. Zoom is an
 * integer because tiles only exist at integers, and the leftover is taken up by scaling,
 * so the track fills the box instead of floating in the middle of it.
 */
data class MapViewport(
    val zoom: Int,
    val scale: Double,
    /** World pixel coordinates, at [zoom], of the canvas's top-left corner. */
    val originX: Double,
    val originY: Double,
    val tileSize: Int,
) {
    fun project(point: LatLon): Pair<Float, Float> {
        val x = (WebMercator.worldX(point.lon, zoom, tileSize) - originX) * scale
        val y = (WebMercator.worldY(point.lat, zoom, tileSize) - originY) * scale
        return x.toFloat() to y.toFloat()
    }

    /** Where a tile's top-left corner lands on the canvas, and how big it is drawn. */
    fun tileTopLeft(x: Int, y: Int): Pair<Float, Float> =
        ((x.toDouble() * tileSize - originX) * scale).toFloat() to
            ((y.toDouble() * tileSize - originY) * scale).toFloat()

    val drawnTileSize: Float get() = (tileSize * scale).toFloat()
}

/**
 * Spherical Web Mercator, the projection every raster tile set on Earth is cut to.
 *
 * Deliberately not the app's own equirectangular projection. That one is right for
 * measuring short distances and wrong for drawing on top of tiles, which are Mercator by
 * definition: mixing the two puts the track visibly beside the road it was run on.
 *
 * Spherical rather than ellipsoidal, matching the tile convention (EPSG:3857). The
 * difference is a projection artefact both the tiles and the track share, so they stay
 * aligned, and no distance is ever measured here.
 */
object WebMercator {

    const val DEFAULT_TILE_SIZE = 256

    /** Mercator diverges at the poles; tile sets stop here, so the maths does too. */
    const val MAX_LATITUDE = 85.05112878

    /**
     * The world in pixels at a fractional zoom.
     *
     * Zoom is continuous here and integral in [MapViewport], and the two are not the
     * same thing: tiles exist only at integers, but a pinch lands anywhere between
     * them. Everything below has a fractional form because a camera uses it, and an
     * integer form because a tile grid does.
     */
    fun worldSize(zoom: Double, tileSize: Int = DEFAULT_TILE_SIZE): Double =
        tileSize.toDouble() * 2.0.pow(zoom)

    fun worldX(lon: Double, zoom: Double, tileSize: Int = DEFAULT_TILE_SIZE): Double =
        (lon + 180.0) / 360.0 * worldSize(zoom, tileSize)

    fun worldY(lat: Double, zoom: Double, tileSize: Int = DEFAULT_TILE_SIZE): Double {
        val clamped = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val radians = Math.toRadians(clamped)
        return (1.0 - asinh(tan(radians)) / PI) / 2.0 * worldSize(zoom, tileSize)
    }

    fun lonAt(worldX: Double, zoom: Double, tileSize: Int = DEFAULT_TILE_SIZE): Double =
        worldX / worldSize(zoom, tileSize) * 360.0 - 180.0

    fun latAt(worldY: Double, zoom: Double, tileSize: Int = DEFAULT_TILE_SIZE): Double {
        val n = PI * (1.0 - 2.0 * worldY / worldSize(zoom, tileSize))
        return Math.toDegrees(kotlin.math.atan(sinh(n)))
    }

    // The integer forms, for tile arithmetic. They delegate rather than repeat the
    // maths: two copies of a projection drift apart, and the drift shows up as a
    // track drawn beside its own road.
    fun worldSize(zoom: Int, tileSize: Int = DEFAULT_TILE_SIZE): Double =
        worldSize(zoom.toDouble(), tileSize)

    fun worldX(lon: Double, zoom: Int, tileSize: Int = DEFAULT_TILE_SIZE): Double =
        worldX(lon, zoom.toDouble(), tileSize)

    fun worldY(lat: Double, zoom: Int, tileSize: Int = DEFAULT_TILE_SIZE): Double =
        worldY(lat, zoom.toDouble(), tileSize)

    fun lonAt(worldX: Double, zoom: Int, tileSize: Int = DEFAULT_TILE_SIZE): Double =
        lonAt(worldX, zoom.toDouble(), tileSize)

    fun latAt(worldY: Double, zoom: Int, tileSize: Int = DEFAULT_TILE_SIZE): Double =
        latAt(worldY, zoom.toDouble(), tileSize)

    /**
     * Fits [bounds] into a canvas.
     *
     * Picks the deepest zoom whose tiles still cover the canvas without being stretched,
     * then scales the rest of the way. Upscaling is capped at two, which is exactly what
     * the next zoom down would have cost anyway.
     */
    fun fit(
        bounds: LatLonBounds,
        widthPx: Float,
        heightPx: Float,
        paddingPx: Float,
        minZoom: Int,
        maxZoom: Int,
        tileSize: Int = DEFAULT_TILE_SIZE,
    ): MapViewport {
        val usableW = (widthPx - 2 * paddingPx).coerceAtLeast(1f).toDouble()
        val usableH = (heightPx - 2 * paddingPx).coerceAtLeast(1f).toDouble()

        fun fitAt(zoom: Int): Double {
            val spanX = abs(
                worldX(bounds.maxLon, zoom, tileSize) - worldX(bounds.minLon, zoom, tileSize)
            ).coerceAtLeast(1e-6)
            // A track at one spot has no span; a metre of it is enough to divide by.
            val spanY = abs(
                worldY(bounds.minLat, zoom, tileSize) - worldY(bounds.maxLat, zoom, tileSize)
            ).coerceAtLeast(1e-6)
            return min(usableW / spanX, usableH / spanY)
        }

        val zoom = (maxZoom downTo minZoom).firstOrNull { fitAt(it) >= 1.0 } ?: minZoom
        val scale = fitAt(zoom).coerceAtMost(2.0)

        val centreX = (worldX(bounds.minLon, zoom, tileSize) + worldX(bounds.maxLon, zoom, tileSize)) / 2
        val centreY = (worldY(bounds.minLat, zoom, tileSize) + worldY(bounds.maxLat, zoom, tileSize)) / 2

        return MapViewport(
            zoom = zoom,
            scale = scale,
            originX = centreX - widthPx / 2.0 / scale,
            originY = centreY - heightPx / 2.0 / scale,
            tileSize = tileSize,
        )
    }

    /** The tiles a viewport needs to cover a canvas of this size. */
    fun tilesFor(viewport: MapViewport, widthPx: Float, heightPx: Float): TileRange {
        val last = (1 shl viewport.zoom) - 1
        val minX = floor(viewport.originX / viewport.tileSize).toInt().coerceIn(0, last)
        val minY = floor(viewport.originY / viewport.tileSize).toInt().coerceIn(0, last)
        val maxX = floor((viewport.originX + widthPx / viewport.scale) / viewport.tileSize)
            .toInt().coerceIn(0, last)
        val maxY = floor((viewport.originY + heightPx / viewport.scale) / viewport.tileSize)
            .toInt().coerceIn(0, last)
        return TileRange(viewport.zoom, minX, maxX, minY, maxY)
    }

    /**
     * The ground a block of tiles covers, inclusive at both ends.
     *
     * A tile's own x and y are its top-left corner, so the far edge is one tile further
     * on — off by that one, and a map file reports itself a tile short in each
     * direction.
     */
    fun boundsOf(range: TileRange, tileSize: Int = DEFAULT_TILE_SIZE): LatLonBounds =
        LatLonBounds(
            minLat = latAt((range.maxY + 1).toDouble() * tileSize, range.zoom, tileSize),
            maxLat = latAt(range.minY.toDouble() * tileSize, range.zoom, tileSize),
            minLon = lonAt(range.minX.toDouble() * tileSize, range.zoom, tileSize),
            maxLon = lonAt((range.maxX + 1).toDouble() * tileSize, range.zoom, tileSize),
        )

    /**
     * MBTiles numbers rows from the bottom (TMS); everything else numbers them from the
     * top. One subtraction, and getting it wrong flips the map upside down.
     */
    fun toTmsRow(y: Int, zoom: Int): Int = (1 shl zoom) - 1 - y
}

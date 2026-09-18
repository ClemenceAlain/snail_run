package io.snailrun.domain.geo

import io.snailrun.domain.model.LatLon
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMercatorTest {

    private val paris = LatLon(48.8566, 2.3522)

    @Test
    fun `the origin of the world is the top-left of tile zero`() {
        assertEquals(0.0, WebMercator.worldX(-180.0, 0), 1e-9)
        assertEquals(0.0, WebMercator.worldY(WebMercator.MAX_LATITUDE, 0), 1e-6)
        assertEquals(256.0, WebMercator.worldX(180.0, 0), 1e-9)
        assertEquals(256.0, WebMercator.worldY(-WebMercator.MAX_LATITUDE, 0), 1e-6)
    }

    @Test
    fun `the equator and the prime meridian meet in the middle`() {
        assertEquals(128.0, WebMercator.worldX(0.0, 0), 1e-9)
        assertEquals(128.0, WebMercator.worldY(0.0, 0), 1e-9)
    }

    @Test
    fun `projecting and unprojecting returns the same place`() {
        val zoom = 14
        val x = WebMercator.worldX(paris.lon, zoom)
        val y = WebMercator.worldY(paris.lat, zoom)

        assertEquals(paris.lon, WebMercator.lonAt(x, zoom), 1e-9)
        assertEquals(paris.lat, WebMercator.latAt(y, zoom), 1e-9)
    }

    @Test
    fun `Paris lands in the tile the world agrees it is in`() {
        // The slippy-map tile holding central Paris, computed from the standard formula
        // rather than recalled: z14/8299/5636.
        val zoom = 14
        val tileX = (WebMercator.worldX(paris.lon, zoom) / 256).toInt()
        val tileY = (WebMercator.worldY(paris.lat, zoom) / 256).toInt()

        assertEquals(8299, tileX)
        assertEquals(5636, tileY)
    }

    @Test
    fun `a track fills the canvas it is fitted into`() {
        val bounds = LatLonBounds(minLat = 48.85, maxLat = 48.87, minLon = 2.34, maxLon = 2.37)

        val viewport = WebMercator.fit(bounds, 1000f, 1000f, 20f, minZoom = 0, maxZoom = 18)

        val (x1, y1) = viewport.project(LatLon(bounds.maxLat, bounds.minLon))
        val (x2, y2) = viewport.project(LatLon(bounds.minLat, bounds.maxLon))
        // Inside the canvas, and filling at least one axis of it up to the padding.
        listOf(x1, y1, x2, y2).forEach { assertTrue("$it is off the canvas", it in -1f..1001f) }
        val widest = maxOf(abs(x2 - x1), abs(y2 - y1))
        assertTrue("only filled $widest px of 960", widest > 900f)
    }

    @Test
    fun `a deeper zoom is chosen for a smaller track`() {
        val big = LatLonBounds(48.5, 49.2, 2.0, 2.9)
        val small = LatLonBounds(48.8566, 48.8586, 2.3522, 2.3542)

        val zoomBig = WebMercator.fit(big, 1000f, 1000f, 0f, 0, 18).zoom
        val zoomSmall = WebMercator.fit(small, 1000f, 1000f, 0f, 0, 18).zoom

        assertTrue("$zoomBig should be shallower than $zoomSmall", zoomBig < zoomSmall)
    }

    @Test
    fun `the zoom never exceeds what the tile file holds`() {
        val small = LatLonBounds(48.8566, 48.8567, 2.3522, 2.3523)

        val viewport = WebMercator.fit(small, 1000f, 1000f, 0f, minZoom = 10, maxZoom = 13)

        assertEquals(13, viewport.zoom)
        assertTrue("scale ${viewport.scale} would blur past two", viewport.scale <= 2.0)
    }

    @Test
    fun `a track at a single point still produces a usable viewport`() {
        val pin = LatLonBounds(paris.lat, paris.lat, paris.lon, paris.lon)

        val viewport = WebMercator.fit(pin, 600f, 400f, 12f, 0, 16)

        val (x, y) = viewport.project(paris)
        assertEquals(300f, x, 1f)
        assertEquals(200f, y, 1f)
    }

    @Test
    fun `the tile range covers the canvas and no more`() {
        val bounds = LatLonBounds(48.85, 48.87, 2.34, 2.37)
        val viewport = WebMercator.fit(bounds, 800f, 500f, 0f, 0, 18)

        val range = WebMercator.tilesFor(viewport, 800f, 500f)

        assertEquals(viewport.zoom, range.zoom)
        assertTrue(range.count in 1..12)
        // The first tile's top-left must be at or above the canvas origin.
        val (left, top) = viewport.tileTopLeft(range.minX, range.minY)
        assertTrue(left <= 0.01f && top <= 0.01f)
        val (right, bottom) = viewport.tileTopLeft(range.maxX, range.maxY)
        assertTrue(right + viewport.drawnTileSize >= 799f)
        assertTrue(bottom + viewport.drawnTileSize >= 499f)
    }

    @Test
    fun `the TMS row flip is its own inverse`() {
        assertEquals(5637, WebMercator.toTmsRow(WebMercator.toTmsRow(5637, 14), 14))
        assertEquals(0, WebMercator.toTmsRow((1 shl 3) - 1, 3))
    }
}

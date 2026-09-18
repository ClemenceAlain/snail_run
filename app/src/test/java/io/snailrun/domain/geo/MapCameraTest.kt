package io.snailrun.domain.geo

import io.snailrun.domain.model.LatLon
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera is what a finger moves, so what is asserted here is what a finger expects:
 * that the ground under it does not slide, that letting go leaves the map where it was
 * put, and that the tiles and the trace agree on where a place is.
 */
class MapCameraTest {

    private val width = 1080f
    private val height = 1920f
    private val range = 1.0..22.0

    private val loop = LatLonBounds(
        minLat = 48.8552,
        maxLat = 48.8580,
        minLon = 2.3500,
        maxLon = 2.3544,
    )

    private fun fitted() = MapCamera.fitting(loop, width, height, 32f, range)

    @Test
    fun `a fitted camera puts the bounds inside the canvas`() {
        val camera = fitted()
        val viewport = camera.viewport(width, height, 0, 22)

        val corners = listOf(
            LatLon(loop.minLat, loop.minLon),
            LatLon(loop.maxLat, loop.maxLon),
        ).map { viewport.project(it) }

        corners.forEach { (x, y) ->
            assertTrue("x=$x off canvas", x in 0f..width)
            assertTrue("y=$y off canvas", y in 0f..height)
        }
    }

    @Test
    fun `a fitted camera is centred on the bounds`() {
        val viewport = fitted().viewport(width, height, 0, 22)
        val (x, y) = viewport.project(
            LatLon(
                lat = WebMercator.latAt(
                    (WebMercator.worldY(loop.minLat, 0.0) + WebMercator.worldY(loop.maxLat, 0.0)) / 2,
                    0.0,
                ),
                lon = (loop.minLon + loop.maxLon) / 2,
            )
        )
        assertEquals(width / 2, x, 0.5f)
        assertEquals(height / 2, y, 0.5f)
    }

    @Test
    fun `the fitted zoom fills the tighter of the two axes`() {
        // The loop is wider than it is tall relative to the canvas, which is portrait,
        // so height is not what limits it: dropping the width would let it zoom in.
        val camera = fitted()
        val narrower = MapCamera.fitting(loop, width / 2, height, 32f, range)
        assertTrue(narrower.zoom < camera.zoom)
    }

    @Test
    fun `dragging back and forth leaves the map where it started`() {
        val camera = fitted()
        val returned = camera.panned(180f, -240f).panned(-180f, 240f)

        assertEquals(camera.centerLat, returned.centerLat, 1e-9)
        assertEquals(camera.centerLon, returned.centerLon, 1e-9)
        assertEquals(camera.zoom, returned.zoom, 0.0)
    }

    @Test
    fun `dragging right moves the map right`() {
        val camera = fitted()
        val viewport = camera.viewport(width, height, 0, 22)
        val place = LatLon(loop.minLat, loop.minLon)
        val before = viewport.project(place)

        val dragged = camera.panned(100f, 0f).viewport(width, height, 0, 22)
        val after = dragged.project(place)

        assertEquals(before.first + 100f, after.first, 0.5f)
        assertEquals(before.second, after.second, 0.5f)
    }

    @Test
    fun `a drag moves the ground exactly as far as the finger`() {
        val camera = fitted()
        val place = camera.unproject(300f, 700f, width, height)
        val moved = camera.panned(-64f, 128f)
        val landed = moved.viewport(width, height, 0, 22).project(place)

        assertEquals(300f - 64f, landed.first, 0.5f)
        assertEquals(700f + 128f, landed.second, 0.5f)
    }

    @Test
    fun `zooming keeps whatever is under the fingers under them`() {
        val camera = fitted()
        val focusX = 240f
        val focusY = 1_500f
        val anchor = camera.unproject(focusX, focusY, width, height)

        val zoomed = camera.zoomedTo(camera.zoom + 2.3, focusX, focusY, width, height, range)
        val (x, y) = zoomed.viewport(width, height, 0, 22).project(anchor)

        assertEquals(focusX, x, 0.5f)
        assertEquals(focusY, y, 0.5f)
    }

    @Test
    fun `zooming about the centre does not move the centre`() {
        val camera = fitted()
        val zoomed = camera.zoomedTo(camera.zoom - 1.0, width / 2, height / 2, width, height, range)

        assertEquals(camera.centerLat, zoomed.centerLat, 1e-6)
        assertEquals(camera.centerLon, zoomed.centerLon, 1e-6)
    }

    @Test
    fun `zoom stops at the ends of its range`() {
        val camera = fitted()
        assertEquals(22.0, camera.zoomedTo(99.0, 0f, 0f, width, height, range).zoom, 1e-9)
        assertEquals(1.0, camera.zoomedTo(-99.0, 0f, 0f, width, height, range).zoom, 1e-9)
    }

    @Test
    fun `the fitted zoom is itself clamped to the range`() {
        // A run around one lamp post has no span worth speaking of, and the fit would
        // otherwise solve for a zoom no tile set has ever held.
        val pinpoint = LatLonBounds(48.8566, 48.8566, 2.3522, 2.3522)
        val camera = MapCamera.fitting(pinpoint, width, height, 32f, 1.0..19.0)
        assertEquals(19.0, camera.zoom, 1e-9)
    }

    @Test
    fun `the tile grid and the camera agree on where a place is`() {
        // The tiles are drawn through the viewport and the trace through the camera's
        // own zoom. If these two ever disagreed, every run would be drawn beside its
        // own road — which is the whole reason the app projects in Mercator at all.
        val camera = fitted().panned(37f, -19f).zoomedTo(16.4, 100f, 200f, width, height, range)
        val viewport = camera.viewport(width, height, 13, 17)
        val place = LatLon(48.8571, 2.3519)

        val (viaViewport, viaViewportY) = viewport.project(place)

        val factor = Math.pow(2.0, camera.zoom - 22.0)
        val originX = WebMercator.worldX(camera.centerLon, camera.zoom) - width / 2.0
        val originY = WebMercator.worldY(camera.centerLat, camera.zoom) - height / 2.0
        val viaCamera = (WebMercator.worldX(place.lon, 22.0) * factor - originX).toFloat()
        val viaCameraY = (WebMercator.worldY(place.lat, 22.0) * factor - originY).toFloat()

        assertEquals(viaCamera, viaViewport, 0.01f)
        assertEquals(viaCameraY, viaViewportY, 0.01f)
    }

    @Test
    fun `the viewport uses the deepest tiles it has and scales past them`() {
        val camera = MapCamera(48.8566, 2.3522, 19.5)
        val viewport = camera.viewport(width, height, 13, 17)

        assertEquals(17, viewport.zoom)
        // Two and a half levels past the deepest tiles is a five-fold enlargement.
        assertEquals(Math.pow(2.0, 2.5), viewport.scale, 1e-9)
    }

    @Test
    fun `unprojecting the middle of the canvas gives the camera's own centre`() {
        val camera = fitted()
        val place = camera.unproject(width / 2, height / 2, width, height)

        assertTrue(abs(place.lat - camera.centerLat) < 1e-9)
        assertTrue(abs(place.lon - camera.centerLon) < 1e-9)
    }
}

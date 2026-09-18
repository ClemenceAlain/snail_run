package io.snailrun.domain.geo

import io.snailrun.domain.model.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionTest {

    private val square = listOf(
        LatLon(48.8560, 2.3500),
        LatLon(48.8560, 2.3520),
        LatLon(48.8570, 2.3520),
        LatLon(48.8570, 2.3500),
    )

    @Test
    fun `every projected point sits inside the canvas`() {
        val out = Projection.fit(square, width = 200f, height = 100f, padding = 8f)
        assertTrue(out.all { it.x >= 7.9f && it.x <= 192.1f })
        assertTrue(out.all { it.y >= 7.9f && it.y <= 92.1f })
    }

    @Test
    fun `scale is uniform so the shape is not stretched to the box`() {
        // The square is wider than tall in metres, so fitting a 200x100 canvas must
        // leave vertical slack rather than stretching latitude.
        val out = Projection.fit(square, width = 200f, height = 100f, padding = 0f)
        val width = out.maxOf { it.x } - out.minOf { it.x }
        val height = out.maxOf { it.y } - out.minOf { it.y }
        val metresWide = GeoDistance.between(48.8560, 2.3500, 48.8560, 2.3520)
        val metresTall = GeoDistance.between(48.8560, 2.3500, 48.8570, 2.3500)
        assertEquals(metresWide / metresTall, (width / height).toDouble(), 0.02)
    }

    @Test
    fun `north is up`() {
        val out = Projection.fit(
            listOf(LatLon(48.8560, 2.35), LatLon(48.8570, 2.35)),
            width = 100f, height = 100f, padding = 0f,
        )
        assertTrue("the more northern point must have the smaller y", out[1].y < out[0].y)
    }

    @Test
    fun `a single point does not divide by zero`() {
        val out = Projection.fit(listOf(LatLon(48.8566, 2.3522)), 100f, 100f, 8f)
        assertEquals(1, out.size)
        assertTrue(out[0].x.isFinite() && out[0].y.isFinite())
    }

    @Test
    fun `an empty track projects to nothing`() {
        assertTrue(Projection.fit(emptyList(), 100f, 100f, 8f).isEmpty())
    }

    @Test
    fun `a subset projected through the whole track's frame lands on it`() {
        // What a graph selection does: a stretch of the run drawn over the run. Fitting
        // the stretch on its own would scale it to fill the canvas, and the highlight
        // would sit somewhere the trace beneath it never goes.
        val track = listOf(
            LatLon(48.8560, 2.3500),
            LatLon(48.8570, 2.3520),
            LatLon(48.8580, 2.3540),
            LatLon(48.8590, 2.3560),
        )
        val whole = Projection.fit(track, 400f, 300f, 10f)
        val project = Projection.fitting(track, 400f, 300f, 10f)

        val stretch = track.subList(1, 3)
        val projected = stretch.map(project)

        assertEquals(whole[1].x, projected[0].x, 1e-4f)
        assertEquals(whole[1].y, projected[0].y, 1e-4f)
        assertEquals(whole[2].x, projected[1].x, 1e-4f)
        assertEquals(whole[2].y, projected[1].y, 1e-4f)
    }

    @Test
    fun `fit and fitting are the same transform`() {
        val track = listOf(
            LatLon(48.8560, 2.3500),
            LatLon(48.8600, 2.3600),
            LatLon(48.8520, 2.3560),
        )
        val project = Projection.fitting(track, 200f, 200f, 8f)

        Projection.fit(track, 200f, 200f, 8f).forEachIndexed { i, expected ->
            val actual = project(track[i])
            assertEquals(expected.x, actual.x, 1e-4f)
            assertEquals(expected.y, actual.y, 1e-4f)
        }
    }
}

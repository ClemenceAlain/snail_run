package io.snailrun.data.basemap

import io.snailrun.domain.demo.DemoRoute
import io.snailrun.domain.demo.DemoRunProfile
import io.snailrun.domain.geo.LatLonBounds
import io.snailrun.domain.geo.WebMercator
import io.snailrun.domain.model.LatLon
import io.snailrun.ui.components.BasemapLayer
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The map in the APK, checked against the run it exists for.
 *
 * It is a generated file that is committed rather than built, so nothing else would
 * notice if `tools/make-demo-map.py` were changed and not re-run, or if the asset were
 * dropped from the build. What it costs to check is one query; what it buys is that the
 * demo — the first thing anyone tries — cannot quietly lose its map.
 */
@RunWith(RobolectricTestRunner::class)
class DemoBasemapTest {

    private lateinit var file: File
    private var basemap: MbtilesBasemap? = null

    @Before
    fun setUp() {
        val assets = RuntimeEnvironment.getApplication().assets
        file = File.createTempFile("demo", ".mbtiles")
        assets.open("demo.mbtiles").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        basemap = MbtilesBasemap.open(file).getOrThrow()
    }

    @After
    fun tearDown() {
        basemap?.close()
        file.delete()
    }

    private fun info() = requireNotNull(basemap).info

    /** Where the demo run actually goes, derived rather than restated. */
    private fun demoRunBounds(): LatLonBounds {
        val profile = DemoRunProfile()
        val fixes = DemoRoute.fixes(profile, startEpochMs = 0L)
            .take(profile.loopPerimeterM.toInt())
            .map { LatLon(it.lat, it.lon) }
            .toList()
        return requireNotNull(LatLonBounds.of(fixes))
    }

    @Test
    fun `the bundled map is a readable MBTiles file`() {
        assertEquals("png", info().format)
        assertTrue("no tiles in the bundled map", info().tileCount > 0)
    }

    @Test
    fun `it covers the ground the demo run is run on`() {
        val coverage = requireNotNull(info().coverage) { "the bundled map reports no coverage" }
        val run = demoRunBounds()

        assertTrue("map starts north of the run", coverage.minLat < run.minLat)
        assertTrue("map ends south of the run", coverage.maxLat > run.maxLat)
        assertTrue("map starts east of the run", coverage.minLon < run.minLon)
        assertTrue("map ends west of the run", coverage.maxLon > run.maxLon)
    }

    @Test
    fun `it has tiles at the zoom a phone frames the loop at`() {
        // The loop is about 320 m across and a phone is about 1000 px wide, which lands
        // near zoom 18. Shipping less means the first view anyone sees is an upscaled
        // one, so this is the level worth asserting on rather than the range as a whole.
        assertTrue("deepest zoom is ${info().maxZoom}", info().maxZoom >= 18)
        assertTrue("shallowest zoom is ${info().minZoom}", info().minZoom <= 14)
    }

    @Test
    fun `the tile under the start of the run is really there`() {
        val profile = DemoRunProfile()
        val zoom = info().maxZoom
        val x = (WebMercator.worldX(profile.startLon, zoom) / 256).toInt()
        val y = (WebMercator.worldY(profile.startLat, zoom) / 256).toInt()

        assertNotNull(requireNotNull(basemap).tile(zoom, x, y))
    }

    @Test
    fun `it is offered for the demo run and withheld everywhere else`() {
        val layer = BasemapLayer(
            minZoom = info().minZoom,
            maxZoom = info().maxZoom,
            tile = { _, _, _ -> null },
            coverage = info().coverage,
        )

        assertTrue(layer.covers(demoRunBounds()))
        // A real run in Oslo gets the trace it always got, not a blank Mercator grid.
        assertFalse(layer.covers(LatLonBounds(59.91, 59.92, 10.73, 10.75)))
        assertFalse(layer.covers(null))
    }

    @Test
    fun `a map the user chose applies wherever they ran`() {
        val theirs = BasemapLayer(
            minZoom = 12,
            maxZoom = 16,
            tile = { _, _, _ -> null },
            coverage = null,
        )

        assertTrue(theirs.covers(LatLonBounds(59.91, 59.92, 10.73, 10.75)))
        assertTrue(theirs.covers(null))
    }
}

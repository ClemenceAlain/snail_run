package io.snailrun.domain.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoDistanceTest {

    @Test
    fun `identical points are zero metres apart`() {
        assertEquals(0.0, GeoDistance.between(48.8566, 2.3522, 48.8566, 2.3522), 1e-9)
    }

    @Test
    fun `one hundred metres north matches the metres-per-degree series`() {
        // 0.001 degrees of latitude near Paris is about 111.2 m.
        val d = GeoDistance.between(48.8566, 2.3522, 48.8576, 2.3522)
        assertEquals(111.2, d, 0.5)
    }

    @Test
    fun `longitude shrinks with latitude`() {
        val atEquator = GeoDistance.between(0.0, 0.0, 0.0, 0.001)
        val nearParis = GeoDistance.between(48.8566, 2.3522, 48.8566, 2.3532)
        assertTrue("longitude degrees must be shorter at 49N than at the equator", nearParis < atEquator)
        // cos(48.8566 deg) = 0.6583
        assertEquals(atEquator * 0.6583, nearParis, 0.5)
    }

    @Test
    fun `a short leg matches the WGS84 geodesic to within a tenth of a percent`() {
        // Reference from Vincenty's inverse solution on the WGS84 ellipsoid, which is
        // what Location.distanceTo implements: 374.187 m.
        val d = GeoDistance.between(48.858370, 2.294481, 48.861100, 2.291500)
        assertEquals(374.187, d, 374.187 * 0.001)
    }

    @Test
    fun `a kilometre-scale leg matches the geodesic, where haversine would drift`() {
        // Vincenty: 1296.953 m. Spherical haversine gives 1294.398 m, 0.20 % short --
        // about 20 m over a 10 km run, which a runner would notice.
        val d = GeoDistance.between(45.7640, 4.8357, 45.7700, 4.8500)
        assertEquals(1296.953, d, 0.05)
    }

    @Test
    fun `distance is symmetric`() {
        val forward = GeoDistance.between(45.7640, 4.8357, 45.7700, 4.8500)
        val backward = GeoDistance.between(45.7700, 4.8500, 45.7640, 4.8357)
        assertEquals(forward, backward, 1e-9)
    }
}

package io.snailrun.domain.geo

import io.snailrun.domain.model.LatLon
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Distance between two nearby fixes, in metres.
 *
 * Local equirectangular projection rather than spherical haversine: haversine on a
 * 6371 km sphere carries a latitude-dependent bias of up to ~0.5 %, which is 50 m over
 * a 10 km run and visible to a runner. The metres-per-degree series below is the
 * standard WGS84 expansion and is exact to well under 0.1 m for the sub-100 m
 * baselines between consecutive fixes.
 *
 * `Location.distanceTo` would also be correct, but it needs an Android object, which
 * would put the most important arithmetic in the app beyond reach of a JVM test.
 */
object GeoDistance {

    fun between(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi = Math.toRadians((lat1 + lat2) / 2.0)
        val dLat = (lat2 - lat1) * metresPerDegreeLat(phi)
        val dLon = (lon2 - lon1) * metresPerDegreeLon(phi)
        return hypot(dLat, dLon)
    }

    fun between(a: LatLon, b: LatLon): Double = between(a.lat, a.lon, b.lat, b.lon)

    fun metresPerDegreeLat(phiRad: Double): Double =
        111132.92 - 559.82 * cos(2 * phiRad) + 1.175 * cos(4 * phiRad) - 0.0023 * cos(6 * phiRad)

    fun metresPerDegreeLon(phiRad: Double): Double =
        111412.84 * cos(phiRad) - 93.5 * cos(3 * phiRad) + 0.118 * cos(5 * phiRad)
}

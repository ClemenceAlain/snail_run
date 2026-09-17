package io.snailrun.domain.geo

import io.snailrun.domain.model.LatLon
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Ramer–Douglas–Peucker, run in projected metres so the tolerance is a real distance
 * rather than a degree value that would mean different things at different latitudes.
 *
 * Used to shrink a track before drawing: a full 1 Hz hour is 3 600 points, but a
 * detail-screen trace needs a few hundred and a list thumbnail a few dozen.
 */
object Simplify {

    fun douglasPeucker(points: List<LatLon>, toleranceMeters: Double): List<LatLon> {
        if (points.size <= 2 || toleranceMeters <= 0.0) return points
        val phi = Math.toRadians(points.sumOf { it.lat } / points.size)
        val mLat = GeoDistance.metresPerDegreeLat(phi)
        val mLon = GeoDistance.metresPerDegreeLon(phi)
        val xs = DoubleArray(points.size) { points[it].lon * mLon }
        val ys = DoubleArray(points.size) { points[it].lat * mLat }

        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        simplifyRange(xs, ys, 0, points.lastIndex, toleranceMeters, keep)
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** Simplify down to at most [maxPoints] by doubling the tolerance until it fits. */
    fun toAtMost(points: List<LatLon>, maxPoints: Int, startToleranceMeters: Double = 1.0): List<LatLon> {
        if (points.size <= maxPoints) return points
        var tolerance = startToleranceMeters
        var result = douglasPeucker(points, tolerance)
        while (result.size > maxPoints && tolerance < 100_000.0) {
            tolerance *= 2
            result = douglasPeucker(points, tolerance)
        }
        return result
    }

    private fun simplifyRange(
        xs: DoubleArray,
        ys: DoubleArray,
        first: Int,
        last: Int,
        tolerance: Double,
        keep: BooleanArray,
    ) {
        if (last <= first + 1) return
        var maxDistance = -1.0
        var maxIndex = first
        for (i in (first + 1) until last) {
            val d = perpendicularDistance(xs[i], ys[i], xs[first], ys[first], xs[last], ys[last])
            if (d > maxDistance) {
                maxDistance = d
                maxIndex = i
            }
        }
        if (maxDistance > tolerance) {
            keep[maxIndex] = true
            simplifyRange(xs, ys, first, maxIndex, tolerance, keep)
            simplifyRange(xs, ys, maxIndex, last, tolerance, keep)
        }
    }

    private fun perpendicularDistance(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double,
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        if (dx == 0.0 && dy == 0.0) return hypot(px - ax, py - ay)
        val area = abs(dy * px - dx * py + bx * ay - by * ax)
        return area / hypot(dx, dy)
    }

}

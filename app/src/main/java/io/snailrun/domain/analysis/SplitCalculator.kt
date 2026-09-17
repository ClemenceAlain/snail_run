package io.snailrun.domain.analysis

import io.snailrun.domain.model.Split
import io.snailrun.domain.model.TrackPoint

/**
 * Per-kilometre splits from a finished track.
 *
 * Each boundary is placed by interpolating between the two points that straddle it,
 * rather than by snapping to the nearest fix. At 1 Hz a runner covers about 3 m per
 * fix, so snapping would smear each split by up to a second, and the errors accumulate
 * over a long run.
 */
object SplitCalculator {

    fun compute(points: List<TrackPoint>, splitLengthM: Double = 1000.0): List<Split> {
        if (points.size < 2) return emptyList()

        val splits = mutableListOf<Split>()
        var boundary = splitLengthM
        var previousBoundaryTimeMs = points.first().timestampMs.toDouble()
        var previousBoundaryElevation = points.first().elevationM
        var index = 0

        for (i in 1 until points.size) {
            val before = points[i - 1]
            val after = points[i]
            while (after.cumulativeDistanceM >= boundary) {
                val span = after.cumulativeDistanceM - before.cumulativeDistanceM
                val fraction = if (span <= 0.0) 0.0 else (boundary - before.cumulativeDistanceM) / span
                val boundaryTimeMs =
                    before.timestampMs + fraction * (after.timestampMs - before.timestampMs)
                val boundaryElevation = interpolate(before.elevationM, after.elevationM, fraction)

                splits += Split(
                    index = index,
                    distanceMeters = splitLengthM,
                    durationMs = (boundaryTimeMs - previousBoundaryTimeMs).toLong(),
                    elevationGainM = gainBetween(previousBoundaryElevation, boundaryElevation),
                    isPartial = false,
                )
                index++
                previousBoundaryTimeMs = boundaryTimeMs
                previousBoundaryElevation = boundaryElevation
                boundary += splitLengthM
            }
        }

        val last = points.last()
        val tailDistance = last.cumulativeDistanceM - (boundary - splitLengthM)
        if (tailDistance > 1.0) {
            splits += Split(
                index = index,
                distanceMeters = tailDistance,
                durationMs = (last.timestampMs - previousBoundaryTimeMs).toLong(),
                elevationGainM = gainBetween(previousBoundaryElevation, last.elevationM),
                isPartial = true,
            )
        }
        return splits
    }

    private fun interpolate(a: Double?, b: Double?, fraction: Double): Double? =
        if (a == null || b == null) b ?: a else a + fraction * (b - a)

    private fun gainBetween(from: Double?, to: Double?): Double {
        if (from == null || to == null) return 0.0
        return (to - from).coerceAtLeast(0.0)
    }
}

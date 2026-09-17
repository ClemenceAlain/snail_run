package io.snailrun.domain.analysis

import io.snailrun.domain.model.BestEffort
import io.snailrun.domain.model.TrackPoint

/**
 * Fastest continuous stretch covering each target distance — the raw material for
 * personal records.
 *
 * A sliding window over `cumulativeDistanceM`, which is monotonic, so the whole thing
 * is one linear pass per distance rather than a quadratic search. The window's ends are
 * interpolated for the same reason splits are: a run is sampled every few metres, and
 * rounding both ends of a 5 k window costs seconds.
 */
object BestEffortFinder {

    val StandardDistances = listOf(1_000, 5_000, 10_000, 21_097, 42_195)

    fun findAll(
        points: List<TrackPoint>,
        distances: List<Int> = StandardDistances,
    ): List<BestEffort> = distances.mapNotNull { find(points, it) }

    fun find(points: List<TrackPoint>, targetMeters: Int): BestEffort? {
        if (points.size < 2) return null
        val total = points.last().cumulativeDistanceM - points.first().cumulativeDistanceM
        if (total < targetMeters) return null

        var best: BestEffort? = null
        var start = 0

        for (end in 1 until points.size) {
            // Advance the window's tail while the span still covers the target, so the
            // window is always the shortest one ending here that is long enough.
            while (start < end - 1 &&
                points[end].cumulativeDistanceM - points[start + 1].cumulativeDistanceM >= targetMeters
            ) {
                start++
            }
            val span = points[end].cumulativeDistanceM - points[start].cumulativeDistanceM
            if (span < targetMeters) continue

            // The window starts a little before the target distance: interpolate the
            // exact crossing point between `start` and `start + 1`.
            val overshoot = span - targetMeters
            val leg = points[start + 1].cumulativeDistanceM - points[start].cumulativeDistanceM
            val fraction = if (leg <= 0.0) 0.0 else (overshoot / leg).coerceIn(0.0, 1.0)
            val startTimeMs = points[start].timestampMs +
                fraction * (points[start + 1].timestampMs - points[start].timestampMs)
            val durationMs = (points[end].timestampMs - startTimeMs).toLong()

            if (durationMs > 0 && (best == null || durationMs < best.durationMs)) {
                best = BestEffort(
                    distanceMeters = targetMeters,
                    durationMs = durationMs,
                    startSeq = points[start].seq,
                    endSeq = points[end].seq,
                    startOffsetMs = (startTimeMs - points.first().timestampMs).toLong(),
                )
            }
        }
        return best
    }
}

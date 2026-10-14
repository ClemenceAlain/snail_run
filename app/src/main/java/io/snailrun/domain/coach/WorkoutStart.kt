package io.snailrun.domain.coach

import io.snailrun.domain.metrics.TrackGaps
import io.snailrun.domain.model.TrackPoint

/**
 * Where in a run its session began.
 *
 * A session is usually loaded before the start and begins at zero. One loaded mid-run
 * begins later, and everything that counts the runner through it — the live scheduler,
 * the replay after a crash, the review on the run's own screen — measures from that
 * point. They all read it from here so they cannot disagree about where it was.
 */
object WorkoutStart {

    /**
     * Active duration at each point of a stored track, by the same rule the accumulator
     * uses: inside a segment, and across a hole only where the runner ran through it.
     */
    fun timeline(points: List<TrackPoint>): LongArray {
        val out = LongArray(points.size)
        var activeMs = 0L
        for (i in 1 until points.size) {
            val before = points[i - 1]
            val point = points[i]
            if (point.segment == before.segment) {
                activeMs += TrackGaps.countable(
                    gapMs = point.timestampMs - before.timestampMs,
                    straightLineM = point.cumulativeDistanceM - before.cumulativeDistanceM,
                )
            }
            out[i] = activeMs
        }
        return out
    }

    /** The distance at the first point at or past [activeMs], or the last one if none is. */
    fun metersAt(points: List<TrackPoint>, activeMs: Long): Double {
        if (points.isEmpty()) return 0.0
        val times = timeline(points)
        val index = times.indexOfFirst { it >= activeMs }
        return points[if (index < 0) points.lastIndex else index].cumulativeDistanceM
    }

    /**
     * Where a session loaded now should be counted from.
     *
     * The running already done goes towards the first segment when that segment is a
     * warm-up or an easy run: ten minutes jogged before loading a session that opens on
     * fifteen minutes of warm-up leaves five. Anything else starts now — running easy
     * is not a rep.
     */
    fun creditedStart(
        segments: List<WorkoutSegment>,
        points: List<TrackPoint>,
        nowActiveMs: Long,
        nowMeters: Double,
    ): Long {
        val first = segments.firstOrNull() ?: return nowActiveMs
        if (first.kind != SegmentKind.WarmUp) return nowActiveMs
        first.targetMs?.let { return (nowActiveMs - it).coerceAtLeast(0) }
        val target = first.targetM ?: return 0
        val from = nowMeters - target
        if (from <= 0.0 || points.isEmpty()) return 0
        // The last fix at or before the boundary, so the warm-up is never left a metre
        // short and announced as still going.
        val times = timeline(points)
        val index = points.indexOfLast { it.cumulativeDistanceM <= from }
        return if (index < 0) 0 else times[index]
    }
}

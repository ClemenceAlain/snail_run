package io.snailrun.domain.coach

import io.snailrun.domain.model.TrackPoint

/** How a segment's pace went against what it asked for, from the runner's side. */
enum class PaceVerdict { OnTarget, Better, Worse }

/** One segment as prescribed, beside what was actually run in it. */
data class SegmentResult(
    val segment: WorkoutSegment,
    val actualMs: Long,
    val actualMeters: Double,
    /** Where along the run it began, so its target can be drawn over the pace graph. */
    val startedAtMeters: Double = 0.0,
) {
    val actualPaceSecPerKm: Double?
        get() = if (actualMeters < 20.0 || actualMs <= 0) null
        else actualMs / 1000.0 / (actualMeters / 1000.0)

    /** Seconds per kilometre off target, or null where there was no target to miss. */
    val paceDeltaSecPerKm: Double?
        get() {
            val band = segment.paceSecPerKm ?: return null
            val actual = actualPaceSecPerKm ?: return null
            return when {
                actual < band.start -> actual - band.start
                actual > band.endInclusive -> actual - band.endInclusive
                else -> 0.0
            }
        }

    /** Within [ON_TARGET_SEC_PER_KM] of the band: a few seconds either way is not a miss. */
    val onTarget: Boolean
        get() = paceDeltaSecPerKm?.let { kotlin.math.abs(it) <= ON_TARGET_SEC_PER_KM } ?: true

    /** Whether a miss went the way that helps, or null where there was no target. Faster helps. */
    val verdict: PaceVerdict?
        get() {
            val delta = paceDeltaSecPerKm ?: return null
            if (onTarget) return PaceVerdict.OnTarget
            return if (delta < 0) PaceVerdict.Better else PaceVerdict.Worse
        }

    companion object {
        const val ON_TARGET_SEC_PER_KM = 5.0
    }
}

/**
 * What the runner actually did with the session they were given.
 *
 * Worked out from the stored track rather than recorded as it happened. That is the same
 * rule the rest of the app follows — the database keeps the positions and nothing derived
 * from them is trusted — and here it has a second payoff: improve the position filter and
 * every past session's splits are re-read through it, without a migration.
 *
 * The replay is the same [WorkoutScheduler] the run was counted through, fed the same
 * numbers in the same order, so the boundaries it finds are the boundaries the runner
 * heard.
 */
object WorkoutReview {

    /**
     * [startActiveMs] is where in the run the session was loaded. The track before it is
     * the runner's own running and is not judged against anything.
     *
     * [boundariesActiveMs] are where each step ended as it was cued, counted from the
     * session's start. Each step is measured on [points] between those times, so the
     * review times the stretch the runner was actually told to run. Runs recorded before
     * the boundaries were stored pass null, and the boundaries are found again by
     * replaying [cuePoints] — the track as it was counted live, which is the closest
     * thing left to what the runner heard.
     */
    fun of(
        segments: List<WorkoutSegment>,
        advancesActiveMs: List<Long>,
        points: List<TrackPoint>,
        startActiveMs: Long = 0,
        boundariesActiveMs: List<Long>? = null,
        cuePoints: List<TrackPoint> = points,
    ): List<SegmentResult> {
        if (segments.isEmpty() || points.size < 2) return emptyList()

        val timeline = WorkoutStart.timeline(points)
        if (timeline.last() < startActiveMs) return emptyList()
        val boundaries = (boundariesActiveMs ?: boundaries(segments, advancesActiveMs, cuePoints, startActiveMs))
            .take(segments.size)
        val lastMs = timeline.last() - startActiveMs

        val results = mutableListOf<SegmentResult>()
        var fromMs = 0L
        for (index in segments.indices) {
            val finished = index < boundaries.size
            val toMs = if (finished) boundaries[index] else lastMs
            val fromM = metersAt(points, timeline, startActiveMs + fromMs)
            val toM = metersAt(points, timeline, startActiveMs + toMs)
            results += SegmentResult(
                segment = segments[index],
                actualMs = (toMs - fromMs).coerceAtLeast(0),
                actualMeters = (toM - fromM).coerceAtLeast(0.0),
                startedAtMeters = fromM,
            )
            // Whatever the runner was in the middle of when they pressed stop is the last.
            if (!finished) break
            fromMs = toMs
        }
        return results
    }

    /**
     * Where each step ends when [points] are run back through the scheduler, counted from
     * the session's start. The same scheduler the run was counted through, fed the same
     * numbers in the same order.
     */
    fun boundaries(
        segments: List<WorkoutSegment>,
        advancesActiveMs: List<Long>,
        points: List<TrackPoint>,
        startActiveMs: Long = 0,
    ): List<Long> {
        if (segments.isEmpty() || points.isEmpty()) return emptyList()
        val scheduler = WorkoutScheduler(segments)
        val marks = advancesActiveMs.sorted()
        val timeline = WorkoutStart.timeline(points)
        val startMeters = WorkoutStart.metersAt(points, startActiveMs)
        val out = mutableListOf<Long>()

        var cursor = WorkoutCursor()
        var applied = 0
        points.forEachIndexed { i, point ->
            if (timeline[i] < startActiveMs) return@forEachIndexed
            val activeMs = timeline[i] - startActiveMs
            val meters = point.cumulativeDistanceM - startMeters

            // A segment the runner ended by hand ends here; one that ran its course ends
            // where the scheduler says, which is somewhere between this fix and the last.
            while (applied < marks.size && marks[applied] <= activeMs && !cursor.complete) {
                cursor = scheduler.advance(activeMs, meters, cursor)
                out += cursor.segmentStartedActiveMs
                applied++
            }
            while (true) {
                cursor = scheduler.advanceNaturally(activeMs, meters, cursor) ?: break
                out += cursor.segmentStartedActiveMs
            }
        }
        return out
    }

    /** Distance at an active time, interpolated between the two fixes either side of it. */
    private fun metersAt(points: List<TrackPoint>, timeline: LongArray, activeMs: Long): Double {
        val after = timeline.indexOfFirst { it >= activeMs }
        if (after < 0) return points.last().cumulativeDistanceM
        if (after == 0) return points[0].cumulativeDistanceM
        val span = timeline[after] - timeline[after - 1]
        val before = points[after - 1].cumulativeDistanceM
        if (span <= 0) return before
        val fraction = (activeMs - timeline[after - 1]).toDouble() / span
        return before + (points[after].cumulativeDistanceM - before) * fraction
    }
}

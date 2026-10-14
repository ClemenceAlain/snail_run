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
     */
    fun of(
        segments: List<WorkoutSegment>,
        advancesActiveMs: List<Long>,
        points: List<TrackPoint>,
        startActiveMs: Long = 0,
    ): List<SegmentResult> {
        if (segments.isEmpty() || points.size < 2) return emptyList()

        val scheduler = WorkoutScheduler(segments)
        val marks = advancesActiveMs.sorted()
        val results = mutableListOf<SegmentResult>()
        // The same gate the accumulator uses: within a segment of the track, and across a
        // hole in the fixes only where the runner ran through it.
        val timeline = WorkoutStart.timeline(points)
        val startMeters = WorkoutStart.metersAt(points, startActiveMs)

        var cursor = WorkoutCursor()
        var applied = 0
        var activeMs = 0L
        var meters = 0.0

        points.forEachIndexed { i, point ->
            if (timeline[i] < startActiveMs) return@forEachIndexed
            activeMs = timeline[i] - startActiveMs
            meters = point.cumulativeDistanceM - startMeters

            // A segment the runner ended by hand ends here; one that ran its course ends
            // where the scheduler says, which is somewhere between this fix and the last.
            while (applied < marks.size && marks[applied] <= activeMs && !cursor.complete) {
                cursor = record(results, scheduler, cursor, scheduler.advance(activeMs, meters, cursor), startMeters)
                applied++
            }
            while (true) {
                val next = scheduler.advanceNaturally(activeMs, meters, cursor) ?: break
                cursor = record(results, scheduler, cursor, next, startMeters)
            }
        }

        // Whatever the runner was in the middle of when they pressed stop.
        if (!cursor.complete && results.size <= cursor.segmentIndex) {
            results += SegmentResult(
                segment = segments[cursor.segmentIndex],
                actualMs = (activeMs - cursor.segmentStartedActiveMs).coerceAtLeast(0),
                actualMeters = (meters - cursor.segmentStartedMeters).coerceAtLeast(0.0),
                startedAtMeters = startMeters + cursor.segmentStartedMeters,
            )
        }
        return results
    }

    /**
     * Writes down the segment the cursor has just left.
     *
     * The new cursor's marks are the old segment's end, interpolated across the gap
     * between two fixes, so a rep's figures are what it was run in rather than what the
     * fix after it happened to say.
     */
    private fun record(
        into: MutableList<SegmentResult>,
        scheduler: WorkoutScheduler,
        before: WorkoutCursor,
        after: WorkoutCursor,
        startMeters: Double,
    ): WorkoutCursor {
        into += SegmentResult(
            segment = scheduler.segments[before.segmentIndex],
            actualMs = (after.segmentStartedActiveMs - before.segmentStartedActiveMs)
                .coerceAtLeast(0),
            actualMeters = (after.segmentStartedMeters - before.segmentStartedMeters)
                .coerceAtLeast(0.0),
            startedAtMeters = startMeters + before.segmentStartedMeters,
        )
        return after
    }
}

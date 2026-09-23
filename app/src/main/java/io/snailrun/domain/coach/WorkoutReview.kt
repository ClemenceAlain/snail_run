package io.snailrun.domain.coach

import io.snailrun.domain.metrics.TrackGaps
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

    val onTarget: Boolean get() = paceDeltaSecPerKm?.let { kotlin.math.abs(it) < 1.0 } ?: true

    /**
     * Whether a miss went the way that helps, or null where there was no target.
     *
     * Faster is better only on work. On a warm-up, a jog or a cool-down the point is to
     * go easy, so running it slower than asked is fine and faster is the mistake — the
     * one that leaves nothing for the reps.
     */
    val verdict: PaceVerdict?
        get() {
            val delta = paceDeltaSecPerKm ?: return null
            if (onTarget) return PaceVerdict.OnTarget
            val faster = delta < 0
            val fasterIsBetter = segment.kind == SegmentKind.Work
            return if (faster == fasterIsBetter) PaceVerdict.Better else PaceVerdict.Worse
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

    fun of(
        segments: List<WorkoutSegment>,
        advancesActiveMs: List<Long>,
        points: List<TrackPoint>,
    ): List<SegmentResult> {
        if (segments.isEmpty() || points.size < 2) return emptyList()

        val scheduler = WorkoutScheduler(segments)
        val marks = advancesActiveMs.sorted()
        val results = mutableListOf<SegmentResult>()

        var cursor = WorkoutCursor()
        var applied = 0
        var activeMs = 0L
        var previous: TrackPoint? = null

        points.forEach { point ->
            previous?.let { before ->
                val delta = point.timestampMs - before.timestampMs
                // The same gate the accumulator uses: within a segment of the track,
                // and across a hole in the fixes only where the runner ran through it.
                if (point.segment == before.segment) {
                    activeMs += TrackGaps.countable(
                        gapMs = delta,
                        straightLineM = point.cumulativeDistanceM - before.cumulativeDistanceM,
                    )
                }
            }
            previous = point
            val meters = point.cumulativeDistanceM

            // A segment the runner ended by hand ends here; one that ran its course ends
            // where the scheduler says, which is somewhere between this fix and the last.
            while (applied < marks.size && marks[applied] <= activeMs && !cursor.complete) {
                cursor = record(results, scheduler, cursor, scheduler.advance(activeMs, meters, cursor))
                applied++
            }
            while (true) {
                val next = scheduler.advanceNaturally(activeMs, meters, cursor) ?: break
                cursor = record(results, scheduler, cursor, next)
            }
        }

        // Whatever the runner was in the middle of when they pressed stop.
        if (!cursor.complete && results.size <= cursor.segmentIndex) {
            results += SegmentResult(
                segment = segments[cursor.segmentIndex],
                actualMs = (activeMs - cursor.segmentStartedActiveMs).coerceAtLeast(0),
                actualMeters = (points.last().cumulativeDistanceM - cursor.segmentStartedMeters)
                    .coerceAtLeast(0.0),
                startedAtMeters = cursor.segmentStartedMeters,
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
    ): WorkoutCursor {
        into += SegmentResult(
            segment = scheduler.segments[before.segmentIndex],
            actualMs = (after.segmentStartedActiveMs - before.segmentStartedActiveMs)
                .coerceAtLeast(0),
            actualMeters = (after.segmentStartedMeters - before.segmentStartedMeters)
                .coerceAtLeast(0.0),
            startedAtMeters = before.segmentStartedMeters,
        )
        return after
    }
}

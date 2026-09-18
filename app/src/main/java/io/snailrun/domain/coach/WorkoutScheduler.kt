package io.snailrun.domain.coach

/**
 * Where the runner is in the session.
 *
 * Every field is derived from two numbers the recorder already has — active duration and
 * cumulative distance — plus the marks in the cursor. Nothing here is remembered that
 * could have been worked out again.
 */
data class WorkoutProgress(
    val segment: WorkoutSegment,
    val next: WorkoutSegment?,
    val elapsedInSegmentMs: Long,
    val metersInSegment: Double,
    val remainingMs: Long?,
    val remainingM: Double?,
    val segmentsDone: Int,
    val segmentCount: Int,
    val complete: Boolean,
)

/**
 * Something to say, buzz, or both.
 *
 * Distinct from `Announcement`, which reports figures on a schedule. A cue is worth
 * nothing a minute late: it is the difference between starting the rep and being told you
 * should have started it.
 */
sealed interface WorkoutCue {
    data class StepStart(val segment: WorkoutSegment, val next: WorkoutSegment?) : WorkoutCue
    data class Countdown(val seconds: Int) : WorkoutCue
    data class OffPace(val tooFast: Boolean) : WorkoutCue
    data object Finished : WorkoutCue
}

/**
 * Bookkeeping, passed in and returned rather than held.
 *
 * That shape is what makes a crash survivable: nothing about where the runner had got to
 * is written down, because replaying the scheduler over the stored track produces the
 * same cursor it had before the process died.
 */
data class WorkoutCursor(
    val segmentIndex: Int = 0,
    val segmentStartedActiveMs: Long = 0,
    val segmentStartedMeters: Double = 0.0,
    /** Lowest countdown number already spoken for this segment. 0 means none. */
    val countdownSpoken: Int = 0,
    /** Which segment the runner has been told about. -1 before the session opens. */
    val announcedIndex: Int = -1,
    val offPaceSinceActiveMs: Long? = null,
    val lastNudgeActiveMs: Long = -1,
    val complete: Boolean = false,
)

data class WorkoutCueConfig(
    /** Off by default: it is the one that gets the whole feature turned off. */
    val nudgeOffPace: Boolean = false,
    val countdownFrom: Int = 3,
    /** How far outside the band counts as off it. */
    val nudgeToleranceSecPerKm: Double = 8.0,
    /** How long it has to stay outside before the app says anything. */
    val nudgeAfterMs: Long = 20_000,
    /** And how long before it may say it again. */
    val nudgeEveryMs: Long = 60_000,
    /** Segments shorter than this are over before a nudge could help. */
    val nudgeMinSegmentMs: Long = 90_000,
)

/**
 * Counts a runner through a session.
 *
 * Pure, clock-free, and driven only by what the recorder already knows. Three decisions
 * are worth stating because each could reasonably have gone the other way:
 *
 * - **A segment ends on active duration or on distance**, never on wall clock. A pause
 *   stops the accrual, so standing at a light does not burn the rep — the same reason
 *   every other figure in this app is measured that way.
 * - **Boundaries are only tested when this is called**, which is on a GPS fix, about once
 *   a second. A rep therefore ends on the first fix past its end: a second late normally,
 *   later through a tunnel. The alternative is a timer that ends a rep the runner was not
 *   moving through, which is worse than a late cue.
 * - **A skipped segment is a segment that ended early**, not a segment removed. [advance]
 *   just moves the marks, so the replay that rebuilds this after a crash needs nothing
 *   but the active-duration stamps of the skips.
 */
class WorkoutScheduler(
    val segments: List<WorkoutSegment>,
    private val config: WorkoutCueConfig = WorkoutCueConfig(),
) {

    fun evaluate(
        activeMs: Long,
        meters: Double,
        paceSecPerKm: Double?,
        cursor: WorkoutCursor,
    ): Triple<WorkoutProgress, List<WorkoutCue>, WorkoutCursor> {
        if (segments.isEmpty()) return Triple(finished(cursor), emptyList(), cursor.copy(complete = true))

        val cues = mutableListOf<WorkoutCue>()
        var state = cursor

        if (state.announcedIndex < 0) {
            state = state.copy(segmentStartedActiveMs = activeMs, segmentStartedMeters = meters)
        }

        // A loop rather than an `if`: one long gap in the fixes can carry the runner past
        // a whole rep, and leaving them a segment behind until the next fix would put the
        // session permanently one step out.
        while (!state.complete && isOver(segments[state.segmentIndex], activeMs, meters, state)) {
            state = step(state, activeMs, meters, natural = true)
        }

        // At most one step cue per call, for where the runner is now. Coming out of a
        // tunnel three segments late, "rep two, hard" is the useful sentence; "hard, jog,
        // hard" in one breath is noise, and the announcer speaks one utterance at a time
        // anyway, so only the last of them would be heard.
        if (state.complete) {
            if (cursor.announcedIndex != COMPLETE) {
                cues += WorkoutCue.Finished
                state = state.copy(announcedIndex = COMPLETE)
            }
        } else if (state.announcedIndex != state.segmentIndex) {
            cues += WorkoutCue.StepStart(
                segments[state.segmentIndex],
                segments.getOrNull(state.segmentIndex + 1),
            )
            state = state.copy(announcedIndex = state.segmentIndex)
        }

        if (!state.complete) {
            val (countdown, afterCountdown) = countdown(state, activeMs, meters)
            state = afterCountdown
            cues += countdown

            val (nudge, afterNudge) = nudge(state, activeMs, paceSecPerKm)
            state = afterNudge
            if (nudge != null) cues += nudge
        }

        return Triple(progress(state, activeMs, meters), cues, state)
    }

    /** The runner pressed Next. The segment ends here rather than where it was going to. */
    fun advance(activeMs: Long, meters: Double, cursor: WorkoutCursor): WorkoutCursor =
        if (cursor.complete) cursor else step(cursor, activeMs, meters, natural = false)

    fun progressOf(cursor: WorkoutCursor, activeMs: Long, meters: Double): WorkoutProgress =
        progress(cursor, activeMs, meters)

    /**
     * One segment on, if this one is over; null if it is not.
     *
     * [evaluate] runs this in a loop and reports only where the runner ended up, which is
     * all a cue needs. A replay looking back over a finished run needs every boundary it
     * passed, so it drives the same rule one step at a time rather than writing a second
     * copy of it.
     */
    fun advanceNaturally(activeMs: Long, meters: Double, cursor: WorkoutCursor): WorkoutCursor? {
        if (cursor.complete) return null
        if (!isOver(segments[cursor.segmentIndex], activeMs, meters, cursor)) return null
        return step(cursor, activeMs, meters, natural = true)
    }

    // ---- internals -------------------------------------------------------------------

    /**
     * Moves to the next segment.
     *
     * A segment that ran its course hands the overshoot on: the new one starts where the
     * old one was due to end, not where the fix happened to land. Without that a session
     * drifts a second longer per rep at best, and a long gap in the fixes can only ever
     * advance one segment however much running happened inside it.
     *
     * A segment the runner ended by hand starts the next one here, because here is where
     * they actually stopped doing the old one.
     */
    private fun step(
        cursor: WorkoutCursor,
        activeMs: Long,
        meters: Double,
        natural: Boolean,
    ): WorkoutCursor {
        val segment = segments[cursor.segmentIndex]
        val next = cursor.segmentIndex + 1

        // Where the segment actually ended, which is somewhere between the last two
        // fixes. The governing dimension is known exactly; the other is interpolated
        // across the gap at the same fraction — the way `SplitCalculator` already finds a
        // kilometre boundary between two points. Without it a long gap can only ever
        // advance one segment, because the one it advances into looks like it has just
        // started.
        val elapsed = activeMs - cursor.segmentStartedActiveMs
        val covered = meters - cursor.segmentStartedMeters
        val fraction = when {
            !natural -> 1.0
            segment.targetMs != null && elapsed > 0 -> segment.targetMs.toDouble() / elapsed
            segment.targetM != null && covered > 0 -> segment.targetM / covered
            else -> 1.0
        }.coerceIn(0.0, 1.0)

        return cursor.copy(
            segmentIndex = next.coerceAtMost(segments.lastIndex),
            segmentStartedActiveMs = cursor.segmentStartedActiveMs + (elapsed * fraction).toLong(),
            segmentStartedMeters = cursor.segmentStartedMeters + covered * fraction,
            countdownSpoken = 0,
            offPaceSinceActiveMs = null,
            complete = next > segments.lastIndex,
        )
    }

    private fun isOver(
        segment: WorkoutSegment,
        activeMs: Long,
        meters: Double,
        cursor: WorkoutCursor,
    ): Boolean {
        segment.targetMs?.let { return activeMs - cursor.segmentStartedActiveMs >= it }
        segment.targetM?.let { return meters - cursor.segmentStartedMeters >= it }
        // A segment with no target runs until the runner says otherwise: an easy run is
        // one of those, and so is the last stretch of a session somebody is extending.
        return false
    }

    private fun countdown(
        cursor: WorkoutCursor,
        activeMs: Long,
        meters: Double,
    ): Pair<List<WorkoutCue>, WorkoutCursor> {
        val segment = segments[cursor.segmentIndex]
        val remaining = remainingMs(segment, cursor, activeMs, meters)
            ?: return emptyList<WorkoutCue>() to cursor

        val at = ((remaining + 999) / 1000).toInt()
        if (at !in 1..config.countdownFrom) return emptyList<WorkoutCue>() to cursor
        // Spoken once each, and only downwards: a fix arriving late must not make the
        // count go "three, one, two".
        if (cursor.countdownSpoken != 0 && at >= cursor.countdownSpoken) {
            return emptyList<WorkoutCue>() to cursor
        }
        return listOf(WorkoutCue.Countdown(at)) to cursor.copy(countdownSpoken = at)
    }

    private fun nudge(
        cursor: WorkoutCursor,
        activeMs: Long,
        paceSecPerKm: Double?,
    ): Pair<WorkoutCue?, WorkoutCursor> {
        val segment = segments[cursor.segmentIndex]
        val band = segment.paceSecPerKm
        if (!config.nudgeOffPace || band == null || paceSecPerKm == null) {
            return null to cursor.copy(offPaceSinceActiveMs = null)
        }
        // A rep too short to fix is a rep not worth commenting on.
        if ((segment.targetMs ?: 0) < config.nudgeMinSegmentMs) {
            return null to cursor.copy(offPaceSinceActiveMs = null)
        }

        val tooFast = paceSecPerKm < band.start - config.nudgeToleranceSecPerKm
        val tooSlow = paceSecPerKm > band.endInclusive + config.nudgeToleranceSecPerKm
        if (!tooFast && !tooSlow) return null to cursor.copy(offPaceSinceActiveMs = null)

        val since = cursor.offPaceSinceActiveMs ?: activeMs
        val drifting = cursor.copy(offPaceSinceActiveMs = since)
        if (activeMs - since < config.nudgeAfterMs) return null to drifting
        if (cursor.lastNudgeActiveMs >= 0 && activeMs - cursor.lastNudgeActiveMs < config.nudgeEveryMs) {
            return null to drifting
        }
        return WorkoutCue.OffPace(tooFast) to drifting.copy(
            lastNudgeActiveMs = activeMs,
            offPaceSinceActiveMs = null,
        )
    }

    private fun remainingMs(
        segment: WorkoutSegment,
        cursor: WorkoutCursor,
        activeMs: Long,
        meters: Double,
    ): Long? {
        segment.targetMs?.let { return it - (activeMs - cursor.segmentStartedActiveMs) }
        // A distance segment has no countdown. Guessing one from the current pace would
        // say "three, two, one" and then be wrong by ten metres, which is worse than
        // saying nothing and cueing the change when it happens.
        return null
    }

    private fun progress(cursor: WorkoutCursor, activeMs: Long, meters: Double): WorkoutProgress {
        if (cursor.complete) return finished(cursor)
        val segment = segments[cursor.segmentIndex]
        return WorkoutProgress(
            segment = segment,
            next = segments.getOrNull(cursor.segmentIndex + 1),
            elapsedInSegmentMs = activeMs - cursor.segmentStartedActiveMs,
            metersInSegment = meters - cursor.segmentStartedMeters,
            remainingMs = remainingMs(segment, cursor, activeMs, meters)?.coerceAtLeast(0),
            remainingM = segment.targetM?.let {
                (it - (meters - cursor.segmentStartedMeters)).coerceAtLeast(0.0)
            },
            segmentsDone = cursor.segmentIndex,
            segmentCount = segments.size,
            complete = false,
        )
    }

    private companion object {
        /** [WorkoutCursor.announcedIndex] once the session is over and has said so. */
        const val COMPLETE = -2
    }

    private fun finished(cursor: WorkoutCursor) = WorkoutProgress(
        segment = segments.lastOrNull() ?: WorkoutSegment(0, "Done", SegmentKind.CoolDown),
        next = null,
        elapsedInSegmentMs = 0,
        metersInSegment = 0.0,
        remainingMs = null,
        remainingM = null,
        segmentsDone = segments.size,
        segmentCount = segments.size,
        complete = true,
    )
}

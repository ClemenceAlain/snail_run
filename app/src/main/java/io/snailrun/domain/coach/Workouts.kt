package io.snailrun.domain.coach

import kotlin.math.roundToLong

/**
 * The named sessions. Between them they cover every intensity a distance runner trains
 * at, which is the point: a plan that only knows "easy" and "hard" cannot build anything.
 */
enum class WorkoutType(val label: String) {
    Rest("Rest"),
    Recovery("Recovery"),
    Easy("Easy"),
    Strides("Easy + strides"),
    Long("Long run"),
    Progression("Progression"),
    Steady("Steady"),
    Tempo("Tempo"),
    CruiseIntervals("Cruise intervals"),
    Intervals("Intervals"),
    Hills("Hill repeats"),
    Fartlek("Fartlek"),
    Repetitions("Repetitions"),
    Strength("Strength"),
    ;

    /**
     * Whether the session needs a day either side of it.
     *
     * Strides are not on this list on purpose. Six twenty-second accelerations inside an
     * easy run are a reminder of what fast feels like, not a workout, and treating them
     * as one would cost a runner a quality day they never spent.
     *
     * Nor is strength. It is hard, but it is hard in a way that does not compete with a
     * tempo for the same recovery, and marking it quality would push a running session
     * out of the week to make room for a set of squats.
     */
    val isQuality: Boolean
        get() = this in setOf(Steady, Tempo, CruiseIntervals, Intervals, Hills, Fartlek, Repetitions)

    /** Whether this is something you leave the house to record. Strength is not. */
    val isRun: Boolean get() = this != Rest && this != Strength
}

/**
 * One line of a session. Distance or duration, whichever the step is actually prescribed
 * in — 8 × 200 m is a distance and 4 × 8 min is a time, and rewriting either into the
 * other loses what the runner is meant to watch.
 */
data class WorkoutStep(
    val label: String,
    val repeats: Int = 1,
    val distanceM: Double? = null,
    val durationMs: Long? = null,
    val paceSecPerKm: ClosedFloatingPointRange<Double>? = null,
    /**
     * The jog between reps.
     *
     * Its distance was always counted in [Workout.totalMeters] — a session is what the
     * legs carry, and the jog is most of the time on them. It just was not written down
     * anywhere but the label, which is fine for a line on a screen and useless to
     * anything that has to count the runner through it.
     */
    val recoveryMs: Long? = null,
    val recoveryM: Double? = null,
    val recoveryPaceSecPerKm: ClosedFloatingPointRange<Double>? = null,
    /**
     * Repetitions inside a set, for strength work: the 12 in "3 × 12 squats".
     *
     * A third counting dimension beside distance and duration, because a squat is
     * measured in neither. Null everywhere else.
     */
    val countPerSet: Int? = null,
    /** Counted per leg or per side, so the set is really twice what it says. */
    val perSide: Boolean = false,
)

data class Workout(
    val type: WorkoutType,
    val totalMeters: Double,
    val steps: List<WorkoutStep>,
    /** Why this session, this length, today. Shown under it, never generated loosely. */
    val reason: String,
    /**
     * Metres actually run at the hard pace, jogs and trimmings excluded.
     *
     * Separate from [totalMeters] because the two are capped by different things: the
     * week's total is what the legs carry, and this is what they carry it at. Daniels'
     * limits — ten per cent threshold, eight per cent interval, five per cent repetition
     * — are limits on this number, and asserting them against the session total would
     * quietly allow half as much again.
     */
    val qualityMeters: Double = 0.0,
    /** Roughly how long it takes, for a session measured in neither pace nor distance. */
    val estimatedMs: Long? = null,
    /** A name for this particular session, where the type's label is too coarse. */
    val title: String? = null,
) {
    val name: String get() = title ?: type.label
}

/**
 * Builds a session from a pace and a metres budget.
 *
 * Warm-up and cool-down are steps like any other and count towards [Workout.totalMeters].
 * A session whose total counts only the fast part under-reports the week by five or six
 * kilometres, and the weekly cap is the thing standing between a runner and an injury.
 *
 * Two rules hold throughout, and both exist so that what the plan says and what the app
 * counts the runner through are the same thing:
 *
 * - **Every distance and every derived duration is rounded once, here** — see [Round].
 *   Rounding at the point of display instead would leave a card reading "900 m" above a
 *   rep that ends at 913.
 * - **[Workout.totalMeters] is the sum of the session as it is actually run**, measured by
 *   [metersOf] rather than assembled by hand in each builder. Every builder used to add up
 *   its own total, which is four lines of arithmetic per session that nobody re-checks
 *   when a rep count changes — and which the jog between reps quietly slipped out of more
 *   than once.
 */
object Workouts {

    fun rest(reason: String) = Workout(WorkoutType.Rest, 0.0, emptyList(), reason)

    fun easy(meters: Double, paces: TrainingPaces, reason: String) = build(
        type = WorkoutType.Easy,
        steps = listOf(
            WorkoutStep("Easy", distanceM = Round.blockMeters(meters), paceSecPerKm = paces.easySecPerKm)
        ),
        reason = reason,
    )

    fun recovery(meters: Double, paces: TrainingPaces, reason: String) = build(
        type = WorkoutType.Recovery,
        steps = listOf(
            // The slow end of easy, and only the slow end. A recovery run run at the fast
            // end of easy is an easy run, and the day it was meant to repair is gone.
            WorkoutStep(
                "Very easy",
                distanceM = Round.blockMeters(meters),
                paceSecPerKm = paces.easySecPerKm.endInclusive..(paces.easySecPerKm.endInclusive + 30.0),
            )
        ),
        reason = reason,
    )

    fun longRun(meters: Double, paces: TrainingPaces, reason: String) = build(
        type = WorkoutType.Long,
        steps = listOf(
            WorkoutStep("Easy", distanceM = Round.blockMeters(meters), paceSecPerKm = paces.easySecPerKm)
        ),
        reason = reason,
    )

    /** Easy for two thirds, marathon pace for the last third. A long run's cheap upgrade. */
    fun progression(meters: Double, paces: TrainingPaces, reason: String): Workout {
        val fast = Round.blockMeters(meters / 3.0)
        return build(
            type = WorkoutType.Progression,
            steps = listOf(
                WorkoutStep(
                    "Easy",
                    distanceM = Round.blockMeters(meters - fast),
                    paceSecPerKm = paces.easySecPerKm,
                ),
                WorkoutStep("Finish steady", distanceM = fast, paceSecPerKm = single(paces.marathonSecPerKm)),
            ),
            reason = reason,
        )
    }

    fun strides(meters: Double, paces: TrainingPaces, reason: String): Workout {
        val reps = 6
        // Twenty seconds because somebody chose twenty, so it is not rounded: see [Round].
        val strideMs = 20_000L
        val strideMeters = metersAt(paces.repetitionSecPerKm, strideMs) * reps
        return build(
            type = WorkoutType.Strides,
            steps = listOf(
                WorkoutStep(
                    "Easy",
                    distanceM = Round.blockMeters(meters - strideMeters),
                    paceSecPerKm = paces.easySecPerKm,
                ),
                WorkoutStep(
                    "Strides, walk back between",
                    repeats = reps,
                    durationMs = strideMs,
                    paceSecPerKm = single(paces.repetitionSecPerKm),
                ),
            ),
            reason = reason,
        )
    }

    /** Continuous at marathon pace: the closest thing to race rehearsal that is not a race. */
    fun steady(workMeters: Double, paces: TrainingPaces, reason: String): Workout {
        val work = Round.blockMeters(workMeters)
        val trim = trimFor(work)
        return build(
            type = WorkoutType.Steady,
            qualityMeters = work,
            steps = listOf(
                warmUp(trim, paces),
                WorkoutStep("Steady", distanceM = work, paceSecPerKm = single(paces.marathonSecPerKm)),
                coolDown(trim, paces),
            ),
            reason = reason,
        )
    }

    /**
     * One continuous block at threshold, twelve to forty minutes.
     *
     * The ceiling is the session's definition rather than a safety margin: past forty
     * minutes threshold work stops being comfortably hard and turns into a race the
     * runner did not enter. The floor is low because the weekly cap has the last word —
     * a runner on twenty kilometres a week cannot afford a twenty-minute tempo, and the
     * right answer there is a shorter one, not a bigger week.
     */
    fun tempo(workMeters: Double, paces: TrainingPaces, reason: String): Workout {
        val duration = Round.stepMs(
            durationAt(paces.thresholdSecPerKm, workMeters).coerceIn(12 * 60_000L, 40 * 60_000L)
        )
        val meters = Round.blockMeters(metersAt(paces.thresholdSecPerKm, duration))
        val trim = trimFor(meters)
        return build(
            type = WorkoutType.Tempo,
            qualityMeters = meters,
            steps = listOf(
                warmUp(trim, paces),
                WorkoutStep(
                    "Tempo",
                    distanceM = meters,
                    durationMs = duration,
                    paceSecPerKm = single(paces.thresholdSecPerKm),
                ),
                coolDown(trim, paces),
            ),
            reason = reason,
        )
    }

    /**
     * The same threshold minutes, broken up. Same benefit, less chance of drifting faster.
     *
     * The rep length falls out of the budget rather than being fixed: three to six reps
     * of whatever the week can afford, which lands between four and eight minutes each.
     * Fixing the rep at eight minutes and clamping the count is what makes a small week
     * get handed twenty-four minutes of threshold it cannot carry.
     */
    fun cruiseIntervals(workMeters: Double, paces: TrainingPaces, reason: String): Workout {
        val totalMs = durationAt(paces.thresholdSecPerKm, workMeters)
        val reps = (totalMs / (5 * 60_000L)).toInt().coerceIn(3, 6)
        val repMs = Round.stepMs(totalMs / reps)
        val repMeters = Round.repMeters(metersAt(paces.thresholdSecPerKm, repMs))
        val trim = trimFor(repMeters * reps)
        return build(
            type = WorkoutType.CruiseIntervals,
            qualityMeters = repMeters * reps,
            steps = listOf(
                warmUp(trim, paces),
                WorkoutStep(
                    "At threshold, 2 min jog between",
                    repeats = reps,
                    durationMs = repMs,
                    distanceM = repMeters,
                    paceSecPerKm = single(paces.thresholdSecPerKm),
                    recoveryMs = 2 * 60_000L,
                    recoveryPaceSecPerKm = jog(paces),
                ),
                coolDown(trim, paces),
            ),
            reason = reason,
        )
    }

    /** Four to six reps at interval pace, equal jog. The rep length fits the budget. */
    fun intervals(workMeters: Double, paces: TrainingPaces, reason: String): Workout {
        val totalMs = durationAt(paces.intervalSecPerKm, workMeters)
        val reps = (totalMs / (3 * 60_000L)).toInt().coerceIn(4, 6)
        val repMs = Round.stepMs(totalMs / reps)
        val repMeters = Round.repMeters(metersAt(paces.intervalSecPerKm, repMs))
        val trim = trimFor(repMeters * reps)
        return build(
            type = WorkoutType.Intervals,
            qualityMeters = repMeters * reps,
            steps = listOf(
                warmUp(trim, paces),
                WorkoutStep(
                    "Hard, equal jog between",
                    repeats = reps,
                    durationMs = repMs,
                    distanceM = repMeters,
                    paceSecPerKm = single(paces.intervalSecPerKm),
                    recoveryMs = repMs,
                    recoveryPaceSecPerKm = jog(paces),
                ),
                coolDown(trim, paces),
            ),
            reason = reason,
        )
    }

    /**
     * Interval strain at a fraction of the impact: running up a hill the legs cannot turn
     * over fast enough to land hard, and jogging down is the recovery.
     *
     * Prescribed by effort and not by pace, because a pace up a hill is meaningless — the
     * gradient decides it, and a runner chasing a flat-ground number up a slope is how a
     * calf goes. The budget is still priced at interval pace, which is what the effort
     * costs even though it is not what the watch will read.
     */
    fun hills(workMeters: Double, paces: TrainingPaces, reason: String): Workout {
        val repMs = 45_000L
        val repMeters = Round.repMeters(metersAt(paces.intervalSecPerKm, repMs))
        val reps = (workMeters / repMeters).toInt().coerceIn(4, 10)
        val trim = trimFor(repMeters * reps)
        return build(
            type = WorkoutType.Hills,
            qualityMeters = repMeters * reps,
            steps = listOf(
                warmUp(trim, paces),
                WorkoutStep(
                    "Uphill hard, jog down",
                    repeats = reps,
                    durationMs = repMs,
                    // Carried but never shown and never enforced: the step is prescribed
                    // in time and has no pace band on purpose — a pace up a hill is the
                    // gradient's decision, not the runner's. The distance is only what
                    // that time is expected to cover, and it is here because it is the
                    // one session whose work the week cannot otherwise count: with no
                    // band to price the minutes at, the uphills would weigh nothing.
                    distanceM = repMeters,
                    // The way down is the way up, so the jog is a distance and not a
                    // time: how long it takes is the runner's business.
                    recoveryM = repMeters,
                    recoveryPaceSecPerKm = jog(paces),
                ),
                coolDown(trim, paces),
            ),
            reason = reason,
        )
    }

    fun fartlek(workMeters: Double, paces: TrainingPaces, reason: String): Workout {
        val repMs = 60_000L
        val repMeters = Round.repMeters(metersAt(paces.intervalSecPerKm, repMs))
        val reps = (workMeters / repMeters).toInt().coerceIn(4, 10)
        val trim = trimFor(repMeters * reps)
        return build(
            type = WorkoutType.Fartlek,
            qualityMeters = repMeters * reps,
            steps = listOf(
                warmUp(trim, paces),
                WorkoutStep(
                    "1 min quick, 1 min easy",
                    repeats = reps,
                    durationMs = repMs,
                    paceSecPerKm = single(paces.intervalSecPerKm),
                    recoveryMs = repMs,
                    recoveryPaceSecPerKm = jog(paces),
                ),
                coolDown(trim, paces),
            ),
            reason = reason,
        )
    }

    /** Short, fast, fully recovered. Trains how you run, not how much oxygen you can use. */
    fun repetitions(workMeters: Double, paces: TrainingPaces, reason: String): Workout {
        val repMeters = 200.0
        val reps = (workMeters / repMeters).toInt().coerceIn(4, 10)
        val meters = repMeters * reps
        val trim = trimFor(meters * 2)
        return build(
            type = WorkoutType.Repetitions,
            qualityMeters = meters,
            steps = listOf(
                warmUp(trim, paces),
                WorkoutStep(
                    "Fast, 400 m jog between",
                    repeats = reps,
                    distanceM = repMeters,
                    paceSecPerKm = single(paces.repetitionSecPerKm),
                    recoveryM = 400.0,
                    recoveryPaceSecPerKm = jog(paces),
                ),
                coolDown(trim, paces),
            ),
            reason = reason,
        )
    }

    /**
     * Assembles a session and prices it from the sequence it will actually be run in.
     *
     * The two-step construction is the point: [WorkoutSegments] takes a [Workout], so the
     * total cannot be known until the steps are wrapped in one. The draft exists for
     * exactly as long as it takes to measure it.
     */
    private fun build(
        type: WorkoutType,
        steps: List<WorkoutStep>,
        reason: String,
        qualityMeters: Double = 0.0,
    ): Workout {
        val draft = Workout(type, 0.0, steps, reason, qualityMeters)
        return draft.copy(totalMeters = metersOf(draft))
    }

    /**
     * What the session covers, counting every rep and every jog the runner will run.
     *
     * Read off the steps rather than off [WorkoutSegments], which is where this started
     * and where it was wrong: a segment keeps only the dimension it is governed by, so a
     * step prescribed in time loses its distance on the way through. Hill repeats are
     * prescribed in time *and* carry no pace band, which left nothing at all to price
     * them from — the week was counting a hill session at its warm-up and its jogs.
     *
     * The two rules [WorkoutSegments] applies are applied here too, because what is being
     * measured is the session as the recorder will count it:
     *
     * - a step's own distance wins where it has one, and a distance is inferred from the
     *   time and the middle of the pace band only where it does not;
     * - the jog is counted between the reps and not after the last one, which is the
     *   trailing recovery the segment list drops.
     */
    fun metersOf(workout: Workout): Double = workout.steps.sumOf { step ->
        val repeats = step.repeats.coerceAtLeast(1)
        val work = step.distanceM
            ?: step.durationMs?.let { metersAt(midpoint(step.paceSecPerKm), it) }
            ?: 0.0
        val recovery = step.recoveryM
            ?: step.recoveryMs?.let { metersAt(midpoint(step.recoveryPaceSecPerKm), it) }
            ?: 0.0
        work * repeats + recovery * (repeats - 1)
    }

    /**
     * A band's middle, for turning a duration into a distance.
     *
     * A band rather than a pace is an easy run or a jog, and a runner inside one is on
     * average in the middle of it. Taking either end instead would bias every weekly
     * total in the same direction, week after week.
     */
    private fun midpoint(range: ClosedFloatingPointRange<Double>?): Double =
        if (range == null) 0.0 else (range.start + range.endInclusive) / 2.0

    private fun warmUp(meters: Double, paces: TrainingPaces) =
        WorkoutStep("Warm up", distanceM = meters, paceSecPerKm = paces.easySecPerKm)

    private fun coolDown(meters: Double, paces: TrainingPaces) =
        WorkoutStep("Cool down", distanceM = meters, paceSecPerKm = paces.easySecPerKm)

    /** Warm-up and cool-down scale with the session, within reason. */
    private fun trimFor(workMeters: Double): Double =
        Round.blockMeters((workMeters * 0.5).coerceIn(1_200.0, 2_500.0))

    fun metersAt(paceSecPerKm: Double, durationMs: Long): Double =
        if (paceSecPerKm <= 0.0) 0.0 else durationMs / 1000.0 / paceSecPerKm * 1000.0

    fun durationAt(paceSecPerKm: Double, meters: Double): Long =
        (meters / 1000.0 * paceSecPerKm * 1000.0).roundToLong()

    private fun single(pace: Double) = pace..pace

    /** Recovery is run at the slow end of easy, or it is not recovery. */
    private fun jog(paces: TrainingPaces) =
        paces.easySecPerKm.endInclusive..(paces.easySecPerKm.endInclusive + 30.0)
}

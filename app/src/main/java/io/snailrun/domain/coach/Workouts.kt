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
    ;

    /**
     * Whether the session needs a day either side of it.
     *
     * Strides are not on this list on purpose. Six twenty-second accelerations inside an
     * easy run are a reminder of what fast feels like, not a workout, and treating them
     * as one would cost a runner a quality day they never spent.
     */
    val isQuality: Boolean
        get() = this in setOf(Steady, Tempo, CruiseIntervals, Intervals, Hills, Fartlek, Repetitions)
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
)

/**
 * Builds a session from a pace and a metres budget.
 *
 * Warm-up and cool-down are steps like any other and count towards [Workout.totalMeters].
 * A session whose total counts only the fast part under-reports the week by five or six
 * kilometres, and the weekly cap is the thing standing between a runner and an injury.
 */
object Workouts {

    fun rest(reason: String) = Workout(WorkoutType.Rest, 0.0, emptyList(), reason)

    fun easy(meters: Double, paces: TrainingPaces, reason: String) = Workout(
        type = WorkoutType.Easy,
        totalMeters = meters,
        steps = listOf(WorkoutStep("Easy", distanceM = meters, paceSecPerKm = paces.easySecPerKm)),
        reason = reason,
    )

    fun recovery(meters: Double, paces: TrainingPaces, reason: String) = Workout(
        type = WorkoutType.Recovery,
        totalMeters = meters,
        steps = listOf(
            // The slow end of easy, and only the slow end. A recovery run run at the fast
            // end of easy is an easy run, and the day it was meant to repair is gone.
            WorkoutStep(
                "Very easy",
                distanceM = meters,
                paceSecPerKm = paces.easySecPerKm.endInclusive..(paces.easySecPerKm.endInclusive + 30.0),
            )
        ),
        reason = reason,
    )

    fun longRun(meters: Double, paces: TrainingPaces, reason: String) = Workout(
        type = WorkoutType.Long,
        totalMeters = meters,
        steps = listOf(WorkoutStep("Easy", distanceM = meters, paceSecPerKm = paces.easySecPerKm)),
        reason = reason,
    )

    /** Easy for two thirds, marathon pace for the last third. A long run's cheap upgrade. */
    fun progression(meters: Double, paces: TrainingPaces, reason: String): Workout {
        val fast = meters / 3.0
        return Workout(
            type = WorkoutType.Progression,
            totalMeters = meters,
            steps = listOf(
                WorkoutStep("Easy", distanceM = meters - fast, paceSecPerKm = paces.easySecPerKm),
                WorkoutStep("Finish steady", distanceM = fast, paceSecPerKm = single(paces.marathonSecPerKm)),
            ),
            reason = reason,
        )
    }

    fun strides(meters: Double, paces: TrainingPaces, reason: String): Workout {
        val reps = 6
        val strideMs = 20_000L
        val strideMeters = metersAt(paces.repetitionSecPerKm, strideMs) * reps
        return Workout(
            type = WorkoutType.Strides,
            totalMeters = meters,
            steps = listOf(
                WorkoutStep("Easy", distanceM = meters - strideMeters, paceSecPerKm = paces.easySecPerKm),
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
        val trim = trimFor(workMeters)
        return Workout(
            type = WorkoutType.Steady,
            totalMeters = workMeters + 2 * trim,
            qualityMeters = workMeters,
            steps = listOf(
                warmUp(trim, paces),
                WorkoutStep("Steady", distanceM = workMeters, paceSecPerKm = single(paces.marathonSecPerKm)),
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
        val duration = durationAt(paces.thresholdSecPerKm, workMeters)
            .coerceIn(12 * 60_000L, 40 * 60_000L)
        val meters = metersAt(paces.thresholdSecPerKm, duration)
        val trim = trimFor(meters)
        return Workout(
            type = WorkoutType.Tempo,
            totalMeters = meters + 2 * trim,
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
        val repMs = totalMs / reps
        val repMeters = metersAt(paces.thresholdSecPerKm, repMs)
        val meters = repMeters * reps
        val trim = trimFor(meters)
        return Workout(
            type = WorkoutType.CruiseIntervals,
            totalMeters = meters + reps * metersAt(paces.easySecPerKm.endInclusive, 2 * 60_000L) + 2 * trim,
            qualityMeters = meters,
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
        val repMs = totalMs / reps
        val repMeters = metersAt(paces.intervalSecPerKm, repMs)
        val meters = repMeters * reps
        val trim = trimFor(meters)
        return Workout(
            type = WorkoutType.Intervals,
            // The jog is at the slow end of easy and is real distance on the legs.
            totalMeters = meters + reps * metersAt(paces.easySecPerKm.endInclusive, repMs) + 2 * trim,
            qualityMeters = meters,
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
        val repMeters = metersAt(paces.intervalSecPerKm, repMs)
        val reps = (workMeters / repMeters).toInt().coerceIn(4, 10)
        val meters = repMeters * reps
        val trim = trimFor(meters)
        return Workout(
            type = WorkoutType.Hills,
            totalMeters = meters * 2 + 2 * trim,
            qualityMeters = meters,
            steps = listOf(
                warmUp(trim, paces),
                WorkoutStep(
                    "Uphill hard, jog down",
                    repeats = reps,
                    durationMs = repMs,
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
        val repMeters = metersAt(paces.intervalSecPerKm, repMs)
        val reps = (workMeters / repMeters).toInt().coerceIn(4, 10)
        val meters = repMeters * reps
        val trim = trimFor(meters)
        return Workout(
            type = WorkoutType.Fartlek,
            totalMeters = meters + reps * metersAt(paces.easySecPerKm.endInclusive, repMs) + 2 * trim,
            qualityMeters = meters,
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
        return Workout(
            type = WorkoutType.Repetitions,
            totalMeters = meters + reps * 400.0 + 2 * trim,
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

    private fun warmUp(meters: Double, paces: TrainingPaces) =
        WorkoutStep("Warm up", distanceM = meters, paceSecPerKm = paces.easySecPerKm)

    private fun coolDown(meters: Double, paces: TrainingPaces) =
        WorkoutStep("Cool down", distanceM = meters, paceSecPerKm = paces.easySecPerKm)

    /** Warm-up and cool-down scale with the session, within reason. */
    private fun trimFor(workMeters: Double): Double = (workMeters * 0.5).coerceIn(1_200.0, 2_500.0)

    fun metersAt(paceSecPerKm: Double, durationMs: Long): Double =
        if (paceSecPerKm <= 0.0) 0.0 else durationMs / 1000.0 / paceSecPerKm * 1000.0

    fun durationAt(paceSecPerKm: Double, meters: Double): Long =
        (meters / 1000.0 * paceSecPerKm * 1000.0).roundToLong()

    private fun single(pace: Double) = pace..pace

    /** Recovery is run at the slow end of easy, or it is not recovery. */
    private fun jog(paces: TrainingPaces) =
        paces.easySecPerKm.endInclusive..(paces.easySecPerKm.endInclusive + 30.0)
}

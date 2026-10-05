package io.snailrun.domain.coach

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * One session laid out for two runners of different speeds.
 *
 * [her] is what the app counts the runner through; [his] is what the partner reads off
 * her phone or the shared text. The two have the same steps in the same order — the same
 * session — so step *i* of one is always step *i* of the other.
 */
data class TogetherPlan(
    val her: Workout,
    val his: Workout,
    /** Steps the two run side by side, at one pace. */
    val sharedSteps: Set<Int>,
    /** Rep steps whose jogs are run side by side. */
    val sharedRecoveries: Set<Int>,
    /**
     * Running added to a step so the two arrive together, keyed by step index.
     *
     * For a rep step it is added to every jog; for any other step, once. Kept apart from
     * the step itself so it can be said out loud — "jog back 40 s to meet her" is the
     * instruction, and a recovery that silently reads 3:40 instead of 3:00 is a typo.
     */
    val herExtraMs: Map<Int, Long>,
    val hisExtraMs: Map<Int, Long>,
    /**
     * How far ahead the faster runner is when a step they ran apart ends, keyed by step.
     *
     * A rep run for time starts together and ends apart: same minutes, different metres.
     * The clocks still agree, so nothing has to be added — the one ahead turns back, and
     * the two meet in the jog. This is how far there is to come back.
     */
    val spreadM: Map<Int, Double>,
    val togetherMs: Long,
    val totalMs: Long,
    /** How many times they split up and run back into each other. */
    val regroups: Int,
    /** How far apart they finish, where the session ends on something they cannot share. */
    val finishGapMs: Long,
)

/**
 * A runner's session, at a partner's speed.
 *
 * Steps carry absolute paces, not intensities, so each pace is read back to the fraction
 * of the runner's VDOT it was written at and then forward again at the partner's. That
 * covers every intensity the builders use, and the jog bands either side of them, with
 * nothing in [Workouts] having to say which one it meant.
 */
object PartnerPlan {

    /**
     * The fraction of VDOT under which a step is easy enough to run side by side.
     *
     * Easy tops out at [Vdot.EASY_HIGH]; marathon pace starts at [Vdot.MARATHON]. This sits
     * between them, so rounding inside a band can never tip an easy step into work.
     */
    const val EASY_FRACTION = 0.76

    /**
     * How far below their easy band the faster runner will drop to keep company.
     *
     * The same thirty seconds a recovery run sits under easy (see [Workouts.recovery]):
     * slow enough to be a different run, not so slow it becomes a walk.
     */
    const val SHARED_ALLOWANCE_SEC_PER_KM = 30.0

    /** The same intensity, at a different VDOT. */
    fun translate(paceSecPerKm: Double, fromVdot: Double, toVdot: Double): Double {
        if (paceSecPerKm <= 0.0 || fromVdot <= 0.0 || toVdot <= 0.0) return paceSecPerKm
        val fraction = Vdot.oxygenCost(60_000.0 / paceSecPerKm) / fromVdot
        return Vdot.paceSecPerKm(toVdot, fraction)
    }

    fun translate(
        range: ClosedFloatingPointRange<Double>,
        fromVdot: Double,
        toVdot: Double,
    ): ClosedFloatingPointRange<Double> =
        translate(range.start, fromVdot, toVdot)..translate(range.endInclusive, fromVdot, toVdot)

    /** The same distances and durations, at his paces. */
    fun mirror(workout: Workout, herVdot: Double, hisVdot: Double): Workout {
        if (!workout.type.isRun) return workout
        val steps = workout.steps.map { step ->
            step.copy(
                paceSecPerKm = step.paceSecPerKm?.let { translate(it, herVdot, hisVdot) },
                recoveryPaceSecPerKm = step.recoveryPaceSecPerKm?.let { translate(it, herVdot, hisVdot) },
            )
        }
        return priced(workout.copy(steps = steps))
    }

    /**
     * The same session, timed so the two keep meeting.
     *
     * Two clocks run down the steps, one each. Wherever the session allows company —
     * easy running and the jogs between reps — the two share a pace, and whoever is
     * ahead runs the difference first, so they set off on the next thing together. Where
     * it does not, each runs their own pace for the same time, which is how a tempo ends
     * with both of them at the same lamp post.
     *
     * The reps themselves are never touched: same count, same distance, own pace. The
     * faster runner finishes each one early and spends the gap jogging back to meet the
     * other, which is the extra running a stronger partner wants anyway.
     */
    fun together(workout: Workout, herVdot: Double, hisVdot: Double): TogetherPlan {
        val mirrored = mirror(workout, herVdot, hisVdot)
        if (!workout.type.isRun) {
            return TogetherPlan(workout, mirrored, emptySet(), emptySet(), emptyMap(), emptyMap(), emptyMap(), 0L, 0L, 0, 0L)
        }
        val herSteps = mutableListOf<WorkoutStep>()
        val hisSteps = mutableListOf<WorkoutStep>()
        val sharedSteps = mutableSetOf<Int>()
        val sharedRecoveries = mutableSetOf<Int>()
        val herExtra = mutableMapOf<Int, Long>()
        val hisExtra = mutableMapOf<Int, Long>()
        val spread = mutableMapOf<Int, Double>()

        // Her clock minus his. Positive: he is ahead, with that much to spare.
        var gap = 0L
        var herClock = 0L
        var togetherMs = 0L
        var regroups = 0
        var apart = false
        // Metres between them when they last split, still to be closed before they are
        // side by side again.
        var pendingSpreadM = 0.0

        workout.steps.forEachIndexed { index, mine ->
            val his = mirrored.steps.getOrElse(index) { mine }
            val repeats = mine.repeats.coerceAtLeast(1)

            if (repeats == 1 && isEasy(mine.paceSecPerKm, herVdot) && his.paceSecPerKm != null) {
                val shared = sharedPace(mine.paceSecPerKm!!, his.paceSecPerKm)
                val herBase = if (shared != null) mine.copy(paceSecPerKm = shared) else mine
                val herMs = durationOf(herBase)
                val hisBase = if (shared != null) his.copy(paceSecPerKm = shared) else timed(his, herMs)

                // Whoever arrived first runs the difference before the two set off.
                val extra = abs(gap)
                if (gap > 0 && extra > 0) hisExtra[index] = extra
                if (gap < 0 && extra > 0) herExtra[index] = extra
                herSteps += if (gap < 0) extended(herBase, extra) else herBase
                hisSteps += if (gap > 0) extended(hisBase, extra) else hisBase
                herClock += herMs + (herExtra[index] ?: 0L)
                gap = 0L

                if (shared != null) {
                    sharedSteps += index
                    togetherMs += (herMs - meetingMs(pendingSpreadM, shared)).coerceAtLeast(0L)
                    if (apart) regroups++
                    apart = false
                    pendingSpreadM = 0.0
                } else {
                    apart = true
                    pendingSpreadM = noteSpread(spread, index, herBase, hisBase, herMs)
                }
                return@forEachIndexed
            }

            if (repeats == 1) {
                // Work that cannot be shared: each at their own pace, for her time.
                val herMs = durationOf(mine)
                herSteps += mine
                val hisStep = if (herMs > 0) timed(his, herMs) else his
                hisSteps += hisStep
                herClock += herMs
                apart = true
                pendingSpreadM = noteSpread(spread, index, mine, hisStep, herMs)
                return@forEachIndexed
            }

            // Reps. Same count, same distance or time, own pace.
            val herRep = durationOf(mine)
            val hisRep = durationOf(his)
            // Exact, not rounded: a few seconds lost on every rep add up to a rep start
            // they no longer share. The screen rounds it; the clocks do not.
            val delta = if (herRep > 0 && hisRep > 0) herRep - hisRep else 0L
            val hasRecovery = mine.recoveryMs != null || mine.recoveryM != null
            if (!hasRecovery) {
                herSteps += mine
                hisSteps += his
                herClock += herRep * repeats
                gap += delta * repeats
                apart = true
                pendingSpreadM = noteSpread(spread, index, mine, his, herRep)
                return@forEachIndexed
            }

            val shared = mine.recoveryPaceSecPerKm?.let { a ->
                his.recoveryPaceSecPerKm?.let { b -> sharedPace(a, b) }
            }
            val herJogPace = shared ?: mine.recoveryPaceSecPerKm
            val jogMs = mine.recoveryMs ?: durationAt(herJogPace, mine.recoveryM ?: 0.0)
            var herStep = if (shared != null) mine.copy(recoveryPaceSecPerKm = shared) else mine
            var hisStep = if (shared != null) his.copy(recoveryPaceSecPerKm = shared) else his
            // A jog he cannot share is still one he finishes when she does, so it is
            // timed rather than measured: his metres at his pace would end elsewhere.
            if (shared == null && his.recoveryMs == null) hisStep = hisStep.copy(recoveryMs = jogMs, recoveryM = null)

            if (delta > 0) {
                hisExtra[index] = delta
                hisStep = hisStep.copy(recoveryMs = jogMs + delta, recoveryM = null)
            } else if (delta < 0) {
                herExtra[index] = -delta
                herStep = herStep.copy(recoveryMs = jogMs - delta, recoveryM = null)
            }
            herSteps += herStep
            hisSteps += hisStep

            herClock += herRep * repeats + (jogMs + (herExtra[index] ?: 0L)) * (repeats - 1)
            // Every jog closes the gap its rep opened, except after the last rep: there
            // is no jog there, so that one is carried to the next thing they can share.
            gap += delta
            val repSpread = noteSpread(spread, index, mine, his, herRep)
            if (shared != null) {
                sharedRecoveries += index
                togetherMs += (jogMs - meetingMs(repSpread, shared)).coerceAtLeast(0L) * (repeats - 1)
                regroups += repeats - 1
            }
            apart = true
            pendingSpreadM = repSpread
        }

        return TogetherPlan(
            her = priced(workout.copy(steps = herSteps)),
            his = priced(mirrored.copy(steps = hisSteps)),
            sharedSteps = sharedSteps,
            sharedRecoveries = sharedRecoveries,
            herExtraMs = herExtra,
            hisExtraMs = hisExtra,
            spreadM = spread,
            togetherMs = togetherMs,
            totalMs = herClock,
            regroups = regroups,
            finishGapMs = abs(gap),
        )
    }

    /**
     * One pace two runners can hold together, or null.
     *
     * Their bands overlapping is the easy case: run in the overlap. Otherwise the faster
     * runner comes down to the slower one's quickest, as long as that is no more than
     * [SHARED_ALLOWANCE_SEC_PER_KM] below their own band. Further than that and running
     * together is a favour, not a session — so they run apart and meet at the end.
     */
    fun sharedPace(
        a: ClosedFloatingPointRange<Double>,
        b: ClosedFloatingPointRange<Double>,
    ): ClosedFloatingPointRange<Double>? {
        val low = maxOf(a.start, b.start)
        val high = minOf(a.endInclusive, b.endInclusive)
        if (low <= high) return low..high
        val (faster, slower) = if (a.endInclusive < b.start) a to b else b to a
        val reach = faster.endInclusive + SHARED_ALLOWANCE_SEC_PER_KM
        if (slower.start > reach) return null
        return slower.start..minOf(slower.endInclusive, reach)
    }

    /** Whether a step is easy running, read off the fraction of VDOT its pace is. */
    fun isEasy(pace: ClosedFloatingPointRange<Double>?, vdot: Double): Boolean {
        if (pace == null || vdot <= 0.0) return false
        val mid = (pace.start + pace.endInclusive) / 2.0
        if (mid <= 0.0) return false
        return Vdot.oxygenCost(60_000.0 / mid) / vdot <= EASY_FRACTION
    }

    /**
     * Records how far apart a step run for [ms] leaves them, and returns it.
     *
     * Read off the distances where the step has them and the paces where it does not —
     * a step run for the same distance ends at the same place, whoever got there first.
     */
    private fun noteSpread(
        spread: MutableMap<Int, Double>,
        index: Int,
        mine: WorkoutStep,
        his: WorkoutStep,
        ms: Long,
    ): Double {
        if (mine.durationMs == null && his.durationMs == null) return 0.0
        val herM = mine.paceSecPerKm?.let { Workouts.metersAt(mid(it), ms) } ?: return 0.0
        val hisM = his.paceSecPerKm?.let { Workouts.metersAt(mid(it), ms) } ?: return 0.0
        val apart = (abs(hisM - herM) / 10.0).roundToLong() * 10.0
        if (apart > 0.0) spread[index] = apart
        return apart
    }

    /** How long two runners [meters] apart take to meet, jogging towards each other. */
    private fun meetingMs(meters: Double, pace: ClosedFloatingPointRange<Double>): Long =
        if (meters <= 0.0) 0L else Workouts.durationAt(mid(pace), meters / 2.0)

    private fun mid(range: ClosedFloatingPointRange<Double>) = (range.start + range.endInclusive) / 2.0

    /** How long one rep of a step takes, or zero where nothing says. */
    fun durationOf(step: WorkoutStep): Long =
        step.durationMs ?: step.distanceM?.let { durationAt(step.paceSecPerKm, it) } ?: 0L

    private fun durationAt(pace: ClosedFloatingPointRange<Double>?, meters: Double): Long {
        if (pace == null || meters <= 0.0) return 0L
        return Workouts.durationAt((pace.start + pace.endInclusive) / 2.0, meters)
    }

    /** The step run for [ms] instead, with the distance that is expected to cover. */
    private fun timed(step: WorkoutStep, ms: Long): WorkoutStep {
        val pace = step.paceSecPerKm
        val meters = pace?.let { Round.repMeters(Workouts.metersAt((it.start + it.endInclusive) / 2.0, ms)) }
        return step.copy(durationMs = ms, distanceM = meters ?: step.distanceM)
    }

    /** The step, with [extraMs] more of it, in whichever dimension it is prescribed in. */
    private fun extended(step: WorkoutStep, extraMs: Long): WorkoutStep {
        if (extraMs <= 0L) return step
        step.durationMs?.let { return step.copy(durationMs = it + extraMs) }
        val distance = step.distanceM ?: return step
        val pace = step.paceSecPerKm ?: return step
        val more = Workouts.metersAt((pace.start + pace.endInclusive) / 2.0, extraMs)
        return step.copy(distanceM = distance + Round.repMeters(more))
    }

    private fun priced(workout: Workout): Workout = workout.copy(totalMeters = Workouts.metersOf(workout))
}

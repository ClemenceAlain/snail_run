package io.snailrun.domain.coach

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Daniels and Gilbert's equations: a distance and a time in, an oxygen cost out, and
 * back again.
 *
 * Two curves fitted to laboratory data and published in the 1970s. The first says what a
 * given speed costs in millilitres of oxygen per kilogram per minute; the second says
 * what fraction of a runner's maximum they can hold for a given length of time. Divide
 * one by the other and a race result becomes a VO2max — VDOT, in Daniels' notation — and
 * every training pace falls out of it as a percentage.
 *
 * This is arithmetic, not a model: the same numbers go in and the same numbers come out,
 * on any phone, forever, and the tests below check them against Daniels' own published
 * table. That is the whole reason there is no machine learning anywhere near the coach.
 * A pace that is wrong by fifteen seconds a kilometre is an injury, and a guess that
 * cannot be checked is not worth the megabytes it would cost to ship.
 */
object Vdot {

    /**
     * Percentages of VDOT each training intensity sits at.
     *
     * Verified against Daniels' table at VDOT 50, which is where a 20:00 5 k lands: E
     * 5:00–5:38, M 4:30, T 4:15, I 3:54, R 3:39 per kilometre. Every one of those is
     * within a couple of seconds of what these fractions produce.
     */
    const val EASY_LOW = 0.62
    const val EASY_HIGH = 0.72
    const val MARATHON = 0.82
    const val THRESHOLD = 0.88
    const val INTERVAL = 0.98
    const val REPETITION = 1.06

    /** Oxygen cost of running at [velocityMPerMin], ml/kg/min. */
    fun oxygenCost(velocityMPerMin: Double): Double =
        -4.60 + 0.182258 * velocityMPerMin + 0.000104 * velocityMPerMin * velocityMPerMin

    /** The fraction of VO2max a runner can hold for [minutes]. Falls as the effort lengthens. */
    fun percentOfMax(minutes: Double): Double =
        0.8 + 0.1894393 * exp(-0.012778 * minutes) + 0.2989558 * exp(-0.1932605 * minutes)

    /** VDOT implied by covering [meters] in [durationMs]. Null if either is nonsense. */
    fun fromEffort(meters: Double, durationMs: Long): Double? {
        if (meters <= 0.0 || durationMs <= 0L) return null
        val minutes = durationMs / 60_000.0
        val cost = oxygenCost(meters / minutes)
        val fraction = percentOfMax(minutes)
        if (cost <= 0.0 || fraction <= 0.0) return null
        return cost / fraction
    }

    /**
     * The speed that costs [oxygenCost] — the positive root of the cost quadratic.
     *
     * The negative root is a speed of about -1800 m/min, which is arithmetic rather than
     * running, so it is discarded without ceremony.
     */
    fun velocityFor(oxygenCost: Double): Double {
        val a = 0.000104
        val b = 0.182258
        val c = -(oxygenCost + 4.60)
        val discriminant = b * b - 4 * a * c
        if (discriminant <= 0.0) return 0.0
        return (-b + sqrt(discriminant)) / (2 * a)
    }

    /** Seconds per kilometre at [fraction] of [vdot]. A velocity in m/min is 60000/pace. */
    fun paceSecPerKm(vdot: Double, fraction: Double): Double {
        val velocity = velocityFor(vdot * fraction)
        return if (velocity <= 0.0) 0.0 else 60_000.0 / velocity
    }

    /**
     * How long [meters] would take at [vdot] — the race prediction.
     *
     * Solved by bisection rather than algebraically, because the time appears inside two
     * exponentials as well as in the speed, and there is no closed form. VDOT falls
     * monotonically as the time rises, so sixty halvings land well inside a second.
     *
     * The slow end of the bracket scales with the distance rather than being a fixed ten
     * hours. The cost curve turns negative below about 25 m/min — arithmetic, not
     * running — and ten hours over a kilometre is comfortably inside that, so a fixed
     * bracket makes the prediction for short distances fail outright.
     */
    fun timeMsFor(vdot: Double, meters: Double): Long? {
        if (vdot <= 0.0 || meters <= 0.0) return null
        var low = 0.5
        var high = (meters / 50.0).coerceIn(2.0, 1_200.0)
        if (fromEffort(meters, minutesToMs(low)).let { it == null || it < vdot }) return null
        if (fromEffort(meters, minutesToMs(high)).let { it == null || it > vdot }) return null
        repeat(60) {
            val mid = (low + high) / 2
            val implied = fromEffort(meters, minutesToMs(mid)) ?: return null
            if (implied > vdot) low = mid else high = mid
        }
        return minutesToMs((low + high) / 2)
    }

    private fun minutesToMs(minutes: Double): Long = (minutes * 60_000.0).toLong()
}

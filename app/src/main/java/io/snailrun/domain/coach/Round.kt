package io.snailrun.domain.coach

import kotlin.math.roundToLong

/**
 * Numbers a runner can actually act on.
 *
 * The arithmetic in this package divides budgets by rep counts and multiplies paces by
 * durations, and it lands on 913 m and 6.31 km. Both are honest and neither is a
 * prescription: nobody runs 913 metres, and a coach that asks for it is telling the
 * runner the number matters to a precision it does not have.
 *
 * So every figure that reaches the runner is snapped to a grid they could hit — and
 * snapped once, at the point the session is built, rather than at the point it is
 * printed. A plan that displays 900 m and counts you through 913 is worse than either.
 */
object Round {

    /**
     * Inside a rep: 50 m, the shortest distance a runner can pick out on a road.
     *
     * Never to zero. A rep budget small enough to round away is still a rep, and a
     * session with a 0 m step would be counted through instantly.
     */
    fun repMeters(meters: Double): Double = snap(meters, 50.0)

    /** A whole block — a warm-up, an easy run, a long run: 100 m. */
    fun blockMeters(meters: Double): Double = snap(meters, 100.0)

    /**
     * A derived duration, to something a watch face reads cleanly.
     *
     * Whole minutes once a step is long enough to be described in them, and quarter
     * minutes below that: "4 × 4 min" rather than "4 × 4:37", and "45 s" rather than
     * "47 s". Only ever applied to a duration that fell out of a division — a 20-second
     * stride is 20 seconds because somebody chose 20, and rounding it would be rewriting
     * the session rather than tidying it.
     */
    fun stepMs(millis: Long): Long =
        if (millis >= 120_000L) snapMs(millis, 60_000L) else snapMs(millis, 15_000L)

    private fun snap(value: Double, grid: Double): Double =
        if (value <= 0.0) 0.0 else ((value / grid).roundToLong() * grid).coerceAtLeast(grid)

    private fun snapMs(value: Long, grid: Long): Long =
        if (value <= 0L) 0L else (Math.round(value.toDouble() / grid) * grid).coerceAtLeast(grid)
}

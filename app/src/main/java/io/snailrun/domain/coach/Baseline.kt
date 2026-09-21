package io.snailrun.domain.coach

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * What the runner says they were doing before the app was watching.
 *
 * The coach reads history, and a new install has none, so the first four weeks it plans
 * are the ones it knows least about: a 50 km-a-week runner is handed a base week of three
 * easy runs, and the only way out of it is to spend a month proving what they already
 * knew. This is the short way out — four questions, answered once.
 *
 * It is a statement about the past, not a promise about the future, and it is treated as
 * exactly that: the figures describe the four weeks before [recordedOn] and they age out
 * of the coach's windows on their own, day by day, like real runs do. Nothing here has to
 * be cleared, corrected or expired, and a runner who fills it in and then stops running
 * for a month gets the same "you have not run in a fortnight" week as anybody else.
 */
data class CoachBaseline(
    /** Days a week they were running. Zero is allowed: it means starting from nothing. */
    val runsPerWeek: Int,
    val weeklyMeters: Double,
    val longestRunMeters: Double,
    /** A recent race or time trial, if there was one. All three or none. */
    val raceDistanceMeters: Int? = null,
    val raceDurationMs: Long? = null,
    val raceDateEpochDay: Long? = null,
    /** The day the questions were answered. Everything above is relative to it. */
    val recordedOnEpochDay: Long,
) {
    val recordedOn: LocalDate get() = LocalDate.ofEpochDay(recordedOnEpochDay)

    val race: RecentEffort?
        get() {
            val distance = raceDistanceMeters ?: return null
            val duration = raceDurationMs ?: return null
            val day = raceDateEpochDay ?: return null
            if (distance <= 0 || duration <= 0) return null
            return RecentEffort(distance, duration, LocalDate.ofEpochDay(day))
        }
}

/**
 * Turns the answers into the runs the coach would have seen, had it been there.
 *
 * Synthetic runs rather than a special case inside the planner, so every rule already
 * written keeps applying unchanged: the ramp, the acute-to-chronic ratio, the long-run
 * cap, the frequency the runner is held to. A week planned off a baseline is planned by
 * exactly the arithmetic that plans every other week, and there is no second code path
 * that could disagree with the first.
 *
 * They occupy only the four weeks before the questions were answered, and only the days
 * no real run already sits on. So they thin out as the runner records real runs on top of
 * them, and they have left the window entirely a month later — which is the point at
 * which the app knows more about the runner than the runner just told it.
 */
object Baselines {

    /** How far back the answers describe. The same window the load summary reads. */
    const val WINDOW_DAYS = 28L

    /**
     * Where a week's runs fall when nobody has said.
     *
     * The long run on Sunday, then the days a Tuesday-and-Thursday runner uses. It
     * matches the order the planner itself falls back to, so the week the coach writes
     * lands on the days its own invented history was run on.
     */
    private val DayPreference = listOf(
        DayOfWeek.SUNDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.SATURDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.MONDAY,
        DayOfWeek.FRIDAY,
    )

    /** A run has to be worth calling one. */
    private const val MIN_RUN_M = 2_000.0

    fun syntheticRuns(baseline: CoachBaseline, existing: List<CoachRun>): List<CoachRun> {
        val days = baseline.runsPerWeek.coerceIn(0, 7)
        if (days == 0 || baseline.weeklyMeters <= 0.0) return emptyList()

        val chosen = DayPreference.take(days).toSet()
        val longRunDay = DayOfWeek.SUNDAY.takeIf { it in chosen } ?: DayPreference.first()

        // The long run is capped at half the week before the rest is shared out, so a
        // runner who reports a 20 km long run inside a 25 km week is not handed a plan
        // built on four days of two kilometres.
        val longRun = if (days == 1) {
            baseline.weeklyMeters
        } else {
            baseline.longestRunMeters.coerceAtMost(baseline.weeklyMeters * 0.5).coerceAtLeast(0.0)
        }
        val others = days - 1
        val share = if (others == 0) 0.0 else (baseline.weeklyMeters - longRun) / others

        val ran = existing.map { it.date }.toSet()
        val last = baseline.recordedOn.minusDays(1)

        return (0 until WINDOW_DAYS)
            .map { last.minusDays(it) }
            .filter { it.dayOfWeek in chosen && it !in ran }
            .map { date ->
                val meters = if (date.dayOfWeek == longRunDay) longRun else share
                CoachRun(
                    date = date,
                    meters = meters.coerceAtLeast(MIN_RUN_M),
                    // No moving time: nothing reads it but the progress chart, and an
                    // invented duration would put an invented pace on a real screen.
                    movingMs = 0,
                )
            }
    }
}

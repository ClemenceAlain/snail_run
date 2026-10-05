package io.snailrun.domain.coach

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

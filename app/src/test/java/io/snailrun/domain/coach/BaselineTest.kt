package io.snailrun.domain.coach

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the runner reported before the app: only the race still counts, as an effort. */
class BaselineTest {

    private val today = LocalDate.of(2026, 9, 21)

    private fun baseline(
        runsPerWeek: Int = 4,
        weeklyMeters: Double = 40_000.0,
        longestRunMeters: Double = 15_000.0,
        raceDistanceMeters: Int? = null,
        raceDurationMs: Long? = null,
        raceDateEpochDay: Long? = null,
    ) = CoachBaseline(
        runsPerWeek = runsPerWeek,
        weeklyMeters = weeklyMeters,
        longestRunMeters = longestRunMeters,
        raceDistanceMeters = raceDistanceMeters,
        raceDurationMs = raceDurationMs,
        raceDateEpochDay = raceDateEpochDay,
        recordedOnEpochDay = today.toEpochDay(),
    )

    @Test
    fun `the reported race prices the training paces`() {
        val race = baseline(
            raceDistanceMeters = 10_000,
            raceDurationMs = 45 * 60_000L,
            raceDateEpochDay = today.minusWeeks(2).toEpochDay(),
        ).race

        val fitness = Fitness.estimate(listOfNotNull(race), today)!!
        // A 45:00 10 km is about VDOT 44, and it is trusted: 10 km is a real effort.
        assertEquals(44.0, fitness.vdot, 1.5)
        assertEquals(Confidence.Solid, fitness.confidence)
    }

    @Test
    fun `a race with no time is no race at all`() {
        assertEquals(null, baseline(raceDistanceMeters = 10_000).race)
    }
}

package io.snailrun.domain.coach

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `four weeks of the week they described`() {
        val runs = Baselines.syntheticRuns(baseline(), emptyList())

        assertEquals(16, runs.size)
        // Four weeks at 40 km, to within the long run's own rounding.
        assertEquals(160_000.0, runs.sumOf { it.meters }, 1_000.0)
        assertTrue(runs.all { it.date < today })
        assertTrue(runs.all { it.date >= today.minusDays(28) })
    }

    @Test
    fun `the long run lands on a Sunday`() {
        val runs = Baselines.syntheticRuns(baseline(), emptyList())
        val longest = runs.maxBy { it.meters }
        assertEquals(DayOfWeek.SUNDAY, longest.date.dayOfWeek)
        assertEquals(15_000.0, longest.meters, 1.0)
    }

    @Test
    fun `a long run bigger than half the week is cut down to it`() {
        // 20 km claimed inside a 25 km week would leave the other three days at
        // under two kilometres each.
        val runs = Baselines.syntheticRuns(
            baseline(weeklyMeters = 25_000.0, longestRunMeters = 20_000.0),
            emptyList(),
        )
        assertEquals(12_500.0, runs.maxOf { it.meters }, 1.0)
    }

    @Test
    fun `one run a week is that week`() {
        val runs = Baselines.syntheticRuns(
            baseline(runsPerWeek = 1, weeklyMeters = 12_000.0),
            emptyList(),
        )
        assertEquals(4, runs.size)
        assertTrue(runs.all { it.meters == 12_000.0 })
    }

    @Test
    fun `a day with a real run on it is left alone`() {
        val sunday = today.minusDays(7).with(DayOfWeek.SUNDAY)
        val real = listOf(CoachRun(sunday, 8_000.0, 2_400_000))

        val runs = Baselines.syntheticRuns(baseline(), real)

        assertTrue(runs.none { it.date == sunday })
    }

    @Test
    fun `nothing is invented for someone who says they have not been running`() {
        assertTrue(Baselines.syntheticRuns(baseline(runsPerWeek = 0), emptyList()).isEmpty())
    }

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

    @Test
    fun `the first week is planned for the runner who answered, not for a beginner`() {
        val plan = WeekPlanner.plan(
            load = TrainingLoad.summarise(
                runs = Baselines.syntheticRuns(baseline(), emptyList()),
                today = today,
            ),
            fitness = null,
            goal = null,
            weekStart = today.plusDays(1),
        )

        // Without the answers this would be the 15 km base week. With them it is the
        // week they have been running, plus the ramp the rules allow.
        assertTrue("planned ${plan.plannedMeters}", plan.plannedMeters > 35_000.0)
        assertEquals(4, plan.days.count { it.workout.type != WorkoutType.Rest })
    }
}

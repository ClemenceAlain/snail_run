package io.snailrun.domain.coach

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceGoalTest {

    private val today = LocalDate.of(2026, 9, 18)
    private val weekStart = LocalDate.of(2026, 9, 21) // a Monday

    private val fitness = Fitness.estimate(
        listOf(RecentEffort(5_000, 20 * 60_000L, today.minusDays(7))),
        today,
    )!!

    private fun goal(weeksOut: Long, meters: Int = 10_000) =
        RaceGoal(meters, weekStart.plusWeeks(weeksOut))

    @Test
    fun `the phases fall on their week boundaries`() {
        assertEquals(Phase.Base, Races.phaseFor(goal(20), weekStart))
        assertEquals(Phase.Base, Races.phaseFor(goal(13), weekStart))
        assertEquals(Phase.Build, Races.phaseFor(goal(12), weekStart))
        assertEquals(Phase.Build, Races.phaseFor(goal(5), weekStart))
        assertEquals(Phase.Peak, Races.phaseFor(goal(4), weekStart))
        assertEquals(Phase.Peak, Races.phaseFor(goal(3), weekStart))
        assertEquals(Phase.Taper, Races.phaseFor(goal(2), weekStart))
        assertEquals(Phase.Taper, Races.phaseFor(goal(0), weekStart))
    }

    @Test
    fun `only the taper touches the volume`() {
        assertEquals(1.0, Races.volumeFactor(goal(20), weekStart), 0.0)
        assertEquals(1.0, Races.volumeFactor(goal(8), weekStart), 0.0)
        assertEquals(1.0, Races.volumeFactor(goal(3), weekStart), 0.0)
        assertEquals(0.6, Races.volumeFactor(goal(2), weekStart), 0.0)
        assertEquals(0.4, Races.volumeFactor(goal(0), weekStart), 0.0)
    }

    @Test
    fun `a faster runner is predicted a faster race`() {
        val slower = Fitness.estimate(
            listOf(RecentEffort(5_000, 24 * 60_000L, today)), today,
        )!!
        val fast = Races.predictedTimeMs(goal(6), fitness)!!
        val slow = Races.predictedTimeMs(goal(6), slower)!!
        assertTrue(fast < slow)
    }

    @Test
    fun `a twenty minute five k runner is predicted about forty-two for ten`() {
        val predicted = Races.predictedTimeMs(goal(6), fitness)!!
        assertEquals(41.6, predicted / 60_000.0, 0.7)
    }

    @Test
    fun `no fitness means no prediction rather than a round number`() {
        assertNull(Races.predictedTimeMs(goal(6), null))
    }

    // ---- what the phase does to the week ----------------------------------------------

    private val days = listOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.FRIDAY, DayOfWeek.SUNDAY,
    )

    private fun planFor(goal: RaceGoal?): WeekPlan {
        val runs = (0 until 6).flatMap { back ->
            days.mapIndexed { i, day ->
                val date = today.minusWeeks(back.toLong()).with(day)
                CoachRun(date, if (i == days.lastIndex) 14_000.0 else 6_500.0, 0)
            }
        }.filter { it.date <= today }

        return WeekPlanner.plan(
            load = TrainingLoad.summarise(runs, today, DayOfWeek.MONDAY),
            fitness = fitness,
            goal = goal,
            weekStart = weekStart,
        )
    }

    @Test
    fun `a taper cuts the volume and keeps the hard days`() {
        val normal = planFor(null)
        val taper = planFor(goal(1))

        assertEquals(Phase.Taper, taper.phase)
        assertTrue(
            "${taper.plannedMeters} against ${normal.plannedMeters}",
            taper.plannedMeters < normal.plannedMeters * 0.8,
        )
        assertTrue(taper.days.any { it.workout.type.isQuality })
    }

    @Test
    fun `a build phase sharpens where a base phase does not`() {
        val base = planFor(goal(20)).days.map { it.workout.type }
        val build = planFor(goal(8)).days.map { it.workout.type }
        assertTrue("base was $base", base.none { it == WorkoutType.Intervals })
        assertTrue("build was $build", build.any { it in setOf(WorkoutType.Intervals, WorkoutType.Fartlek, WorkoutType.Hills) })
    }

    @Test
    fun `the peak phase rehearses the race`() {
        assertTrue(planFor(goal(3)).days.any { it.workout.type == WorkoutType.Steady })
    }

    @Test
    fun `no goal leaves the week alone`() {
        assertNull(planFor(null).phase)
        assertNull(planFor(null).predictedTimeMs)
    }
}

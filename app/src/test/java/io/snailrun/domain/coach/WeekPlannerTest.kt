package io.snailrun.domain.coach

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The caps are the tests.
 *
 * Every assertion here is a rule that exists to stop the plan hurting someone, so each
 * one is written against the limit rather than against a particular week's numbers: a
 * change to the rotation or the wording should not touch this file, and a change that
 * lets a session past a cap should fail it.
 */
class WeekPlannerTest {

    private val today = LocalDate.of(2026, 9, 18)
    private val weekStart = LocalDate.of(2026, 9, 21) // a Monday

    private fun history(weeks: Int = 6, perWeekKm: Double = 40.0, days: List<DayOfWeek> = ThreeDays) =
        (0 until weeks).flatMap { back ->
            days.mapIndexed { i, day ->
                val date = today.minusWeeks(back.toLong()).with(day)
                // The last day of the list is the long one, at a third of the week.
                val share = if (i == days.lastIndex) 0.35 else 0.65 / (days.size - 1)
                CoachRun(date, perWeekKm * 1000 * share, 0)
            }
        }.filter { it.date <= today }

    private val ThreeDays = listOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SUNDAY)
    private val FiveDays = listOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.FRIDAY, DayOfWeek.SUNDAY,
    )

    private val solid = Fitness.estimate(
        listOf(RecentEffort(5_000, 20 * 60_000L, today.minusDays(11))),
        today,
    )!!

    private fun plan(
        runs: List<CoachRun> = history(),
        fitness: FitnessEstimate? = solid,
        goal: RaceGoal? = null,
    ) = WeekPlanner.plan(
        load = TrainingLoad.summarise(runs, today, DayOfWeek.MONDAY),
        fitness = fitness,
        goal = goal,
        weekStart = weekStart,
    )

    private val WeekPlan.sessions get() = days.map { it.workout }
    private val WeekPlan.quality get() = sessions.filter { it.type.isQuality }

    // ---- the caps --------------------------------------------------------------------

    @Test
    fun `threshold work never passes the ceiling`() {
        val plan = plan()
        plan.quality
            .filter { it.type == WorkoutType.Tempo || it.type == WorkoutType.CruiseIntervals }
            .forEach {
                assertTrue(
                    "${it.qualityMeters} m at threshold in a ${plan.plannedMeters} m week",
                    it.qualityMeters <= plan.plannedMeters * WeekPlanner.THRESHOLD_CEILING_SHARE + 1,
                )
            }
    }

    @Test
    fun `interval work never passes eight per cent nor ten kilometres`() {
        listOf(30.0, 60.0, 120.0).forEach { volume ->
            val plan = plan(runs = history(perWeekKm = volume, days = FiveDays))
            plan.quality
                .filter { it.type in setOf(WorkoutType.Intervals, WorkoutType.Hills, WorkoutType.Fartlek) }
                .forEach {
                    assertTrue(
                        "${it.qualityMeters} m hard in a ${plan.plannedMeters} m week",
                        it.qualityMeters <= plan.plannedMeters * WeekPlanner.INTERVAL_SHARE + 1,
                    )
                    assertTrue(it.qualityMeters <= WeekPlanner.INTERVAL_CAP_M + 1)
                }
        }
    }

    @Test
    fun `the long run is capped by both the share and the recent longest`() {
        // Fifty-kilometre weeks made of eight-kilometre runs: the share would allow
        // fifteen, the history allows 8.8, and the history has to win.
        val runs = (0 until 6).flatMap { back ->
            FiveDays.map { day ->
                CoachRun(today.minusWeeks(back.toLong()).with(day), 8_000.0, 0)
            }
        }.filter { it.date <= today }

        val plan = plan(runs = runs)
        val long = plan.sessions.single { it.type == WorkoutType.Long }
        assertTrue(
            "long run was ${long.totalMeters} m",
            long.totalMeters <= 8_000.0 * WeekPlanner.LONG_RUN_GROWTH + 1,
        )
    }

    @Test
    fun `the week rises by no more than a tenth`() {
        val plan = plan()
        assertTrue(
            "${plan.plannedMeters} against ${plan.lastWeekMeters}",
            plan.plannedMeters <= plan.lastWeekMeters * WeekPlanner.RAMP * 1.05,
        )
    }

    @Test
    fun `a spike is held level and stripped of quality`() {
        val runs = history(perWeekKm = 30.0) +
            listOf(CoachRun(today.minusDays(1), 40_000.0, 0))
        val plan = plan(runs = runs)
        assertTrue(plan.quality.isEmpty())
        assertTrue(plan.plannedMeters < plan.lastWeekMeters)
    }

    @Test
    fun `no two hard days touch`() {
        listOf(ThreeDays, FiveDays).forEach { days ->
            val plan = plan(runs = history(days = days))
            val hard = plan.days.filter {
                it.workout.type.isQuality || it.workout.type == WorkoutType.Long
            }.map { it.date }
            hard.forEach { a ->
                hard.forEach { b ->
                    if (a != b) {
                        assertTrue(
                            "$a and $b are next to each other",
                            abs(a.toEpochDay() - b.toEpochDay()) > 1,
                        )
                    }
                }
            }
        }
    }

    // ---- what may be prescribed from what ---------------------------------------------

    @Test
    fun `a provisional estimate buys no interval or repetition session`() {
        val provisional = Fitness.estimate(
            listOf(RecentEffort(1_000, 3 * 60_000L + 20_000L, today.minusDays(4))),
            today,
        )!!
        assertEquals(Confidence.Provisional, provisional.confidence)

        val plan = plan(runs = history(days = FiveDays), fitness = provisional)
        plan.quality.forEach {
            assertFalse(
                "${it.type} was prescribed from a provisional estimate",
                it.type in setOf(WorkoutType.Intervals, WorkoutType.Repetitions, WorkoutType.Fartlek),
            )
        }
    }

    @Test
    fun `no fitness estimate means no hard day at all`() {
        assertTrue(plan(fitness = null).quality.isEmpty())
    }

    @Test
    fun `almost no history gives a base week and says so`() {
        val plan = plan(runs = listOf(CoachRun(today.minusDays(3), 5_000.0, 0)))
        assertTrue(plan.quality.isEmpty())
        assertEquals(3, plan.sessions.count { it.type != WorkoutType.Rest })
        assertTrue(plan.note.contains("Not enough"))
    }

    @Test
    fun `a fortnight off comes back easy and shorter`() {
        val runs = history().filter { it.date <= today.minusDays(15) }
        val plan = plan(runs = runs)
        assertTrue(plan.quality.isEmpty())
        assertTrue(plan.plannedMeters < plan.chronicWeeklyMeters)
        assertTrue(plan.note.contains("not run in"))
    }

    @Test
    fun `three rising weeks produce a cutback that keeps the hard day`() {
        val runs = (0 until 5).flatMap { back ->
            val km = 40.0 - back * 5
            ThreeDays.map { day ->
                CoachRun(today.minusWeeks(back.toLong()).with(day), km * 1000 / 3, 0)
            }
        }.filter { it.date <= today }

        val plan = plan(runs = runs)
        assertTrue(plan.note.contains("cutback"))
        assertTrue(plan.plannedMeters < plan.lastWeekMeters)
    }

    // ---- shape -----------------------------------------------------------------------

    @Test
    fun `frequency matches what the runner already does`() {
        assertEquals(3, plan(runs = history(days = ThreeDays)).sessions.count { it.type != WorkoutType.Rest })
        assertEquals(5, plan(runs = history(days = FiveDays)).sessions.count { it.type != WorkoutType.Rest })
    }

    @Test
    fun `five days a week earns a second hard day, three does not`() {
        assertEquals(1, plan(runs = history(days = ThreeDays)).quality.size)
        assertEquals(2, plan(runs = history(days = FiveDays)).quality.size)
    }

    @Test
    fun `the long run lands on the day they already run long`() {
        val plan = plan()
        val long = plan.days.single { it.workout.type == WorkoutType.Long }
        assertEquals(DayOfWeek.SUNDAY, long.date.dayOfWeek)
    }

    @Test
    fun `the long run really is the longest`() {
        val plan = plan(runs = history(days = FiveDays))
        val long = plan.sessions.single { it.type == WorkoutType.Long }
        plan.sessions.filter { it.type != WorkoutType.Long }.forEach {
            assertTrue("${it.type} was ${it.totalMeters}", it.totalMeters <= long.totalMeters)
        }
    }

    @Test
    fun `most of the week is easy`() {
        val plan = plan(runs = history(days = FiveDays))
        val hard = plan.quality.sumOf { it.qualityMeters }
        assertTrue("$hard of ${plan.plannedMeters}", hard < plan.plannedMeters * 0.2)
    }

    @Test
    fun `a run recorded this week marks its day done`() {
        val plan = WeekPlanner.plan(
            load = TrainingLoad.summarise(history(), today, DayOfWeek.MONDAY),
            fitness = solid,
            goal = null,
            weekStart = weekStart,
            thisWeeksRuns = listOf(CoachRun(weekStart.plusDays(1), 8_000.0, 0)),
        )
        assertTrue(plan.days.single { it.date == weekStart.plusDays(1) }.done)
        assertFalse(plan.days.single { it.date == weekStart.plusDays(2) }.done)
    }

    @Test
    fun `every session says why it is there`() {
        plan(runs = history(days = FiveDays)).sessions.forEach {
            assertTrue("${it.type} had no reason", it.reason.length > 20)
        }
    }

    @Test
    fun `the same inputs always give the same week`() {
        assertEquals(plan().days.map { it.workout.type }, plan().days.map { it.workout.type })
    }
}

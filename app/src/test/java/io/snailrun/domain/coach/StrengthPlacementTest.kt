package io.snailrun.domain.coach

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the reinforcement work lands, and what it must never cost.
 *
 * The rule the whole feature stands on is the first test: strength carries no distance.
 * The moment it does, every cap in [WeekPlanner] starts counting squats as kilometres and
 * a runner gets a shorter long run because they did a plank.
 */
class StrengthPlacementTest {

    private val today = LocalDate.of(2026, 9, 18)
    private val weekStart = LocalDate.of(2026, 9, 21) // a Monday

    private val ThreeDays = listOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SUNDAY)
    private val SixDays = DayOfWeek.entries.filter { it != DayOfWeek.FRIDAY }

    private fun history(days: List<DayOfWeek>, weeks: Int = 6, perWeekKm: Double = 45.0) =
        (0 until weeks).flatMap { back ->
            days.mapIndexed { i, day ->
                val date = today.minusWeeks(back.toLong()).with(day)
                val share = if (i == days.lastIndex) 0.3 else 0.7 / (days.size - 1)
                CoachRun(date, perWeekKm * 1000 * share, 0)
            }
        }.filter { it.date <= today }

    private val solid = Fitness.estimate(
        listOf(RecentEffort(5_000, 20 * 60_000L, today.minusDays(11))),
        today,
    )!!

    private fun plan(days: List<DayOfWeek> = ThreeDays, goal: RaceGoal? = null) =
        WeekPlanner.plan(
            load = TrainingLoad.summarise(history(days), today, DayOfWeek.MONDAY),
            fitness = solid,
            goal = goal,
            weekStart = weekStart,
        )

    private val WeekPlan.strengthDays get() = days.filter { it.strength != null }

    // ---- the rule the feature stands on -----------------------------------------------

    @Test
    fun `strength carries no distance and so costs the week nothing`() {
        val session = Strength.session(0)
        assertEquals(0.0, session.totalMeters, 0.0)
        assertEquals(0.0, session.qualityMeters, 0.0)

        val plan = plan()
        assertEquals(
            plan.days.sumOf { it.workout.totalMeters },
            plan.plannedMeters,
            0.001,
        )
    }

    @Test
    fun `a strength session is never something the app would try to record`() {
        assertTrue(!WorkoutType.Strength.isRun)
        assertTrue(!WorkoutType.Strength.isQuality)
    }

    // ---- placement ---------------------------------------------------------------------

    @Test
    fun `an ordinary week gets two of them`() {
        assertEquals(WeekPlanner.STRENGTH_SESSIONS, plan().strengthDays.size)
    }

    @Test
    fun `never the day before something hard`() {
        listOf(ThreeDays, SixDays).forEach { days ->
            val plan = plan(days)
            val hard = plan.days
                .filter { it.workout.type.isQuality || it.workout.type == WorkoutType.Long }
                .map { it.date }
            plan.strengthDays.forEach { day ->
                assertTrue(
                    "strength on ${day.date} is the day before a hard one",
                    day.date.plusDays(1) !in hard,
                )
            }
        }
    }

    @Test
    fun `never two days running`() {
        listOf(ThreeDays, SixDays).forEach { days ->
            val dates = plan(days).strengthDays.map { it.date }
            dates.zipWithNext().forEach { (a, b) ->
                assertTrue("$a and $b are consecutive", abs(a.toEpochDay() - b.toEpochDay()) > 1)
            }
        }
    }

    /** Six running days leaves one rest day, so the fallback has to find the second one. */
    @Test
    fun `a crowded week still gets its strength work`() {
        assertTrue(plan(SixDays).strengthDays.isNotEmpty())
    }

    @Test
    fun `a rest day is preferred over a running day`() {
        val plan = plan(ThreeDays)
        val resting = plan.strengthDays.count { it.workout.type == WorkoutType.Rest }
        assertTrue("only $resting of them landed on a rest day", resting >= 1)
    }

    /** Race week is for arriving fresh. Two sessions of squats is not that. */
    @Test
    fun `a taper week gets one`() {
        val goal = RaceGoal(10_000, weekStart.plusDays(6))
        assertEquals(1, plan(ThreeDays, goal).strengthDays.size)
    }

    // ---- the session itself -------------------------------------------------------------

    @Test
    fun `the routine alternates but is the same every time a week is planned`() {
        val a = Strength.session(100)
        val b = Strength.session(101)
        assertNotNull(a.title)
        assertTrue(a.title != b.title)
        assertEquals(a, Strength.session(100))
    }

    @Test
    fun `every exercise says how many sets and how much of what`() {
        Strength.session(0).steps.forEach { step ->
            assertTrue("${step.label} has no sets", step.repeats >= 1)
            assertTrue(
                "${step.label} is counted in neither reps nor seconds",
                step.countPerSet != null || step.durationMs != null,
            )
        }
    }

    /** A move must take the whole day with it, or a rest day arrives holding a tempo's squats. */
    @Test
    fun `reordering a week carries the strength session with the day`() {
        val plan = plan()
        val first = plan.days.indexOfFirst { it.strength != null }
        val moved = with(WeekPlanner) { plan.reordered(WeekPlanner.moveOrder(WeekPlanner.identityOrder, first, 6)) }
        assertEquals(plan.days[first].strength, moved.days[6].strength)
        assertEquals(plan.days[first].workout, moved.days[6].workout)
    }
}

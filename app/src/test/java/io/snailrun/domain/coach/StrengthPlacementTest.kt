package io.snailrun.domain.coach

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
 * The moment it does, the week's totals start counting squats as kilometres.
 */
class StrengthPlacementTest {

    private val start = LocalDate.of(2026, 10, 5) // a Monday
    private val basis = CoachFitness.basis(Vma(14.0, start, VmaSource.Typed), emptyList(), start)

    private val ThreeDays = 3
    private val FourDays = 4

    private fun plan(sessions: Int = ThreeDays, week: Int = 0): WeekPlan {
        val plan = TrainingPlan(start, RaceGoal(10_000, start.plusDays(55)), null, sessions, start)
        return PlanWeeks.week(plan, basis, start.plusWeeks(week.toLong()))!!
    }

    private val WeekPlan.strengthDays get() = days.filter { it.strength != null }

    // ---- the rule the feature stands on -----------------------------------------------

    @Test
    fun `strength carries no distance and so costs the week nothing`() {
        val session = Strength.session(0)
        assertEquals(0.0, session.totalMeters, 0.0)
        assertEquals(0.0, session.qualityMeters, 0.0)

        // Strength is never a session's main workout, so it never reaches a total.
        assertTrue(plan().days.none { it.workout.type == WorkoutType.Strength })
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
        listOf(ThreeDays, FourDays).forEach { days ->
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
        listOf(ThreeDays, FourDays).forEach { days ->
            val dates = plan(days).strengthDays.map { it.date }
            dates.zipWithNext().forEach { (a, b) ->
                assertTrue("$a and $b are consecutive", abs(a.toEpochDay() - b.toEpochDay()) > 1)
            }
        }
    }

    /** Four running days leaves three rest days, two of them next to something hard. */
    @Test
    fun `a crowded week still gets its strength work`() {
        assertTrue(plan(FourDays).strengthDays.isNotEmpty())
    }

    @Test
    fun `a rest day is preferred over a running day`() {
        val plan = plan(ThreeDays)
        val resting = plan.strengthDays.count { it.workout.type == WorkoutType.Rest }
        assertTrue("only $resting of them landed on a rest day", resting >= 1)
    }

    /** Race week is for arriving fresh. Two sessions of squats is not that. */
    @Test
    fun `a race week gets one`() {
        assertEquals(1, plan(ThreeDays, week = 7).strengthDays.size)
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

package io.snailrun.domain.coach

import io.snailrun.domain.coach.WeekPlanner.reordered
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a runner's own reordering does to a week of their plan. */
class WeekOrderTest {

    private val start = LocalDate.of(2026, 10, 5) // a Monday

    private val plan = TrainingPlan(start, RaceGoal(10_000, start.plusDays(55)), 45 * 60_000L, 3, start)
    private val basis = CoachFitness.basis(Vma(14.0, start, VmaSource.Typed), emptyList(), start)

    private fun week(order: List<Int>? = null) = PlanWeeks.week(plan, basis, start, order = order)!!

    @Test
    fun `a move shifts the days it passes rather than swapping with one`() {
        assertEquals(listOf(0, 2, 3, 1, 4, 5, 6), WeekPlanner.moveOrder(WeekPlanner.identityOrder, from = 1, to = 3))
        assertEquals(listOf(0, 4, 1, 2, 3, 5, 6), WeekPlanner.moveOrder(WeekPlanner.identityOrder, from = 4, to = 1))
    }

    @Test
    fun `moving nowhere changes nothing`() {
        assertEquals(WeekPlanner.identityOrder, WeekPlanner.moveOrder(WeekPlanner.identityOrder, from = 2, to = 2))
        assertEquals(WeekPlanner.identityOrder, WeekPlanner.moveOrder(WeekPlanner.identityOrder, from = 2, to = 9))
    }

    @Test
    fun `a reordered week keeps its dates and moves its sessions`() {
        val plain = week()
        val moved = plain.reordered(WeekPlanner.moveOrder(WeekPlanner.identityOrder, 1, 2))
        assertEquals(plain.days.map { it.date }, moved.days.map { it.date })
        assertEquals(plain.days[1].workout, moved.days[2].workout)
        assertEquals(
            plain.days.sumOf { it.workout.totalMeters },
            moved.days.sumOf { it.workout.totalMeters },
            1e-6,
        )
    }

    @Test
    fun `putting a week back clears the order`() {
        val moved = week().reordered(listOf(0, 2, 3, 1, 4, 5, 6))
        assertNotEquals(null, moved.order)
        assertNull(moved.reordered(WeekPlanner.identityOrder).order)
    }

    @Test
    fun `an order that is not a permutation is ignored`() {
        assertNull(week().reordered(listOf(0, 0, 1, 2, 3, 4, 5)).order)
        assertNull(week().reordered(listOf(0, 1, 2)).order)
    }

    @Test
    fun `a saved order is applied when the week is planned`() {
        val order = listOf(0, 2, 1, 3, 4, 5, 6)
        val saved = week(order)
        assertEquals(order, saved.order)
        assertEquals(week().days[1].workout, saved.days[2].workout)
    }

    /** Tuesday's VMA moved to Wednesday sits the day before Thursday's race pace. */
    @Test
    fun `a move that stacks two hard days is called out`() {
        val moved = week(WeekPlanner.moveOrder(WeekPlanner.identityOrder, 1, 2))
        assertTrue(moved.conflicts.any { it.contains("hard") })
    }

    @Test
    fun `a week nobody has touched has nothing to warn about`() {
        (0 until 8).forEach { i ->
            val w = PlanWeeks.week(plan, basis, start.plusWeeks(i.toLong()), order = null)!!
            assertTrue("week $i: ${w.conflicts}", w.conflicts.isEmpty())
        }
    }
}

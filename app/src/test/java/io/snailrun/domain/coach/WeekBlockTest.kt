package io.snailrun.domain.coach

import io.snailrun.domain.coach.WeekPlanner.reordered
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The block of weeks, and what a runner's own reordering does to one. */
class WeekBlockTest {

    private val today = LocalDate.of(2026, 9, 18)
    private val weekStart = LocalDate.of(2026, 9, 21) // a Monday

    private val days = listOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.FRIDAY, DayOfWeek.SUNDAY,
    )

    private val runs = (0 until 6).flatMap { back ->
        days.mapIndexed { i, day ->
            CoachRun(
                today.minusWeeks(back.toLong()).with(day),
                if (i == days.lastIndex) 14_000.0 else 6_500.0,
                0,
            )
        }
    }.filter { it.date <= today }

    private val fitness = Fitness.estimate(
        listOf(RecentEffort(5_000, 20 * 60_000L, today.minusDays(11))),
        today,
    )

    private fun block(weeks: Int = 4, orders: Map<LocalDate, List<Int>> = emptyMap()) =
        WeekPlanner.block(
            runs = runs,
            fitness = fitness,
            goal = null,
            firstWeekStart = weekStart,
            today = today,
            weeks = weeks,
            firstDayOfWeek = DayOfWeek.MONDAY,
            orders = orders,
        )

    // ---- the block --------------------------------------------------------------------

    @Test
    fun `asking for no weeks gives none rather than an error`() {
        assertTrue(block(weeks = 0).isEmpty())
    }

    @Test
    fun `the block runs from the week asked for, one week at a time`() {
        val weeks = block()
        assertEquals(4, weeks.size)
        weeks.forEachIndexed { i, plan ->
            assertEquals(weekStart.plusWeeks(i.toLong()), plan.weekStart)
            assertEquals(7, plan.days.size)
        }
    }

    /**
     * The point of rolling the history forward. Without it every week is planned against
     * the same last-week figure and the block shows four identical weeks.
     */
    @Test
    fun `each week builds on the one planned before it`() {
        val weeks = block()
        weeks.zipWithNext().forEach { (earlier, later) ->
            assertEquals(
                "week beginning ${later.weekStart} did not see the week before it",
                earlier.plannedMeters,
                later.lastWeekMeters,
                1.0,
            )
            assertTrue(later.plannedMeters > earlier.plannedMeters * 0.7)
        }
    }

    @Test
    fun `the caps still hold in the last week of the block`() {
        val last = block().last()
        assertTrue(last.plannedMeters <= last.lastWeekMeters * WeekPlanner.RAMP * 1.05)
        last.days.map { it.workout }.filter { it.type.isQuality }.forEach {
            assertTrue(
                "${it.qualityMeters} m hard in a ${last.plannedMeters} m week",
                it.qualityMeters <= last.plannedMeters * WeekPlanner.THRESHOLD_CEILING_SHARE + 1,
            )
        }
    }

    @Test
    fun `only the current week can already have days run`() {
        val weeks = block()
        weeks.drop(1).forEach { plan ->
            assertTrue(plan.days.none { it.done })
        }
    }

    // ---- moving a session --------------------------------------------------------------

    @Test
    fun `a move shifts the days it passes rather than swapping with one`() {
        assertEquals(
            listOf(0, 2, 3, 1, 4, 5, 6),
            WeekPlanner.moveOrder(WeekPlanner.identityOrder, from = 1, to = 3),
        )
        assertEquals(
            listOf(0, 4, 1, 2, 3, 5, 6),
            WeekPlanner.moveOrder(WeekPlanner.identityOrder, from = 4, to = 1),
        )
    }

    @Test
    fun `moving nowhere changes nothing`() {
        assertEquals(
            WeekPlanner.identityOrder,
            WeekPlanner.moveOrder(WeekPlanner.identityOrder, from = 2, to = 2),
        )
        assertEquals(
            WeekPlanner.identityOrder,
            WeekPlanner.moveOrder(WeekPlanner.identityOrder, from = 2, to = 9),
        )
    }

    @Test
    fun `a reordered week keeps its dates and moves its sessions`() {
        val plan = block(weeks = 1).single()
        val moved = plan.reordered(WeekPlanner.moveOrder(WeekPlanner.identityOrder, 1, 3))

        assertEquals(plan.days.map { it.date }, moved.days.map { it.date })
        assertEquals(plan.days[1].workout.type, moved.days[3].workout.type)
        assertEquals(plan.days[2].workout.type, moved.days[1].workout.type)
        assertEquals(plan.plannedMeters, moved.days.sumOf { it.workout.totalMeters }, 0.1)
    }

    @Test
    fun `putting a week back clears the order`() {
        val plan = block(weeks = 1).single()
        val moved = plan.reordered(listOf(0, 2, 3, 1, 4, 5, 6))
        assertNotEquals(null, moved.order)
        assertNull(moved.reordered(WeekPlanner.identityOrder).order)
    }

    @Test
    fun `an order that is not a permutation is ignored`() {
        val plan = block(weeks = 1).single()
        assertNull(plan.reordered(listOf(0, 0, 1, 2, 3, 4, 5)).order)
        assertNull(plan.reordered(listOf(0, 1, 2)).order)
    }

    @Test
    fun `a saved order is applied when the block is rebuilt`() {
        val order = listOf(0, 2, 3, 1, 4, 5, 6)
        val plain = block(weeks = 1).single()
        val saved = block(weeks = 1, orders = mapOf(weekStart to order)).single()

        assertEquals(order, saved.order)
        assertEquals(plain.days[1].workout.type, saved.days[3].workout.type)
    }

    /**
     * The plan does not refuse a move. It says what the move costs, which is the only
     * useful thing it can do about a decision the runner has more information about.
     */
    @Test
    fun `a move that stacks two hard days is called out`() {
        val plan = block(weeks = 1).single()
        val hard = plan.days.indexOfFirst { it.workout.type.isQuality }
        val long = plan.days.indexOfFirst { it.workout.type == WorkoutType.Long }

        val next = if (hard < long) long - 1 else long + 1
        val moved = plan.reordered(WeekPlanner.moveOrder(WeekPlanner.identityOrder, hard, next))

        assertTrue("no warning for $hard -> $next", moved.conflicts.isNotEmpty())
        assertTrue(moved.conflicts.any { it.contains("hard") })
    }

    @Test
    fun `a week nobody has touched has nothing to warn about`() {
        block().forEach { assertTrue(it.conflicts.isEmpty()) }
    }
}

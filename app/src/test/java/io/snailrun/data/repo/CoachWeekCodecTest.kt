package io.snailrun.data.repo

import io.snailrun.domain.coach.CoachFitness
import io.snailrun.domain.coach.PlanWeeks
import io.snailrun.domain.coach.RaceGoal
import io.snailrun.domain.coach.TrainingPlan
import io.snailrun.domain.coach.Vma
import io.snailrun.domain.coach.VmaSource
import io.snailrun.domain.coach.WeekPlanner.reordered
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A saved week reads back as the week that was saved. Robolectric for `org.json`. */
@RunWith(RobolectricTestRunner::class)
class CoachWeekCodecTest {

    private val start = LocalDate.of(2026, 10, 5)

    private val week = PlanWeeks.week(
        plan = TrainingPlan(start, RaceGoal(10_000, start.plusDays(55)), 45 * 60_000L, 4, start),
        basis = CoachFitness.basis(Vma(14.0, start, VmaSource.Test), emptyList(), start),
        weekStart = start.plusWeeks(4),
    )!!.reordered(listOf(0, 3, 1, 2, 4, 5, 6))

    @Test
    fun `every day, step and explanation comes back`() {
        val back = CoachWeekCodec.decode(CoachWeekCodec.encode(week))!!
        assertTrue(back.frozen)
        // Saved weeks come back frozen; fitness and prediction belong to today, not to it.
        assertEquals(week.copy(frozen = true, fitness = null, predictedTimeMs = null), back)
    }

    @Test
    fun `a body it cannot read is no week rather than a crash`() {
        assertNull(CoachWeekCodec.decode("not json"))
    }
}

package io.snailrun.data.prefs

import io.snailrun.domain.coach.RaceGoal
import io.snailrun.domain.coach.TrainingPlan
import io.snailrun.domain.coach.Vma
import io.snailrun.domain.coach.VmaSource
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The plan and the VMA, through the strings the preference store keeps them in. */
class CoachPlanCodecTest {

    private val day = LocalDate.of(2026, 10, 5)

    @Test
    fun `a plan with a race and a target survives the round trip`() {
        val plan = TrainingPlan(day, RaceGoal(21_097, day.plusWeeks(14)), 105 * 60_000L, 4, day)
        assertEquals(plan, TrainingPlans.decode(TrainingPlans.encode(plan)))
    }

    @Test
    fun `a plan with no race and no target survives too`() {
        val plan = TrainingPlan(day, null, null, 2, day)
        assertEquals(plan, TrainingPlans.decode(TrainingPlans.encode(plan)))
    }

    @Test
    fun `a plan from an unknown version is no plan`() {
        assertNull(TrainingPlans.decode("2,1,2,3,4,5,6"))
        assertNull(TrainingPlans.decode("garbage"))
        assertNull(TrainingPlans.decode(null))
    }

    @Test
    fun `a VMA keeps its tenth, its source and its run`() {
        val vma = Vma(14.7, day, VmaSource.Test, testRunId = 42)
        assertEquals(vma, CoachVmas.decode(CoachVmas.encode(vma)))
        val typed = Vma(12.0, day, VmaSource.Typed)
        assertEquals(typed, CoachVmas.decode(CoachVmas.encode(typed)))
        assertNull(CoachVmas.decode("1,0,20000,Typed,0"))
    }
}

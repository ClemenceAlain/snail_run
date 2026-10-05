package io.snailrun.ui.coach

import io.snailrun.data.db.RunEntity
import io.snailrun.data.prefs.CoachSettings
import io.snailrun.domain.coach.RaceGoal
import io.snailrun.domain.coach.TrainingPlan
import io.snailrun.domain.coach.Vma
import io.snailrun.domain.coach.VmaSource
import io.snailrun.domain.coach.WorkoutType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the wizard reads out of what was typed, and when the plan owes a VMA test. */
class PlanInputsTest {

    private val today = LocalDate.of(2026, 10, 5) // a Monday

    @Test
    fun `a VMA is read with either decimal mark, inside a human range`() {
        assertEquals(14.5, PlanInputs.vma("14,5")!!, 1e-9)
        assertEquals(14.5, PlanInputs.vma(" 14.5 ")!!, 1e-9)
        assertNull(PlanInputs.vma("3"))
        assertNull(PlanInputs.vma("45"))
        assertNull(PlanInputs.vma(""))
    }

    @Test
    fun `a test distance becomes a VMA of a hundredth of it`() {
        assertEquals(14.8, PlanInputs.vmaFrom(VmaAnswer.Measured, "1480", today)!!.kmh, 1e-9)
        assertEquals(VmaSource.Test, PlanInputs.vmaFrom(VmaAnswer.Measured, "1480", today)!!.source)
        assertNull(PlanInputs.vmaFrom(VmaAnswer.Unknown, "1480", today))
    }

    @Test
    fun `a target time is read as minutes, mm ss or h mm ss`() {
        assertEquals(45 * 60_000L, PlanInputs.time("45"))
        assertEquals(45 * 60_000L + 30_000L, PlanInputs.time("45:30"))
        assertEquals(105 * 60_000L, PlanInputs.time("1:45:00"))
        assertNull(PlanInputs.time("45:75"))
        assertNull(PlanInputs.time("abc"))
        assertNull(PlanInputs.time("0"))
    }

    private val plan = TrainingPlan(today, RaceGoal(10_000, today.plusDays(55)), null, 3, today)

    private fun run(date: LocalDate, type: WorkoutType?) = RunEntity(
        id = 1, startedAtEpochMs = 0, endedAtEpochMs = 0, timeZoneId = "UTC",
        localDate = date.toString(), distanceMeters = 5_000.0, elapsedTimeMs = 0, movingTimeMs = 0,
        avgPaceSecPerKm = 0.0, elevationGainM = 0.0, elevationLossM = 0.0, pointCount = 0,
        minLat = 0.0, maxLat = 0.0, minLon = 0.0, maxLon = 0.0, title = null, note = null,
        source = "RECORDED", status = "COMPLETE", workoutType = type?.name,
    )

    @Test
    fun `with no VMA the test goes in the first week it can`() {
        val saved = CoachSettings(plan = plan)
        assertEquals(today, CoachPlans.testWeek(emptyList(), saved, today, today))
    }

    @Test
    fun `a recent VMA owes no test, an old one does`() {
        val fresh = CoachSettings(plan = plan, vma = Vma(14.0, today.minusDays(10), VmaSource.Typed))
        assertNull(CoachPlans.testWeek(emptyList(), fresh, today, today))
        val old = CoachSettings(plan = plan, vma = Vma(14.0, today.minusDays(100), VmaSource.Typed))
        assertEquals(today, CoachPlans.testWeek(emptyList(), old, today, today))
    }

    @Test
    fun `a test run this week keeps the test in the week after it set the VMA`() {
        val saved = CoachSettings(plan = plan, vma = Vma(14.8, today.plusDays(1), VmaSource.Test, 1))
        val runs = listOf(run(today.plusDays(1), WorkoutType.VmaTest))
        assertEquals(today, CoachPlans.testWeek(runs, saved, today, today.plusDays(2)))
    }
}

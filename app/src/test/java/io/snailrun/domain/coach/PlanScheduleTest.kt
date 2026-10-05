package io.snailrun.domain.coach

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which week of which template a calendar week is. */
class PlanScheduleTest {

    private val start = LocalDate.of(2026, 10, 5) // a Monday

    private fun plan(raceMeters: Int?, weeksToRace: Int, sessions: Int = 3) = TrainingPlan(
        startWeek = start,
        // The race on the Sunday of the race week.
        race = raceMeters?.let { RaceGoal(it, start.plusWeeks(weeksToRace - 1L).plusDays(6)) },
        targetTimeMs = null,
        sessionsPerWeek = sessions,
        createdOn = start,
    )

    private fun weeks(plan: TrainingPlan, count: Int) =
        (0 until count).map { PlanSchedule.position(plan, start.plusWeeks(it.toLong())) }

    @Test
    fun `a 10 km eight weeks out is the template exactly`() {
        val p = plan(10_000, 8)
        val positions = weeks(p, 8)
        assertEquals((1..8).toList(), positions.map { it!!.templateWeek })
        assertEquals(WeekKind.Light, positions[3]!!.kind)
        assertEquals(WeekKind.Race, positions[7]!!.kind)
        assertEquals(8, positions[0]!!.of)
    }

    @Test
    fun `a race further out gets base weeks in front, ending on a lighter one`() {
        val p = plan(10_000, 12)
        val positions = weeks(p, 12)
        assertEquals(PlanTemplates.Cycle, positions[0]!!.template)
        assertEquals(WeekKind.Light, positions[3]!!.kind)
        assertEquals(PlanTemplates.TenK, positions[4]!!.template)
        assertEquals(1, positions[4]!!.templateWeek)
        assertEquals(WeekKind.Race, positions[11]!!.kind)
    }

    @Test
    fun `an odd number of base weeks still ends on the lighter one`() {
        val p = plan(5_000, 8 + 3)
        val base = weeks(p, 3).map { it!!.templateWeek }
        assertEquals(listOf(2, 3, 4), base)
    }

    @Test
    fun `a race closer than the template skips its first weeks and keeps the taper`() {
        val p = plan(10_000, 5)
        val positions = weeks(p, 5)
        assertEquals(listOf(4, 5, 6, 7, 8), positions.map { it!!.templateWeek })
    }

    @Test
    fun `nothing before the start or after the race`() {
        val p = plan(10_000, 8)
        assertNull(PlanSchedule.position(p, start.minusWeeks(1)))
        assertNull(PlanSchedule.position(p, start.plusWeeks(8)))
        assertEquals(start.plusWeeks(7), PlanSchedule.lastWeek(p))
    }

    @Test
    fun `with no race the 10 km month repeats, longer the second time`() {
        val p = plan(null, 0)
        val positions = weeks(p, 8).map { it!!.templateWeek }
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 4), positions)
        assertNull(weeks(p, 8).first()!!.of)
        assertEquals(start.plusWeeks(TrainingPlan.OPEN_WEEKS - 1L), PlanSchedule.lastWeek(p))
    }

    @Test
    fun `a plan started early in the week starts that week, later the next`() {
        val monday = LocalDate.of(2026, 10, 5)
        assertEquals(monday, TrainingPlan.startWeekFor(monday, DayOfWeek.MONDAY))
        assertEquals(monday, TrainingPlan.startWeekFor(monday.plusDays(1), DayOfWeek.MONDAY))
        assertEquals(monday.plusWeeks(1), TrainingPlan.startWeekFor(monday.plusDays(3), DayOfWeek.MONDAY))
    }

    @Test
    fun `every template ends with its race week and a half or marathon tapers first`() {
        listOf(PlanTemplates.FiveK, PlanTemplates.TenK, PlanTemplates.Half, PlanTemplates.Marathon).forEach {
            assertEquals(it.name, WeekKind.Race, it.weeks.last().kind)
            assertEquals(it.name, SessionSpec.Race::class, it.weeks.last().third::class)
        }
        assertEquals(WeekKind.Taper, PlanTemplates.Half.weeks[10].kind)
        assertEquals(WeekKind.Taper, PlanTemplates.Marathon.weeks[14].kind)
        assertEquals(listOf(8, 8, 12, 16), listOf(PlanTemplates.FiveK, PlanTemplates.TenK, PlanTemplates.Half, PlanTemplates.Marathon).map { it.weeks.size })
    }

    @Test
    fun `the 10 km is the Decathlon table`() {
        val w = PlanTemplates.TenK.weeks
        fun vma(i: Int) = w[i].first as SessionSpec.VmaReps
        fun pace(i: Int) = w[i].second as SessionSpec.RacePace
        fun long(i: Int) = (w[i].third as SessionSpec.Easy).minutes

        assertEquals(listOf(12, 200.0, 1.05..1.05, 100.0), vma(0).let { listOf(it.reps, it.repM, it.pct, it.recoveryM) })
        assertEquals(listOf(12, 300.0, 1.0..1.05), vma(1).let { listOf(it.reps, it.repM, it.pct) })
        assertEquals(listOf(10, 400.0, 0.95..1.0), vma(2).let { listOf(it.reps, it.repM, it.pct) })
        assertEquals(listOf(2, 8, 200.0, 150_000L), vma(4).let { listOf(it.sets, it.reps, it.repM, it.setRecoveryMs) })
        assertEquals(listOf(8, 500.0, 0.95..0.95, 200.0), vma(5).let { listOf(it.reps, it.repM, it.pct, it.recoveryM) })

        assertEquals(List(5) { 1_000.0 }, pace(0).blocksM)
        assertEquals(List(3) { 1_500.0 }, pace(1).blocksM)
        assertEquals(List(3) { 2_000.0 }, pace(2).blocksM)
        assertEquals(List(2) { 3_000.0 }, pace(3).blocksM)
        assertEquals(List(6) { 1_000.0 }, pace(4).blocksM)
        assertEquals(listOf(90_000L), pace(4).recoveriesMs.distinct())
        assertEquals(listOf(3_000.0, 2_000.0, 1_000.0), pace(6).blocksM)

        assertEquals(listOf(60, 75, 75, 60, 90, 75, 60), (0..6).map(::long))
        assertEquals(45, (w[3].first as SessionSpec.Easy).minutes)
        assertEquals(20, (w[7].second as SessionSpec.Easy).minutes)
    }
}

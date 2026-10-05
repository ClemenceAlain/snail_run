package io.snailrun.domain.coach

import io.snailrun.ui.format.SessionFormat
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Template weeks laid onto days, priced, and explained. */
class PlanWeeksTest {

    private val start = LocalDate.of(2026, 10, 5) // a Monday
    private val basis = CoachFitness.basis(Vma(14.0, start, VmaSource.Test), emptyList(), start)

    private fun plan(sessions: Int = 3, weeks: Int = 8, target: Long? = 45 * 60_000L, meters: Int = 10_000) =
        TrainingPlan(
            startWeek = start,
            race = RaceGoal(meters, start.plusWeeks(weeks - 1L).plusDays(6)),
            targetTimeMs = target,
            sessionsPerWeek = sessions,
            createdOn = start,
        )

    private fun week(plan: TrainingPlan, index: Int, testWeek: LocalDate? = null, basis: PaceBasis = this.basis) =
        PlanWeeks.week(plan, basis, start.plusWeeks(index.toLong()), testWeek)!!

    private fun runs(week: WeekPlan) = week.days.filter { it.workout.type.isRun }

    @Test
    fun `three sessions land on Tuesday, Thursday and Sunday`() {
        val w = week(plan(), 0)
        assertEquals(
            listOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SUNDAY),
            runs(w).map { it.date.dayOfWeek },
        )
        assertEquals(
            listOf(WorkoutType.VmaIntervals, WorkoutType.RacePace, WorkoutType.Long),
            runs(w).map { it.workout.type },
        )
    }

    @Test
    fun `four sessions add an easy Friday, two alternate the hard one`() {
        assertEquals(DayOfWeek.FRIDAY, runs(week(plan(4), 0))[2].date.dayOfWeek)
        val two = plan(2)
        assertEquals(WorkoutType.VmaIntervals, runs(week(two, 0)).first().workout.type)
        assertEquals(WorkoutType.RacePace, runs(week(two, 1)).first().workout.type)
        assertEquals(2, runs(week(two, 1)).size)
    }

    @Test
    fun `a quality session is framed by the Decathlon warm-up and cool-down`() {
        val steps = runs(week(plan(), 0)).first().workout.steps
        assertEquals(20 * 60_000L, steps[0].durationMs)
        assertEquals("Warm up drills", steps[1].label)
        assertEquals(3, steps[2].repeats)
        assertEquals(100.0, steps[2].distanceM)
        assertEquals(10 * 60_000L, steps.last().durationMs)
        assertEquals(SegmentKind.CoolDown, steps.last().kind)
    }

    @Test
    fun `VMA reps are priced off the VMA and say so`() {
        val fast = runs(week(plan(), 0)).first().workout.steps.first { it.kind == SegmentKind.Work }
        assertEquals(12, fast.repeats)
        assertEquals(200.0, fast.distanceM)
        // 105 % of 14 km/h.
        assertEquals(245.0, fast.paceSecPerKm!!.start, 1.0)
        assertEquals("105 % VMA", fast.intensity)
        assertTrue(fast.why!!, fast.why!!.contains("4:05/km") && fast.why!!.contains("14.0 km/h"))
    }

    @Test
    fun `race pace comes from the target time`() {
        val block = runs(week(plan(), 0))[1].workout.steps.first { it.kind == SegmentKind.Work }
        // 45:00 over 10 km.
        assertEquals(270.0, block.paceSecPerKm!!.start, 0.5)
        assertTrue(block.why!!, block.why!!.contains("45:00 target"))
    }

    @Test
    fun `two sets of reps read as two rows, with the recovery between`() {
        val workout = runs(week(plan(), 4)).first().workout
        val blocks = SessionFormat.blocks(WorkoutSegments.of(workout))
        val work = blocks.filter { it.kind == SegmentKind.Work }
        assertEquals(listOf("8 × Fast", "8 × Fast"), work.map { it.title })
        assertTrue(blocks.any { it.kind == SegmentKind.Recover && it.title == "Recover" })
    }

    @Test
    fun `the test replaces the first session in the test week only`() {
        val p = plan()
        assertEquals(WorkoutType.VmaTest, runs(week(p, 0, testWeek = start)).first().workout.type)
        assertEquals(WorkoutType.VmaIntervals, runs(week(p, 1, testWeek = start)).first().workout.type)
        val test = runs(week(p, 0, testWeek = start)).first().workout
        val segment = WorkoutSegments.of(test).first { it.kind == SegmentKind.Work }
        assertEquals(Vmas.TEST_MS, segment.targetMs)
    }

    @Test
    fun `the test is never put in a lighter week or the race week`() {
        val p = plan()
        assertEquals(start, PlanWeeks.firstTestWeek(p, start))
        assertEquals(start.plusWeeks(4), PlanWeeks.firstTestWeek(p, start.plusWeeks(3)))
        assertNull(PlanWeeks.firstTestWeek(p, start.plusWeeks(7)))
    }

    @Test
    fun `race week is laid out from the race date`() {
        val p = plan()
        val w = week(p, 7)
        val raceDay = p.race!!.date
        val byDate = runs(w).associate { it.date to it.workout.type }
        assertEquals(WorkoutType.Race, byDate[raceDay])
        assertEquals(WorkoutType.Strides, byDate[raceDay.minusDays(2)])
        assertEquals(WorkoutType.VmaIntervals, byDate[raceDay.minusDays(5)])
        assertEquals(1, w.days.count { it.strength != null })
    }

    @Test
    fun `without a VMA the fast reps are by feel and say why`() {
        val none = CoachFitness.basis(null, emptyList(), start)
        val fast = runs(week(plan(target = null), 0, basis = none)).first().workout.steps
            .first { it.kind == SegmentKind.Work }
        assertNull(fast.paceSecPerKm)
        assertTrue(fast.why!!.contains("by feel"))
    }

    @Test
    fun `a slow VMA shortens the runs and the rep counts`() {
        val slow = CoachFitness.basis(Vma(10.5, start, VmaSource.Typed), emptyList(), start)
        val w = week(plan(), 0, basis = slow)
        assertEquals(9, runs(w).first().workout.steps.first { it.kind == SegmentKind.Work }.repeats)
        assertEquals(50 * 60_000L, runs(w).last().workout.steps.first().durationMs)
    }

    @Test
    fun `every session has a reason, a tip and an explained main step`() {
        listOf(plan(meters = 5_000), plan(), plan(weeks = 12, meters = 21_097), plan(weeks = 16, meters = 42_195))
            .forEach { p ->
                (0 until PlanSchedule.weeksIn(p)!!).forEach { i ->
                    runs(week(p, i)).forEach { day ->
                        val w = day.workout
                        assertTrue(w.reason.isNotBlank())
                        assertFalse(w.tip.isNullOrBlank())
                        assertNotNull("${w.name} week $i", w.steps.firstOrNull { it.why != null })
                        assertEquals(Workouts.metersOf(w), w.totalMeters, 1e-6)
                        assertNotNull(w.estimatedMs)
                    }
                }
            }
    }

    @Test
    fun `the plan explanation covers the structure, the paces and the taper`() {
        val about = PlanExplainer.about(plan(), basis, PlanSchedule.position(plan(), start))
        val text = about.joinToString(" ") { it.title + " " + it.body }
        assertTrue(text.contains("14.0 km/h"))
        assertTrue(text.contains("105 % = 4:05/km"))
        assertTrue(text.contains("four fifths"))
        assertTrue(text.contains("Every fourth week is lighter"))
        assertTrue(text.contains("4:30/km"))
    }

    @Test
    fun `the week note says where the week sits and what is next`() {
        val p = plan()
        assertEquals("Week 3 of 8 · Build. Next week is lighter.", week(p, 2).note)
    }

    @Test
    fun `the same inputs give the same week`() {
        assertEquals(week(plan(), 2), week(plan(), 2))
    }
}

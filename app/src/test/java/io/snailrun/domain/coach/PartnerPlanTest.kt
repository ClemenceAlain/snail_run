package io.snailrun.domain.coach

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnerPlanTest {

    /** 20:00 for 5 km: Daniels' anchor row. */
    private val her = Vdot.fromEffort(5_000.0, 20 * 60_000L)!!
    private val herPaces = Fitness.pacesFor(her)

    /** A partner a good deal quicker: 18 km/h at VMA. */
    private val fast = Partners.vdotOf(18.0)

    @Test
    fun `a VMA converts to the VDOT whose VO2max speed it is`() {
        // VDOT 50 reaches VO2max at about 3:50 /km — 15.6 km/h.
        val vdot = Partners.vdotOf(15.6)
        assertEquals(50.0, vdot, 1.0)
        assertEquals(60_000.0 / (15.6 * 1000 / 60), Vdot.paceSecPerKm(vdot, 1.0), 0.5)
    }

    @Test
    fun `translating at the same VDOT changes nothing`() {
        val session = Workouts.intervals(4_000.0, herPaces, "")
        val same = PartnerPlan.mirror(session, her, her)
        session.steps.zip(same.steps).forEach { (a, b) ->
            assertEquals(a.copy(paceSecPerKm = null, recoveryPaceSecPerKm = null), b.copy(paceSecPerKm = null, recoveryPaceSecPerKm = null))
            assertEquals(a.paceSecPerKm!!.start, b.paceSecPerKm!!.start, 1e-6)
            assertEquals(a.paceSecPerKm!!.endInclusive, b.paceSecPerKm!!.endInclusive, 1e-6)
        }
        assertEquals(session.totalMeters, same.totalMeters, 1e-3)
    }

    @Test
    fun `his paces are his own Daniels paces`() {
        val session = Workouts.tempo(5_000.0, herPaces, "")
        val his = PartnerPlan.mirror(session, her, fast)
        val tempo = his.steps.single { it.label == "Tempo" }.paceSecPerKm!!
        assertEquals(Fitness.pacesFor(fast).thresholdSecPerKm, tempo.start, 0.5)
        val easy = his.steps.first().paceSecPerKm!!
        assertEquals(Fitness.pacesFor(fast).easySecPerKm.start, easy.start, 0.5)
        assertEquals(Fitness.pacesFor(fast).easySecPerKm.endInclusive, easy.endInclusive, 0.5)
    }

    @Test
    fun `mirror keeps the structure and the distances`() {
        val session = Workouts.repetitions(2_000.0, herPaces, "")
        val his = PartnerPlan.mirror(session, her, fast)
        assertEquals(session.steps.map { it.repeats }, his.steps.map { it.repeats })
        assertEquals(session.steps.map { it.distanceM }, his.steps.map { it.distanceM })
        assertEquals(session.totalMeters, his.totalMeters, 0.1)
    }

    @Test
    fun `overlapping bands share the overlap`() {
        assertEquals(300.0..320.0, PartnerPlan.sharedPace(280.0..320.0, 300.0..340.0))
    }

    @Test
    fun `the faster runner comes down to the slower one's quickest`() {
        // His easy is 4:30–5:00, hers 5:10–5:40: he drops to 5:10, under his 5:30 limit.
        assertEquals(310.0..330.0, PartnerPlan.sharedPace(270.0..300.0, 310.0..340.0))
    }

    @Test
    fun `too far apart is not shared`() {
        assertNull(PartnerPlan.sharedPace(240.0..270.0, 310.0..340.0))
    }

    @Test
    fun `distance reps restart together, the faster one jogging the difference`() {
        val session = Workouts.repetitions(2_000.0, herPaces, "")
        val plan = PartnerPlan.together(session, her, fast)
        val reps = session.steps.indexOfFirst { it.repeats > 1 }

        val extra = plan.hisExtraMs[reps]!!
        assertTrue("expected a gap to make up, got $extra", extra > 0)
        assertTrue(plan.herExtraMs.isEmpty())
        // Her session is untouched where she is the slower one; only the jog paces are
        // brought together.
        assertEquals(session.steps.map { it.distanceM }, plan.her.steps.map { it.distanceM })
        assertRepsStartTogether(plan)
    }

    @Test
    fun `when she is the faster one, her jogs absorb the gap`() {
        val slow = Partners.vdotOf(13.0)
        val session = Workouts.repetitions(2_000.0, herPaces, "")
        val plan = PartnerPlan.together(session, her, slow)
        val reps = session.steps.indexOfFirst { it.repeats > 1 }
        assertTrue(plan.herExtraMs.getValue(reps) > 0)
        assertTrue(plan.hisExtraMs.isEmpty())
        assertRepsStartTogether(plan)
    }

    @Test
    fun `time reps keep the clocks together and report how far apart they end`() {
        val session = Workouts.intervals(4_000.0, herPaces, "")
        val plan = PartnerPlan.together(session, her, fast)
        val reps = session.steps.indexOfFirst { it.repeats > 1 }
        assertTrue(plan.hisExtraMs.isEmpty() && plan.herExtraMs.isEmpty())
        assertTrue(plan.spreadM.getValue(reps) > 0.0)
        assertTrue(reps in plan.sharedRecoveries)
        assertRepsStartTogether(plan)
    }

    @Test
    fun `a tempo is run for her time, so they finish it together`() {
        val session = Workouts.tempo(5_000.0, herPaces, "")
        val plan = PartnerPlan.together(session, her, fast)
        val tempo = session.steps.indexOfFirst { it.label == "Tempo" }
        assertEquals(PartnerPlan.durationOf(plan.her.steps[tempo]), plan.his.steps[tempo].durationMs)
        assertEquals(0L, plan.finishGapMs)
        assertTrue(0 in plan.sharedSteps)
        assertTrue(session.steps.lastIndex in plan.sharedSteps)
        assertEquals(1, plan.regroups)
    }

    @Test
    fun `an easy run with a much faster partner is run apart and finished together`() {
        val session = Workouts.easy(8_000.0, herPaces, "")
        val plan = PartnerPlan.together(session, her, Partners.vdotOf(21.0))
        assertTrue(plan.sharedSteps.isEmpty())
        assertEquals(PartnerPlan.durationOf(plan.her.steps[0]), plan.his.steps[0].durationMs)
    }

    @Test
    fun `equal partners run the whole thing side by side`() {
        val session = Workouts.cruiseIntervals(5_000.0, herPaces, "")
        val plan = PartnerPlan.together(session, her, her)
        assertEquals(session.steps.size, plan.sharedSteps.size + plan.sharedRecoveries.size)
        assertTrue(plan.spreadM.isEmpty())
        assertEquals(0L, plan.finishGapMs)
    }

    @Test
    fun `a strength session passes through untouched`() {
        val session = Strength.session(0)
        val plan = PartnerPlan.together(session, her, fast)
        assertEquals(session, plan.her)
        assertEquals(session, plan.his)
    }

    /** Walks both sessions on their own clocks and checks every rep starts at once. */
    private fun assertRepsStartTogether(plan: TogetherPlan) {
        val herStarts = repStarts(plan.her)
        val hisStarts = repStarts(plan.his)
        assertEquals(herStarts.size, hisStarts.size)
        herStarts.zip(hisStarts).forEach { (a, b) ->
            assertTrue("rep starts ${a}ms and ${b}ms apart", abs(a - b) <= 2_500L)
        }
    }

    private fun repStarts(workout: Workout): List<Long> {
        val starts = mutableListOf<Long>()
        var clock = 0L
        workout.steps.forEach { step ->
            val rep = PartnerPlan.durationOf(step)
            val jog = step.recoveryMs ?: step.recoveryM?.let { m ->
                step.recoveryPaceSecPerKm?.let { Workouts.durationAt((it.start + it.endInclusive) / 2, m) }
            } ?: 0L
            repeat(step.repeats.coerceAtLeast(1)) { i ->
                if (step.repeats > 1) starts += clock
                clock += rep
                if (i < step.repeats - 1) clock += jog
            }
        }
        return starts
    }
}

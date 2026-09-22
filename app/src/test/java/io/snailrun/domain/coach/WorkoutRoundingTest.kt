package io.snailrun.domain.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two properties, asserted over every session the coach can build.
 *
 * Written as a sweep rather than as one test per session type on purpose: both of these
 * are invariants of the whole module, and a new session added next year should fail this
 * file rather than quietly ship a rep of 913 metres because nobody wrote it a test.
 */
class WorkoutRoundingTest {

    private val paces = TrainingPaces(
        easySecPerKm = 372.0..432.0,
        marathonSecPerKm = 327.0,
        thresholdSecPerKm = 309.0,
        intervalSecPerKm = 285.0,
        repetitionSecPerKm = 265.0,
    )

    /** A spread of budgets, including the awkward ones that produce the worst decimals. */
    private val budgets = listOf(3_137.0, 4_213.0, 5_000.0, 6_781.0, 8_942.0, 12_311.0)

    private fun every(meters: Double): List<Workout> = listOf(
        Workouts.easy(meters, paces, "x"),
        Workouts.recovery(meters, paces, "x"),
        Workouts.longRun(meters, paces, "x"),
        Workouts.progression(meters, paces, "x"),
        Workouts.strides(meters, paces, "x"),
        Workouts.steady(meters, paces, "x"),
        Workouts.tempo(meters, paces, "x"),
        Workouts.cruiseIntervals(meters, paces, "x"),
        Workouts.intervals(meters, paces, "x"),
        Workouts.hills(meters, paces, "x"),
        Workouts.fartlek(meters, paces, "x"),
        Workouts.repetitions(meters, paces, "x"),
    )

    @Test
    fun `every prescribed distance is one a runner can pace`() {
        budgets.forEach { budget ->
            every(budget).forEach { workout ->
                workout.steps.forEach { step ->
                    step.distanceM?.let {
                        assertTrue("${workout.type} step ${step.label} is $it m", it % 50.0 == 0.0)
                    }
                    step.recoveryM?.let {
                        assertTrue("${workout.type} jog is $it m", it % 50.0 == 0.0)
                    }
                }
            }
        }
    }

    /**
     * The total is the session, not an estimate of it.
     *
     * Asserted against the steps read independently rather than against [Workouts.metersOf],
     * which would only be checking that a function agrees with itself. The version of this
     * that did exactly that passed while hill repeats were being counted at their warm-up
     * and their jogs, because the uphills carry no pace band and the measurement went
     * looking for one.
     */
    @Test
    fun `the total is exactly what the session comes to`() {
        budgets.forEach { budget ->
            every(budget).forEach { workout ->
                assertEquals("${workout.type} at $budget m", expected(workout), workout.totalMeters, 1.0)
            }
        }
    }

    /**
     * A second, dumber sum: every step's own distance, taken at face value, plus the jogs
     * between the reps. It knows nothing about pace bands, which is the point — a session
     * whose work cannot be measured this way is one the week cannot count.
     */
    private fun expected(workout: Workout): Double = workout.steps.sumOf { step ->
        val reps = step.repeats
        val work = requireNotNull(step.distanceM ?: viaPace(step)) {
            "${workout.type}: step '${step.label}' has no distance and no way to reach one"
        }
        val jog = step.recoveryM ?: step.recoveryMs?.let { ms ->
            val band = requireNotNull(step.recoveryPaceSecPerKm) { "jog with no pace" }
            Workouts.metersAt((band.start + band.endInclusive) / 2, ms)
        } ?: 0.0
        work * reps + jog * (reps - 1)
    }

    private fun viaPace(step: WorkoutStep): Double? {
        val ms = step.durationMs ?: return null
        val band = step.paceSecPerKm ?: return null
        return Workouts.metersAt((band.start + band.endInclusive) / 2, ms)
    }

    /** Every session is worth some distance, and the hard ones are worth most of it. */
    @Test
    fun `a hill session counts its uphills`() {
        val hills = Workouts.hills(5_000.0, paces, "x")
        val jogsAndTrimmings = hills.steps
            .filter { it.label.startsWith("Warm") || it.label.startsWith("Cool") }
            .sumOf { it.distanceM ?: 0.0 }
        assertTrue(
            "hills came to ${hills.totalMeters} m, of which ${jogsAndTrimmings} m is trimmings",
            hills.totalMeters > jogsAndTrimmings + hills.qualityMeters,
        )
    }

    @Test
    fun `every session is worth some distance`() {
        budgets.forEach { budget ->
            every(budget).forEach { workout ->
                assertTrue("${workout.type} is ${workout.totalMeters} m", workout.totalMeters > 0)
            }
        }
    }

    @Test
    fun `a derived rep duration is a whole number of minutes or a quarter of one`() {
        budgets.forEach { budget ->
            listOf(
                Workouts.tempo(budget, paces, "x"),
                Workouts.cruiseIntervals(budget, paces, "x"),
                Workouts.intervals(budget, paces, "x"),
            ).forEach { workout ->
                workout.steps.forEach { step ->
                    step.durationMs?.let {
                        assertTrue("${workout.type} step is $it ms", it % 15_000L == 0L)
                    }
                }
            }
        }
    }

    /** Strides are twenty seconds because somebody chose twenty. Rounding would rewrite it. */
    @Test
    fun `a chosen duration is left alone`() {
        val strides = Workouts.strides(8_000.0, paces, "x")
        assertEquals(20_000L, strides.steps.last().durationMs)
    }
}

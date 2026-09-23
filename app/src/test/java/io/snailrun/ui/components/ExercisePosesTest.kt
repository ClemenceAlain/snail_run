package io.snailrun.ui.components

import io.snailrun.domain.coach.Strength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every move the coach prescribes has a drawing, and every drawing fits its box. */
class ExercisePosesTest {

    /** Both routines, since the routine alternates week by week. */
    private val prescribed = (0L..1L).flatMap { week -> Strength.session(week).steps.map { it.label } }

    @Test
    fun `every prescribed exercise has a drawing`() {
        prescribed.forEach { name ->
            assertNotNull("no drawing for $name", ExercisePoses.forExercise(name))
        }
    }

    @Test
    fun `every pose stays inside the unit square and on or above the floor`() {
        prescribed.forEach { name ->
            val move = ExercisePoses.forExercise(name)!!
            listOf(0f, 0.5f, 1f).map(move::at).flatMap { it.joints }.forEach { p ->
                assertTrue("$name: $p", p.x in 0f..1f && p.y in 0f..ExercisePoses.FLOOR)
            }
        }
    }

    @Test
    fun `a move blends from its start to its end`() {
        val move = ExercisePoses.forExercise("Squats")!!
        assertEquals(move.start, move.at(0f))
        assertEquals(move.end, move.at(1f))
    }
}

package io.snailrun.domain.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutSegmentsTest {

    private val paces = Fitness.pacesFor(50.0)

    @Test
    fun `a tempo is a warm up, the block, and a cool down`() {
        val segments = WorkoutSegments.of(Workouts.tempo(4_000.0, paces, "because"))
        assertEquals(3, segments.size)
        assertEquals(
            listOf(SegmentKind.WarmUp, SegmentKind.Work, SegmentKind.CoolDown),
            segments.map { it.kind },
        )
        assertNull("nothing to count reps of", segments[1].repIndex)
    }

    @Test
    fun `intervals unroll into reps with a jog between each`() {
        val segments = WorkoutSegments.of(Workouts.intervals(4_000.0, paces, "because"))
        val reps = segments.filter { it.kind == SegmentKind.Work }
        val jogs = segments.filter { it.kind == SegmentKind.Recover }

        assertTrue("four to six reps, got ${reps.size}", reps.size in 4..6)
        assertEquals("one jog fewer than reps", reps.size - 1, jogs.size)
        assertEquals((1..reps.size).toList(), reps.map { it.repIndex })
        assertTrue(reps.all { it.repCount == reps.size })
    }

    /**
     * Nobody jogs a recovery and then immediately jogs a cool-down. The cue would be
     * telling the runner to do what they are already doing.
     */
    @Test
    fun `the session does not end on a recovery jog`() {
        listOf(
            Workouts.intervals(4_000.0, paces, "x"),
            Workouts.repetitions(1_600.0, paces, "x"),
            Workouts.hills(3_000.0, paces, "x"),
            Workouts.cruiseIntervals(5_000.0, paces, "x"),
        ).forEach { workout ->
            val segments = WorkoutSegments.of(workout)
            val lastWork = segments.indexOfLast { it.kind == SegmentKind.Work }
            assertEquals(
                "${workout.type} jogged after its last rep",
                SegmentKind.CoolDown,
                segments[lastWork + 1].kind,
            )
        }
    }

    @Test
    fun `every segment ends on a time or a distance, never both`() {
        listOf(
            Workouts.intervals(4_000.0, paces, "x"),
            Workouts.tempo(4_000.0, paces, "x"),
            Workouts.repetitions(1_600.0, paces, "x"),
            Workouts.hills(3_000.0, paces, "x"),
            Workouts.fartlek(3_000.0, paces, "x"),
            Workouts.steady(8_000.0, paces, "x"),
        ).forEach { workout ->
            WorkoutSegments.of(workout).forEach { segment ->
                assertTrue(
                    "${workout.type} ${segment.label} has ${segment.targetMs} and ${segment.targetM}",
                    (segment.targetMs == null) != (segment.targetM == null),
                )
            }
        }
    }

    @Test
    fun `the indices run from zero without a gap`() {
        val segments = WorkoutSegments.of(Workouts.repetitions(1_600.0, paces, "x"))
        assertEquals(segments.indices.toList(), segments.map { it.index })
    }

    /**
     * The rep is what is in front of the runner, so "Hard" — not "Hard, equal jog
     * between", which describes the block and is what the plan screen wants.
     */
    @Test
    fun `a rep is labelled with the rep and not with the block`() {
        val segments = WorkoutSegments.of(Workouts.intervals(4_000.0, paces, "x"))
        assertEquals("Hard", segments.first { it.kind == SegmentKind.Work }.label)
    }

    @Test
    fun `an easy run has nothing to count and is one segment`() {
        val segments = WorkoutSegments.of(Workouts.easy(8_000.0, paces, "x"))
        assertEquals(1, segments.size)
        assertTrue(segments.none { it.kind == SegmentKind.Work })
    }

    @Test
    fun `a rest day flattens to nothing rather than to an error`() {
        assertTrue(WorkoutSegments.of(Workouts.rest("x")).isEmpty())
    }
}

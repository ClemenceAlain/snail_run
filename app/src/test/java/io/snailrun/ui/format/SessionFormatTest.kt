package io.snailrun.ui.format

import io.snailrun.domain.coach.SegmentKind
import io.snailrun.domain.coach.TrainingPaces
import io.snailrun.domain.coach.WorkoutSegments
import io.snailrun.domain.coach.Workouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detail view's arithmetic, which is all of it that can be got wrong silently.
 *
 * The block list is what a runner reads before leaving the house *and* what lights up
 * mid-session, so a row covering the wrong segment range is a session that highlights the
 * cool-down while the runner is on rep three.
 */
class SessionFormatTest {

    private val paces = TrainingPaces(
        easySecPerKm = 372.0..432.0,
        marathonSecPerKm = 327.0,
        thresholdSecPerKm = 309.0,
        intervalSecPerKm = 285.0,
        repetitionSecPerKm = 265.0,
    )

    private fun blocks(workout: io.snailrun.domain.coach.Workout) =
        SessionFormat.blocks(WorkoutSegments.of(workout))

    @Test
    fun `an easy run is one row`() {
        val rows = blocks(Workouts.easy(8_000.0, paces, "x"))
        assertEquals(1, rows.size)
        assertEquals("Easy", rows.single().title)
        assertTrue(rows.single().detail.contains("8.0"))
        assertNull(rows.single().recovery)
    }

    @Test
    fun `reps are folded back into one row that names the jog`() {
        val rows = blocks(Workouts.intervals(4_000.0, paces, "x"))
        assertEquals(3, rows.size)
        assertEquals("Warm up", rows[0].title)
        assertEquals("Cool down", rows[2].title)

        val work = rows[1]
        assertTrue("title was ${work.title}", work.title.matches(Regex("""\d+ × Hard""")))
        assertEquals(SegmentKind.Work, work.kind)
        assertNotNull("a rep block with no jog described", work.recovery)
    }

    /**
     * Every segment belongs to exactly one row, in order. This is the property the live
     * highlight depends on — a gap and the current step lights nothing, an overlap and it
     * lights two.
     */
    @Test
    fun `the rows cover the session exactly once`() {
        listOf(
            Workouts.intervals(4_000.0, paces, "x"),
            Workouts.hills(5_000.0, paces, "x"),
            Workouts.repetitions(2_000.0, paces, "x"),
            Workouts.cruiseIntervals(6_000.0, paces, "x"),
            Workouts.tempo(6_000.0, paces, "x"),
            Workouts.strides(9_000.0, paces, "x"),
        ).forEach { workout ->
            val segments = WorkoutSegments.of(workout)
            val covered = SessionFormat.blocks(segments).flatMap { it.range.toList() }
            assertEquals("${workout.type}", segments.indices.toList(), covered)
        }
    }

    // ---- the units ---------------------------------------------------------------------

    @Test
    fun `a distance under a kilometre is said in metres`() {
        assertEquals("200 m", SessionFormat.distance(200.0))
        assertEquals("1.2 km", SessionFormat.distance(1_200.0))
    }

    @Test
    fun `a duration is only ever as precise as it needs to be`() {
        assertEquals("45 s", SessionFormat.duration(45_000))
        assertEquals("8 min", SessionFormat.duration(8 * 60_000))
        assertEquals("4:30", SessionFormat.duration(270_000))
    }

    /** One decimal. A prescribed distance is a decision, and none of them are to the metre. */
    @Test
    fun `kilometres never carry a second decimal`() {
        assertEquals("6.3 km", SessionFormat.kmWithUnit(6_312.0))
        assertEquals("6.3 km", SessionFormat.kmWithUnit(6_300.0))
        assertEquals("10.0 km", SessionFormat.kmWithUnit(10_000.0))
    }

    @Test
    fun `a strength exercise reads as sets and reps`() {
        val session = io.snailrun.domain.coach.Strength.session(0)
        val lines = session.steps.map { SessionFormat.strengthStep(it) }
        assertTrue(lines.any { it.matches(Regex("""\d+ × \d+ \D+""")) })
        assertTrue(lines.any { it.contains("s ") })
    }
}

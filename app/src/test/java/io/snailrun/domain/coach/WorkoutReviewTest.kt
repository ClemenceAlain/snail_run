package io.snailrun.domain.coach

import io.snailrun.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutReviewTest {

    /** Warm up 1 km, 2 × (2 min hard, 1 min jog), cool down 1 km. */
    private val segments = listOf(
        WorkoutSegment(0, "Warm up", SegmentKind.WarmUp, targetM = 1_000.0, paceSecPerKm = 330.0..360.0),
        WorkoutSegment(1, "Hard", SegmentKind.Work, targetMs = 120_000, paceSecPerKm = 240.0..240.0, repIndex = 1, repCount = 2),
        WorkoutSegment(2, "Jog", SegmentKind.Recover, targetMs = 60_000, paceSecPerKm = 360.0..390.0),
        WorkoutSegment(3, "Hard", SegmentKind.Work, targetMs = 120_000, paceSecPerKm = 240.0..240.0, repIndex = 2, repCount = 2),
        WorkoutSegment(4, "Cool down", SegmentKind.CoolDown, targetM = 1_000.0, paceSecPerKm = 330.0..360.0),
    )

    /**
     * A track at a steady speed, one fix a second. Distance is what the run actually
     * covered; the prescription is what it was asked for.
     */
    private fun track(seconds: Int, metresPerSecond: Double): List<TrackPoint> =
        (0..seconds).map { second ->
            TrackPoint(
                seq = second,
                segment = 0,
                timestampMs = second * 1000L,
                lat = 0.0,
                lon = 0.0,
                cumulativeDistanceM = second * metresPerSecond,
            )
        }

    @Test
    fun `nothing to review without a session or a track`() {
        assertTrue(WorkoutReview.of(emptyList(), emptyList(), track(100, 4.0)).isEmpty())
        assertTrue(WorkoutReview.of(segments, emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `every segment of a finished session is reported once`() {
        // 4 m/s: 250 s warm-up, 120 rep, 60 jog, 120 rep, 250 cool-down.
        val results = WorkoutReview.of(segments, emptyList(), track(810, 4.0))
        assertEquals(5, results.size)
        assertEquals(segments.map { it.label }, results.map { it.segment.label })
    }

    @Test
    fun `a rep is measured over the rep and not over the run`() {
        val results = WorkoutReview.of(segments, emptyList(), track(810, 4.0))
        val rep = results[1]
        assertEquals(120_000L, rep.actualMs)
        assertEquals(480.0, rep.actualMeters, 1.0)
        // 4 m/s is 250 s/km.
        assertEquals(250.0, rep.actualPaceSecPerKm!!, 1.0)
    }

    @Test
    fun `a rep run slower than asked reads as slower`() {
        val results = WorkoutReview.of(segments, emptyList(), track(810, 4.0))
        // Target 240 s/km, ran 250: ten seconds a kilometre down.
        assertEquals(10.0, results[1].paceDeltaSecPerKm!!, 1.0)
        assertTrue(!results[1].onTarget)
    }

    @Test
    fun `a rep inside its band reads as on target`() {
        // 1000/240 m/s is exactly the prescribed pace.
        val results = WorkoutReview.of(segments, emptyList(), track(810, 1000.0 / 240.0))
        assertEquals(0.0, results[1].paceDeltaSecPerKm!!, 0.5)
        assertTrue(results[1].onTarget)
    }

    @Test
    fun `a segment the runner cut short is measured where they cut it`() {
        // Skip the warm-up after 100 s.
        val results = WorkoutReview.of(segments, listOf(100_000L), track(810, 4.0))
        assertEquals(100_000L, results[0].actualMs)
        assertEquals(400.0, results[0].actualMeters, 1.0)
        // The rep still ran its full two minutes, from the skip.
        assertEquals(120_000L, results[1].actualMs)
    }

    @Test
    fun `a session stopped halfway still reports what was run`() {
        // Stopped 60 s into the first rep.
        val results = WorkoutReview.of(segments, emptyList(), track(310, 4.0))
        assertEquals(2, results.size)
        assertEquals("Hard", results[1].segment.label)
        assertEquals(60_000L, results[1].actualMs)
    }

    @Test
    fun `a segment with no target pace has nothing to be off`() {
        val noPace = listOf(WorkoutSegment(0, "Uphill hard", SegmentKind.Work, targetMs = 45_000))
        val results = WorkoutReview.of(noPace, emptyList(), track(100, 4.0))
        assertNull(results.single().paceDeltaSecPerKm)
        assertTrue("nothing to miss is not a miss", results.single().onTarget)
    }

    /**
     * A dropout. The fixes stop for four minutes and the runner passes a rep and a jog
     * inside the gap; the boundaries are interpolated, and every segment is still
     * accounted for exactly once.
     */
    @Test
    fun `a gap in the fixes does not lose a rep`() {
        val points = (track(250, 4.0) + track(810, 4.0).drop(500))
            .mapIndexed { i, point -> point.copy(seq = i) }
        val results = WorkoutReview.of(segments, emptyList(), points)

        assertEquals(5, results.size)
        assertEquals(segments.map { it.label }, results.map { it.segment.label })
        // The time is still the session's, not the wall clock's: nothing was double-counted.
        assertTrue(results.sumOf { it.actualMs } <= 810_000L)
    }

    @Test
    fun `faster is better on a rep, and the mistake on a jog`() {
        fun result(kind: SegmentKind, seconds: Long) = SegmentResult(
            segment = WorkoutSegment(0, "x", kind, targetMs = 60_000, paceSecPerKm = 300.0..300.0),
            actualMs = seconds * 1000,
            actualMeters = 1_000.0,
        )
        assertEquals(PaceVerdict.Better, result(SegmentKind.Work, 290).verdict)
        assertEquals(PaceVerdict.Worse, result(SegmentKind.Work, 310).verdict)
        assertEquals(PaceVerdict.Worse, result(SegmentKind.Recover, 290).verdict)
        assertEquals(PaceVerdict.Better, result(SegmentKind.Recover, 310).verdict)
        assertEquals(PaceVerdict.OnTarget, result(SegmentKind.Work, 300).verdict)
    }

    @Test
    fun `each segment knows where along the run it began`() {
        val results = WorkoutReview.of(segments, emptyList(), track(810, 4.0))
        assertEquals(0.0, results[0].startedAtMeters, 0.0)
        assertEquals(1_000.0, results[1].startedAtMeters, 1.0)
        assertEquals(1_480.0, results[2].startedAtMeters, 1.0)
    }
}

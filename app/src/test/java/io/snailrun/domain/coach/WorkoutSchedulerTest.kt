package io.snailrun.domain.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutSchedulerTest {

    /** Warm up 1 km, 2 × (2 min hard, 1 min jog), cool down 1 km. */
    private val segments = listOf(
        WorkoutSegment(0, "Warm up", SegmentKind.WarmUp, targetM = 1_000.0, paceSecPerKm = 330.0..360.0),
        WorkoutSegment(1, "Hard", SegmentKind.Work, targetMs = 120_000, paceSecPerKm = 234.0..234.0, repIndex = 1, repCount = 2),
        WorkoutSegment(2, "Jog", SegmentKind.Recover, targetMs = 60_000, paceSecPerKm = 360.0..390.0),
        WorkoutSegment(3, "Hard", SegmentKind.Work, targetMs = 120_000, paceSecPerKm = 234.0..234.0, repIndex = 2, repCount = 2),
        WorkoutSegment(4, "Cool down", SegmentKind.CoolDown, targetM = 1_000.0, paceSecPerKm = 330.0..360.0),
    )

    private fun scheduler(config: WorkoutCueConfig = WorkoutCueConfig()) =
        WorkoutScheduler(segments, config)

    /** Walks a session, one fix a second at a steady pace, collecting what it says. */
    private fun run(
        seconds: Int,
        metresPerSecond: Double = 4.0,
        pace: Double? = null,
        config: WorkoutCueConfig = WorkoutCueConfig(),
        advanceAt: Set<Int> = emptySet(),
    ): Pair<List<WorkoutCue>, WorkoutCursor> {
        val s = scheduler(config)
        var cursor = WorkoutCursor()
        val cues = mutableListOf<WorkoutCue>()
        for (second in 0..seconds) {
            val activeMs = second * 1000L
            val meters = second * metresPerSecond
            if (second in advanceAt) cursor = s.advance(activeMs, meters, cursor)
            val (_, fired, next) = s.evaluate(activeMs, meters, pace, cursor)
            cues += fired
            cursor = next
        }
        return cues to cursor
    }

    private fun starts(cues: List<WorkoutCue>) = cues.filterIsInstance<WorkoutCue.StepStart>()

    // ---- boundaries -------------------------------------------------------------------

    @Test
    fun `the session opens by naming its first step`() {
        val (cues, _) = run(seconds = 0)
        assertEquals(1, cues.size)
        assertEquals("Warm up", (cues.single() as WorkoutCue.StepStart).segment.label)
    }

    @Test
    fun `a distance segment ends on metres`() {
        // 1 km warm-up at 4 m/s is 250 s.
        val (before, _) = run(seconds = 249)
        assertEquals(1, starts(before).size)
        val (after, _) = run(seconds = 250)
        assertEquals(2, starts(after).size)
        assertEquals("Hard", starts(after)[1].segment.label)
    }

    @Test
    fun `a time segment ends on active duration`() {
        val (cues, _) = run(seconds = 250 + 120)
        assertEquals(listOf("Warm up", "Hard", "Jog"), starts(cues).map { it.segment.label })
    }

    /**
     * A rep that ends between two fixes hands the overshoot to the next one, so five reps
     * do not quietly become five reps and five seconds.
     */
    @Test
    fun `a rep that ends between fixes does not lengthen the session`() {
        val s = scheduler()
        var cursor = WorkoutCursor()
        val (_, _, opened) = s.evaluate(0, 0.0, null, cursor)
        // Warm-up ends at 1000 m; this fix is already 40 m past it.
        val (_, _, intoRep) = s.evaluate(260_000L, 1_040.0, null, opened)
        assertEquals(1, intoRep.segmentIndex)
        assertEquals(
            "the rep starts where the warm-up ended, not where the fix landed",
            1_000.0,
            intoRep.segmentStartedMeters,
            0.001,
        )
    }

    /**
     * Active duration is what is handed in, and a pause simply stops it advancing. There
     * is no pause case anywhere in the scheduler, which is the point.
     */
    @Test
    fun `standing still does not burn the rep`() {
        val s = scheduler()
        var cursor = WorkoutCursor()
        var cues = mutableListOf<WorkoutCue>()

        // Into the first rep.
        listOf(0, 250, 300).forEach { second ->
            val (_, fired, next) = s.evaluate(second * 1000L, second * 4.0, null, cursor)
            cues += fired
            cursor = next
        }
        assertEquals(2, starts(cues).size)

        // Five minutes at a light: active duration does not move, so nothing happens.
        repeat(5) {
            val (_, fired, next) = s.evaluate(300_000L, 1_200.0, null, cursor)
            assertTrue(fired.isEmpty())
            cursor = next
        }
        assertEquals(1, cursor.segmentIndex)
    }

    /**
     * Out of a tunnel three segments late, "rep two, hard" is the useful sentence.
     * "Hard, jog, hard" in one breath is noise, and the engine speaks one utterance at a
     * time anyway, so only the last would ever be heard.
     */
    @Test
    fun `a long gap moves the runner on and says where they now are, once`() {
        val s = scheduler()
        val cursor = WorkoutCursor()
        val (_, _, c1) = s.evaluate(0, 0.0, null, cursor)

        // A tunnel. One fix at 400 s and 1600 m, at 4 m/s: the warm-up ended at 250 s and
        // 1000 m, the first rep at 370 s, and the runner is 30 s into the jog.
        val (progress, cues, next) = s.evaluate(400_000L, 1_600.0, null, c1)

        assertEquals("one cue, for where they are now", 1, starts(cues).size)
        assertEquals("Jog", starts(cues).single().segment.label)
        assertEquals(2, next.segmentIndex)
        assertEquals(2, progress.segmentsDone)
        assertEquals(370_000L, next.segmentStartedActiveMs)
        assertEquals(30_000L, progress.remainingMs!!)
    }

    @Test
    fun `the last segment ends the session`() {
        val (cues, cursor) = run(seconds = 250 + 120 + 60 + 120 + 250 + 1)
        assertTrue(cues.any { it is WorkoutCue.Finished })
        assertTrue(cursor.complete)
    }

    @Test
    fun `nothing is said after the session is over`() {
        val s = scheduler()
        val cursor = WorkoutCursor(segmentIndex = 4, complete = true, announcedIndex = -2)
        val (progress, cues, next) = s.evaluate(999_000L, 5_000.0, 240.0, cursor)
        assertTrue(cues.isEmpty())
        assertTrue(progress.complete)
        assertTrue(next.complete)
    }

    // ---- countdown --------------------------------------------------------------------

    @Test
    fun `the countdown says three, two, one and each of them once`() {
        val (cues, _) = run(seconds = 250 + 120)
        val counted = cues.filterIsInstance<WorkoutCue.Countdown>().map { it.seconds }
        assertEquals(listOf(3, 2, 1), counted)
    }

    @Test
    fun `a distance segment has no countdown`() {
        // Only the warm-up has run at 249 s, and it ends on metres.
        val (cues, _) = run(seconds = 249)
        assertTrue(cues.filterIsInstance<WorkoutCue.Countdown>().isEmpty())
    }

    @Test
    fun `a repeated fix cannot count the same second twice`() {
        val s = scheduler()
        val cursor = WorkoutCursor(
            segmentIndex = 1,
            announcedIndex = 1,
            segmentStartedActiveMs = 0,
        )
        val (_, first, c1) = s.evaluate(118_000L, 500.0, null, cursor)
        assertEquals(listOf(2), first.filterIsInstance<WorkoutCue.Countdown>().map { it.seconds })
        val (_, again, _) = s.evaluate(118_000L, 500.0, null, c1)
        assertTrue(again.filterIsInstance<WorkoutCue.Countdown>().isEmpty())
    }

    // ---- skipping ---------------------------------------------------------------------

    @Test
    fun `pressing next ends the segment where the runner is`() {
        val (cues, cursor) = run(seconds = 60, advanceAt = setOf(30))
        assertEquals(listOf("Warm up", "Hard"), starts(cues).map { it.segment.label })
        assertEquals(1, cursor.segmentIndex)
    }

    @Test
    fun `the segment after a skip is measured from the skip`() {
        val s = scheduler()
        var cursor = WorkoutCursor()
        val (_, _, c1) = s.evaluate(0, 0.0, null, cursor)
        // Skip the warm-up after 30 s, then run the 2 min rep from there.
        val skipped = s.advance(30_000L, 120.0, c1)

        // The skip put the runner into the rep, so the next call names it.
        val (_, entering, inRep) = s.evaluate(30_000L, 120.0, null, skipped)
        assertEquals("Hard", starts(entering).single().segment.label)

        // Two minutes from the skip, not two minutes from the start of the run.
        val (_, early, _) = s.evaluate(149_000L, 600.0, null, inRep)
        assertTrue("the rep ended early", starts(early).isEmpty())
        val (_, over, _) = s.evaluate(150_000L, 604.0, null, inRep)
        assertEquals("Jog", starts(over).single().segment.label)
    }

    // ---- off-pace ---------------------------------------------------------------------

    private val nudging = WorkoutCueConfig(nudgeOffPace = true)

    @Test
    fun `nothing is said about pace unless it was asked for`() {
        val (cues, _) = run(seconds = 500, pace = 180.0)
        assertTrue(cues.filterIsInstance<WorkoutCue.OffPace>().isEmpty())
    }

    @Test
    fun `a pace inside the band is left alone`() {
        val (cues, _) = run(seconds = 500, pace = 236.0, config = nudging)
        assertTrue(cues.filterIsInstance<WorkoutCue.OffPace>().isEmpty())
    }

    /** The reps here are two minutes, under the ninety-second-plus floor by design. */
    @Test
    fun `a rep too short to fix is not commented on`() {
        val short = WorkoutScheduler(
            listOf(WorkoutSegment(0, "Hard", SegmentKind.Work, targetMs = 45_000, paceSecPerKm = 234.0..234.0)),
            WorkoutCueConfig(nudgeOffPace = true),
        )
        var cursor = WorkoutCursor()
        val cues = mutableListOf<WorkoutCue>()
        for (second in 0..44) {
            val (_, fired, next) = short.evaluate(second * 1000L, second * 4.0, 300.0, cursor)
            cues += fired
            cursor = next
        }
        assertTrue(cues.filterIsInstance<WorkoutCue.OffPace>().isEmpty())
    }

    @Test
    fun `drifting off pace is called out once it has lasted`() {
        val long = WorkoutScheduler(
            listOf(WorkoutSegment(0, "Tempo", SegmentKind.Work, targetMs = 600_000, paceSecPerKm = 255.0..255.0)),
            WorkoutCueConfig(nudgeOffPace = true),
        )
        var cursor = WorkoutCursor()
        val fired = mutableListOf<Pair<Int, WorkoutCue.OffPace>>()
        for (second in 0..200) {
            val (_, cues, next) = long.evaluate(second * 1000L, second * 4.0, 300.0, cursor)
            cues.filterIsInstance<WorkoutCue.OffPace>().forEach { fired += second to it }
            cursor = next
        }

        assertTrue("nothing before twenty seconds", fired.none { it.first < 20 })
        assertEquals("too slow", false, fired.first().second.tooFast)
        // Capped at one a minute over three and a bit minutes of drifting.
        assertTrue("said ${fired.size} times", fired.size <= 4)
        fired.zipWithNext().forEach { (a, b) -> assertTrue(b.first - a.first >= 60) }
    }

    @Test
    fun `coming back onto pace resets the clock on the nudge`() {
        val long = WorkoutScheduler(
            listOf(WorkoutSegment(0, "Tempo", SegmentKind.Work, targetMs = 600_000, paceSecPerKm = 255.0..255.0)),
            WorkoutCueConfig(nudgeOffPace = true),
        )
        var cursor = WorkoutCursor()
        // Nineteen seconds off, one second back on, nineteen off again: never twenty in a row.
        for (second in 0..200) {
            val pace = if (second % 20 == 19) 255.0 else 300.0
            val (_, cues, next) = long.evaluate(second * 1000L, second * 4.0, pace, cursor)
            assertTrue("spoke at $second", cues.filterIsInstance<WorkoutCue.OffPace>().isEmpty())
            cursor = next
        }
    }

    @Test
    fun `running too fast is called out as too fast`() {
        val long = WorkoutScheduler(
            listOf(WorkoutSegment(0, "Tempo", SegmentKind.Work, targetMs = 600_000, paceSecPerKm = 255.0..255.0)),
            WorkoutCueConfig(nudgeOffPace = true),
        )
        var cursor = WorkoutCursor()
        var seen: WorkoutCue.OffPace? = null
        for (second in 0..60) {
            val (_, cues, next) = long.evaluate(second * 1000L, second * 4.0, 230.0, cursor)
            cues.filterIsInstance<WorkoutCue.OffPace>().firstOrNull()?.let { seen = it }
            cursor = next
        }
        assertEquals(true, seen?.tooFast)
    }

    // ---- replay -----------------------------------------------------------------------

    /**
     * What makes crash recovery work. The cursor is never written down; the recorder
     * rebuilds it by running the whole track through again, so the two have to agree.
     */
    @Test
    fun `replaying the track reproduces the cursor exactly`() {
        val (_, live) = run(seconds = 400)

        val s = scheduler()
        var replayed = WorkoutCursor()
        for (second in 0..400) {
            val (_, _, next) = s.evaluate(second * 1000L, second * 4.0, null, replayed)
            replayed = next
        }
        assertEquals(live, replayed)
    }

    @Test
    fun `progress counts what is left of the segment`() {
        val s = scheduler()
        var cursor = WorkoutCursor()
        val (_, _, c1) = s.evaluate(0, 0.0, null, cursor)
        val (progress, _, _) = s.evaluate(100_000L, 400.0, null, c1)

        assertEquals("Warm up", progress.segment.label)
        assertEquals(600.0, progress.remainingM!!, 0.1)
        assertNull("a distance segment has no clock", progress.remainingMs)
        assertEquals(0, progress.segmentsDone)
        assertEquals(5, progress.segmentCount)
        assertFalse(progress.complete)
    }

    @Test
    fun `a session with no segments is over before it starts`() {
        val (progress, cues, cursor) = WorkoutScheduler(emptyList())
            .evaluate(0, 0.0, null, WorkoutCursor())
        assertTrue(cues.isEmpty())
        assertTrue(progress.complete)
        assertTrue(cursor.complete)
    }
}

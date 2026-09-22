package io.snailrun.domain.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A session on the floor, counted through.
 *
 * The property everything else hangs off is the first test: half the stages end on a
 * clock and half of them cannot. Getting that wrong in either direction is a feature that
 * does not work — a plank that waits for a tap, or a set of squats the app cuts off in
 * the middle of.
 */
class StrengthSessionTest {

    /** 2 × 10 squats, then 2 × 30 s plank. Four sets, three rests between them. */
    private val workout = Workout(
        type = WorkoutType.Strength,
        totalMeters = 0.0,
        steps = listOf(
            WorkoutStep("Squats", repeats = 2, countPerSet = 10),
            WorkoutStep("Plank", repeats = 2, durationMs = 30_000L),
        ),
        reason = "x",
    )

    private val stages = StrengthSession.stages(workout, restSeconds = 45)
    private fun session() = StrengthSession(stages)

    // ---- unrolling ---------------------------------------------------------------------

    @Test
    fun `every set becomes a stage, with a rest between but not after`() {
        assertEquals(7, stages.size)
        assertEquals(
            listOf("Squats", "Rest", "Squats", "Rest", "Plank", "Rest", "Plank"),
            stages.map { it.exercise },
        )
        assertEquals(StrengthStageKind.Work, stages.last().kind)
    }

    @Test
    fun `a stage knows where it is in the session and in its exercise`() {
        assertEquals(1 to 2, stages[0].set to stages[0].setCount)
        assertEquals(2 to 2, stages[2].set to stages[2].setCount)
        assertEquals(1, stages[0].exerciseIndex)
        assertEquals(2, stages[4].exerciseIndex)
        assertEquals(2, stages[0].exerciseCount)
    }

    @Test
    fun `a counted set carries reps and no clock, a held one the reverse`() {
        assertFalse(stages[0].isTimed)
        assertEquals(10, stages[0].reps)
        assertTrue(stages[4].isTimed)
        assertEquals(30_000L, stages[4].durationMs)
        assertNull(stages[4].reps)
    }

    @Test
    fun `per side survives the unrolling`() {
        val oneSided = Workout(
            WorkoutType.Strength, 0.0,
            listOf(WorkoutStep("Split squats", repeats = 1, countPerSet = 10, perSide = true)),
            "x",
        )
        assertTrue(StrengthSession.stages(oneSided).single().perSide)
    }

    // ---- what ends on a clock and what does not ------------------------------------------

    @Test
    fun `a counted set never ends on its own, however long you leave it`() {
        val s = session()
        val (progress, _, cursor) = s.evaluate(10 * 60_000L, StrengthCursor())
        assertEquals("Squats", progress.stage.exercise)
        assertEquals(0, cursor.stageIndex)
        assertNull("a counted set must not show a countdown", progress.remainingMs)
    }

    @Test
    fun `saying it is done is what moves a counted set on`() {
        val s = session()
        var cursor = s.evaluate(0, StrengthCursor()).third
        cursor = s.advance(4_000L, cursor)
        val progress = s.progressOf(cursor, 4_000L)
        assertEquals("Rest", progress.stage.exercise)
        assertEquals(45_000L, progress.remainingMs)
    }

    @Test
    fun `a rest ends itself`() {
        val s = session()
        var cursor = s.advance(4_000L, s.evaluate(0, StrengthCursor()).third)
        // One tick before, and one after.
        assertEquals("Rest", s.evaluate(48_000L, cursor).first.stage.exercise)
        cursor = s.evaluate(49_100L, cursor).third
        assertEquals("Squats", s.progressOf(cursor, 49_100L).stage.exercise)
        assertEquals(2, s.progressOf(cursor, 49_100L).stage.set)
    }

    @Test
    fun `a held set ends itself`() {
        val s = session()
        var cursor = StrengthCursor(stageIndex = 4, stageStartedMs = 100_000L, announcedIndex = 4)
        assertEquals("Plank", s.progressOf(cursor, 120_000L).stage.exercise)
        cursor = s.evaluate(131_000L, cursor).third
        assertEquals("Rest", s.progressOf(cursor, 131_000L).stage.exercise)
    }

    /** A phone that slept through a rest should come back with the next set started. */
    @Test
    fun `a long gap advances as many stages as it covers`() {
        val s = session()
        val cursor = StrengthCursor(stageIndex = 4, stageStartedMs = 0L, announcedIndex = 4)
        // 30 s plank, 45 s rest, 30 s plank = 105 s of timed stages from here.
        val (progress, _, after) = s.evaluate(200_000L, cursor)
        assertTrue("session should be over, was ${progress.stage.exercise}", after.complete)
    }

    @Test
    fun `a stage that ran its course hands the overshoot on`() {
        val s = session()
        val cursor = StrengthCursor(stageIndex = 4, stageStartedMs = 0L, announcedIndex = 4)
        // The plank was due at 30 s; the tick lands at 30.9.
        val after = s.evaluate(30_900L, cursor).third
        // The rest therefore started at 30 s, not at 30.9 — so 5 s in, 40 remain.
        assertEquals(40_000L, s.progressOf(after, 35_000L).remainingMs)
    }

    // ---- what it says ----------------------------------------------------------------------

    @Test
    fun `the session opens by naming its first exercise`() {
        val cues = session().evaluate(0, StrengthCursor()).second
        val start = cues.filterIsInstance<StrengthCue.StageStart>().single()
        assertEquals("Squats", start.stage.exercise)
        assertEquals("Rest", start.next?.exercise)
    }

    @Test
    fun `a held stage counts down once each and only downwards`() {
        val s = session()
        var cursor = StrengthCursor(stageIndex = 4, stageStartedMs = 0L, announcedIndex = 4)
        val spoken = mutableListOf<Int>()
        // Four ticks a second through the last five seconds of a 30 s plank.
        for (ms in 25_000L..30_000L step 250L) {
            val (_, cues, next) = s.evaluate(ms, cursor)
            cues.filterIsInstance<StrengthCue.Countdown>().forEach { spoken += it.seconds }
            cursor = next
        }
        assertEquals(listOf(3, 2, 1), spoken)
    }

    @Test
    fun `the end is announced once`() {
        val s = session()
        var cursor = StrengthCursor(stageIndex = 6, stageStartedMs = 0L, announcedIndex = 6)
        cursor = s.advance(1_000L, cursor)
        val first = s.evaluate(1_000L, cursor)
        assertTrue(first.second.contains(StrengthCue.Finished))
        assertTrue(first.first.complete)
        assertFalse(s.evaluate(2_000L, first.third).second.contains(StrengthCue.Finished))
    }

    @Test
    fun `an empty session is complete rather than broken`() {
        val empty = StrengthSession(emptyList())
        val (progress, _, cursor) = empty.evaluate(0, StrengthCursor())
        assertTrue(progress.complete)
        assertTrue(cursor.complete)
    }

    /** The real routines, since those are what anyone will actually be counted through. */
    @Test
    fun `every planned routine unrolls into something startable`() {
        (0L..3L).forEach { week ->
            val session = Strength.session(week)
            val stages = StrengthSession.stages(session)
            assertTrue(stages.isNotEmpty())
            assertEquals(StrengthStageKind.Work, stages.first().kind)
            assertEquals(StrengthStageKind.Work, stages.last().kind)
            stages.filter { it.kind == StrengthStageKind.Work }.forEach {
                assertTrue(
                    "${it.exercise} is counted in neither reps nor seconds",
                    it.reps != null || it.seconds != null,
                )
            }
        }
    }
}

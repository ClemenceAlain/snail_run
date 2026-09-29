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

    /**
     * Two rounds of 10 squats then a 30 s plank. Four sets, three rests between them:
     * short inside a round, long between the two.
     */
    private val workout = Workout(
        type = WorkoutType.Strength,
        totalMeters = 0.0,
        steps = listOf(
            WorkoutStep("Squats", repeats = 2, countPerSet = 10),
            WorkoutStep("Plank", repeats = 2, durationMs = 30_000L),
        ),
        reason = "x",
    )

    private val stages = StrengthSession.stages(workout, moveRestSeconds = 15, roundRestSeconds = 60)
    private fun session() = StrengthSession(stages)

    // ---- unrolling ---------------------------------------------------------------------

    @Test
    fun `the session is a circuit, with a rest between sets but not after the last`() {
        assertEquals(
            listOf("Squats", "Rest", "Plank", "Rest", "Squats", "Rest", "Plank"),
            stages.map { it.exercise },
        )
        assertEquals(StrengthStageKind.Work, stages.last().kind)
    }

    @Test
    fun `the rest between rounds is longer than the rest between moves`() {
        assertEquals(15, stages[1].seconds)
        assertEquals(60, stages[3].seconds)
        assertEquals(15, stages[5].seconds)
    }

    @Test
    fun `a stage knows its round and its exercise`() {
        assertEquals(1 to 2, stages[0].round to stages[0].roundCount)
        assertEquals(2 to 2, stages[4].round to stages[4].roundCount)
        assertEquals(1, stages[0].exerciseIndex)
        assertEquals(2, stages[2].exerciseIndex)
        assertEquals(2, stages[0].exerciseCount)
        assertEquals(stages.indices.toList(), stages.map { it.index })
    }

    @Test
    fun `an exercise with fewer sets drops out of the later rounds`() {
        val uneven = Workout(
            WorkoutType.Strength, 0.0,
            listOf(
                WorkoutStep("Squats", repeats = 2, countPerSet = 10),
                WorkoutStep("Plank", repeats = 1, durationMs = 30_000L),
            ),
            "x",
        )
        assertEquals(
            listOf("Squats", "Rest", "Plank", "Rest", "Squats"),
            StrengthSession.stages(uneven).map { it.exercise },
        )
    }

    @Test
    fun `a counted set carries reps and no clock, a held one the reverse`() {
        assertFalse(stages[0].isTimed)
        assertEquals(10, stages[0].reps)
        assertTrue(stages[2].isTimed)
        assertEquals(30_000L, stages[2].durationMs)
        assertNull(stages[2].reps)
    }

    @Test
    fun `a counted set done per side is counted once a side`() {
        val oneSided = Workout(
            WorkoutType.Strength, 0.0,
            listOf(WorkoutStep("Split squats", repeats = 1, countPerSet = 10, perSide = true)),
            "x",
        )
        val unrolled = StrengthSession.stages(oneSided, switchSeconds = 5)
        assertEquals(
            listOf(StrengthStageKind.Work, StrengthStageKind.Switch, StrengthStageKind.Work),
            unrolled.map { it.kind },
        )
        assertEquals(StrengthSide.Left to 10, unrolled[0].side to unrolled[0].reps)
        assertEquals(5, unrolled[1].seconds)
        assertEquals(StrengthSide.Right to 10, unrolled[2].side to unrolled[2].reps)
    }

    /** "30 s each side" is thirty on the left and thirty on the right, each on its own clock. */
    @Test
    fun `a held set done per side gets the full time on each side`() {
        val oneSided = Workout(
            WorkoutType.Strength, 0.0,
            listOf(WorkoutStep("Side plank", repeats = 1, durationMs = 30_000L, perSide = true)),
            "x",
        )
        val work = StrengthSession.stages(oneSided).filter { it.kind == StrengthStageKind.Work }
        assertEquals(listOf(StrengthSide.Left, StrengthSide.Right), work.map { it.side })
        assertEquals(listOf(30_000L, 30_000L), work.map { it.durationMs })
    }

    @Test
    fun `a switch between sides ends itself`() {
        val oneSided = Workout(
            WorkoutType.Strength, 0.0,
            listOf(WorkoutStep("Split squats", repeats = 1, countPerSet = 10, perSide = true)),
            "x",
        )
        val s = StrengthSession(StrengthSession.stages(oneSided, switchSeconds = 5))
        val cursor = s.advance(4_000L, s.evaluate(0, StrengthCursor()).third)
        assertEquals(StrengthStageKind.Switch, s.progressOf(cursor, 4_000L).stage.kind)
        val after = s.evaluate(9_100L, cursor).third
        assertEquals(StrengthSide.Right, s.progressOf(after, 9_100L).stage.side)
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
        assertEquals(15_000L, progress.remainingMs)
    }

    @Test
    fun `a rest ends itself`() {
        val s = session()
        var cursor = s.advance(4_000L, s.evaluate(0, StrengthCursor()).third)
        // One tick before, and one after.
        assertEquals("Rest", s.evaluate(18_000L, cursor).first.stage.exercise)
        cursor = s.evaluate(19_100L, cursor).third
        assertEquals("Plank", s.progressOf(cursor, 19_100L).stage.exercise)
        assertEquals(1, s.progressOf(cursor, 19_100L).stage.round)
    }

    @Test
    fun `a held set ends itself`() {
        val s = session()
        var cursor = StrengthCursor(stageIndex = 2, stageStartedMs = 100_000L, announcedIndex = 2)
        assertEquals("Plank", s.progressOf(cursor, 120_000L).stage.exercise)
        cursor = s.evaluate(131_000L, cursor).third
        assertEquals("Rest", s.progressOf(cursor, 131_000L).stage.exercise)
    }

    /** A phone that slept through a rest should come back with the next set started. */
    @Test
    fun `a long gap advances as many stages as it covers`() {
        val s = session()
        val cursor = StrengthCursor(stageIndex = 2, stageStartedMs = 0L, announcedIndex = 2)
        // 30 s plank, then 60 s rest: the squats of round 2 started at 90 s, and wait.
        val (progress, _, after) = s.evaluate(200_000L, cursor)
        assertEquals(4, after.stageIndex)
        assertEquals(110_000L, progress.elapsedInStageMs)
    }

    @Test
    fun `a stage that ran its course hands the overshoot on`() {
        val s = session()
        val cursor = StrengthCursor(stageIndex = 2, stageStartedMs = 0L, announcedIndex = 2)
        // The plank was due at 30 s; the tick lands at 30.9.
        val after = s.evaluate(30_900L, cursor).third
        // The rest therefore started at 30 s, not at 30.9 — so 5 s in, 55 remain.
        assertEquals(55_000L, s.progressOf(after, 35_000L).remainingMs)
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
        var cursor = StrengthCursor(stageIndex = 2, stageStartedMs = 0L, announcedIndex = 2)
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
            // Every left side is followed, after its switch, by the right.
            stages.filter { it.side == StrengthSide.Left }.forEach {
                assertEquals(StrengthStageKind.Switch, stages[it.index + 1].kind)
                assertEquals(StrengthSide.Right, stages[it.index + 2].side)
                assertEquals(it.exercise, stages[it.index + 2].exercise)
            }
        }
    }
}

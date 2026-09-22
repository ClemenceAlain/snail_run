package io.snailrun.domain.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A coach that asks for 913 metres is asking for a number, not for a session.
 *
 * The grids themselves are the assertions here, because they are the whole contract:
 * every figure the plan hands a runner has to be one they can act on without deciding
 * for themselves how much of it to ignore.
 */
class RoundTest {

    @Test
    fun `a rep distance lands on fifty metres`() {
        assertEquals(900.0, Round.repMeters(913.0), 0.0)
        assertEquals(950.0, Round.repMeters(926.0), 0.0)
        assertEquals(200.0, Round.repMeters(200.0), 0.0)
    }

    @Test
    fun `a block distance lands on a hundred metres`() {
        assertEquals(6_300.0, Round.blockMeters(6_312.0), 0.0)
        assertEquals(6_400.0, Round.blockMeters(6_351.0), 0.0)
    }

    /** A step of zero metres would be counted through the instant it started. */
    @Test
    fun `nothing rounds away to nothing`() {
        assertEquals(50.0, Round.repMeters(4.0), 0.0)
        assertEquals(100.0, Round.blockMeters(1.0), 0.0)
        assertEquals(15_000L, Round.stepMs(200L))
    }

    @Test
    fun `a long step is whole minutes and a short one is quarter minutes`() {
        assertEquals(5 * 60_000L, Round.stepMs(4 * 60_000L + 37_000L))
        assertEquals(45_000L, Round.stepMs(47_000L))
        assertEquals(60_000L, Round.stepMs(58_000L))
    }

    /** Negative and zero are not inputs the planner produces, but they are inputs. */
    @Test
    fun `nothing in gives nothing out`() {
        assertEquals(0.0, Round.blockMeters(0.0), 0.0)
        assertEquals(0.0, Round.repMeters(-5.0), 0.0)
        assertEquals(0L, Round.stepMs(0L))
    }

    @Test
    fun `rounding never moves a figure by more than half the grid`() {
        (1..4_000).forEach { metres ->
            assertTrue(
                "$metres rounded to ${Round.repMeters(metres.toDouble())}",
                kotlin.math.abs(Round.repMeters(metres.toDouble()) - metres) <= 25.0 ||
                    metres < 50,
            )
        }
    }
}

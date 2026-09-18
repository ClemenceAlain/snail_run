package io.snailrun.domain.coach

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The equations against Daniels' own published table.
 *
 * These are the assertions the whole coach rests on. If a training pace here drifts by
 * fifteen seconds a kilometre nothing else in the app notices, and the runner finds out
 * on their next interval session.
 */
class VdotTest {

    /** 20:00 for 5 km is the table's anchor row, and lands on VDOT 50. */
    private val vdot50 = Vdot.fromEffort(5_000.0, 20 * 60_000L)!!

    private fun pace(fraction: Double) = Vdot.paceSecPerKm(vdot50, fraction).roundToInt()

    /** Daniels' table is printed to the second; three seconds either way is agreement. */
    private fun assertPace(expected: Int, actual: Int) =
        assertTrue("expected ${expected}s/km, got ${actual}s/km", abs(expected - actual) <= 3)

    @Test
    fun `a twenty minute five k is VDOT fifty`() {
        assertEquals(49.8, vdot50, 0.1)
    }

    @Test
    fun `easy pace spans five minutes to five thirty-eight`() {
        assertPace(5 * 60 + 0, pace(Vdot.EASY_HIGH))
        assertPace(5 * 60 + 38, pace(Vdot.EASY_LOW))
    }

    @Test
    fun `marathon pace is four thirty`() {
        assertPace(4 * 60 + 30, pace(Vdot.MARATHON))
    }

    @Test
    fun `threshold pace is four fifteen`() {
        assertPace(4 * 60 + 15, pace(Vdot.THRESHOLD))
    }

    @Test
    fun `interval pace is three fifty-four`() {
        assertPace(3 * 60 + 54, pace(Vdot.INTERVAL))
    }

    @Test
    fun `repetition pace is three thirty-nine`() {
        assertPace(3 * 60 + 39, pace(Vdot.REPETITION))
    }

    @Test
    fun `the intensities are ordered`() {
        assertTrue(pace(Vdot.EASY_LOW) > pace(Vdot.EASY_HIGH))
        assertTrue(pace(Vdot.EASY_HIGH) > pace(Vdot.MARATHON))
        assertTrue(pace(Vdot.MARATHON) > pace(Vdot.THRESHOLD))
        assertTrue(pace(Vdot.THRESHOLD) > pace(Vdot.INTERVAL))
        assertTrue(pace(Vdot.INTERVAL) > pace(Vdot.REPETITION))
    }

    @Test
    fun `predicting a time inverts reading one`() {
        listOf(1_000.0, 5_000.0, 10_000.0, 21_097.0, 42_195.0).forEach { meters ->
            val time = Vdot.timeMsFor(vdot50, meters)!!
            val back = Vdot.fromEffort(meters, time)!!
            assertEquals("round trip over $meters m", vdot50, back, 0.05)
        }
    }

    /**
     * Cross-checked against Riegel, which is an entirely different fit to entirely
     * different data. Two independent formulas agreeing to within a few per cent is what
     * rules out a sign error that a self-consistent round trip would happily pass.
     */
    @Test
    fun `a ten k prediction agrees with Riegel`() {
        val fiveK = 20 * 60_000L
        val riegel = fiveK * (10_000.0 / 5_000.0).pow(1.06)
        val predicted = Vdot.timeMsFor(vdot50, 10_000.0)!!
        assertTrue(
            "Daniels ${predicted / 1000}s vs Riegel ${riegel.toLong() / 1000}s",
            abs(predicted - riegel) / riegel < 0.03,
        )
    }

    @Test
    fun `a marathon prediction is slower than four times the ten k`() {
        val tenK = Vdot.timeMsFor(vdot50, 10_000.0)!!
        val marathon = Vdot.timeMsFor(vdot50, 42_195.0)!!
        assertTrue(marathon > tenK * 4)
    }

    @Test
    fun `nonsense in gives nothing back rather than an answer`() {
        assertNull(Vdot.fromEffort(0.0, 60_000L))
        assertNull(Vdot.fromEffort(5_000.0, 0L))
        assertNull(Vdot.timeMsFor(0.0, 5_000.0))
    }
}

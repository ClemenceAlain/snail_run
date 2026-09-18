package io.snailrun.domain.coach

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FitnessTest {

    private val today = LocalDate.of(2026, 9, 18)

    private fun effort(meters: Int, seconds: Long, daysAgo: Long) =
        RecentEffort(meters, seconds * 1000, today.minusDays(daysAgo))

    @Test
    fun `no efforts means no estimate rather than a guess`() {
        assertNull(Fitness.estimate(emptyList(), today))
    }

    @Test
    fun `a five k effort is trusted`() {
        val estimate = Fitness.estimate(listOf(effort(5_000, 20 * 60, 5)), today)!!
        assertEquals(Confidence.Solid, estimate.confidence)
        assertEquals(5_000, estimate.fromDistanceM)
        assertEquals(49.8, estimate.vdot, 0.1)
    }

    @Test
    fun `a kilometre on its own is provisional`() {
        val estimate = Fitness.estimate(listOf(effort(1_000, 3 * 60 + 30, 2)), today)!!
        assertEquals(Confidence.Provisional, estimate.confidence)
        assertEquals(1_000, estimate.fromDistanceM)
    }

    /**
     * The whole safety argument in one test. The kilometre implies a far higher VDOT than
     * the ten, and it is the ten that must win — a fast kilometre inside a training run is
     * a surge, and pricing interval pace off it sends the runner out too fast.
     */
    @Test
    fun `a long effort outranks a faster short one`() {
        val fastKm = effort(1_000, 3 * 60 + 10, 3)
        val tenK = effort(10_000, 45 * 60, 4)

        assertTrue(
            "the short effort really does imply more",
            Vdot.fromEffort(1_000.0, fastKm.durationMs)!! > Vdot.fromEffort(10_000.0, tenK.durationMs)!!,
        )

        val estimate = Fitness.estimate(listOf(fastKm, tenK), today)!!
        assertEquals(10_000, estimate.fromDistanceM)
        assertEquals(Confidence.Solid, estimate.confidence)
    }

    @Test
    fun `the best of several long efforts wins`() {
        val estimate = Fitness.estimate(
            listOf(effort(5_000, 22 * 60, 30), effort(5_000, 20 * 60, 10)),
            today,
        )!!
        assertEquals(today.minusDays(10), estimate.fromDate)
    }

    @Test
    fun `an effort older than ten weeks says nothing about today`() {
        assertNull(Fitness.estimate(listOf(effort(5_000, 20 * 60, 71)), today))
    }

    @Test
    fun `an effort dated in the future is ignored`() {
        assertNull(Fitness.estimate(listOf(effort(5_000, 20 * 60, -1)), today))
    }

    @Test
    fun `paces come out in the right order`() {
        val paces = Fitness.pacesFor(50.0)
        assertTrue(paces.easySecPerKm.start > paces.marathonSecPerKm)
        assertTrue(paces.easySecPerKm.start < paces.easySecPerKm.endInclusive)
        assertTrue(paces.marathonSecPerKm > paces.thresholdSecPerKm)
        assertTrue(paces.thresholdSecPerKm > paces.intervalSecPerKm)
        assertTrue(paces.intervalSecPerKm > paces.repetitionSecPerKm)
    }
}

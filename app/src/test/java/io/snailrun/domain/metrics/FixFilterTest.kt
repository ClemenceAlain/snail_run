package io.snailrun.domain.metrics

import io.snailrun.domain.fixtures.Traces
import io.snailrun.domain.model.RawFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixFilterTest {

    private fun totalDistance(filter: FixFilter, fixes: List<RawFix>): Double =
        fixes.sumOf { (filter.apply(it) as? FilterResult.Accepted)?.distanceDeltaM ?: 0.0 }

    @Test
    fun `a fix with no accuracy is rejected`() {
        val result = FixFilter().apply(Traces.fix(0.0, Traces.START_MS).copy(accuracyM = null))
        assertEquals(FilterResult.Rejected(RejectReason.NO_ACCURACY), result)
    }

    @Test
    fun `an imprecise fix is rejected`() {
        val result = FixFilter().apply(Traces.fix(0.0, Traces.START_MS, accuracyM = 80f))
        assertEquals(FilterResult.Rejected(RejectReason.INACCURATE), result)
    }

    @Test
    fun `a stale fix is rejected`() {
        val stale = Traces.fix(0.0, Traces.START_MS).copy(ageMs = 9_000)
        assertEquals(FilterResult.Rejected(RejectReason.STALE), FixFilter().apply(stale))
    }

    @Test
    fun `a mock fix is rejected unless allowed`() {
        val mock = Traces.fix(0.0, Traces.START_MS).copy(isMock = true)
        assertEquals(FilterResult.Rejected(RejectReason.MOCK), FixFilter().apply(mock))
        assertTrue(
            FixFilter(FilterConfig(allowMock = true)).apply(mock) is FilterResult.Accepted
        )
    }

    @Test
    fun `duplicate deliveries within half a second are dropped`() {
        val filter = FixFilter()
        filter.apply(Traces.fix(0.0, Traces.START_MS))
        val result = filter.apply(Traces.fix(1.0, Traces.START_MS + 100))
        assertEquals(FilterResult.Rejected(RejectReason.TOO_SOON), result)
    }

    @Test
    fun `a single outlier is rejected without stranding the filter`() {
        val filter = FixFilter()
        filter.apply(Traces.fix(0.0, Traces.START_MS))
        val outlier = filter.apply(Traces.teleport(Traces.START_MS + 1000, metresAway = 800.0))
        assertEquals(FilterResult.Rejected(RejectReason.TELEPORT), outlier)

        // The run carries on normally afterwards.
        val next = filter.apply(Traces.fix(4.0, Traces.START_MS + 2000))
        assertTrue(next is FilterResult.Accepted)
    }

    @Test
    fun `three outliers in a row resync rather than rejecting forever`() {
        val filter = FixFilter()
        filter.apply(Traces.fix(0.0, Traces.START_MS))
        // A tunnel exit: the phone reappears 800 m away and stays there.
        val results = (1..3).map {
            filter.apply(Traces.teleport(Traces.START_MS + it * 1000L, metresAway = 800.0 + it * 3))
        }
        assertTrue(results[0] is FilterResult.Rejected)
        assertTrue(results[1] is FilterResult.Rejected)
        assertTrue("the third outlier must resync", results[2] is FilterResult.Accepted)
        // Resync must not invent the 800 m jump as distance covered.
        assertEquals(0.0, (results[2] as FilterResult.Accepted).distanceDeltaM, 1e-9)
    }

    @Test
    fun `standing still adds no distance`() {
        val filter = FixFilter()
        val still = (0..30).map { Traces.fix((it % 2) * 0.9, Traces.START_MS + it * 1000L) }
        assertEquals(0.0, totalDistance(filter, still), 1e-9)
    }

    @Test
    fun `a runner at five thirty per kilometre is measured accurately`() {
        // 3.03 m/s sits right on the 3 m jitter floor. Comparing each fix only to its
        // predecessor would drop about half the steps and undercount the whole run.
        val fixes = Traces.steadyRun(seconds = 600, speedMps = 3.03)
        val measured = totalDistance(FixFilter(), fixes)
        val expected = 600 * 3.03
        assertEquals(expected, measured, expected * 0.01)
    }

    @Test
    fun `a slow walk still accumulates distance`() {
        val fixes = Traces.steadyRun(seconds = 300, speedMps = 1.2)
        val measured = totalDistance(FixFilter(), fixes)
        assertEquals(300 * 1.2, measured, 300 * 1.2 * 0.02)
    }

    @Test
    fun `reset makes the next fix start a fresh segment`() {
        val filter = FixFilter()
        filter.apply(Traces.fix(0.0, Traces.START_MS))
        filter.reset()
        // After a pause the runner is 500 m away; that gap must not be counted.
        val result = filter.apply(Traces.fix(500.0, Traces.START_MS + 600_000))
        assertEquals(0.0, (result as FilterResult.Accepted).distanceDeltaM, 1e-9)
    }
}

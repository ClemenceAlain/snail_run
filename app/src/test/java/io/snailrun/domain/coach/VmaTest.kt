package io.snailrun.domain.coach

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** VMA in, VDOT and paces out, and the six-minute test read back off a run. */
class VmaTest {

    private val today = LocalDate.of(2026, 10, 5)

    @Test
    fun `a 15 km per hour VMA is a VDOT in the mid forties`() {
        val vdot = Vmas.vdotOf(15.0)!!
        assertTrue("VDOT was $vdot", vdot in 44.0..45.0)
    }

    @Test
    fun `105 per cent of 14 km per hour is 4 05 a kilometre`() {
        assertEquals(245.0, Vmas.paceSecPerKm(14.0, 1.05), 1.0)
    }

    @Test
    fun `working a VMA back out of its own VDOT lands where it started`() {
        listOf(10.0, 13.5, 17.0, 20.0).forEach { kmh ->
            assertEquals(kmh, Vmas.fromVdot(Vmas.vdotOf(kmh)!!), 0.05)
        }
    }

    @Test
    fun `the test distance over a hundred is the VMA`() {
        assertEquals(14.5, Vmas.fromTestDistance(1_450.0), 1e-9)
    }

    private fun result(kind: SegmentKind, targetMs: Long?, ms: Long, meters: Double) = SegmentResult(
        segment = WorkoutSegment(0, "x", kind, targetMs = targetMs),
        actualMs = ms,
        actualMeters = meters,
    )

    @Test
    fun `the test is read off the six-minute segment and not the warm-up`() {
        val results = listOf(
            result(SegmentKind.WarmUp, 15 * 60_000L, 15 * 60_000L, 2_500.0),
            result(SegmentKind.Work, Vmas.TEST_MS, Vmas.TEST_MS, 1_480.0),
            result(SegmentKind.CoolDown, 10 * 60_000L, 10 * 60_000L, 1_600.0),
        )
        assertEquals(14.8, Vmas.fromTestResult(results)!!, 1e-9)
    }

    @Test
    fun `a test stopped early is not read at all`() {
        val results = listOf(result(SegmentKind.Work, Vmas.TEST_MS, 300_000L, 1_300.0))
        assertNull(Vmas.fromTestResult(results))
    }

    @Test
    fun `a VMA the runner gave wins over the efforts the app found`() {
        val efforts = listOf(RecentEffort(10_000, 40 * 60_000L, today.minusDays(3)))
        val basis = CoachFitness.basis(Vma(13.0, today, VmaSource.Typed), efforts, today)
        assertEquals(13.0, basis.vmaKmh!!, 1e-9)
        assertEquals(VmaOrigin.Typed, basis.origin)
        assertTrue(basis.fitness!!.fromVma)
    }

    @Test
    fun `without a VMA one is estimated from the best effort`() {
        val efforts = listOf(RecentEffort(10_000, 46 * 60_000L, today.minusDays(3)))
        val basis = CoachFitness.basis(null, efforts, today)
        assertEquals(VmaOrigin.Estimated, basis.origin)
        assertTrue("VMA was ${basis.vmaKmh}", basis.vmaKmh!! in 14.0..16.0)
    }

    @Test
    fun `with nothing at all there is no VMA and no fast pace`() {
        val basis = CoachFitness.basis(null, emptyList(), today)
        assertEquals(VmaOrigin.None, basis.origin)
        assertNull(basis.vmaBand(1.0..1.05))
    }
}

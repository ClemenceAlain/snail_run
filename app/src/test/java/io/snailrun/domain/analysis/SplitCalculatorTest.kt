package io.snailrun.domain.analysis

import io.snailrun.domain.fixtures.Traces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitCalculatorTest {

    @Test
    fun `an exact three kilometre run yields three full splits`() {
        // 3 m/s for 1000 s = 3000 m.
        val splits = SplitCalculator.compute(Traces.straightTrack(seconds = 1000, speedMps = 3.0))
        assertEquals(3, splits.count { !it.isPartial })
        splits.filter { !it.isPartial }.forEach {
            assertEquals(1000.0, it.distanceMeters, 1e-9)
            // 1000 m at 3 m/s is 333.3 s.
            assertEquals(333_333.0, it.durationMs.toDouble(), 500.0)
        }
    }

    @Test
    fun `a partial last kilometre is reported separately`() {
        // 3512 m at 3 m/s.
        val splits = SplitCalculator.compute(
            Traces.straightTrack(seconds = (3512 / 3.0).toInt(), speedMps = 3.0)
        )
        assertEquals(4, splits.size)
        assertEquals(3, splits.count { !it.isPartial })
        val partial = splits.last()
        assertTrue(partial.isPartial)
        assertEquals(512.0, partial.distanceMeters, 5.0)
    }

    @Test
    fun `boundaries are interpolated, not snapped to the nearest fix`() {
        // 7 m per fix, so no fix lands on 1000 m. Snapping would put the boundary up to
        // a whole second out; interpolation must land within a few milliseconds.
        val splits = SplitCalculator.compute(Traces.straightTrack(seconds = 400, speedMps = 7.0))
        val first = splits.first()
        assertEquals(1000.0 / 7.0 * 1000, first.durationMs.toDouble(), 20.0)
    }

    @Test
    fun `split pace matches the run pace`() {
        val splits = SplitCalculator.compute(Traces.straightTrack(seconds = 700, speedMps = 3.0))
        // 3 m/s is 5:33/km.
        assertEquals(333.3, splits.first().paceSecPerKm, 1.0)
    }

    @Test
    fun `a run shorter than one kilometre is a single partial split`() {
        val splits = SplitCalculator.compute(Traces.straightTrack(seconds = 100, speedMps = 3.0))
        assertEquals(1, splits.size)
        assertTrue(splits.single().isPartial)
        assertEquals(300.0, splits.single().distanceMeters, 1.0)
    }

    @Test
    fun `a track with fewer than two points has no splits`() {
        assertTrue(SplitCalculator.compute(emptyList()).isEmpty())
        assertTrue(SplitCalculator.compute(Traces.straightTrack(seconds = 0)).isEmpty())
    }

    @Test
    fun `a negligible tail is not reported as a split`() {
        val track = Traces.straightTrack(seconds = 1000, speedMps = 3.0)
        assertFalse(SplitCalculator.compute(track).last().isPartial)
    }
}

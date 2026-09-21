package io.snailrun.domain.metrics

import io.snailrun.domain.fixtures.Traces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackGapsTest {

    @Test
    fun `a second of missing GPS is not a gap at all`() {
        assertEquals(GapKind.Continuous, TrackGaps.classify(1_000, 3.0))
        assertEquals(GapKind.Continuous, TrackGaps.classify(29_000, 90.0))
    }

    @Test
    fun `three minutes under a tunnel at running speed is inferred`() {
        assertEquals(GapKind.Inferred, TrackGaps.classify(180_000, 540.0))
    }

    @Test
    fun `standing about with no signal is not running`() {
        // Ten metres in three minutes: the phone was on a table, not on a runner.
        assertEquals(GapKind.Broken, TrackGaps.classify(180_000, 10.0))
    }

    @Test
    fun `a ride across town is never credited as a run`() {
        // Four kilometres in four minutes is 60 km/h.
        assertEquals(GapKind.Broken, TrackGaps.classify(240_000, 4_000.0))
    }

    @Test
    fun `a hole longer than twenty minutes cannot be reconstructed`() {
        // Even at a believable pace, nothing says the line between the two was run.
        assertEquals(GapKind.Broken, TrackGaps.classify(25 * 60_000L, 4_500.0))
    }

    @Test
    fun `the clock runs through a dropout the runner ran through`() {
        val points = Traces.straightTrack(seconds = 60, speedMps = 3.0)
        val gapped = points.take(30) + points.drop(30).map {
            // The fixes stop for three minutes; the runner keeps running.
            it.copy(
                timestampMs = it.timestampMs + 180_000,
                cumulativeDistanceM = it.cumulativeDistanceM + 540.0,
            )
        }

        // Sixty seconds of fixes and three minutes of tunnel, all of it running.
        assertEquals(240_000L, TrackGaps.activeDurationOf(gapped))
    }

    @Test
    fun `no clock and no distance cross a hole that cannot be accounted for`() {
        val points = Traces.straightTrack(seconds = 60, speedMps = 3.0)
        // Half an hour later, at the same place: the run was over and nobody said so.
        val gapped = points.take(30) + points.drop(30).map {
            it.copy(timestampMs = it.timestampMs + 30 * 60_000L)
        }

        // The 29 seconds before the hole and the 30 after it, and nothing in between.
        assertEquals(59_000L, TrackGaps.activeDurationOf(gapped))
        assertTrue(TrackGaps.inferredLegs(gapped).isEmpty())
    }

    @Test
    fun `an inferred stretch is reported with the pace it implies`() {
        val points = Traces.straightTrack(seconds = 60, speedMps = 3.0)
        val gapped = points.take(30) + points.drop(30).map {
            it.copy(
                timestampMs = it.timestampMs + 120_000,
                cumulativeDistanceM = it.cumulativeDistanceM + 360.0,
            )
        }

        val legs = TrackGaps.inferredLegs(gapped)
        assertEquals(1, legs.size)
        assertEquals(121_000L, legs.first().gapMs)
        assertEquals(363.0, legs.first().meters, 0.001)
        // 3 m/s is 5:33/km.
        assertEquals(333.3, legs.first().paceSecPerKm!!, 1.0)
    }
}

package io.snailrun.domain.geo

import io.snailrun.domain.fixtures.Traces
import io.snailrun.domain.model.TrackPoint
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The smoother earns its place only if it beats the raw track against a known truth,
 * so every assertion here is scored against a fixture whose real length is exact.
 */
class TrackSmootherTest {

    /** Distance as the app would read it with no smoothing: sum of raw displacements. */
    private fun rawDistance(points: List<TrackPoint>): Double =
        points.zipWithNext().sumOf { (a, b) -> GeoDistance.between(a.lat, a.lon, b.lat, b.lon) }

    private fun smoothedDistance(points: List<TrackPoint>): Double =
        TrackSmoother.smooth(points).last().cumulativeDistanceM

    @Test
    fun `noise inflates a raw track, and the smoother takes it back out`() {
        val seconds = 1_200
        val speedMps = 3.0
        val truth = seconds * speedMps
        val points = Traces.noisyTrack(seconds = seconds, speedMps = speedMps, noiseM = 4.0)

        val raw = rawDistance(points)
        val smoothed = smoothedDistance(points)

        // Raw integration of jitter reads long; that is the whole problem.
        assertTrue("raw was $raw m against $truth m", raw > truth * 1.20)
        assertTrue(
            "smoothed was $smoothed m against $truth m",
            abs(smoothed - truth) / truth < 0.03,
        )
    }

    @Test
    fun `holds a straight noiseless track to the metre`() {
        val points = Traces.straightTrack(seconds = 600, speedMps = 3.0)

        assertEquals(1_800.0, smoothedDistance(points), 20.0)
    }

    @Test
    fun `pulls a reflection back onto the track instead of following it`() {
        val points = Traces.straightTrack(seconds = 120, speedMps = 3.0).toMutableList()
        val clean = TrackSmoother.smooth(points)[60]
        // One fix 400 m off to the side, the shape a reflection off a building takes.
        points[60] = points[60].copy(lat = points[60].lat + 400.0 / 111_212.0)

        val corrected = TrackSmoother.smooth(points)[60]

        val strayM = GeoDistance.between(clean.lat, clean.lon, corrected.lat, corrected.lon)
        assertTrue("the estimate moved $strayM m towards the reflection", strayM < 15.0)
        assertEquals(
            "a single outlier must not add distance",
            360.0,
            TrackSmoother.smooth(points).last().cumulativeDistanceM,
            25.0,
        )
    }

    @Test
    fun `accepts a real reposition once it stops being a one-off`() {
        // Leaving a tunnel: the receiver reacquires 300 m further on and stays there.
        val before = Traces.straightTrack(seconds = 60, speedMps = 3.0)
        val after = Traces.straightTrack(seconds = 60, speedMps = 3.0)
            .map { it.copy(lat = it.lat + 300.0 / 111_212.0, timestampMs = it.timestampMs + 61_000) }

        val smoothed = TrackSmoother.smooth(before + after)

        val last = smoothed.last()
        val truthLat = after.last().lat
        val offBy = GeoDistance.between(last.lat, last.lon, truthLat, last.lon)
        assertTrue("still $offBy m behind the reacquired position", offBy < 20.0)
    }

    @Test
    fun `carries velocity across a dropout without fighting the fix that ends it`() {
        val head = Traces.straightTrack(seconds = 60, speedMps = 3.0)
        // Thirty seconds of silence, then the runner reappears 90 m further on: exactly
        // where the pace says they should be.
        val tail = (1..60).map { i ->
            TrackPoint(
                seq = 60 + i,
                segment = 0,
                timestampMs = head.last().timestampMs + 30_000 + i * 1000L,
                lat = Traces.metresNorth(180.0 + 90.0 + i * 3.0),
                lon = Traces.START_LON,
                elevationM = 35.0,
                accuracyM = 5f,
                speedMps = 3f,
                cumulativeDistanceM = 0.0,
            )
        }

        val smoothed = TrackSmoother.smooth(head + tail)

        // 180 m, then 90 m across the hole, then 180 m more.
        assertEquals(450.0, smoothed.last().cumulativeDistanceM, 25.0)
    }

    @Test
    fun `never carries a step across a pause boundary`() {
        val first = Traces.straightTrack(seconds = 60, speedMps = 3.0, segment = 0)
        // The runner walks 500 m away while paused; the second segment starts there.
        val second = Traces.straightTrack(seconds = 60, speedMps = 3.0, segment = 1)
            .map { it.copy(seq = it.seq + 100, lat = it.lat + 500.0 / 111_212.0) }

        val smoothed = TrackSmoother.smooth(first + second)

        assertEquals(360.0, smoothed.last().cumulativeDistanceM, 25.0)
    }

    @Test
    fun `reports a speed a pace can be read from`() {
        val smoother = TrackSmoother()
        var last = smoother.onFix(0, Traces.START_LAT, Traces.START_LON, 5f)

        // Sixty seconds at 3 m/s: the velocity state should have converged by the end.
        for (i in 1..60) {
            last = smoother.onFix(i * 1000L, Traces.metresNorth(i * 3.0), Traces.START_LON, 5f)
        }

        assertEquals(3.0, last.speedMps, 0.4)
    }
}

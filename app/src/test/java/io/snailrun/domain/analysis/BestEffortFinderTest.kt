package io.snailrun.domain.analysis

import io.snailrun.domain.fixtures.Traces
import io.snailrun.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BestEffortFinderTest {

    @Test
    fun `a run shorter than the target has no effort for it`() {
        val track = Traces.straightTrack(seconds = 100, speedMps = 3.0) // 300 m
        assertNull(BestEffortFinder.find(track, 1_000))
    }

    @Test
    fun `a steady run's fastest kilometre matches its pace`() {
        val track = Traces.straightTrack(seconds = 1000, speedMps = 3.0)
        val effort = BestEffortFinder.find(track, 1_000)
        assertNotNull(effort)
        assertEquals(333_333.0, effort!!.durationMs.toDouble(), 1_500.0)
    }

    @Test
    fun `the fastest window is found wherever it sits in the run`() {
        // Slow, then a fast kilometre, then slow again.
        val points = mutableListOf<TrackPoint>()
        var distance = 0.0
        var t = Traces.START_MS
        var seq = 0
        fun leg(seconds: Int, speed: Double) {
            repeat(seconds) {
                points += TrackPoint(
                    seq = seq++, segment = 0, timestampMs = t,
                    lat = Traces.metresNorth(distance), lon = Traces.START_LON,
                    cumulativeDistanceM = distance,
                )
                distance += speed
                t += 1000
            }
        }
        leg(seconds = 400, speed = 2.5)   // 1000 m slow
        leg(seconds = 250, speed = 4.0)   // 1000 m fast
        leg(seconds = 400, speed = 2.5)   // 1000 m slow

        val effort = BestEffortFinder.find(points, 1_000)!!
        assertEquals(250_000.0, effort.durationMs.toDouble(), 6_000.0)
        // The fast leg starts 1000 m in, at second 400.
        assertEquals(400_000.0, effort.startOffsetMs.toDouble(), 8_000.0)
    }

    @Test
    fun `a negative split run picks the faster second half`() {
        val points = mutableListOf<TrackPoint>()
        var distance = 0.0
        var t = Traces.START_MS
        var seq = 0
        fun leg(seconds: Int, speed: Double) {
            repeat(seconds) {
                points += TrackPoint(
                    seq = seq++, segment = 0, timestampMs = t,
                    lat = Traces.metresNorth(distance), lon = Traces.START_LON,
                    cumulativeDistanceM = distance,
                )
                distance += speed
                t += 1000
            }
        }
        leg(seconds = 800, speed = 2.5)   // 2000 m at 6:40/km
        leg(seconds = 600, speed = 3.5)   // 2100 m at 4:46/km

        val effort = BestEffortFinder.find(points, 2_000)!!
        // The best 2 km must come from the closing stretch: 2000 m at 3.5 m/s = 571 s.
        assertTrue("got ${effort.durationMs} ms", effort.durationMs < 600_000)
    }

    @Test
    fun `standard distances only report the ones actually covered`() {
        val track = Traces.straightTrack(seconds = 2000, speedMps = 3.0) // 6000 m
        val efforts = BestEffortFinder.findAll(track)
        assertEquals(listOf(1_000, 5_000), efforts.map { it.distanceMeters })
    }
}

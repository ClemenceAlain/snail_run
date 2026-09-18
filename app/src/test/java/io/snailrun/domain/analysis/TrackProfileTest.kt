package io.snailrun.domain.analysis

import io.snailrun.domain.fixtures.Traces
import io.snailrun.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackProfileTest {

    /** A track whose pace and elevation are known exactly at every metre. */
    private fun track(
        seconds: Int,
        speedMps: Double = 3.0,
        climbPerSecond: Double = 0.0,
        segment: Int = 0,
        startSeq: Int = 0,
        startMs: Long = Traces.START_MS,
        startDistanceM: Double = 0.0,
    ): List<TrackPoint> = (0..seconds).map { i ->
        TrackPoint(
            seq = startSeq + i,
            segment = segment,
            timestampMs = startMs + i * 1000L,
            lat = Traces.metresNorth(startDistanceM + i * speedMps),
            lon = Traces.START_LON,
            elevationM = 40.0 + i * climbPerSecond,
            accuracyM = 5f,
            speedMps = speedMps.toFloat(),
            cumulativeDistanceM = startDistanceM + i * speedMps,
        )
    }

    @Test
    fun `samples span the whole run and end on its total distance`() {
        val samples = TrackProfile.sample(track(seconds = 1_200), buckets = 100)

        assertEquals(100, samples.size)
        assertEquals(3_600.0, samples.last().distanceM, 0.001)
        assertEquals(1_200_000L, samples.last().elapsedMs)
    }

    @Test
    fun `a steady run reads the same pace in every bucket`() {
        val samples = TrackProfile.sample(track(seconds = 1_200, speedMps = 3.0), buckets = 60)

        // 3 m/s is 333.3 s/km.
        samples.forEach { assertEquals(333.3, it.paceSecPerKm!!, 1.0) }
    }

    @Test
    fun `a selection averages exactly the stretch asked for`() {
        // Two kilometres at 3 m/s, then two at 4 m/s.
        val slow = track(seconds = 666, speedMps = 3.0)
        val fast = track(
            seconds = 500,
            speedMps = 4.0,
            startSeq = 700,
            startMs = Traces.START_MS + 667_000,
            startDistanceM = slow.last().cumulativeDistanceM,
        )

        val selection = TrackProfile.selection(slow + fast, fromM = 2_100.0, toM = 3_900.0)!!

        assertEquals(1_800.0, selection.distanceM, 0.001)
        // Entirely inside the fast half: 4 m/s is 250 s/km.
        assertEquals(250.0, selection.paceSecPerKm!!, 2.0)
        assertEquals(450_000.0, selection.durationMs.toDouble(), 2_000.0)
    }

    @Test
    fun `a selection that straddles a change of pace lands between the two`() {
        val slow = track(seconds = 666, speedMps = 3.0)
        val fast = track(
            seconds = 500,
            speedMps = 4.0,
            startSeq = 700,
            startMs = Traces.START_MS + 667_000,
            startDistanceM = slow.last().cumulativeDistanceM,
        )

        val selection = TrackProfile.selection(slow + fast, fromM = 1_000.0, toM = 3_000.0)!!

        assertTrue(selection.paceSecPerKm!! in 250.0..333.4)
    }

    @Test
    fun `a pause costs no time in a selection that contains it`() {
        val before = track(seconds = 300, speedMps = 3.0)
        // Ten minutes of pause, then the run resumes in a new segment from where it left.
        val after = track(
            seconds = 300,
            speedMps = 3.0,
            segment = 1,
            startSeq = 400,
            startMs = Traces.START_MS + 300_000 + 600_000,
            startDistanceM = before.last().cumulativeDistanceM,
        )

        val selection = TrackProfile.selection(before + after, fromM = 0.0, toM = 1_800.0)!!

        // 600 seconds of running, not 1200.
        assertEquals(600_000.0, selection.durationMs.toDouble(), 3_000.0)
        assertEquals(333.3, selection.paceSecPerKm!!, 3.0)
    }

    @Test
    fun `reports the climb over a selection`() {
        val climbing = track(seconds = 600, speedMps = 3.0, climbPerSecond = 0.1)

        val selection = TrackProfile.selection(climbing, fromM = 0.0, toM = 1_800.0)!!

        // 600 seconds at 0.1 m/s of climb is 60 m, less whatever the hysteresis holds back.
        assertTrue("gain was ${selection.elevationGainM}", selection.elevationGainM in 45.0..60.0)
    }

    @Test
    fun `refuses a selection too short to mean anything`() {
        val points = track(seconds = 600)

        assertNull(TrackProfile.selection(points, fromM = 500.0, toM = 500.5))
        assertNotNull(TrackProfile.selection(points, fromM = 500.0, toM = 600.0))
    }

    @Test
    fun `says nothing about pace where the runner was barely moving`() {
        // A metre a second is under the floor a pace can be read at.
        val crawling = track(seconds = 600, speedMps = 0.2)

        val samples = TrackProfile.sample(crawling, buckets = 20)

        assertTrue(samples.all { it.paceSecPerKm == null })
    }

    @Test
    fun `an empty or single-point track produces nothing rather than dividing by zero`() {
        assertEquals(emptyList<ProfileSample>(), TrackProfile.sample(emptyList()))
        assertEquals(emptyList<ProfileSample>(), TrackProfile.sample(track(seconds = 0)))
        assertNull(TrackProfile.selection(emptyList(), 0.0, 100.0))
    }
}

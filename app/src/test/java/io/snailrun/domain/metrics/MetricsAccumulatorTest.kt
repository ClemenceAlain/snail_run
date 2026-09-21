package io.snailrun.domain.metrics

import io.snailrun.domain.fixtures.Traces
import io.snailrun.domain.model.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsAccumulatorTest {

    @Test
    fun `a ten minute steady run measures its distance and duration`() {
        val accumulator = MetricsAccumulator()
        Traces.steadyRun(seconds = 600, speedMps = 3.0).forEach { accumulator.onFix(it) }

        val metrics = accumulator.metrics
        assertEquals(1800.0, metrics.distanceMeters, 1800.0 * 0.01)
        assertEquals(600_000L, metrics.activeDurationMs)
        assertEquals(5 * 60.0 + 33.3, metrics.averagePaceSecPerKm!!, 5.0)
    }

    @Test
    fun `time spent paused is excluded from active duration`() {
        val accumulator = MetricsAccumulator()
        val before = Traces.steadyRun(seconds = 60, speedMps = 3.0)
        before.forEach { accumulator.onFix(it) }
        val activeBefore = accumulator.metrics.activeDurationMs

        accumulator.pause()
        // Five minutes of fixes arrive while paused and must change nothing.
        (1..300).forEach {
            accumulator.onFix(Traces.fix(180.0, Traces.START_MS + 60_000 + it * 1000L))
        }
        assertEquals(activeBefore, accumulator.metrics.activeDurationMs)
        assertEquals(RunStatus.PAUSED_MANUAL, accumulator.metrics.status)
    }

    @Test
    fun `the gap crossed while paused is never counted as distance`() {
        val accumulator = MetricsAccumulator()
        Traces.steadyRun(seconds = 60, speedMps = 3.0).forEach { accumulator.onFix(it) }
        val distanceBefore = accumulator.metrics.distanceMeters

        accumulator.pause()
        accumulator.resume()
        // The runner resumes 2 km away, having taken the metro.
        accumulator.onFix(Traces.fix(2_180.0, Traces.START_MS + 600_000))

        assertEquals(distanceBefore, accumulator.metrics.distanceMeters, 1e-9)
        assertEquals(1, accumulator.metrics.segment)
    }

    @Test
    fun `losing GPS in a tunnel does not stop the clock`() {
        val accumulator = MetricsAccumulator()
        // Two minutes of running, three minutes of tunnel, two minutes more.
        Traces.runWithDropout(beforeSeconds = 120, dropoutSeconds = 180, afterSeconds = 120)
            .forEach { accumulator.onFix(it) }

        val metrics = accumulator.metrics
        // Seven minutes on the clock, not the four the chip could see.
        assertEquals(418_000L, metrics.activeDurationMs)
        // And the ground covered through it, as a straight line.
        assertEquals(418.0 * 3.0, metrics.distanceMeters, 418.0 * 3.0 * 0.03)
        // One segment: the itinerary is inferred, so the trace is not cut.
        assertEquals(0, metrics.segment)
    }

    @Test
    fun `the pace after a dropout is the one the dropout implies`() {
        val accumulator = MetricsAccumulator()
        Traces.runWithDropout(beforeSeconds = 60, dropoutSeconds = 120, afterSeconds = 1, speedMps = 3.0)
            .forEach { accumulator.onFix(it) }

        // 3 m/s is 5:33/km, whatever the chip happened to report on the fix that
        // ended the hole.
        assertEquals(333.3, accumulator.metrics.paceSecPerKm!!, 5.0)
    }

    @Test
    fun `a ride across town while the signal is gone is not counted as running`() {
        val accumulator = MetricsAccumulator()
        Traces.runWithDropout(
            beforeSeconds = 120,
            dropoutSeconds = 300,
            afterSeconds = 60,
            // Five kilometres in five minutes: the metro, not the runner.
            movedDuringDropoutM = 5_000.0,
        ).forEach { accumulator.onFix(it) }

        val metrics = accumulator.metrics
        // The two stretches that were recorded, and nothing between them. Two seconds
        // short of both, because the fix that ends the ride looks like a reflection
        // until it has been repeated — which is exactly what it should look like.
        assertEquals(176_000L, metrics.activeDurationMs)
        assertEquals(176.0 * 3.0, metrics.distanceMeters, 176.0 * 3.0 * 0.05)
        // A new segment, so nothing is drawn across the ride either.
        assertEquals(1, metrics.segment)
    }

    @Test
    fun `standing still with no signal buys no moving time`() {
        val accumulator = MetricsAccumulator()
        Traces.runWithDropout(
            beforeSeconds = 120,
            dropoutSeconds = 240,
            afterSeconds = 60,
            // Four minutes on a doorstep: the fix comes back where it left off.
            movedDuringDropoutM = 2.0,
        ).forEach { accumulator.onFix(it) }

        assertEquals(178_000L, accumulator.metrics.activeDurationMs)
    }

    @Test
    fun `pausing and resuming increments the segment so the trace is not bridged`() {
        val accumulator = MetricsAccumulator()
        accumulator.onFix(Traces.fix(0.0, Traces.START_MS))
        accumulator.pause()
        accumulator.resume()
        val outcome = accumulator.onFix(Traces.fix(10.0, Traces.START_MS + 60_000))
        assertEquals(1, (outcome as FixOutcome.Recorded).point.segment)
    }

    @Test
    fun `a stop at a traffic light does not inflate distance`() {
        val accumulator = MetricsAccumulator()
        Traces.runWithStop(beforeSeconds = 60, stillSeconds = 45, afterSeconds = 60)
            .forEach { accumulator.onFix(it) }
        // 120 steps of 3 m; the 45 s of jitter in between must contribute nothing.
        assertEquals(360.0, accumulator.metrics.distanceMeters, 15.0)
    }

    @Test
    fun `average pace is withheld until there is enough distance to mean anything`() {
        val accumulator = MetricsAccumulator()
        accumulator.onFix(Traces.fix(0.0, Traces.START_MS))
        accumulator.onFix(Traces.fix(4.0, Traces.START_MS + 1000))
        assertNull(accumulator.metrics.averagePaceSecPerKm)
    }

    @Test
    fun `replaying a track reproduces the live figures exactly`() {
        val fixes = Traces.steadyRun(seconds = 900, speedMps = 2.8)
        val live = MetricsAccumulator().also { a -> fixes.forEach { a.onFix(it) } }.metrics
        val replayed = MetricsAccumulator().also { a -> fixes.forEach { a.onFix(it) } }.metrics
        assertEquals(live.distanceMeters, replayed.distanceMeters, 0.0)
        assertEquals(live.activeDurationMs, replayed.activeDurationMs)
        assertEquals(live.elevationGainM, replayed.elevationGainM, 0.0)
    }

    @Test
    fun `smoothed pace settles near the true pace`() {
        val accumulator = MetricsAccumulator()
        Traces.steadyRun(seconds = 120, speedMps = 3.33).forEach { accumulator.onFix(it) }
        // 3.33 m/s is 5:00/km.
        assertEquals(300.0, accumulator.metrics.paceSecPerKm!!, 10.0)
    }

    @Test
    fun `flat GPS altitude noise does not invent elevation gain`() {
        val accumulator = MetricsAccumulator()
        var t = Traces.START_MS
        var distance = 0.0
        // A flat run whose reported altitude wanders by a few metres, as GPS does.
        repeat(300) { i ->
            val wobble = listOf(0.0, 2.5, -1.8, 3.1, -2.2, 1.4)[i % 6]
            accumulator.onFix(
                Traces.fix(distance, t, altitudeM = 40.0 + wobble, verticalAccuracyM = 5f)
            )
            distance += 3.0
            t += 1000
        }
        assertTrue(
            "flat run reported ${accumulator.metrics.elevationGainM} m of climb",
            accumulator.metrics.elevationGainM < 5.0,
        )
    }

    @Test
    fun `a sustained climb is counted`() {
        val accumulator = MetricsAccumulator()
        var t = Traces.START_MS
        var distance = 0.0
        // 300 s climbing 0.2 m/s: 60 m of real gain.
        repeat(300) { i ->
            accumulator.onFix(
                Traces.fix(distance, t, altitudeM = 40.0 + i * 0.2, verticalAccuracyM = 5f)
            )
            distance += 3.0
            t += 1000
        }
        assertEquals(60.0, accumulator.metrics.elevationGainM, 12.0)
    }

    @Test
    fun `a recovered run continues its totals instead of starting over`() {
        val first = MetricsAccumulator()
        val points = mutableListOf<io.snailrun.domain.model.TrackPoint>()
        Traces.steadyRun(seconds = 600, speedMps = 3.0).forEach { fix ->
            (first.onFix(fix) as? FixOutcome.Recorded)?.let { points += it.point }
        }
        val before = first.metrics

        // The process dies; a new accumulator picks the run up from its stored track.
        val recovered = MetricsAccumulator().apply { restore(points) }
        assertEquals(before.distanceMeters, recovered.metrics.distanceMeters, 1e-9)
        assertEquals(before.activeDurationMs, recovered.metrics.activeDurationMs)

        // Continuing adds to the restored totals rather than to zero.
        recovered.resume()
        recovered.onFix(Traces.fix(1810.0, Traces.START_MS + 620_000))
        recovered.onFix(Traces.fix(1813.0, Traces.START_MS + 621_000))
        assertTrue(recovered.metrics.distanceMeters >= before.distanceMeters)
    }

    @Test
    fun `a recovered run resumes in a new segment`() {
        val accumulator = MetricsAccumulator()
        val points = mutableListOf<io.snailrun.domain.model.TrackPoint>()
        Traces.steadyRun(seconds = 30, speedMps = 3.0).forEach { fix ->
            (accumulator.onFix(fix) as? FixOutcome.Recorded)?.let { points += it.point }
        }
        val recovered = MetricsAccumulator().apply { restore(points) }
        recovered.resume()
        val outcome = recovered.onFix(Traces.fix(200.0, Traces.START_MS + 600_000))
        assertEquals(1, (outcome as FixOutcome.Recorded).point.segment)
        // The gap crossed while the app was dead is not distance covered.
        assertEquals(points.last().cumulativeDistanceM, recovered.metrics.distanceMeters, 1e-9)
    }
}

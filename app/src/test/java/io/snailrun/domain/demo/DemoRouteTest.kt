package io.snailrun.domain.demo

import io.snailrun.domain.geo.GeoDistance
import io.snailrun.domain.metrics.FixOutcome
import io.snailrun.domain.metrics.MetricsAccumulator
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo trace is only useful if the real pipeline believes it. These assertions are
 * about the filter and the accumulators accepting it, not about the shape being pretty.
 */
class DemoRouteTest {

    private val startMs = 1_700_000_000_000L

    private fun take(seconds: Int, profile: DemoRunProfile = DemoRunProfile()) =
        DemoRoute.fixes(profile, startMs).take(seconds + 1).toList()

    @Test
    fun `emits one fix per second, starting at the given time`() {
        val fixes = take(120)

        assertEquals(121, fixes.size)
        assertEquals(startMs, fixes.first().epochMs)
        assertEquals(startMs + 120_000L, fixes.last().epochMs)
    }

    @Test
    fun `never reports itself as a mock, which the filter would drop`() {
        assertTrue(take(300).none { it.isMock })
    }

    @Test
    fun `stays inside a loop no larger than its perimeter`() {
        val profile = DemoRunProfile(loopPerimeterM = 1_000.0)
        val fixes = take(1_800, profile)

        val spanM = GeoDistance.between(
            fixes.minOf { it.lat }, fixes.minOf { it.lon },
            fixes.maxOf { it.lat }, fixes.maxOf { it.lon },
        )
        // A 1 km loop encloses a few hundred metres across; well under the perimeter,
        // and not a straight line either.
        assertTrue("span was $spanM m", spanM in 100.0..profile.loopPerimeterM)
    }

    @Test
    fun `is deterministic for a given seed`() {
        assertEquals(take(200), take(200))
    }

    @Test
    fun `covers roughly the distance its pace implies, once through the real filter`() {
        val accumulator = MetricsAccumulator()
        val profile = DemoRunProfile(stops = emptyList())
        val minutes = 20

        take(minutes * 60, profile).forEach { accumulator.onFix(it) }

        // 20 minutes at about 5:30/km is a little over 3.6 km. The filter's jitter floor
        // and the pace swing move it a few percent either way.
        val metrics = accumulator.metrics
        val expected = minutes * 60.0 * 1_000.0 / profile.basePaceSecPerKm
        assertTrue(
            "recorded ${metrics.distanceMeters} m, expected about $expected m",
            abs(metrics.distanceMeters - expected) / expected < 0.08,
        )
    }

    @Test
    fun `passes the filter on almost every fix`() {
        val accumulator = MetricsAccumulator()
        val fixes = take(900)

        val recorded = fixes.count { accumulator.onFix(it) is FixOutcome.Recorded }

        assertTrue("$recorded of ${fixes.size} accepted", recorded > fixes.size * 0.98)
    }

    @Test
    fun `stands still at a traffic light without losing distance`() {
        val profile = DemoRunProfile(stops = listOf(200..260))
        val accumulator = MetricsAccumulator()
        val fixes = take(300, profile)

        var beforeStop = 0.0
        var afterStop = 0.0
        fixes.forEachIndexed { second, fix ->
            accumulator.onFix(fix)
            if (second == 199) beforeStop = accumulator.metrics.distanceMeters
            if (second == 260) afterStop = accumulator.metrics.distanceMeters
        }

        // Jitter while stopped must not be integrated into the total.
        assertTrue("gained ${afterStop - beforeStop} m while standing still", afterStop - beforeStop < 5.0)
        // 240 of the 300 seconds were spent running, at a little over 3 m/s.
        assertTrue(
            "total was ${accumulator.metrics.distanceMeters} m",
            accumulator.metrics.distanceMeters > 650.0,
        )
    }

    @Test
    fun `climbs a hill the elevation tracker can see`() {
        val accumulator = MetricsAccumulator()

        take(1_500).forEach { accumulator.onFix(it) }

        assertTrue("gain was ${accumulator.metrics.elevationGainM}", accumulator.metrics.elevationGainM > 10.0)
    }
}

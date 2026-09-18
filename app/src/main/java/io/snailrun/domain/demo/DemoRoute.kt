package io.snailrun.domain.demo

import io.snailrun.domain.geo.GeoDistance
import io.snailrun.domain.model.RawFix
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * The shape of a fake run: a loop walked at a pace that drifts, with two traffic-light
 * stops and a hill.
 *
 * Defaults are chosen to exercise the real pipeline rather than to look tidy. The pace
 * sits on the filter's 3 m jitter floor, the stops produce a slow split, and the hill
 * is tall enough to clear the elevation hysteresis.
 */
data class DemoRunProfile(
    val startLat: Double = 48.8566,
    val startLon: Double = 2.3522,
    /** One lap is one kilometre, so every lap is also a split. */
    val loopPerimeterM: Double = 1_000.0,
    /** An hour of running: longer than any test, and the flow simply ends after it. */
    val durationSeconds: Int = 60 * 60,
    val basePaceSecPerKm: Double = 330.0,
    /** Pace wanders this far either side of the base, over [paceSwingPeriodS]. */
    val paceSwingSec: Double = 40.0,
    val paceSwingPeriodS: Double = 420.0,
    /** Seconds spent standing still, as at a light. Fixes keep arriving, and jitter. */
    val stops: List<IntRange> = listOf(380..424, 1_520..1_564, 2_900..2_944),
    val baseAltitudeM: Double = 42.0,
    /** Peak-to-base climb per lap. Above the tracker's 5 m hysteresis, on purpose. */
    val hillAmplitudeM: Double = 14.0,
    val accuracyM: Float = 6f,
    val verticalAccuracyM: Float = 4f,
    val lateralNoiseM: Double = 1.2,
    val altitudeNoiseM: Double = 0.8,
    val seed: Long = 20_260_918L,
)

/**
 * Generates the fixes a demo run is made of.
 *
 * Pure and deterministic, like everything else in `domain`: the same profile and seed
 * always produce the same trace, so what the phone shows in demo mode is exactly what a
 * JVM test asserts on.
 *
 * `isMock` stays false throughout. These fixes never touch Android's mock location
 * provider — they are made up inside the app — and [io.snailrun.domain.metrics.FixFilter]
 * drops mock fixes, which would leave a demo run empty.
 */
object DemoRoute {

    /** Samples around the loop. 720 gives a sub-metre chord at a 1 km perimeter. */
    private const val LOOP_SAMPLES = 720

    fun fixes(
        profile: DemoRunProfile = DemoRunProfile(),
        startEpochMs: Long,
    ): Sequence<RawFix> = sequence {
        val loop = Loop(profile.loopPerimeterM)
        val random = Random(profile.seed)
        val phi = Math.toRadians(profile.startLat)
        val metresPerDegLat = GeoDistance.metresPerDegreeLat(phi)
        val metresPerDegLon = GeoDistance.metresPerDegreeLon(phi)

        var travelled = 0.0

        for (second in 0..profile.durationSeconds) {
            val stopped = profile.stops.any { second in it }
            val speed = if (stopped) 0.0 else speedAt(profile, second)
            travelled += speed

            val position = loop.at(travelled)
            val east = position.x + random.symmetric(profile.lateralNoiseM)
            val north = position.y + random.symmetric(profile.lateralNoiseM)

            yield(
                RawFix(
                    epochMs = startEpochMs + second * 1_000L,
                    lat = profile.startLat + north / metresPerDegLat,
                    lon = profile.startLon + east / metresPerDegLon,
                    accuracyM = profile.accuracyM,
                    altitudeM = altitudeAt(profile, loop, travelled) +
                        random.symmetric(profile.altitudeNoiseM),
                    verticalAccuracyM = profile.verticalAccuracyM,
                    speedMps = speed.toFloat(),
                    // Under 1 m/s, so the accumulator trusts this over differenced
                    // positions — the same path a real GNSS chip takes.
                    speedAccuracyMps = 0.4f,
                    ageMs = 0,
                    isMock = false,
                )
            )
        }
    }

    private fun speedAt(profile: DemoRunProfile, second: Int): Double {
        val swing = profile.paceSwingSec *
            sin(2 * PI * second / profile.paceSwingPeriodS)
        val pace = (profile.basePaceSecPerKm + swing).coerceAtLeast(150.0)
        return 1_000.0 / pace
    }

    /** One climb and one descent per lap, so laps differ and the hill repeats. */
    private fun altitudeAt(profile: DemoRunProfile, loop: Loop, travelled: Double): Double {
        val fraction = (travelled % loop.perimeterM) / loop.perimeterM
        return profile.baseAltitudeM +
            profile.hillAmplitudeM * (1 - cos(2 * PI * fraction)) / 2
    }

    private fun Random.symmetric(amplitude: Double): Double =
        (nextDouble() * 2 - 1) * amplitude

    private data class Metres(val x: Double, val y: Double)

    /**
     * A closed, organic-looking loop in metres east/north of the start.
     *
     * A plain circle draws a trace no runner would believe, so the radius is modulated
     * by two harmonics. The polyline is then rescaled to the requested perimeter and
     * walked by arc length, which is what keeps the pace honest.
     */
    private class Loop(val perimeterM: Double) {

        private val points: List<Metres>
        private val cumulative: DoubleArray

        init {
            val raw = (0 until LOOP_SAMPLES).map { i ->
                val theta = 2 * PI * i / LOOP_SAMPLES
                val r = 1 + 0.16 * sin(3 * theta + 0.7) + 0.08 * cos(2 * theta)
                Metres(r * cos(theta), r * sin(theta))
            }
            val rawPerimeter = raw.indices.sumOf { i ->
                val a = raw[i]
                val b = raw[(i + 1) % raw.size]
                hypot(b.x - a.x, b.y - a.y)
            }
            val scale = perimeterM / rawPerimeter
            points = raw.map { Metres(it.x * scale, it.y * scale) }

            cumulative = DoubleArray(points.size + 1)
            for (i in points.indices) {
                val a = points[i]
                val b = points[(i + 1) % points.size]
                cumulative[i + 1] = cumulative[i] + hypot(b.x - a.x, b.y - a.y)
            }
        }

        /** Position after walking [distanceM] along the loop, wrapping between laps. */
        fun at(distanceM: Double): Metres {
            val total = cumulative.last()
            var d = distanceM % total
            if (d < 0) d += total

            // Linear scan would be O(n) per fix; the samples are uniform enough that a
            // binary search over the cumulative array is both exact and cheap.
            var low = 0
            var high = points.size
            while (low < high - 1) {
                val mid = (low + high) / 2
                if (cumulative[mid] <= d) low = mid else high = mid
            }

            val segment = (cumulative[low + 1] - cumulative[low]).coerceAtLeast(1e-9)
            val t = (d - cumulative[low]) / segment
            val a = points[low]
            val b = points[(low + 1) % points.size]
            return Metres(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        }
    }
}

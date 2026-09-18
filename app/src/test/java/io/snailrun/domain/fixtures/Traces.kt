package io.snailrun.domain.fixtures

import io.snailrun.domain.model.RawFix
import io.snailrun.domain.model.TrackPoint

/**
 * Synthetic traces used across the metric tests. Every scenario the filter and the
 * accumulators are supposed to survive is expressed here once, as data.
 */
object Traces {

    const val START_LAT = 48.8566
    const val START_LON = 2.3522
    const val START_MS = 1_700_000_000_000L

    /** Metres of latitude per degree near Paris; good enough to lay out a fixture. */
    private const val M_PER_DEG_LAT = 111_212.0

    fun metresNorth(metres: Double): Double = START_LAT + metres / M_PER_DEG_LAT

    /**
     * A straight northward run at a constant [speedMps], one fix per second.
     * Default 3.03 m/s is 5:30/km, a realistic easy pace and — deliberately — right on
     * the filter's 3 m jitter floor.
     */
    fun steadyRun(
        seconds: Int,
        speedMps: Double = 3.03,
        accuracyM: Float = 6f,
        startMs: Long = START_MS,
        altitudeStartM: Double? = null,
    ): List<RawFix> = (0..seconds).map { i ->
        RawFix(
            epochMs = startMs + i * 1000L,
            lat = metresNorth(i * speedMps),
            lon = START_LON,
            accuracyM = accuracyM,
            altitudeM = altitudeStartM?.plus(0.0),
            verticalAccuracyM = altitudeStartM?.let { 4f },
            speedMps = speedMps.toFloat(),
            speedAccuracyMps = 0.4f,
        )
    }

    /** A run that stops dead for [stillSeconds] in the middle, as at a traffic light. */
    fun runWithStop(
        beforeSeconds: Int,
        stillSeconds: Int,
        afterSeconds: Int,
        speedMps: Double = 3.0,
    ): List<RawFix> {
        val fixes = mutableListOf<RawFix>()
        var distance = 0.0
        var t = START_MS
        repeat(beforeSeconds) {
            fixes += fix(distance, t)
            distance += speedMps
            t += 1000
        }
        repeat(stillSeconds) {
            // Standing still still jitters by a metre or so; that is the point.
            fixes += fix(distance + (it % 2) * 0.8, t)
            t += 1000
        }
        repeat(afterSeconds) {
            fixes += fix(distance, t)
            distance += speedMps
            t += 1000
        }
        return fixes
    }

    /** A wild outlier fix, the shape a reflection off a building takes. */
    fun teleport(fromMs: Long, metresAway: Double = 800.0): RawFix =
        fix(metresAway, fromMs, accuracyM = 18f)

    fun fix(
        metresFromStart: Double,
        epochMs: Long,
        accuracyM: Float = 6f,
        altitudeM: Double? = null,
        verticalAccuracyM: Float? = null,
        speedMps: Float? = null,
    ) = RawFix(
        epochMs = epochMs,
        lat = metresNorth(metresFromStart),
        lon = START_LON,
        accuracyM = accuracyM,
        altitudeM = altitudeM,
        verticalAccuracyM = verticalAccuracyM,
        speedMps = speedMps,
    )

    /**
     * A straight run with GPS noise on every fix, as track points.
     *
     * The truth is exactly [seconds] * [speedMps] metres long, which is what makes this
     * fixture useful: any distance a smoother reports can be scored against it.
     */
    fun noisyTrack(
        seconds: Int,
        speedMps: Double = 3.0,
        noiseM: Double = 4.0,
        accuracyM: Float = 6f,
        seed: Long = 7L,
        startMs: Long = START_MS,
    ): List<TrackPoint> {
        val random = kotlin.random.Random(seed)
        return (0..seconds).map { i ->
            val alongM = i * speedMps
            val jitterLat = (random.nextDouble() * 2 - 1) * noiseM
            val jitterLon = (random.nextDouble() * 2 - 1) * noiseM
            val lat = metresNorth(alongM + jitterLat)
            val lon = START_LON + jitterLon / M_PER_DEG_LON
            TrackPoint(
                seq = i,
                segment = 0,
                timestampMs = startMs + i * 1000L,
                lat = lat,
                lon = lon,
                elevationM = 35.0,
                accuracyM = accuracyM,
                speedMps = speedMps.toFloat(),
                // Left at zero: a smoother derives this, it never reads it.
                cumulativeDistanceM = 0.0,
            )
        }
    }

    /** Metres of longitude per degree near Paris. */
    private const val M_PER_DEG_LON = 73_170.0

    /** Track points laid out on a straight line, one per second at [speedMps]. */
    fun straightTrack(
        seconds: Int,
        speedMps: Double = 3.0,
        startMs: Long = START_MS,
        segment: Int = 0,
    ): List<TrackPoint> = (0..seconds).map { i ->
        TrackPoint(
            seq = i,
            segment = segment,
            timestampMs = startMs + i * 1000L,
            lat = metresNorth(i * speedMps),
            lon = START_LON,
            elevationM = 35.0,
            accuracyM = 5f,
            speedMps = speedMps.toFloat(),
            cumulativeDistanceM = i * speedMps,
        )
    }
}

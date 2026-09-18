package io.snailrun.domain.analysis

import io.snailrun.domain.metrics.ElevationTracker
import io.snailrun.domain.model.TrackPoint

/** One column of the run's profile: how fast, and how high, at this point along it. */
data class ProfileSample(
    /** Metres from the start, at the far edge of the bucket this sample covers. */
    val distanceM: Double,
    /** Active time to here, so a pause never shows as a flat stretch of slow pace. */
    val elapsedMs: Long,
    /** Null where the runner was too slow for a pace to mean anything. */
    val paceSecPerKm: Double?,
    val elevationM: Double?,
)

/** What a stretch of the run adds up to, for a selection on the graph. */
data class ProfileSelection(
    val fromM: Double,
    val toM: Double,
    val distanceM: Double,
    val durationMs: Long,
    val paceSecPerKm: Double?,
    val elevationGainM: Double,
    val elevationLossM: Double,
)

/**
 * Turns a track into something drawable, and answers what any stretch of it averaged.
 *
 * Distance is the x-axis rather than time, because that is the axis a runner compares
 * along: the same hill is the same place on the chart whatever the day's pace.
 *
 * Both the buckets and a selection are measured between interpolated boundaries, not
 * between the nearest fixes. At 1 Hz a runner covers about three metres, so snapping to
 * fixes would scatter each bucket's pace by a few seconds per kilometre — visible as
 * grass on a line that should be smooth.
 *
 * Time accumulates only within a segment, and only across gaps short enough to be
 * running. A pause therefore costs no time here, exactly as it costs none in the run's
 * own total.
 */
object TrackProfile {

    /** Above this between two fixes, the runner was not running: a gap, not a stride. */
    private const val MAX_STEP_MS = 30_000L

    /** Below this the runner is standing about and a pace figure would be nonsense. */
    private const val MIN_SPEED_MPS = 0.5

    fun sample(points: List<TrackPoint>, buckets: Int = 160): List<ProfileSample> {
        val track = Track.of(points) ?: return emptyList()
        val step = track.totalM / buckets
        if (step <= 0.0) return emptyList()

        var previous = track.at(0.0)
        return (1..buckets).map { i ->
            val distance = if (i == buckets) track.totalM else i * step
            val here = track.at(distance)
            val durationMs = here.elapsedMs - previous.elapsedMs
            previous = here

            ProfileSample(
                distanceM = distance,
                elapsedMs = here.elapsedMs,
                paceSecPerKm = paceOf(step, durationMs),
                elevationM = here.elevationM,
            )
        }
    }

    fun selection(points: List<TrackPoint>, fromM: Double, toM: Double): ProfileSelection? {
        val track = Track.of(points) ?: return null
        val start = fromM.coerceIn(0.0, track.totalM)
        val end = toM.coerceIn(0.0, track.totalM)
        if (end - start < 1.0) return null

        val a = track.at(start)
        val b = track.at(end)
        val distance = end - start
        val durationMs = b.elapsedMs - a.elapsedMs

        val elevation = ElevationTracker()
        // The endpoints are interpolated, so the climb is measured between them and not
        // between whichever fixes happen to sit just inside.
        a.elevationM?.let(elevation::onVettedElevation)
        points.filter { it.cumulativeDistanceM in start..end }
            .forEach { point -> point.elevationM?.let(elevation::onVettedElevation) }
        b.elevationM?.let(elevation::onVettedElevation)

        return ProfileSelection(
            fromM = start,
            toM = end,
            distanceM = distance,
            durationMs = durationMs,
            paceSecPerKm = paceOf(distance, durationMs),
            elevationGainM = elevation.gainM,
            elevationLossM = elevation.lossM,
        )
    }

    private fun paceOf(distanceM: Double, durationMs: Long): Double? {
        if (distanceM <= 0.0 || durationMs <= 0L) return null
        val speed = distanceM / (durationMs / 1000.0)
        if (speed < MIN_SPEED_MPS) return null
        return 1_000.0 / speed
    }

    /** A point on the track, found by interpolating between the two fixes around it. */
    private data class Cursor(val elapsedMs: Long, val elevationM: Double?)

    /**
     * The track as three parallel arrays, built once per call.
     *
     * A binary search over the distance array answers any boundary in log time, which
     * matters: a graph is 160 buckets and a drag asks for two more on every frame.
     */
    private class Track(
        private val distanceM: DoubleArray,
        private val elapsedMs: LongArray,
        private val elevationM: Array<Double?>,
    ) {
        val totalM: Double get() = distanceM.last()

        fun at(distance: Double): Cursor {
            if (distance <= distanceM.first()) return Cursor(elapsedMs.first(), elevationM.first())
            if (distance >= totalM) return Cursor(elapsedMs.last(), elevationM.last())

            var low = 0
            var high = distanceM.size - 1
            while (low < high - 1) {
                val mid = (low + high) / 2
                if (distanceM[mid] <= distance) low = mid else high = mid
            }

            val span = distanceM[high] - distanceM[low]
            val fraction = if (span <= 0.0) 0.0 else (distance - distanceM[low]) / span
            return Cursor(
                elapsedMs = elapsedMs[low] +
                    ((elapsedMs[high] - elapsedMs[low]) * fraction).toLong(),
                elevationM = interpolate(elevationM[low], elevationM[high], fraction),
            )
        }

        companion object {
            fun of(points: List<TrackPoint>): Track? {
                if (points.size < 2) return null
                val distance = DoubleArray(points.size)
                val elapsed = LongArray(points.size)
                val elevation = arrayOfNulls<Double>(points.size)

                var active = 0L
                points.forEachIndexed { i, point ->
                    if (i > 0) {
                        val previous = points[i - 1]
                        val delta = point.timestampMs - previous.timestampMs
                        if (point.segment == previous.segment && delta in 1..MAX_STEP_MS) {
                            active += delta
                        }
                    }
                    distance[i] = point.cumulativeDistanceM
                    elapsed[i] = active
                    elevation[i] = point.elevationM
                }
                if (distance.last() <= 0.0) return null
                return Track(distance, elapsed, elevation)
            }

            private fun interpolate(a: Double?, b: Double?, fraction: Double): Double? =
                if (a == null || b == null) b ?: a else a + fraction * (b - a)
        }
    }
}

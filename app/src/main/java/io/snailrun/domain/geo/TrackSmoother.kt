package io.snailrun.domain.geo

import io.snailrun.domain.metrics.GapKind
import io.snailrun.domain.metrics.TrackGaps
import io.snailrun.domain.model.TrackPoint
import kotlin.math.hypot
import kotlin.math.sqrt

/** A position the smoother believes in, with the speed it implies. */
data class SmoothedFix(
    val lat: Double,
    val lon: Double,
    /** Speed from the filter's own velocity state, not from the chip. */
    val speedMps: Double,
    /** How far the raw fix sat from the prediction, in standard deviations. */
    val residualSigma: Double,
    /** True when the raw fix was too far out to be believed and was pulled back in. */
    val correctedOutlier: Boolean,
    /** True when the previous fix was old enough that the track has a hole before this one. */
    val afterGap: Boolean,
)

/**
 * Corrects a GPS track: pulls in outliers, smooths jitter, and carries velocity across
 * dropouts.
 *
 * A constant-velocity Kalman filter over local metres, one independent filter per axis
 * — process and measurement noise are isotropic here, so a full 4x4 would carry only
 * zeros off the diagonal blocks and cost arithmetic to prove it.
 *
 * What each part is for:
 *
 *  - **Jitter.** A stationary receiver wanders by a metre or two per second. Summing
 *    raw displacements integrates that wander into the distance, which is why a run
 *    measured from raw fixes reads long. The filter weighs each fix against where the
 *    runner was predicted to be, using the accuracy the chip reported, so noise is
 *    averaged out instead of accumulated.
 *
 *  - **Outliers.** A reflection off a building lands hundreds of metres away. Rejecting
 *    such a fix outright is wrong when it is the truth — leaving a tunnel, the receiver
 *    really has moved. So a fix beyond the gate is not dropped: its measurement noise is
 *    inflated, which lets it nudge the estimate rather than snap it. If it keeps
 *    happening, the filter accepts that the world moved and resets onto the new position.
 *
 *  - **Dropouts.** Under trees or in a tunnel the fixes stop. The prediction keeps
 *    running on the last known velocity, and its uncertainty grows with the gap, so the
 *    first fix after the hole is judged against a wide gate and adopted immediately
 *    instead of being fought as an outlier. Nothing is invented across the gap: the
 *    straight line between the last fix and the first new one is the only reconstruction
 *    the data supports, and [SmoothedFix.afterGap] marks it so a caller can say so.
 *
 * Stateful across a segment and pure: no clock, no Android, no randomness. A live run
 * drives it one fix at a time; [smooth] re-derives a stored track with it and then runs
 * back over the result, which a live run cannot do.
 */
class TrackSmoother(private val config: Config = Config()) {

    data class Config(
        /**
         * Acceleration process noise, (m/s^2)^2/Hz. Sets how eagerly the filter follows
         * a change of pace. Too high and it chases jitter and reads long; too low and it
         * cuts corners and lags a change of pace.
         *
         * Swept against two fixtures with a known true length — a straight run and the
         * curvy demo loop, both with 4 m of noise. Raw integration reads about 65 % long
         * on each. This value lands within 2 % on the straight one and 3.5 % on the loop,
         * and the curve is flat enough either side that the exact figure is not delicate.
         */
        val accelerationPsd: Double = 0.04,
        /** Used when a fix carries no accuracy at all. Pessimistic on purpose. */
        val defaultAccuracyM: Float = 15f,
        /**
         * The least uncertainty a fix is credited with, whatever the phone claims. Some
         * phones report two metres while their positions wander by more than that.
         */
        val minAccuracyM: Float = 1f,
        /** Chi-square, two degrees of freedom, p = 0.999. Beyond this a fix is suspect. */
        val outlierGate: Double = 13.82,
        /** How much a suspect fix's variance is inflated instead of being dropped. */
        val outlierVarianceFactor: Double = 100.0,
        /** Consecutive suspect fixes before the filter accepts the world has moved. */
        val resyncAfter: Int = 3,
        /** Longer than this between fixes and the track has a hole, not a step. */
        val gapMs: Long = 5_000,
        /** A dropout cannot widen the prediction further than this; beyond it, resync. */
        val maxPredictMs: Long = 60_000,
    )

    private var originLat = 0.0
    private var originLonRef = 0.0
    private var metresPerDegLat = 0.0
    private var metresPerDegLon = 0.0
    private var started = false

    private val east = AxisFilter()
    private val north = AxisFilter()

    private enum class Step { Start, Update }

    /** What the forward pass knew at one fix: enough to run [backwardPass] over. */
    internal class Snapshot(
        val fix: SmoothedFix,
        val timestampMs: Long,
        /** Taken in by an update, so predicted from the fix before it. */
        val updated: Boolean,
        val east: DoubleArray,
        val north: DoubleArray,
        val eastPredicted: DoubleArray,
        val northPredicted: DoubleArray,
        val metresPerDegLat: Double,
        val metresPerDegLon: Double,
    )

    /** A position after the backward pass, with the speed it implies. */
    internal data class SmoothedPoint(val lat: Double, val lon: Double, val speedMps: Double)

    private var lastFix: SmoothedFix? = null

    /** The state after the last [onFix]. */
    internal fun snapshot() = Snapshot(
        fix = checkNotNull(lastFix) { "no fix yet" },
        timestampMs = lastTimestampMs,
        updated = lastStep == Step.Update,
        east = east.state(),
        north = north.state(),
        eastPredicted = east.predicted.copyOf(),
        northPredicted = north.predicted.copyOf(),
        metresPerDegLat = metresPerDegLat,
        metresPerDegLon = metresPerDegLon,
    )
    private var lastTimestampMs = 0L
    private var consecutiveOutliers = 0
    private var awaitingVelocity = false
    private var startVariance = 0.0

    /** How the last fix was taken in. Read by [smooth] to know where a backward pass may run. */
    private var lastStep = Step.Start

    fun reset() {
        started = false
        awaitingVelocity = false
        consecutiveOutliers = 0
    }

    fun onFix(
        timestampMs: Long,
        lat: Double,
        lon: Double,
        accuracyM: Float?,
    ): SmoothedFix = take(timestampMs, lat, lon, accuracyM).also { lastFix = it }

    private fun take(
        timestampMs: Long,
        lat: Double,
        lon: Double,
        accuracyM: Float?,
    ): SmoothedFix {
        val sigma = (accuracyM ?: config.defaultAccuracyM).toDouble()
            .coerceAtLeast(config.minAccuracyM.toDouble())
        val variance = sigma * sigma

        if (!started) return start(timestampMs, lat, lon, variance, sigma)

        val dtMs = (timestampMs - lastTimestampMs).coerceAtLeast(0)
        val afterGap = dtMs > config.gapMs
        // A gap longer than the prediction is any good for: the velocity carried across
        // it is meaningless, so start again from the new fix rather than pretend.
        if (dtMs > config.maxPredictMs) return start(timestampMs, lat, lon, variance, sigma, afterGap = true)

        val dt = dtMs / 1000.0

        // The second fix of a segment sets the velocity outright rather than letting it
        // grow from zero. Left to converge, the filter spends the first ten seconds
        // behind the runner and quietly loses ten metres off the front of every run.
        if (awaitingVelocity && dt > 0.0) {
            awaitingVelocity = false
            val initEast = (lon - originLonRef) * metresPerDegLon
            val initNorth = (lat - originLat) * metresPerDegLat
            east.initialiseVelocity(initEast, dt, variance, startVariance)
            north.initialiseVelocity(initNorth, dt, variance, startVariance)
            lastStep = Step.Start
            lastTimestampMs = timestampMs
            return SmoothedFix(
                lat = originLat + north.position / metresPerDegLat,
                lon = originLonRef + east.position / metresPerDegLon,
                speedMps = hypot(east.velocity, north.velocity),
                residualSigma = 0.0,
                correctedOutlier = false,
                afterGap = afterGap,
            )
        }

        east.predict(dt, config.accelerationPsd)
        north.predict(dt, config.accelerationPsd)

        val measuredEast = (lon - originLonRef) * metresPerDegLon
        val measuredNorth = (lat - originLat) * metresPerDegLat

        val residualEast = measuredEast - east.position
        val residualNorth = measuredNorth - north.position
        val innovationEast = east.positionVariance + variance
        val innovationNorth = north.positionVariance + variance
        val nis = residualEast * residualEast / innovationEast +
            residualNorth * residualNorth / innovationNorth

        val suspect = nis > config.outlierGate
        if (suspect) consecutiveOutliers++ else consecutiveOutliers = 0

        if (suspect && consecutiveOutliers >= config.resyncAfter) {
            // Three in a row is not a reflection, it is a new position. Believe it.
            return start(timestampMs, lat, lon, variance, sigma, afterGap = afterGap)
        }

        val effectiveVariance = if (suspect) variance * config.outlierVarianceFactor else variance
        east.update(measuredEast, effectiveVariance)
        north.update(measuredNorth, effectiveVariance)
        lastTimestampMs = timestampMs
        lastStep = Step.Update

        return SmoothedFix(
            lat = originLat + north.position / metresPerDegLat,
            lon = originLonRef + east.position / metresPerDegLon,
            speedMps = hypot(east.velocity, north.velocity),
            residualSigma = sqrt(nis),
            correctedOutlier = suspect,
            afterGap = afterGap,
        )
    }

    private fun start(
        timestampMs: Long,
        lat: Double,
        lon: Double,
        variance: Double,
        sigma: Double,
        afterGap: Boolean = false,
    ): SmoothedFix {
        // The projection is anchored at the first fix of the segment and never moves, so
        // metres stay linear in degrees for the few kilometres a run covers.
        if (!started) {
            originLat = lat
            originLonRef = lon
            val phi = Math.toRadians(lat)
            metresPerDegLat = GeoDistance.metresPerDegreeLat(phi)
            metresPerDegLon = GeoDistance.metresPerDegreeLon(phi)
        }
        val measuredEast = (lon - originLonRef) * metresPerDegLon
        val measuredNorth = (lat - originLat) * metresPerDegLat
        east.start(measuredEast, variance)
        north.start(measuredNorth, variance)
        started = true
        awaitingVelocity = true
        startVariance = variance
        lastTimestampMs = timestampMs
        consecutiveOutliers = 0
        lastStep = Step.Start
        return SmoothedFix(
            lat = lat,
            lon = lon,
            speedMps = 0.0,
            residualSigma = 0.0,
            correctedOutlier = false,
            afterGap = afterGap,
        )
    }

    /**
     * One axis: position and velocity, with the 2x2 covariance kept as three numbers
     * because it is symmetric.
     */
    private class AxisFilter {
        var position = 0.0
        var velocity = 0.0
        var positionVariance = 0.0
        var velocityVariance = 0.0
        var covariance = 0.0

        /** The state [predict] produced, before the fix was weighed in. */
        val predicted = DoubleArray(5)

        fun state() = doubleArrayOf(position, velocity, positionVariance, covariance, velocityVariance)

        fun start(measurement: Double, variance: Double) {
            position = measurement
            velocity = 0.0
            positionVariance = variance
            // No idea of the velocity yet, so say so: 25 m^2/s^2 is a 5 m/s standard
            // deviation, which covers standing still through sprinting.
            velocityVariance = 25.0
            covariance = 0.0
        }

        /**
         * Two-point initialisation: the displacement between the first two fixes is the
         * velocity, and its variance follows from theirs. Exact, and it costs one fix
         * instead of the ten a converging filter needs.
         */
        fun initialiseVelocity(
            measurement: Double,
            dt: Double,
            variance: Double,
            previousVariance: Double,
        ) {
            velocity = (measurement - position) / dt
            position = measurement
            positionVariance = variance
            velocityVariance = (variance + previousVariance) / (dt * dt)
            covariance = variance / dt
        }

        fun predict(dt: Double, accelerationPsd: Double) {
            position += velocity * dt
            val dt2 = dt * dt
            val dt3 = dt2 * dt
            positionVariance += 2 * dt * covariance + dt2 * velocityVariance +
                accelerationPsd * dt3 / 3.0
            covariance += dt * velocityVariance + accelerationPsd * dt2 / 2.0
            velocityVariance += accelerationPsd * dt
            predicted[0] = position
            predicted[1] = velocity
            predicted[2] = positionVariance
            predicted[3] = covariance
            predicted[4] = velocityVariance
        }

        fun update(measurement: Double, variance: Double) {
            val innovation = positionVariance + variance
            val gainPosition = positionVariance / innovation
            val gainVelocity = covariance / innovation
            val residual = measurement - position

            position += gainPosition * residual
            velocity += gainVelocity * residual

            val previousCovariance = covariance
            positionVariance -= gainPosition * positionVariance
            covariance -= gainPosition * previousCovariance
            velocityVariance -= gainVelocity * previousCovariance
        }
    }

    companion object {
        /**
         * Bumped whenever the arithmetic changes. Runs carry the version they were
         * derived with, so improving the filter reprocesses the runs already recorded
         * instead of leaving them on the old numbers.
         *
         * Strictly it versions every figure derived from the stored track, not only this
         * filter's: 3 re-derives the climb, which a vertical-accuracy gate had held at
         * 0 m on phones that report it poorly or not at all. 4 adds the backward pass in
         * [smooth], which stops a track reading 12 % long.
         */
        const val VERSION = 4

        /**
         * Smoothed positions never move far enough per fix to need a jitter floor, so
         * the only displacement thrown away is the sub-centimetre kind.
         */
        private const val MIN_STEP_M = 0.05

        /**
         * The settings a stored track is re-derived with.
         *
         * Looser than the live filter's, because the backward pass in [smooth] takes out
         * the lag a responsive filter would otherwise leave on every bend. Swept against
         * a synthetic lane-2 track with white noise (within 1.5 % at 3 and 5 m/s), the
         * straight noisy fixture (exact), straight 300 m reps whose start and end are a
         * known chord apart (within 2 m), and six minutes on a real 400 m track, which
         * read 12 % long before and reads 5 % long now — the rest is in the positions
         * that phone reported, which trace a bigger oval than the lane.
         */
        val STORED = Config(accelerationPsd = 0.3, minAccuracyM = 5f)

        /**
         * Re-derives a stored track: corrected positions, and the cumulative distance
         * that follows from them.
         *
         * Raw points are what the database keeps, so this runs on every read path. A
         * forward pass of the same filter the live run drives, then a backward pass over
         * it (Rauch–Tung–Striebel) that lets each position be corrected by the fixes that
         * came after it as well as before. A filter that can only look back overshoots
         * the start of every bend and reads a track long; one that looks both ways does
         * not. The figure on the run's own screen can therefore differ from the one on
         * the record screen by a few percent — the stored one is the better of the two.
         */
        fun smooth(points: List<TrackPoint>, config: Config = STORED): List<TrackPoint> {
            if (points.isEmpty()) return points

            val n = points.size
            val smoother = TrackSmoother(config)
            val forward = ArrayList<Snapshot>(n)
            /** Whether the step from the point before counts as distance. */
            val linked = BooleanArray(n)

            var segment = points.first().segment
            var hasPrevious = false
            var previousPoint: TrackPoint? = null

            for ((i, point) in points.withIndex()) {
                // Each pause boundary is a fresh start: velocity carried across a pause
                // would fabricate a step the runner never took.
                if (point.segment != segment) {
                    smoother.reset()
                    hasPrevious = false
                    segment = point.segment
                } else previousPoint?.let { before ->
                    // A hole in the fixes, judged by the same rules the live run used.
                    val gap = point.timestampMs - before.timestampMs
                    val straight = GeoDistance.between(before.lat, before.lon, point.lat, point.lon)
                    when (TrackGaps.classify(gap, straight)) {
                        // Believe the fix that ends the dropout outright rather than
                        // predicting a minute of velocity onto it; the straight line to
                        // it is still counted, because the runner ran it.
                        GapKind.Inferred -> smoother.reset()
                        // Nothing may cross a hole this big: not the distance, and not
                        // the drawn line.
                        GapKind.Broken -> {
                            smoother.reset()
                            hasPrevious = false
                        }
                        GapKind.Continuous -> Unit
                    }
                }
                previousPoint = point
                linked[i] = hasPrevious
                smoother.onFix(point.timestampMs, point.lat, point.lon, point.accuracyM)
                forward += smoother.snapshot()
                hasPrevious = true
            }

            val smoothed = backwardPass(forward)

            val out = ArrayList<TrackPoint>(n)
            var distance = 0.0
            for ((i, point) in points.withIndex()) {
                val here = smoothed[i]
                if (linked[i]) {
                    val before = smoothed[i - 1]
                    val step = GeoDistance.between(before.lat, before.lon, here.lat, here.lon)
                    if (step >= MIN_STEP_M) distance += step
                }
                out += point.copy(
                    lat = here.lat,
                    lon = here.lon,
                    speedMps = here.speedMps.toFloat(),
                    cumulativeDistanceM = distance,
                )
            }
            return out
        }

        /**
         * Runs back over a stretch of forward-filtered fixes, so each position is also
         * corrected by the fixes that came after it.
         *
         * A fix taken in by an update was predicted from the one before it, so the
         * correction can flow back across that link; a restart breaks the chain, and
         * each stretch between restarts is smoothed on its own. The last fix has nothing
         * after it and keeps its forward estimate. A stretch cut from the middle of a run
         * — the last few seconds of a live one — is handled the same way: the oldest fix
         * in it is corrected only by what follows it inside the stretch.
         */
        internal fun backwardPass(forward: List<Snapshot>): List<SmoothedPoint> {
            val n = forward.size
            if (n == 0) return emptyList()
            val out = arrayOfNulls<SmoothedPoint>(n)
            fun keep(i: Int) = SmoothedPoint(forward[i].fix.lat, forward[i].fix.lon, forward[i].fix.speedMps)
            out[n - 1] = keep(n - 1)
            var eastNext = forward[n - 1].east.copyOfRange(0, 2)
            var northNext = forward[n - 1].north.copyOfRange(0, 2)
            for (i in n - 2 downTo 0) {
                val here = forward[i]
                val after = forward[i + 1]
                if (!after.updated) {
                    out[i] = keep(i)
                    eastNext = here.east.copyOfRange(0, 2)
                    northNext = here.north.copyOfRange(0, 2)
                    continue
                }
                val dt = (after.timestampMs - here.timestampMs) / 1000.0
                eastNext = backward(here.east, after.eastPredicted, eastNext, dt)
                northNext = backward(here.north, after.northPredicted, northNext, dt)
                out[i] = SmoothedPoint(
                    lat = here.fix.lat + if (here.metresPerDegLat > 0) (northNext[0] - here.north[0]) / here.metresPerDegLat else 0.0,
                    lon = here.fix.lon + if (here.metresPerDegLon > 0) (eastNext[0] - here.east[0]) / here.metresPerDegLon else 0.0,
                    speedMps = hypot(eastNext[1], northNext[1]),
                )
            }
            return out.map { it!! }
        }

        /**
         * One step of the backward pass on one axis: the filtered state at a fix, moved
         * by how far the smoothed state at the next fix sits from what was predicted for
         * it. States are position, velocity, position variance, covariance, velocity
         * variance; [next] is the smoothed position and velocity.
         */
        private fun backward(filtered: DoubleArray, predicted: DoubleArray, next: DoubleArray, dt: Double): DoubleArray {
            val (p00, p01, p11) = Triple(filtered[2], filtered[3], filtered[4])
            // P F^T, with F the constant-velocity transition.
            val a00 = p00 + dt * p01
            val a01 = p01
            val a10 = p01 + dt * p11
            val a11 = p11
            val q00 = predicted[2]
            val q01 = predicted[3]
            val q11 = predicted[4]
            val det = q00 * q11 - q01 * q01
            if (det <= 1e-12) return filtered.copyOfRange(0, 2)
            val i00 = q11 / det
            val i01 = -q01 / det
            val i11 = q00 / det
            val c00 = a00 * i00 + a01 * i01
            val c01 = a00 * i01 + a01 * i11
            val c10 = a10 * i00 + a11 * i01
            val c11 = a10 * i01 + a11 * i11
            val d0 = next[0] - predicted[0]
            val d1 = next[1] - predicted[1]
            return doubleArrayOf(filtered[0] + c00 * d0 + c01 * d1, filtered[1] + c10 * d0 + c11 * d1)
        }
    }
}

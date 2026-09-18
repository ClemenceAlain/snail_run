package io.snailrun.domain.geo

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
 * Stateful across a segment and pure: no clock, no Android, no randomness. [smooth]
 * folds a whole stored track through exactly the instance a live run uses, which is what
 * makes a past run and a running one agree to the metre.
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
    private var lastTimestampMs = 0L
    private var consecutiveOutliers = 0
    private var awaitingVelocity = false
    private var startVariance = 0.0

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
    ): SmoothedFix {
        val sigma = (accuracyM ?: config.defaultAccuracyM).toDouble().coerceAtLeast(1.0)
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
        private var velocityVariance = 0.0
        private var covariance = 0.0

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
         */
        const val VERSION = 1

        /**
         * Smoothed positions never move far enough per fix to need a jitter floor, so
         * the only displacement thrown away is the sub-centimetre kind.
         */
        private const val MIN_STEP_M = 0.05

        /**
         * Re-derives a stored track: corrected positions, and the cumulative distance
         * that follows from them.
         *
         * Raw points are what the database keeps, so this runs on every read path. It is
         * the same filter instance a live run drives, fed in the same order, which is why
         * the figure on the record screen and the figure on the run's own screen agree.
         */
        fun smooth(points: List<TrackPoint>, config: Config = Config()): List<TrackPoint> {
            if (points.isEmpty()) return points

            val smoother = TrackSmoother(config)
            val out = ArrayList<TrackPoint>(points.size)
            var distance = 0.0
            var previous: SmoothedFix? = null
            var segment = points.first().segment

            for (point in points) {
                // Each pause boundary is a fresh start: velocity carried across a pause
                // would fabricate a step the runner never took.
                if (point.segment != segment) {
                    smoother.reset()
                    previous = null
                    segment = point.segment
                }

                val fix = smoother.onFix(point.timestampMs, point.lat, point.lon, point.accuracyM)
                previous?.let {
                    val step = GeoDistance.between(it.lat, it.lon, fix.lat, fix.lon)
                    if (step >= MIN_STEP_M) distance += step
                }
                previous = fix

                out += point.copy(
                    lat = fix.lat,
                    lon = fix.lon,
                    speedMps = fix.speedMps.toFloat(),
                    cumulativeDistanceM = distance,
                )
            }
            return out
        }
    }
}

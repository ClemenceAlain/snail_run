package io.snailrun.domain.metrics

import io.snailrun.domain.geo.GeoDistance
import io.snailrun.domain.model.RawFix

data class FilterConfig(
    /** Fixes less precise than this are noise, not position. 20-30 m is the running band. */
    val maxAccuracyM: Float = 25f,
    /**
     * The bar once the fixes have already stopped, and how long they have to have
     * stopped for it to drop.
     *
     * A receiver losing the sky does not go silent: it reports worse and worse accuracy
     * first, and at the ordinary bar every one of those is thrown away — so a run under
     * a canopy or between tall buildings records nothing at all, and the app then has to
     * infer the whole stretch as a straight line. A 50 m fix is a poor position and it
     * is still a position, and the smoother weighs it by exactly the accuracy it
     * carries. Taking it beats guessing.
     *
     * It applies only after a dropout, never at the ordinary rate: a 50 m fix arriving
     * every second among good ones is a reflection, and that is what the bar is for.
     */
    val dropoutAccuracyM: Float = 50f,
    val dropoutAfterMs: Long = 15_000,
    /** A fix that sat in a queue or came from cache is not where you are now. */
    val maxAgeMs: Long = 5_000,
    /** Duplicate deliveries arrive within a few milliseconds of each other. */
    val minIntervalMs: Long = 500,
    /** 12 m/s is 43 km/h: nobody runs that, so the fix is an outlier. */
    val maxSpeedMps: Double = 12.0,
    /** Below this, consecutive fixes are integrating GPS noise, not covering ground. */
    val minDistanceM: Double = 3.0,
    /** After this many consecutive outliers, believe the new position instead. */
    val teleportResyncAfter: Int = 3,
    val allowMock: Boolean = false,
)

enum class RejectReason {
    NO_ACCURACY,
    INACCURATE,
    STALE,
    MOCK,
    TOO_SOON,
    TELEPORT,
}

sealed interface FilterResult {
    /**
     * @param distanceDeltaM metres to add to the total. Zero for a point that is kept
     *   for the trace but is too close to the last one to be real movement.
     */
    data class Accepted(val fix: RawFix, val distanceDeltaM: Double) : FilterResult
    data class Rejected(val reason: RejectReason) : FilterResult
}

/**
 * Decides which fixes are real. Stateful across a segment but free of side effects, so
 * a recorded trace can be replayed through it and asserted on.
 *
 * Order matters: cheap, unconditional rejections first, then the outlier test, which
 * needs a previous fix to compare against.
 */
class FixFilter(private val config: FilterConfig = FilterConfig()) {

    /** Last fix accepted at all. Guards against duplicate deliveries. */
    private var lastAccepted: RawFix? = null

    /**
     * Last fix that actually advanced the total. Held across sub-threshold steps so
     * slow movement accumulates instead of being discarded: at 5:30/km a runner covers
     * about 3 m per second, right on the jitter floor, and comparing each fix only to
     * its immediate predecessor would silently drop half the distance of a whole run.
     */
    private var anchor: RawFix? = null

    private var consecutiveTeleports = 0

    /** Call on pause, resume, or any gap: the next fix starts a new segment. */
    fun reset() {
        lastAccepted = null
        anchor = null
        consecutiveTeleports = 0
    }

    /**
     * The accuracy a fix has to beat to be believed.
     *
     * Relaxed only while the track already has a hole in it, and never for the first fix
     * of a segment: that one anchors the projection and everything measured from it, so
     * it is the one fix worth waiting for.
     */
    private fun accuracyBarFor(fix: RawFix): Float {
        val last = lastAccepted ?: return config.maxAccuracyM
        val since = fix.epochMs - last.epochMs
        return if (since >= config.dropoutAfterMs) config.dropoutAccuracyM else config.maxAccuracyM
    }

    fun apply(fix: RawFix): FilterResult {
        val accuracy = fix.accuracyM ?: return FilterResult.Rejected(RejectReason.NO_ACCURACY)
        if (accuracy > accuracyBarFor(fix)) return FilterResult.Rejected(RejectReason.INACCURATE)
        if (fix.ageMs > config.maxAgeMs) return FilterResult.Rejected(RejectReason.STALE)
        if (fix.isMock && !config.allowMock) return FilterResult.Rejected(RejectReason.MOCK)

        val previous = lastAccepted
        if (previous == null) {
            lastAccepted = fix
            anchor = fix
            return FilterResult.Accepted(fix, 0.0)
        }
        if (fix.epochMs - previous.epochMs < config.minIntervalMs) {
            return FilterResult.Rejected(RejectReason.TOO_SOON)
        }

        val from = anchor ?: previous
        val dtMs = (fix.epochMs - from.epochMs).coerceAtLeast(1)
        val distance = GeoDistance.between(from.lat, from.lon, fix.lat, fix.lon)

        if (distance / (dtMs / 1000.0) > config.maxSpeedMps) {
            consecutiveTeleports++
            // A genuine tunnel exit looks exactly like an outlier. Without this escape
            // hatch the filter would reject every fix for the rest of the run.
            if (consecutiveTeleports < config.teleportResyncAfter) {
                return FilterResult.Rejected(RejectReason.TELEPORT)
            }
            consecutiveTeleports = 0
            lastAccepted = fix
            anchor = fix
            return FilterResult.Accepted(fix, 0.0)
        }
        consecutiveTeleports = 0
        lastAccepted = fix

        // Every accepted fix is kept for the trace. Distance only advances once the
        // displacement from the anchor clears the jitter floor, and then the anchor
        // moves with it, so nothing is lost and noise is not integrated.
        return if (distance >= config.minDistanceM) {
            anchor = fix
            FilterResult.Accepted(fix, distance)
        } else {
            FilterResult.Accepted(fix, 0.0)
        }
    }
}

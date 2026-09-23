package io.snailrun.domain.metrics

import io.snailrun.domain.model.TrackPoint

/**
 * Elevation gain from GPS altitude, which is the noisiest signal the app handles:
 * vertical error is typically two to three times the horizontal figure, so 10-30 m
 * of wander is normal. Summing positive deltas naively invents hundreds of metres of
 * climb on a flat run.
 *
 * Two stages, each removing a different artefact:
 *  1. median over a short window to kill spikes, then an EMA to smooth the rest
 *     (median first — an EMA alone smears a spike across its whole window),
 *  2. accumulate against a moving reference with hysteresis, so only sustained
 *     climbs count.
 *
 * There is no gate on the chip's vertical accuracy. There used to be, at 10 m, and on a
 * phone that reports worse than that — or never reports it at all — every fix failed it
 * and every run climbed 0 m. The filtering above is what copes with the noise; and the
 * stored track keeps no vertical accuracy, so a gate would also make the live figure one
 * the stored track could never reproduce.
 */
class ElevationTracker(
    private val medianWindow: Int = 7,
    private val emaAlpha: Double = 0.1,
    private val hysteresisM: Double = 5.0,
) {
    private val window = ArrayDeque<Double>()
    private var smoothed: Double? = null
    private var reference: Double? = null

    var gainM: Double = 0.0
        private set
    var lossM: Double = 0.0
        private set

    fun onElevation(altitudeM: Double?) {
        if (altitudeM == null) return
        window.addLast(altitudeM)
        if (window.size > medianWindow) window.removeFirst()
        if (window.size < medianWindow) return

        val median = window.sorted()[window.size / 2]
        val next = smoothed?.let { it + emaAlpha * (median - it) } ?: median
        smoothed = next

        val ref = reference
        if (ref == null) {
            reference = next
            return
        }
        val delta = next - ref
        if (delta >= hysteresisM) {
            gainM += delta
            reference = next
        } else if (delta <= -hysteresisM) {
            lossM += -delta
            reference = next
        }
    }

    companion object {
        /**
         * The climb over a stored track, in order. Fed the same altitudes the live
         * tracker saw, so the stored figure is the one the screen showed.
         */
        fun over(points: List<TrackPoint>): ElevationTracker =
            ElevationTracker().apply { points.forEach { onElevation(it.elevationM) } }
    }
}

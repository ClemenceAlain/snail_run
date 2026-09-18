package io.snailrun.domain.metrics

/**
 * Elevation gain from GPS altitude, which is the noisiest signal the app handles:
 * vertical accuracy is typically two to three times the horizontal figure, so 10-30 m
 * of wander is normal. Summing positive deltas naively invents hundreds of metres of
 * climb on a flat run.
 *
 * Three stages, each removing a different artefact:
 *  1. reject fixes whose vertical accuracy is too poor to mean anything,
 *  2. median over a short window to kill spikes, then an EMA to smooth the rest
 *     (median first — an EMA alone smears a spike across its whole window),
 *  3. accumulate against a moving reference with hysteresis, so only sustained
 *     climbs count.
 */
class ElevationTracker(
    private val maxVerticalAccuracyM: Float = 10f,
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

    fun onFix(altitudeM: Double?, verticalAccuracyM: Float?) {
        if (altitudeM == null) return
        if (verticalAccuracyM == null || verticalAccuracyM > maxVerticalAccuracyM) return
        onVettedElevation(altitudeM)
    }

    /**
     * For elevations that already passed the accuracy gate once, when they were fixes.
     * A stored track keeps no vertical accuracy, so re-deriving the climb over part of a
     * run — a selection on the graph, say — has nothing left to gate on.
     */
    fun onVettedElevation(altitudeM: Double) {
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
}

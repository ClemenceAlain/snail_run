package io.snailrun.domain.metrics

sealed interface AutoPauseEvent {
    data object Pause : AutoPauseEvent
    data object Resume : AutoPauseEvent
    data object None : AutoPauseEvent
}

/**
 * Stops the clock when the runner does, and starts it again when they move off.
 *
 * Two thresholds rather than one, with a dwell time on each. A single threshold would
 * flap: GPS speed wanders by a few tenths of a metre per second, so a runner standing at
 * a light would produce a burst of pause and resume events, each of which splits the
 * track into another segment.
 *
 * The gap between the thresholds is deliberate. Pausing needs the runner to be properly
 * stopped, not merely slow, because a walking break in the middle of a run is usually
 * still the run. Resuming fires at a lower bar and after a shorter dwell than pausing,
 * because the cost of the two mistakes is not symmetric: a late pause adds a few seconds
 * of standing to the clock, while a late resume silently drops real running out of it.
 *
 * Fed from the smoothed velocity rather than the chip's own figure, so the decision is
 * made on the same positions the distance is.
 */
class AutoPauseDetector(private val config: Config = Config()) {

    data class Config(
        /** Properly stopped, not merely slow: a walk is about 1.3 m/s. */
        val pauseBelowMps: Double = 0.7,
        val resumeAboveMps: Double = 1.2,
        /** Short enough to catch a traffic light, long enough to ride out jitter. */
        val pauseAfterMs: Long = 2_000,
        val resumeAfterMs: Long = 1_200,
        /**
         * Smoothing on the speed the thresholds see. Without it a single fix reading
         * 0.9 m/s in the middle of standing still restarts the pause countdown, and the
         * pause never fires at all. At 1 Hz this is roughly a two-second constant:
         * enough to ignore one stray fix, short enough not to delay the decision.
         */
        val speedAlpha: Double = 0.5,
    )

    private var belowSinceMs: Long? = null
    private var aboveSinceMs: Long? = null
    private var smoothedSpeedMps: Double? = null

    fun reset() {
        belowSinceMs = null
        aboveSinceMs = null
        smoothedSpeedMps = null
    }

    fun onSpeed(timestampMs: Long, rawSpeedMps: Double, isAutoPaused: Boolean): AutoPauseEvent {
        val speedMps = smoothedSpeedMps
            ?.let { it + config.speedAlpha * (rawSpeedMps - it) }
            ?: rawSpeedMps
        smoothedSpeedMps = speedMps

        if (isAutoPaused) {
            belowSinceMs = null
            if (speedMps < config.resumeAboveMps) {
                aboveSinceMs = null
                return AutoPauseEvent.None
            }
            val since = aboveSinceMs ?: timestampMs.also { aboveSinceMs = it }
            if (timestampMs - since < config.resumeAfterMs) return AutoPauseEvent.None
            aboveSinceMs = null
            return AutoPauseEvent.Resume
        }

        aboveSinceMs = null
        if (speedMps > config.pauseBelowMps) {
            belowSinceMs = null
            return AutoPauseEvent.None
        }
        val since = belowSinceMs ?: timestampMs.also { belowSinceMs = it }
        if (timestampMs - since < config.pauseAfterMs) return AutoPauseEvent.None
        belowSinceMs = null
        return AutoPauseEvent.Pause
    }
}

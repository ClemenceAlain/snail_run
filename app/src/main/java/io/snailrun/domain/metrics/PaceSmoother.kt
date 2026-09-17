package io.snailrun.domain.metrics

/**
 * Smoothed instantaneous pace.
 *
 * Averages *speed*, never pace. Pace is 1/speed, and the mean of reciprocals is not the
 * reciprocal of the mean — averaging pace directly biases the number every time the
 * runner slows down. Convert once, at the end.
 *
 * alpha 0.15 at 1 Hz is roughly a 7-second time constant: responsive enough to show a
 * hill, steady enough not to flicker.
 */
class PaceSmoother(
    private val alpha: Double = 0.15,
    /** Below this, pace is meaningless and the UI shows `--:--` instead of 40:00/km. */
    private val minSpeedMps: Double = 0.5,
) {
    private var smoothedSpeed: Double? = null

    fun onSample(speedMps: Double) {
        if (speedMps < 0) return
        smoothedSpeed = smoothedSpeed?.let { it + alpha * (speedMps - it) } ?: speedMps
    }

    fun reset() {
        smoothedSpeed = null
    }

    /** Seconds per kilometre, or null when the runner is not moving fast enough to say. */
    fun paceSecPerKm(): Double? {
        val speed = smoothedSpeed ?: return null
        if (speed < minSpeedMps) return null
        return 1000.0 / speed
    }
}

package io.snailrun.domain.metrics

import kotlin.math.sqrt

enum class MotionState { STILL, MOVING, UNSURE }

/** What the accelerometer says the body is doing, and for how long it has said so. */
data class Motion(val state: MotionState, val heldMs: Long)

/**
 * Tells standing from running by how much the phone is being shaken.
 *
 * GPS answers "is the runner moving" late in both directions: the chip's Doppler speed
 * is filtered inside the receiver and lags a second or two, and the detector has to
 * smooth and dwell on top of that to ride out jitter. The accelerometer answers the
 * same question within a second. Every running stride lands with an impact of a g or
 * more; a runner standing at a light, phone in pocket or hand, barely moves it.
 *
 * The figure is the spread of the acceleration's magnitude over the last second. The
 * magnitude rather than any one axis, so it does not matter which way up the phone is
 * carried, and the spread rather than the mean, so gravity drops out.
 *
 * Between the two thresholds the answer is [MotionState.UNSURE]: a walk, a phone being
 * taken out of a pocket. Those are left to GPS.
 */
class MotionClassifier(private val config: Config = Config()) {

    data class Config(
        val windowMs: Long = 1_000,
        /** Standing, phone in hand or pocket: a few tenths of a m/s². */
        val stillBelowMps2: Double = 0.6,
        /** A running stride: several m/s², well clear of a walk or a fidget. */
        val movingAboveMps2: Double = 3.0,
        /** Samples older than this mean the sensor has stopped: no opinion. */
        val staleAfterMs: Long = 2_000,
    )

    private val window = ArrayDeque<Pair<Long, Double>>()
    private var sum = 0.0
    private var sumSquares = 0.0
    private var state = MotionState.UNSURE
    private var stateSinceNs: Long? = null

    fun reset() {
        window.clear()
        sum = 0.0
        sumSquares = 0.0
        state = MotionState.UNSURE
        stateSinceNs = null
    }

    fun onSample(timestampNs: Long, x: Float, y: Float, z: Float) {
        val magnitude = sqrt((x * x + y * y + z * z).toDouble())
        window.addLast(timestampNs to magnitude)
        sum += magnitude
        sumSquares += magnitude * magnitude
        val windowNs = config.windowMs * 1_000_000
        while (timestampNs - window.first().first > windowNs) {
            val (_, old) = window.removeFirst()
            sum -= old
            sumSquares -= old * old
        }

        val next = classify(timestampNs)
        if (next != state || stateSinceNs == null) {
            state = next
            stateSinceNs = timestampNs
        }
    }

    /** Null when there is nothing recent enough to go on. */
    fun current(nowNs: Long): Motion? {
        val last = window.lastOrNull()?.first ?: return null
        if (nowNs - last > config.staleAfterMs * 1_000_000) return null
        val since = stateSinceNs ?: return null
        return Motion(state, ((nowNs - since) / 1_000_000).coerceAtLeast(0))
    }

    private fun classify(nowNs: Long): MotionState {
        // Half a window is too little to have seen a stride: a runner lands about three
        // times a second, and a quiet gap between two landings must not read as still.
        val spanNs = nowNs - window.first().first
        if (spanNs < config.windowMs * 1_000_000 * 3 / 4) return MotionState.UNSURE
        val n = window.size
        val mean = sum / n
        val spread = sqrt((sumSquares / n - mean * mean).coerceAtLeast(0.0))
        return when {
            spread < config.stillBelowMps2 -> MotionState.STILL
            spread > config.movingAboveMps2 -> MotionState.MOVING
            else -> MotionState.UNSURE
        }
    }
}

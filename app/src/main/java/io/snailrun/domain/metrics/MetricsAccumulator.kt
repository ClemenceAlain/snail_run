package io.snailrun.domain.metrics

import io.snailrun.domain.model.GpsQuality
import io.snailrun.domain.model.RawFix
import io.snailrun.domain.model.RunStatus
import io.snailrun.domain.model.TrackPoint

data class RunMetrics(
    val status: RunStatus = RunStatus.RECORDING,
    val distanceMeters: Double = 0.0,
    /** Excludes pauses. Every pace figure derives from this, never from wall clock. */
    val activeDurationMs: Long = 0,
    val elevationGainM: Double = 0.0,
    val elevationLossM: Double = 0.0,
    val paceSecPerKm: Double? = null,
    val pointCount: Int = 0,
    val segment: Int = 0,
    val gpsQuality: GpsQuality = GpsQuality.NO_FIX,
    val lastPoint: TrackPoint? = null,
) {
    val averagePaceSecPerKm: Double?
        get() = if (distanceMeters < 10.0 || activeDurationMs <= 0) null
        else activeDurationMs / 1000.0 / (distanceMeters / 1000.0)
}

sealed interface FixOutcome {
    data class Recorded(val point: TrackPoint, val metrics: RunMetrics) : FixOutcome
    data class Ignored(val reason: RejectReason, val metrics: RunMetrics) : FixOutcome
    /** The fix arrived while paused: kept out of the track entirely. */
    data class Paused(val metrics: RunMetrics) : FixOutcome
}

/**
 * Turns a stream of raw fixes into the numbers the app shows.
 *
 * Deliberately pure and replayable: feed it the points stored for an interrupted run
 * and it produces exactly the figures the live screen showed, which is what makes crash
 * recovery trustworthy rather than approximate.
 *
 * Active duration accrues from fix timestamps rather than a wall clock, so it stays
 * correct across CPU suspend when the screen is off.
 */
class MetricsAccumulator(
    filterConfig: FilterConfig = FilterConfig(),
    private val paceSmoother: PaceSmoother = PaceSmoother(),
    private val elevation: ElevationTracker = ElevationTracker(),
) {
    private val filter = FixFilter(filterConfig)

    private var status = RunStatus.RECORDING
    private var distance = 0.0
    private var activeDurationMs = 0L
    private var lastTimestampMs: Long? = null
    private var seq = 0
    private var segment = 0
    private var lastPoint: TrackPoint? = null
    private var quality = GpsQuality.NO_FIX

    val metrics: RunMetrics
        get() = RunMetrics(
            status = status,
            distanceMeters = distance,
            activeDurationMs = activeDurationMs,
            elevationGainM = elevation.gainM,
            elevationLossM = elevation.lossM,
            paceSecPerKm = paceSmoother.paceSecPerKm(),
            pointCount = seq,
            segment = segment,
            gpsQuality = quality,
            lastPoint = lastPoint,
        )

    /**
     * Resumes an interrupted run from its stored track.
     *
     * Without this, continuing after a crash would restart the totals at zero and the
     * second half of the run would be recorded as a separate, shorter one. The stored
     * points are already filtered, so their figures are carried over directly rather
     * than being passed through the filter a second time.
     */
    fun restore(points: List<TrackPoint>) {
        if (points.isEmpty()) return
        val last = points.last()
        distance = last.cumulativeDistanceM
        seq = last.seq + 1
        // A recovered run always resumes in a new segment: whatever happened while the
        // app was dead is a gap, not a straight line.
        segment = last.segment + 1
        lastPoint = last
        lastTimestampMs = null
        activeDurationMs = activeDurationOf(points)
        status = RunStatus.PAUSED_MANUAL
        filter.reset()
        paceSmoother.reset()
    }

    fun pause(manual: Boolean = true) {
        if (status == RunStatus.COMPLETE) return
        status = if (manual) RunStatus.PAUSED_MANUAL else RunStatus.PAUSED_AUTO
        // A new segment, so the gap crossed while paused is never drawn or counted.
        segment++
        filter.reset()
        paceSmoother.reset()
        lastTimestampMs = null
    }

    fun resume() {
        if (status == RunStatus.COMPLETE) return
        status = RunStatus.RECORDING
    }

    fun finish(): RunMetrics {
        status = RunStatus.COMPLETE
        return metrics
    }

    fun onFix(fix: RawFix): FixOutcome {
        if (status != RunStatus.RECORDING) return FixOutcome.Paused(metrics)

        return when (val result = filter.apply(fix)) {
            is FilterResult.Rejected -> {
                quality = degrade(quality)
                FixOutcome.Ignored(result.reason, metrics)
            }

            is FilterResult.Accepted -> {
                val previousTimestampMs = lastTimestampMs
                previousTimestampMs?.let { previous ->
                    val delta = fix.epochMs - previous
                    // Guard against a clock jump or a long gap being counted as running.
                    if (delta in 1..30_000) activeDurationMs += delta
                }
                lastTimestampMs = fix.epochMs

                distance += result.distanceDeltaM
                elevation.onFix(fix.altitudeM, fix.verticalAccuracyM)
                sampleSpeed(fix, result.distanceDeltaM, previousTimestampMs)
                quality = qualityFor(fix.accuracyM)

                val point = TrackPoint(
                    seq = seq,
                    segment = segment,
                    timestampMs = fix.epochMs,
                    lat = fix.lat,
                    lon = fix.lon,
                    elevationM = fix.altitudeM,
                    accuracyM = fix.accuracyM,
                    speedMps = fix.speedMps,
                    cumulativeDistanceM = distance,
                )
                seq++
                lastPoint = point
                FixOutcome.Recorded(point, metrics)
            }
        }
    }

    /**
     * Doppler speed from the GNSS chip beats differencing two noisy positions, so it is
     * preferred whenever the receiver reports it with usable confidence.
     */
    private fun sampleSpeed(fix: RawFix, distanceDeltaM: Double, previousTimestampMs: Long?) {
        val reported = fix.speedMps?.toDouble()
        val accuracy = fix.speedAccuracyMps
        if (reported != null && (accuracy == null || accuracy < 1.0f)) {
            paceSmoother.onSample(reported)
            return
        }
        val previous = previousTimestampMs ?: return
        val dt = (fix.epochMs - previous) / 1000.0
        if (dt > 0) paceSmoother.onSample(distanceDeltaM / dt)
    }

    private fun activeDurationOf(points: List<TrackPoint>): Long {
        var total = 0L
        for (i in 1 until points.size) {
            val delta = points[i].timestampMs - points[i - 1].timestampMs
            if (points[i].segment == points[i - 1].segment && delta in 1..30_000) total += delta
        }
        return total
    }

    private fun qualityFor(accuracyM: Float?): GpsQuality = when {
        accuracyM == null -> GpsQuality.NO_FIX
        accuracyM <= 8f -> GpsQuality.GOOD
        accuracyM <= 15f -> GpsQuality.OK
        else -> GpsQuality.POOR
    }

    private fun degrade(current: GpsQuality): GpsQuality = when (current) {
        GpsQuality.GOOD -> GpsQuality.OK
        GpsQuality.OK -> GpsQuality.POOR
        else -> current
    }
}

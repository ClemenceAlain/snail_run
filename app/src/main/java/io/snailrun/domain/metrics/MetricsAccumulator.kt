package io.snailrun.domain.metrics

import io.snailrun.domain.geo.GeoDistance
import io.snailrun.domain.geo.SmoothedFix
import io.snailrun.domain.geo.TrackSmoother
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
    /**
     * Corrects the positions the distance is measured from. Null restores the raw
     * behaviour, which is how the filter's own arithmetic is still tested in isolation.
     *
     * The track point keeps the raw latitude and longitude: the database is the archive
     * of what the chip actually said, so a better filter later can be run over runs
     * already recorded. Only the derived figures are smoothed.
     */
    private val smoother: TrackSmoother? = TrackSmoother(TrackSmoother.STORED),
    /** See [LIVE_LAG_FIXES]. A parameter so a test can measure what the lag buys. */
    private val lagFixes: Int = LIVE_LAG_FIXES,
) {
    private val filter = FixFilter(filterConfig)

    private var status = RunStatus.RECORDING

    /**
     * The distance shown. Never goes down: it is the larger of what it was and
     * [committed] plus the provisional tail, which can shrink by a few centimetres
     * when a new fix redraws the last few seconds.
     */
    private var distance = 0.0

    /**
     * Distance up to the oldest fix of [window], measured between positions that have
     * been corrected by the fixes after them and will not move again.
     */
    private var committed = 0.0

    /**
     * The last few seconds, still provisional. Each new fix runs the smoother's backward
     * pass over them, so a position is corrected by what came after it before its step
     * is committed. A filter that only looks back overshoots the start of every bend and
     * reads a running track 12 % long; looking a few seconds ahead takes most of that out.
     */
    private val window = ArrayDeque<TrackSmoother.Snapshot>()

    /** For each fix in [window], whether the step into it counts. See [smoothedStep]. */
    private val windowCounts = ArrayDeque<Boolean>()

    /** The newest committed position in this stretch of track, or null at its start. */
    private var settled: TrackSmoother.SmoothedPoint? = null
    private var activeDurationMs = 0L

    /**
     * Fixes left before the phone's own speed is believed again. After a hole in the
     * fixes some phones report a speed of exactly zero for a fix or two while the runner
     * is plainly moving, and believing it threw away the whole step across the hole —
     * forty metres of a 300 m rep, on one recorded session.
     */
    private var untrustedSpeedFixes = 0
    private var lastTimestampMs: Long? = null
    private var seq = 0
    private var segment = 0
    private var lastPoint: TrackPoint? = null
    private var quality = GpsQuality.NO_FIX
    private var lastSmoothed: SmoothedFix? = null

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
        committed = distance
        clearWindow()
        seq = last.seq + 1
        // A recovered run always resumes in a new segment: whatever happened while the
        // app was dead is a gap, not a straight line.
        segment = last.segment + 1
        lastPoint = last
        lastTimestampMs = null
        activeDurationMs = TrackGaps.activeDurationOf(points)
        points.forEach { elevation.onElevation(it.elevationM) }
        status = RunStatus.PAUSED_MANUAL
        filter.reset()
        paceSmoother.reset()
        smoother?.reset()
        lastSmoothed = null
    }

    fun pause(manual: Boolean = true) {
        if (status == RunStatus.COMPLETE) return
        status = if (manual) RunStatus.PAUSED_MANUAL else RunStatus.PAUSED_AUTO
        // A new segment, so the gap crossed while paused is never drawn or counted.
        segment++
        filter.reset()
        paceSmoother.reset()
        // The smoother's velocity must not survive a pause: carried across it, the first
        // fix on resume would be predicted somewhere the runner never went.
        settleWindow()
        smoother?.reset()
        lastSmoothed = null
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
                val gapMs = previousTimestampMs?.let { fix.epochMs - it } ?: 0L
                val straightLineM = lastPoint
                    ?.let { GeoDistance.between(it.lat, it.lon, fix.lat, fix.lon) }
                    ?: 0.0
                val gap = if (previousTimestampMs == null) GapKind.Continuous
                else TrackGaps.classify(gapMs, straightLineM)

                lastTimestampMs = fix.epochMs
                if (gapMs > MISSED_FIX_MS) untrustedSpeedFixes = UNTRUSTED_AFTER_HOLE

                val shownBefore = distance
                when (gap) {
                    GapKind.Continuous -> {
                        // Guard against a clock jump being counted as running.
                        if (gapMs > 0) activeDurationMs += gapMs
                        if (!smoothedStep(fix)) committed += result.distanceDeltaM
                        distance = maxOf(distance, committed + tail())
                        sampleSpeed(fix, distance - shownBefore, previousTimestampMs)
                    }

                    // The signal came back somewhere the runner could have run to. The
                    // clock ran through the hole because the runner did, and the straight
                    // line between the two fixes is the only route the data supports.
                    GapKind.Inferred -> {
                        activeDurationMs += gapMs
                        inferAcross(fix, straightLineM, gapMs)
                        distance = maxOf(distance, committed + tail())
                    }

                    // Too long, too far or too slow to be running. Nothing crosses it:
                    // not the clock, not the distance, and not the drawn trace.
                    GapKind.Broken -> {
                        breakTrack()
                        smoothedStep(fix)
                    }
                }
                elevation.onElevation(fix.altitudeM)
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
     * Picks the run up on the far side of a hole in the fixes.
     *
     * The position filter is started again rather than predicted across: a velocity
     * carried over minutes is a fiction, and the first fix back is the best position
     * there is. The pace is the one the gap implies — distance over the time it took —
     * because the chip's instantaneous reading at the moment it regained the sky says
     * nothing about the two minutes before it.
     */
    private fun inferAcross(fix: RawFix, straightLineM: Double, gapMs: Long) {
        settleWindow()
        committed += straightLineM
        smoother?.reset()
        lastSmoothed = null
        smoothedStep(fix)
        paceSmoother.reset()
        if (gapMs > 0) paceSmoother.onSample(straightLineM / (gapMs / 1000.0))
    }

    /**
     * Starts a new segment where the fixes came back.
     *
     * The same thing a pause does, and for the same reason: nothing may be drawn or
     * counted across a hole the app cannot account for. The run itself keeps going —
     * losing the signal is not the runner stopping.
     */
    private fun breakTrack() {
        segment++
        paceSmoother.reset()
        settleWindow()
        smoother?.reset()
        lastSmoothed = null
    }

    /**
     * The displacement the smoother believes in, or null when smoothing is off.
     *
     * The filter's own jitter floor is skipped here: it exists to stop raw noise being
     * integrated, and the smoother has already removed the noise. Applying both would
     * discard real, slow movement twice over.
     *
     * A speed floor replaces it. Standing at a light, the estimate still drifts a few
     * tenths of a metre per second, and over a minute that is twenty metres of distance
     * the runner never covered.
     *
     * The floor reads the chip's Doppler speed where that is trustworthy, and the
     * filter's own velocity otherwise. Doppler collapses to zero the moment the runner
     * stops, whereas the filter — deliberately stiff, so that it does not chase jitter —
     * takes several seconds to accept it and coasts a good ten metres in the meantime.
     */
    private fun smoothedStep(fix: RawFix): Boolean {
        val smoother = smoother ?: return false
        val next = smoother.onFix(fix.epochMs, fix.lat, fix.lon, fix.accuracyM)
        val previous = lastSmoothed
        lastSmoothed = next
        val trustReported = untrustedSpeedFixes == 0
        if (untrustedSpeedFixes > 0) untrustedSpeedFixes--
        val counts = previous != null &&
            gateSpeedOf(fix, next, trustReported) >= MIN_SMOOTHED_SPEED_MPS

        window.addLast(smoother.snapshot())
        windowCounts.addLast(counts)
        if (window.size > lagFixes + 1) {
            // The oldest fix has now been corrected by every fix the window holds after
            // it. Its step is committed and it leaves.
            val oldest = TrackSmoother.backwardPass(window).first()
            if (windowCounts.first()) committed += stepBetween(settled, oldest)
            settled = oldest
            window.removeFirst()
            windowCounts.removeFirst()
        }
        return true
    }

    /** The provisional distance across [window], from the last committed position. */
    private fun tail(): Double {
        if (window.isEmpty()) return 0.0
        var total = 0.0
        var previous = settled
        TrackSmoother.backwardPass(window).forEachIndexed { i, point ->
            if (windowCounts[i]) total += stepBetween(previous, point)
            previous = point
        }
        return total
    }

    /** Commits the whole window: the track stops here, so nothing after it will correct it. */
    private fun settleWindow() {
        committed += tail()
        clearWindow()
    }

    private fun clearWindow() {
        window.clear()
        windowCounts.clear()
        settled = null
    }

    private fun stepBetween(from: TrackSmoother.SmoothedPoint?, to: TrackSmoother.SmoothedPoint): Double {
        from ?: return 0.0
        val step = GeoDistance.between(from.lat, from.lon, to.lat, to.lon)
        return if (step >= MIN_SMOOTHED_STEP_M) step else 0.0
    }

    private fun gateSpeedOf(fix: RawFix, smoothed: SmoothedFix, trustReported: Boolean): Double {
        if (!trustReported) return smoothed.speedMps
        val reported = fix.speedMps?.toDouble()
        val accuracy = fix.speedAccuracyMps
        return if (reported != null && (accuracy == null || accuracy < 1.0f)) reported
        else smoothed.speedMps
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

    companion object {
        /** Sub-centimetre steps are arithmetic, not movement. */
        private const val MIN_SMOOTHED_STEP_M = 0.05

        /** A third of a metre per second is a shuffle, not a walk, let alone a run. */
        private const val MIN_SMOOTHED_SPEED_MPS = 0.4

        /** Longer than this between fixes and at least one was missed. */
        private const val MISSED_FIX_MS = 1_500L

        /**
         * How many fixes the live distance waits for before committing a step. Six
         * seconds: long enough for the backward pass to take the overshoot out of a
         * bend, short enough that the number on screen is never visibly behind.
         */
        const val LIVE_LAG_FIXES = 6

        /** How many fixes after a hole the phone's speed is not believed for. */
        private const val UNTRUSTED_AFTER_HOLE = 2
    }
}

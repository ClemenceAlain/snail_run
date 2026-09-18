package io.snailrun.tracking

import io.snailrun.data.prefs.SettingsRepository
import io.snailrun.data.repo.RunRepository
import io.snailrun.data.repo.SOURCE_RECORDED
import io.snailrun.domain.analysis.SplitCalculator
import io.snailrun.domain.geo.TrackSmoother
import io.snailrun.domain.metrics.AutoPauseDetector
import io.snailrun.domain.metrics.AutoPauseEvent
import io.snailrun.domain.metrics.FixOutcome
import io.snailrun.domain.metrics.MetricsAccumulator
import io.snailrun.domain.metrics.RunMetrics
import io.snailrun.domain.model.RawFix
import io.snailrun.domain.model.RunStatus
import io.snailrun.domain.model.Split
import io.snailrun.domain.model.TrackPoint
import io.snailrun.domain.voice.Announcement
import io.snailrun.domain.voice.AnnouncementCursor
import io.snailrun.domain.voice.AnnouncementScheduler
import io.snailrun.domain.voice.RunProgress
import io.snailrun.domain.voice.VoiceConfig
import java.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface RecordingState {
    data object Idle : RecordingState

    data class Active(
        val runId: Long,
        val metrics: RunMetrics,
        val splits: List<Split>,
        val startedAtEpochMs: Long,
    ) : RecordingState {
        val isPaused: Boolean
            get() = metrics.status == RunStatus.PAUSED_MANUAL ||
                metrics.status == RunStatus.PAUSED_AUTO
    }
}

/**
 * Owns the live run.
 *
 * A singleton holding a [StateFlow] rather than a bound service: the app is a single
 * process, so a binder would only add a connection race on every rotation and a window
 * where the UI has no service to read. Durability comes from Room, not from the binding.
 *
 * Points are buffered and flushed in batches, and the run summary is rewritten every few
 * seconds, so an interrupted run loses at most the last few seconds of track.
 */
class RunRecorder(
    private val repository: RunRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val flushIntervalMs: Long = 10_000,
    private val summaryIntervalMs: Long = 5_000,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private var accumulator = MetricsAccumulator()
    private var scheduler = AnnouncementScheduler(VoiceConfig(enabled = false))
    private var cursor = AnnouncementCursor()
    private var runId: Long? = null
    private var startedAtEpochMs: Long = 0
    private var autoPause: AutoPauseDetector? = null

    /**
     * A second, independent filter, used only to answer "is the runner moving".
     *
     * It has to be separate from the one inside the accumulator because it must keep
     * running while the run is paused — the fixes that arrive then are the only evidence
     * that the runner has set off again, and the accumulator throws them away.
     */
    private var autoPauseSmoother = TrackSmoother()

    private val pending = mutableListOf<TrackPoint>()
    private val recorded = mutableListOf<TrackPoint>()
    private var lastFlushMs = 0L
    private var lastSummaryMs = 0L

    /** Set by the service; the recorder never talks to Android directly. */
    var onAnnouncement: ((Announcement) -> Unit)? = null

    /** [source] tags the run, so a demo one is never mistaken for a real one. */
    suspend fun start(source: String = SOURCE_RECORDED): Long = mutex.withLock {
        val saved = settings.settings.first()
        scheduler = AnnouncementScheduler(saved.voice)
        cursor = AnnouncementCursor()
        accumulator = MetricsAccumulator()
        autoPause = if (saved.autoPauseEnabled) AutoPauseDetector() else null
        autoPauseSmoother = TrackSmoother()
        pending.clear()
        recorded.clear()

        val now = clock.millis()
        val id = repository.startRun(now, source)
        runId = id
        startedAtEpochMs = now
        lastFlushMs = now
        lastSummaryMs = now
        publish()
        id
    }

    /** Rebuilds live state from a run's stored points after the process was killed. */
    suspend fun recover(id: Long): Boolean = mutex.withLock {
        val stored = repository.pointsFor(id)
        val run = repository.observeRun(id).first() ?: return@withLock false

        val saved = settings.settings.first()
        scheduler = AnnouncementScheduler(saved.voice)
        cursor = AnnouncementCursor(
            distanceMilestones = run.distanceMilestonesAnnounced,
            lastTimeAnnouncedActiveMs = run.lastTimeAnnouncedActiveMs,
        )
        accumulator = MetricsAccumulator().apply { restore(stored) }
        autoPause = if (saved.autoPauseEnabled) AutoPauseDetector() else null
        autoPauseSmoother = TrackSmoother()
        pending.clear()
        recorded.clear()
        recorded += stored

        runId = id
        startedAtEpochMs = run.startedAtEpochMs
        lastFlushMs = clock.millis()
        lastSummaryMs = lastFlushMs
        publish()
        true
    }

    suspend fun onFix(fix: RawFix) = mutex.withLock {
        val id = runId ?: return@withLock
        applyAutoPause(fix, id)
        when (val outcome = accumulator.onFix(fix)) {
            is FixOutcome.Recorded -> {
                pending += outcome.point
                recorded += outcome.point
                maybeAnnounce(outcome.metrics)
                maybePersist(id)
                publish()
            }

            is FixOutcome.Ignored -> publish()
            is FixOutcome.Paused -> Unit
        }
    }

    suspend fun pause(manual: Boolean = true) = mutex.withLock {
        accumulator.pause(manual)
        // A manual pause starts the detector's reasoning over: whatever it had been
        // building towards is no longer about a run that is moving.
        autoPause?.reset()
        runId?.let { flush(it) }
        publish()
    }

    suspend fun resume() = mutex.withLock {
        accumulator.resume()
        autoPause?.reset()
        publish()
    }

    /**
     * Stops and starts the clock on the runner's behalf.
     *
     * Runs before the fix reaches the accumulator, and on every fix including the ones
     * that arrive while auto-paused — those are the only evidence that the runner has
     * set off again, and the accumulator discards them.
     *
     * A manual pause is never undone here. Someone who stopped the run on purpose does
     * not want it restarted because they walked to the car.
     */
    private suspend fun applyAutoPause(fix: RawFix, id: Long) {
        val detector = autoPause ?: return
        val status = accumulator.metrics.status
        if (status != RunStatus.RECORDING && status != RunStatus.PAUSED_AUTO) return

        val speed = speedOf(fix) ?: return
        when (detector.onSpeed(fix.epochMs, speed, isAutoPaused = status == RunStatus.PAUSED_AUTO)) {
            AutoPauseEvent.Pause -> {
                accumulator.pause(manual = false)
                flush(id)
                publish()
            }

            AutoPauseEvent.Resume -> {
                accumulator.resume()
                publish()
            }

            AutoPauseEvent.None -> Unit
        }
    }

    /**
     * Doppler where the chip vouches for it, the filter's own velocity otherwise.
     *
     * Never the plain difference between two fixes: standing still, that reads about a
     * metre per second of pure noise, which is enough to keep the clock running through
     * every traffic light of the run.
     */
    private fun speedOf(fix: RawFix): Double? {
        val smoothed = autoPauseSmoother.onFix(fix.epochMs, fix.lat, fix.lon, fix.accuracyM)

        val reported = fix.speedMps?.toDouble()
        val accuracy = fix.speedAccuracyMps
        if (reported != null && (accuracy == null || accuracy < 1.0f)) return reported
        return smoothed.speedMps
    }

    /** Finishes the run and returns its id, or null if nothing was being recorded. */
    suspend fun finish(): Long? = mutex.withLock {
        val id = runId ?: return@withLock null
        val metrics = accumulator.finish()
        flush(id)
        repository.completeRun(id, metrics, clock.millis())
        runId = null
        _state.value = RecordingState.Idle
        id
    }

    suspend fun discard() = mutex.withLock {
        val id = runId ?: return@withLock
        repository.deleteRun(id)
        runId = null
        _state.value = RecordingState.Idle
    }

    private fun maybeAnnounce(metrics: RunMetrics) {
        val splits = currentSplits()
        val progress = RunProgress(
            distanceMeters = metrics.distanceMeters,
            activeDurationMs = metrics.activeDurationMs,
            averagePaceSecPerKm = metrics.averagePaceSecPerKm,
            lastSplitPaceSecPerKm = splits.lastOrNull { !it.isPartial }?.paceSecPerKm,
        )
        val (announcement, next) = scheduler.evaluate(progress, cursor) ?: return
        cursor = next
        onAnnouncement?.invoke(announcement)
    }

    private suspend fun maybePersist(id: Long) {
        val now = clock.millis()
        if (now - lastFlushMs >= flushIntervalMs) flush(id)
        if (now - lastSummaryMs >= summaryIntervalMs) {
            lastSummaryMs = now
            repository.updateProgress(
                runId = id,
                metrics = accumulator.metrics,
                distanceMilestones = cursor.distanceMilestones,
                lastTimeAnnouncedActiveMs = cursor.lastTimeAnnouncedActiveMs,
            )
        }
    }

    private suspend fun flush(id: Long) {
        if (pending.isEmpty()) return
        repository.appendPoints(id, pending.toList())
        pending.clear()
        lastFlushMs = clock.millis()
    }

    private fun currentSplits(): List<Split> =
        if (recorded.size < 2) emptyList() else SplitCalculator.compute(recorded)

    private fun publish() {
        val id = runId ?: return
        _state.value = RecordingState.Active(
            runId = id,
            metrics = accumulator.metrics,
            splits = currentSplits(),
            startedAtEpochMs = startedAtEpochMs,
        )
    }
}

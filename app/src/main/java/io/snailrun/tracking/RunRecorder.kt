package io.snailrun.tracking

import io.snailrun.data.prefs.SettingsRepository
import io.snailrun.data.repo.RunRepository
import io.snailrun.data.repo.SOURCE_RECORDED
import io.snailrun.domain.analysis.SplitCalculator
import io.snailrun.domain.coach.Workout
import io.snailrun.domain.coach.WorkoutCue
import io.snailrun.domain.coach.WorkoutCueConfig
import io.snailrun.domain.coach.WorkoutCursor
import io.snailrun.domain.coach.WorkoutProgress
import io.snailrun.domain.coach.WorkoutScheduler
import io.snailrun.domain.coach.WorkoutSegment
import io.snailrun.domain.coach.WorkoutSegments
import io.snailrun.domain.coach.WorkoutType
import io.snailrun.domain.geo.TrackSmoother
import io.snailrun.domain.metrics.AutoPauseDetector
import io.snailrun.domain.metrics.AutoPauseEvent
import io.snailrun.domain.metrics.FixOutcome
import io.snailrun.domain.metrics.MetricsAccumulator
import io.snailrun.domain.metrics.Motion
import io.snailrun.domain.metrics.RunMetrics
import io.snailrun.domain.metrics.TrackGaps
import io.snailrun.domain.model.RawFix
import io.snailrun.domain.model.RunStatus
import io.snailrun.domain.model.Split
import io.snailrun.domain.model.TrackPoint
import io.snailrun.domain.voice.Announcement
import io.snailrun.domain.voice.AnnouncementCursor
import io.snailrun.domain.voice.RunNotice
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
        /** Where the runner is in a structured session, or null for an ordinary run. */
        val workout: WorkoutProgress? = null,
        val workoutType: WorkoutType? = null,
        /**
         * The whole session, unrolled, so the screen can show what is still to come.
         *
         * Carried alongside the progress rather than looked up again: after a crash the
         * segments come back from the database and the armed [Workout] is long gone, so
         * this is the only place the full sequence still exists.
         */
        val workoutSegments: List<WorkoutSegment> = emptyList(),
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

    /** When the last fix of any kind arrived, so a hole in them can be noticed. */
    private var lastFixMs: Long? = null

    private var workout: WorkoutScheduler? = null
    private var workoutType: WorkoutType? = null
    private var workoutCursor = WorkoutCursor()

    private val pending = mutableListOf<TrackPoint>()
    private val recorded = mutableListOf<TrackPoint>()
    private var lastFlushMs = 0L
    private var lastSummaryMs = 0L

    /** Set by the service; the recorder never talks to Android directly. */
    var onAnnouncement: ((Announcement) -> Unit)? = null

    /** The same, for the things the app does to the run rather than reports about it. */
    var onNotice: ((RunNotice) -> Unit)? = null

    /** And again, for counting the runner through a session. */
    var onCue: ((WorkoutCue) -> Unit)? = null

    /**
     * Read once at the start of a run, like the auto-pause setting beside it. A notice
     * is part of the voice feature: someone who turned the voice off wants the app
     * quiet, including about its own decisions.
     */
    private var voiceEnabled: Boolean = false

    /**
     * [source] tags the run, so a demo one is never mistaken for a real one.
     *
     * [session] is the structured workout to be counted through, if one was armed. It is
     * read once here, like the settings beside it — a session changed mid-run would leave
     * the runner halfway through a rep that no longer exists.
     */
    suspend fun start(source: String = SOURCE_RECORDED, session: Workout? = null): Long = mutex.withLock {
        val saved = settings.settings.first()
        scheduler = AnnouncementScheduler(saved.voice)
        cursor = AnnouncementCursor()
        accumulator = MetricsAccumulator()
        voiceEnabled = saved.voice.enabled
        autoPause = if (saved.autoPauseEnabled) AutoPauseDetector() else null
        autoPauseSmoother = TrackSmoother()
        lastFixMs = null
        pending.clear()
        recorded.clear()

        val segments = session?.let { WorkoutSegments.of(it) }.orEmpty()
        workout = segments.takeIf { it.isNotEmpty() }?.let {
            WorkoutScheduler(it, WorkoutCueConfig(nudgeOffPace = saved.coach.nudgeOffPace))
        }
        workoutType = session?.type.takeIf { workout != null }
        workoutCursor = WorkoutCursor()

        val now = clock.millis()
        val id = repository.startRun(now, source)
        runId = id
        startedAtEpochMs = now
        lastFlushMs = now
        lastSummaryMs = now
        if (workout != null && session != null) {
            repository.attachWorkout(id, session.type, segments)
        }
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
        voiceEnabled = saved.voice.enabled

        // The session is rebuilt by running the stored track back through the scheduler.
        // Where the runner had got to was never written down because it never needed to
        // be: it is a function of the track and the handful of times they pressed Next.
        val segments = repository.workoutSegmentsFor(id)
        workoutType = run.workoutType?.let { name ->
            runCatching { WorkoutType.valueOf(name) }.getOrNull()
        }
        workout = segments.takeIf { it.isNotEmpty() }?.let {
            WorkoutScheduler(it, WorkoutCueConfig(nudgeOffPace = saved.coach.nudgeOffPace))
        }
        workoutCursor = replayWorkout(stored, run.workoutAdvancesActiveMs)
        autoPause = if (saved.autoPauseEnabled) AutoPauseDetector() else null
        autoPauseSmoother = TrackSmoother()
        lastFixMs = null
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

    /**
     * [motion] is the accelerometer's reading at the moment the fix arrived, or null
     * without one. Passed in rather than read here, so the recorder stays free of Android
     * and a test can say exactly what the phone felt.
     */
    suspend fun onFix(fix: RawFix, motion: Motion? = null) = mutex.withLock {
        val id = runId ?: return@withLock

        // A hole in the fixes wipes what the auto-pause detector was thinking. Its
        // countdown is wall-clock between fixes, so a runner whose last fix before a
        // tunnel read slow would be paused by the first fix out of it — three minutes
        // of silence counted as three minutes of standing still. The filter it reads
        // has no velocity across a hole either.
        lastFixMs?.let { previous ->
            if (fix.epochMs - previous > TrackGaps.CONTINUOUS_MS) {
                autoPause?.reset()
                autoPauseSmoother = TrackSmoother()
            }
        }
        lastFixMs = fix.epochMs

        applyAutoPause(fix, id, motion)
        when (val outcome = accumulator.onFix(fix)) {
            is FixOutcome.Recorded -> {
                pending += outcome.point
                recorded += outcome.point
                maybeCue(outcome.metrics)
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
    private suspend fun applyAutoPause(fix: RawFix, id: Long, motion: Motion?) {
        val detector = autoPause ?: return
        val status = accumulator.metrics.status
        if (status != RunStatus.RECORDING && status != RunStatus.PAUSED_AUTO) return

        val speed = speedOf(fix) ?: return
        when (detector.onSpeed(fix.epochMs, speed, isAutoPaused = status == RunStatus.PAUSED_AUTO, motion = motion)) {
            AutoPauseEvent.Pause -> {
                accumulator.pause(manual = false)
                flush(id)
                publish()
                announce(RunNotice.AutoPaused)
            }

            AutoPauseEvent.Resume -> {
                accumulator.resume()
                publish()
                // Said on the way out as well as in. A clock that started again without
                // saying so is the more expensive silence: the runner who missed the
                // pause is still standing there believing they are being timed.
                announce(RunNotice.AutoResumed)
            }

            AutoPauseEvent.None -> Unit
        }
    }

    private fun announce(notice: RunNotice) {
        if (voiceEnabled) onNotice?.invoke(notice)
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

    /**
     * Ends the current segment where the runner is standing.
     *
     * The mark is written to the run because it is the one thing about a guided session
     * that cannot be worked out again from the track: where each segment ended is
     * arithmetic, and the runner deciding they were done with one is not.
     */
    suspend fun nextSegment() = mutex.withLock {
        val id = runId ?: return@withLock
        val session = workout ?: return@withLock
        if (workoutCursor.complete) return@withLock

        val metrics = accumulator.metrics
        workoutCursor = session.advance(metrics.activeDurationMs, metrics.distanceMeters, workoutCursor)
        repository.recordWorkoutAdvance(id, metrics.activeDurationMs)
        maybeCue(metrics)
        publish()
    }

    /** Drops the guidance and keeps recording. The run is a run either way. */
    suspend fun endSession() = mutex.withLock {
        workout = null
        publish()
    }

    /**
     * Counts the runner through the session and reports anything worth saying.
     *
     * A cue also stamps the announcement cursor. The speech engine speaks one utterance
     * at a time and the newest wins, so without this a kilometre milestone landing on the
     * same second as a rep change would talk over "rep three of five" — and the existing
     * twenty-second floor between announcements is exactly the rule that stops it.
     */
    private fun maybeCue(metrics: RunMetrics) {
        val session = workout ?: return
        val (_, cues, next) = session.evaluate(
            activeMs = metrics.activeDurationMs,
            meters = metrics.distanceMeters,
            paceSecPerKm = metrics.paceSecPerKm,
            cursor = workoutCursor,
        )
        workoutCursor = next
        if (cues.isEmpty()) return
        cursor = cursor.copy(lastAnnouncementActiveMs = metrics.activeDurationMs)
        cues.forEach { cue -> onCue?.invoke(cue) }
    }

    /** Runs a stored track back through the scheduler to find where the session had got to. */
    private fun replayWorkout(points: List<TrackPoint>, advances: String?): WorkoutCursor {
        val session = workout ?: return WorkoutCursor()
        val marks = advances?.split(',')?.mapNotNull(String::toLongOrNull).orEmpty().sorted()

        var replayed = WorkoutCursor()
        var applied = 0
        var activeMs = 0L
        var previous: TrackPoint? = null

        points.forEach { point ->
            previous?.let { before ->
                val delta = point.timestampMs - before.timestampMs
                if (point.segment == before.segment) {
                    activeMs += TrackGaps.countable(
                        gapMs = delta,
                        straightLineM = point.cumulativeDistanceM - before.cumulativeDistanceM,
                    )
                }
            }
            previous = point

            while (applied < marks.size && marks[applied] <= activeMs) {
                replayed = session.advance(activeMs, point.cumulativeDistanceM, replayed)
                applied++
            }
            replayed = session.evaluate(activeMs, point.cumulativeDistanceM, null, replayed).third
        }
        return replayed
    }

    private fun publish() {
        val id = runId ?: return
        val metrics = accumulator.metrics
        _state.value = RecordingState.Active(
            runId = id,
            metrics = metrics,
            splits = currentSplits(),
            startedAtEpochMs = startedAtEpochMs,
            workout = workout?.progressOf(
                workoutCursor,
                metrics.activeDurationMs,
                metrics.distanceMeters,
            ),
            workoutType = workoutType,
            workoutSegments = workout?.segments.orEmpty(),
        )
    }
}

package io.snailrun.data.repo

import io.snailrun.data.db.RunDao
import io.snailrun.data.db.RunEntity
import io.snailrun.domain.analysis.BestEffortFinder
import io.snailrun.domain.coach.WorkoutSegment
import io.snailrun.domain.coach.WorkoutType
import io.snailrun.domain.analysis.SplitCalculator
import io.snailrun.domain.geo.TrackSmoother
import io.snailrun.domain.metrics.RunMetrics
import io.snailrun.domain.model.TrackPoint
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

const val STATUS_RECORDING = "RECORDING"
const val STATUS_COMPLETE = "COMPLETE"
const val SOURCE_RECORDED = "RECORDED"

/** A run replayed from the built-in synthetic trace. Kept out of personal records. */
const val SOURCE_DEMO = "DEMO"

/**
 * The only thing that writes runs.
 *
 * Points are appended as they are accepted; splits and records are derived once, when
 * the run completes, so every later screen reads a small table instead of a million
 * rows of track.
 */
class RunRepository(
    private val dao: RunDao,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun observeHistory(): Flow<List<RunEntity>> = dao.observeHistory()

    fun observeRun(id: Long): Flow<RunEntity?> = dao.observeRun(id)

    fun observeSplits(id: Long) = dao.observeSplits(id).map { splits -> splits.map { it.toDomain() } }

    /**
     * The track exactly as the chip reported it. Used to rebuild a live run after a
     * crash, and as the input to every re-derivation. Nothing shows this to a user.
     */
    fun observePoints(id: Long) = dao.observePoints(id).map { points -> points.map { it.toDomain() } }

    suspend fun pointsFor(id: Long): List<TrackPoint> = dao.pointsFor(id).map { it.toDomain() }

    /**
     * The track as the app draws and measures it: raw positions run through the current
     * position filter.
     *
     * Every screen reads this one. The database keeps the raw fixes so that a better
     * filter can be applied to runs already recorded, and the corrected track is derived
     * on the way out rather than frozen on the way in.
     */
    fun observeSmoothedPoints(id: Long) = observePoints(id).map { TrackSmoother.smooth(it) }

    suspend fun smoothedPointsFor(id: Long): List<TrackPoint> =
        TrackSmoother.smooth(pointsFor(id))

    suspend fun unfinishedRun(): RunEntity? = dao.unfinishedRun()

    suspend fun startRun(
        startedAtEpochMs: Long = clock.millis(),
        source: String = SOURCE_RECORDED,
    ): Long {
        val zone = ZoneId.systemDefault()
        return dao.insertRun(
            RunEntity(
                startedAtEpochMs = startedAtEpochMs,
                endedAtEpochMs = null,
                timeZoneId = zone.id,
                localDate = localDate(startedAtEpochMs, zone),
                distanceMeters = 0.0,
                elapsedTimeMs = 0,
                movingTimeMs = 0,
                avgPaceSecPerKm = 0.0,
                elevationGainM = 0.0,
                elevationLossM = 0.0,
                pointCount = 0,
                minLat = 0.0, maxLat = 0.0, minLon = 0.0, maxLon = 0.0,
                title = null,
                note = null,
                source = source,
                status = STATUS_RECORDING,
            )
        )
    }

    /**
     * Writes the session a run is being guided through.
     *
     * Stored rather than looked up later: the coach rebuilds its plan from the history
     * every time it is shown, so by next week the session this run was is not one it
     * would still write. What was asked of the runner has to be kept if the run's own
     * screen is ever to say whether they did it.
     */
    suspend fun attachWorkout(runId: Long, type: WorkoutType, segments: List<WorkoutSegment>) {
        if (segments.isEmpty()) return
        val run = dao.runById(runId) ?: return
        dao.updateRun(run.copy(workoutType = type.name))
        dao.insertWorkoutSegments(segments.map { it.toEntity(runId) })
    }

    suspend fun workoutSegmentsFor(runId: Long): List<WorkoutSegment> =
        dao.workoutSegmentsFor(runId).map { it.toDomain() }

    fun observeWorkoutSegments(runId: Long) =
        dao.observeWorkoutSegments(runId).map { rows -> rows.map { it.toDomain() } }

    /** Remembers that the runner cut a segment short. See [RunEntity.workoutAdvancesActiveMs]. */
    suspend fun recordWorkoutAdvance(runId: Long, activeMs: Long) {
        val run = dao.runById(runId) ?: return
        val marks = (run.workoutAdvancesActiveMs?.split(',').orEmpty() + activeMs.toString())
            .filter { it.isNotBlank() }
        dao.updateRun(run.copy(workoutAdvancesActiveMs = marks.joinToString(",")))
    }

    suspend fun appendPoints(runId: Long, points: List<TrackPoint>) {
        if (points.isEmpty()) return
        dao.insertPoints(points.map { it.toEntity(runId) })
    }

    /** Called every few seconds so an interrupted run can be recovered near-complete. */
    suspend fun updateProgress(
        runId: Long,
        metrics: RunMetrics,
        distanceMilestones: Int,
        lastTimeAnnouncedActiveMs: Long,
    ) {
        val run = dao.runById(runId) ?: return
        dao.updateRun(
            run.copy(
                distanceMeters = metrics.distanceMeters,
                movingTimeMs = metrics.activeDurationMs,
                elapsedTimeMs = clock.millis() - run.startedAtEpochMs,
                elevationGainM = metrics.elevationGainM,
                elevationLossM = metrics.elevationLossM,
                pointCount = metrics.pointCount,
                distanceMilestonesAnnounced = distanceMilestones,
                lastTimeAnnouncedActiveMs = lastTimeAnnouncedActiveMs,
            )
        )
    }

    /**
     * Derives splits and best efforts from the stored track and writes everything in
     * one transaction. Nothing is computed from live state, so a recovered run and a
     * normally finished one go through exactly the same path.
     */
    suspend fun completeRun(runId: Long, metrics: RunMetrics, endedAtEpochMs: Long = clock.millis()) {
        val run = dao.runById(runId) ?: return
        // Derived from the corrected track, like every figure the app shows. The live
        // recorder ran the same filter over the same fixes in the same order, so the
        // number that was on the screen is the number that gets stored.
        val points = TrackSmoother.smooth(dao.pointsFor(runId).map { it.toDomain() })

        val splits = SplitCalculator.compute(points)
        val efforts = BestEffortFinder.findAll(points)

        val bounds = points.fold(Bounds()) { acc, point -> acc.extend(point) }
        val movingTimeMs = metrics.activeDurationMs

        dao.completeRun(
            run = run.copy(
                endedAtEpochMs = endedAtEpochMs,
                distanceMeters = metrics.distanceMeters,
                elapsedTimeMs = endedAtEpochMs - run.startedAtEpochMs,
                movingTimeMs = movingTimeMs,
                avgPaceSecPerKm = if (metrics.distanceMeters < 10.0) 0.0
                    else movingTimeMs / 1000.0 / (metrics.distanceMeters / 1000.0),
                elevationGainM = metrics.elevationGainM,
                elevationLossM = metrics.elevationLossM,
                pointCount = points.size,
                minLat = bounds.minLat, maxLat = bounds.maxLat,
                minLon = bounds.minLon, maxLon = bounds.maxLon,
                status = STATUS_COMPLETE,
                smootherVersion = TrackSmoother.VERSION,
            ),
            splits = splits.map { it.toEntity(runId) },
            efforts = efforts.map { it.toEntity(runId) },
        )
    }

    /**
     * Re-derives every stored figure for runs recorded under an older position filter.
     *
     * The raw positions are never touched — they are the record of what the chip said.
     * What changes is everything computed from them: the cumulative distance on each
     * point, the run's totals, its splits and its best efforts. Improving the filter is
     * therefore a code change and nothing else; the runs already on the phone catch up
     * by themselves.
     *
     * Returns how many runs it brought up to date.
     */
    suspend fun reprocessOutdatedRuns(): Int {
        val ids = dao.runsBehindSmootherVersion(TrackSmoother.VERSION)
        ids.forEach { reprocessRun(it) }
        return ids.size
    }

    private suspend fun reprocessRun(runId: Long) {
        val run = dao.runById(runId) ?: return
        val raw = dao.pointsFor(runId).map { it.toDomain() }
        if (raw.size < 2) {
            dao.updateRun(run.copy(smootherVersion = TrackSmoother.VERSION))
            return
        }

        val smoothed = TrackSmoother.smooth(raw)
        val distance = smoothed.last().cumulativeDistanceM
        val movingTimeMs = activeDurationOf(smoothed)
        val bounds = smoothed.fold(Bounds()) { acc, point -> acc.extend(point) }

        // Only the derived column is written back; the raw latitude and longitude stay
        // exactly as they were recorded.
        dao.insertPoints(
            raw.mapIndexed { i, point ->
                point.copy(cumulativeDistanceM = smoothed[i].cumulativeDistanceM).toEntity(runId)
            }
        )

        dao.replaceDerived(
            run = run.copy(
                distanceMeters = distance,
                movingTimeMs = movingTimeMs,
                avgPaceSecPerKm = if (distance < 10.0) 0.0
                    else movingTimeMs / 1000.0 / (distance / 1000.0),
                pointCount = smoothed.size,
                minLat = bounds.minLat, maxLat = bounds.maxLat,
                minLon = bounds.minLon, maxLon = bounds.maxLon,
                smootherVersion = TrackSmoother.VERSION,
            ),
            splits = SplitCalculator.compute(smoothed).map { it.toEntity(runId) },
            efforts = BestEffortFinder.findAll(smoothed).map { it.toEntity(runId) },
        )
    }

    /** Time spent running: within a segment, and across gaps short enough to be strides. */
    private fun activeDurationOf(points: List<TrackPoint>): Long {
        var total = 0L
        for (i in 1 until points.size) {
            val delta = points[i].timestampMs - points[i - 1].timestampMs
            if (points[i].segment == points[i - 1].segment && delta in 1..30_000) total += delta
        }
        return total
    }

    suspend fun markExported(runId: Long, uri: String) {
        val run = dao.runById(runId) ?: return
        dao.updateRun(run.copy(gpxExportedUri = uri))
    }

    suspend fun rename(runId: Long, title: String) {
        val run = dao.runById(runId) ?: return
        dao.updateRun(run.copy(title = title))
    }

    suspend fun deleteRun(runId: Long) = dao.deleteRun(runId)

    fun observePersonalRecord(distanceMeters: Int) = dao.observePersonalRecord(distanceMeters)

    fun observeRecentEfforts(since: String) = dao.observeRecentEfforts(since)

    fun observeDailyTotals(from: String, to: String) = dao.observeDailyTotals(from, to)

    fun observeMonthlyTotals() = dao.observeMonthlyTotals()

    private data class Bounds(
        val minLat: Double = 0.0,
        val maxLat: Double = 0.0,
        val minLon: Double = 0.0,
        val maxLon: Double = 0.0,
        val empty: Boolean = true,
    ) {
        fun extend(point: TrackPoint): Bounds = if (empty) {
            Bounds(point.lat, point.lat, point.lon, point.lon, empty = false)
        } else {
            Bounds(
                minLat = minOf(minLat, point.lat),
                maxLat = maxOf(maxLat, point.lat),
                minLon = minOf(minLon, point.lon),
                maxLon = maxOf(maxLon, point.lon),
                empty = false,
            )
        }
    }

    companion object {
        private val DATE = DateTimeFormatter.ISO_LOCAL_DATE

        fun localDate(epochMs: Long, zone: ZoneId): String =
            DATE.format(Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate())
    }
}

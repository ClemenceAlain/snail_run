package io.snailrun.data.repo

import io.snailrun.data.db.RunDao
import io.snailrun.data.db.RunEntity
import io.snailrun.domain.analysis.BestEffortFinder
import io.snailrun.domain.analysis.SplitCalculator
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

    fun observePoints(id: Long) = dao.observePoints(id).map { points -> points.map { it.toDomain() } }

    suspend fun pointsFor(id: Long): List<TrackPoint> = dao.pointsFor(id).map { it.toDomain() }

    suspend fun unfinishedRun(): RunEntity? = dao.unfinishedRun()

    suspend fun startRun(startedAtEpochMs: Long = clock.millis()): Long {
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
                source = SOURCE_RECORDED,
                status = STATUS_RECORDING,
            )
        )
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
        val points = dao.pointsFor(runId).map { it.toDomain() }

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
            ),
            splits = splits.map { it.toEntity(runId) },
            efforts = efforts.map { it.toEntity(runId) },
        )
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

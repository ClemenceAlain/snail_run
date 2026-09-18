package io.snailrun.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** One row per calendar day that has runs. Feeds the calendar and the weekly totals. */
data class DailyTotal(
    val localDate: String,
    val runCount: Int,
    val meters: Double,
    val movingMs: Long,
)

data class PeriodTotal(
    val period: String,
    val runCount: Int,
    val meters: Double,
    val movingMs: Long,
    val elevationGainM: Double,
)

data class PersonalRecord(
    val distanceMeters: Int,
    val durationMs: Long,
    val runId: Long,
    val startedAtEpochMs: Long,
)

@Dao
interface RunDao {

    @Insert
    suspend fun insertRun(run: RunEntity): Long

    @Update
    suspend fun updateRun(run: RunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<TrackPointEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSplits(splits: List<SplitEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBestEfforts(efforts: List<BestEffortEntity>)

    /**
     * Completing a run writes its summary and both derived tables together, so a crash
     * can never leave a run that is finished but has no splits.
     */
    @Transaction
    suspend fun completeRun(
        run: RunEntity,
        splits: List<SplitEntity>,
        efforts: List<BestEffortEntity>,
    ) {
        updateRun(run)
        insertSplits(splits)
        insertBestEfforts(efforts)
    }

    @Query("SELECT * FROM runs WHERE id = :id")
    suspend fun runById(id: Long): RunEntity?

    @Query("SELECT * FROM runs WHERE id = :id")
    fun observeRun(id: Long): Flow<RunEntity?>

    @Query("SELECT * FROM runs WHERE status = 'COMPLETE' ORDER BY startedAtEpochMs DESC")
    fun observeHistory(): Flow<List<RunEntity>>

    /** An interrupted run: the process died while it was still recording. */
    @Query("SELECT * FROM runs WHERE status != 'COMPLETE' ORDER BY startedAtEpochMs DESC LIMIT 1")
    suspend fun unfinishedRun(): RunEntity?

    @Query("SELECT * FROM track_points WHERE runId = :runId ORDER BY seq")
    suspend fun pointsFor(runId: Long): List<TrackPointEntity>

    @Query("SELECT * FROM track_points WHERE runId = :runId ORDER BY seq")
    fun observePoints(runId: Long): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM splits WHERE runId = :runId ORDER BY splitIndex")
    fun observeSplits(runId: Long): Flow<List<SplitEntity>>

    @Query("SELECT * FROM best_efforts WHERE runId = :runId")
    suspend fun effortsFor(runId: Long): List<BestEffortEntity>

    @Query(
        """
        SELECT localDate,
               COUNT(*) AS runCount,
               SUM(distanceMeters) AS meters,
               SUM(movingTimeMs) AS movingMs
        FROM runs
        WHERE status = 'COMPLETE' AND localDate BETWEEN :from AND :to
        GROUP BY localDate
        """
    )
    fun observeDailyTotals(from: String, to: String): Flow<List<DailyTotal>>

    @Query(
        """
        SELECT substr(localDate, 1, 7) AS period,
               COUNT(*) AS runCount,
               SUM(distanceMeters) AS meters,
               SUM(movingTimeMs) AS movingMs,
               SUM(elevationGainM) AS elevationGainM
        FROM runs
        WHERE status = 'COMPLETE'
        GROUP BY period
        ORDER BY period DESC
        """
    )
    fun observeMonthlyTotals(): Flow<List<PeriodTotal>>

    /** Index-only lookup thanks to best_efforts(distanceMeters, durationMs). */
    @Query(
        """
        SELECT be.distanceMeters AS distanceMeters,
               be.durationMs AS durationMs,
               be.runId AS runId,
               r.startedAtEpochMs AS startedAtEpochMs
        FROM best_efforts be
        JOIN runs r ON r.id = be.runId
        WHERE be.distanceMeters = :distanceMeters
          AND r.status = 'COMPLETE'
          AND r.source != 'DEMO'
        ORDER BY be.durationMs ASC
        LIMIT 1
        """
    )
    fun observePersonalRecord(distanceMeters: Int): Flow<PersonalRecord?>

    /** Cascades to points, splits and efforts. */
    @Query("DELETE FROM runs WHERE id = :id")
    suspend fun deleteRun(id: Long)
}

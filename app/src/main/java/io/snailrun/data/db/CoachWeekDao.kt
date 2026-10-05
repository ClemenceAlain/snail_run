package io.snailrun.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Saved coach weeks. Suspend reads only, and on purpose: a Flow over this table would
 * fire on every save, and the save happens whenever the week is planned — which is what
 * the Flow would trigger.
 */
@Dao
interface CoachWeekDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(week: CoachWeekEntity)

    @Query("SELECT * FROM coach_weeks WHERE weekStartEpochDay = :weekStartEpochDay")
    suspend fun week(weekStartEpochDay: Long): CoachWeekEntity?

    @Query("SELECT weekStartEpochDay FROM coach_weeks WHERE weekStartEpochDay < :before ORDER BY weekStartEpochDay DESC")
    suspend fun weekStartsBefore(before: Long): List<Long>

    @Query("DELETE FROM coach_weeks WHERE weekStartEpochDay >= :from")
    suspend fun deleteFrom(from: Long)
}

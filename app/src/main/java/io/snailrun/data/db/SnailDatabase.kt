package io.snailrun.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        RunEntity::class,
        TrackPointEntity::class,
        SplitEntity::class,
        BestEffortEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SnailDatabase : RoomDatabase() {

    abstract fun runDao(): RunDao

    companion object {
        fun build(context: Context): SnailDatabase =
            Room.databaseBuilder(context, SnailDatabase::class.java, "snail-run.db")
                // Concurrent reads during a run's 10-second insert flushes.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}

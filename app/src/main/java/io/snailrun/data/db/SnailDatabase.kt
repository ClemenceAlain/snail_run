package io.snailrun.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RunEntity::class,
        TrackPointEntity::class,
        SplitEntity::class,
        BestEffortEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class SnailDatabase : RoomDatabase() {

    abstract fun runDao(): RunDao

    companion object {
        /**
         * Runs recorded before the position filter existed are stamped version 0, which
         * is what makes them get re-derived on next launch instead of keeping figures
         * nothing can reproduce.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE runs ADD COLUMN smootherVersion INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun build(context: Context): SnailDatabase =
            Room.databaseBuilder(context, SnailDatabase::class.java, "snail-run.db")
                // Concurrent reads during a run's 10-second insert flushes.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

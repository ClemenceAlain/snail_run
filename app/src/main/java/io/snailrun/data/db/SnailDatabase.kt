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
        WorkoutSegmentEntity::class,
    ],
    version = SnailDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
abstract class SnailDatabase : RoomDatabase() {

    abstract fun runDao(): RunDao

    companion object {
        /**
         * The one place the schema version is written. The annotation above reads it, and
         * so does the backup check that refuses a file from a newer build — a constant
         * that drifted from the annotation would let that check pass on a file this
         * build cannot read.
         */
        const val SCHEMA_VERSION = 3

        const val FILE_NAME = "snail-run.db"

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

        /**
         * Structured sessions. Both columns are nullable and both are null for every run
         * already on the phone, because none of them was guided through anything.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE runs ADD COLUMN workoutType TEXT")
                db.execSQL("ALTER TABLE runs ADD COLUMN workoutAdvancesActiveMs TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS workout_segments (
                        runId INTEGER NOT NULL,
                        segmentIndex INTEGER NOT NULL,
                        label TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        targetMs INTEGER,
                        targetM REAL,
                        paceLowSecPerKm REAL,
                        paceHighSecPerKm REAL,
                        repIndex INTEGER,
                        repCount INTEGER,
                        PRIMARY KEY (runId, segmentIndex),
                        FOREIGN KEY (runId) REFERENCES runs(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
            }
        }

        fun build(context: Context): SnailDatabase =
            Room.databaseBuilder(context, SnailDatabase::class.java, FILE_NAME)
                // Concurrent reads during a run's 10-second insert flushes.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}

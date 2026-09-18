package io.snailrun.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A migration that fails takes every run on the phone with it, and there is no copy
 * anywhere else. So the upgrade is exercised against a real version 1 database, with a
 * row in it, rather than trusted.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SnailDatabase::class.java,
    )

    @Test
    fun `version 1 upgrades to 2 and keeps the runs it held`() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO runs (
                    id, activityType, startedAtEpochMs, endedAtEpochMs, timeZoneId,
                    localDate, distanceMeters, elapsedTimeMs, movingTimeMs,
                    avgPaceSecPerKm, elevationGainM, elevationLossM, pointCount,
                    minLat, maxLat, minLon, maxLon, title, note, source, status,
                    distanceMilestonesAnnounced, lastTimeAnnouncedActiveMs, gpxExportedUri
                ) VALUES (
                    1, 'RUN', 1700000000000, 1700000600000, 'Europe/Paris',
                    '2023-11-14', 3000.0, 600000, 600000,
                    200.0, 12.0, 8.0, 601,
                    48.85, 48.87, 2.35, 2.36, NULL, NULL, 'RECORDED', 'COMPLETE',
                    3, 0, NULL
                )
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            2,
            true,
            SnailDatabase.MIGRATION_1_2,
        )

        db.query("SELECT distanceMeters, smootherVersion FROM runs WHERE id = 1").use { cursor ->
            assertTrue("the run did not survive the migration", cursor.moveToFirst())
            assertEquals(3000.0, cursor.getDouble(0), 0.0)
            // Zero, which is what marks it for re-derivation on next launch.
            assertEquals(0, cursor.getInt(1))
        }
        db.close()
    }

    @Test
    fun `version 2 upgrades to 3 and the runs it held gained no session`() {
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO runs (
                    id, activityType, startedAtEpochMs, endedAtEpochMs, timeZoneId,
                    localDate, distanceMeters, elapsedTimeMs, movingTimeMs,
                    avgPaceSecPerKm, elevationGainM, elevationLossM, pointCount,
                    minLat, maxLat, minLon, maxLon, title, note, source, status,
                    distanceMilestonesAnnounced, lastTimeAnnouncedActiveMs, gpxExportedUri,
                    smootherVersion
                ) VALUES (
                    1, 'RUN', 1700000000000, 1700000600000, 'Europe/Paris',
                    '2023-11-14', 3000.0, 600000, 600000,
                    200.0, 12.0, 8.0, 601,
                    48.85, 48.87, 2.35, 2.36, NULL, NULL, 'RECORDED', 'COMPLETE',
                    3, 0, NULL, 1
                )
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 3, true, SnailDatabase.MIGRATION_2_3)

        db.query("SELECT distanceMeters, workoutType, workoutAdvancesActiveMs FROM runs WHERE id = 1")
            .use { cursor ->
                assertTrue("the run did not survive the migration", cursor.moveToFirst())
                assertEquals(3000.0, cursor.getDouble(0), 0.0)
                // Null, because nothing guided a run recorded before this existed.
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
            }

        db.query("SELECT COUNT(*) FROM workout_segments").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }

    /** The whole way up, as a phone two releases behind would take it. */
    @Test
    fun `version 1 upgrades all the way to 3`() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO runs (
                    id, activityType, startedAtEpochMs, endedAtEpochMs, timeZoneId,
                    localDate, distanceMeters, elapsedTimeMs, movingTimeMs,
                    avgPaceSecPerKm, elevationGainM, elevationLossM, pointCount,
                    minLat, maxLat, minLon, maxLon, title, note, source, status,
                    distanceMilestonesAnnounced, lastTimeAnnouncedActiveMs, gpxExportedUri
                ) VALUES (
                    7, 'RUN', 1700000000000, 1700000600000, 'Europe/Paris',
                    '2023-11-14', 4200.0, 600000, 600000,
                    200.0, 12.0, 8.0, 601,
                    48.85, 48.87, 2.35, 2.36, NULL, NULL, 'RECORDED', 'COMPLETE',
                    3, 0, NULL
                )
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            3,
            true,
            SnailDatabase.MIGRATION_1_2,
            SnailDatabase.MIGRATION_2_3,
        )

        db.query("SELECT distanceMeters, smootherVersion, workoutType FROM runs WHERE id = 7")
            .use { cursor ->
                assertTrue("the run did not survive two migrations", cursor.moveToFirst())
                assertEquals(4200.0, cursor.getDouble(0), 0.0)
                assertEquals(0, cursor.getInt(1))
                assertTrue(cursor.isNull(2))
            }
        db.close()
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}

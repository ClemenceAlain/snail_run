package io.snailrun.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.snailrun.data.db.BestEffortEntity
import io.snailrun.domain.coach.CoachBaseline
import io.snailrun.data.db.RunEntity
import io.snailrun.data.db.SnailDatabase
import io.snailrun.data.db.SplitEntity
import io.snailrun.data.prefs.CoachSettings
import io.snailrun.data.db.TrackPointEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Against a real on-disk database in write-ahead-logging mode, never an in-memory one.
 * The WAL is most of the reason this class is not a file copy, so a test that did not
 * have one would agree with whatever the implementation happened to do.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseBackupTest {

    @get:Rule
    val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SnailDatabase::class.java,
    )

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var live: SnailDatabase
    private var recording = false
    private lateinit var backup: DatabaseBackup

    /** Stands in for the preference store the coach actually lives in. */
    private var coach = CoachSettings()

    private val scratch = mutableListOf<File>()

    @Before
    fun setUp() {
        context.getDatabasePath(SnailDatabase.FILE_NAME).parentFile?.mkdirs()
        live = SnailDatabase.build(context)
        backup = DatabaseBackup(
            context = context,
            database = { live },
            isRecording = { recording },
            readCoach = { coach },
            writeCoach = { coach = it },
        )
    }

    @After
    fun tearDown() {
        runCatching { live.close() }
        val db = context.getDatabasePath(SnailDatabase.FILE_NAME)
        listOf(db, File("${db.path}-wal"), File("${db.path}-shm")).forEach { it.delete() }
        scratch.forEach { it.delete() }
    }

    // ---- the round trip -------------------------------------------------------------

    @Test
    fun `a backup carries every run, point, split and record back`() = runBlocking {
        val runId = insertFullRun(distance = 5000.0, title = "Canal loop")

        val file = backUpToFile()
        assertEquals(1, (file.second as BackupResult.Written).runCount)

        // Everything on the phone is thrown away, exactly as an uninstall would.
        wipeLiveDatabase()
        assertNull("the wipe did not take", live.runDao().runById(runId))

        val result = backup.restoreFrom(file.first)

        assertEquals(RestoreResult.Restored(1), result)
        val dao = reopened().runDao()
        val run = dao.runById(runId)
        assertEquals("Canal loop", run?.title)
        assertEquals(5000.0, run?.distanceMeters ?: 0.0, 0.0)
        assertEquals(3, dao.pointsFor(runId).size)
        assertEquals(1, dao.effortsFor(runId).size)
        assertEquals(5, countSplits(runId))
    }

    @Test
    fun `the coach comes back with the runs`() = runBlocking {
        insertFullRun(distance = 5000.0, title = "Canal loop")
        coach = CoachSettings(
            targetDistanceMeters = 21_097,
            targetDateEpochDay = 20_500,
            dayOrders = mapOf(20_353L to listOf(3, 0, 1, 2, 4, 5, 6)),
            nudgeOffPace = true,
            baseline = CoachBaseline(
                runsPerWeek = 4,
                weeklyMeters = 40_000.0,
                longestRunMeters = 15_000.0,
                raceDistanceMeters = 10_000,
                raceDurationMs = 45 * 60_000L,
                raceDateEpochDay = 20_300,
                recordedOnEpochDay = 20_350,
            ),
            baselineAsked = true,
        )

        val (uri, _) = backUpToFile()
        // A new phone: the runs are gone and so is everything the coach knew.
        wipeLiveDatabase()
        coach = CoachSettings()

        assertEquals(RestoreResult.Restored(1), backup.restoreFrom(uri))

        assertEquals(21_097, coach.targetDistanceMeters)
        assertEquals(20_500L, coach.targetDateEpochDay)
        assertEquals(listOf(3, 0, 1, 2, 4, 5, 6), coach.dayOrders[20_353L])
        assertTrue(coach.nudgeOffPace)
        assertTrue(coach.baselineAsked)
        assertEquals(4, coach.baseline?.runsPerWeek)
        assertEquals(40_000.0, coach.baseline?.weeklyMeters ?: 0.0, 0.0)
        assertEquals(10_000, coach.baseline?.raceDistanceMeters)
        assertEquals(45 * 60_000L, coach.baseline?.raceDurationMs)
        assertEquals(20_350L, coach.baseline?.recordedOnEpochDay)
    }

    @Test
    fun `a backup from before the coach was carried leaves this phone's coach alone`() =
        runBlocking {
            insertFullRun(distance = 5000.0, title = "Canal loop")
            val (uri, _) = backUpToFile()
            // The table this build writes, removed: the shape of every older backup.
            SQLiteDatabase.openDatabase(fileBehind(uri).path, null, SQLiteDatabase.OPEN_READWRITE)
                .use { it.execSQL("DROP TABLE coach_prefs") }

            coach = CoachSettings(targetDistanceMeters = 5_000, targetDateEpochDay = 20_600)
            assertEquals(RestoreResult.Restored(1), backup.restoreFrom(uri))

            assertEquals(5_000, coach.targetDistanceMeters)
        }

    /**
     * The regression that justifies `VACUUM INTO`. Room is in WAL mode, so a freshly
     * written run is still in the `-wal` and not yet in `snail-run.db`. A backup taken
     * without a checkpoint must contain it anyway.
     */
    @Test
    fun `a run still sitting in the write-ahead log is in the backup`() = runBlocking {
        insertFullRun(distance = 4200.0, title = "In the WAL")

        val wal = File("${context.getDatabasePath(SnailDatabase.FILE_NAME).path}-wal")
        assertTrue("the test needs an unchecked-pointed WAL to mean anything", wal.length() > 0)

        val (_, result) = backUpToFile()

        assertEquals(1, (result as BackupResult.Written).runCount)
    }

    /**
     * The fallback in [DatabaseBackup.snapshot] would hide a missing `VACUUM INTO` by
     * quietly checkpointing instead, and every other test here would still pass. So the
     * statement is asserted directly: this is the path production takes.
     */
    @Test
    fun `VACUUM INTO is the path actually taken`() {
        val db = live.openHelper.writableDatabase
        val version = db.query("SELECT sqlite_version()")
            .use { if (it.moveToFirst()) it.getString(0) else "" }
        val (major, minor) = version.split(".").take(2).map(String::toInt)
        assertTrue("SQLite $version predates VACUUM INTO", major > 3 || (major == 3 && minor >= 27))

        val target = File.createTempFile("vacuum", ".db").also { scratch += it; it.delete() }
        db.execSQL("VACUUM INTO ?", arrayOf<Any>(target.absolutePath))

        assertTrue(target.length() > 0)
        // One file, no sidecars: the property the whole approach rests on.
        assertFalse(File("${target.path}-wal").exists())
    }

    @Test
    fun `a backup taken at schema version 1 restores and migrates`() = runBlocking {
        insertFullRun(distance = 1000.0, title = "Will be replaced")

        migrations.createDatabase(V1_DB_NAME, 1).use { db -> db.execSQL(VERSION_1_RUN) }
        val old = InstrumentationRegistry.getInstrumentation().targetContext
            .getDatabasePath(V1_DB_NAME)
            .also { scratch += it }
        stamp(old)
        assertEquals(1, userVersionOf(old))

        val result = backup.restoreFrom(uriFor(old))

        assertEquals(RestoreResult.Restored(1), result)
        val run = reopened().runDao().runById(7)
        assertEquals("From version one", run?.title)
        // Migrated, and stamped for re-derivation rather than keeping figures the
        // current filter cannot reproduce.
        assertEquals(0, run?.smootherVersion)
    }

    // ---- refusals, none of which may touch the live database -------------------------

    @Test
    fun `somebody else's SQLite file is refused and the runs are left alone`() = runBlocking {
        val runId = insertFullRun(distance = 8000.0, title = "Keep me")

        val foreign = File.createTempFile("foreign", ".db").also { scratch += it; it.delete() }
        SQLiteDatabase.openOrCreateDatabase(foreign, null).use { db ->
            db.execSQL("CREATE TABLE runs (id INTEGER PRIMARY KEY, note TEXT)")
            db.execSQL("INSERT INTO runs VALUES (1, 'not ours')")
        }

        assertEquals(RestoreResult.NotABackupFile, backup.restoreFrom(uriFor(foreign)))
        assertEquals("Keep me", live.runDao().runById(runId)?.title)
    }

    @Test
    fun `a file that is not a database at all is refused rather than thrown`() = runBlocking {
        val junk = File.createTempFile("junk", ".bin").also { scratch += it }
        junk.writeText("this is not a database")

        assertEquals(RestoreResult.NotABackupFile, backup.restoreFrom(uriFor(junk)))
    }

    @Test
    fun `a Room database without our stamp is refused`() = runBlocking {
        val (file, _) = backUpToFile()
        val unstamped = fileBehind(file)
        SQLiteDatabase.openDatabase(unstamped.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("PRAGMA application_id = 0")
        }

        assertEquals(RestoreResult.NotABackupFile, backup.restoreFrom(file))
    }

    @Test
    fun `a backup from a newer schema is refused, not opened`() = runBlocking {
        val runId = insertFullRun(distance = 3000.0, title = "Still here")
        val (uri, _) = backUpToFile()
        SQLiteDatabase.openDatabase(fileBehind(uri).path, null, SQLiteDatabase.OPEN_READWRITE)
            .use { it.execSQL("PRAGMA user_version = 99") }

        val result = backup.restoreFrom(uri)

        assertEquals(RestoreResult.FromNewerVersion(99, SnailDatabase.SCHEMA_VERSION), result)
        assertEquals("Still here", live.runDao().runById(runId)?.title)
    }

    @Test
    fun `restoring is refused while a run is being recorded`() = runBlocking {
        val (uri, _) = backUpToFile()
        recording = true

        assertEquals(RestoreResult.RunInProgress, backup.restoreFrom(uri))
        // Nothing was even staged.
        assertFalse(File(context.cacheDir, "restore.part").exists())
    }

    /**
     * The atomicity contract. The file passes every cheap check and then fails when Room
     * opens it, which is the only place a schema lie can be caught — and by then the live
     * file has already been replaced. The old runs must come back.
     */
    @Test
    fun `a restore that fails on open puts the original runs back`() = runBlocking {
        val runId = insertFullRun(distance = 9000.0, title = "Survivor")
        val (uri, _) = backUpToFile()

        // A plausible file: our stamp, our tables, our version — and an identity hash Room
        // will reject, so it throws at exactly the point rollback exists for.
        SQLiteDatabase.openDatabase(fileBehind(uri).path, null, SQLiteDatabase.OPEN_READWRITE)
            .use { it.execSQL("UPDATE room_master_table SET identity_hash = 'nonsense'") }

        val result = backup.restoreFrom(uri)

        assertTrue("expected a failure, got $result", result is RestoreResult.Failed)
        assertTrue((result as RestoreResult.Failed).restartNeeded)
        assertEquals("Survivor", reopened().runDao().runById(runId)?.title)
    }

    // ---- helpers ---------------------------------------------------------------------

    private suspend fun insertFullRun(distance: Double, title: String): Long {
        val dao = live.runDao()
        val id = dao.insertRun(
            RunEntity(
                startedAtEpochMs = 1_700_000_000_000,
                endedAtEpochMs = 1_700_000_600_000,
                timeZoneId = "Europe/Paris",
                localDate = "2023-11-14",
                distanceMeters = distance,
                elapsedTimeMs = 600_000,
                movingTimeMs = 600_000,
                avgPaceSecPerKm = 200.0,
                elevationGainM = 12.0,
                elevationLossM = 8.0,
                pointCount = 3,
                minLat = 48.85,
                maxLat = 48.87,
                minLon = 2.35,
                maxLon = 2.36,
                title = title,
                note = null,
                source = "RECORDED",
                status = "COMPLETE",
            )
        )
        dao.insertPoints(
            (0 until 3).map { seq ->
                TrackPointEntity(
                    runId = id,
                    seq = seq,
                    segment = 0,
                    timestampMs = 1_700_000_000_000 + seq * 1_000L,
                    lat = 48.85 + seq * 0.001,
                    lon = 2.35 + seq * 0.001,
                    elevationM = 35.0,
                    accuracyM = 4f,
                    speedMps = 3f,
                    cumulativeDistanceM = seq * 100.0,
                )
            }
        )
        dao.insertSplits(
            (0 until 5).map { index ->
                SplitEntity(
                    runId = id,
                    splitIndex = index,
                    distanceMeters = 1000.0,
                    durationMs = 300_000,
                    paceSecPerKm = 300.0,
                    elevationGainM = 2.0,
                    isPartial = false,
                )
            }
        )
        dao.insertBestEfforts(
            listOf(
                BestEffortEntity(
                    runId = id,
                    distanceMeters = 1000,
                    durationMs = 290_000,
                    startSeq = 0,
                    endSeq = 2,
                    startOffsetMs = 0,
                )
            )
        )
        return id
    }

    /** Backs up through the content resolver, as the picker would, and returns both ends. */
    private suspend fun backUpToFile(): Pair<Uri, BackupResult> {
        val destination = File.createTempFile("backup", ".db").also { scratch += it }
        val uri = uriFor(destination)
        return uri to backup.backupTo(uri)
    }

    /**
     * Robolectric's resolver serves what the test registers. Suppliers rather than single
     * streams, because the code under test opens the same URI more than once.
     */
    private fun uriFor(file: File): Uri {
        val uri = Uri.parse("content://io.snailrun.test/${file.name}")
        shadowOf(context.contentResolver).apply {
            registerInputStreamSupplier(uri) { file.inputStream() }
            registerOutputStreamSupplier(uri) { file.outputStream() }
        }
        registered[uri] = file
        return uri
    }

    private val registered = mutableMapOf<Uri, File>()

    private fun fileBehind(uri: Uri): File = registered.getValue(uri)

    private fun stamp(file: File) =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("PRAGMA application_id = ${DatabaseBackup.APPLICATION_ID}")
        }

    private fun userVersionOf(file: File): Int =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA user_version", null).use {
                if (it.moveToFirst()) it.getInt(0) else -1
            }
        }

    private fun wipeLiveDatabase() {
        live.close()
        val db = context.getDatabasePath(SnailDatabase.FILE_NAME)
        listOf(db, File("${db.path}-wal"), File("${db.path}-shm")).forEach { it.delete() }
        live = SnailDatabase.build(context)
    }

    /** The app restarts after a restore; this is the test's stand-in for that. */
    private fun reopened(): SnailDatabase {
        runCatching { live.close() }
        live = SnailDatabase.build(context)
        return live
    }

    private fun countSplits(runId: Long): Int =
        live.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM splits WHERE runId = $runId")
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private companion object {
        const val V1_DB_NAME = "v1-backup.db"

        val VERSION_1_RUN = """
            INSERT INTO runs (
                id, activityType, startedAtEpochMs, endedAtEpochMs, timeZoneId,
                localDate, distanceMeters, elapsedTimeMs, movingTimeMs,
                avgPaceSecPerKm, elevationGainM, elevationLossM, pointCount,
                minLat, maxLat, minLon, maxLon, title, note, source, status,
                distanceMilestonesAnnounced, lastTimeAnnouncedActiveMs, gpxExportedUri
            ) VALUES (
                7, 'RUN', 1700000000000, 1700000600000, 'Europe/Paris',
                '2023-11-14', 6000.0, 600000, 600000,
                200.0, 12.0, 8.0, 601,
                48.85, 48.87, 2.35, 2.36, 'From version one', NULL, 'RECORDED', 'COMPLETE',
                3, 0, NULL
            )
        """.trimIndent()
    }
}

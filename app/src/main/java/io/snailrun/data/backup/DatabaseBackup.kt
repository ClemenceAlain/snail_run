package io.snailrun.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import io.snailrun.data.db.SnailDatabase
import io.snailrun.data.prefs.CoachSettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface BackupResult {
    data class Written(val uri: Uri, val runCount: Int, val sizeBytes: Long) : BackupResult
    data class Failed(val error: Throwable) : BackupResult
}

sealed interface RestoreResult {
    /** The caller must restart the app: every handle on the old database is now stale. */
    data class Restored(val runCount: Int) : RestoreResult

    data object NotABackupFile : RestoreResult
    data class FromNewerVersion(val found: Int, val supported: Int) : RestoreResult
    data object RunInProgress : RestoreResult

    /**
     * [restartNeeded] distinguishes the two failures the user has to act on differently.
     * False means nothing was touched and they can simply try another file. True means
     * the live database was already closed, the old runs have been put back, and the app
     * has to be restarted before anything reads them again.
     */
    data class Failed(val error: Throwable, val restartNeeded: Boolean) : RestoreResult
}

/**
 * Copies every run out to a file the user chooses, and puts one back.
 *
 * This is the only way a run can survive the phone. The app has no `INTERNET` permission,
 * `allowBackup` is off and the data extraction rules exclude everything, so nothing leaves
 * the device unless the user asks — and until this existed, nothing could.
 *
 * The file written is the database itself, not an interchange format. A run is a track of
 * a few thousand fixes plus everything derived from it, and the only thing guaranteed to
 * read all of that back exactly is the schema that wrote it. GPX is for other programs;
 * this is for this one.
 */
class DatabaseBackup(
    private val context: Context,
    private val database: () -> SnailDatabase,
    private val isRecording: () -> Boolean,
    /**
     * The coach's state, which is not in the database but is about the runner just as
     * much as the runs are. See [CoachBackup]. Defaulted away so the database half of
     * this class can still be exercised on its own.
     */
    private val readCoach: suspend () -> CoachSettings = { CoachSettings() },
    private val writeCoach: suspend (CoachSettings) -> Unit = {},
) {

    suspend fun backupTo(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, STAGING_BACKUP)
        try {
            runCatching {
                snapshot(staging)
                stamp(staging)
                // After the snapshot, because the snapshot is a copy of the database and
                // this is the one thing in the file that never lived there.
                val coach = readCoach()
                openReadWrite(staging).use { CoachBackup.write(it, coach) }
                val runCount = readRunCount(staging)
                val size = staging.length()

                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    staging.inputStream().use { input -> input.copyTo(out) }
                } ?: error("could not open the file you picked for writing")

                BackupResult.Written(uri, runCount, size)
            }.getOrElse { BackupResult.Failed(it) }
        } finally {
            staging.delete()
        }
    }

    /**
     * Replaces every run on the phone with the ones in [uri].
     *
     * Two phases, and the boundary between them is the whole design. Everything that can
     * say no — reading the file, recognising it, the schema version — happens before the
     * live database is touched at all. Only once the file is known to be ours is anything
     * replaced, and a copy of what is being replaced is already on disk by then.
     */
    suspend fun restoreFrom(uri: Uri): RestoreResult = withContext(Dispatchers.IO) {
        // Never while a run is live. The run in progress is not in the backup, so stopping
        // it to restore a file would delete data the user never asked to lose — the one
        // thing this class exists to prevent.
        if (isRecording()) return@withContext RestoreResult.RunInProgress

        val candidate = File(context.cacheDir, STAGING_RESTORE)
        val rollback = File(context.cacheDir, STAGING_ROLLBACK)

        try {
            // Phase one. Nothing here can leave the app worse than it was.
            val staged = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    candidate.outputStream().use { out -> input.copyTo(out) }
                } ?: error("could not read the file you picked")
            }
            staged.exceptionOrNull()?.let {
                return@withContext RestoreResult.Failed(it, restartNeeded = false)
            }

            when (val check = inspect(candidate)) {
                Inspection.NotOurs -> return@withContext RestoreResult.NotABackupFile
                is Inspection.TooNew -> return@withContext RestoreResult.FromNewerVersion(
                    found = check.found,
                    supported = SnailDatabase.SCHEMA_VERSION,
                )
                Inspection.Usable -> Unit
            }

            // Read before anything is replaced, like everything else that can fail
            // safely. Null means the file predates the coach being backed up at all, and
            // the coach on this phone is then left exactly as it is.
            val coach = runCatching { openReadOnly(candidate).use { CoachBackup.read(it) } }
                .getOrNull()

            // A copy of what is about to be overwritten, taken while the database is still
            // open and consistent, so a failure halfway leaves the user with their old runs
            // rather than with neither set.
            runCatching { snapshot(rollback) }.exceptionOrNull()?.let {
                return@withContext RestoreResult.Failed(it, restartNeeded = false)
            }

            // Phase two. Past this line the app must restart whatever happens.
            val live = context.getDatabasePath(SnailDatabase.FILE_NAME)
            runCatching {
                database().close()
                candidate.copyTo(live, overwrite = true)
                // Sidecars belong to the database that wrote them. Left against a
                // different file they are not stale, they are corruption.
                clearSidecars(live)
                openAndCount()
            }.fold(
                onSuccess = { runCount ->
                    // After the runs, and never instead of them: a coach put back beside
                    // a database that failed to open would plan against a history that
                    // is not there.
                    coach?.let { runCatching { writeCoach(it) } }
                    RestoreResult.Restored(runCount)
                },
                onFailure = { broken ->
                    runCatching {
                        rollback.copyTo(live, overwrite = true)
                        clearSidecars(live)
                    }
                    RestoreResult.Failed(broken, restartNeeded = true)
                },
            )
        } finally {
            candidate.delete()
            rollback.delete()
        }
    }

    /**
     * One consistent file holding everything the database holds.
     *
     * Not a file copy. The database runs in write-ahead-logging mode, so `snail-run.db`
     * on its own is missing every write still sitting in the `-wal` — and a copy taken
     * mid-write can be torn. `VACUUM INTO` writes a single compacted file from a
     * consistent read, which is exactly the guarantee a backup needs, and it needs no
     * exclusive access, so a run can keep recording through it.
     *
     * Falling back rather than checking a version string: the statement arrived in SQLite
     * 3.27 and every API 31 device ships 3.32, but the honest test of whether a database
     * understands a statement is to hand it the statement.
     */
    private fun snapshot(destination: File) {
        // VACUUM INTO refuses to overwrite, so the path must be free first.
        destination.delete()
        val db = database().openHelper.writableDatabase
        runCatching { db.execSQL("VACUUM INTO ?", arrayOf<Any>(destination.absolutePath)) }
            .getOrElse {
                destination.delete()
                // Checkpoint first, or the copy misses whatever the -wal still holds.
                db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                context.getDatabasePath(SnailDatabase.FILE_NAME)
                    .copyTo(destination, overwrite = true)
            }
    }

    /** Marks the file as ours, so a restore can tell it from any other SQLite database. */
    private fun stamp(file: File) = openReadWrite(file).use {
        it.execSQL("PRAGMA application_id = $APPLICATION_ID")
    }

    private sealed interface Inspection {
        data object NotOurs : Inspection
        data class TooNew(val found: Int) : Inspection
        data object Usable : Inspection
    }

    /**
     * Decides whether a file can be trusted before anything is replaced.
     *
     * Both the stamp and the table set, not either. Four magic bytes are four coincidences
     * away, and the table names alone would accept anybody's database that happened to
     * have a `runs` table. Together they also cleanly reject an MBTiles file, which is the
     * confusion most likely to actually happen — the user already picks one of those.
     *
     * A backup from an older schema is fine: Room migrates it when it opens. One from a
     * newer schema is refused, because an older app cannot know what a later version added,
     * and opening it would be the one way to lose data that a backup is meant to prevent.
     */
    private fun inspect(file: File): Inspection = runCatching {
        openReadOnly(file).use { db ->
            val applicationId = db.rawQuery("PRAGMA application_id", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
            val tables = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN " +
                    "('runs', 'track_points', 'splits', 'best_efforts', 'room_master_table')",
                null,
            ).use { it.count }

            if (applicationId != APPLICATION_ID || tables != EXPECTED_TABLES) {
                return@use Inspection.NotOurs
            }

            val version = db.rawQuery("PRAGMA user_version", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
            if (version > SnailDatabase.SCHEMA_VERSION) {
                Inspection.TooNew(version)
            } else {
                Inspection.Usable
            }
        }
    }.getOrDefault(Inspection.NotOurs)

    private fun readRunCount(file: File): Int = runCatching {
        openReadOnly(file).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM runs", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        }
    }.getOrDefault(0)

    /**
     * Opens the restored file through Room, which runs any migration it needs and throws
     * if the schema is not what this build expects. Doing it here rather than on next
     * launch is what makes the rollback possible: a file that cannot be opened is found
     * out while the old one is still sitting in the cache.
     */
    private fun openAndCount(): Int {
        val fresh = SnailDatabase.build(context)
        return try {
            fresh.openHelper.readableDatabase
                .query("SELECT COUNT(*) FROM runs")
                .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } finally {
            fresh.close()
        }
    }

    private fun clearSidecars(live: File) {
        File("${live.absolutePath}-wal").delete()
        File("${live.absolutePath}-shm").delete()
    }

    private fun openReadOnly(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    private fun openReadWrite(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)

    companion object {
        const val MIME_TYPE = "application/octet-stream"

        /** "SNLR". Any SQLite file can be handed to the picker; only ours carries this. */
        const val APPLICATION_ID = 0x534E4C52

        private const val EXPECTED_TABLES = 5

        private const val STAGING_BACKUP = "backup.part"
        private const val STAGING_RESTORE = "restore.part"
        private const val STAGING_ROLLBACK = "rollback.part"

        /** `snail-run-backup-2026-09-18-1145.db`, matching the GPX naming. */
        fun suggestedFileName(stamp: String) = "snail-run-backup-$stamp.db"
    }
}

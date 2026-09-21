package io.snailrun.data.backup

import android.database.sqlite.SQLiteDatabase
import io.snailrun.data.prefs.CoachSettings
import io.snailrun.data.prefs.coachSettingsFrom
import io.snailrun.data.prefs.toBackupRows

/**
 * The coach's own state, carried inside the backup file.
 *
 * Settings are not in a backup, and deliberately: the export folder and the map file are
 * grants Android ties to this installation, so restoring them would restore two things
 * that no longer work. The coach is not that. A race date, a week somebody rearranged
 * around their job, and what they said they had been running before the app existed are
 * statements about the runner, exactly like the runs beside them — and losing them to a
 * new phone means the first month on it is planned as though they had never run.
 *
 * It rides in a small key-value table written into the backup file after the database is
 * snapshotted. A table rather than a second file, so there is still exactly one thing to
 * keep safe; outside the Room schema, so it needs no migration and no schema version of
 * its own, and an older build restoring a newer backup simply does not look for it.
 */
internal object CoachBackup {

    const val TABLE = "coach_prefs"

    fun write(db: SQLiteDatabase, coach: CoachSettings) {
        // Dropped and rewritten rather than updated: the file was just vacuumed out of
        // the live database, so whatever is in there came from an earlier restore and
        // describes somebody's older week.
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        db.execSQL("CREATE TABLE $TABLE (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        coach.toBackupRows().forEach { (key, value) ->
            db.execSQL("INSERT INTO $TABLE (key, value) VALUES (?, ?)", arrayOf<Any>(key, value))
        }
    }

    /**
     * What the file says about the coach, or null if it says nothing.
     *
     * Null is the ordinary answer for a backup taken before this existed, and it must
     * stay distinguishable from an empty one: a file with no coach table means "leave
     * what is on this phone alone", and a file with an empty one means "the coach was
     * set to nothing when this was written".
     */
    fun read(db: SQLiteDatabase): CoachSettings? = runCatching {
        val rows = mutableMapOf<String, String>()
        db.rawQuery("SELECT key, value FROM $TABLE", null).use { cursor ->
            while (cursor.moveToNext()) rows[cursor.getString(0)] = cursor.getString(1)
        }
        coachSettingsFrom(rows)
    }.getOrNull()
}

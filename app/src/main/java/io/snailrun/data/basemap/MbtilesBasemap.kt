package io.snailrun.data.basemap

import android.database.sqlite.SQLiteDatabase
import io.snailrun.domain.geo.WebMercator
import java.io.File

/** What a tile file says about itself. Every field is optional in the format. */
data class BasemapInfo(
    val name: String?,
    val format: String?,
    val minZoom: Int,
    val maxZoom: Int,
    val tileCount: Long,
    val sizeBytes: Long,
)

/**
 * A raster basemap read from an MBTiles file on the phone.
 *
 * MBTiles is a SQLite database of tile blobs, which is what makes it the only sensible
 * format here: the app has no `INTERNET` permission and never will, so tiles have to
 * arrive as a file the user put there, and SQLite is already linked in.
 *
 * Opened read-only and never written to. A corrupt or unexpected file is reported as a
 * failure to load rather than thrown: the trace still draws without a map under it, and
 * a run should never be unviewable because a map file went bad.
 */
class MbtilesBasemap private constructor(
    private val db: SQLiteDatabase,
    val info: BasemapInfo,
) {

    /**
     * One tile's encoded bytes, or null where the file has no tile — a hole in coverage
     * is normal at the edges of an extract and is drawn as blank, not as an error.
     */
    fun tile(zoom: Int, x: Int, y: Int): ByteArray? {
        if (zoom < info.minZoom || zoom > info.maxZoom) return null
        return runCatching {
            db.rawQuery(
                "SELECT tile_data FROM tiles " +
                    "WHERE zoom_level = ? AND tile_column = ? AND tile_row = ? LIMIT 1",
                arrayOf("$zoom", "$x", "${WebMercator.toTmsRow(y, zoom)}"),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null }
        }.getOrNull()
    }

    fun close() = runCatching { db.close() }.let { }

    companion object {

        /** Zoom levels a running map is worth having; used when the file does not say. */
        private const val FALLBACK_MIN_ZOOM = 0
        private const val FALLBACK_MAX_ZOOM = 16

        fun open(file: File): Result<MbtilesBasemap> = runCatching {
            if (!file.isFile) error("no basemap file at ${file.name}")
            val db = SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            try {
                // SQLite will happily open a file that is not a database until asked to
                // read one, so the check is a query, not the open call succeeding.
                require(hasTilesTable(db)) { "${file.name} is not an MBTiles map" }
                MbtilesBasemap(db, readInfo(db, file))
            } catch (error: Throwable) {
                db.close()
                throw error
            }
        }

        /** Cheap enough to run on the picker's result, before anything is copied. */
        fun looksLikeMbtiles(file: File): Boolean = runCatching {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                .use { hasTilesTable(it) }
        }.getOrDefault(false)

        private fun hasTilesTable(db: SQLiteDatabase): Boolean = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type IN ('table','view') AND name = 'tiles'",
            null,
        ).use { it.count > 0 }

        private fun readInfo(db: SQLiteDatabase, file: File): BasemapInfo {
            val metadata = runCatching {
                db.rawQuery("SELECT name, value FROM metadata", null).use { cursor ->
                    buildMap {
                        while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
                    }
                }
            }.getOrDefault(emptyMap())

            // Trusting the declared zoom range would show blank tiles whenever it lies,
            // which it often does in hand-cut extracts. The tiles themselves cannot.
            val (minZoom, maxZoom) = runCatching {
                db.rawQuery("SELECT MIN(zoom_level), MAX(zoom_level) FROM tiles", null).use {
                    if (it.moveToFirst() && !it.isNull(0)) it.getInt(0) to it.getInt(1)
                    else FALLBACK_MIN_ZOOM to FALLBACK_MAX_ZOOM
                }
            }.getOrDefault(FALLBACK_MIN_ZOOM to FALLBACK_MAX_ZOOM)

            val tileCount = runCatching {
                db.rawQuery("SELECT COUNT(*) FROM tiles", null).use {
                    if (it.moveToFirst()) it.getLong(0) else 0L
                }
            }.getOrDefault(0L)

            return BasemapInfo(
                name = metadata["name"],
                format = metadata["format"],
                minZoom = minZoom,
                maxZoom = maxZoom,
                tileCount = tileCount,
                sizeBytes = file.length(),
            )
        }
    }
}

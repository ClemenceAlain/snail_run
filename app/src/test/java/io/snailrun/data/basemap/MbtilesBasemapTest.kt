package io.snailrun.data.basemap

import android.database.sqlite.SQLiteDatabase
import io.snailrun.domain.geo.WebMercator
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Read against a real MBTiles file, built here rather than mocked: the whole point of
 * the format is that it is somebody else's SQLite, and a fake would agree with whatever
 * the reader happened to do.
 */
@RunWith(RobolectricTestRunner::class)
class MbtilesBasemapTest {

    private lateinit var file: File

    @Before
    fun setUp() {
        file = File.createTempFile("basemap", ".mbtiles").also { it.delete() }
    }

    @After
    fun tearDown() {
        file.delete()
    }

    /** Writes the schema an MBTiles file has, with [tiles] at (zoom, column, tmsRow). */
    private fun writeMbtiles(tiles: List<Triple<Int, Int, Int>>, name: String = "Paris") {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
            db.execSQL(
                "CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, " +
                    "tile_row INTEGER, tile_data BLOB)"
            )
            db.execSQL("INSERT INTO metadata VALUES ('name', ?)", arrayOf(name))
            db.execSQL("INSERT INTO metadata VALUES ('format', 'png')")
            tiles.forEach { (z, x, row) ->
                db.execSQL(
                    "INSERT INTO tiles VALUES (?, ?, ?, ?)",
                    arrayOf(z, x, row, byteArrayOf(z.toByte(), x.toByte(), row.toByte())),
                )
            }
        }
    }

    @Test
    fun `reads a tile through the TMS row flip`() {
        val zoom = 14
        val y = 5636
        val tmsRow = WebMercator.toTmsRow(y, zoom)
        writeMbtiles(listOf(Triple(zoom, 8299, tmsRow)))

        val basemap = MbtilesBasemap.open(file).getOrThrow()

        // Asked for by slippy-map row, found at the TMS row it was stored under.
        assertNotNull(basemap.tile(zoom, 8299, y))
        assertNull("the unflipped row must not match", basemap.tile(zoom, 8299, tmsRow))
        basemap.close()
    }

    @Test
    fun `a hole in coverage is null, not a failure`() {
        writeMbtiles(listOf(Triple(14, 8299, WebMercator.toTmsRow(5636, 14))))

        val basemap = MbtilesBasemap.open(file).getOrThrow()

        assertNull(basemap.tile(14, 9999, 5636))
        // And the file is still usable afterwards.
        assertNotNull(basemap.tile(14, 8299, 5636))
        basemap.close()
    }

    @Test
    fun `the zoom range comes from the tiles, not from what the file claims`() {
        writeMbtiles(
            listOf(
                Triple(12, 2074, WebMercator.toTmsRow(1409, 12)),
                Triple(13, 4149, WebMercator.toTmsRow(2818, 13)),
                Triple(14, 8299, WebMercator.toTmsRow(5636, 14)),
            )
        )
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            // Hand-cut extracts routinely lie about this.
            it.execSQL("INSERT INTO metadata VALUES ('minzoom', '0')")
            it.execSQL("INSERT INTO metadata VALUES ('maxzoom', '19')")
        }

        val basemap = MbtilesBasemap.open(file).getOrThrow()

        assertEquals(12, basemap.info.minZoom)
        assertEquals(14, basemap.info.maxZoom)
        assertEquals(3L, basemap.info.tileCount)
        assertEquals("Paris", basemap.info.name)
        basemap.close()
    }

    @Test
    fun `a zoom the file does not hold is refused without touching the database`() {
        writeMbtiles(listOf(Triple(14, 8299, WebMercator.toTmsRow(5636, 14))))

        val basemap = MbtilesBasemap.open(file).getOrThrow()

        assertNull(basemap.tile(18, 8299, 5636))
        basemap.close()
    }

    @Test
    fun `a file that is not MBTiles is recognised before it replaces anything`() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE notes (body TEXT)")
        }

        assertFalse(MbtilesBasemap.looksLikeMbtiles(file))
    }

    @Test
    fun `a file that is not a database at all is refused rather than thrown`() {
        file.writeText("this is not a database")

        assertFalse(MbtilesBasemap.looksLikeMbtiles(file))
        assertTrue(MbtilesBasemap.open(file).isFailure)
    }

    @Test
    fun `a missing file is a failure, not a crash`() {
        assertTrue(MbtilesBasemap.open(File("/nowhere/at/all.mbtiles")).isFailure)
    }
}

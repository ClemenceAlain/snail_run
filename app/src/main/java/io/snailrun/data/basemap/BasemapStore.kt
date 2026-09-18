package io.snailrun.data.basemap

import android.content.Context
import android.net.Uri
import androidx.collection.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import io.snailrun.data.prefs.SettingsRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The map in use, and whether it is the one the app shipped with. */
data class ActiveBasemap(val info: BasemapInfo, val bundled: Boolean)

sealed interface BasemapInstall {
    data class Installed(val info: BasemapInfo) : BasemapInstall
    data object NotAnMbtilesFile : BasemapInstall
    data class Failed(val error: Throwable) : BasemapInstall
}

/**
 * Owns the one basemap file the phone has.
 *
 * The file is copied into the app's own storage rather than read where the user left it.
 * SQLite needs a path and a content URI is not one, and a picked file can be revoked,
 * moved or deleted out from under a run at any moment. One copy, once, and the map is
 * the app's thereafter.
 */
class BasemapStore(
    private val context: Context,
    private val settings: SettingsRepository,
) {

    private val file: File get() = File(context.filesDir, FILE_NAME)

    /**
     * The demo map, unpacked from the APK.
     *
     * SQLite needs a path and an asset is not one, so it is copied out once. It is small
     * — a kilometre of ground — and copying it beats the alternatives: reading an asset
     * into memory to hand SQLite a file it would have written anyway.
     */
    private val bundledFile: File get() = File(context.filesDir, BUNDLED_FILE_NAME)

    private var opened: MbtilesBasemap? = null

    /** True when [opened] is the bundled map rather than one the user chose. */
    private var openedIsBundled: Boolean = false

    /** Decoded tiles, keyed by z/x/y, held to a budget in bytes. */
    private val cache = object : LruCache<String, ImageBitmap>(TILE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.width * value.height * 4
    }

    val isInstalled: Boolean get() = file.isFile

    suspend fun install(uri: Uri): BasemapInstall = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "$FILE_NAME.part")
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            } ?: error("could not read the file you picked")

            // Checked before it replaces a working map: picking the wrong file should
            // cost nothing.
            if (!MbtilesBasemap.looksLikeMbtiles(staging)) {
                staging.delete()
                return@withContext BasemapInstall.NotAnMbtilesFile
            }

            close()
            staging.copyTo(file, overwrite = true)
            staging.delete()

            val info = MbtilesBasemap.open(file).getOrThrow().also { opened = it }.info
            settings.setBasemapUri(file.absolutePath)
            BasemapInstall.Installed(info)
        }.getOrElse { error ->
            staging.delete()
            BasemapInstall.Failed(error)
        }
    }

    suspend fun remove() = withContext(Dispatchers.IO) {
        close()
        file.delete()
        settings.setBasemapUri(null)
    }

    suspend fun info(): BasemapInfo? = withContext(Dispatchers.IO) { basemap()?.info }

    /**
     * The map a run should be drawn on, and where it came from.
     *
     * A map the user picked always wins. The bundled one is a floor under that, not a
     * competitor: it covers the demo loop and nothing else, so anywhere real it has
     * nothing to offer and the caller uses its coverage to say so.
     */
    suspend fun active(): ActiveBasemap? = withContext(Dispatchers.IO) {
        val map = basemap() ?: return@withContext null
        ActiveBasemap(info = map.info, bundled = openedIsBundled)
    }

    /**
     * One tile, decoded and cached. Null where the file has no tile there, which is the
     * normal state at the edge of an extract.
     */
    suspend fun tile(zoom: Int, x: Int, y: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "$zoom/$x/$y"
        cache.get(key)?.let { return@withContext it }

        val bytes = basemap()?.tile(zoom, x, y) ?: return@withContext null
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull() ?: return@withContext null

        cache.put(key, bitmap)
        bitmap
    }

    private fun basemap(): MbtilesBasemap? {
        opened?.let { return it }
        if (file.isFile) {
            MbtilesBasemap.open(file).getOrNull()?.let {
                opened = it
                openedIsBundled = false
                return it
            }
        }
        return openBundled()?.also {
            opened = it
            openedIsBundled = true
        }
    }

    /**
     * The demo map, unpacked on first use.
     *
     * Re-unpacked whenever the copy on disk is a different size from the asset, which is
     * how an app update gets the new map: comparing sizes is coarse, but the only thing
     * it can miss is a redrawn map that came out to the same byte count, and the cost of
     * missing it is a slightly stale demo backdrop.
     */
    private fun openBundled(): MbtilesBasemap? = runCatching {
        val assetSize = context.assets.openFd(BUNDLED_ASSET).use { it.length }
        if (!bundledFile.isFile || bundledFile.length() != assetSize) {
            context.assets.open(BUNDLED_ASSET).use { input ->
                bundledFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        MbtilesBasemap.open(bundledFile).getOrThrow()
    }.getOrNull()

    private fun close() {
        opened?.close()
        opened = null
        openedIsBundled = false
        cache.evictAll()
    }

    private companion object {
        const val FILE_NAME = "basemap.mbtiles"
        const val BUNDLED_ASSET = "demo.mbtiles"
        const val BUNDLED_FILE_NAME = "demo-basemap.mbtiles"

        /**
         * Budget in bytes rather than in tiles, because a tile is 256 KB decoded and a
         * full screen of them is fifty. Counting entries was fine for one small trace
         * and would thrash the moment a map could be panned.
         */
        const val TILE_CACHE_BYTES = 24 * 1024 * 1024
    }
}

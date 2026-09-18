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

    private var opened: MbtilesBasemap? = null

    /** Decoded tiles, keyed by z/x/y. Small: a trace needs a dozen tiles at a time. */
    private val cache = object : LruCache<String, ImageBitmap>(TILE_CACHE_ENTRIES) {}

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
        if (!file.isFile) return null
        return MbtilesBasemap.open(file).getOrNull()?.also { opened = it }
    }

    private fun close() {
        opened?.close()
        opened = null
        cache.evictAll()
    }

    private companion object {
        const val FILE_NAME = "basemap.mbtiles"
        const val TILE_CACHE_ENTRIES = 64
    }
}

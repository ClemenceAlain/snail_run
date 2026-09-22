package io.snailrun.data.export

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.snailrun.data.db.RunEntity
import io.snailrun.ui.format.UiLocale
import io.snailrun.data.prefs.SettingsRepository
import io.snailrun.data.repo.RunRepository
import io.snailrun.domain.gpx.GpxTrack
import io.snailrun.domain.gpx.GpxWriter
import io.snailrun.domain.model.TrackPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

sealed interface ExportResult {
    data class Written(val uri: Uri) : ExportResult
    data object NoFolderChosen : ExportResult
    /** The folder was deleted, or the card it lived on was removed. */
    data class FolderUnavailable(val uri: String) : ExportResult
    data class Failed(val error: Throwable) : ExportResult
}

/**
 * Writes a run out as GPX, when the user asks for one.
 *
 * Only when they ask. There used to be a third path here that wrote every finished run
 * into a folder chosen once, on a switch that defaulted to on — and its whole failure
 * surface was notifications apologising for a file nobody had requested: no folder
 * picked, folder since deleted, grant lost to a reinstall. A GPX is a copy for another
 * program, which makes it an export, which makes it something you do on purpose.
 */
class GpxExporter(
    private val context: Context,
    private val repository: RunRepository,
    private val settings: SettingsRepository,
    private val writer: GpxWriter = GpxWriter(),
) {

    /** "Save as…": one file, to a place picked in the moment. */
    suspend fun exportTo(uri: Uri, runId: Long): ExportResult = withContext(Dispatchers.IO) {
        val run = repository.observeRun(runId).first()
            ?: return@withContext ExportResult.Failed(IllegalStateException("run $runId is gone"))
        runCatching {
            writeTo(uri, run, repository.smoothedPointsFor(runId))
            repository.markExported(runId, uri.toString())
            ExportResult.Written(uri)
        }.getOrElse { ExportResult.Failed(it) }
    }

    /** Writes to the cache and returns a shareable URI, for the send-to-another-app case. */
    suspend fun exportForSharing(runId: Long): Uri? = withContext(Dispatchers.IO) {
        val run = repository.observeRun(runId).first() ?: return@withContext null
        val directory = java.io.File(context.cacheDir, "export").apply { mkdirs() }
        val file = java.io.File(directory, fileName(run))
        file.outputStream().bufferedWriter().use { out ->
            writer.write(out, track(run, repository.smoothedPointsFor(runId)))
        }
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    private fun writeTo(uri: Uri, run: RunEntity, points: List<TrackPoint>) {
        val resolver: ContentResolver = context.contentResolver
        resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { out ->
            writer.write(out, track(run, points))
        } ?: error("could not open $uri for writing")
    }

    private fun track(run: RunEntity, points: List<TrackPoint>) = GpxTrack(
        name = run.title ?: defaultTitle(run),
        startedAtEpochMs = run.startedAtEpochMs,
        points = points,
    )

    private fun fileName(run: RunEntity): String {
        val zone = runCatching { ZoneId.of(run.timeZoneId) }.getOrDefault(ZoneId.systemDefault())
        val stamp = FILE_STAMP.format(Instant.ofEpochMilli(run.startedAtEpochMs).atZone(zone))
        return "snail-run-$stamp.gpx"
    }

    private fun defaultTitle(run: RunEntity): String {
        val zone = runCatching { ZoneId.of(run.timeZoneId) }.getOrDefault(ZoneId.systemDefault())
        return TITLE_STAMP.format(Instant.ofEpochMilli(run.startedAtEpochMs).atZone(zone))
    }

    companion object {
        const val MIME_TYPE = "application/gpx+xml"

        private val FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.ROOT)
        private val TITLE_STAMP =
            DateTimeFormatter.ofPattern("EEEE d MMMM, HH:mm", UiLocale)

        /** Some pickers do not know the GPX type; the caller falls back if the sheet is empty. */
        fun shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

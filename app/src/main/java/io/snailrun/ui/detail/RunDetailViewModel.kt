package io.snailrun.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.snailrun.AppContainer
import io.snailrun.data.export.ExportResult
import io.snailrun.data.export.GpxExporter
import io.snailrun.data.db.RunEntity
import io.snailrun.data.repo.RunRepository
import io.snailrun.domain.coach.SegmentResult
import io.snailrun.domain.coach.WorkoutReview
import io.snailrun.domain.analysis.BestEffortFinder
import io.snailrun.domain.analysis.TrackProfile
import io.snailrun.domain.metrics.GapKind
import io.snailrun.domain.metrics.TrackGaps
import io.snailrun.domain.model.LatLon
import io.snailrun.domain.model.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class RunDetailViewModel(
    private val repository: RunRepository,
    private val exporter: GpxExporter,
) : ViewModel() {

    private val _state = MutableStateFlow(RunDetailUiState())
    val state: StateFlow<RunDetailUiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Kept out of the UI state: a selection is answered from the track, not redrawn from it. */
    private var points: List<TrackPoint> = emptyList()

    fun load(runId: Long) {
        viewModelScope.launch {
            combine(
                repository.observeRun(runId),
                // Corrected, like everything the app shows. The raw track stays in the
                // database for the filter to be re-run over later.
                repository.observeSmoothedPoints(runId),
            ) { run, track ->
                points = track
                RunDetailUiState(
                    run = run,
                    segments = track.toSegments(),
                    inferred = TrackGaps.inferredLegs(track),
                    profile = TrackProfile.sample(track),
                    records = emptyList(),
                )
            }.collect { state ->
                _state.value = state.copy(
                    records = recordsHeldBy(runId),
                    session = sessionReview(runId, state.run),
                )
            }
        }
    }

    /**
     * The session this run was guided through, against what was actually run in it.
     *
     * Worked out here from the stored prescription and the corrected track rather than
     * recorded as it happened, so a better position filter improves every past session's
     * figures without anything having to be rewritten.
     */
    private suspend fun sessionReview(runId: Long, run: RunEntity?): List<SegmentResult> {
        if (run?.workoutType == null) return emptyList()
        val segments = repository.workoutSegmentsFor(runId)
        if (segments.isEmpty()) return emptyList()
        val advances = run.workoutAdvancesActiveMs
            ?.split(',')
            ?.mapNotNull(String::toLongOrNull)
            .orEmpty()
        return WorkoutReview.of(segments, advances, points)
    }

    /** Drag on the graph: what did this stretch of the run actually average? */
    fun select(fromM: Double, toM: Double) {
        val selection = TrackProfile.selection(points, fromM, toM)
        _state.value = _state.value.copy(
            selection = selection,
            selectedSegments = selection?.let { segmentsBetween(it.fromM, it.toM) }.orEmpty(),
        )
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selection = null, selectedSegments = emptyList())
    }

    /**
     * The stretch of track between two distances, still split at its pauses.
     *
     * Cut by cumulative distance rather than by index, because that is the axis the
     * graph is drawn against — the reader dragged out a distance, not a range of fixes.
     */
    private fun segmentsBetween(fromM: Double, toM: Double): List<List<LatLon>> =
        points.filter { it.cumulativeDistanceM in fromM..toM }.toSegments()

    /** Only the distances where this run is the fastest one recorded are celebrated. */
    private suspend fun recordsHeldBy(runId: Long): List<Pair<Int, Long>> =
        BestEffortFinder.StandardDistances.mapNotNull { distance ->
            val record = repository.observePersonalRecord(distance).first()
            if (record != null && record.runId == runId) distance to record.durationMs else null
        }

    fun exportManually(uri: android.net.Uri, runId: Long) {
        viewModelScope.launch {
            _message.value = when (val result = exporter.exportTo(uri, runId)) {
                is ExportResult.Written -> "Saved"
                is ExportResult.Failed -> "Could not save: ${result.error.message}"
                else -> "Could not save"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun delete(runId: Long, onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deleteRun(runId)
            onDeleted()
        }
    }

    fun share(runId: Long, onReady: (android.net.Uri) -> Unit) {
        viewModelScope.launch {
            exporter.exportForSharing(runId)?.let(onReady)
                ?: run { _message.value = "Could not prepare the file" }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RunDetailViewModel(container.runRepository, container.gpxExporter) as T
    }
}

/**
 * Splits the track where nothing may be drawn across it.
 *
 * At every pause, and at every hole in the fixes the app refused to account for. The
 * second is derived rather than stored, so a run recorded before the rule existed is
 * drawn under it too — and a hole that contributed no distance never becomes a line
 * across town.
 */
fun List<TrackPoint>.toSegments(): List<List<LatLon>> {
    val segments = mutableListOf<List<LatLon>>()
    var current = mutableListOf<LatLon>()

    forEachIndexed { index, point ->
        val before = getOrNull(index - 1)
        val cut = before != null && (
            point.segment != before.segment ||
                TrackGaps.classify(
                    gapMs = point.timestampMs - before.timestampMs,
                    straightLineM = point.cumulativeDistanceM - before.cumulativeDistanceM,
                ) == GapKind.Broken
            )
        if (cut) {
            segments += current
            current = mutableListOf()
        }
        current += LatLon(point.lat, point.lon)
    }
    segments += current
    return segments.filter { it.size >= 2 }
}

package io.snailrun.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.snailrun.AppContainer
import io.snailrun.data.export.ExportResult
import io.snailrun.data.export.GpxExporter
import io.snailrun.data.repo.RunRepository
import io.snailrun.domain.analysis.BestEffortFinder
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

    fun load(runId: Long) {
        viewModelScope.launch {
            combine(
                repository.observeRun(runId),
                repository.observePoints(runId),
                repository.observeSplits(runId),
            ) { run, points, splits ->
                RunDetailUiState(
                    run = run,
                    segments = points.toSegments(),
                    splits = splits,
                    records = emptyList(),
                )
            }.collect { state ->
                _state.value = state.copy(records = recordsHeldBy(runId))
            }
        }
    }

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

/** Splits the track at pause boundaries so a gap is never drawn as a straight line. */
fun List<TrackPoint>.toSegments(): List<List<LatLon>> =
    groupBy { it.segment }
        .toSortedMap()
        .values
        .map { segment -> segment.map { LatLon(it.lat, it.lon) } }
        .filter { it.size >= 2 }

package io.snailrun.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.snailrun.AppContainer
import io.snailrun.data.db.RunEntity
import io.snailrun.data.location.LocationSource
import io.snailrun.data.repo.RunRepository
import io.snailrun.tracking.RecordingState
import io.snailrun.tracking.RunRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RecordUiState(
    val recording: RecordingState = RecordingState.Idle,
    val gpsEnabled: Boolean = true,
    /** A run left mid-flight by a crash or a kill, waiting for the user to decide. */
    val unfinishedRun: RunEntity? = null,
    val lastFinishedRunId: Long? = null,
)

class RecordViewModel(
    private val recorder: RunRecorder,
    private val repository: RunRepository,
    private val locationSource: LocationSource,
) : ViewModel() {

    private val _ui = MutableStateFlow(RecordUiState())
    val ui: StateFlow<RecordUiState> = _ui.asStateFlow()

    val recording: StateFlow<RecordingState> = recorder.state

    init {
        refresh()
    }

    fun refresh() {
        _ui.value = _ui.value.copy(gpsEnabled = locationSource.isReady())
        viewModelScope.launch {
            // Anything still marked RECORDING at startup was interrupted. It is never
            // deleted silently and never resumed silently: the user chooses.
            _ui.value = _ui.value.copy(unfinishedRun = repository.unfinishedRun())
        }
    }

    fun dismissRecovery() {
        _ui.value = _ui.value.copy(unfinishedRun = null)
    }

    fun finishRecoveredRun(runId: Long) {
        viewModelScope.launch {
            recorder.recover(runId)
            recorder.finish()
            _ui.value = _ui.value.copy(unfinishedRun = null, lastFinishedRunId = runId)
        }
    }

    fun discardRecoveredRun(runId: Long) {
        viewModelScope.launch {
            repository.deleteRun(runId)
            _ui.value = _ui.value.copy(unfinishedRun = null)
        }
    }

    fun consumeFinishedRun() {
        _ui.value = _ui.value.copy(lastFinishedRunId = null)
    }

    fun onRunFinished(runId: Long) {
        _ui.value = _ui.value.copy(lastFinishedRunId = runId)
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RecordViewModel(
            recorder = container.runRecorder,
            repository = container.runRepository,
            locationSource = container.locationSource,
        ) as T
    }
}

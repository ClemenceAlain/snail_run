package io.snailrun.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.snailrun.AppContainer
import io.snailrun.data.db.RunEntity
import io.snailrun.data.location.LocationSource
import io.snailrun.data.prefs.SettingsRepository
import io.snailrun.data.repo.RunRepository
import io.snailrun.tracking.RecordingState
import io.snailrun.domain.coach.Fitness
import io.snailrun.domain.coach.Workout
import io.snailrun.domain.coach.WorkoutType
import io.snailrun.tracking.RunRecorder
import io.snailrun.ui.coach.CoachPlans
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class RecordUiState(
    val recording: RecordingState = RecordingState.Idle,
    val gpsEnabled: Boolean = true,
    /** A run left mid-flight by a crash or a kill, waiting for the user to decide. */
    val unfinishedRun: RunEntity? = null,
    val lastFinishedRunId: Long? = null,
    /** What the coach has down for today, if anything. Null on a rest day. */
    val todaysSession: Workout? = null,
    /** The reinforcement work for today, if the week put any here. Never recorded. */
    val todaysStrength: Workout? = null,
    /** The session that will be started, once the runner has said yes to it. */
    val armedSession: Workout? = null,
)

class RecordViewModel(
    private val recorder: RunRecorder,
    private val repository: RunRepository,
    private val settings: SettingsRepository,
    private val locationSource: LocationSource,
    private val armed: ArmedSession,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    /** The one slot holding a chosen-but-not-started session, owned by [AppContainer]. */
    fun interface ArmedSession {
        fun set(workout: Workout?)
    }

    private val _ui = MutableStateFlow(RecordUiState())
    val ui: StateFlow<RecordUiState> = _ui.asStateFlow()

    val recording: StateFlow<RecordingState> = recorder.state

    init {
        refresh()
        // Today's line out of the coach's own block, so the two screens cannot disagree
        // about what the session is.
        viewModelScope.launch {
            val since = today.minusDays(Fitness.WINDOW_DAYS).toString()
            combine(
                repository.observeHistory(),
                repository.observeRecentEfforts(since),
                settings.settings,
            ) { runs, efforts, saved ->
                CoachPlans.block(
                    runs = runs,
                    efforts = efforts,
                    saved = saved.coach,
                    today = today,
                    firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek,
                    weeks = 1,
                ).firstOrNull()
                    ?.days
                    ?.firstOrNull { it.date == today }
            }.collect { day ->
                _ui.value = _ui.value.copy(
                    todaysSession = day?.workout?.takeIf { it.type != WorkoutType.Rest },
                    todaysStrength = day?.strength,
                )
            }
        }
    }

    /**
     * Arms today's session, or puts it back.
     *
     * Deliberately a separate step from pressing Start. A runner who opens the app to go
     * for an easy half hour should not have to fight a tempo they never asked for, and a
     * runner who wants the tempo has pressed one button to say so.
     */
    fun armSession(workout: Workout?) {
        armed.set(workout)
        _ui.value = _ui.value.copy(armedSession = workout)
    }

    fun consumeArmedSession() {
        _ui.value = _ui.value.copy(armedSession = null)
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
            settings = container.settings,
            locationSource = container.locationSource,
            armed = ArmedSession { container.armedWorkout = it },
        ) as T
    }
}

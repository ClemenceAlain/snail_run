package io.snailrun.ui.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.snailrun.AppContainer
import io.snailrun.data.db.PersonalRecord
import io.snailrun.data.db.RunEntity
import io.snailrun.data.prefs.CoachSettings
import io.snailrun.data.prefs.SettingsRepository
import io.snailrun.data.repo.RunRepository
import io.snailrun.domain.coach.Fitness
import io.snailrun.domain.coach.RaceGoal
import io.snailrun.domain.coach.WeekPlan
import io.snailrun.domain.coach.WeekPlanner
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class CoachUiState(
    val weeks: List<WeekPlan> = emptyList(),
    val goal: RaceGoal? = null,
    val loaded: Boolean = false,
    /** Which day's detail is open. One at a time; a week of expanded cards is a wall. */
    val expanded: LocalDate? = null,
    /** Which of [weeks] is on screen. One at a time, stepped with the arrows. */
    val weekIndex: Int = 0,
) {
    val fitness get() = weeks.firstOrNull()?.fitness
    val week get() = weeks.getOrNull(weekIndex)
}

/**
 * The week's plan, rebuilt from history every time anything in the history changes.
 *
 * Nothing about a plan is stored. That is the point: a plan written to the database on
 * Monday would still claim on Saturday that the runner owes it a tempo they have since
 * run, or that they should build on a week they in fact missed. Recomputing costs a
 * handful of milliseconds over a few hundred rows, and the plan is then always a
 * statement about the runs that exist rather than about the runs that once did.
 */
class CoachViewModel(
    private val repository: RunRepository,
    private val settings: SettingsRepository,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _ui = MutableStateFlow(CoachUiState())
    val ui: StateFlow<CoachUiState> = _ui.asStateFlow()

    init {
        val since = today.minusDays(Fitness.WINDOW_DAYS).toString()
        viewModelScope.launch {
            combine(
                repository.observeHistory(),
                repository.observeRecentEfforts(since),
                settings.settings,
            ) { runs, efforts, saved ->
                build(runs, efforts, saved.coach)
            }.collect { state ->
                _ui.value = state.copy(
                    expanded = _ui.value.expanded,
                    weekIndex = _ui.value.weekIndex.coerceIn(0, (state.weeks.size - 1).coerceAtLeast(0)),
                )
            }
        }
    }

    fun showWeek(offset: Int) {
        val last = (_ui.value.weeks.size - 1).coerceAtLeast(0)
        _ui.value = _ui.value.copy(
            weekIndex = (_ui.value.weekIndex + offset).coerceIn(0, last),
            // The open day belonged to the week being left, and carrying it across would
            // leave a card expanded that the reader can no longer see.
            expanded = null,
        )
    }

    fun expand(date: LocalDate) {
        _ui.value = _ui.value.copy(expanded = if (_ui.value.expanded == date) null else date)
    }

    /**
     * Records that a session has been dragged from one day to another.
     *
     * Only the permutation is written. The plan is left to be recomputed from the history
     * as it always is, so a move survives a new run being recorded, an app restart and a
     * change to the planner itself — none of which a stored plan would survive intact.
     */
    fun move(weekStart: LocalDate, from: Int, to: Int) {
        val plan = _ui.value.weeks.firstOrNull { it.weekStart == weekStart } ?: return
        val order = WeekPlanner.moveOrder(plan.order ?: WeekPlanner.identityOrder, from, to)
        viewModelScope.launch {
            settings.setCoachDayOrder(
                weekStartEpochDay = weekStart.toEpochDay(),
                order = order,
                keepFrom = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek())).toEpochDay(),
            )
        }
    }

    /** Puts one week back the way the rules laid it out. */
    fun resetWeek(weekStart: LocalDate) {
        viewModelScope.launch {
            settings.setCoachDayOrder(
                weekStartEpochDay = weekStart.toEpochDay(),
                order = null,
                keepFrom = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek())).toEpochDay(),
            )
        }
    }

    private fun build(
        runs: List<RunEntity>,
        efforts: List<PersonalRecord>,
        saved: CoachSettings,
    ): CoachUiState {
        val firstDay = firstDayOfWeek()
        val weeks = CoachPlans.block(runs, efforts, saved, today, firstDay)
        return CoachUiState(
            weeks = weeks,
            goal = saved.targetDistanceMeters?.let { distance ->
                saved.targetDateEpochDay?.let { RaceGoal(distance, LocalDate.ofEpochDay(it)) }
            },
            loaded = true,
        )
    }

    /**
     * Monday across most of Europe, Sunday across much of the rest. Read from the locale
     * here rather than assumed in the planner, which stays free of anything that varies
     * with the phone it is running on.
     */
    private fun firstDayOfWeek(): DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CoachViewModel(container.runRepository, container.settings) as T
    }
}

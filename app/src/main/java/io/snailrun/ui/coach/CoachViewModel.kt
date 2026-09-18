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
import io.snailrun.data.repo.SOURCE_DEMO
import io.snailrun.domain.coach.CoachRun
import io.snailrun.domain.coach.Fitness
import io.snailrun.domain.coach.RaceGoal
import io.snailrun.domain.coach.RecentEffort
import io.snailrun.domain.coach.TrainingLoad
import io.snailrun.domain.coach.WeekPlan
import io.snailrun.domain.coach.WeekPlanner
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class CoachUiState(
    val plan: WeekPlan? = null,
    val goal: RaceGoal? = null,
    /** How many finished runs the plan could be built from. Under three, it says so. */
    val runCount: Int = 0,
    val loaded: Boolean = false,
    /** Which day's detail is open. One at a time; a week of expanded cards is a wall. */
    val expanded: LocalDate? = null,
)

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
    settings: SettingsRepository,
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
            }.collect { state -> _ui.value = state.copy(expanded = _ui.value.expanded) }
        }
    }

    fun expand(date: LocalDate) {
        _ui.value = _ui.value.copy(expanded = if (_ui.value.expanded == date) null else date)
    }

    private fun build(
        runs: List<RunEntity>,
        efforts: List<PersonalRecord>,
        saved: CoachSettings,
    ): CoachUiState {
        // Demo runs are excluded here as they are from records. A synthetic trace says
        // nothing about a person's legs, and letting one set next week's volume would be
        // the app training the runner on its own fiction.
        val coachRuns = runs
            .filter { it.source != SOURCE_DEMO }
            .mapNotNull { run ->
                val date = runCatching { LocalDate.parse(run.localDate) }.getOrNull()
                    ?: return@mapNotNull null
                CoachRun(date = date, meters = run.distanceMeters, movingMs = run.movingTimeMs)
            }

        val firstDay = firstDayOfWeek()
        val weekStart = today.with(TemporalAdjusters.previousOrSame(firstDay))

        val load = TrainingLoad.summarise(coachRuns, today, firstDay)
        val fitness = Fitness.estimate(efforts.map { it.toRecentEffort() }, today)
        val goal = saved.toGoal()

        return CoachUiState(
            plan = WeekPlanner.plan(
                load = load,
                fitness = fitness,
                goal = goal,
                weekStart = weekStart,
                thisWeeksRuns = coachRuns.filter { it.date >= weekStart && it.date <= today },
            ),
            goal = goal,
            runCount = coachRuns.size,
            loaded = true,
        )
    }

    /**
     * Monday across most of Europe, Sunday across much of the rest. Read from the locale
     * here rather than assumed in the planner, which stays free of anything that varies
     * with the phone it is running on.
     */
    private fun firstDayOfWeek(): DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek

    private fun PersonalRecord.toRecentEffort() = RecentEffort(
        distanceMeters = distanceMeters,
        durationMs = durationMs,
        date = Instant.ofEpochMilli(startedAtEpochMs).atZone(ZoneId.systemDefault()).toLocalDate(),
    )

    private fun CoachSettings.toGoal(): RaceGoal? {
        val distance = targetDistanceMeters ?: return null
        val day = targetDateEpochDay ?: return null
        return RaceGoal(distance, LocalDate.ofEpochDay(day))
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CoachViewModel(container.runRepository, container.settings) as T
    }
}

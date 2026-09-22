package io.snailrun.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.snailrun.AppContainer
import io.snailrun.data.db.PersonalRecord
import io.snailrun.data.db.RunEntity
import io.snailrun.data.prefs.SettingsRepository
import io.snailrun.data.repo.RunRepository
import io.snailrun.domain.coach.Fitness
import io.snailrun.domain.coach.FitnessEstimate
import io.snailrun.domain.coach.RecentEffort
import io.snailrun.ui.format.UiLocale
import io.snailrun.domain.analysis.BestEffortFinder
import io.snailrun.domain.analysis.CalendarMonth
import io.snailrun.domain.analysis.DayTotal
import io.snailrun.domain.analysis.Progress
import io.snailrun.domain.analysis.ProgressBucket
import io.snailrun.domain.analysis.ProgressPeriod
import io.snailrun.domain.analysis.RunCalendar
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * There is no plain list.
 *
 * The calendar already puts the month's runs in a list underneath it, and tapping a day
 * narrows that list to the day. A separate flat list of every run ever was the same rows
 * with the one useful piece of context — when, relative to everything else — taken out.
 */
enum class HistoryMode { Calendar, Progress, Records }

/**
 * The best time over one distance, across every run.
 *
 * [record] is null for a distance no finished run has covered yet. Those are still
 * listed: an empty row says what the app is watching for, where an absent one would
 * just look like the feature stops at 10 km.
 */
data class DistanceRecord(
    val distanceMeters: Int,
    val record: PersonalRecord?,
)

/** Twelve bars: a season of weeks, or a year of months. Both fit a phone's width. */
private const val PROGRESS_BUCKETS = 12

data class HistoryUiState(
    val mode: HistoryMode = HistoryMode.Calendar,
    val runs: List<RunEntity> = emptyList(),
    val month: CalendarMonth? = null,
    val selectedDate: LocalDate? = null,
    /** In calendar mode, the runs of the selected day, or of the whole month if none. */
    val visibleRuns: List<RunEntity> = emptyList(),
    val progressPeriod: ProgressPeriod = ProgressPeriod.Week,
    val progress: List<ProgressBucket> = emptyList(),
    val records: List<DistanceRecord> = emptyList(),
    /** What the runner can currently run at, shown beside what they have already run. */
    val fitness: FitnessEstimate? = null,
    /** Closed until asked for: see [io.snailrun.ui.history.PacesCard]. */
    val pacesExpanded: Boolean = false,
)

/**
 * The runs list and the calendar over it.
 *
 * Both read the same history flow rather than a second query: a month holds a handful of
 * runs, and grouping them here keeps the calendar and the list from ever disagreeing
 * about what happened on a day.
 */
class HistoryViewModel(
    private val repository: RunRepository,
    private val settings: SettingsRepository,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _ui = MutableStateFlow(HistoryUiState())
    val ui: StateFlow<HistoryUiState> = _ui.asStateFlow()

    private var yearMonth: YearMonth = YearMonth.now()

    init {
        viewModelScope.launch {
            repository.observeHistory().collect { runs ->
                _ui.value = rebuild(_ui.value.copy(runs = runs))
            }
        }

        // Read from the stored best efforts rather than recomputed here. Every finished
        // run already has its efforts derived and saved, so the record over all of them
        // is one indexed query per distance, and it stays right when a run is deleted.
        viewModelScope.launch {
            combine(
                BestEffortFinder.StandardDistances.map { repository.observePersonalRecord(it) }
            ) { found ->
                BestEffortFinder.StandardDistances.mapIndexed { i, distance ->
                    DistanceRecord(distanceMeters = distance, record = found[i])
                }
            }.collect { records ->
                _ui.value = _ui.value.copy(records = records)
            }
        }

        // The same two inputs the coach estimates from, combined the same way: the
        // efforts the app found for itself and the race the runner reported, competing
        // on equal terms. Two paths to one number is how two screens end up quoting
        // different threshold paces for the same runner.
        viewModelScope.launch {
            val since = today.minusDays(Fitness.WINDOW_DAYS).toString()
            combine(
                repository.observeRecentEfforts(since),
                settings.settings,
            ) { efforts, saved ->
                Fitness.estimate(
                    efforts = efforts.map { it.toRecentEffort() } +
                        listOfNotNull(saved.coach.baseline?.race),
                    today = today,
                )
            }.collect { fitness ->
                _ui.value = _ui.value.copy(fitness = fitness)
            }
        }
    }

    fun togglePaces() {
        _ui.value = _ui.value.copy(pacesExpanded = !_ui.value.pacesExpanded)
    }

    private fun PersonalRecord.toRecentEffort() = RecentEffort(
        distanceMeters = distanceMeters,
        durationMs = durationMs,
        date = Instant.ofEpochMilli(startedAtEpochMs).atZone(ZoneId.systemDefault()).toLocalDate(),
    )

    fun setMode(mode: HistoryMode) {
        _ui.value = rebuild(_ui.value.copy(mode = mode))
    }

    fun showMonth(offset: Long) {
        yearMonth = yearMonth.plusMonths(offset)
        // The selection belongs to the month it was made in; carrying it across would
        // leave a day highlighted that the reader can no longer see.
        _ui.value = rebuild(_ui.value.copy(selectedDate = null))
    }

    fun setProgressPeriod(period: ProgressPeriod) {
        _ui.value = rebuild(_ui.value.copy(progressPeriod = period))
    }

    fun selectDate(date: LocalDate?) {
        val current = _ui.value.selectedDate
        _ui.value = rebuild(_ui.value.copy(selectedDate = if (date == current) null else date))
    }

    private fun rebuild(state: HistoryUiState): HistoryUiState {
        val byDate = state.runs.groupBy { it.localDate }
        val totals = byDate.mapNotNull { (localDate, runs) ->
            val date = runCatching { LocalDate.parse(localDate) }.getOrNull()
                ?: return@mapNotNull null
            DayTotal(
                date = date,
                runCount = runs.size,
                meters = runs.sumOf { it.distanceMeters },
                movingMs = runs.sumOf { it.movingTimeMs },
            )
        }

        val month = RunCalendar.build(yearMonth, totals, firstDayOfWeek())
        val visible = when {
            state.selectedDate != null -> byDate[state.selectedDate.toString()].orEmpty()
            else -> state.runs.filter { YearMonth.from(LocalDate.parse(it.localDate)) == yearMonth }
        }

        val progress = Progress.buckets(
            totals = totals,
            period = state.progressPeriod,
            count = PROGRESS_BUCKETS,
            endingOn = LocalDate.now(),
            firstDayOfWeek = firstDayOfWeek(),
        )

        return state.copy(month = month, visibleRuns = visible, progress = progress)
    }

    /**
     * Monday across most of Europe, Sunday across much of the rest. Read from the
     * locale rather than assumed: a calendar starting on the wrong day is misread at a
     * glance rather than noticed.
     *
     * The one thing on this screen that still follows the phone. Which day a week starts
     * on is a regional convention, not a language, so it does not move to [UiLocale] with
     * the month names above it.
     */
    private fun firstDayOfWeek(): DayOfWeek =
        WeekFields.of(Locale.getDefault()).firstDayOfWeek

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HistoryViewModel(container.runRepository, container.settings) as T
    }
}

/** "Mon". English, like every other word on the screen — see [UiLocale]. */
fun DayOfWeek.shortLabel(): String =
    getDisplayName(TextStyle.SHORT, UiLocale)

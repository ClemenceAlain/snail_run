package io.snailrun.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.snailrun.AppContainer
import io.snailrun.data.db.RunEntity
import io.snailrun.data.repo.RunRepository
import io.snailrun.domain.analysis.CalendarMonth
import io.snailrun.domain.analysis.DayTotal
import io.snailrun.domain.analysis.RunCalendar
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class HistoryMode { List, Calendar }

data class HistoryUiState(
    val mode: HistoryMode = HistoryMode.List,
    val runs: List<RunEntity> = emptyList(),
    val month: CalendarMonth? = null,
    val selectedDate: LocalDate? = null,
    /** In calendar mode, the runs of the selected day, or of the whole month if none. */
    val visibleRuns: List<RunEntity> = emptyList(),
)

/**
 * The runs list and the calendar over it.
 *
 * Both read the same history flow rather than a second query: a month holds a handful of
 * runs, and grouping them here keeps the calendar and the list from ever disagreeing
 * about what happened on a day.
 */
class HistoryViewModel(private val repository: RunRepository) : ViewModel() {

    private val _ui = MutableStateFlow(HistoryUiState())
    val ui: StateFlow<HistoryUiState> = _ui.asStateFlow()

    private var yearMonth: YearMonth = YearMonth.now()

    init {
        viewModelScope.launch {
            repository.observeHistory().collect { runs ->
                _ui.value = rebuild(_ui.value.copy(runs = runs))
            }
        }
    }

    fun setMode(mode: HistoryMode) {
        _ui.value = rebuild(_ui.value.copy(mode = mode))
    }

    fun showMonth(offset: Long) {
        yearMonth = yearMonth.plusMonths(offset)
        // The selection belongs to the month it was made in; carrying it across would
        // leave a day highlighted that the reader can no longer see.
        _ui.value = rebuild(_ui.value.copy(selectedDate = null))
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

        return state.copy(month = month, visibleRuns = visible)
    }

    /**
     * Monday across most of Europe, Sunday across much of the rest. Read from the
     * locale rather than assumed: a calendar starting on the wrong day is misread at a
     * glance rather than noticed.
     */
    private fun firstDayOfWeek(): DayOfWeek =
        WeekFields.of(Locale.getDefault()).firstDayOfWeek

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HistoryViewModel(container.runRepository) as T
    }
}

/** "Mon", in the reader's language. */
fun DayOfWeek.shortLabel(): String =
    getDisplayName(TextStyle.SHORT, Locale.getDefault())

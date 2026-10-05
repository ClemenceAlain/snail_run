package io.snailrun.ui.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.snailrun.AppContainer
import io.snailrun.data.db.PersonalRecord
import io.snailrun.data.db.RunEntity
import io.snailrun.data.prefs.CoachSettings
import io.snailrun.data.prefs.SettingsRepository
import io.snailrun.data.repo.CoachWeekStore
import io.snailrun.data.repo.RunRepository
import io.snailrun.domain.coach.AboutSection
import io.snailrun.domain.coach.Fitness
import io.snailrun.domain.coach.PaceBasis
import io.snailrun.domain.coach.Pairing
import io.snailrun.domain.coach.Partner
import io.snailrun.domain.coach.PlanExplainer
import io.snailrun.domain.coach.PlanSchedule
import io.snailrun.domain.coach.RaceGoal
import io.snailrun.domain.coach.TrainingPlan
import io.snailrun.domain.coach.Vma
import io.snailrun.domain.coach.VmaSource
import io.snailrun.domain.coach.Vmas
import io.snailrun.domain.coach.WeekPlan
import io.snailrun.domain.coach.WeekPlanner
import io.snailrun.domain.coach.WorkoutReview
import io.snailrun.domain.coach.WorkoutType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class CoachUiState(
    val loaded: Boolean = false,
    val plan: TrainingPlan? = null,
    val vma: Vma? = null,
    val basis: PaceBasis? = null,
    /** The week on screen, or null for a week the plan does not reach. */
    val week: WeekPlan? = null,
    /** First day of the week on screen. */
    val weekStart: LocalDate? = null,
    /** Steps from this week: 0 is now, -1 the newest saved week, 1 next week. */
    val offset: Int = 0,
    /** Calendar weeks from this one, for the label. Saved weeks can have gaps between. */
    val weeksAway: Long = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    /** Which day's detail is open. One at a time; a week of expanded cards is a wall. */
    val expanded: LocalDate? = null,
    val partners: List<Partner> = emptyList(),
    /** Shared sessions on the week shown, by the day they are now shown on. */
    val pairings: Map<LocalDate, Pairing> = emptyMap(),
    val about: List<AboutSection> = emptyList(),
    /** The plan wizard is open. */
    val creating: Boolean = false,
    /** The race the old Settings screen held, to start the wizard from. */
    val legacyGoal: RaceGoal? = null,
    val editingVma: Boolean = false,
    /** Something the coach has to tell the runner once: a VMA read off a test. */
    val message: String? = null,
    /** A test run that stopped too early to be read. The runner is asked instead. */
    val shortTestRunId: Long? = null,
) {
    val fitness get() = basis?.fitness
}

/**
 * The runner's plan, one week at a time.
 *
 * The weeks ahead are planned when they are looked at, from the plan and today's VMA, so
 * a new VMA reprices every one of them at once. The weeks behind are read back as they
 * were saved: the current week is saved each time it is planned, and stops changing once
 * it is over.
 */
class CoachViewModel(
    private val repository: RunRepository,
    private val settings: SettingsRepository,
    private val weeks: CoachWeekStore,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _ui = MutableStateFlow(CoachUiState())
    val ui: StateFlow<CoachUiState> = _ui.asStateFlow()

    private var runs: List<RunEntity> = emptyList()
    private var efforts: List<PersonalRecord> = emptyList()
    private var saved: CoachSettings = CoachSettings()
    private var pastStarts: List<LocalDate> = emptyList()
    private val readTests = mutableSetOf<Long>()

    private val firstDay: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    private val current: LocalDate = CoachPlans.currentWeek(today, firstDay)

    init {
        val since = today.minusDays(Fitness.WINDOW_DAYS).toString()
        viewModelScope.launch {
            pastStarts = weeks.weekStartsBefore(current)
            combine(
                repository.observeHistory(),
                repository.observeRecentEfforts(since),
                settings.settings,
            ) { runs, efforts, saved -> Triple(runs, efforts, saved.coach) }
                .collect { (runs, efforts, coach) ->
                    this@CoachViewModel.runs = runs
                    this@CoachViewModel.efforts = efforts
                    this@CoachViewModel.saved = coach
                    saveCurrentWeek()
                    show(_ui.value.offset)
                    readVmaTest()
                }
        }
    }

    fun showWeek(step: Int) {
        val state = _ui.value
        if (step < 0 && !state.canGoBack) return
        if (step > 0 && !state.canGoForward) return
        _ui.value = state.copy(expanded = null)
        viewModelScope.launch { show(state.offset + step) }
    }

    fun expand(date: LocalDate) {
        _ui.value = _ui.value.copy(expanded = if (_ui.value.expanded == date) null else date)
    }

    /**
     * Records that a session has been dragged from one day to another.
     *
     * Only the permutation is written. The week is replanned with it applied, so a move
     * survives a new run being recorded, an app restart and a new VMA.
     */
    fun move(weekStart: LocalDate, from: Int, to: Int) {
        val week = _ui.value.week?.takeIf { it.weekStart == weekStart && !it.frozen } ?: return
        val order = WeekPlanner.moveOrder(week.order ?: WeekPlanner.identityOrder, from, to)
        viewModelScope.launch {
            settings.setCoachDayOrder(weekStart.toEpochDay(), order, keepFrom = current.toEpochDay())
        }
    }

    /** Puts one week back the way the plan laid it out. */
    fun resetWeek(weekStart: LocalDate) {
        viewModelScope.launch {
            settings.setCoachDayOrder(weekStart.toEpochDay(), null, keepFrom = current.toEpochDay())
        }
    }

    /** Shares the session shown on [date], or stops sharing it with null. */
    fun pair(date: LocalDate, pairing: Pairing?) {
        val week = _ui.value.week?.takeIf { !it.frozen } ?: return
        val planned = CoachPlans.plannedDay(week, date) ?: return
        viewModelScope.launch {
            settings.setPairing(
                plannedEpochDay = planned.toEpochDay(),
                pairing = pairing,
                keepFrom = current.toEpochDay(),
            )
        }
    }

    fun startCreating(open: Boolean) {
        _ui.value = _ui.value.copy(creating = open)
    }

    /**
     * Starts a plan from the wizard's answers.
     *
     * A null [vma] is "I don't know": the stored one is cleared so the plan schedules the
     * test. The current week's snapshot goes too — it belonged to the old plan, and the
     * new one is about to write its own — but every week before it stays.
     */
    fun startPlan(race: RaceGoal?, targetTimeMs: Long?, sessionsPerWeek: Int, vma: Vma?) {
        val plan = TrainingPlan(
            startWeek = TrainingPlan.startWeekFor(today, firstDay),
            race = race,
            targetTimeMs = targetTimeMs,
            sessionsPerWeek = sessionsPerWeek,
            createdOn = today,
        )
        _ui.value = _ui.value.copy(creating = false, offset = 0, expanded = null)
        viewModelScope.launch {
            weeks.deleteFrom(current)
            // One write, so the week saved next is planned from the new plan and the new
            // VMA together rather than from one of them and the other's predecessor.
            settings.startCoachPlan(plan, vma)
        }
    }

    fun editVma(open: Boolean) {
        _ui.value = _ui.value.copy(editingVma = open)
    }

    /** A VMA typed in, or a test distance entered by hand. */
    fun setVma(kmh: Double, source: VmaSource) {
        // A distance entered for a test that stopped early belongs to that run, so it is
        // not read again.
        val run = _ui.value.shortTestRunId
        _ui.value = _ui.value.copy(editingVma = false, shortTestRunId = null)
        viewModelScope.launch {
            settings.setCoachVma(Vma(Vmas.round1(kmh), today, source, run))
        }
    }

    fun dismissMessage() {
        _ui.value = _ui.value.copy(message = null, shortTestRunId = null)
    }

    // ---- weeks -----------------------------------------------------------------------

    private suspend fun saveCurrentWeek() {
        val week = CoachPlans.week(runs, efforts, saved, current, today, firstDay) ?: return
        weeks.saveIfChanged(week)
    }

    private suspend fun show(offset: Int) {
        val plan = saved.plan
        val lastForward = plan?.let { PlanSchedule.lastWeek(it) } ?: current
        val target = if (offset < 0) pastStarts.getOrNull(-offset - 1) else current.plusWeeks(offset.toLong())
        if (target == null) {
            show(0)
            return
        }
        val week = if (offset < 0) {
            weeks.load(target, ranOn(target))
        } else {
            CoachPlans.week(runs, efforts, saved, target, today, firstDay)
        }
        val basis = CoachPlans.basis(efforts, saved, today)
        _ui.value = _ui.value.copy(
            loaded = true,
            plan = plan,
            vma = saved.vma,
            basis = basis,
            week = week,
            weekStart = target,
            offset = offset,
            canGoBack = -offset < pastStarts.size,
            canGoForward = offset < 0 || (plan != null && current.plusWeeks(offset + 1L) <= lastForward),
            weeksAway = ChronoUnit.WEEKS.between(current, target),
            about = plan?.let {
                PlanExplainer.about(it, basis, PlanSchedule.position(it, current) ?: PlanSchedule.position(it, it.startWeek))
            }.orEmpty(),
            legacyGoal = CoachPlans.legacyGoal(saved),
            partners = saved.partners,
            // A saved week is history: its sessions were run with whoever they were run with.
            pairings = week?.takeIf { !it.frozen }?.let { w ->
                w.days.mapNotNull { day -> CoachPlans.pairingOn(w, day.date, saved)?.let { day.date to it } }.toMap()
            }.orEmpty(),
        )
    }

    private fun ranOn(weekStart: LocalDate): Set<LocalDate> {
        val end = weekStart.plusDays(6)
        return runs.mapNotNull { runCatching { LocalDate.parse(it.localDate) }.getOrNull() }
            .filter { it >= weekStart && it <= end }
            .toSet()
    }

    // ---- the VMA test ----------------------------------------------------------------

    /**
     * Reads the VMA off the newest test run the coach has not read yet.
     *
     * Off the six-minute segment only, replayed through [WorkoutReview] exactly as the
     * run screen does, so the warm-up does not drag the number down. A test the runner
     * stopped early is not guessed at: they are asked for the distance instead.
     */
    private suspend fun readVmaTest() {
        val vma = saved.vma
        val test = runs
            .filter { it.workoutType == WorkoutType.VmaTest.name && it.id !in readTests }
            .filter { vma == null || (it.id != vma.testRunId && it.localDate >= vma.measuredOn.toString()) }
            .maxByOrNull { it.startedAtEpochMs } ?: return
        readTests += test.id

        val segments = repository.workoutSegmentsFor(test.id)
        val advances = test.workoutAdvancesActiveMs?.split(',')?.mapNotNull(String::toLongOrNull).orEmpty()
        val results = WorkoutReview.of(segments, advances, repository.smoothedPointsFor(test.id))
        val kmh = Vmas.fromTestResult(results)
        if (kmh == null) {
            _ui.value = _ui.value.copy(shortTestRunId = test.id)
            return
        }
        val date = runCatching { LocalDate.parse(test.localDate) }.getOrDefault(today)
        settings.setCoachVma(Vma(kmh, date, VmaSource.Test, test.id))
        val meters = (kmh * 100).roundToInt()
        _ui.value = _ui.value.copy(
            message = "Your test: $meters m in six minutes, a VMA of $kmh km/h. Every pace in " +
                "the plan now follows from it.",
        )
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CoachViewModel(container.runRepository, container.settings, container.coachWeeks) as T
    }
}

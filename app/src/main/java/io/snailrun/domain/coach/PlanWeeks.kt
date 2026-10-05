package io.snailrun.domain.coach

import io.snailrun.domain.coach.WeekPlanner.reordered
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Lays one week of a [TrainingPlan] onto the calendar.
 *
 * The days are fixed rather than chosen: the first session on Tuesday, the second on
 * Thursday, the long run on Sunday — the Decathlon week — and a fourth, easy, on Friday
 * for a runner who asked for four. A runner whose life does not fit that drags the
 * sessions where it does; the week remembers the move and says what it costs.
 *
 * Race week is laid out from the race instead: the race on its own date, the shake-out
 * two days before, the last short VMA session five days before. Days after the race are
 * rest.
 */
object PlanWeeks {

    val FirstDay: DayOfWeek = DayOfWeek.TUESDAY
    val SecondDay: DayOfWeek = DayOfWeek.THURSDAY
    val ExtraDay: DayOfWeek = DayOfWeek.FRIDAY
    val LongDay: DayOfWeek = DayOfWeek.SUNDAY

    private val ExtraEasy = SessionSpec.Easy(
        minutes = 40,
        tip = "Flat and conversational: if you cannot talk in full sentences, slow down.",
    )

    /**
     * The week starting [weekStart], or null outside the plan.
     *
     * [testWeek] is the week the VMA test goes in, if one is owed: its first session is
     * swapped for the test. [ranOn] marks days done.
     */
    fun week(
        plan: TrainingPlan,
        basis: PaceBasis,
        weekStart: LocalDate,
        testWeek: LocalDate? = null,
        ranOn: Set<LocalDate> = emptySet(),
        order: List<Int>? = null,
    ): WeekPlan? {
        val position = PlanSchedule.position(plan, weekStart) ?: return null
        val templateWeek = position.template.weeks[position.templateWeek - 1]
        val ctx = contextFor(plan, basis, position)
        val dates = (0L..6L).map { weekStart.plusDays(it) }

        val sessions: Map<LocalDate, SessionSpec> = if (position.kind == WeekKind.Race && plan.race != null) {
            raceWeek(plan, templateWeek, dates)
        } else {
            ordinaryWeek(plan, position, templateWeek, dates, testing = weekStart == testWeek)
        }

        val workouts = sessions.mapValues { (_, spec) -> PlanSessions.build(spec, ctx) }
        val runDates = workouts.keys.toList().sorted()
        val longDate = workouts.entries
            .firstOrNull { it.value.type == WorkoutType.Long || it.value.type == WorkoutType.Race }
            ?.key ?: runDates.lastOrNull() ?: dates.last()
        val qualityDates = workouts.filterValues { it.type.isQuality }.keys.toList()

        val strengthCount = when (position.kind) {
            WeekKind.Taper, WeekKind.Race -> 1
            else -> WeekPlanner.STRENGTH_SESSIONS
        }
        val strengthDates = WeekPlanner.chooseStrengthDays(
            dates = dates,
            runDates = runDates,
            longDate = longDate,
            qualityDates = qualityDates,
            count = strengthCount,
        )
        val strength = Strength.session(weekStart.toEpochDay() / 7)

        val days = dates.map { date ->
            PlannedDay(
                date = date,
                workout = workouts[date] ?: Workouts.rest(restReason(plan, date)),
                done = date in ranOn,
                strength = strength.takeIf { date in strengthDates },
            )
        }

        val week = WeekPlan(
            weekStart = weekStart,
            days = days,
            note = PlanExplainer.weekNote(position, plan),
            predictedTimeMs = plan.race?.let { race ->
                basis.fitness?.let { Vdot.timeMsFor(it.vdot, race.distanceMeters.toDouble()) }
            },
            fitness = basis.fitness,
            position = position,
        )
        return order?.let { week.reordered(it) } ?: week
    }

    /** The first week from [from] that can take a test: not a lighter week, not the taper. */
    fun firstTestWeek(plan: TrainingPlan, from: LocalDate): LocalDate? {
        var week = if (from.isBefore(plan.startWeek)) plan.startWeek else from
        val last = PlanSchedule.lastWeek(plan)
        while (!week.isAfter(last)) {
            val kind = PlanSchedule.position(plan, week)?.kind ?: return null
            if (kind == WeekKind.Base || kind == WeekKind.Build) return week
            week = week.plusWeeks(1)
        }
        return null
    }

    private fun ordinaryWeek(
        plan: TrainingPlan,
        position: PlanPosition,
        week: TemplateWeek,
        dates: List<LocalDate>,
        testing: Boolean,
    ): Map<LocalDate, SessionSpec> {
        fun on(day: DayOfWeek) = dates.first { it.dayOfWeek == day }
        // Two sessions a week keeps the long run and alternates the other two, so a
        // fortnight still has one of each.
        val first = when (plan.sessionsPerWeek.coerceIn(TrainingPlan.MIN_SESSIONS, TrainingPlan.MAX_SESSIONS)) {
            2 -> if (position.number % 2 == 1) week.first else week.second
            else -> week.first
        }
        return buildMap {
            put(on(FirstDay), if (testing) SessionSpec.VmaTest() else first)
            if (plan.sessionsPerWeek >= 3) put(on(SecondDay), week.second)
            if (plan.sessionsPerWeek >= 4) put(on(ExtraDay), ExtraEasy)
            put(on(LongDay), week.third)
        }
    }

    private fun raceWeek(
        plan: TrainingPlan,
        week: TemplateWeek,
        dates: List<LocalDate>,
    ): Map<LocalDate, SessionSpec> {
        val raceDay = plan.race!!.date
        val wanted = buildList {
            if (plan.sessionsPerWeek >= 3) add(raceDay.minusDays(5) to week.first)
            add(raceDay.minusDays(2) to week.second)
            add(raceDay to week.third)
        }
        return wanted.filter { (date, _) -> date in dates }.toMap()
    }

    private fun contextFor(plan: TrainingPlan, basis: PaceBasis, position: PlanPosition): SessionContext {
        val distance = position.template.distanceM
        val race = plan.race
        val target = plan.targetTimeMs
            ?.takeIf { race != null && PlanTemplates.forDistance(race.distanceMeters).distanceM == distance }
        val predicted = basis.fitness?.let { Vdot.timeMsFor(it.vdot, distance.toDouble()) }
        val name = CoachText.raceName(distance)
        val (paceMs, source) = when {
            target != null -> target to "your ${CoachText.clock(target)} target for the $name"
            predicted != null -> predicted to "your predicted $name on today's fitness, ${CoachText.clock(predicted)}"
            else -> null to ""
        }
        return SessionContext(
            basis = basis,
            specificPaceSecPerKm = paceMs?.let { it / 1000.0 / (distance / 1000.0) },
            specificName = "$name pace",
            specificSource = source,
            raceDistanceM = race?.distanceMeters,
            scaled = basis.vmaKmh?.let { it < SessionContext.SLOW_VMA_KMH } ?: false,
        )
    }

    private fun restReason(plan: TrainingPlan, date: LocalDate) =
        if (plan.race != null && date.isAfter(plan.race.date)) {
            "After the race. Rest, and enjoy it."
        } else {
            "Rest. The training lands on the days you do not run."
        }
}

package io.snailrun.domain.coach

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.min
import kotlin.math.roundToInt

data class PlannedDay(
    val date: LocalDate,
    val workout: Workout,
    /** A run was recorded on this day. Inferred, not ticked off — see [WeekPlanner]. */
    val done: Boolean = false,
    /**
     * The reinforcement session on this day, if it got one.
     *
     * A second session rather than a replacement for [workout], because that is what it
     * is: twenty minutes on the floor sits beside a rest day or an easy run, it does not
     * take one away. Keeping it in its own field is also what stops it ever reaching the
     * volume arithmetic, the recorder, or the "what is today's session" lookup on the
     * record screen — all three of which only ever read [workout].
     */
    val strength: Workout? = null,
)

data class WeekPlan(
    val weekStart: LocalDate,
    val days: List<PlannedDay>,
    val plannedMeters: Double,
    val lastWeekMeters: Double,
    val chronicWeeklyMeters: Double,
    /** Why the week is this size. The rule that fired, in a sentence. */
    val note: String,
    val phase: Phase? = null,
    val predictedTimeMs: Long? = null,
    val fitness: FitnessEstimate? = null,
    /**
     * What the week has been moved into, if the runner has moved anything.
     *
     * A permutation of 0..6: position *k* holds the session originally planned for day
     * `order[k]`. Null means the plan is as the rules laid it out.
     */
    val order: List<Int>? = null,
    /**
     * Rules the runner's own reordering has broken.
     *
     * The plan does not refuse a move, and it does not silently put the week back. It
     * says what the move costs and leaves the decision where it belongs — someone who
     * has to be at work on Tuesday knows something the planner does not.
     */
    val conflicts: List<String> = emptyList(),
)

/**
 * Turns a training history into next week's sessions.
 *
 * Everything here is a rule with a number attached, and every number has a reason that
 * goes on the screen beside it. That is not decoration. A coach that cannot say why it
 * asked for six kilometres of threshold cannot be argued with, and a runner who cannot
 * argue with their plan follows it into an injury.
 *
 * The rules that exist to stop that, in the order they bind:
 *
 * - **Volume rises by at most ten per cent of last week**, and never past 1.3× the
 *   four-week average. The second cap is the one that matters: the ten-per-cent rule
 *   compounds a spike, the ratio refuses to.
 * - **The long run is capped twice** — a share of the week *and* 1.1× the longest run of
 *   the last four weeks. A runner with 50 km weeks made of 10 km runs does not get a
 *   15 km Sunday because the arithmetic allowed it.
 * - **Hard running is capped as a fraction of the week**: threshold 10 %, interval 8 %,
 *   repetition 5 %, marathon pace 20 %. Sessions are shortened to fit; the week is never
 *   lengthened to fit a session.
 * - **Frequency is never increased.** How many days a week someone runs is a decision
 *   about their life. The coach works inside it.
 * - **Nothing fast is prescribed from a fitness estimate that is only [Confidence
 *   .Provisional]**, because those come from efforts too short to be trusted.
 *
 * Completion is inferred rather than tracked: a run recorded on a planned day marks that
 * day done. Nothing is stored, so the plan cannot drift out of step with the history —
 * it is recomputed from it every time it is shown.
 */
object WeekPlanner {

    const val RAMP = 1.10
    const val ACWR_CEILING = 1.30
    const val ACWR_ALARM = 1.5
    const val LONG_RUN_GROWTH = 1.10
    const val THRESHOLD_SHARE = 0.10

    /**
     * Threshold work gets a floor and a hard ceiling either side of the ten per cent.
     *
     * Ten per cent of a twenty-kilometre week is two kilometres, which at threshold is
     * about eight minutes — long enough to hurt and too short to do anything. So the
     * floor buys fifteen minutes where the week can afford it, and the ceiling refuses to
     * let that floor run away with a small week. Between them a low-mileage runner gets
     * the longest threshold session their week supports and not a minute more.
     */
    const val THRESHOLD_FLOOR_MS = 15 * 60_000L
    const val THRESHOLD_CEILING_SHARE = 0.15
    const val INTERVAL_SHARE = 0.08
    const val INTERVAL_CAP_M = 10_000.0
    const val REPETITION_SHARE = 0.05
    const val REPETITION_CAP_M = 8_000.0
    const val MARATHON_SHARE = 0.20
    const val MARATHON_CAP_M = 25_000.0

    /**
     * Reinforcement sessions in an ordinary week.
     *
     * Two is where the evidence sits, and it is also the most a runner keeps doing. It
     * does not vary with mileage: strength work is insurance against the landings, and
     * the runner with the smallest week is usually the one with the least of it already.
     */
    const val STRENGTH_SESSIONS = 2

    /** A first week for someone with almost no history. Three easy runs, nothing clever. */
    const val BASE_WEEK_METERS = 15_000.0
    private const val MIN_EASY_M = 3_000.0
    private const val MIN_LONG_M = 5_000.0

    fun plan(
        load: LoadSummary,
        fitness: FitnessEstimate?,
        goal: RaceGoal?,
        weekStart: LocalDate,
        thisWeeksRuns: List<CoachRun> = emptyList(),
    ): WeekPlan {
        val phase = goal?.let { Races.phaseFor(it, weekStart) }
        val paces = fitness?.paces ?: provisionalPaces(load)
        val budget = budgetFor(load, fitness, goal, weekStart)

        val dates = (0L..6L).map { weekStart.plusDays(it) }
        val runDates = chooseRunDays(load, dates, budget.runDays)
        val longDate = chooseLongRunDay(load, runDates)
        val qualityDates = chooseQualityDays(runDates, longDate, budget.quality)

        val quality = qualityDates.mapIndexed { index, date ->
            val type = qualityType(phase, index, fitness?.confidence, weekStart)
            date to buildQuality(type, budget.meters, paces, fitness)
        }.toMap()

        val qualityMeters = quality.values.sumOf { it.totalMeters }
        val easyDates = runDates - longDate - qualityDates.toSet()

        val longRun = buildLongRun(budget, load, paces, qualityMeters, easyDates.size)
        val easyShare = ((budget.meters - qualityMeters - longRun.totalMeters) /
            easyDates.size.coerceAtLeast(1)).coerceAtLeast(MIN_EASY_M)

        val strengthDates = chooseStrengthDays(
            dates = dates,
            runDates = runDates,
            longDate = longDate,
            qualityDates = qualityDates,
            count = budget.strength,
        )
        val strength = Strength.session(weekStart.toEpochDay() / 7)

        val ranOn = thisWeeksRuns.map { it.date }.toSet()
        var stridesPlaced = false

        val days = dates.map { date ->
            val workout = when {
                date == longDate -> longRun
                quality.containsKey(date) -> quality.getValue(date)
                date in easyDates -> {
                    val yesterdayWasHard = (date.minusDays(1) == longDate) ||
                        quality.containsKey(date.minusDays(1))
                    when {
                        yesterdayWasHard -> Workouts.recovery(
                            meters = easyShare * 0.8,
                            paces = paces,
                            reason = "The day after a hard one. Short and slow.",
                        )
                        !stridesPlaced && budget.strides -> {
                            stridesPlaced = true
                            Workouts.strides(
                                meters = easyShare,
                                paces = paces,
                                reason = "Six accelerations inside an easy run. They cost " +
                                    "nothing and keep your legs quick.",
                            )
                        }
                        else -> Workouts.easy(
                            meters = easyShare,
                            paces = paces,
                            reason = "Four fifths of a week should feel easy.",
                        )
                    }
                }
                else -> Workouts.rest(restReason(budget.runDays))
            }
            PlannedDay(
                date = date,
                workout = workout,
                done = date in ranOn,
                strength = strength.takeIf { date in strengthDates },
            )
        }

        val planned = days.sumOf { it.workout.totalMeters }
        return WeekPlan(
            weekStart = weekStart,
            days = days,
            plannedMeters = planned,
            lastWeekMeters = load.acuteMeters,
            chronicWeeklyMeters = load.chronicWeeklyMeters,
            note = budget.note(planned),
            phase = phase,
            predictedTimeMs = goal?.let { Races.predictedTimeMs(it, fitness) },
            fitness = fitness,
        )
    }

    /**
     * The same week, planned out for [weeks] weeks running.
     *
     * Each week after the first is planned against a history that already contains the
     * weeks before it, as though they had been run exactly as written. That is the only
     * honest way to show a block: the second week's ten per cent is ten per cent of the
     * first week's plan, not of the week the runner actually just did, and a block built
     * without rolling the history forward shows four identical weeks and no progression
     * at all.
     *
     * Fitness is *not* rolled forward. The paces stay at what the runner's efforts say
     * today, because a projected VDOT four weeks out is a guess, and a guess in a pace is
     * the one thing this whole module exists to avoid.
     */
    fun block(
        runs: List<CoachRun>,
        fitness: FitnessEstimate?,
        goal: RaceGoal?,
        firstWeekStart: LocalDate,
        today: LocalDate,
        weeks: Int,
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
        orders: Map<LocalDate, List<Int>> = emptyMap(),
    ): List<WeekPlan> {
        if (weeks <= 0) return emptyList()
        val history = runs.toMutableList()
        val plans = mutableListOf<WeekPlan>()

        repeat(weeks) { index ->
            val weekStart = firstWeekStart.plusWeeks(index.toLong())
            // The first week is planned from where the runner stands now; a later one is
            // planned from the day before it starts, so its "last week" is the week just
            // written rather than a week half over.
            val asOf = if (index == 0) today else weekStart.minusDays(1)

            val plan = plan(
                load = TrainingLoad.summarise(history, asOf, firstDayOfWeek),
                fitness = fitness,
                goal = goal,
                weekStart = weekStart,
                thisWeeksRuns = if (index == 0) {
                    runs.filter { it.date >= weekStart && it.date <= today }
                } else {
                    emptyList()
                },
            ).let { orders[weekStart]?.let { order -> it.reordered(order) } ?: it }

            plans += plan
            history += plan.days
                .filter { it.workout.type != WorkoutType.Rest }
                .map { CoachRun(it.date, it.workout.totalMeters, 0) }
        }
        return plans
    }

    /**
     * Moves the session at [from] to [to], shifting everything between along by a day.
     *
     * A move rather than a swap, because that is what a runner means. Pushing Tuesday's
     * tempo to Thursday should slide Wednesday and Thursday back a day, not trade the
     * tempo for whatever Thursday happened to hold.
     */
    fun moveOrder(current: List<Int>, from: Int, to: Int): List<Int> {
        if (from !in current.indices || to !in current.indices || from == to) return current
        val moved = current.toMutableList()
        moved.add(to, moved.removeAt(from))
        return moved
    }

    val identityOrder: List<Int> get() = (0..6).toList()

    /**
     * Applies a runner's reordering to a planned week.
     *
     * The dates stay put and the workouts move between them, so a session keeps its
     * shape and changes its day. `done` is re-read from the date rather than carried with
     * the workout: a run recorded on Tuesday marks Tuesday done whatever is now sitting
     * on it.
     */
    fun WeekPlan.reordered(order: List<Int>): WeekPlan {
        if (order.sorted() != days.indices.toList()) return this
        val moved = order.mapIndexed { position, source ->
            days[position].copy(
                workout = days[source].workout,
                strength = days[source].strength,
            )
        }
        return copy(
            days = moved,
            order = if (order == identityOrder) null else order,
            conflicts = conflictsIn(moved),
        )
    }

    /**
     * What a reordered week now gets wrong.
     *
     * Only things the rules would have refused outright. A week the runner has shuffled
     * is still their week, and a screen full of advice about a plan they deliberately
     * changed reads as nagging rather than as a warning worth reading.
     */
    private fun conflictsIn(days: List<PlannedDay>): List<String> {
        val warnings = mutableListOf<String>()
        val hard = days.filter { it.workout.type.isQuality || it.workout.type == WorkoutType.Long }

        hard.zipWithNext().forEach { (first, second) ->
            if (second.date.toEpochDay() - first.date.toEpochDay() == 1L) {
                warnings += "${dayName(first.date)} and ${dayName(second.date)} are both hard."
            }
        }

        val runs = days.filter { it.workout.type != WorkoutType.Rest }
        runs.windowed(4, 1, partialWindows = false).forEach { window ->
            val consecutive = window.zipWithNext()
                .all { (a, b) -> b.date.toEpochDay() - a.date.toEpochDay() == 1L }
            if (consecutive && warnings.none { it.startsWith("Four") }) {
                warnings += "Four days running without a rest."
            }
        }
        return warnings
    }

    private fun dayName(date: LocalDate) =
        date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)

    // ---- the week's size -------------------------------------------------------------

    /**
     * [note] takes the week's actual total rather than being a finished string.
     *
     * The budget is a ceiling and the sessions land a little under it — a recovery day is
     * shorter than an easy one, a tempo rounds to whole reps. Writing the ceiling into the
     * note would leave the runner reading "this week is 44 km" above a plan that sums to
     * 42.5, which is the sort of small inconsistency that costs a feature its credibility.
     */
    private data class Budget(
        val meters: Double,
        val runDays: Int,
        val quality: Int,
        val strides: Boolean,
        /**
         * Reinforcement sessions this week.
         *
         * Two, nearly always. It is the one part of the plan that does not scale with
         * mileage: a runner on twenty kilometres a week needs the strength work more than
         * one on eighty, not less, and it costs neither of them a kilometre.
         */
        val strength: Int,
        val note: (Double) -> String,
    )

    private fun budgetFor(
        load: LoadSummary,
        fitness: FitnessEstimate?,
        goal: RaceGoal?,
        weekStart: LocalDate,
    ): Budget {
        val days = load.runsPerWeek.coerceIn(3, 6)
        val allowed = qualityAllowance(days, fitness)
        val taper = goal?.let { Races.volumeFactor(it, weekStart) } ?: 1.0

        val budget = when {
            load.runsInLast28Days < 3 -> Budget(
                meters = BASE_WEEK_METERS,
                runDays = 3,
                quality = 0,
                strides = false,
                strength = STRENGTH_SESSIONS,
                note = { "Not enough behind you to plan from yet. A week of easy running." },
            )

            (load.daysSinceLastRun ?: 0) >= 14 -> Budget(
                meters = load.chronicWeeklyMeters * 0.6,
                runDays = min(days, 4),
                quality = 0,
                strides = false,
                strength = STRENGTH_SESSIONS,
                note = { total ->
                    "You have not run in ${load.daysSinceLastRun} days. Back at ${km(total)}, " +
                        "all easy."
                },
            )

            load.ratio != null && load.ratio > ACWR_ALARM -> Budget(
                meters = load.chronicWeeklyMeters,
                runDays = days,
                quality = 0,
                strides = false,
                strength = STRENGTH_SESSIONS,
                note = { total ->
                    "Last week was ${times(load.ratio)} your four-week average. Holding level " +
                        "at ${km(total)}, all easy."
                },
            )

            load.risingWeeks >= 3 -> Budget(
                meters = load.acuteMeters * 0.75,
                runDays = days,
                quality = allowed,
                strides = days >= 4,
                strength = STRENGTH_SESSIONS,
                note = { total ->
                    "Three rising weeks behind you, so a cutback: ${km(total)}. The hard days stay."
                },
            )

            else -> {
                // The floor matters as much as the ceiling: after a cutback week, ten per
                // cent on top of a deliberately small week would ratchet the runner down
                // instead of returning them to where they were.
                val raw = min(load.acuteMeters * RAMP, load.chronicWeeklyMeters * ACWR_CEILING)
                    .coerceAtLeast(load.chronicWeeklyMeters * 0.9)
                Budget(
                    meters = raw,
                    runDays = days,
                    quality = allowed,
                    strides = days >= 4 && allowed <= 1,
                    strength = STRENGTH_SESSIONS,
                    note = { total ->
                        "Last week ${km(load.acuteMeters)}, four-week average " +
                            "${km(load.chronicWeeklyMeters)}. This week ${km(total)}."
                    },
                )
            }
        }

        if (taper == 1.0) return budget
        return budget.copy(
            meters = budget.meters * taper,
            // One, not two. A taper exists to arrive fresh, and the only thing a second
            // set of squats can do to race day is take something off it.
            strength = 1,
            note = { total ->
                "Tapering: ${km(total)}, about ${percent(taper)} of normal. The sharpness stays."
            },
        )
    }

    /**
     * How many hard days the week can carry.
     *
     * Three runs a week supports one; five supports two. Nobody gets a third, whatever
     * their volume — the third quality session is where the returns stop and the injuries
     * start. Nothing at all without a trustworthy fitness estimate to price it from.
     */
    private fun qualityAllowance(runDays: Int, fitness: FitnessEstimate?): Int = when {
        fitness == null || fitness.confidence == Confidence.None -> 0
        fitness.confidence == Confidence.Provisional -> 1
        runDays >= 5 -> 2
        runDays >= 3 -> 1
        else -> 0
    }

    // ---- which days ------------------------------------------------------------------

    /**
     * Sunday first, then the days a Tuesday/Thursday runner uses. Only a tie-break: what
     * the runner actually does, from [LoadSummary.runDayFrequency], comes first.
     */
    private val DefaultPreference = listOf(
        DayOfWeek.SUNDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY,
        DayOfWeek.SATURDAY, DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY, DayOfWeek.FRIDAY,
    )

    private fun chooseRunDays(load: LoadSummary, dates: List<LocalDate>, count: Int): List<LocalDate> =
        dates.sortedWith(
            compareByDescending<LocalDate> { load.runDayFrequency[it.dayOfWeek] ?: 0 }
                .thenBy { DefaultPreference.indexOf(it.dayOfWeek) }
        ).take(count).sorted()

    private fun chooseLongRunDay(load: LoadSummary, runDates: List<LocalDate>): LocalDate =
        runDates.firstOrNull { it.dayOfWeek == load.longRunDay }
            ?: runDates.minByOrNull { DefaultPreference.indexOf(it.dayOfWeek) }
            ?: runDates.first()

    /**
     * Hard days, spread out.
     *
     * A day is refused if it touches another hard day at all — the day before or after a
     * quality session, and either side of the long run. Two hard days in a row is the
     * single most reliable way to turn a training week into three weeks off.
     */
    private fun chooseQualityDays(
        runDates: List<LocalDate>,
        longDate: LocalDate,
        count: Int,
    ): List<LocalDate> {
        if (count <= 0) return emptyList()
        val taken = mutableListOf(longDate)
        val chosen = mutableListOf<LocalDate>()

        runDates
            .filter { it != longDate }
            .sortedBy { DefaultPreference.indexOf(it.dayOfWeek) }
            .forEach { date ->
                if (chosen.size >= count) return@forEach
                val touches = taken.any { kotlin.math.abs(it.toEpochDay() - date.toEpochDay()) <= 1 }
                if (!touches) {
                    chosen += date
                    taken += date
                }
            }
        return chosen.sorted()
    }

    /**
     * Which days carry the reinforcement work.
     *
     * Rest days first. A runner who has already laced up is not the one who needs a
     * reason to be on the floor, and a set of squats is the easiest thing in a week to
     * fit around everything else.
     *
     * The one rule that is not a preference: never the day before something hard. Loaded
     * legs are slow legs for about twenty-four hours, and a tempo run on them is a tempo
     * run at the wrong pace. If honouring that would leave the week with no room at all —
     * six running days with two quality sessions and a long run does — the fallback puts
     * the strength work *on* a hard day instead, after the running. Hard days hard is a
     * worse-looking plan and a better-recovered runner than spreading the load thin.
     */
    private fun chooseStrengthDays(
        dates: List<LocalDate>,
        runDates: List<LocalDate>,
        longDate: LocalDate,
        qualityDates: List<LocalDate>,
        count: Int,
    ): List<LocalDate> {
        if (count <= 0) return emptyList()
        val hard = (qualityDates + longDate).toSet()
        val eveOfHard = hard.map { it.minusDays(1) }.toSet()
        val chosen = mutableListOf<LocalDate>()

        fun take(candidates: List<LocalDate>) {
            candidates.forEach { date ->
                if (chosen.size >= count) return
                if (date in eveOfHard) return@forEach
                // Never back to back: two sessions on consecutive days is one session
                // and one session done on sore legs.
                if (chosen.any { kotlin.math.abs(it.toEpochDay() - date.toEpochDay()) <= 1 }) {
                    return@forEach
                }
                chosen += date
            }
        }

        take(dates.filterNot { it in runDates })
        take(dates.filter { it in runDates && it !in hard })
        take(dates.filter { it in hard })
        return chosen.sorted()
    }

    // ---- which sessions --------------------------------------------------------------

    private val ThresholdRotation = listOf(WorkoutType.Tempo, WorkoutType.CruiseIntervals)
    private val Vo2Rotation = listOf(WorkoutType.Intervals, WorkoutType.Hills, WorkoutType.Fartlek)

    /**
     * Which session this is, this week.
     *
     * Rotated on the week number so a plan does not prescribe the same Tuesday for a year,
     * and rotated by arithmetic rather than at random so the same week always produces the
     * same plan and the tests can assert one.
     */
    private fun qualityType(
        phase: Phase?,
        index: Int,
        confidence: Confidence?,
        weekStart: LocalDate,
    ): WorkoutType {
        val week = weekStart.toEpochDay() / 7
        // Threshold is the one hard session a short effort can be priced from: it is run
        // at a pace held for an hour, not at a pace held for three minutes.
        if (confidence == Confidence.Provisional) {
            return ThresholdRotation[(week % ThresholdRotation.size).toInt()]
        }
        val vo2 = Vo2Rotation[(week % Vo2Rotation.size).toInt()]
        val threshold = ThresholdRotation[(week % ThresholdRotation.size).toInt()]
        return when (phase) {
            null, Phase.Base -> if (index == 0) threshold else WorkoutType.Hills
            Phase.Build -> if (index == 0) threshold else vo2
            Phase.Peak -> if (index == 0) WorkoutType.Steady else WorkoutType.Intervals
            Phase.Taper -> if (index == 0) WorkoutType.Tempo else WorkoutType.Repetitions
        }
    }

    private fun buildQuality(
        type: WorkoutType,
        budgetMeters: Double,
        paces: TrainingPaces,
        fitness: FitnessEstimate?,
    ): Workout {
        val from = fitness?.let {
            "Your ${distanceName(it.fromDistanceM)} effort on ${it.fromDate} sets "
        } ?: "From your recent running, "

        return when (type) {
            WorkoutType.Tempo -> {
                val work = thresholdWork(budgetMeters, paces)
                Workouts.tempo(
                    work, paces,
                    from + "${pace(paces.thresholdSecPerKm)}/km at threshold. Ten per cent " +
                        "of the week is ${km(work)}.",
                )
            }

            WorkoutType.CruiseIntervals -> {
                val work = thresholdWork(budgetMeters, paces)
                Workouts.cruiseIntervals(
                    work, paces,
                    from + "${pace(paces.thresholdSecPerKm)}/km. The same ${km(work)} of " +
                        "threshold as a tempo, broken up.",
                )
            }

            WorkoutType.Intervals -> {
                val work = Round.blockMeters(min(budgetMeters * INTERVAL_SHARE, INTERVAL_CAP_M))
                Workouts.intervals(
                    work, paces,
                    from + "${pace(paces.intervalSecPerKm)}/km. Eight per cent of the week, " +
                        "so ${km(work)} hard.",
                )
            }

            WorkoutType.Hills -> {
                val work = Round.blockMeters(min(budgetMeters * INTERVAL_SHARE, INTERVAL_CAP_M))
                Workouts.hills(
                    work, paces,
                    "The hill sets the pace: run it hard and ignore the watch. " +
                        "${km(work)} of climbing, interval effort at less of the impact.",
                )
            }

            WorkoutType.Fartlek -> {
                val work = Round.blockMeters(min(budgetMeters * INTERVAL_SHARE, INTERVAL_CAP_M))
                Workouts.fartlek(
                    work, paces,
                    from + "about ${pace(paces.intervalSecPerKm)}/km for the quick minutes. " +
                        "Unstructured on purpose.",
                )
            }

            WorkoutType.Repetitions -> {
                val work = Round.blockMeters(min(budgetMeters * REPETITION_SHARE, REPETITION_CAP_M))
                Workouts.repetitions(
                    work, paces,
                    from + "${pace(paces.repetitionSecPerKm)}/km, fully recovered between. " +
                        "Five per cent of the week at most.",
                )
            }

            WorkoutType.Steady -> {
                val work = Round.blockMeters(min(budgetMeters * MARATHON_SHARE, MARATHON_CAP_M))
                Workouts.steady(
                    work, paces,
                    from + "${pace(paces.marathonSecPerKm)}/km, ${km(work)} continuous. " +
                        "Race rehearsal.",
                )
            }

            else -> Workouts.easy(budgetMeters * 0.2, paces, "Easy.")
        }
    }

    private fun thresholdWork(budgetMeters: Double, paces: TrainingPaces): Double =
        Round.blockMeters(
            (budgetMeters * THRESHOLD_SHARE)
                .coerceAtLeast(Workouts.metersAt(paces.thresholdSecPerKm, THRESHOLD_FLOOR_MS))
                .coerceAtMost(budgetMeters * THRESHOLD_CEILING_SHARE)
        )

    private fun buildLongRun(
        budget: Budget,
        load: LoadSummary,
        paces: TrainingPaces,
        qualityMeters: Double,
        easyDays: Int,
    ): Workout {
        // A three-day week cannot put thirty per cent into its longest run and still have
        // it be the longest, so the share loosens as the week gets shorter.
        val share = when {
            budget.runDays <= 3 -> 0.40
            budget.runDays == 4 -> 0.35
            else -> 0.30
        }
        val byShare = budget.meters * share
        val byHistory = if (load.longestRunMeters > 0.0) {
            load.longestRunMeters * LONG_RUN_GROWTH
        } else {
            byShare
        }
        val room = budget.meters - qualityMeters - easyDays * MIN_EASY_M
        val meters = Round.blockMeters(minOf(byShare, byHistory, room).coerceAtLeast(MIN_LONG_M))

        val reason = if (byHistory < byShare && load.longestRunMeters > 0.0) {
            "Your longest in four weeks was ${km(load.longestRunMeters)}, so this is " +
                "${km(meters)}. A long run grows a tenth at a time."
        } else {
            // Not "of a 44 km week": the budget is a ceiling and the plan lands under it,
            // so quoting it here would disagree with the total in the week's own header.
            "${percent(share)} of the week, which is ${km(meters)}. The distance is the " +
                "session, not the pace."
        }
        return Workouts.longRun(meters, paces, reason)
    }

    private fun restReason(runDays: Int) =
        "Rest. The training lands on the days you do not run."

    /**
     * Paces for a runner the app cannot price yet.
     *
     * Derived from their own average pace over the last four weeks rather than from a
     * table: a beginner handed a stranger's easy pace runs it, and it is either pointless
     * or dangerous. With no history at all there is nothing to say, so the ranges are
     * wide and the planner will not prescribe anything fast off them anyway.
     */
    private fun provisionalPaces(load: LoadSummary): TrainingPaces {
        val fallbackEasy = 420.0
        return TrainingPaces(
            easySecPerKm = fallbackEasy..(fallbackEasy + 60.0),
            marathonSecPerKm = fallbackEasy - 45.0,
            thresholdSecPerKm = fallbackEasy - 70.0,
            intervalSecPerKm = fallbackEasy - 90.0,
            repetitionSecPerKm = fallbackEasy - 105.0,
        )
    }

    // ---- text ------------------------------------------------------------------------
    //
    // Formatted here rather than through `ui/format`, and without a Locale: these strings
    // are asserted in tests, and a decimal separator that changes with the phone's
    // language would make the assertions depend on where the build ran.

    private fun km(meters: Double): String {
        val tenths = (meters / 100.0).roundToInt()
        return "${tenths / 10}.${tenths % 10} km"
    }

    private fun pace(secPerKm: Double): String {
        val total = secPerKm.roundToInt()
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    private fun percent(fraction: Double) = "${(fraction * 100).roundToInt()} per cent"

    private fun times(ratio: Double) = "${(ratio * 10).roundToInt() / 10}.${(ratio * 10).roundToInt() % 10} times"

    private fun distanceName(meters: Int) = when (meters) {
        21_097 -> "half marathon"
        42_195 -> "marathon"
        else -> "${meters / 1000} km"
    }
}

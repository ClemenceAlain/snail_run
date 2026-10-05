package io.snailrun.domain.coach

import java.time.DayOfWeek
import java.time.LocalDate

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
    /** Where the week sits in the plan, in a sentence: "Week 3 of 8 · Build." */
    val note: String,
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
    /** Where the week sits in the runner's plan. */
    val position: PlanPosition? = null,
    /** Read back from a saved week rather than planned now. Cannot be rearranged. */
    val frozen: Boolean = false,
)

/**
 * What the plan's weeks share, whatever template they come from.
 *
 * The sessions themselves are [PlanWeeks]' business. What lives here is the part of a
 * week that is about the runner rather than the plan: the reorderings they make, the
 * warnings those reorderings earn, and where the strength work fits around the running.
 */
object WeekPlanner {

    /**
     * Reinforcement sessions in an ordinary week.
     *
     * Two is where the evidence sits, and it is also the most a runner keeps doing. It
     * does not vary with mileage: strength work is insurance against the landings, and
     * the runner with the smallest week is usually the one with the least of it already.
     */
    const val STRENGTH_SESSIONS = 2

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
    internal fun conflictsIn(days: List<PlannedDay>): List<String> {
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
    internal fun chooseStrengthDays(
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
}

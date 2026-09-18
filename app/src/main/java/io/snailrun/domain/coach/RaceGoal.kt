package io.snailrun.domain.coach

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A distance and the day it is run. Optional throughout: no goal is a valid state. */
data class RaceGoal(val distanceMeters: Int, val date: LocalDate)

/**
 * Where a week sits relative to the race.
 *
 * The boundaries are conventional rather than derived, and worth stating plainly: a
 * build phase longer than about twelve weeks stops building and starts wearing out, and
 * a taper shorter than two weeks does not give the legs time to take up what was done to
 * them.
 */
enum class Phase(val label: String) {
    Base("Base"),
    Build("Build"),
    Peak("Peak"),
    Taper("Taper"),
}

object Races {

    /** What the settings screen offers. Matches `BestEffortFinder.StandardDistances`. */
    val Distances = listOf(5_000, 10_000, 21_097, 42_195)

    fun phaseFor(goal: RaceGoal, weekStart: LocalDate): Phase {
        val weeks = ChronoUnit.WEEKS.between(weekStart, goal.date)
        return when {
            weeks > 12 -> Phase.Base
            weeks > 4 -> Phase.Build
            weeks > 2 -> Phase.Peak
            else -> Phase.Taper
        }
    }

    fun weeksTo(goal: RaceGoal, weekStart: LocalDate): Long =
        ChronoUnit.WEEKS.between(weekStart, goal.date)

    /**
     * Volume multiplier for the phase.
     *
     * Only the taper moves it. The other three phases change what the hard days are, not
     * how much is run — a base week and a build week of the same volume differ in where
     * the effort goes, and pretending otherwise is how a plan quietly ramps twice.
     *
     * The taper cuts volume and leaves the intensity alone. Cutting both is the classic
     * mistake: the runner arrives rested and flat, having spent two weeks forgetting what
     * race pace feels like.
     */
    fun volumeFactor(goal: RaceGoal, weekStart: LocalDate): Double =
        if (phaseFor(goal, weekStart) != Phase.Taper) {
            1.0
        } else {
            if (weeksTo(goal, weekStart) <= 0) 0.4 else 0.6
        }

    /**
     * What the goal distance would take at today's fitness.
     *
     * Presented as a reading of the runner's current form, never as a target: it assumes
     * the day goes well, the course is flat and nothing between now and then changes,
     * and exactly one of those is usually true.
     */
    fun predictedTimeMs(goal: RaceGoal, fitness: FitnessEstimate?): Long? =
        fitness?.let { Vdot.timeMsFor(it.vdot, goal.distanceMeters.toDouble()) }
}

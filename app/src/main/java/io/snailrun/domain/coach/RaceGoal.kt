package io.snailrun.domain.coach

import java.time.LocalDate

/** A distance and the day it is run. Optional throughout: no goal is a valid state. */
data class RaceGoal(val distanceMeters: Int, val date: LocalDate)

object Races {

    /** What the settings screen offers. Matches `BestEffortFinder.StandardDistances`. */
    val Distances = listOf(5_000, 10_000, 21_097, 42_195)

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

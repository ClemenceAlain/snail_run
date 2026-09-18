package io.snailrun.domain.coach

import io.snailrun.domain.analysis.DayTotal
import io.snailrun.domain.analysis.Progress
import io.snailrun.domain.analysis.ProgressPeriod
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** One finished run, reduced to what the coach needs of it. */
data class CoachRun(
    val date: LocalDate,
    val meters: Double,
    val movingMs: Long,
)

/**
 * What the runner has actually been doing, which is what decides what they can be asked
 * to do next.
 *
 * [ratio] is acute over chronic: the last seven days against the average of the last
 * twenty-eight. It is the single number that separates a sensible build from a spike,
 * and it is the reason the planner never simply adds ten per cent to last week — a
 * runner who doubled last week would have that doubling compounded.
 */
data class LoadSummary(
    val acuteMeters: Double,
    val chronicWeeklyMeters: Double,
    val ratio: Double?,
    val runsPerWeek: Int,
    val longestRunMeters: Double,
    val daysSinceLastRun: Int?,
    val runsInLast28Days: Int,
    /** Consecutive weeks each bigger than the one before it. Three is a cutback. */
    val risingWeeks: Int,
    /** Where their long run actually falls, so the plan fits the week they already have. */
    val longRunDay: DayOfWeek?,
    val runDayFrequency: Map<DayOfWeek, Int>,
)

object TrainingLoad {

    const val ACUTE_DAYS = 7L
    const val CHRONIC_DAYS = 28L

    fun summarise(
        runs: List<CoachRun>,
        today: LocalDate,
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    ): LoadSummary {
        val past = runs.filter { it.date <= today }
        val acuteFrom = today.minusDays(ACUTE_DAYS - 1)
        val chronicFrom = today.minusDays(CHRONIC_DAYS - 1)

        val acute = past.filter { it.date >= acuteFrom }
        val chronic = past.filter { it.date >= chronicFrom }

        val acuteMeters = acute.sumOf { it.meters }
        val chronicWeekly = chronic.sumOf { it.meters } / (CHRONIC_DAYS / ACUTE_DAYS)

        // Frequency counts days, not runs: two runs in one day is one day of loading on
        // the legs, and it is the days that decide how a week can be laid out.
        val activeDays = chronic.map { it.date }.distinct()

        return LoadSummary(
            acuteMeters = acuteMeters,
            chronicWeeklyMeters = chronicWeekly,
            ratio = if (chronicWeekly <= 0.0) null else acuteMeters / chronicWeekly,
            runsPerWeek = (activeDays.size / (CHRONIC_DAYS.toDouble() / ACUTE_DAYS)).roundToInt(),
            longestRunMeters = chronic.maxOfOrNull { it.meters } ?: 0.0,
            daysSinceLastRun = past.maxOfOrNull { it.date }
                ?.let { ChronoUnit.DAYS.between(it, today).toInt() },
            runsInLast28Days = chronic.size,
            risingWeeks = risingWeeks(past, today, firstDayOfWeek),
            longRunDay = longRunDay(chronic),
            runDayFrequency = activeDays.groupingBy { it.dayOfWeek }.eachCount(),
        )
    }

    /**
     * How many weeks in a row have been bigger than the week before.
     *
     * The current week is excluded: it is partial, so it is always smaller than the one
     * before it and would reset the count every Monday. Built on [Progress.buckets] so
     * the weeks break exactly where the progress chart's bars do, and a cutback lands on
     * the week the runner can see rising.
     */
    private fun risingWeeks(
        runs: List<CoachRun>,
        today: LocalDate,
        firstDayOfWeek: DayOfWeek,
    ): Int {
        val totals = runs.groupBy { it.date }.map { (date, sameDay) ->
            DayTotal(
                date = date,
                runCount = sameDay.size,
                meters = sameDay.sumOf { it.meters },
                movingMs = sameDay.sumOf { it.movingMs },
            )
        }
        val weeks = Progress.buckets(
            totals = totals,
            period = ProgressPeriod.Week,
            count = 6,
            endingOn = today.minusWeeks(1),
            firstDayOfWeek = firstDayOfWeek,
        )

        var rising = 0
        for (i in weeks.indices.reversed()) {
            if (i == 0) break
            if (weeks[i].meters > weeks[i - 1].meters && weeks[i - 1].meters > 0.0) rising++ else break
        }
        return rising
    }

    /**
     * The day their longest runs land on, by total distance rather than by count — one
     * 20 km Sunday says more about where the long run lives than three 5 km Tuesdays.
     * Null when nothing has been run.
     */
    private fun longRunDay(runs: List<CoachRun>): DayOfWeek? {
        if (runs.isEmpty()) return null
        return runs
            .groupBy { it.date.dayOfWeek }
            .mapValues { (_, sameDay) -> sameDay.maxOf { it.meters } }
            .maxByOrNull { it.value }
            ?.key
    }
}

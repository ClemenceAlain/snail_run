package io.snailrun.domain.analysis

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class ProgressPeriod { Week, Month }

/** One bar: a week or a month, and what was run in it. */
data class ProgressBucket(
    val start: LocalDate,
    val end: LocalDate,
    val meters: Double,
    val movingMs: Long,
    val runCount: Int,
)

/**
 * Turns days of running into the weeks or months a progress chart is made of.
 *
 * Empty periods are kept rather than dropped. A gap is the most useful thing a progress
 * chart shows, and a chart that closes over its gaps says the opposite of what happened.
 *
 * Periods run to the end of the one containing [endingOn], so the current week or month
 * is the last bar and is visibly partial rather than looking like a collapse in form.
 */
object Progress {

    fun buckets(
        totals: List<DayTotal>,
        period: ProgressPeriod,
        count: Int,
        endingOn: LocalDate,
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    ): List<ProgressBucket> {
        if (count <= 0) return emptyList()

        val lastStart = startOf(endingOn, period, firstDayOfWeek)
        return (count - 1 downTo 0).map { back ->
            val start = when (period) {
                ProgressPeriod.Week -> lastStart.minusWeeks(back.toLong())
                ProgressPeriod.Month -> lastStart.minusMonths(back.toLong())
            }
            val end = when (period) {
                ProgressPeriod.Week -> start.plusDays(6)
                ProgressPeriod.Month -> start.with(TemporalAdjusters.lastDayOfMonth())
            }
            val days = totals.filter { it.date >= start && it.date <= end }
            ProgressBucket(
                start = start,
                end = end,
                meters = days.sumOf { it.meters },
                movingMs = days.sumOf { it.movingMs },
                runCount = days.sumOf { it.runCount },
            )
        }
    }

    /**
     * The trend line over the bars: a trailing mean across [window] periods.
     *
     * Null until there are enough periods behind a bar to mean anything. A partial mean
     * drawn as a full one reads as a slump in the first weeks of every chart.
     */
    fun trend(buckets: List<ProgressBucket>, window: Int = 4): List<Double?> =
        buckets.indices.map { i ->
            if (i + 1 < window) null
            else buckets.subList(i + 1 - window, i + 1).sumOf { it.meters } / window
        }

    private fun startOf(date: LocalDate, period: ProgressPeriod, firstDayOfWeek: DayOfWeek) =
        when (period) {
            ProgressPeriod.Week -> date.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
            ProgressPeriod.Month -> date.withDayOfMonth(1)
        }
}

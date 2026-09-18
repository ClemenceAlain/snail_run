package io.snailrun.domain.analysis

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** What one day of the month adds up to. Days with no run are simply absent. */
data class DayTotal(
    val date: LocalDate,
    val runCount: Int,
    val meters: Double,
    val movingMs: Long,
)

/**
 * One cell of the grid. [date] is null for the blanks that pad the first and last weeks,
 * which are cells the layout needs and the reader should not see.
 */
data class CalendarCell(
    val date: LocalDate?,
    val runCount: Int = 0,
    val meters: Double = 0.0,
    val movingMs: Long = 0,
) {
    val hasRun: Boolean get() = runCount > 0
}

data class CalendarMonth(
    val yearMonth: YearMonth,
    /** Seven cells per row, first row padded to the week's first day. */
    val weeks: List<List<CalendarCell>>,
    /** Column headings, already in the order the weeks are laid out. */
    val dayOfWeekOrder: List<DayOfWeek>,
    val runCount: Int,
    val activeDays: Int,
    val meters: Double,
    val movingMs: Long,
    /** The busiest day, for scaling how strongly each cell is tinted. */
    val busiestDayMeters: Double,
)

/**
 * Lays a month out as weeks.
 *
 * The first day of the week is a parameter rather than a constant: it is Monday across
 * most of Europe and Sunday across much of the rest, and a calendar that starts on the
 * wrong day is read wrong at a glance rather than noticed and corrected.
 *
 * Totals are summed from the days actually handed in, so a month with no runs is a valid
 * month and not an error. One entry per date is expected — the query that feeds this
 * groups by day already.
 */
object RunCalendar {

    fun build(
        yearMonth: YearMonth,
        totals: List<DayTotal>,
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    ): CalendarMonth {
        val byDate = totals.associateBy { it.date }
        val length = yearMonth.lengthOfMonth()

        // How many blanks before the first: the distance round the week from the week's
        // first day to the day the month happens to start on.
        val leading = Math.floorMod(
            yearMonth.atDay(1).dayOfWeek.value - firstDayOfWeek.value,
            7,
        )

        val cells = buildList {
            repeat(leading) { add(CalendarCell(date = null)) }
            (1..length).forEach { day ->
                val date = yearMonth.atDay(day)
                val total = byDate[date]
                add(
                    CalendarCell(
                        date = date,
                        runCount = total?.runCount ?: 0,
                        meters = total?.meters ?: 0.0,
                        movingMs = total?.movingMs ?: 0,
                    )
                )
            }
            while (size % 7 != 0) add(CalendarCell(date = null))
        }

        val inMonth = totals.filter { YearMonth.from(it.date) == yearMonth }
        return CalendarMonth(
            yearMonth = yearMonth,
            weeks = cells.chunked(7),
            dayOfWeekOrder = (0..6).map { firstDayOfWeek.plus(it.toLong()) },
            runCount = inMonth.sumOf { it.runCount },
            activeDays = inMonth.count { it.runCount > 0 },
            meters = inMonth.sumOf { it.meters },
            movingMs = inMonth.sumOf { it.movingMs },
            busiestDayMeters = inMonth.maxOfOrNull { it.meters } ?: 0.0,
        )
    }
}

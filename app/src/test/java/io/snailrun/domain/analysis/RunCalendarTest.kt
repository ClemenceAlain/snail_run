package io.snailrun.domain.analysis

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunCalendarTest {

    private fun day(date: String, km: Double, count: Int = 1) = DayTotal(
        date = LocalDate.parse(date),
        runCount = count,
        meters = km * 1000,
        movingMs = (km * 330_000).toLong(),
    )

    @Test
    fun `a month starting midweek is padded to the first day of the week`() {
        // 1 September 2026 is a Tuesday.
        val month = RunCalendar.build(YearMonth.of(2026, 9), emptyList(), DayOfWeek.MONDAY)

        assertNull(month.weeks.first()[0].date)
        assertEquals(LocalDate.of(2026, 9, 1), month.weeks.first()[1].date)
        assertEquals(DayOfWeek.MONDAY, month.dayOfWeekOrder.first())
    }

    @Test
    fun `the same month laid out from Sunday shifts by a day`() {
        val month = RunCalendar.build(YearMonth.of(2026, 9), emptyList(), DayOfWeek.SUNDAY)

        assertEquals(LocalDate.of(2026, 9, 1), month.weeks.first()[2].date)
        assertEquals(DayOfWeek.SUNDAY, month.dayOfWeekOrder.first())
    }

    @Test
    fun `every week has seven cells and every day of the month appears once`() {
        val month = RunCalendar.build(YearMonth.of(2026, 2), emptyList())

        assertTrue(month.weeks.all { it.size == 7 })
        val dates = month.weeks.flatten().mapNotNull { it.date }
        assertEquals(28, dates.size)
        assertEquals(dates.distinct(), dates)
    }

    @Test
    fun `totals cover the month and name its busiest day`() {
        val month = RunCalendar.build(
            YearMonth.of(2026, 9),
            // One entry per date, as the query that feeds this groups by day.
            listOf(day("2026-09-02", 5.0), day("2026-09-05", 15.0, count = 2)),
        )

        assertEquals(3, month.runCount)
        assertEquals(2, month.activeDays)
        assertEquals(20_000.0, month.meters, 0.001)
        assertEquals(15_000.0, month.busiestDayMeters, 0.001)
    }

    @Test
    fun `days outside the month are ignored rather than counted`() {
        val month = RunCalendar.build(
            YearMonth.of(2026, 9),
            listOf(day("2026-08-31", 10.0), day("2026-09-01", 4.0), day("2026-10-01", 8.0)),
        )

        assertEquals(1, month.runCount)
        assertEquals(4_000.0, month.meters, 0.001)
    }

    @Test
    fun `a month with no runs is a month, not an error`() {
        val month = RunCalendar.build(YearMonth.of(2026, 9), emptyList())

        assertEquals(0, month.runCount)
        assertEquals(0.0, month.busiestDayMeters, 0.0)
        assertTrue(month.weeks.isNotEmpty())
    }
}

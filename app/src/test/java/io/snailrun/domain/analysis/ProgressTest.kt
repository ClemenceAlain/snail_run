package io.snailrun.domain.analysis

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressTest {

    private fun day(date: String, km: Double) = DayTotal(
        date = LocalDate.parse(date),
        runCount = 1,
        meters = km * 1000,
        movingMs = (km * 330_000).toLong(),
    )

    // Friday 18 September 2026.
    private val today = LocalDate.of(2026, 9, 18)

    @Test
    fun `the last bucket is the week the given day falls in`() {
        val buckets = Progress.buckets(emptyList(), ProgressPeriod.Week, 4, today, DayOfWeek.MONDAY)

        assertEquals(4, buckets.size)
        assertEquals(LocalDate.of(2026, 9, 14), buckets.last().start)
        assertEquals(LocalDate.of(2026, 9, 20), buckets.last().end)
        assertEquals(LocalDate.of(2026, 8, 24), buckets.first().start)
    }

    @Test
    fun `a week sums the days inside it and ignores the ones outside`() {
        val buckets = Progress.buckets(
            listOf(
                day("2026-09-13", 10.0),  // the Sunday before: previous week
                day("2026-09-14", 5.0),
                day("2026-09-17", 8.0),
                day("2026-09-21", 12.0),  // next week
            ),
            ProgressPeriod.Week,
            count = 2,
            endingOn = today,
            firstDayOfWeek = DayOfWeek.MONDAY,
        )

        assertEquals(10_000.0, buckets[0].meters, 0.001)
        assertEquals(13_000.0, buckets[1].meters, 0.001)
        assertEquals(2, buckets[1].runCount)
    }

    @Test
    fun `weeks follow the locale's first day`() {
        val sundayFirst =
            Progress.buckets(emptyList(), ProgressPeriod.Week, 1, today, DayOfWeek.SUNDAY)

        assertEquals(LocalDate.of(2026, 9, 13), sundayFirst.single().start)
        assertEquals(LocalDate.of(2026, 9, 19), sundayFirst.single().end)
    }

    @Test
    fun `months run from the first to the last day, whatever their length`() {
        val buckets = Progress.buckets(
            listOf(day("2026-02-28", 6.0), day("2026-03-01", 9.0)),
            ProgressPeriod.Month,
            count = 8,
            endingOn = today,
        )

        assertEquals(8, buckets.size)
        assertEquals(LocalDate.of(2026, 2, 1), buckets[0].start)
        assertEquals(LocalDate.of(2026, 2, 28), buckets[0].end)
        assertEquals(6_000.0, buckets[0].meters, 0.001)
        assertEquals(9_000.0, buckets[1].meters, 0.001)
        assertEquals(LocalDate.of(2026, 9, 30), buckets.last().end)
    }

    @Test
    fun `a week with no runs is kept as an empty bar`() {
        val buckets = Progress.buckets(
            listOf(day("2026-09-01", 5.0)),
            ProgressPeriod.Week,
            count = 4,
            endingOn = today,
        )

        assertEquals(4, buckets.size)
        assertEquals(0.0, buckets.last().meters, 0.0)
        assertEquals(0, buckets.last().runCount)
    }

    @Test
    fun `the trend says nothing until it has a full window`() {
        val buckets = Progress.buckets(
            (1..6).map { day("2026-08-%02d".format(it * 3), it * 2.0) },
            ProgressPeriod.Week,
            count = 6,
            endingOn = today,
        )

        val trend = Progress.trend(buckets, window = 4)

        assertEquals(6, trend.size)
        assertNull(trend[0])
        assertNull(trend[2])
        assertEquals(buckets.take(4).sumOf { it.meters } / 4, trend[3]!!, 0.001)
    }

    @Test
    fun `asking for no periods gives no bars rather than an error`() {
        assertEquals(emptyList<ProgressBucket>(), Progress.buckets(emptyList(), ProgressPeriod.Week, 0, today))
    }
}

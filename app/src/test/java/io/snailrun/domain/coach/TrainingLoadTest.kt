package io.snailrun.domain.coach

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingLoadTest {

    /** A Friday, so a Monday-start week is four days old and visibly partial. */
    private val today = LocalDate.of(2026, 9, 18)

    private fun run(daysAgo: Long, km: Double) =
        CoachRun(today.minusDays(daysAgo), km * 1000, (km * 5.5 * 60_000).toLong())

    @Test
    fun `an empty history summarises to nothing rather than to zero fitness`() {
        val load = TrainingLoad.summarise(emptyList(), today)
        assertEquals(0.0, load.acuteMeters, 0.0)
        assertNull(load.ratio)
        assertNull(load.daysSinceLastRun)
        assertNull(load.longRunDay)
        assertEquals(0, load.runsInLast28Days)
    }

    @Test
    fun `the acute window is the last seven days inclusive of today`() {
        val load = TrainingLoad.summarise(listOf(run(0, 5.0), run(6, 5.0), run(7, 5.0)), today)
        assertEquals(10_000.0, load.acuteMeters, 0.1)
    }

    @Test
    fun `chronic is the four-week total divided by four`() {
        val runs = (0L..27L).step(7).map { run(it, 10.0) }
        val load = TrainingLoad.summarise(runs, today)
        assertEquals(10_000.0, load.chronicWeeklyMeters, 0.1)
        assertEquals(1.0, load.ratio!!, 0.01)
    }

    @Test
    fun `a doubled week shows as a ratio of two`() {
        // 24 km this week on top of three weeks of 8 km: a chronic average of 12 km.
        val runs = listOf(run(0, 24.0), run(8, 8.0), run(15, 8.0), run(22, 8.0))
        val load = TrainingLoad.summarise(runs, today)
        assertEquals(2.0, load.ratio!!, 0.01)
    }

    @Test
    fun `frequency counts days and not runs`() {
        // Two runs on each of seven days across four weeks is under two days a week,
        // not the three and a half that counting runs would give.
        val runs = (0L..27L).step(4).flatMap { listOf(run(it, 5.0), run(it, 4.0)) }
        val load = TrainingLoad.summarise(runs, today)
        assertEquals(14, load.runsInLast28Days)
        assertEquals(2, load.runsPerWeek)
    }

    @Test
    fun `the longest run is a single run and not a day's total`() {
        val load = TrainingLoad.summarise(listOf(run(3, 8.0), run(3, 8.0), run(10, 12.0)), today)
        assertEquals(12_000.0, load.longestRunMeters, 0.1)
    }

    @Test
    fun `a fortnight off shows in the gap`() {
        val load = TrainingLoad.summarise(listOf(run(15, 10.0), run(20, 10.0)), today)
        assertEquals(15, load.daysSinceLastRun)
        assertEquals(0.0, load.acuteMeters, 0.0)
    }

    @Test
    fun `the long run day is the one the longest runs land on`() {
        val sundays = (0L..3L).map { weeks ->
            CoachRun(today.minusWeeks(weeks).with(DayOfWeek.SUNDAY).minusWeeks(1), 18_000.0, 0)
        }
        val tuesdays = (0L..3L).map { weeks ->
            CoachRun(today.minusWeeks(weeks).with(DayOfWeek.TUESDAY), 6_000.0, 0)
        }
        val load = TrainingLoad.summarise(sundays + tuesdays, today)
        assertEquals(DayOfWeek.SUNDAY, load.longRunDay)
        assertTrue(load.runDayFrequency.getValue(DayOfWeek.TUESDAY) >= 3)
    }

    @Test
    fun `three weeks of rising volume are flagged`() {
        // Weeks, oldest first: 20, 25, 30, 35 km. The current week is excluded, so the
        // three complete rises behind it are what counts.
        val runs = listOf(
            run(28, 20.0), run(21, 25.0), run(14, 30.0), run(7, 35.0),
        )
        val load = TrainingLoad.summarise(runs, today, DayOfWeek.MONDAY)
        assertTrue("saw ${load.risingWeeks} rising weeks", load.risingWeeks >= 3)
    }

    @Test
    fun `a flat month is not a rise`() {
        val runs = (0L..27L).step(7).map { run(it, 10.0) }
        assertEquals(0, TrainingLoad.summarise(runs, today, DayOfWeek.MONDAY).risingWeeks)
    }
}

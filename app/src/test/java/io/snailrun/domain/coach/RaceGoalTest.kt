package io.snailrun.domain.coach

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceGoalTest {

    private val today = LocalDate.of(2026, 9, 18)
    private val weekStart = LocalDate.of(2026, 9, 21) // a Monday

    private val fitness = Fitness.estimate(
        listOf(RecentEffort(5_000, 20 * 60_000L, today.minusDays(7))),
        today,
    )!!

    private fun goal(weeksOut: Long, meters: Int = 10_000) =
        RaceGoal(meters, weekStart.plusWeeks(weeksOut))

    @Test
    fun `a faster runner is predicted a faster race`() {
        val slower = Fitness.estimate(
            listOf(RecentEffort(5_000, 24 * 60_000L, today)), today,
        )!!
        val fast = Races.predictedTimeMs(goal(6), fitness)!!
        val slow = Races.predictedTimeMs(goal(6), slower)!!
        assertTrue(fast < slow)
    }

    @Test
    fun `a twenty minute five k runner is predicted about forty-two for ten`() {
        val predicted = Races.predictedTimeMs(goal(6), fitness)!!
        assertEquals(41.6, predicted / 60_000.0, 0.7)
    }

    @Test
    fun `no fitness means no prediction rather than a round number`() {
        assertNull(Races.predictedTimeMs(goal(6), null))
    }
}

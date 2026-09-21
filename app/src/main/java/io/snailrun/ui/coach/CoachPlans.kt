package io.snailrun.ui.coach

import io.snailrun.data.db.PersonalRecord
import io.snailrun.data.db.RunEntity
import io.snailrun.data.prefs.CoachSettings
import io.snailrun.domain.coach.Baselines
import io.snailrun.domain.coach.CoachRun
import io.snailrun.domain.coach.Fitness
import io.snailrun.domain.coach.RaceGoal
import io.snailrun.domain.coach.RecentEffort
import io.snailrun.domain.coach.WeekPlan
import io.snailrun.domain.coach.WeekPlanner
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** How far ahead the block runs. Four weeks is a training cycle and fits a scroll. */
const val COACH_WEEKS = 4

/**
 * Builds the block of weeks from stored rows.
 *
 * Shared because two screens want the same answer for different reasons: the Coach tab
 * shows the block, and the Record screen wants only today's line out of it. Computing it
 * twice from the same rows would be cheap enough; computing it twice from two different
 * pieces of code is how the two screens end up disagreeing about what today's session is.
 */
object CoachPlans {

    fun block(
        runs: List<RunEntity>,
        efforts: List<PersonalRecord>,
        saved: CoachSettings,
        today: LocalDate,
        firstDayOfWeek: DayOfWeek,
        weeks: Int = COACH_WEEKS,
    ): List<WeekPlan> {
        val recorded = runs.mapNotNull { run ->
            val date = runCatching { LocalDate.parse(run.localDate) }.getOrNull()
                ?: return@mapNotNull null
            CoachRun(date = date, meters = run.distanceMeters, movingMs = run.movingTimeMs)
        }
        // What the runner told the coach about the weeks before it was installed, on the
        // days they have not since filled with a real run. They are runs like any other
        // from here on, which is what keeps one set of rules rather than two.
        val coachRuns = recorded + (
            saved.baseline?.let { Baselines.syntheticRuns(it, recorded) }.orEmpty()
            )
        val weekStart = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))

        return WeekPlanner.block(
            runs = coachRuns,
            fitness = Fitness.estimate(
                // The race they reported competes with the efforts the app has found
                // for itself, on the same terms: the best one wins, and an old one falls
                // out of the ten-week window on its own.
                efforts = efforts.map { it.toRecentEffort() } + listOfNotNull(saved.baseline?.race),
                today = today,
            ),
            goal = saved.toGoal(),
            firstWeekStart = weekStart,
            today = today,
            weeks = weeks,
            firstDayOfWeek = firstDayOfWeek,
            orders = saved.dayOrders.mapKeys { LocalDate.ofEpochDay(it.key) },
        )
    }

    fun runsSince(runs: List<RunEntity>, from: LocalDate, to: LocalDate): List<CoachRun> =
        runs.mapNotNull { run ->
            val date = runCatching { LocalDate.parse(run.localDate) }.getOrNull()
                ?: return@mapNotNull null
            CoachRun(date, run.distanceMeters, run.movingTimeMs).takeIf { date >= from && date <= to }
        }

    private fun PersonalRecord.toRecentEffort() = RecentEffort(
        distanceMeters = distanceMeters,
        durationMs = durationMs,
        date = Instant.ofEpochMilli(startedAtEpochMs).atZone(ZoneId.systemDefault()).toLocalDate(),
    )

    private fun CoachSettings.toGoal(): RaceGoal? {
        val distance = targetDistanceMeters ?: return null
        val day = targetDateEpochDay ?: return null
        return RaceGoal(distance, LocalDate.ofEpochDay(day))
    }
}

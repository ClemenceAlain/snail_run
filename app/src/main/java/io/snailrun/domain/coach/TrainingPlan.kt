package io.snailrun.domain.coach

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * The plan the runner started, as they started it.
 *
 * Four answers and a date. Everything else — which week this is, what the sessions are,
 * what pace they are written in — is worked out from these and the runner's VMA every
 * time a week is shown, so a plan never has to be migrated when a template changes.
 */
data class TrainingPlan(
    /** First day of the plan's first week. */
    val startWeek: LocalDate,
    /** Null is a plan with no race: a four-week cycle that repeats. */
    val race: RaceGoal?,
    /** What the runner wants to run the race in. Null means "what I am worth today". */
    val targetTimeMs: Long?,
    /** Two, three or four. Three is the Decathlon week the templates are written for. */
    val sessionsPerWeek: Int,
    val createdOn: LocalDate,
) {
    companion object {
        const val MIN_SESSIONS = 2
        const val MAX_SESSIONS = 4

        /** How far ahead a plan with no race can be looked at. Half a year. */
        const val OPEN_WEEKS = 26

        /**
         * The first week of a plan started on [today].
         *
         * This week if it has barely begun, otherwise the next. A plan started on a
         * Thursday whose Tuesday session is already behind it starts with a missed
         * session, and that is a bad first impression for something meant to be followed.
         */
        fun startWeekFor(today: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate {
            val thisWeek = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
            val dayInWeek = ChronoUnit.DAYS.between(thisWeek, today)
            return if (dayInWeek <= 1) thisWeek else thisWeek.plusWeeks(1)
        }
    }
}

/** What a week is for. Shown beside the week number. */
enum class WeekKind(val label: String) {
    Base("Base"),
    Build("Build"),
    Light("Lighter week"),
    Taper("Taper"),
    Race("Race week"),
}

/** Where one week sits in the plan. */
data class PlanPosition(
    /** 1 for the plan's first week. */
    val number: Int,
    /** How many weeks the plan has, or null for a plan with no race. */
    val of: Int?,
    val kind: WeekKind,
    /** Which template this week is drawn from, and which of its weeks. */
    val template: PlanTemplate,
    val templateWeek: Int,
) {
    val label: String
        get() = buildString {
            append("Week $number")
            of?.let { append(" of $it") }
            append(" · ${kind.label}")
        }
}

/**
 * Which week of which template a calendar week is.
 *
 * Templates are fixed lengths — eight weeks for a 5 or 10 km, twelve for a half, sixteen
 * for a marathon — and race dates are not, so the two have to be fitted together:
 *
 * - **More time than the template**: four-week base cycles go in front of it. The last
 *   base week before the template is always a lighter one, so the block starts rested.
 * - **Less time than the template**: its first weeks are skipped. The taper and the race
 *   week are the part that cannot be done without, and they are always kept.
 * - **No race**: the 10 km cycle repeats for as long as the runner keeps going.
 */
object PlanSchedule {

    fun position(plan: TrainingPlan, weekStart: LocalDate): PlanPosition? {
        val index = ChronoUnit.WEEKS.between(plan.startWeek, weekStart).toInt()
        if (weekStart.isBefore(plan.startWeek)) return null
        val race = plan.race
            ?: return if (index >= TrainingPlan.OPEN_WEEKS) null else openPosition(index)

        val total = weeksIn(plan) ?: return null
        if (index >= total) return null
        val template = PlanTemplates.forDistance(race.distanceMeters)
        val length = template.weeks.size

        if (total >= length) {
            val base = total - length
            if (index < base) {
                // Counted back from the template, so the week just before it is the
                // lighter one whatever the number of base weeks.
                val untilTemplate = base - index
                val cycleWeek = 4 - ((untilTemplate - 1) % 4)
                val week = PlanTemplates.Cycle.weeks[cycleWeek - 1]
                return PlanPosition(
                    number = index + 1,
                    of = total,
                    kind = if (week.kind == WeekKind.Light) WeekKind.Light else WeekKind.Base,
                    template = PlanTemplates.Cycle,
                    templateWeek = cycleWeek,
                )
            }
            val templateWeek = index - base + 1
            return PlanPosition(index + 1, total, template.weeks[templateWeek - 1].kind, template, templateWeek)
        }

        val templateWeek = index + (length - total) + 1
        return PlanPosition(index + 1, total, template.weeks[templateWeek - 1].kind, template, templateWeek)
    }

    /** How many weeks a plan with a race has, its race week included. */
    fun weeksIn(plan: TrainingPlan): Int? {
        val race = plan.race ?: return null
        if (race.date.isBefore(plan.startWeek)) return null
        return (ChronoUnit.DAYS.between(plan.startWeek, race.date) / 7).toInt() + 1
    }

    /** The first day of the plan's last week: the race week, or half a year out. */
    fun lastWeek(plan: TrainingPlan): LocalDate {
        val weeks = weeksIn(plan) ?: TrainingPlan.OPEN_WEEKS
        return plan.startWeek.plusWeeks((weeks - 1).toLong().coerceAtLeast(0))
    }

    /**
     * The 10 km block's first four weeks, then its second four, then again.
     *
     * Weeks one to three build and week four lightens; the second time round, weeks five
     * to seven take their place, so the reps get longer rather than the same month being
     * run on a loop.
     */
    private fun openPosition(index: Int): PlanPosition {
        val cycle = index / 4
        val inCycle = index % 4
        val templateWeek = when {
            inCycle == 3 -> 4
            cycle % 2 == 0 -> inCycle + 1
            else -> inCycle + 5
        }
        val week = PlanTemplates.TenK.weeks[templateWeek - 1]
        return PlanPosition(
            number = index + 1,
            of = null,
            kind = if (week.kind == WeekKind.Light) WeekKind.Light else WeekKind.Build,
            template = PlanTemplates.TenK,
            templateWeek = templateWeek,
        )
    }
}

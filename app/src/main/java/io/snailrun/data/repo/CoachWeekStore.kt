package io.snailrun.data.repo

import io.snailrun.data.db.CoachWeekDao
import io.snailrun.data.db.CoachWeekEntity
import io.snailrun.domain.coach.WeekPlan
import java.time.Clock
import java.time.LocalDate

/**
 * The coach's past, one week at a time.
 *
 * The current week is saved every time it is planned and compared first, so planning it
 * again with nothing changed writes nothing. Once the week is over nobody plans it again,
 * and the row left behind is the week as the runner was given it.
 */
class CoachWeekStore(private val dao: CoachWeekDao, private val clock: Clock) {

    suspend fun saveIfChanged(week: WeekPlan) {
        val body = CoachWeekCodec.encode(week)
        val day = week.weekStart.toEpochDay()
        if (dao.week(day)?.body == body) return
        dao.upsert(CoachWeekEntity(day, body, clock.millis()))
    }

    /** Saved weeks that started before [before], most recent first. */
    suspend fun weekStartsBefore(before: LocalDate): List<LocalDate> =
        dao.weekStartsBefore(before.toEpochDay()).map(LocalDate::ofEpochDay)

    /** A saved week, with its days marked done from [ranOn]. */
    suspend fun load(weekStart: LocalDate, ranOn: Set<LocalDate>): WeekPlan? {
        val week = dao.week(weekStart.toEpochDay())?.body?.let(CoachWeekCodec::decode) ?: return null
        return week.copy(days = week.days.map { it.copy(done = it.date in ranOn) })
    }

    /** A new plan replaces the current week and anything after it. The past stays. */
    suspend fun deleteFrom(weekStart: LocalDate) = dao.deleteFrom(weekStart.toEpochDay())
}

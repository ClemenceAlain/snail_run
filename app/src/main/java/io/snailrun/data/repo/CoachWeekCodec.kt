package io.snailrun.data.repo

import io.snailrun.domain.coach.PlanPosition
import io.snailrun.domain.coach.PlanTemplates
import io.snailrun.domain.coach.PlannedDay
import io.snailrun.domain.coach.SegmentKind
import io.snailrun.domain.coach.WeekKind
import io.snailrun.domain.coach.WeekPlan
import io.snailrun.domain.coach.Workout
import io.snailrun.domain.coach.WorkoutStep
import io.snailrun.domain.coach.WorkoutType
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/**
 * A planned week as JSON, for [io.snailrun.data.db.CoachWeekEntity].
 *
 * `org.json` because Android ships it and a week is a tree: seven days, a handful of
 * steps each, a dozen optional numbers per step. Anything the reader does not recognise
 * falls back rather than throws, because a saved week is history and a newer build that
 * cannot read an older one should still show it.
 *
 * Not stored: whether a day is done, which is read off the runs every time, so a run
 * typed in later still ticks the day it was run on.
 */
object CoachWeekCodec {

    private const val VERSION = 1

    fun encode(week: WeekPlan): String = JSONObject().apply {
        put("v", VERSION)
        put("weekStart", week.weekStart.toEpochDay())
        put("note", week.note)
        week.order?.let { put("order", JSONArray(it)) }
        put("conflicts", JSONArray(week.conflicts))
        week.position?.let { put("position", position(it)) }
        put("days", JSONArray(week.days.map(::day)))
    }.toString()

    fun decode(raw: String): WeekPlan? = runCatching {
        val json = JSONObject(raw)
        val days = json.getJSONArray("days").objects().map(::readDay)
        WeekPlan(
            weekStart = LocalDate.ofEpochDay(json.getLong("weekStart")),
            days = days,
            note = json.optString("note"),
            order = json.optJSONArray("order")?.let { a -> (0 until a.length()).map(a::getInt) },
            conflicts = json.optJSONArray("conflicts")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(),
            position = json.optJSONObject("position")?.let(::readPosition),
            frozen = true,
        )
    }.getOrNull()

    private fun position(p: PlanPosition) = JSONObject().apply {
        put("number", p.number)
        p.of?.let { put("of", it) }
        put("kind", p.kind.name)
        put("template", p.template.name)
        put("templateWeek", p.templateWeek)
    }

    private fun readPosition(o: JSONObject): PlanPosition? {
        val template = PlanTemplates.byName(o.optString("template")) ?: return null
        return PlanPosition(
            number = o.getInt("number"),
            of = o.optIntOrNull("of"),
            kind = runCatching { WeekKind.valueOf(o.getString("kind")) }.getOrDefault(WeekKind.Build),
            template = template,
            templateWeek = o.getInt("templateWeek"),
        )
    }

    private fun day(d: PlannedDay) = JSONObject().apply {
        put("date", d.date.toEpochDay())
        put("workout", workout(d.workout))
        d.strength?.let { put("strength", workout(it)) }
    }

    private fun readDay(o: JSONObject) = PlannedDay(
        date = LocalDate.ofEpochDay(o.getLong("date")),
        workout = readWorkout(o.getJSONObject("workout")),
        strength = o.optJSONObject("strength")?.let(::readWorkout),
    )

    private fun workout(w: Workout) = JSONObject().apply {
        put("type", w.type.name)
        put("totalMeters", w.totalMeters)
        put("qualityMeters", w.qualityMeters)
        put("reason", w.reason)
        w.estimatedMs?.let { put("estimatedMs", it) }
        w.title?.let { put("title", it) }
        w.tip?.let { put("tip", it) }
        put("steps", JSONArray(w.steps.map(::step)))
    }

    private fun readWorkout(o: JSONObject) = Workout(
        type = runCatching { WorkoutType.valueOf(o.getString("type")) }.getOrDefault(WorkoutType.Easy),
        totalMeters = o.optDouble("totalMeters", 0.0),
        steps = o.optJSONArray("steps")?.objects()?.map(::readStep).orEmpty(),
        reason = o.optString("reason"),
        qualityMeters = o.optDouble("qualityMeters", 0.0),
        estimatedMs = o.optLongOrNull("estimatedMs"),
        title = o.optStringOrNull("title"),
        tip = o.optStringOrNull("tip"),
    )

    private fun step(s: WorkoutStep) = JSONObject().apply {
        put("label", s.label)
        put("repeats", s.repeats)
        s.distanceM?.let { put("distanceM", it) }
        s.durationMs?.let { put("durationMs", it) }
        s.paceSecPerKm?.let { put("paceLow", it.start); put("paceHigh", it.endInclusive) }
        s.recoveryMs?.let { put("recoveryMs", it) }
        s.recoveryM?.let { put("recoveryM", it) }
        s.recoveryPaceSecPerKm?.let { put("recoveryPaceLow", it.start); put("recoveryPaceHigh", it.endInclusive) }
        s.countPerSet?.let { put("countPerSet", it) }
        if (s.perSide) put("perSide", true)
        s.kind?.let { put("kind", it.name) }
        s.intensity?.let { put("intensity", it) }
        s.why?.let { put("why", it) }
    }

    private fun readStep(o: JSONObject) = WorkoutStep(
        label = o.optString("label"),
        repeats = o.optInt("repeats", 1),
        distanceM = o.optDoubleOrNull("distanceM"),
        durationMs = o.optLongOrNull("durationMs"),
        paceSecPerKm = o.range("paceLow", "paceHigh"),
        recoveryMs = o.optLongOrNull("recoveryMs"),
        recoveryM = o.optDoubleOrNull("recoveryM"),
        recoveryPaceSecPerKm = o.range("recoveryPaceLow", "recoveryPaceHigh"),
        countPerSet = o.optIntOrNull("countPerSet"),
        perSide = o.optBoolean("perSide", false),
        kind = o.optStringOrNull("kind")?.let { k -> runCatching { SegmentKind.valueOf(k) }.getOrNull() },
        intensity = o.optStringOrNull("intensity"),
        why = o.optStringOrNull("why"),
    )

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key)) getString(key) else null
    private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key)) getLong(key) else null
    private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key)) getInt(key) else null
    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key)) getDouble(key) else null

    private fun JSONObject.range(low: String, high: String): ClosedFloatingPointRange<Double>? {
        val a = optDoubleOrNull(low) ?: return null
        val b = optDoubleOrNull(high) ?: return null
        return a..b
    }
}

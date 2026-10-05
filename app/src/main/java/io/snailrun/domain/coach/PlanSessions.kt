package io.snailrun.domain.coach

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * What a template session needs to be priced for one runner.
 *
 * [specificPaceSecPerKm] is the race pace the template's race-pace sessions are run at:
 * the runner's target time if they gave one for this distance, today's prediction if
 * not, and null when there is nothing to predict from.
 */
data class SessionContext(
    val basis: PaceBasis,
    val specificPaceSecPerKm: Double?,
    /** "10 km pace". */
    val specificName: String,
    /** "your 45:00 target" or "your predicted 10 km, 46:12". Empty with no pace. */
    val specificSource: String,
    /** The race's distance, for the race-day session. */
    val raceDistanceM: Int?,
    /**
     * Shorter sessions for a runner under [SLOW_VMA_KMH]. The templates are written for a
     * runner who already runs 10 km in under 45 minutes, and a 75-minute long run is a
     * very different day at 7:30/km than at 5:30.
     */
    val scaled: Boolean,
) {
    companion object {
        const val SLOW_VMA_KMH = 12.0
    }
}

/**
 * Turns a [SessionSpec] into a [Workout] a runner can be counted through.
 *
 * Every quality session is framed the way the Decathlon plans frame them: twenty minutes
 * of easy running, five of drills and three strides before, ten easy minutes after. And
 * every step carries its own explanation — what the pace is a percentage of, why the
 * recovery is the length it is — because a number with no reason beside it is a number
 * the runner will round to whatever feels right on the day.
 */
object PlanSessions {

    private const val MIN = 60_000L

    fun build(spec: SessionSpec, ctx: SessionContext): Workout = when (spec) {
        is SessionSpec.VmaReps -> vmaReps(spec, ctx)
        is SessionSpec.Threshold -> threshold(spec, ctx)
        is SessionSpec.RacePace -> racePace(spec, ctx)
        is SessionSpec.Easy -> easy(spec, ctx)
        is SessionSpec.VmaTest -> vmaTest(spec, ctx)
        is SessionSpec.Race -> race(spec, ctx)
    }

    // ---- sessions ----------------------------------------------------------------------

    private fun vmaReps(spec: SessionSpec.VmaReps, ctx: SessionContext): Workout {
        val reps = scaleReps(spec.reps, ctx)
        val band = ctx.basis.vmaBand(spec.pct)
        val intensity = "${CoachText.percentRange(spec.pct)} VMA"
        val repMeters = spec.repM ?: Round.repMeters(
            Workouts.metersAt(band?.let(::mid) ?: ctx.basis.paces.intervalSecPerKm, spec.repMs ?: 0L)
        )
        val repText = spec.repM?.let(CoachText::distance) ?: CoachText.duration(spec.repMs ?: 0L)
        val recoveryText = spec.recoveryM?.let { "${CoachText.distance(it)} jog" }
            ?: spec.recoveryMs?.let { "${CoachText.duration(it)} jog" }
            ?: "jog"

        val why = buildString {
            append("$reps × $repText at $intensity")
            if (band != null) {
                append(": ${CoachText.paceRange(band)} from your ${CoachText.kmh(ctx.basis.vmaKmh!!)} VMA. ")
            } else {
                append(". No VMA yet, so run them by feel: fast, and as fast on the last as on the first. ")
            }
            append("Short reps at or just above VMA are what raises it. The $recoveryText is short ")
            append("on purpose, so your heart rate stays high from one rep to the next.")
        }
        val fast = WorkoutStep(
            label = "Fast",
            repeats = reps,
            distanceM = repMeters,
            durationMs = spec.repMs,
            paceSecPerKm = band,
            recoveryM = spec.recoveryM,
            recoveryMs = spec.recoveryMs,
            recoveryPaceSecPerKm = jog(ctx),
            kind = SegmentKind.Work,
            intensity = intensity,
            why = why,
        )
        val main = if (spec.sets <= 1) {
            listOf(fast)
        } else {
            val between = WorkoutStep(
                label = "Recover",
                durationMs = spec.setRecoveryMs ?: (2 * MIN),
                paceSecPerKm = jog(ctx),
                kind = SegmentKind.Recover,
                why = "${CoachText.duration(spec.setRecoveryMs ?: (2 * MIN))} of jogging between " +
                    "the sets, so the second starts as fast as the first.",
            )
            (1..spec.sets).flatMap { set -> if (set == 1) listOf(fast) else listOf(between, fast) }
        }

        val title = when {
            spec.repMs != null && spec.recoveryMs == spec.repMs ->
                "VMA ${CoachText.seconds(spec.repMs)}/${CoachText.seconds(spec.repMs)} × $reps"
            spec.sets > 1 -> "VMA ${spec.sets} × ($reps × $repText)"
            else -> "VMA $reps × $repText"
        }
        return assemble(
            type = WorkoutType.VmaIntervals,
            title = title,
            steps = warmUp(ctx) + main + coolDown(ctx),
            reason = "VMA session. Your VMA is the speed at which you reach your maximum oxygen " +
                "uptake, and every other pace sits under it: raise it and they all get easier." +
                scaledNote(ctx, reps != spec.reps),
            tip = spec.tip,
            ctx = ctx,
        )
    }

    private fun threshold(spec: SessionSpec.Threshold, ctx: SessionContext): Workout {
        val pct = 0.80..0.85
        val band = ctx.basis.vmaBand(pct)
        val reps = spec.reps
        val repMeters = band?.let { Round.repMeters(Workouts.metersAt(mid(it), spec.repMs)) }
        val why = buildString {
            append("$reps × ${CoachText.duration(spec.repMs)} at ${CoachText.percentRange(pct)} VMA")
            if (band != null) {
                append(": ${CoachText.paceRange(band)} from your ${CoachText.kmh(ctx.basis.vmaKmh!!)} VMA. ")
            } else {
                append(", comfortably hard, by feel. ")
            }
            append("Threshold is the fastest pace you can hold for about an hour. Running just ")
            append("under it teaches your body to clear the effort as fast as it makes it. ")
            append("${CoachText.duration(spec.recoveryMs)} jogging between is enough to start again, not to rest.")
        }
        return assemble(
            type = WorkoutType.Threshold,
            title = "Threshold $reps × ${CoachText.duration(spec.repMs)}",
            steps = warmUp(ctx) + WorkoutStep(
                label = "Threshold",
                repeats = reps,
                durationMs = spec.repMs,
                distanceM = repMeters,
                paceSecPerKm = band,
                recoveryMs = spec.recoveryMs,
                recoveryPaceSecPerKm = jog(ctx),
                kind = SegmentKind.Work,
                intensity = "${CoachText.percentRange(pct)} VMA",
                why = why,
            ) + coolDown(ctx),
            reason = "Threshold session. It moves the point where running fast starts to " +
                "burn, which is what a half or a full marathon is run against.",
            tip = spec.tip,
            ctx = ctx,
        )
    }

    private fun racePace(spec: SessionSpec.RacePace, ctx: SessionContext): Workout {
        val pace = ctx.specificPaceSecPerKm
        val band = pace?.let { it..it }
        val intensity = buildString {
            append(ctx.specificName)
            val vma = ctx.basis.vmaKmh
            if (pace != null && vma != null) append(" · ${CoachText.percent(3600.0 / pace / vma)} VMA")
        }
        val equal = spec.blocksM.distinct().size == 1 && spec.recoveriesMs.distinct().size <= 1
        val describe = if (equal) {
            if (spec.blocksM.size == 1) CoachText.distance(spec.blocksM.first())
            else "${spec.blocksM.size} × ${CoachText.distance(spec.blocksM.first())}"
        } else {
            spec.blocksM.joinToString(" + ") { CoachText.distance(it) }
        }
        val why = buildString {
            append("$describe at ${ctx.specificName}")
            if (pace != null) {
                append(", ${CoachText.pace(pace)}: ${ctx.specificSource}. ")
            } else {
                append(", by feel: the pace you could hold for the whole race. ")
            }
            append("Rehearsing the race speed in pieces, longer each week, is what makes it ")
            append("feel familiar on the day.")
            spec.recoveriesMs.distinct().takeIf { it.isNotEmpty() }?.let { gaps ->
                append(" ${gaps.joinToString(" then ") { CoachText.duration(it) }} jogging between.")
            }
        }

        val main = if (equal) {
            listOf(
                WorkoutStep(
                    label = "At ${ctx.specificName}",
                    repeats = spec.blocksM.size,
                    distanceM = spec.blocksM.first(),
                    paceSecPerKm = band,
                    recoveryMs = spec.recoveriesMs.firstOrNull(),
                    recoveryPaceSecPerKm = spec.recoveriesMs.firstOrNull()?.let { jog(ctx) },
                    kind = SegmentKind.Work,
                    intensity = intensity,
                    why = why,
                ),
            )
        } else {
            spec.blocksM.flatMapIndexed { index, meters ->
                val block = WorkoutStep(
                    label = "At ${ctx.specificName}",
                    distanceM = meters,
                    paceSecPerKm = band,
                    kind = SegmentKind.Work,
                    intensity = intensity,
                    why = if (index == 0) why else null,
                )
                val gap = spec.recoveriesMs.getOrNull(index)?.let {
                    WorkoutStep(
                        label = "Recover",
                        durationMs = it,
                        paceSecPerKm = jog(ctx),
                        kind = SegmentKind.Recover,
                    )
                }
                listOfNotNull(block, gap)
            }
        }
        return assemble(
            type = WorkoutType.RacePace,
            title = "Race pace $describe",
            steps = warmUp(ctx) + main + coolDown(ctx),
            reason = "Race-pace session. The speed you want to race at, in blocks, so the " +
                "pace is in your legs before the race asks for it.",
            tip = spec.tip,
            ctx = ctx,
        )
    }

    private fun easy(spec: SessionSpec.Easy, ctx: SessionContext): Workout {
        val minutes = scaleMinutes(spec.minutes, ctx)
        val paces = ctx.basis.paces
        val label = if (spec.terrain != null) "Easy, ${spec.terrain} ground" else "Easy"
        val why = buildString {
            append("${CoachText.duration(minutes * MIN)} at easy pace, ${CoachText.paceRange(paces.easySecPerKm)}")
            append(" — about 70 % of your maximum heart rate, where you can still talk. ")
            if (spec.long) {
                append("The long run builds the endurance everything else stands on: the time ")
                append("on your feet is the session, not the speed.")
            } else {
                append("Easy running is most of the week on purpose: it builds endurance and ")
                append("lets the hard sessions land.")
            }
        }
        val steps = buildList {
            add(
                WorkoutStep(
                    label = label,
                    durationMs = minutes * MIN,
                    paceSecPerKm = paces.easySecPerKm,
                    intensity = "easy",
                    why = why,
                ),
            )
            if (spec.strides > 0) {
                add(
                    WorkoutStep(
                        label = "Strides, walk back between",
                        repeats = spec.strides,
                        distanceM = 100.0,
                        recoveryM = 100.0,
                        kind = SegmentKind.Work,
                        why = "${spec.strides} relaxed accelerations over 100 m: quick feet, " +
                            "no strain. They remind your legs of speed without tiring them.",
                    ),
                )
            }
        }
        val type = when {
            spec.long -> WorkoutType.Long
            spec.strides > 0 -> WorkoutType.Strides
            else -> WorkoutType.Easy
        }
        val title = when {
            spec.long -> "Long run ${CoachText.duration(minutes * MIN)}"
            spec.terrain != null -> "Easy ${CoachText.duration(minutes * MIN)}, ${spec.terrain}"
            spec.strides > 0 -> "Easy ${CoachText.duration(minutes * MIN)} + ${spec.strides} strides"
            else -> "Easy ${CoachText.duration(minutes * MIN)}"
        }
        return assemble(
            type = type,
            title = title,
            steps = steps,
            reason = (if (spec.long) "Long run. " else "Easy run. ") +
                "Four fifths of a good week is easy, and this is part of them." +
                scaledNote(ctx, minutes != spec.minutes),
            tip = spec.tip,
            ctx = ctx,
        )
    }

    private fun vmaTest(spec: SessionSpec.VmaTest, ctx: SessionContext): Workout {
        val steps = listOf(
            WorkoutStep(
                label = "Warm up",
                durationMs = 15 * MIN,
                paceSecPerKm = ctx.basis.paces.easySecPerKm,
                kind = SegmentKind.WarmUp,
                intensity = "easy",
                why = "Fifteen easy minutes: the test only means something on warm legs.",
            ),
            strides(),
            WorkoutStep(
                label = "VMA test",
                durationMs = Vmas.TEST_MS,
                kind = SegmentKind.Work,
                intensity = "maximum",
                why = "Six minutes, as far as you can, as evenly as you can. The distance in " +
                    "metres ÷ 100 is your VMA in km/h: 1 500 m makes 15.0 km/h. The app reads " +
                    "it off this run when you save it, and every fast pace in the plan follows.",
            ),
            coolDownStep(ctx),
        )
        return assemble(
            type = WorkoutType.VmaTest,
            title = "VMA test: 6 minutes",
            steps = steps,
            reason = "The plan's fast sessions are written as percentages of your VMA, and " +
                "you do not know yours yet. This measures it, in six minutes.",
            tip = spec.tip,
            ctx = ctx,
        )
    }

    private fun race(spec: SessionSpec.Race, ctx: SessionContext): Workout {
        val meters = (ctx.raceDistanceM ?: 10_000).toDouble()
        val pace = ctx.specificPaceSecPerKm
        val short = meters <= 10_000
        val steps = buildList {
            if (short) {
                addAll(warmUp(ctx))
            } else {
                // A marathon is its own warm-up, and twenty minutes before one is twenty
                // minutes the last ten kilometres will want back.
                add(
                    WorkoutStep(
                        label = "Warm up",
                        durationMs = 10 * MIN,
                        paceSecPerKm = ctx.basis.paces.easySecPerKm,
                        kind = SegmentKind.WarmUp,
                        intensity = "easy",
                        why = "Ten easy minutes. The first kilometres of the race do the rest.",
                    ),
                )
            }
            add(
                WorkoutStep(
                    label = "Race",
                    distanceM = meters,
                    paceSecPerKm = pace?.let { it..it },
                    kind = SegmentKind.Work,
                    intensity = ctx.specificName,
                    why = if (pace != null) {
                        "${CoachText.pace(pace)} is ${ctx.specificSource}. Run the first " +
                            "kilometre a little under it and let the pace come to you."
                    } else {
                        "No pace to give you: run by feel and keep something for the end."
                    },
                ),
            )
        }
        return assemble(
            type = WorkoutType.Race,
            title = "Race: ${CoachText.raceName(meters.toInt())}",
            steps = steps,
            reason = "Race day. Everything in the plan was for this.",
            tip = spec.tip,
            ctx = ctx,
        )
    }

    // ---- framing -----------------------------------------------------------------------

    private fun warmUp(ctx: SessionContext): List<WorkoutStep> = listOf(
        WorkoutStep(
            label = "Warm up",
            durationMs = 20 * MIN,
            paceSecPerKm = ctx.basis.paces.easySecPerKm,
            kind = SegmentKind.WarmUp,
            intensity = "easy",
            why = "Twenty easy minutes raise your heart rate and warm your muscles before " +
                "anything fast.",
        ),
        WorkoutStep(
            label = "Warm up drills",
            durationMs = 5 * MIN,
            kind = SegmentKind.WarmUp,
            why = "Five minutes of drills — skips, heel flicks, high knees — wake up the " +
                "stride the fast part will ask for.",
        ),
        strides(),
    )

    private fun strides() = WorkoutStep(
        label = "Warm up strides",
        repeats = 3,
        distanceM = 100.0,
        recoveryM = 100.0,
        kind = SegmentKind.WarmUp,
        why = "Three 100 m accelerations, walking back between, so the first rep is not the " +
            "fastest thing your legs have done today.",
    )

    private fun coolDown(ctx: SessionContext) = listOf(coolDownStep(ctx))

    private fun coolDownStep(ctx: SessionContext) = WorkoutStep(
        label = "Cool down",
        durationMs = 10 * MIN,
        paceSecPerKm = ctx.basis.paces.easySecPerKm,
        kind = SegmentKind.CoolDown,
        intensity = "easy",
        why = "Ten easy minutes bring your heart rate down and start the recovery.",
    )

    private fun assemble(
        type: WorkoutType,
        title: String,
        steps: List<WorkoutStep>,
        reason: String,
        tip: String,
        ctx: SessionContext,
    ): Workout {
        val draft = Workout(type, 0.0, steps, reason, title = title, tip = tip)
        val total = Workouts.metersOf(draft)
        val quality = steps.filter { it.kind == SegmentKind.Work }.sumOf { step ->
            val each = step.distanceM ?: step.durationMs?.let { ms ->
                step.paceSecPerKm?.let { Workouts.metersAt(mid(it), ms) }
            } ?: 0.0
            each * step.repeats.coerceAtLeast(1)
        }
        return draft.copy(
            totalMeters = total,
            qualityMeters = quality,
            estimatedMs = estimateMs(steps, ctx),
        )
    }

    /**
     * How long the session takes, end to end.
     *
     * A step with no pace — drills, strides, the test — is costed at the slow end of
     * easy, which is what the walk-backs and the jogs between them actually are.
     */
    private fun estimateMs(steps: List<WorkoutStep>, ctx: SessionContext): Long {
        val slow = ctx.basis.paces.easySecPerKm.endInclusive
        fun msFor(meters: Double?, ms: Long?, pace: ClosedFloatingPointRange<Double>?): Long =
            ms ?: meters?.let { Workouts.durationAt(pace?.let(::mid) ?: slow, it) } ?: 0L
        val total = steps.sumOf { step ->
            val repeats = step.repeats.coerceAtLeast(1)
            val work = msFor(step.distanceM, step.durationMs, step.paceSecPerKm)
            val recovery = msFor(step.recoveryM, step.recoveryMs, step.recoveryPaceSecPerKm)
            work * repeats + recovery * (repeats - 1)
        }
        // To five minutes: "about 1 h 05" is what a runner plans a morning around.
        return (total / (5 * MIN).toDouble()).roundToLong().coerceAtLeast(1) * 5 * MIN
    }

    private fun scaleReps(reps: Int, ctx: SessionContext) =
        if (ctx.scaled) (reps * 0.75).roundToInt().coerceAtLeast(4).coerceAtMost(reps) else reps

    private fun scaleMinutes(minutes: Int, ctx: SessionContext): Int {
        if (!ctx.scaled) return minutes
        val scaled = ((minutes * 0.8) / 5.0).roundToInt() * 5
        return scaled.coerceAtLeast(30).coerceAtMost(minutes)
    }

    private fun scaledNote(ctx: SessionContext, changed: Boolean) =
        if (ctx.scaled && changed) {
            " Shortened by about a fifth: the plan is written for a VMA of 12 km/h and up."
        } else {
            ""
        }

    private fun jog(ctx: SessionContext): ClosedFloatingPointRange<Double> {
        val easy = ctx.basis.paces.easySecPerKm
        return easy.endInclusive..(easy.endInclusive + 30.0)
    }

    private fun mid(range: ClosedFloatingPointRange<Double>) = (range.start + range.endInclusive) / 2.0
}

/**
 * Numbers in sentences.
 *
 * Formatted without a Locale, like the planner's own text: these strings are asserted in
 * tests, and a decimal separator that followed the phone's language would make the
 * assertions depend on where the build ran.
 */
object CoachText {

    fun pace(secPerKm: Double): String = "${minSec(secPerKm)}/km"

    fun paceRange(range: ClosedFloatingPointRange<Double>): String =
        if (minSec(range.start) == minSec(range.endInclusive)) pace(range.start)
        else "${minSec(range.start)}–${minSec(range.endInclusive)}/km"

    fun minSec(secPerKm: Double): String {
        val total = secPerKm.roundToInt()
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    fun kmh(kmh: Double): String {
        val tenths = (kmh * 10).roundToInt()
        return "${tenths / 10}.${tenths % 10} km/h"
    }

    fun percent(fraction: Double) = "${(fraction * 100).roundToInt()} %"

    fun percentRange(range: ClosedFloatingPointRange<Double>): String {
        val low = (range.start * 100).roundToInt()
        val high = (range.endInclusive * 100).roundToInt()
        return if (low == high) "$low %" else "$low–$high %"
    }

    fun distance(meters: Double): String = when {
        meters < 1_000.0 -> "${meters.roundToInt()} m"
        meters % 1_000.0 == 0.0 -> "${(meters / 1_000).roundToInt()} km"
        else -> {
            val tenths = (meters / 100.0).roundToInt()
            "${tenths / 10}.${tenths % 10} km"
        }
    }

    /** "45 min", "1 h 15", "2:30", "30 s". */
    fun duration(ms: Long): String {
        val seconds = (ms / 1000.0).roundToInt()
        return when {
            seconds < 60 -> "$seconds s"
            seconds % 60 != 0 -> "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
            seconds < 3_600 -> "${seconds / 60} min"
            seconds % 3_600 == 0 -> "${seconds / 3_600} h"
            else -> "${seconds / 3_600} h ${((seconds % 3_600) / 60).toString().padStart(2, '0')}"
        }
    }

    fun seconds(ms: Long) = "${ms / 1000}"

    /** "45:00", "1:52:30". */
    fun clock(ms: Long): String {
        val total = (ms / 1000.0).roundToInt()
        val h = total / 3_600
        val m = (total % 3_600) / 60
        val s = total % 60
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        else "$m:${s.toString().padStart(2, '0')}"
    }

    fun raceName(meters: Int) = when (meters) {
        21_097 -> "half marathon"
        42_195 -> "marathon"
        else -> "${meters / 1000} km"
    }
}

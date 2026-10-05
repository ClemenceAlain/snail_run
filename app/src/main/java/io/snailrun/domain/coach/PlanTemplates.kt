package io.snailrun.domain.coach

/**
 * One session of a template, before it is priced for a runner.
 *
 * Data and nothing else. [PlanSessions] turns one of these and a VMA into a [Workout];
 * keeping the two apart is what lets the templates read as the table they were copied
 * from, and lets a test hold them to it line by line.
 */
sealed interface SessionSpec {
    /** One line of advice, the way a coach would say it at the track. */
    val tip: String

    /**
     * Fast repetitions at a percentage of VMA: the VMA session.
     *
     * Either [repM] or [repMs] is set. [sets] above one splits the reps into blocks with
     * [setRecoveryMs] of jogging between, which is how 16 × 200 m stays fast to the end.
     */
    data class VmaReps(
        val reps: Int,
        val repM: Double? = null,
        val repMs: Long? = null,
        val pct: ClosedFloatingPointRange<Double>,
        val recoveryM: Double? = null,
        val recoveryMs: Long? = null,
        val sets: Int = 1,
        val setRecoveryMs: Long? = null,
        override val tip: String,
    ) : SessionSpec

    /** Repeated blocks at threshold, 80–85 % of VMA: the half and marathon's other engine. */
    data class Threshold(
        val reps: Int,
        val repMs: Long,
        val recoveryMs: Long,
        override val tip: String,
    ) : SessionSpec

    /**
     * Blocks at the pace of the race the template is for.
     *
     * Equal blocks are one step with a repeat count; a ladder of different lengths is a
     * list. [recoveriesMs] has one entry per gap, so a list of one block has none.
     */
    data class RacePace(
        val blocksM: List<Double>,
        val recoveriesMs: List<Long>,
        override val tip: String,
    ) : SessionSpec {
        companion object {
            fun reps(count: Int, meters: Double, recoveryMs: Long, tip: String) = RacePace(
                blocksM = List(count) { meters },
                recoveriesMs = List(count - 1) { recoveryMs },
                tip = tip,
            )
        }
    }

    /** An easy run by the clock, optionally on hills, optionally with strides at the end. */
    data class Easy(
        val minutes: Int,
        val long: Boolean = false,
        val terrain: String? = null,
        val strides: Int = 0,
        override val tip: String,
    ) : SessionSpec

    /** The six-minute VMA test. Put in place of a VMA session while the VMA is unknown. */
    data class VmaTest(override val tip: String = VMA_TEST_TIP) : SessionSpec

    /** The race itself, on its own date. */
    data class Race(override val tip: String) : SessionSpec
}

const val VMA_TEST_TIP =
    "Run as far as you can in six minutes, on a track or a flat measured loop. Start " +
        "firmly but not flat out — you should be able to speed up in the last minute."

/** One week of a template: three sessions and what the week is for. */
data class TemplateWeek(
    val kind: WeekKind,
    /** Early in the week. Usually the VMA session. */
    val first: SessionSpec,
    /** Mid-week. Usually race pace. */
    val second: SessionSpec,
    /** The weekend. The long run, or the race. */
    val third: SessionSpec,
)

data class PlanTemplate(
    val name: String,
    /** The race the specific-pace sessions are written for. */
    val distanceM: Int,
    val weeks: List<TemplateWeek>,
)

/**
 * The plans, written down.
 *
 * The 10 km is Decathlon Coach's "40 min au 10 km en 8 semaines" as published, with its
 * race pace swapped for the runner's own: three sessions a week, one VMA, one at race
 * pace, one easy and long, a lighter fourth week and a taper into the race. The other
 * three follow the same grammar, scaled — shorter reps and shorter long runs for the
 * 5 km, threshold and longer race-pace blocks for the half and the marathon, a lighter
 * week every fourth. Their numbers are the app's, not a published plan's.
 */
object PlanTemplates {

    private const val MIN = 60_000L

    private val build = WeekKind.Build
    private val light = WeekKind.Light
    private val taper = WeekKind.Taper
    private val raceWeek = WeekKind.Race

    private const val CONTROL = "Control your pace: every rep should look like the first."
    private const val RELAX = "Stay relaxed — shoulders down, hands loose."
    private const val EVEN = "Hold the pace evenly. Race pace should feel firm, not hard."
    private const val CONVERSATION =
        "Flat and conversational: if you cannot talk in full sentences, slow down."
    private const val HILLY =
        "Rolling ground, easy effort. The hills strengthen your legs without any speed."
    private const val WATER = "Long enough to need water: take some with you."
    private const val RECOVER =
        "A lighter week. Sleep, eat well, and change out of wet kit straight after."
    private const val SHARPEN = "Short and light. This keeps your legs quick, it does not build anything."
    private const val SHAKEOUT = "Twenty easy minutes and five strides. Cut it short if you feel tired."
    private const val RACE =
        "Do not start too fast. If you feel good, speed up over the last fifth. Think of " +
            "the finish line when it gets hard."

    private fun vma(
        reps: Int,
        m: Double,
        pct: ClosedFloatingPointRange<Double>,
        recM: Double,
        tip: String = CONTROL,
    ) = SessionSpec.VmaReps(reps = reps, repM = m, pct = pct, recoveryM = recM, tip = tip)

    private fun sets(sets: Int, reps: Int, m: Double, pct: ClosedFloatingPointRange<Double>, recM: Double, setMs: Long) =
        SessionSpec.VmaReps(
            reps = reps, repM = m, pct = pct, recoveryM = recM,
            sets = sets, setRecoveryMs = setMs, tip = CONTROL,
        )

    private fun thirtyThirty(reps: Int) = SessionSpec.VmaReps(
        reps = reps, repMs = 30_000L, pct = 1.0..1.0, recoveryMs = 30_000L,
        tip = "Thirty seconds fast, thirty seconds jogging. Find a rhythm and keep it.",
    )

    private fun threshold(reps: Int, minutes: Int, recMinutes: Int = 3) = SessionSpec.Threshold(
        reps = reps, repMs = minutes * MIN, recoveryMs = recMinutes * MIN,
        tip = "Comfortably hard: you could say a few words, not a sentence.",
    )

    private fun pace(count: Int, m: Double, recMs: Long, tip: String = EVEN) =
        SessionSpec.RacePace.reps(count, m, recMs, tip)

    private fun easy(minutes: Int, tip: String = CONVERSATION) = SessionSpec.Easy(minutes, tip = tip)
    private fun long(minutes: Int, tip: String = CONVERSATION) = SessionSpec.Easy(minutes, long = true, tip = tip)
    private fun hilly(minutes: Int) = SessionSpec.Easy(minutes, terrain = "rolling", tip = HILLY)
    private val shakeout = SessionSpec.Easy(20, strides = 5, tip = SHAKEOUT)
    private val race = SessionSpec.Race(RACE)

    private const val P105 = 1.05
    private val at105 = P105..P105
    private val at100to105 = 1.0..1.05
    private val at95to100 = 0.95..1.0
    private val at95 = 0.95..0.95
    private val at100 = 1.0..1.0
    private val at90to95 = 0.90..0.95

    val TenK = PlanTemplate(
        name = "10 km in 8 weeks",
        distanceM = 10_000,
        weeks = listOf(
            TemplateWeek(build, vma(12, 200.0, at105, 100.0, RELAX), pace(5, 1_000.0, 2 * MIN), long(60)),
            TemplateWeek(build, vma(12, 300.0, at100to105, 100.0), pace(3, 1_500.0, 150_000L), long(75)),
            TemplateWeek(build, vma(10, 400.0, at95to100, 100.0), pace(3, 2_000.0, 150_000L), long(75)),
            TemplateWeek(light, hilly(45), pace(2, 3_000.0, 150_000L, RECOVER), long(60)),
            TemplateWeek(build, sets(2, 8, 200.0, at105, 100.0, 150_000L), pace(6, 1_000.0, 90_000L), long(90, WATER)),
            TemplateWeek(
                build,
                vma(8, 500.0, at95, 200.0, "Control your pace and do not start too fast."),
                pace(2, 3_000.0, 150_000L),
                long(75),
            ),
            TemplateWeek(
                build,
                pace(4, 1_000.0, 2 * MIN),
                SessionSpec.RacePace(listOf(3_000.0, 2_000.0, 1_000.0), listOf(150_000L, 2 * MIN), EVEN),
                long(60, "Easy and short. It should be a pleasure."),
            ),
            TemplateWeek(raceWeek, vma(8, 200.0, at105, 100.0, SHARPEN), shakeout, race),
        ),
    )

    val FiveK = PlanTemplate(
        name = "5 km in 8 weeks",
        distanceM = 5_000,
        weeks = listOf(
            TemplateWeek(build, vma(10, 200.0, at105, 100.0, RELAX), pace(6, 400.0, 90_000L), long(45)),
            TemplateWeek(build, vma(12, 200.0, at105, 100.0), pace(5, 600.0, 2 * MIN), long(50)),
            TemplateWeek(build, vma(10, 300.0, at100to105, 100.0), pace(4, 800.0, 2 * MIN), long(55)),
            TemplateWeek(light, hilly(40), pace(3, 1_000.0, 2 * MIN, RECOVER), long(45)),
            TemplateWeek(build, sets(2, 8, 200.0, at105, 100.0, 150_000L), pace(5, 1_000.0, 2 * MIN), long(60)),
            TemplateWeek(build, vma(8, 400.0, at100, 200.0), pace(3, 1_500.0, 150_000L), long(55)),
            TemplateWeek(build, vma(6, 300.0, at105, 100.0), pace(2, 2_000.0, 150_000L), long(50)),
            TemplateWeek(raceWeek, vma(6, 200.0, at105, 100.0, SHARPEN), shakeout, race),
        ),
    )

    val Half = PlanTemplate(
        name = "Half marathon in 12 weeks",
        distanceM = 21_097,
        weeks = listOf(
            TemplateWeek(build, vma(10, 300.0, at100, 100.0), pace(3, 2_000.0, 2 * MIN), long(60)),
            TemplateWeek(build, threshold(2, 10), pace(2, 3_000.0, 150_000L), long(70)),
            TemplateWeek(build, vma(8, 500.0, at95, 200.0), pace(3, 3_000.0, 150_000L), long(80)),
            TemplateWeek(light, hilly(45), pace(2, 3_000.0, 150_000L, RECOVER), long(60)),
            TemplateWeek(build, threshold(2, 12), pace(2, 4_000.0, 3 * MIN), long(85)),
            TemplateWeek(build, kmReps(5), pace(3, 4_000.0, 3 * MIN), long(90, WATER)),
            TemplateWeek(build, threshold(2, 15), pace(2, 5_000.0, 3 * MIN), long(95, WATER)),
            TemplateWeek(light, thirtyThirty(10), pace(2, 3_000.0, 150_000L, RECOVER), long(60)),
            TemplateWeek(build, kmReps(6), pace(3, 5_000.0, 3 * MIN), long(105, WATER)),
            TemplateWeek(build, threshold(3, 10), pace(2, 6_000.0, 3 * MIN), long(90, WATER)),
            TemplateWeek(taper, vma(8, 300.0, at100, 100.0, SHARPEN), pace(2, 3_000.0, 150_000L), long(60)),
            TemplateWeek(raceWeek, vma(6, 200.0, at105, 100.0, SHARPEN), shakeout, race),
        ),
    )

    val Marathon = PlanTemplate(
        name = "Marathon in 16 weeks",
        distanceM = 42_195,
        weeks = listOf(
            TemplateWeek(build, vma(10, 300.0, at100, 100.0), pace(3, 2_000.0, 2 * MIN), long(75)),
            TemplateWeek(build, threshold(2, 10), pace(2, 4_000.0, 3 * MIN), long(85)),
            TemplateWeek(build, vma(8, 500.0, at95, 200.0), pace(3, 3_000.0, 150_000L), long(95, WATER)),
            TemplateWeek(light, hilly(45), pace(2, 3_000.0, 150_000L, RECOVER), long(70)),
            TemplateWeek(build, threshold(2, 12), pace(2, 5_000.0, 3 * MIN), long(105, WATER)),
            TemplateWeek(build, kmReps(5), pace(3, 4_000.0, 3 * MIN), long(115, WATER)),
            TemplateWeek(build, threshold(2, 15), pace(2, 6_000.0, 3 * MIN), long(125, WATER)),
            TemplateWeek(light, thirtyThirty(10), pace(2, 4_000.0, 3 * MIN, RECOVER), long(80)),
            TemplateWeek(build, kmReps(6), pace(3, 5_000.0, 3 * MIN), long(135, WATER)),
            TemplateWeek(build, threshold(3, 10), pace(2, 8_000.0, 3 * MIN), long(140, WATER)),
            TemplateWeek(build, kmReps(5), pace(3, 6_000.0, 3 * MIN), long(150, WATER)),
            TemplateWeek(light, hilly(45), pace(2, 5_000.0, 3 * MIN, RECOVER), long(90)),
            TemplateWeek(build, threshold(2, 15), pace(1, 15_000.0, 0L), long(140, WATER)),
            TemplateWeek(build, vma(6, 800.0, at95, 200.0), pace(2, 6_000.0, 3 * MIN), long(120, WATER)),
            TemplateWeek(taper, vma(8, 300.0, at100, 100.0, SHARPEN), pace(2, 4_000.0, 3 * MIN), long(75)),
            TemplateWeek(raceWeek, vma(6, 200.0, at105, 100.0, SHARPEN), shakeout, race),
        ),
    )

    /** The base cycle a long run-up to a race repeats: the 10 km block's first month. */
    val Cycle = PlanTemplate(name = "Base cycle", distanceM = 10_000, weeks = TenK.weeks.take(4))

    fun forDistance(meters: Int): PlanTemplate = when {
        meters <= 5_000 -> FiveK
        meters <= 10_000 -> TenK
        meters <= 21_097 -> Half
        else -> Marathon
    }

    private fun kmReps(reps: Int) = SessionSpec.VmaReps(
        reps = reps, repM = 1_000.0, pct = at90to95, recoveryMs = 2 * MIN, tip = CONTROL,
    )
}

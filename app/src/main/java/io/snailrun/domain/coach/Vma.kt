package io.snailrun.domain.coach

import java.time.LocalDate
import kotlin.math.roundToInt

/** Where a VMA came from. Kept so the screen can say how far to trust it. */
enum class VmaSource { Test, Typed }

/**
 * Maximal aerobic speed: the speed at which the runner reaches their VO2max, in km/h.
 *
 * The French clubs' number. A runner who has done a test at their club knows it, and
 * every session in a Decathlon or club plan is written as a percentage of it. The coach
 * still thinks in VDOT underneath — see [Vmas.vdotOf] — so a VMA is just one more way of
 * telling it where the runner stands, and a more direct one than a race result.
 */
data class Vma(
    val kmh: Double,
    val measuredOn: LocalDate,
    val source: VmaSource,
    /** The run the test was read off, so the same run is never read twice. */
    val testRunId: Long? = null,
)

/** How the paces of a plan were found, which is what the explanations say. */
enum class VmaOrigin { Test, Typed, Estimated, None }

/**
 * Everything a session needs to be priced: a VMA if there is one, and the VDOT paces.
 *
 * Fast work is written in percentages of [vmaKmh]. Easy running stays on the VDOT easy
 * range, because "70 % of VMA" is a pace a beginner can hold for an hour and a
 * confirmed runner cannot jog at, and the easy range already knows the difference.
 */
data class PaceBasis(
    val vmaKmh: Double?,
    val origin: VmaOrigin,
    val measuredOn: LocalDate?,
    val fitness: FitnessEstimate?,
    val paces: TrainingPaces,
) {
    /** Seconds per kilometre at [fraction] of VMA, or null with no VMA to take it from. */
    fun vmaPace(fraction: Double): Double? = vmaKmh?.let { Vmas.paceSecPerKm(it, fraction) }

    /** A band from two fractions, faster end first, or null with no VMA. */
    fun vmaBand(fractions: ClosedFloatingPointRange<Double>): ClosedFloatingPointRange<Double>? {
        val fast = vmaPace(fractions.endInclusive) ?: return null
        val slow = vmaPace(fractions.start) ?: return null
        return fast..slow
    }
}

object Vmas {

    /** The test lasts six minutes: the SAC Athlétisme half-Cooper. */
    const val TEST_MS = 360_000L

    /**
     * A test that stopped before this is not a test: the last half-minute is where the
     * runner finds out whether they started too fast, and the distance before it says
     * nothing about the speed they can hold.
     */
    const val TEST_MIN_MS = 330_000L

    /** After twelve weeks of training a VMA has moved, and the plan asks for a new one. */
    const val RETEST_DAYS = 84L

    /**
     * The VDOT a VMA stands for.
     *
     * The six-minute test is a six-minute race, so it goes through the same equations as
     * any other effort: [Vdot.fromEffort] with VMA × 100 metres in six minutes. A 15 km/h
     * VMA comes out near 44, which is where a 46-minute 10 km sits.
     */
    fun vdotOf(kmh: Double): Double? =
        if (kmh <= 0.0) null else Vdot.fromEffort(kmh * 100.0, TEST_MS)

    /** The other way: the VMA a VDOT implies. Used where only efforts are known. */
    fun fromVdot(vdot: Double): Double {
        val minutes = TEST_MS / 60_000.0
        val metersPerMinute = Vdot.velocityFor(vdot * Vdot.percentOfMax(minutes))
        return metersPerMinute * 60.0 / 1000.0
    }

    /** 3600 ÷ (VMA × fraction): 105 % of 14 km/h is 4:05/km. */
    fun paceSecPerKm(kmh: Double, fraction: Double): Double = 3600.0 / (kmh * fraction)

    /** Metres covered in six minutes ÷ 100. 1 500 m is a VMA of 15 km/h. */
    fun fromTestDistance(meters: Double): Double = meters / 100.0

    /**
     * The VMA a guided test run says, read off the six-minute segment alone.
     *
     * The warm-up is run on the same track and must not count: the whole run's distance
     * over the whole run's time is a jog's speed. Null when the test segment was cut
     * short, so the runner is asked rather than handed a VMA that is too low.
     */
    fun fromTestResult(results: List<SegmentResult>): Double? {
        val test = results.firstOrNull {
            it.segment.kind == SegmentKind.Work && it.segment.targetMs == TEST_MS
        } ?: return null
        if (test.actualMs < TEST_MIN_MS || test.actualMeters <= 0.0) return null
        val kmh = test.actualMeters / (test.actualMs / 1000.0) * 3.6
        return round1(kmh)
    }

    /** VMA is read and typed to a tenth of a km/h. */
    fun round1(kmh: Double): Double = (kmh * 10.0).roundToInt() / 10.0
}

/** One place the coach and the Records screen get their numbers from. */
object CoachFitness {

    /**
     * The pace basis for today.
     *
     * A VMA the runner gave wins over anything the app found for itself: they did a test
     * or they know their number, and an effort pulled out of a training run is a weaker
     * reading than either. Without one, the best recent effort stands in and the VMA is
     * worked back out of it, marked as an estimate.
     */
    fun basis(vma: Vma?, efforts: List<RecentEffort>, today: LocalDate): PaceBasis {
        if (vma != null) {
            val vdot = Vmas.vdotOf(vma.kmh)
            if (vdot != null) {
                return PaceBasis(
                    vmaKmh = vma.kmh,
                    origin = if (vma.source == VmaSource.Test) VmaOrigin.Test else VmaOrigin.Typed,
                    measuredOn = vma.measuredOn,
                    fitness = FitnessEstimate(
                        vdot = vdot,
                        fromDistanceM = (vma.kmh * 100.0).roundToInt(),
                        fromDate = vma.measuredOn,
                        confidence = Confidence.Solid,
                        paces = Fitness.pacesFor(vdot),
                        vmaKmh = vma.kmh,
                        fromVma = true,
                    ),
                    paces = Fitness.pacesFor(vdot),
                )
            }
        }
        val estimate = Fitness.estimate(efforts, today)
        if (estimate != null) {
            val kmh = Vmas.round1(Vmas.fromVdot(estimate.vdot))
            return PaceBasis(
                vmaKmh = kmh,
                origin = VmaOrigin.Estimated,
                measuredOn = estimate.fromDate,
                fitness = estimate.copy(vmaKmh = kmh),
                paces = estimate.paces,
            )
        }
        return PaceBasis(
            vmaKmh = null,
            origin = VmaOrigin.None,
            measuredOn = null,
            fitness = null,
            paces = provisionalPaces(),
        )
    }

    /**
     * Paces for a runner the app cannot price yet: easy around 7:00/km.
     *
     * Only the easy range is ever used from these. Nothing fast is written off them — a
     * session without a VMA is prescribed by feel, and says so.
     */
    fun provisionalPaces(): TrainingPaces {
        val easy = 420.0
        return TrainingPaces(
            easySecPerKm = easy..(easy + 60.0),
            marathonSecPerKm = easy - 45.0,
            thresholdSecPerKm = easy - 70.0,
            intervalSecPerKm = easy - 90.0,
            repetitionSecPerKm = easy - 105.0,
        )
    }
}

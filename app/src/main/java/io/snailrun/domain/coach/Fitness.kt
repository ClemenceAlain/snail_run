package io.snailrun.domain.coach

import java.time.LocalDate

/** One fast stretch the app has already found and stored, with the day it was run. */
data class RecentEffort(
    val distanceMeters: Int,
    val durationMs: Long,
    val date: LocalDate,
)

/**
 * The five paces a training week is written in, in seconds per kilometre.
 *
 * Easy is a range because it is one: anywhere inside it does the job, and a runner given
 * a single easy number treats it as a target and runs their easy days too hard, which is
 * the most common way to train yourself into an injury.
 */
data class TrainingPaces(
    val easySecPerKm: ClosedFloatingPointRange<Double>,
    val marathonSecPerKm: Double,
    val thresholdSecPerKm: Double,
    val intervalSecPerKm: Double,
    val repetitionSecPerKm: Double,
)

/**
 * How much the estimate can be trusted, which decides what may be prescribed from it.
 *
 * [Provisional] comes from short efforts only. The planner will write easy and threshold
 * work off one, and refuses to write interval or repetition work, because those are the
 * sessions where being fifteen seconds a kilometre out is an injury rather than a bad day.
 */
enum class Confidence { Solid, Provisional, None }

data class FitnessEstimate(
    val vdot: Double,
    val fromDistanceM: Int,
    val fromDate: LocalDate,
    val confidence: Confidence,
    val paces: TrainingPaces,
)

/**
 * Turns the best efforts already stored against past runs into a current fitness, and
 * that into paces.
 *
 * The delicate part is not the arithmetic — [Vdot] does that — but which effort to
 * believe. These are not race results. They are the fastest stretch the app could find
 * inside a training run, and a 1 km one is usually a surge to a crossing or a single rep
 * of a session. Read as a time trial it overstates what the runner can hold, and every
 * pace derived from it comes out too fast.
 *
 * So the rule is **the best effort at 5 km or longer, not the best effort**. A fast 10 k
 * inside a 12 k run is close to a real effort; a fast kilometre inside it is not. Where
 * nothing reaches 5 km the short effort is used and the estimate is marked [Confidence
 * .Provisional], which costs the runner the hardest sessions until they have run far
 * enough to justify them.
 *
 * The bias this leaves is deliberate. An effort pulled out of a training run under-reads
 * fitness far more often than it over-reads it, so the paces here land a little easy.
 * Easy is the mistake you recover from on the next run.
 */
object Fitness {

    /** How far back an effort still says something about today. Ten weeks. */
    const val WINDOW_DAYS = 70L

    /** Below this, an effort is a surge inside a run rather than a sustained effort. */
    const val TRUSTED_DISTANCE_M = 5_000

    fun estimate(efforts: List<RecentEffort>, today: LocalDate): FitnessEstimate? {
        val cutoff = today.minusDays(WINDOW_DAYS)
        val recent = efforts.filter { it.date >= cutoff && it.date <= today }
        if (recent.isEmpty()) return null

        val trusted = recent.filter { it.distanceMeters >= TRUSTED_DISTANCE_M }
        val pool = trusted.ifEmpty { recent }

        val best = pool
            .mapNotNull { effort ->
                Vdot.fromEffort(effort.distanceMeters.toDouble(), effort.durationMs)
                    ?.let { effort to it }
            }
            .maxByOrNull { (_, vdot) -> vdot }
            ?: return null

        val (effort, vdot) = best
        return FitnessEstimate(
            vdot = vdot,
            fromDistanceM = effort.distanceMeters,
            fromDate = effort.date,
            confidence = if (trusted.isEmpty()) Confidence.Provisional else Confidence.Solid,
            paces = pacesFor(vdot),
        )
    }

    fun pacesFor(vdot: Double): TrainingPaces = TrainingPaces(
        // Low fraction is the slow end, so the faster pace is the range's start.
        easySecPerKm = Vdot.paceSecPerKm(vdot, Vdot.EASY_HIGH)..Vdot.paceSecPerKm(vdot, Vdot.EASY_LOW),
        marathonSecPerKm = Vdot.paceSecPerKm(vdot, Vdot.MARATHON),
        thresholdSecPerKm = Vdot.paceSecPerKm(vdot, Vdot.THRESHOLD),
        intervalSecPerKm = Vdot.paceSecPerKm(vdot, Vdot.INTERVAL),
        repetitionSecPerKm = Vdot.paceSecPerKm(vdot, Vdot.REPETITION),
    )
}

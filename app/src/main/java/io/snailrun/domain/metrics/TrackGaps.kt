package io.snailrun.domain.metrics

import io.snailrun.domain.model.TrackPoint

/** What happened between two fixes that are further apart than one second. */
enum class GapKind {
    /** Close enough together to be the ordinary fix stream. */
    Continuous,

    /**
     * The fixes stopped and came back somewhere the runner could plausibly have run to.
     * The time counts and the straight line between the two counts as distance.
     */
    Inferred,

    /** Nothing about the hole can be reconstructed. Neither time nor distance crosses it. */
    Broken,
}

/** A stretch the app had to infer, because the chip had nothing to say about it. */
data class InferredLeg(
    val fromSeq: Int,
    val toSeq: Int,
    val startMs: Long,
    val gapMs: Long,
    val meters: Double,
) {
    val paceSecPerKm: Double? get() = if (meters < 1.0) null else gapMs / 1000.0 / (meters / 1000.0)
}

/**
 * What the app does about a hole in the fixes.
 *
 * Losing the signal is not the runner stopping, and the app used to treat it as though
 * it were: no fix meant no clock, so a tunnel, a station underpass or three minutes of
 * dense city cost the run those three minutes of moving time while still crediting the
 * distance, which came out as a pace nobody ran.
 *
 * So a gap is classified rather than ignored. Between two real fixes the runner was
 * somewhere, and the straight line between them is the only reconstruction the data
 * supports — the same reasoning the position filter already applies to a dropout. What
 * decides whether that reconstruction is honest is the speed it implies:
 *
 * - Slower than a walk means they were standing about, or the phone was in a pocket on a
 *   train platform. Crediting it would put a rest in the middle of the moving time.
 * - Faster than a runner can hold means they travelled, not ran. A metro ride across town
 *   is the shape that produces it, and it must not become five kilometres of running.
 * - Longer than twenty minutes is not a lost signal, it is a lost afternoon.
 *
 * Both the live recorder and every later re-derivation read these rules from here, which
 * is what keeps the number on the record screen and the number on the run's own screen
 * the same number.
 */
object TrackGaps {

    /**
     * Under this, a gap is just the fix stream breathing. The figure is the one the app
     * has always used, and a second of missing GPS is not worth a different treatment
     * from a second of present GPS.
     */
    const val CONTINUOUS_MS = 30_000L

    /** Past this the hole is too big to say anything about. */
    const val MAX_INFERRED_MS = 20 * 60_000L

    /** Slower than a brisk walk over the whole gap: they were not running through it. */
    const val MIN_INFERRED_SPEED_MPS = 0.8

    /** 6 m/s is 2:46/km. Sustained over minutes, that is a vehicle. */
    const val MAX_INFERRED_SPEED_MPS = 6.0

    /**
     * [straightLineM] is the displacement between the two fixes — on the live path the
     * raw positions, on a re-derivation the distance that was credited for the leg. The
     * two differ by a metre or two over a gap of minutes, which cannot change the answer
     * except on a boundary where either answer is defensible.
     */
    fun classify(gapMs: Long, straightLineM: Double): GapKind = when {
        gapMs <= CONTINUOUS_MS -> GapKind.Continuous
        gapMs > MAX_INFERRED_MS -> GapKind.Broken
        else -> {
            val speed = straightLineM / (gapMs / 1000.0)
            if (speed in MIN_INFERRED_SPEED_MPS..MAX_INFERRED_SPEED_MPS) {
                GapKind.Inferred
            } else {
                GapKind.Broken
            }
        }
    }

    /**
     * The part of one step between fixes that counts as time spent running.
     *
     * Zero across a hole nothing can be said about, the whole gap across one the runner
     * plainly ran through, and the plain difference otherwise.
     */
    fun countable(gapMs: Long, straightLineM: Double): Long = when {
        gapMs <= 0 -> 0
        classify(gapMs, straightLineM) == GapKind.Broken -> 0
        else -> gapMs
    }

    /**
     * Time spent running in a stored track: inside a segment, and across the gaps whose
     * far side the runner ran to.
     *
     * Classified on the distance already credited to the leg, so a re-derivation that
     * refused to bridge a hole cannot then count its time.
     */
    fun activeDurationOf(points: List<TrackPoint>): Long {
        var total = 0L
        for (i in 1 until points.size) {
            val from = points[i - 1]
            val to = points[i]
            if (to.segment != from.segment) continue
            val gap = to.timestampMs - from.timestampMs
            if (gap <= 0) continue
            when (classify(gap, to.cumulativeDistanceM - from.cumulativeDistanceM)) {
                GapKind.Continuous, GapKind.Inferred -> total += gap
                GapKind.Broken -> Unit
            }
        }
        return total
    }

    /**
     * The stretches of a stored track that were inferred rather than measured.
     *
     * Derived on read rather than written down during the run: the rules above can change
     * and the fixes cannot, so a run says what today's arithmetic makes of it.
     */
    fun inferredLegs(points: List<TrackPoint>): List<InferredLeg> {
        val legs = mutableListOf<InferredLeg>()
        for (i in 1 until points.size) {
            val from = points[i - 1]
            val to = points[i]
            if (to.segment != from.segment) continue
            val gap = to.timestampMs - from.timestampMs
            if (gap <= 0) continue
            val meters = to.cumulativeDistanceM - from.cumulativeDistanceM
            if (classify(gap, meters) != GapKind.Inferred) continue
            legs += InferredLeg(
                fromSeq = from.seq,
                toSeq = to.seq,
                startMs = from.timestampMs,
                gapMs = gap,
                meters = meters,
            )
        }
        return legs
    }
}

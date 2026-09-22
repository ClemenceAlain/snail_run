package io.snailrun.ui.format

import io.snailrun.domain.coach.SegmentKind
import io.snailrun.domain.coach.Workout
import io.snailrun.domain.coach.WorkoutSegment
import io.snailrun.domain.coach.WorkoutStep
import kotlin.math.roundToInt

/**
 * A no-break space between a number and its unit.
 *
 * "1.5 km" is one thing to read, and a column narrow enough to break it leaves "1.5" over
 * "km" — which looks like a layout bug even when the arithmetic is right.
 */
const val NBSP = ' '

/**
 * How a planned session is written down.
 *
 * Separate from [RunFormat], which reports what a run *was*: a recorded distance is
 * measured and deserves its two decimals, while a prescribed one is a decision and gets
 * as many digits as the decision has. The coach never asks for 6.31 km, so nothing here
 * prints a second decimal — and [io.snailrun.domain.coach.Round] has already made sure
 * the underlying number is one a runner can hit, so this is display and not repair.
 */
object SessionFormat {

    /** "5.2 km", or "900 m" below a kilometre where metres is what a runner counts in. */
    fun distance(meters: Double): String =
        if (meters < 1_000.0) "${meters.roundToInt()}${NBSP}m" else "${km(meters)}${NBSP}km"

    /** One decimal, always. See the object comment. */
    fun km(meters: Double): String {
        val tenths = (meters / 100.0).roundToInt()
        return "${tenths / 10}.${tenths % 10}"
    }

    fun kmWithUnit(meters: Double): String = "${km(meters)}${NBSP}km"

    /** "8 min", "45 s", or "1:30" when it is neither whole minutes nor under one. */
    fun duration(millis: Long): String {
        val seconds = (millis / 1000.0).roundToInt()
        return when {
            seconds < 60 -> "$seconds${NBSP}s"
            seconds % 60 == 0 -> "${seconds / 60}${NBSP}min"
            else -> "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        }
    }

    fun pace(range: ClosedFloatingPointRange<Double>): String =
        if (range.start == range.endInclusive) {
            "${RunFormat.pace(range.start)}$NBSP/km"
        } else {
            "${RunFormat.pace(range.start)}–${RunFormat.pace(range.endInclusive)}$NBSP/km"
        }

    /** What a step of a session is, on one line: "6 × 45 s at 4:05 /km". */
    fun step(step: WorkoutStep): String = buildString {
        if (step.repeats > 1) append("${step.repeats} × ")
        // Time first where a step has both, because time is what governs it — the same
        // rule WorkoutSegments applies, and the reason a tempo is "20 min" rather than
        // the distance the app happens to expect that to cover.
        when {
            step.durationMs != null -> append(duration(step.durationMs))
            step.distanceM != null -> append(distance(step.distanceM))
        }
        if (isNotEmpty()) append(" ")
        append(step.label.lowercase())
        step.paceSecPerKm?.let { append(" at ${pace(it)}") }
    }

    /** "3 × 12 squats", "3 × 45 s plank", "3 × 10 split squats per leg". */
    fun strengthStep(step: WorkoutStep): String = buildString {
        append("${step.repeats} × ")
        when {
            step.countPerSet != null -> append("${step.countPerSet} ")
            step.durationMs != null -> append("${duration(step.durationMs)} ")
        }
        append(step.label.lowercase())
        if (step.perSide) append(" per side")
    }

    /**
     * The session as a reader wants it: one row per block, reps folded back up.
     *
     * The segment list is the truth — it is what the recorder counts through — but it is
     * the truth said to a machine. Nineteen rows reading "hard, jog, hard, jog" is a
     * worse description of six hill repeats than one row saying six hill repeats, and a
     * runner scrolling it on a phone before they leave the house will not read it twice.
     *
     * So the reps are folded, and the block keeps the segment indices it covers. That
     * last part is what lets the same list be used mid-run with the current block lit up:
     * nothing has to be recomputed, and nothing can disagree about where the runner is.
     */
    fun blocks(segments: List<WorkoutSegment>): List<SessionBlock> {
        val out = mutableListOf<SessionBlock>()
        var i = 0
        while (i < segments.size) {
            val first = segments[i]
            if (!first.isRep) {
                out += SessionBlock(
                    title = first.label,
                    kind = first.kind,
                    detail = target(first),
                    recovery = null,
                    range = i..i,
                )
                i++
                continue
            }

            // One block covers the whole repeated set and the jogs inside it. The jogs
            // are identical by construction, so the last one found describes them all.
            val start = i
            var recovery: WorkoutSegment? = null
            var reps = 0
            while (i < segments.size &&
                segments[i].isRep &&
                segments[i].label == first.label &&
                segments[i].repCount == first.repCount
            ) {
                reps++
                i++
                if (i < segments.size && segments[i].kind == SegmentKind.Recover) {
                    recovery = segments[i]
                    i++
                }
            }
            out += SessionBlock(
                title = "$reps × ${first.label}",
                kind = first.kind,
                detail = target(first),
                recovery = recovery?.let { "${it.label.lowercase()} ${target(it)}" },
                range = start..(i - 1),
            )
        }
        return out
    }

    private fun target(segment: WorkoutSegment): String = buildString {
        when {
            segment.targetMs != null -> append(duration(segment.targetMs))
            segment.targetM != null -> append(distance(segment.targetM))
        }
        segment.paceSecPerKm?.let {
            if (isNotEmpty()) append(" · ")
            append(pace(it))
        }
    }

    /** Roughly how long a session takes, for the line under its name. */
    fun estimate(workout: Workout): String? = workout.estimatedMs?.let { "about ${duration(it)}" }
}

/** One readable row of a session: see [SessionFormat.blocks]. */
data class SessionBlock(
    val title: String,
    val kind: SegmentKind,
    /** "45 s · 4:05 /km", or empty for a step that runs until the runner says stop. */
    val detail: String,
    /** The jog between reps, if there is one. */
    val recovery: String?,
    /** Which segments this row stands for, so a live session can light the right one. */
    val range: IntRange,
)

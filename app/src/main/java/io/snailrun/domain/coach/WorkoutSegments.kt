package io.snailrun.domain.coach

/** What a segment is for, which is what decides the cue and the colour. */
enum class SegmentKind { WarmUp, Work, Recover, CoolDown }

/**
 * One stretch of running with nothing to decide inside it.
 *
 * A [WorkoutStep] describes a session to a reader — "5 × 3 min at 3:54, equal jog" is one
 * line and reads well. A segment describes it to something that has to count the runner
 * through it, so the reps are unrolled and the jog is a segment of its own. The reader's
 * version stays; this is the same session said again, to a machine.
 */
data class WorkoutSegment(
    val index: Int,
    val label: String,
    val kind: SegmentKind,
    /** Ends on whichever of these it was prescribed in. Never both, never neither. */
    val targetMs: Long? = null,
    val targetM: Double? = null,
    val paceSecPerKm: ClosedFloatingPointRange<Double>? = null,
    /** "rep 3 of 5". Null outside a repeated block. */
    val repIndex: Int? = null,
    val repCount: Int? = null,
) {
    val isRep: Boolean get() = repIndex != null && repCount != null
}

object WorkoutSegments {

    /**
     * Flattens a session into the segments it is actually run in.
     *
     * The trailing recovery is dropped. A session that ends "…, rep 5, jog 3 min, cool
     * down 2 km" asks the runner to jog slowly, then jog slowly, and the only thing the
     * extra segment adds is a cue telling them to do what they are already doing.
     */
    fun of(workout: Workout): List<WorkoutSegment> {
        val out = mutableListOf<WorkoutSegment>()

        workout.steps.forEachIndexed { stepIndex, step ->
            val repeats = step.repeats.coerceAtLeast(1)
            val kind = kindOf(step, stepIndex, workout.steps.size, repeats)

            repeat(repeats) { rep ->
                out += WorkoutSegment(
                    index = out.size,
                    label = labelOf(step, kind),
                    kind = kind,
                    targetMs = step.durationMs,
                    // A step given both a time and a distance is prescribed in time —
                    // the distance is what that time is expected to cover, and holding
                    // the runner to it would turn a three-minute rep into a race.
                    targetM = step.distanceM.takeIf { step.durationMs == null },
                    paceSecPerKm = step.paceSecPerKm,
                    repIndex = if (repeats > 1) rep + 1 else null,
                    repCount = if (repeats > 1) repeats else null,
                )

                val hasRecovery = step.recoveryMs != null || step.recoveryM != null
                val last = rep == repeats - 1
                if (hasRecovery && !last) {
                    out += WorkoutSegment(
                        index = out.size,
                        label = "Jog",
                        kind = SegmentKind.Recover,
                        targetMs = step.recoveryMs,
                        targetM = step.recoveryM.takeIf { step.recoveryMs == null },
                        paceSecPerKm = step.recoveryPaceSecPerKm,
                    )
                }
            }
        }
        return out
    }

    private fun kindOf(step: WorkoutStep, index: Int, count: Int, repeats: Int): SegmentKind = when {
        step.label.startsWith("Warm", ignoreCase = true) -> SegmentKind.WarmUp
        step.label.startsWith("Cool", ignoreCase = true) -> SegmentKind.CoolDown
        // An easy or long run is one step and is not work in the sense that matters here:
        // nothing is counting you through it, and a cue every kilometre would be the
        // voice announcements the runner already has.
        count == 1 && repeats == 1 -> SegmentKind.WarmUp
        else -> SegmentKind.Work
    }

    /**
     * The step's label describes the whole block — "Hard, equal jog between" — which is
     * right on a plan and wrong on the one rep in front of you. Strip the part about what
     * comes after it.
     */
    private fun labelOf(step: WorkoutStep, kind: SegmentKind): String {
        if (kind != SegmentKind.Work) return step.label
        return step.label.substringBefore(',').trim().ifEmpty { step.label }
    }
}

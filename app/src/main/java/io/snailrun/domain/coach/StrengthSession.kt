package io.snailrun.domain.coach

/** Whether you are doing the thing or recovering from it. */
enum class StrengthStageKind { Work, Rest }

/**
 * One thing to do now: a set, or the rest after one.
 *
 * The strength equivalent of a [WorkoutSegment], and separate from it for the same reason
 * that exists — a [WorkoutStep] describes "3 × 10 split squats per leg" to a reader, and
 * something has to describe the second of those three to a runner who is in it.
 *
 * It carries the exercise's position as well as the set's, because "set 2 of 3" alone is
 * useless on a screen you glance at from the floor: the question is always which exercise
 * *and* how much of it is left.
 */
data class StrengthStage(
    val index: Int,
    val exercise: String,
    /** 1-based, for "exercise 2 of 5". */
    val exerciseIndex: Int,
    val exerciseCount: Int,
    val set: Int,
    val setCount: Int,
    val kind: StrengthStageKind,
    /** Repetitions to count out, for a set that is not held. */
    val reps: Int? = null,
    /** Seconds to hold, for a set that is. Rest stages always have one. */
    val seconds: Int? = null,
    val perSide: Boolean = false,
) {
    /**
     * Whether this stage ends on its own.
     *
     * The distinction the whole screen turns on. A plank ends when the clock says so; ten
     * squats end when the tenth one does, and no clock in the world knows when that is.
     */
    val isTimed: Boolean get() = seconds != null
    val durationMs: Long? get() = seconds?.let { it * 1000L }
}

/** Something to say or buzz, at the moment a stage changes. */
sealed interface StrengthCue {
    data class StageStart(val stage: StrengthStage, val next: StrengthStage?) : StrengthCue
    data class Countdown(val seconds: Int) : StrengthCue
    data object Finished : StrengthCue
}

/**
 * Bookkeeping, passed in and returned rather than held — the same shape as
 * [WorkoutCursor], and for the same reason: everything about where the session has got to
 * can be recomputed, so nothing about it needs to be owned.
 */
data class StrengthCursor(
    val stageIndex: Int = 0,
    val stageStartedMs: Long = 0,
    /** Lowest countdown number already spoken for this stage. 0 means none. */
    val countdownSpoken: Int = 0,
    val announcedIndex: Int = -1,
    val complete: Boolean = false,
)

/** Where the runner is, for the screen. */
data class StrengthProgress(
    val stage: StrengthStage,
    val next: StrengthStage?,
    val elapsedInStageMs: Long,
    /** Null on a set that is counted rather than held. */
    val remainingMs: Long?,
    val stagesDone: Int,
    val stageCount: Int,
    val complete: Boolean,
)

/**
 * Counts a runner through a session on the floor.
 *
 * Pure and clock-free, like [WorkoutScheduler], and for the same reason: the caller owns
 * the clock, so this can be tested by handing it numbers.
 *
 * One thing is genuinely different, and it is the thing the design turns on. A running
 * session is driven entirely by fixes — the runner covers ground whether or not they are
 * paying attention, and every boundary can be detected. A floor session has no such
 * signal. Nothing observable happens when somebody finishes their tenth squat, so a
 * counted set can only ever end when they say it has. Held sets and rests do end on a
 * clock, so those advance themselves.
 *
 * That is why this takes an elapsed millisecond count rather than reading a clock, and
 * why [advance] exists beside [dueAt]: half of a strength session is on a timer and half
 * of it is on the honour system, and pretending otherwise would mean either a plank that
 * needs a tap to end or a set of squats the app cuts you off in the middle of.
 */
class StrengthSession(val stages: List<StrengthStage>) {

    /**
     * How long to rest between sets.
     *
     * Forty-five seconds is the figure for bodyweight work done for endurance rather than
     * for maximum strength: long enough to do the next set properly, short enough that a
     * twenty-minute session stays twenty minutes. It is not offered as a setting, because
     * a runner who wants to argue about rest intervals is not the runner this feature is
     * for.
     */
    companion object {
        const val REST_SECONDS = 45

        /**
         * Unrolls a strength [Workout] into the stages it is actually done in.
         *
         * The trailing rest is dropped, exactly as [WorkoutSegments] drops the trailing
         * jog: a session that ends "…, set 3, rest 45 s" asks somebody to sit on the
         * floor and then tells them they have finished sitting on the floor.
         */
        fun stages(workout: Workout, restSeconds: Int = REST_SECONDS): List<StrengthStage> {
            val out = mutableListOf<StrengthStage>()
            val exercises = workout.steps
            exercises.forEachIndexed { exerciseIndex, step ->
                val sets = step.repeats.coerceAtLeast(1)
                repeat(sets) { set ->
                    out += StrengthStage(
                        index = out.size,
                        exercise = step.label,
                        exerciseIndex = exerciseIndex + 1,
                        exerciseCount = exercises.size,
                        set = set + 1,
                        setCount = sets,
                        kind = StrengthStageKind.Work,
                        reps = step.countPerSet,
                        seconds = step.durationMs?.let { (it / 1000L).toInt() },
                        perSide = step.perSide,
                    )
                    val last = exerciseIndex == exercises.lastIndex && set == sets - 1
                    if (!last) {
                        out += StrengthStage(
                            index = out.size,
                            exercise = "Rest",
                            exerciseIndex = exerciseIndex + 1,
                            exerciseCount = exercises.size,
                            set = set + 1,
                            setCount = sets,
                            kind = StrengthStageKind.Rest,
                            seconds = restSeconds,
                        )
                    }
                }
            }
            return out
        }
    }

    /**
     * Where things stand, and anything worth saying about it.
     *
     * Called on a tick. Timed stages that have run out advance here; counted ones wait for
     * [advance].
     */
    fun evaluate(elapsedMs: Long, cursor: StrengthCursor): Triple<StrengthProgress, List<StrengthCue>, StrengthCursor> {
        if (stages.isEmpty()) {
            return Triple(finished(), emptyList(), cursor.copy(complete = true))
        }

        val cues = mutableListOf<StrengthCue>()
        var state = cursor
        if (state.announcedIndex < 0) state = state.copy(stageStartedMs = elapsedMs)

        // A loop rather than an `if`: a phone that slept through a forty-five second rest
        // should come back with the next set started, not one tick behind it.
        while (!state.complete && dueAt(state, elapsedMs)) {
            state = step(state, elapsedMs)
        }

        if (state.complete) {
            if (cursor.announcedIndex != COMPLETE) {
                cues += StrengthCue.Finished
                state = state.copy(announcedIndex = COMPLETE)
            }
        } else if (state.announcedIndex != state.stageIndex) {
            cues += StrengthCue.StageStart(
                stages[state.stageIndex],
                stages.getOrNull(state.stageIndex + 1),
            )
            state = state.copy(announcedIndex = state.stageIndex)
        }

        if (!state.complete) {
            val (countdown, after) = countdown(state, elapsedMs)
            state = after
            cues += countdown
        }

        return Triple(progressOf(state, elapsedMs), cues, state)
    }

    /** The runner says the set is done. The only way a counted set ever ends. */
    fun advance(elapsedMs: Long, cursor: StrengthCursor): StrengthCursor =
        if (cursor.complete) cursor else step(cursor, elapsedMs)

    fun progressOf(cursor: StrengthCursor, elapsedMs: Long): StrengthProgress {
        if (cursor.complete || stages.isEmpty()) return finished()
        val stage = stages[cursor.stageIndex]
        val elapsed = elapsedMs - cursor.stageStartedMs
        return StrengthProgress(
            stage = stage,
            next = stages.getOrNull(cursor.stageIndex + 1),
            elapsedInStageMs = elapsed,
            remainingMs = stage.durationMs?.let { (it - elapsed).coerceAtLeast(0L) },
            stagesDone = cursor.stageIndex,
            stageCount = stages.size,
            complete = false,
        )
    }

    private fun dueAt(cursor: StrengthCursor, elapsedMs: Long): Boolean {
        val duration = stages[cursor.stageIndex].durationMs ?: return false
        return elapsedMs - cursor.stageStartedMs >= duration
    }

    /**
     * On to the next stage.
     *
     * A stage that ran its course hands the overshoot on, so a session does not drift a
     * second longer per set. One ended by hand starts the next one here, because here is
     * where the runner actually stopped.
     */
    private fun step(cursor: StrengthCursor, elapsedMs: Long): StrengthCursor {
        val duration = stages[cursor.stageIndex].durationMs
        val startedAt = if (duration != null && elapsedMs - cursor.stageStartedMs >= duration) {
            cursor.stageStartedMs + duration
        } else {
            elapsedMs
        }
        val next = cursor.stageIndex + 1
        return cursor.copy(
            stageIndex = next.coerceAtMost(stages.lastIndex),
            stageStartedMs = startedAt,
            countdownSpoken = 0,
            complete = next > stages.lastIndex,
        )
    }

    private fun countdown(
        cursor: StrengthCursor,
        elapsedMs: Long,
    ): Pair<List<StrengthCue>, StrengthCursor> {
        val duration = stages[cursor.stageIndex].durationMs
            ?: return emptyList<StrengthCue>() to cursor
        val remaining = duration - (elapsedMs - cursor.stageStartedMs)
        val at = ((remaining + 999) / 1000).toInt()
        if (at !in 1..COUNTDOWN_FROM) return emptyList<StrengthCue>() to cursor
        // Once each and only downwards, so a late tick cannot make it say "three, one,
        // two".
        if (cursor.countdownSpoken != 0 && at >= cursor.countdownSpoken) {
            return emptyList<StrengthCue>() to cursor
        }
        return listOf(StrengthCue.Countdown(at)) to cursor.copy(countdownSpoken = at)
    }

    private fun finished() = StrengthProgress(
        stage = stages.lastOrNull()
            ?: StrengthStage(0, "Done", 1, 1, 1, 1, StrengthStageKind.Rest),
        next = null,
        elapsedInStageMs = 0,
        remainingMs = null,
        stagesDone = stages.size,
        stageCount = stages.size,
        complete = true,
    )
}

private const val COUNTDOWN_FROM = 3

/** [StrengthCursor.announcedIndex] once the session is over and has said so. */
private const val COMPLETE = -2

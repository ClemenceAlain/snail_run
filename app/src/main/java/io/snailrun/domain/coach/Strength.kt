package io.snailrun.domain.coach

/**
 * The reinforcement work: twenty minutes on the floor that a running plan cannot buy.
 *
 * Deliberately not a running session and deliberately not measured in kilometres. It
 * carries no distance at all, so it never competes with the week's volume budget and
 * never shortens a run to make room for itself — a runner who trades three kilometres of
 * easy running for a set of squats has made themselves weaker, not stronger.
 *
 * Nothing here needs a gym, a bench or a plate. That is the whole design constraint: the
 * strength session a runner actually does is the one they can start where they are
 * standing, and every exercise below is bodyweight and takes a metre of floor.
 */
object Strength {

    /** One movement, as it is prescribed: sets, then what a set is. */
    data class Exercise(
        val name: String,
        val sets: Int,
        /** Repetitions in a set, for a movement counted in reps. */
        val reps: Int? = null,
        /** Seconds held, for a movement counted in time. */
        val seconds: Int? = null,
        /** Both sides, counted separately. "per leg" on the screen. */
        val perSide: Boolean = false,
    )

    private data class Routine(
        val name: String,
        val minutes: Int,
        val reason: String,
        val exercises: List<Exercise>,
    )

    /**
     * Two routines, alternated week by week.
     *
     * Two rather than one because the same five movements every week for a season stops
     * being training and starts being a habit; two rather than six because a runner who
     * has to look up what today's session is will not do it.
     */
    private val Routines = listOf(
        Routine(
            name = "Legs and core",
            minutes = 20,
            reason = "Running is a thousand single-leg landings an hour. This is what " +
                "stops the tenth mile looking different from the first.",
            exercises = listOf(
                Exercise("Squats", sets = 3, reps = 12),
                Exercise("Split squats", sets = 3, reps = 10, perSide = true),
                Exercise("Calf raises", sets = 3, reps = 15, perSide = true),
                Exercise("Glute bridges", sets = 3, reps = 15),
                Exercise("Plank", sets = 3, seconds = 45),
            ),
        ),
        Routine(
            name = "Hips and feet",
            minutes = 15,
            reason = "The hip that drops and the foot that rolls are where most running " +
                "injuries actually start. Neither shows up in a training log.",
            exercises = listOf(
                Exercise("Single-leg deadlifts", sets = 3, reps = 10, perSide = true),
                Exercise("Side-lying leg raises", sets = 3, reps = 15, perSide = true),
                Exercise("Side plank", sets = 3, seconds = 30, perSide = true),
                Exercise("Step-ups", sets = 3, reps = 10, perSide = true),
                Exercise("Heel walks", sets = 3, seconds = 30),
            ),
        ),
    )

    /**
     * The session for a given week.
     *
     * Chosen by arithmetic on the week number rather than at random, for the same reason
     * the running sessions are: the same week always produces the same plan, so what is
     * on screen on Tuesday is still there on Wednesday and a test can assert one.
     */
    fun session(week: Long): Workout {
        val routine = Routines[(Math.floorMod(week, Routines.size.toLong())).toInt()]
        return Workout(
            type = WorkoutType.Strength,
            // Zero, and the whole point. See the class comment.
            totalMeters = 0.0,
            steps = routine.exercises.map { it.toStep() },
            reason = routine.reason,
            estimatedMs = routine.minutes * 60_000L,
            title = routine.name,
        )
    }

    private fun Exercise.toStep() = WorkoutStep(
        label = name,
        repeats = sets,
        durationMs = seconds?.let { it * 1000L },
        countPerSet = reps,
        perSide = perSide,
    )
}

package io.snailrun.ui.components

/**
 * Stick-figure drawings of the reinforcement moves, as data.
 *
 * Each move is two poses — where a repetition starts and where it turns round — and the
 * figure is drawn anywhere between the two. A held move gives the same pose twice and
 * so stands still, which is what a plank should do.
 *
 * Coordinates are in a unit square, x to the right and y down, with the floor at
 * [FLOOR]. The figure is seen side on, facing right. "Near" limbs are the ones on the
 * viewer's side and are drawn solid; "far" limbs are drawn faint behind them, so a
 * one-legged move reads as one-legged.
 *
 * Keyed by the exercise's name, because that is all a [io.snailrun.domain.coach.WorkoutStep]
 * carries. A move with no drawing here simply shows none; a test keeps every move the
 * coach prescribes covered.
 */
object ExercisePoses {

    const val FLOOR = 0.95f

    data class P(val x: Float, val y: Float)

    /**
     * One position of the whole body.
     *
     * An arm is shoulder-less (it hangs off [neck]): elbow, hand. A leg hangs off [hip]:
     * knee, ankle, toe.
     */
    data class Pose(
        val head: P,
        val neck: P,
        val hip: P,
        val nearElbow: P,
        val nearHand: P,
        val farElbow: P,
        val farHand: P,
        val nearKnee: P,
        val nearAnkle: P,
        val nearToe: P,
        val farKnee: P,
        val farAnkle: P,
        val farToe: P,
    ) {
        /** Every joint in a fixed order, so two poses can be blended joint by joint. */
        val joints: List<P>
            get() = listOf(
                head, neck, hip, nearElbow, nearHand, farElbow, farHand,
                nearKnee, nearAnkle, nearToe, farKnee, farAnkle, farToe,
            )

        companion object {
            fun of(joints: List<P>) = Pose(
                joints[0], joints[1], joints[2], joints[3], joints[4], joints[5], joints[6],
                joints[7], joints[8], joints[9], joints[10], joints[11], joints[12],
            )
        }
    }

    /** A rectangle to stand on, for a move that needs one. */
    data class Prop(val left: Float, val top: Float, val right: Float)

    data class Move(val start: Pose, val end: Pose, val prop: Prop? = null) {
        /** The body at [t], 0 being [start] and 1 [end]. */
        fun at(t: Float): Pose = Pose.of(
            start.joints.zip(end.joints) { a, b -> P(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t) }
        )
    }

    fun forExercise(name: String): Move? = Moves[name]

    private fun p(x: Double, y: Double) = P(x.toFloat(), y.toFloat())

    /** Upright, arms at the sides. The starting point for most of the standing moves. */
    private val Standing = Pose(
        head = p(0.50, 0.165), neck = p(0.50, 0.25), hip = p(0.50, 0.54),
        nearElbow = p(0.50, 0.40), nearHand = p(0.51, 0.54),
        farElbow = p(0.50, 0.40), farHand = p(0.51, 0.54),
        nearKnee = p(0.50, 0.73), nearAnkle = p(0.50, 0.92), nearToe = p(0.57, 0.95),
        farKnee = p(0.50, 0.73), farAnkle = p(0.50, 0.92), farToe = p(0.57, 0.95),
    )

    private val Moves: Map<String, Move> = mapOf(
        "Squats" to Move(
            start = Standing.copy(
                nearElbow = p(0.65, 0.26), nearHand = p(0.79, 0.26),
                farElbow = p(0.65, 0.26), farHand = p(0.79, 0.26),
            ),
            end = Standing.copy(
                head = p(0.63, 0.40), neck = p(0.59, 0.48), hip = p(0.40, 0.72),
                nearElbow = p(0.73, 0.48), nearHand = p(0.87, 0.47),
                farElbow = p(0.73, 0.48), farHand = p(0.87, 0.47),
                nearKnee = p(0.61, 0.74), farKnee = p(0.61, 0.74),
            ),
        ),
        "Split squats" to Move(
            start = Standing.copy(
                nearKnee = p(0.59, 0.73), nearAnkle = p(0.68, 0.92), nearToe = p(0.75, 0.95),
                farKnee = p(0.37, 0.74), farAnkle = p(0.25, 0.91), farToe = p(0.30, 0.95),
            ),
            end = Standing.copy(
                head = p(0.50, 0.305), neck = p(0.50, 0.39), hip = p(0.50, 0.68),
                nearElbow = p(0.50, 0.54), nearHand = p(0.51, 0.68),
                farElbow = p(0.50, 0.54), farHand = p(0.51, 0.68),
                nearKnee = p(0.70, 0.71), nearAnkle = p(0.68, 0.92), nearToe = p(0.75, 0.95),
                farKnee = p(0.41, 0.89), farAnkle = p(0.25, 0.91), farToe = p(0.30, 0.95),
            ),
        ),
        "Calf raises" to run {
            // On one leg, the other hooked up behind, so the drawing says "per leg".
            val oneLeg = Standing.copy(
                farKnee = p(0.53, 0.73), farAnkle = p(0.42, 0.84), farToe = p(0.40, 0.90),
            )
            Move(
                start = oneLeg,
                end = oneLeg.lifted(0.06).copy(nearAnkle = p(0.52, 0.87), nearToe = p(0.57, 0.95)),
            )
        },
        "Glute bridges" to Move(
            start = Pose(
                head = p(0.13, 0.885), neck = p(0.22, 0.89), hip = p(0.52, 0.90),
                nearElbow = p(0.36, 0.92), nearHand = p(0.50, 0.93),
                farElbow = p(0.36, 0.92), farHand = p(0.50, 0.93),
                nearKnee = p(0.67, 0.72), nearAnkle = p(0.77, 0.90), nearToe = p(0.84, 0.94),
                farKnee = p(0.67, 0.72), farAnkle = p(0.77, 0.90), farToe = p(0.84, 0.94),
            ),
            end = Pose(
                head = p(0.13, 0.885), neck = p(0.22, 0.89), hip = p(0.50, 0.70),
                nearElbow = p(0.36, 0.92), nearHand = p(0.50, 0.93),
                farElbow = p(0.36, 0.92), farHand = p(0.50, 0.93),
                nearKnee = p(0.69, 0.65), nearAnkle = p(0.77, 0.90), nearToe = p(0.84, 0.94),
                farKnee = p(0.69, 0.65), farAnkle = p(0.77, 0.90), farToe = p(0.84, 0.94),
            ),
        ),
        "Plank" to run {
            // Forearms down, one straight line from heel to head.
            val plank = Pose(
                head = p(0.88, 0.715), neck = p(0.79, 0.75), hip = p(0.52, 0.81),
                nearElbow = p(0.79, 0.93), nearHand = p(0.93, 0.93),
                farElbow = p(0.79, 0.93), farHand = p(0.93, 0.93),
                nearKnee = p(0.33, 0.85), nearAnkle = p(0.14, 0.89), nearToe = p(0.13, 0.95),
                farKnee = p(0.33, 0.85), farAnkle = p(0.14, 0.89), farToe = p(0.13, 0.95),
            )
            Move(plank, plank)
        },
        "Single-leg deadlifts" to Move(
            start = Standing.copy(
                farKnee = p(0.48, 0.73), farAnkle = p(0.43, 0.90), farToe = p(0.48, 0.94),
            ),
            end = Pose(
                head = p(0.86, 0.50), neck = p(0.78, 0.54), hip = p(0.50, 0.56),
                nearElbow = p(0.78, 0.69), nearHand = p(0.78, 0.83),
                farElbow = p(0.78, 0.69), farHand = p(0.78, 0.83),
                nearKnee = p(0.53, 0.74), nearAnkle = p(0.50, 0.92), nearToe = p(0.57, 0.95),
                farKnee = p(0.30, 0.55), farAnkle = p(0.11, 0.55), farToe = p(0.10, 0.62),
            ),
        ),
        "Side-lying leg raises" to run {
            // Seen from the front, lying on the far side, head on the far arm.
            val lying = Pose(
                head = p(0.12, 0.825), neck = p(0.20, 0.86), hip = p(0.50, 0.88),
                nearElbow = p(0.35, 0.85), nearHand = p(0.49, 0.86),
                farElbow = p(0.16, 0.93), farHand = p(0.05, 0.86),
                nearKnee = p(0.71, 0.87), nearAnkle = p(0.91, 0.87), nearToe = p(0.93, 0.82),
                farKnee = p(0.71, 0.90), farAnkle = p(0.91, 0.91), farToe = p(0.93, 0.86),
            )
            Move(
                start = lying,
                end = lying.copy(
                    nearKnee = p(0.67, 0.76), nearAnkle = p(0.83, 0.65), nearToe = p(0.83, 0.59),
                ),
            )
        },
        "Side plank" to run {
            // Seen from the front: on one forearm, hips up, the top arm to the ceiling.
            val sidePlank = Pose(
                head = p(0.86, 0.54), neck = p(0.78, 0.58), hip = p(0.52, 0.72),
                nearElbow = p(0.78, 0.43), nearHand = p(0.78, 0.28),
                farElbow = p(0.78, 0.93), farHand = p(0.92, 0.93),
                nearKnee = p(0.33, 0.82), nearAnkle = p(0.14, 0.91), nearToe = p(0.12, 0.95),
                farKnee = p(0.33, 0.82), farAnkle = p(0.14, 0.91), farToe = p(0.12, 0.95),
            )
            Move(sidePlank, sidePlank)
        },
        "Step-ups" to Move(
            start = Standing.copy(
                head = p(0.56, 0.18), neck = p(0.54, 0.265), hip = p(0.48, 0.56),
                nearElbow = p(0.52, 0.41), nearHand = p(0.56, 0.54),
                farElbow = p(0.52, 0.41), farHand = p(0.56, 0.54),
                nearKnee = p(0.66, 0.60), nearAnkle = p(0.63, 0.80), nearToe = p(0.70, 0.83),
                farKnee = p(0.43, 0.74), farAnkle = p(0.38, 0.92), farToe = p(0.45, 0.95),
            ),
            end = Standing.copy(
                head = p(0.63, 0.045), neck = p(0.63, 0.13), hip = p(0.63, 0.42),
                nearElbow = p(0.63, 0.28), nearHand = p(0.64, 0.42),
                farElbow = p(0.63, 0.28), farHand = p(0.64, 0.42),
                nearKnee = p(0.64, 0.61), nearAnkle = p(0.63, 0.80), nearToe = p(0.70, 0.83),
                farKnee = p(0.82, 0.45), farAnkle = p(0.80, 0.64), farToe = p(0.87, 0.66),
            ),
            prop = Prop(left = 0.50f, top = 0.83f, right = 0.85f),
        ),
        "Heel walks" to run {
            // Mid-stride on the heels, toes pulled up; the second pose is the other stride.
            val stride = Standing.copy(
                nearElbow = p(0.46, 0.40), nearHand = p(0.43, 0.53),
                farElbow = p(0.54, 0.40), farHand = p(0.59, 0.52),
                nearKnee = p(0.55, 0.73), nearAnkle = p(0.59, 0.91), nearToe = p(0.65, 0.87),
                farKnee = p(0.46, 0.73), farAnkle = p(0.41, 0.91), farToe = p(0.47, 0.87),
            )
            Move(
                start = stride,
                end = stride.copy(
                    nearElbow = stride.farElbow, nearHand = stride.farHand,
                    farElbow = stride.nearElbow, farHand = stride.nearHand,
                    nearKnee = stride.farKnee, nearAnkle = stride.farAnkle, nearToe = stride.farToe,
                    farKnee = stride.nearKnee, farAnkle = stride.nearAnkle, farToe = stride.nearToe,
                ),
            )
        },
    )

    /** The body raised by [dy], feet and all. */
    private fun Pose.lifted(dy: Double): Pose = Pose.of(joints.map { P(it.x, it.y - dy.toFloat()) })
}

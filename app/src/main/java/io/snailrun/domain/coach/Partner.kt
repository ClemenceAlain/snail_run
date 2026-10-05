package io.snailrun.domain.coach

/**
 * Someone who runs some sessions alongside, without the app.
 *
 * Not a second user. A partner has no runs, no history and no plan of their own — only a
 * VMA, which is all it takes to say what one of the runner's sessions means at their
 * speed. Their week is theirs to manage; the app only ever translates the day they share.
 */
data class Partner(
    val id: Int,
    val name: String,
    /** Speed at VO2max, km/h: the number a track test or a club coach hands out. */
    val vmaKmh: Double,
) {
    val vdot: Double get() = Partners.vdotOf(vmaKmh)
}

/** How a shared session is laid out. */
enum class PairMode {
    /** The same session at the partner's paces. Nothing of the runner's changes. */
    Mirror,

    /** The same session, timed so the two of them keep running into each other. */
    Together,
}

data class Pairing(val partnerId: Int, val mode: PairMode)

object Partners {

    /** Below a brisk walk or past a world record is a typo, not a VMA. */
    val VmaRange: ClosedFloatingPointRange<Double> = 8.0..26.0

    /**
     * VDOT from VMA.
     *
     * VMA is the speed at which a runner reaches VO2max, and VDOT is a VO2max, so the
     * conversion is the oxygen cost of running at that speed — the same curve every pace
     * in the app is read off. No second model, and no table copied from a club handout.
     */
    fun vdotOf(vmaKmh: Double): Double = Vdot.oxygenCost(vmaKmh * 1000.0 / 60.0)
}

/**
 * One planned session, shared.
 *
 * [her] is what the runner is counted through — her own session in [PairMode.Mirror], the
 * regrouping version in [PairMode.Together]. [his] is the partner's copy, step for step.
 */
data class SharedSession(
    val partner: Partner,
    val mode: PairMode,
    val herVdot: Double,
    val her: Workout,
    val his: Workout,
    val together: TogetherPlan?,
) {
    /** Whether he is the quicker of the two, which decides who turns back. */
    val partnerFaster: Boolean get() = partner.vdot > herVdot

    companion object {
        /** Null when there is nothing to share, or no fitness to translate from. */
        fun of(workout: Workout, herVdot: Double?, partner: Partner?, mode: PairMode?): SharedSession? {
            if (partner == null || mode == null || herVdot == null || herVdot <= 0.0) return null
            if (!workout.type.isRun) return null
            return when (mode) {
                PairMode.Mirror -> SharedSession(
                    partner, mode, herVdot, workout, PartnerPlan.mirror(workout, herVdot, partner.vdot), null,
                )
                PairMode.Together -> PartnerPlan.together(workout, herVdot, partner.vdot).let {
                    SharedSession(partner, mode, herVdot, it.her, it.his, it)
                }
            }
        }
    }
}

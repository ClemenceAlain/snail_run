package io.snailrun.domain.coach

/** One part of "About this plan": a heading and a paragraph. */
data class AboutSection(val title: String, val body: String)

/**
 * The plan, explained.
 *
 * Every number the coach shows has its reason one tap away, and the plan as a whole is
 * no different: why it is this many weeks, why most of them are easy, where the paces
 * come from, and why it gets lighter at the end. A plan the runner understands is one
 * they can bend on a bad week without breaking.
 */
object PlanExplainer {

    fun about(plan: TrainingPlan, basis: PaceBasis, position: PlanPosition?): List<AboutSection> = buildList {
        add(AboutSection("How this plan is built", structure(plan, position)))
        add(AboutSection("Where your paces come from", paces(plan, basis, position)))
        add(
            AboutSection(
                "Easy most of the time",
                "About four fifths of your running is easy, at a pace where you can still " +
                    "talk. That is where endurance is built, and it is what lets the hard " +
                    "fifth be hard. Running the easy days faster does not make you fitter; " +
                    "it makes you too tired for the sessions that do.",
            ),
        )
        add(AboutSection("What each session does", sessions(plan)))
        add(
            AboutSection(
                "Lighter weeks and the taper",
                "Every fourth week is lighter: fitness is built while you recover, not while " +
                    "you train, and a week off the gas is when the work of the three before " +
                    "it lands. The last week" + (if (plan.race?.let { it.distanceMeters > 10_000 } == true) "s" else "") +
                    " before the race cut the volume and keep a little speed, so you arrive " +
                    "rested without forgetting what race pace feels like.",
            ),
        )
    }

    /** "Week 3 of 8 · Build. Next week is lighter." */
    fun weekNote(position: PlanPosition, plan: TrainingPlan): String {
        val next = PlanSchedule.position(plan, plan.startWeek.plusWeeks(position.number.toLong()))
        return buildString {
            append(position.label)
            append(".")
            when {
                next == null && plan.race != null -> Unit
                next?.kind == WeekKind.Light -> append(" Next week is lighter.")
                next?.kind == WeekKind.Taper -> append(" The taper starts next week.")
                next?.kind == WeekKind.Race -> append(" Race week next.")
            }
        }
    }

    private fun structure(plan: TrainingPlan, position: PlanPosition?): String {
        val race = plan.race
        val sessions = when (plan.sessionsPerWeek) {
            2 -> "Two sessions a week: a long run, and a VMA or a race-pace session in turn."
            4 -> "Four sessions a week: a VMA session, a race-pace session, an easy run and a long run."
            else -> "Three sessions a week: a VMA session, a race-pace session and a long run."
        }
        if (race == null) {
            return "No race, so the plan is a four-week cycle that repeats: three weeks that " +
                "build and a lighter fourth. The second time round the reps get longer. " +
                "Race-pace sessions use your 10 km pace. $sessions"
        }
        val total = PlanSchedule.weeksIn(plan) ?: 0
        val template = PlanTemplates.forDistance(race.distanceMeters)
        val length = template.weeks.size
        val fit = when {
            total > length -> "${total - length} base weeks first, then the ${template.name.lowercase()} block."
            total < length -> "Your race is ${total} weeks away, so the plan joins the " +
                "${template.name.lowercase()} block at week ${length - total + 1} and keeps the taper."
            else -> "Exactly the ${template.name.lowercase()} block."
        }
        val now = position?.let { " This is week ${it.number}." }.orEmpty()
        return "$total weeks to your ${CoachText.raceName(race.distanceMeters)}: $fit $sessions$now"
    }

    private fun paces(plan: TrainingPlan, basis: PaceBasis, position: PlanPosition?): String {
        val vma = basis.vmaKmh
        val source = when (basis.origin) {
            VmaOrigin.Test -> "measured by your six-minute test"
            VmaOrigin.Typed -> "as you entered it"
            VmaOrigin.Estimated -> "estimated from your best recent effort — run the six-minute " +
                "test to replace the estimate"
            VmaOrigin.None -> ""
        }
        if (vma == null) {
            return "You do not have a VMA yet, so the fast sessions are run by feel until the " +
                "six-minute test measures it. Run as far as you can in six minutes: the " +
                "distance in metres ÷ 100 is your VMA in km/h."
        }
        val ladder = listOf(1.05, 1.0, 0.95, 0.85, 0.80).joinToString(", ") { pct ->
            "${CoachText.percent(pct)} = ${CoachText.pace(Vmas.paceSecPerKm(vma, pct))}"
        }
        val specific = position?.let { pos ->
            val distance = pos.template.distanceM
            val target = plan.targetTimeMs?.takeIf {
                plan.race != null && PlanTemplates.forDistance(plan.race.distanceMeters).distanceM == distance
            }
            val predicted = basis.fitness?.let { Vdot.timeMsFor(it.vdot, distance.toDouble()) }
            val ms = target ?: predicted ?: return@let null
            val pace = ms / 1000.0 / (distance / 1000.0)
            " Race pace for the ${CoachText.raceName(distance)} is ${CoachText.pace(pace)}, " +
                (if (target != null) "from your ${CoachText.clock(target)} target" else "from your predicted ${CoachText.clock(ms)}") +
                " — ${CoachText.percent(3600.0 / pace / vma)} of your VMA."
        }.orEmpty()
        return "Your VMA is ${CoachText.kmh(vma)}, $source. Every fast pace is a percentage of " +
            "it: $ladder. Easy running is ${CoachText.paceRange(basis.paces.easySecPerKm)}." + specific
    }

    private fun sessions(plan: TrainingPlan): String {
        val long = (plan.race?.distanceMeters ?: 0) > 10_000
        return buildString {
            append("VMA sessions — short reps at 95–105 % of VMA — raise the ceiling every ")
            append("other pace sits under. ")
            if (long) {
                append("Threshold sessions, at 80–85 %, move the point where running fast ")
                append("starts to burn, which is what a long race is run against. ")
            }
            append("Race-pace sessions put the race speed in your legs in blocks that grow ")
            append("each week. The long run builds endurance: the time on your feet is the ")
            append("session, not the speed.")
        }
    }
}

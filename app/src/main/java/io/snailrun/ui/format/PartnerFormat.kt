package io.snailrun.ui.format

import io.snailrun.domain.coach.SharedSession
import io.snailrun.domain.coach.WorkoutSegments
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The partner's version of one block of the session, to sit under the runner's. */
data class PartnerLine(
    /** "3 min · 3:35 /km", the same wording [SessionFormat.blocks] gives the runner. */
    val detail: String,
    val recovery: String?,
    /** Who waits, who turns back, what is run side by side. Null when there is nothing. */
    val note: String?,
)

/**
 * A shared session, written for two.
 *
 * The runner's phone speaks to the runner — "you" — and names the partner. The text sent
 * to the partner turns that round: "you" is them, and the runner is "your partner". Same
 * facts, so one function writes both, told which side it is on.
 */
object PartnerFormat {

    /** One line per block of [SharedSession.her], or empty when the two do not line up. */
    fun lines(shared: SharedSession, forPartner: Boolean = false): List<PartnerLine> {
        val herBlocks = SessionFormat.blocks(WorkoutSegments.of(shared.her))
        val hisBlocks = SessionFormat.blocks(WorkoutSegments.of(shared.his))
        // A block is a step: reps fold back into one row, and nothing else merges. Where
        // that ever stops holding, saying nothing beats lining up the wrong rows.
        if (herBlocks.size != hisBlocks.size || hisBlocks.size != shared.his.steps.size) return emptyList()
        return hisBlocks.mapIndexed { index, block ->
            val step = shared.his.steps[index]
            val mine = shared.her.steps[index]
            // A step he runs for her time rather than her distance: say roughly how far
            // that is, since the distance is what he would otherwise have been told.
            val approx = if (mine.durationMs == null && step.durationMs != null && step.distanceM != null) {
                " (≈${SessionFormat.distance(step.distanceM)})"
            } else {
                ""
            }
            PartnerLine(
                detail = block.detail + approx,
                recovery = block.recovery,
                note = note(shared, index, forPartner),
            )
        }
    }

    /** "38 of 52 min together, regrouping 5 times." Null outside Together mode. */
    fun summary(shared: SharedSession): String? {
        val plan = shared.together ?: return null
        val together = (plan.togetherMs / 60_000.0).roundToInt()
        val total = (plan.totalMs / 60_000.0).roundToInt()
        return buildString {
            append("$together of $total min together")
            when (plan.regroups) {
                0 -> Unit
                1 -> append(", regrouping once")
                else -> append(", regrouping ${plan.regroups} times")
            }
            if (plan.finishGapMs >= 5_000L) append(", finishing ${duration(plan.finishGapMs)} apart")
            append(".")
        }
    }

    /** The whole thing as a message, for the partner who does not have the app. */
    fun shareText(shared: SharedSession, dayLabel: String): String = buildString {
        appendLine("${shared.his.name} — $dayLabel")
        appendLine(
            "At your paces (VMA ${vma(shared.partner.vmaKmh)}${NBSP}km/h), " +
                "${SessionFormat.kmWithUnit(shared.his.totalMeters)} in all.",
        )
        val blocks = SessionFormat.blocks(WorkoutSegments.of(shared.his))
        val lines = lines(shared, forPartner = true)
        blocks.forEachIndexed { index, block ->
            val line = lines.getOrNull(index)
            append("• ${block.title}")
            val detail = line?.detail ?: block.detail
            if (detail.isNotEmpty()) append(": $detail")
            appendLine()
            (line?.recovery ?: block.recovery)?.let { appendLine("   ↳ $it") }
            line?.note?.let { appendLine("   $it") }
        }
        summary(shared)?.let { appendLine(it) }
    }.trimEnd()

    private fun note(shared: SharedSession, step: Int, forPartner: Boolean): String? {
        val plan = shared.together ?: return null
        val name = shared.partner.name
        val he = if (forPartner) "you" else name
        val she = if (forPartner) "your partner" else "you"
        val heJogs = if (forPartner) "you jog" else "$name jogs"
        val sheJogs = if (forPartner) "your partner jogs" else "you jog"
        val parts = mutableListOf<String>()

        if (step in plan.sharedSteps) parts += "side by side"
        if (step in plan.sharedRecoveries) parts += "jogs side by side"
        plan.hisExtraMs[step]?.let { parts += "$heJogs ${duration(it)} more to regroup" }
        plan.herExtraMs[step]?.let { parts += "$sheJogs ${duration(it)} more to regroup" }
        plan.spreadM[step]?.let { meters ->
            val gap = SessionFormat.distance(meters)
            parts += if (shared.partnerFaster) {
                "$he ${if (forPartner) "end" else "ends"} about $gap ahead, then ${if (forPartner) "turn" else "turns"} back to meet $she"
            } else {
                "$she ${if (forPartner) "ends" else "end"} about $gap ahead, then ${if (forPartner) "turns" else "turn"} back to meet $he"
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")?.replaceFirstChar { it.uppercase() }
    }

    /** To five seconds: a gap is something to wait for, not to time. */
    private fun duration(ms: Long): String = SessionFormat.duration(((ms / 5_000.0).roundToLong() * 5_000L).coerceAtLeast(5_000L))

    private fun vma(kmh: Double): String =
        if (kmh % 1.0 == 0.0) kmh.toInt().toString() else ((kmh * 10).roundToInt() / 10.0).toString()
}

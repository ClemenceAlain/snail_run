package io.snailrun.domain.voice

import kotlin.math.floor

/** What the scheduler needs to know about the run. Deliberately not the whole state. */
data class RunProgress(
    val distanceMeters: Double,
    val activeDurationMs: Long,
    val averagePaceSecPerKm: Double?,
    val lastSplitPaceSecPerKm: Double?,
)

/**
 * Decides when to speak. Pure: same input, same decision, no clock and no I/O.
 *
 * Two rules do most of the work.
 *
 * Time is measured in *active* duration, not wall clock, so a pause simply stops the
 * accrual — there is no catch-up burst when the runner starts again, and no special
 * case for pausing anywhere in this class.
 *
 * Distance uses a monotonic milestone count rather than a "distance since last"
 * comparison, so a GPS correction that moves the total backwards cannot re-fire an
 * announcement that already played.
 */
class AnnouncementScheduler(private val config: VoiceConfig) {

    fun evaluate(
        progress: RunProgress,
        cursor: AnnouncementCursor,
    ): Pair<Announcement, AnnouncementCursor>? {
        if (!config.enabled) return null

        val distanceDue = config.everyMeters > 0 &&
            floor(progress.distanceMeters / config.everyMeters).toInt() > cursor.distanceMilestones
        val timeDue = config.everyMillis > 0 &&
            progress.activeDurationMs - cursor.lastTimeAnnouncedActiveMs >= config.everyMillis

        if (!distanceDue && !timeDue) return null

        if (cursor.lastAnnouncementActiveMs >= 0 &&
            progress.activeDurationMs - cursor.lastAnnouncementActiveMs < config.minGapMs
        ) {
            return null
        }

        val milestoneCount =
            if (config.everyMeters > 0) floor(progress.distanceMeters / config.everyMeters).toInt() else 0

        val milestone = when {
            distanceDue && timeDue -> Milestone.Both(
                meters = milestoneCount * config.everyMeters,
                millis = progress.activeDurationMs,
            )
            distanceDue -> Milestone.Distance(milestoneCount * config.everyMeters)
            else -> Milestone.Time(progress.activeDurationMs)
        }

        val announcement = Announcement(
            milestone = milestone,
            activeDurationMs = progress.activeDurationMs,
            distanceMeters = progress.distanceMeters,
            averagePaceSecPerKm = progress.averagePaceSecPerKm.takeIf { config.speakAveragePace },
            lastSplitPaceSecPerKm = progress.lastSplitPaceSecPerKm.takeIf { config.speakLastSplitPace },
        )

        val next = cursor.copy(
            distanceMilestones = if (distanceDue) milestoneCount else cursor.distanceMilestones,
            lastTimeAnnouncedActiveMs =
                if (timeDue) progress.activeDurationMs else cursor.lastTimeAnnouncedActiveMs,
            lastAnnouncementActiveMs = progress.activeDurationMs,
        )
        return announcement to next
    }
}

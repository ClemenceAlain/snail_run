package io.snailrun.domain.voice

/** What a milestone announcement should say, before it becomes words. */
data class Announcement(
    val milestone: Milestone,
    val activeDurationMs: Long,
    val distanceMeters: Double,
    val averagePaceSecPerKm: Double?,
    val lastSplitPaceSecPerKm: Double?,
)

/**
 * Something the app did to the run, said aloud as it happens.
 *
 * Separate from [Announcement] because it carries no figures and answers to no
 * schedule. A milestone is due at a distance the runner is heading towards; a notice is
 * the app reporting that it has just stopped the clock for them, and it is worth
 * nothing a minute late.
 */
enum class RunNotice { AutoPaused, AutoResumed }

sealed interface Milestone {
    data class Distance(val meters: Double) : Milestone
    data class Time(val millis: Long) : Milestone
    /** Both triggers came due together; one announcement covers them. */
    data class Both(val meters: Double, val millis: Long) : Milestone
}

data class VoiceConfig(
    val enabled: Boolean = true,
    /** 0 disables the distance trigger. */
    val everyMeters: Double = 1_000.0,
    /** 0 disables the time trigger. */
    val everyMillis: Long = 0,
    val speakElapsed: Boolean = true,
    val speakAveragePace: Boolean = true,
    val speakLastSplitPace: Boolean = false,
    /** Floor between two announcements, so triggers cannot stack up. */
    val minGapMs: Long = 20_000,
)

/** Persisted with the run, so a crash mid-run cannot make the app replay "5 kilometres". */
data class AnnouncementCursor(
    val distanceMilestones: Int = 0,
    val lastTimeAnnouncedActiveMs: Long = 0,
    val lastAnnouncementActiveMs: Long = -1,
)

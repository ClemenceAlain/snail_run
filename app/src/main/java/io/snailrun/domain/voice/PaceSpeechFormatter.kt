package io.snailrun.domain.voice

import io.snailrun.domain.coach.WorkoutCue
import io.snailrun.domain.coach.WorkoutSegment
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The words a speech engine is given. Supplied by the Android layer from string
 * resources; kept as an interface so the formatter itself stays a JVM test target.
 */
interface SpeechVocabulary {
    fun kilometres(value: Double): String      // "1 kilometre" / "2,5 kilomètres"
    fun minutes(value: Int): String            // "5 minutes"
    fun seconds(value: Int): String            // "12 seconds"
    fun hours(value: Int): String
    val timeLabel: String                      // "time"
    val averagePaceLabel: String               // "average pace"
    val lastSplitLabel: String                 // "last kilometre"
    val perKilometre: String                   // "per kilometre"
    fun notice(notice: RunNotice): String      // "paused" / "running again"
    val sentenceSeparator: String get() = ". "

    // ---- structured sessions ----
    fun repOf(index: Int, count: Int): String  // "rep 3 of 5"
    fun countdown(seconds: Int): String        // "3"
    val easeDown: String                       // "ease down"
    val pickItUp: String                       // "pick it up"
    val sessionComplete: String                // "session done"
    val forLabel: String                       // "for"
    val betweenLabel: String                   // "between"
    val andLabel: String                       // "and"
    val nextLabel: String                      // "then"
}

/**
 * Turns an announcement into a sentence for a speech engine.
 *
 * Everything is spelled out in words and whole numbers. Never "5:12" — engines read a
 * colon literally ("five colon twelve"), and eSpeak NG, the likely engine on a
 * de-Googled phone, is the most literal of all.
 */
class PaceSpeechFormatter(private val vocabulary: SpeechVocabulary) {

    fun format(notice: RunNotice): String = vocabulary.notice(notice)

    /**
     * What to say when the session moves on.
     *
     * Short on purpose. A runner three minutes into a rep has a heart rate of 170 and can
     * hold about one clause; the long version is on the screen, where it can be read at a
     * walk.
     */
    fun format(cue: WorkoutCue): String = when (cue) {
        is WorkoutCue.Countdown -> vocabulary.countdown(cue.seconds)
        is WorkoutCue.OffPace ->
            if (cue.tooFast) vocabulary.easeDown else vocabulary.pickItUp
        WorkoutCue.Finished -> "${vocabulary.sessionComplete}."
        is WorkoutCue.StepStart -> stepStart(cue.segment)
    }

    private fun stepStart(segment: WorkoutSegment): String {
        val parts = mutableListOf<String>()

        parts += if (segment.isRep) {
            "${vocabulary.repOf(segment.repIndex!!, segment.repCount!!)}${vocabulary.sentenceSeparator}" +
                segment.label.lowercase()
        } else {
            segment.label.lowercase()
        }

        segment.targetMs?.let { parts += "${vocabulary.forLabel} ${duration(it)}" }
        segment.targetM?.let { parts += "${vocabulary.forLabel} ${vocabulary.kilometres(it / 1000.0)}" }
        segment.paceSecPerKm?.let { parts += band(it) }

        return parts.joinToString(vocabulary.sentenceSeparator) + "."
    }

    /** A collapsed range is one pace; a real one is read as the range it is. */
    private fun band(range: ClosedFloatingPointRange<Double>): String =
        if (range.start == range.endInclusive) {
            pace(range.start)
        } else {
            "${vocabulary.betweenLabel} ${pace(range.start)} ${vocabulary.andLabel} " +
                pace(range.endInclusive)
        }

    fun format(announcement: Announcement): String {
        val parts = mutableListOf<String>()

        parts += when (val milestone = announcement.milestone) {
            is Milestone.Distance -> vocabulary.kilometres(milestone.meters / 1000.0)
            is Milestone.Time -> duration(milestone.millis)
            is Milestone.Both ->
                vocabulary.kilometres(milestone.meters / 1000.0) +
                    vocabulary.sentenceSeparator +
                    "${vocabulary.timeLabel} ${duration(milestone.millis)}"
        }

        if (announcement.milestone !is Milestone.Both && announcement.milestone !is Milestone.Time) {
            parts += "${vocabulary.timeLabel} ${duration(announcement.activeDurationMs)}"
        }

        announcement.averagePaceSecPerKm?.let {
            parts += "${vocabulary.averagePaceLabel} ${pace(it)}"
        }
        announcement.lastSplitPaceSecPerKm?.let {
            parts += "${vocabulary.lastSplitLabel} ${pace(it)}"
        }

        return parts.joinToString(vocabulary.sentenceSeparator) + "."
    }

    /** Seconds per kilometre as "5 minutes 12 seconds per kilometre". */
    fun pace(secPerKm: Double): String {
        val total = secPerKm.roundToInt()
        val minutes = total / 60
        val seconds = total % 60
        val spoken = buildString {
            if (minutes > 0) append(vocabulary.minutes(minutes))
            if (seconds > 0) {
                if (minutes > 0) append(" ")
                append(vocabulary.seconds(seconds))
            }
            if (minutes == 0 && seconds == 0) append(vocabulary.seconds(0))
        }
        return "$spoken ${vocabulary.perKilometre}"
    }

    /** A duration as "1 hour 4 minutes 17 seconds", dropping empty leading units. */
    fun duration(millis: Long): String {
        val totalSeconds = (millis / 1000.0).roundToLong()
        val hours = (totalSeconds / 3600).toInt()
        val minutes = ((totalSeconds % 3600) / 60).toInt()
        val seconds = (totalSeconds % 60).toInt()
        val parts = mutableListOf<String>()
        if (hours > 0) parts += vocabulary.hours(hours)
        if (minutes > 0) parts += vocabulary.minutes(minutes)
        if (seconds > 0 || parts.isEmpty()) parts += vocabulary.seconds(seconds)
        return parts.joinToString(" ")
    }
}

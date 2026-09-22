package io.snailrun.domain.voice

import org.junit.Assert.assertEquals
import io.snailrun.domain.coach.SegmentKind
import io.snailrun.domain.coach.WorkoutCue
import io.snailrun.domain.coach.WorkoutSegment
import org.junit.Assert.assertFalse
import org.junit.Test

/** English vocabulary, as the Android layer would supply it from resources. */
private object EnglishVocabulary : SpeechVocabulary {
    override fun kilometres(value: Double): String {
        val whole = value.toInt()
        val text = if (value == whole.toDouble()) "$whole" else String.format("%.1f", value)
        return "$text ${if (value == 1.0) "kilometre" else "kilometres"}"
    }
    override fun minutes(value: Int) = "$value ${if (value == 1) "minute" else "minutes"}"
    override fun seconds(value: Int) = "$value ${if (value == 1) "second" else "seconds"}"
    override fun hours(value: Int) = "$value ${if (value == 1) "hour" else "hours"}"
    override val timeLabel = "time"
    override val averagePaceLabel = "average pace"
    override val lastSplitLabel = "last kilometre"
    override val perKilometre = "per kilometre"
    override fun notice(notice: RunNotice) = when (notice) {
        RunNotice.AutoPaused -> "paused"
        RunNotice.AutoResumed -> "running again"
    }
    override fun repOf(index: Int, count: Int) = "rep $index of $count"
    override fun setOf(index: Int, count: Int) = "set $index of $count"
    override fun timesLabel(count: Int) = if (count == 1) "1 time" else "$count times"
    override val perSideLabel = "each side"
    override val restLabel = "rest"
    override fun countdown(seconds: Int) = "$seconds"
    override val easeDown = "ease down"
    override val pickItUp = "pick it up"
    override val sessionComplete = "session done"
    override val forLabel = "for"
    override val betweenLabel = "between"
    override val andLabel = "and"
    override val nextLabel = "then"
}

class PaceSpeechFormatterTest {

    private val formatter = PaceSpeechFormatter(EnglishVocabulary)

    @Test
    fun `pace is spoken as words, never as a clock time`() {
        val spoken = formatter.pace(312.0)
        assertEquals("5 minutes 12 seconds per kilometre", spoken)
        assertFalse("a colon would be read out literally by the engine", spoken.contains(":"))
    }

    @Test
    fun `a whole-minute pace omits the seconds`() {
        assertEquals("5 minutes per kilometre", formatter.pace(300.0))
    }

    @Test
    fun `durations drop empty leading units`() {
        assertEquals("42 seconds", formatter.duration(42_000))
        assertEquals("5 minutes 12 seconds", formatter.duration(312_000))
        assertEquals("1 hour 4 minutes 17 seconds", formatter.duration(3_857_000))
        assertEquals("1 hour", formatter.duration(3_600_000))
    }

    @Test
    fun `a kilometre announcement reads as a sentence`() {
        val announcement = Announcement(
            milestone = Milestone.Distance(1000.0),
            activeDurationMs = 312_000,
            distanceMeters = 1000.0,
            averagePaceSecPerKm = 312.0,
            lastSplitPaceSecPerKm = null,
        )
        assertEquals(
            "1 kilometre. time 5 minutes 12 seconds. average pace 5 minutes 12 seconds per kilometre.",
            formatter.format(announcement),
        )
    }

    @Test
    fun `a merged announcement states both milestones once`() {
        val announcement = Announcement(
            milestone = Milestone.Both(meters = 2000.0, millis = 600_000),
            activeDurationMs = 600_000,
            distanceMeters = 2000.0,
            averagePaceSecPerKm = 300.0,
            lastSplitPaceSecPerKm = null,
        )
        assertEquals(
            "2 kilometres. time 10 minutes. average pace 5 minutes per kilometre.",
            formatter.format(announcement),
        )
    }

    @Test
    fun `no announcement ever contains a digit-colon-digit sequence`() {
        val announcement = Announcement(
            milestone = Milestone.Distance(5000.0),
            activeDurationMs = 1_530_000,
            distanceMeters = 5000.0,
            averagePaceSecPerKm = 306.0,
            lastSplitPaceSecPerKm = 298.0,
        )
        val spoken = formatter.format(announcement)
        assertFalse(spoken, Regex("\\d:\\d").containsMatchIn(spoken))
    }

    // ---- structured sessions ----------------------------------------------------------

    private val rep = WorkoutSegment(
        index = 1,
        label = "Hard",
        kind = SegmentKind.Work,
        targetMs = 180_000,
        paceSecPerKm = 234.0..234.0,
        repIndex = 3,
        repCount = 5,
    )

    @Test
    fun `a rep is named, timed and paced`() {
        assertEquals(
            "rep 3 of 5. hard. for 3 minutes. 3 minutes 54 seconds per kilometre.",
            formatter.format(WorkoutCue.StepStart(rep, next = null)),
        )
    }

    @Test
    fun `a step outside a repeated block is not called a rep`() {
        val warmUp = WorkoutSegment(0, "Warm up", SegmentKind.WarmUp, targetM = 2_000.0)
        assertEquals(
            "warm up. for 2 kilometres.",
            formatter.format(WorkoutCue.StepStart(warmUp, next = rep)),
        )
    }

    /** An easy range is a range, and saying one end of it would send the runner to it. */
    @Test
    fun `a pace range is spoken as a range`() {
        val easy = WorkoutSegment(0, "Easy", SegmentKind.WarmUp, targetM = 2_000.0, paceSecPerKm = 300.0..330.0)
        assertEquals(
            "easy. for 2 kilometres. between 5 minutes per kilometre and 5 minutes 30 seconds per kilometre.",
            formatter.format(WorkoutCue.StepStart(easy, next = null)),
        )
    }

    @Test
    fun `the short cues are short`() {
        assertEquals("3", formatter.format(WorkoutCue.Countdown(3)))
        assertEquals("ease down", formatter.format(WorkoutCue.OffPace(tooFast = true)))
        assertEquals("pick it up", formatter.format(WorkoutCue.OffPace(tooFast = false)))
        assertEquals("session done.", formatter.format(WorkoutCue.Finished))
    }

    /**
     * The rule that matters most, extended over every cue: eSpeak NG reads a colon out
     * loud, and a rep cue is the one sentence a runner cannot afford to re-parse.
     */
    @Test
    fun `no cue can contain a clock time`() {
        val cues = listOf(
            WorkoutCue.StepStart(rep, next = null),
            WorkoutCue.StepStart(
                WorkoutSegment(0, "Easy", SegmentKind.WarmUp, targetM = 2_000.0, paceSecPerKm = 300.0..330.0),
                next = rep,
            ),
            WorkoutCue.Countdown(1),
            WorkoutCue.OffPace(tooFast = false),
            WorkoutCue.Finished,
        )
        cues.forEach { cue ->
            val spoken = formatter.format(cue)
            assertFalse(spoken, spoken.contains(":"))
            assertFalse(spoken, Regex("\\d:\\d").containsMatchIn(spoken))
        }
    }
}

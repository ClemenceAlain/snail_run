package io.snailrun.domain.voice

import org.junit.Assert.assertEquals
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
}

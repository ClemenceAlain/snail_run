package io.snailrun.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementSchedulerTest {

    private fun progress(
        meters: Double,
        activeMs: Long,
        avgPace: Double? = 300.0,
        splitPace: Double? = 295.0,
    ) = RunProgress(meters, activeMs, avgPace, splitPace)

    @Test
    fun `nothing is spoken before the first kilometre`() {
        val scheduler = AnnouncementScheduler(VoiceConfig())
        assertNull(scheduler.evaluate(progress(999.0, 300_000), AnnouncementCursor()))
    }

    @Test
    fun `the first kilometre fires once`() {
        val scheduler = AnnouncementScheduler(VoiceConfig())
        val (announcement, cursor) =
            scheduler.evaluate(progress(1000.0, 330_000), AnnouncementCursor())!!
        assertEquals(Milestone.Distance(1000.0), announcement.milestone)
        assertEquals(1, cursor.distanceMilestones)

        // A metre later, nothing more to say.
        assertNull(scheduler.evaluate(progress(1001.0, 330_500), cursor))
    }

    @Test
    fun `a backwards GPS correction cannot replay a milestone`() {
        val scheduler = AnnouncementScheduler(VoiceConfig())
        val (_, cursor) = scheduler.evaluate(progress(1000.0, 330_000), AnnouncementCursor())!!
        // The total drops below the boundary and climbs back: still silent.
        assertNull(scheduler.evaluate(progress(996.0, 360_000), cursor))
        assertNull(scheduler.evaluate(progress(1002.0, 365_000), cursor))
    }

    @Test
    fun `time triggers use active duration, so a pause causes no catch-up burst`() {
        val config = VoiceConfig(everyMeters = 0.0, everyMillis = 300_000)
        val scheduler = AnnouncementScheduler(config)
        val (_, cursor) = scheduler.evaluate(progress(1500.0, 300_000), AnnouncementCursor())!!

        // Twenty minutes of wall clock pass while paused. Active duration does not move,
        // so the scheduler has nothing to say and no backlog to work through.
        assertNull(scheduler.evaluate(progress(1500.0, 300_000), cursor))
        assertNull(scheduler.evaluate(progress(1520.0, 320_000), cursor))
        assertTrue(scheduler.evaluate(progress(2000.0, 600_000), cursor) != null)
    }

    @Test
    fun `two triggers due at once produce one merged announcement`() {
        val config = VoiceConfig(everyMeters = 1000.0, everyMillis = 300_000)
        val scheduler = AnnouncementScheduler(config)
        val (announcement, cursor) =
            scheduler.evaluate(progress(1000.0, 300_000), AnnouncementCursor())!!
        assertTrue(announcement.milestone is Milestone.Both)
        assertEquals(1, cursor.distanceMilestones)
        assertEquals(300_000L, cursor.lastTimeAnnouncedActiveMs)
    }

    @Test
    fun `announcements cannot stack up inside the minimum gap`() {
        val config = VoiceConfig(everyMeters = 1000.0, everyMillis = 60_000, minGapMs = 20_000)
        val scheduler = AnnouncementScheduler(config)
        val (_, cursor) = scheduler.evaluate(progress(1000.0, 60_000), AnnouncementCursor())!!
        // The time trigger comes due five seconds later; the floor suppresses it.
        assertNull(scheduler.evaluate(progress(1020.0, 65_000), cursor))
    }

    @Test
    fun `a disabled voice never fires`() {
        val scheduler = AnnouncementScheduler(VoiceConfig(enabled = false))
        assertNull(scheduler.evaluate(progress(5000.0, 1_500_000), AnnouncementCursor()))
    }

    @Test
    fun `skipped milestones do not queue up behind a long GPS gap`() {
        val scheduler = AnnouncementScheduler(VoiceConfig())
        // The phone loses signal and reappears 3 km later: announce 3 km, not 1 then 2 then 3.
        val (announcement, cursor) =
            scheduler.evaluate(progress(3000.0, 900_000), AnnouncementCursor())!!
        assertEquals(Milestone.Distance(3000.0), announcement.milestone)
        assertEquals(3, cursor.distanceMilestones)
    }

    @Test
    fun `only the enabled metrics are included`() {
        val config = VoiceConfig(speakAveragePace = false, speakLastSplitPace = true)
        val scheduler = AnnouncementScheduler(config)
        val (announcement, _) =
            scheduler.evaluate(progress(1000.0, 330_000), AnnouncementCursor())!!
        assertNull(announcement.averagePaceSecPerKm)
        assertEquals(295.0, announcement.lastSplitPaceSecPerKm!!, 1e-9)
    }
}

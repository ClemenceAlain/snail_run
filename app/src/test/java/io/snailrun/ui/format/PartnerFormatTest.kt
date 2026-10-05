package io.snailrun.ui.format

import io.snailrun.domain.coach.Fitness
import io.snailrun.domain.coach.PairMode
import io.snailrun.domain.coach.Partner
import io.snailrun.domain.coach.SharedSession
import io.snailrun.domain.coach.Vdot
import io.snailrun.domain.coach.WorkoutSegments
import io.snailrun.domain.coach.Workouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnerFormatTest {

    private val her = Vdot.fromEffort(5_000.0, 20 * 60_000L)!!
    private val alex = Partner(1, "Alex", 18.0)
    private val reps = Workouts.repetitions(2_000.0, Fitness.pacesFor(her), "")

    private fun shared(mode: PairMode) = SharedSession.of(reps, her, alex, mode)!!

    @Test
    fun `one line per block of her session`() {
        val s = shared(PairMode.Mirror)
        val lines = PartnerFormat.lines(s)
        assertEquals(SessionFormat.blocks(WorkoutSegments.of(s.her)).size, lines.size)
        assertTrue(lines.all { it.note == null })
        assertNull(PartnerFormat.summary(s))
    }

    @Test
    fun `together says who jogs the gap, from each side`() {
        val s = shared(PairMode.Together)
        val mine = PartnerFormat.lines(s).mapNotNull { it.note }.joinToString()
        val his = PartnerFormat.lines(s, forPartner = true).mapNotNull { it.note }.joinToString()
        assertTrue(mine, mine.contains("Alex jogs"))
        assertTrue(his, his.contains("You jog") || his.contains("you jog"))
        assertNotNull(PartnerFormat.summary(s))
    }

    @Test
    fun `the shared text is written to the partner`() {
        val text = PartnerFormat.shareText(shared(PairMode.Together), "Tue 7 Oct")
        assertTrue(text, text.startsWith("Repetitions — Tue 7 Oct"))
        assertTrue(text, text.contains("VMA 18"))
        assertTrue(text, text.contains("Regrouping 10 times."))
        assertTrue(text, !text.contains("min together"))
        assertTrue(text, !text.contains("Alex jogs"))
    }

    @Test
    fun `nothing is shared without a partner or a fitness`() {
        assertNull(SharedSession.of(reps, null, alex, PairMode.Mirror))
        assertNull(SharedSession.of(reps, her, null, PairMode.Mirror))
    }

    @Test
    fun `regrouping is said without a time spent together`() {
        val summary = PartnerFormat.summary(shared(PairMode.Together))!!
        assertTrue(summary, summary.startsWith("Regrouping"))
        assertTrue(summary, !summary.contains("together"))
    }

    @Test
    fun `every segment of hers has his target beside it, with the regroup note`() {
        val s = shared(PairMode.Together)
        val live = PartnerFormat.bySegment(s)!!
        assertEquals(WorkoutSegments.of(s.her).size, live.size)
        assertTrue(live.all { it.target.isNotEmpty() })
        assertTrue(live.any { it.note?.contains("Alex jogs") == true })
    }
}

package io.snailrun.data.sound

import io.snailrun.data.haptics.Buzz
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeepPatternsTest {

    private val patterns = Buzz.entries.map(BeepPatterns::of) + listOf(BeepPatterns.COUNT)

    @Test
    fun `the samples last as long as the duration the voice waits for`() {
        patterns.forEach { tones ->
            val samples = BeepPatterns.pcm(tones).size
            val expected = BeepPatterns.durationMs(tones) * BeepPatterns.SAMPLE_RATE / 1000
            assertEquals(expected.toDouble(), samples.toDouble(), tones.size.toDouble())
        }
    }

    @Test
    fun `every pip starts and ends near silence so it does not click`() {
        patterns.forEach { tones ->
            val pcm = BeepPatterns.pcm(tones)
            assertTrue(abs(pcm.first().toInt()) < 100)
            assertTrue(abs(pcm.last().toInt()) < 2_000)
        }
    }

    @Test
    fun `the pips are loud but never clip`() {
        patterns.forEach { tones ->
            val peak = BeepPatterns.pcm(tones).maxOf { abs(it.toInt()) }
            assertTrue(peak > Short.MAX_VALUE / 2)
            assertTrue(peak < Short.MAX_VALUE)
        }
    }

    @Test
    fun `work rises and ease falls, so they are told apart without words`() {
        val work = BeepPatterns.of(Buzz.Work).map { it.hz }
        val ease = BeepPatterns.of(Buzz.Ease).map { it.hz }
        assertEquals(work.sorted(), work)
        assertEquals(ease.sortedDescending(), ease)
        assertTrue((work + ease).all { it >= 1_500 })
    }
}

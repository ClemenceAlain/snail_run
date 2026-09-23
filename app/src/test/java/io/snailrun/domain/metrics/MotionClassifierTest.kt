package io.snailrun.domain.metrics

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionClassifierTest {

    private val classifier = MotionClassifier()

    /** Feeds [seconds] of 50 Hz samples from [startS], each a vertical acceleration. */
    private fun feed(startS: Double, seconds: Double, vertical: (Double) -> Double) {
        val samples = (seconds * 50).toInt()
        repeat(samples) { i ->
            val t = startS + i / 50.0
            classifier.onSample((t * 1e9).toLong(), 0.3f, vertical(t).toFloat(), 0.2f)
        }
    }

    private fun nowNs(s: Double) = (s * 1e9).toLong()

    /** About three strides a second, landing at two and a half g. */
    private val running: (Double) -> Double = { t -> 9.81 + 12.0 * sin(2 * PI * 2.8 * t) }

    /** Standing, with the small tremor of a hand. */
    private val standing: (Double) -> Double = { t -> 9.81 + 0.15 * sin(2 * PI * 7.0 * t) }

    @Test
    fun `running reads as moving`() {
        feed(0.0, 3.0, running)
        assertEquals(MotionState.MOVING, classifier.current(nowNs(3.0))?.state)
    }

    @Test
    fun `standing reads as still, and says for how long`() {
        feed(0.0, 3.0, running)
        feed(3.0, 3.0, standing)

        val motion = classifier.current(nowNs(6.0))!!
        assertEquals(MotionState.STILL, motion.state)
        // The last stride leaves the window a second after it lands.
        assertEquals(2_000.0, motion.heldMs.toDouble(), 100.0)
    }

    @Test
    fun `no recent samples is no opinion`() {
        feed(0.0, 2.0, standing)
        assertNull(classifier.current(nowNs(10.0)))
    }

    @Test
    fun `a window not yet full is no verdict`() {
        feed(0.0, 0.5, standing)
        assertEquals(MotionState.UNSURE, classifier.current(nowNs(0.5))?.state)
    }
}

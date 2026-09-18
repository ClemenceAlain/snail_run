package io.snailrun.domain.metrics

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoPauseDetectorTest {

    private val detector = AutoPauseDetector()

    /** Feeds one speed per second and returns the events, in order, with their second. */
    private fun feed(speeds: List<Double>, pausedAfter: Int? = null): List<Pair<Int, AutoPauseEvent>> {
        var paused = false
        return speeds.mapIndexedNotNull { second, speed ->
            val event = detector.onSpeed(second * 1_000L, speed, paused)
            when (event) {
                AutoPauseEvent.Pause -> paused = true
                AutoPauseEvent.Resume -> paused = false
                AutoPauseEvent.None -> Unit
            }
            if (event == AutoPauseEvent.None) null else second to event
        }.also { if (pausedAfter != null) Unit }
    }

    @Test
    fun `running steadily never pauses`() {
        assertEquals(emptyList<Pair<Int, AutoPauseEvent>>(), feed(List(600) { 3.0 }))
    }

    @Test
    fun `pauses a few seconds after stopping and resumes when moving off`() {
        val speeds = List(20) { 3.0 } + List(30) { 0.1 } + List(20) { 3.0 }

        val events = feed(speeds)

        // Stopped at second 20. The smoothing costs two seconds before the speed is
        // believed to be under the threshold, then the two-second dwell runs.
        assertEquals(24 to AutoPauseEvent.Pause, events[0])
        // Moving again at second 50, and running is recognised faster than stopping.
        assertEquals(52 to AutoPauseEvent.Resume, events[1])
        assertEquals(2, events.size)
    }

    @Test
    fun `a brief stumble is not a pause`() {
        // Two seconds under the threshold, then running again.
        val speeds = List(20) { 3.0 } + List(2) { 0.3 } + List(20) { 3.0 }

        assertEquals(emptyList<Pair<Int, AutoPauseEvent>>(), feed(speeds))
    }

    @Test
    fun `a walking break keeps the clock running`() {
        // 1.3 m/s is a walk: above the pause threshold, so the run continues.
        val speeds = List(20) { 3.0 } + List(60) { 1.3 } + List(20) { 3.0 }

        assertEquals(emptyList<Pair<Int, AutoPauseEvent>>(), feed(speeds))
    }

    @Test
    fun `jitter while stopped does not flap between pause and resume`() {
        // Standing at a light, the reported speed wanders under a metre per second.
        val speeds = List(10) { 3.0 } + (0 until 60).map { if (it % 3 == 0) 0.9 else 0.2 }

        val events = feed(speeds)

        assertEquals(1, events.size)
        assertEquals(AutoPauseEvent.Pause, events.single().second)
    }

    @Test
    fun `resuming needs sustained movement, not one fast fix`() {
        val speeds = List(10) { 3.0 } + List(10) { 0.1 } +
            listOf(4.0) + List(10) { 0.1 } + List(10) { 3.0 }

        val events = feed(speeds)

        assertEquals(AutoPauseEvent.Pause, events[0].second)
        // The lone 4 m/s fix at second 20 must not restart the clock.
        assertEquals(AutoPauseEvent.Resume, events[1].second)
        assertEquals(33, events[1].first)
    }
}

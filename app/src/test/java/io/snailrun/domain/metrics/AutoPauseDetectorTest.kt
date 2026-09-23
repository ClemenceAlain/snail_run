package io.snailrun.domain.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** One (speed, what the accelerometer felt) per second. */
    private fun feedWithMotion(seconds: List<Pair<Double, MotionState?>>): List<Pair<Int, AutoPauseEvent>> {
        var paused = false
        var state: MotionState? = null
        var since = 0
        return seconds.mapIndexedNotNull { second, (speed, felt) ->
            if (felt != state) {
                state = felt
                since = second
            }
            val motion = felt?.let { Motion(it, (second - since) * 1_000L) }
            val event = detector.onSpeed(second * 1_000L, speed, paused, motion)
            when (event) {
                AutoPauseEvent.Pause -> paused = true
                AutoPauseEvent.Resume -> paused = false
                AutoPauseEvent.None -> Unit
            }
            if (event == AutoPauseEvent.None) null else second to event
        }
    }

    /** The chip's speed as it really comes in: a second or two behind the runner. */
    private val stopAndGo: List<Double> =
        List(20) { 3.0 } + listOf(2.0, 0.8) + List(28) { 0.1 } + listOf(0.6, 1.8) + List(18) { 3.0 }

    @Test
    fun `the accelerometer pauses and resumes seconds sooner than GPS alone`() {
        val felt = List(20) { MotionState.MOVING } + List(30) { MotionState.STILL } +
            List(20) { MotionState.MOVING }

        val withMotion = feedWithMotion(stopAndGo.zip(felt))
        val gpsOnly = AutoPauseDetector().let { gps ->
            var paused = false
            stopAndGo.mapIndexedNotNull { second, speed ->
                gps.onSpeed(second * 1_000L, speed, paused)
                    .takeIf { it != AutoPauseEvent.None }
                    ?.also { paused = it == AutoPauseEvent.Pause }
                    ?.let { second to it }
            }
        }

        // Stopped at 20: still for a second by 21, and the chip already under 1.2 m/s.
        assertEquals(listOf(21 to AutoPauseEvent.Pause, 51 to AutoPauseEvent.Resume), withMotion)
        assertTrue(gpsOnly[0].first >= 24)
        assertTrue(gpsOnly[1].first >= 53)
    }

    @Test
    fun `handling the phone while stopped does not restart the clock`() {
        // Taken out of a pocket and shaken about, but the runner is going nowhere.
        val seconds = List(10) { 3.0 to MotionState.MOVING } +
            List(10) { 0.1 to MotionState.STILL } +
            List(5) { 0.2 to MotionState.MOVING } +
            List(10) { 0.1 to MotionState.STILL }

        val events = feedWithMotion(seconds)

        assertEquals(listOf(AutoPauseEvent.Pause), events.map { it.second })
    }

    @Test
    fun `a still phone does not pause a runner GPS says is running`() {
        val seconds = List(30) { 3.0 to MotionState.STILL }

        assertEquals(emptyList<Pair<Int, AutoPauseEvent>>(), feedWithMotion(seconds))
    }

    @Test
    fun `jogging on the spot at a light is still paused by GPS`() {
        val seconds = List(10) { 3.0 to MotionState.MOVING } + List(20) { 0.1 to MotionState.MOVING }

        val events = feedWithMotion(seconds)

        assertEquals(listOf(AutoPauseEvent.Pause), events.map { it.second })
    }
}

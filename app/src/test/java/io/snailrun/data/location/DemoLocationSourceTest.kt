package io.snailrun.data.location

import io.snailrun.domain.demo.DemoRunProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DemoLocationSourceTest {

    private val startMs = 1_700_000_000_000L
    private val profile = DemoRunProfile(durationSeconds = 10 * 60)

    private fun source(factor: Int) = DemoLocationSource(
        speedFactor = { factor },
        profile = profile,
        nowMs = { startMs },
    )

    @Test
    fun `fix timestamps stay one second apart whatever the speed`() = runTest {
        val slow = source(1).fixes().take(30).toList()
        val fast = source(60).fixes().take(30).toList()

        assertEquals(slow.map { it.epochMs }, fast.map { it.epochMs })
        assertEquals(startMs + 29_000L, fast.last().epochMs)
    }

    @Test
    fun `compresses the wall clock by the speed factor`() = runTest {
        val before = currentTime

        source(60).fixes().take(600).toList()

        // Ten minutes of running, delivered in ten seconds of wall clock.
        val elapsed = currentTime - before
        assertTrue("took $elapsed ms", elapsed in 8_000..12_000)
    }

    @Test
    fun `ends when the profile runs out instead of hanging`() = runTest {
        val all = source(60).fixes().toList()

        assertEquals(profile.durationSeconds + 1, all.size)
    }

    @Test
    fun `is always ready, so the record screen shows no location warning`() {
        assertTrue(source(30).isReady())
    }
}

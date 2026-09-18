package io.snailrun.tracking

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.snailrun.data.db.SnailDatabase
import io.snailrun.data.prefs.SettingsRepository
import io.snailrun.data.repo.RunRepository
import io.snailrun.data.repo.SOURCE_DEMO
import io.snailrun.domain.demo.DemoRoute
import io.snailrun.domain.demo.DemoRunProfile
import io.snailrun.domain.fixtures.Traces
import io.snailrun.domain.gpx.GpxTrack
import io.snailrun.domain.gpx.GpxWriter
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The whole pipeline end to end: fixes in, a saved run with splits and records out,
 * and a GPX file that parses. This is the closest thing to a real run available
 * without a phone.
 */
@RunWith(RobolectricTestRunner::class)
class RunRecorderTest {

    private lateinit var db: SnailDatabase
    private lateinit var repository: RunRepository
    private lateinit var recorder: RunRecorder

    /** Advances with the fixture's own timestamps, so nothing depends on wall clock. */
    private class FixtureClock(var nowMs: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
        override fun millis(): Long = nowMs
    }

    private val clock = FixtureClock(Traces.START_MS)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, SnailDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RunRepository(db.runDao(), clock)
        recorder = RunRecorder(
            repository = repository,
            settings = SettingsRepository(context),
            clock = clock,
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun runFixture(seconds: Int, speedMps: Double = 3.0): Long {
        val id = recorder.start()
        Traces.steadyRun(seconds = seconds, speedMps = speedMps).forEach { fix ->
            clock.nowMs = fix.epochMs
            recorder.onFix(fix)
        }
        return checkNotNull(recorder.finish()) { "the run should have been in progress" }
    }

    @Test
    fun `recording a run stores it with its track, splits and records`() = runTest {
        val id = runFixture(seconds = 1200)   // 3600 m at 3 m/s

        val run = repository.observeRun(id).first()!!
        assertEquals("COMPLETE", run.status)
        assertEquals(3600.0, run.distanceMeters, 3600.0 * 0.01)
        assertEquals(1_200_000L, run.movingTimeMs)
        // 3 m/s is 5:33/km.
        assertEquals(333.3, run.avgPaceSecPerKm, 4.0)
        assertEquals(1201, run.pointCount)

        val splits = repository.observeSplits(id).first()
        assertEquals(3, splits.count { !it.isPartial })
        assertTrue(splits.last().isPartial)

        assertNotNull(repository.observePersonalRecord(1_000).first())
        assertNull(
            "a 3.6 km run cannot hold a 5 km record",
            repository.observePersonalRecord(5_000).first(),
        )
    }

    @Test
    fun `a demo run is stored and labelled, but never becomes a personal record`() = runTest {
        val profile = DemoRunProfile(stops = emptyList())
        val demoId = recorder.start(SOURCE_DEMO)
        DemoRoute.fixes(profile, startEpochMs = clock.nowMs)
            .take(20 * 60 + 1)
            .forEach { fix ->
                clock.nowMs = fix.epochMs
                recorder.onFix(fix)
            }
        recorder.finish()

        val run = repository.observeRun(demoId).first()!!
        assertEquals(SOURCE_DEMO, run.source)
        assertEquals("COMPLETE", run.status)
        // 20 minutes at about 5:30/km: a real run's worth of track, splits and all.
        assertEquals(3_640.0, run.distanceMeters, 300.0)
        assertTrue(repository.observeSplits(demoId).first().size >= 3)

        assertNull(
            "a demo run must not hold a record",
            repository.observePersonalRecord(1_000).first(),
        )

        // And a real run of the same shape still does.
        runFixture(seconds = 1200)
        assertNotNull(repository.observePersonalRecord(1_000).first())
    }

    @Test
    fun `the state flow reports live progress and returns to idle`() = runTest {
        val id = recorder.start()
        Traces.steadyRun(seconds = 60, speedMps = 3.0).forEach { fix ->
            clock.nowMs = fix.epochMs
            recorder.onFix(fix)
        }

        val active = recorder.state.value as RecordingState.Active
        assertEquals(id, active.runId)
        assertEquals(180.0, active.metrics.distanceMeters, 5.0)

        recorder.finish()
        assertEquals(RecordingState.Idle, recorder.state.value)
    }

    @Test
    fun `pausing keeps the run out of the paused stretch`() = runTest {
        val id = recorder.start()
        Traces.steadyRun(seconds = 60, speedMps = 3.0).forEach { fix ->
            clock.nowMs = fix.epochMs
            recorder.onFix(fix)
        }
        recorder.pause()

        // Ten minutes of fixes arrive while paused, including a two-kilometre move.
        (1..600).forEach { i ->
            val fix = Traces.fix(180.0 + i * 3.0, Traces.START_MS + 60_000 + i * 1000L)
            clock.nowMs = fix.epochMs
            recorder.onFix(fix)
        }
        recorder.resume()
        clock.nowMs = Traces.START_MS + 700_000
        recorder.finish()

        val run = repository.observeRun(id).first()!!
        assertEquals(180.0, run.distanceMeters, 8.0)
        assertEquals(60_000L, run.movingTimeMs)
    }

    @Test
    fun `the stored track exports to GPX that parses`() = runTest {
        val id = runFixture(seconds = 300)
        val points = repository.pointsFor(id)
        val run = repository.observeRun(id).first()!!

        val xml = GpxWriter().writeToString(
            GpxTrack(name = "Test run", startedAtEpochMs = run.startedAtEpochMs, points = points)
        )
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(xml.byteInputStream())
        assertEquals(points.size, document.getElementsByTagName("trkpt").length)
    }

    @Test
    fun `an interrupted run is recovered and finished without losing distance`() = runTest {
        val id = recorder.start()
        Traces.steadyRun(seconds = 600, speedMps = 3.0).forEach { fix ->
            clock.nowMs = fix.epochMs
            recorder.onFix(fix)
        }
        // The process dies here: no finish(), so the run is left mid-flight.
        val unfinished = repository.unfinishedRun()
        assertEquals(id, unfinished?.id)

        val fresh = RunRecorder(
            repository = repository,
            settings = SettingsRepository(
                ApplicationProvider.getApplicationContext<android.content.Context>()
            ),
            clock = clock,
        )
        assertTrue(fresh.recover(id))
        fresh.resume()
        // Carrying on adds to what was already recorded.
        (1..60).forEach { i ->
            val fix = Traces.fix(1800.0 + i * 3.0, Traces.START_MS + 700_000 + i * 1000L)
            clock.nowMs = fix.epochMs
            fresh.onFix(fix)
        }
        clock.nowMs = Traces.START_MS + 800_000
        fresh.finish()

        val run = repository.observeRun(id).first()!!
        assertEquals("COMPLETE", run.status)
        assertTrue("recovered distance was ${run.distanceMeters}", run.distanceMeters >= 1795.0)
    }
}

package io.snailrun.tracking

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.snailrun.data.db.SnailDatabase
import io.snailrun.data.prefs.SettingsRepository
import io.snailrun.data.repo.RunRepository
import io.snailrun.data.repo.SOURCE_DEMO
import io.snailrun.domain.coach.SegmentKind
import io.snailrun.domain.coach.Workout
import io.snailrun.domain.coach.WorkoutCue
import io.snailrun.domain.coach.WorkoutStep
import io.snailrun.domain.coach.WorkoutType
import io.snailrun.domain.demo.DemoRoute
import io.snailrun.domain.demo.DemoRunProfile
import io.snailrun.domain.fixtures.Traces
import io.snailrun.domain.gpx.GpxTrack
import io.snailrun.domain.gpx.GpxWriter
import io.snailrun.domain.model.RunStatus
import io.snailrun.domain.voice.RunNotice
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
    private lateinit var settings: SettingsRepository

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
        settings = SettingsRepository(context)
        // The database is in memory and fresh per test; the settings are not. DataStore
        // keeps one file for the whole class, so a test that turns auto-pause on hands
        // it to every test that runs after it — which is how a passing suite hid the
        // fact that "without the setting" was being run with the setting.
        runBlocking {
            settings.setAutoPauseEnabled(false)
            settings.setVoiceEnabled(true)
        }
        recorder = RunRecorder(
            repository = repository,
            settings = settings,
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

    // ---- structured sessions -----------------------------------------------------------

    /** Warm up 600 m, 3 x (1 min hard, 1 min jog), cool down 600 m. */
    private val session = Workout(
        type = WorkoutType.Intervals,
        totalMeters = 2_400.0,
        qualityMeters = 540.0,
        reason = "because",
        steps = listOf(
            WorkoutStep("Warm up", distanceM = 600.0, paceSecPerKm = 330.0..360.0),
            WorkoutStep(
                "Hard, equal jog between",
                repeats = 3,
                durationMs = 60_000,
                paceSecPerKm = 240.0..240.0,
                recoveryMs = 60_000,
                recoveryPaceSecPerKm = 360.0..390.0,
            ),
            WorkoutStep("Cool down", distanceM = 600.0, paceSecPerKm = 330.0..360.0),
        ),
    )

    private suspend fun guidedRun(seconds: Int, cues: MutableList<WorkoutCue>): Long {
        recorder.onCue = { cues += it }
        val id = recorder.start(session = session)
        Traces.steadyRun(seconds = seconds, speedMps = 3.0).forEach { fix ->
            clock.nowMs = fix.epochMs
            recorder.onFix(fix)
        }
        return id
    }

    @Test
    fun `a guided run is counted through its session and stores what it was`() = runTest {
        val cues = mutableListOf<WorkoutCue>()
        // 600 m at 3 m/s is 200 s, then six 60 s steps, then 200 s: 760 s in all.
        val id = guidedRun(seconds = 800, cues = cues)
        recorder.finish()

        val run = repository.observeRun(id).first()!!
        assertEquals(WorkoutType.Intervals.name, run.workoutType)

        // Warm-up, three reps, two jogs, cool-down.
        val stored = repository.workoutSegmentsFor(id)
        assertEquals(7, stored.size)
        assertEquals(3, stored.count { it.kind == SegmentKind.Work })
        assertEquals(2, stored.count { it.kind == SegmentKind.Recover })

        val starts = cues.filterIsInstance<WorkoutCue.StepStart>()
        assertEquals(7, starts.size)
        assertEquals("Warm up", starts.first().segment.label)
        assertEquals(listOf(1, 2, 3), starts.filter { it.segment.isRep }.map { it.segment.repIndex })
        assertTrue("the session never finished", cues.any { it is WorkoutCue.Finished })
    }

    /**
     * The engine speaks one utterance at a time and the newest wins, so a kilometre
     * milestone landing on the same second as a rep change would cut the rep cue off
     * mid-sentence.
     */
    @Test
    fun `a kilometre milestone does not talk over a step change`() = runTest {
        // Every 200 m, so milestones and step changes are constantly colliding.
        settings.setAnnounceEveryMeters(200.0)

        val cueTimes = mutableListOf<Long>()
        val spokenTimes = mutableListOf<Long>()
        recorder.onCue = { cueTimes += activeMs() }
        recorder.onAnnouncement = { spokenTimes += it.activeDurationMs }

        val id = recorder.start(session = session)
        Traces.steadyRun(seconds = 800, speedMps = 3.0).forEach { fix ->
            clock.nowMs = fix.epochMs
            recorder.onFix(fix)
        }
        recorder.finish()

        assertTrue("nothing was announced at all", spokenTimes.isNotEmpty())
        assertTrue("nothing was cued at all", cueTimes.isNotEmpty())
        spokenTimes.forEach { spoken ->
            val crowded = cueTimes.any { cue -> spoken >= cue && spoken - cue < 20_000 }
            assertTrue("an announcement at $spoken landed on top of a cue", !crowded)
        }
    }

    private fun activeMs(): Long =
        (recorder.state.value as? RecordingState.Active)?.metrics?.activeDurationMs ?: 0L

    @Test
    fun `an ordinary run is guided through nothing`() = runTest {
        val cues = mutableListOf<WorkoutCue>()
        recorder.onCue = { cues += it }
        val id = runFixture(seconds = 300)

        assertTrue(cues.isEmpty())
        assertNull(repository.observeRun(id).first()!!.workoutType)
        assertTrue(repository.workoutSegmentsFor(id).isEmpty())
    }

    @Test
    fun `pressing next ends the step and is remembered on the run`() = runTest {
        val cues = mutableListOf<WorkoutCue>()
        recorder.onCue = { cues += it }
        val id = recorder.start(session = session)

        Traces.steadyRun(seconds = 60, speedMps = 3.0).forEach { fix ->
            clock.nowMs = fix.epochMs
            recorder.onFix(fix)
        }
        // 180 m in: the warm-up had 600 m to run, so this is a skip.
        recorder.nextSegment()

        val run = repository.observeRun(id).first()!!
        assertNotNull("the skip was not written down", run.workoutAdvancesActiveMs)
        val state = recorder.state.value as RecordingState.Active
        assertEquals("Hard", state.workout!!.segment.label)
    }

    /**
     * Where the runner had got to is never stored. It is rebuilt by running the track
     * back through the same scheduler, which is only true if the two agree exactly.
     */
    @Test
    fun `a recovered run resumes on the step it was on`() = runTest {
        val cues = mutableListOf<WorkoutCue>()
        val id = guidedRun(seconds = 320, cues = cues)
        val before = (recorder.state.value as RecordingState.Active).workout!!

        // The process dies here: no finish, and a fresh recorder on the next launch.
        val revived = RunRecorder(repository = repository, settings = settings, clock = clock)
        assertTrue(revived.recover(id))

        val after = (revived.state.value as RecordingState.Active).workout!!
        assertEquals(before.segment.index, after.segment.index)
        assertEquals(before.segment.label, after.segment.label)
        assertEquals(before.segment.repIndex, after.segment.repIndex)
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
    fun `a demo run is stored, labelled, and counts like any other`() = runTest {
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

        // It holds records and feeds the coach like any other run. It is badged DEMO
        // everywhere it appears, so a record set on one is visibly a record set on one —
        // where silently dropping it would tell the runner something untrue about what
        // the app keeps.
        assertNotNull(
            "a demo run is a run",
            repository.observePersonalRecord(1_000).first(),
        )
        assertTrue(
            repository.observeRecentEfforts("2000-01-01").first().any { it.runId == demoId },
        )
    }

    @Test
    fun `auto-pause stops the clock at a light and starts it again`() = runTest {
        settings.setAutoPauseEnabled(true)
        recorder.start()

        // Five minutes running, one minute standing still, five minutes running.
        val fixes = Traces.runWithStop(
            beforeSeconds = 300,
            stillSeconds = 60,
            afterSeconds = 300,
            speedMps = 3.0,
        )
        fixes.forEach { fix ->
            clock.nowMs = fix.epochMs
            recorder.onFix(fix)
        }
        val id = recorder.finish()!!

        val run = repository.observeRun(id).first()!!
        // Ten minutes of running, and the minute at the light charged to nobody. The few
        // seconds either side are the detector deciding.
        assertEquals(600_000.0, run.movingTimeMs.toDouble(), 12_000.0)
        assertEquals(1_800.0, run.distanceMeters, 60.0)
    }

    @Test
    fun `a tunnel neither stops the clock nor pauses the run`() = runTest {
        settings.setAutoPauseEnabled(true)
        recorder.start()

        // Two minutes running, three minutes with no fixes at all, two minutes more.
        val fixes = Traces.runWithDropout(
            beforeSeconds = 120,
            dropoutSeconds = 180,
            afterSeconds = 120,
            speedMps = 3.0,
        )
        fixes.forEach { fix ->
            clock.nowMs = fix.epochMs
            recorder.onFix(fix)
        }
        val id = recorder.finish()!!

        val run = repository.observeRun(id).first()!!
        // Seven minutes of running: the four the chip saw and the three it did not.
        assertEquals(418_000.0, run.movingTimeMs.toDouble(), 2_000.0)
        assertEquals(418.0 * 3.0, run.distanceMeters, 418.0 * 3.0 * 0.05)
        // And the tunnel is one stretch of one trace, not two runs stuck together.
        assertEquals(1, repository.pointsFor(id).map { it.segment }.distinct().size)
    }

    @Test
    fun `the live state says auto-paused while the clock is stopped`() = runTest {
        // What the record screen reads. The timing assertions above would all pass even
        // if the status never reached the UI, which is the one thing a runner sees.
        settings.setAutoPauseEnabled(true)
        recorder.start()

        val fixes = Traces.runWithStop(
            beforeSeconds = 60,
            stillSeconds = 60,
            afterSeconds = 60,
            speedMps = 3.0,
        )

        val seen = mutableSetOf<RunStatus>()
        var statusAtSecond90: RunStatus? = null
        fixes.forEach { fix ->
            clock.nowMs = fix.epochMs
            recorder.onFix(fix)
            val state = recorder.state.value as RecordingState.Active
            seen += state.metrics.status
            // Half a minute into standing still, well past the two-second dwell.
            if (fix.epochMs == Traces.START_MS + 90_000) {
                statusAtSecond90 = state.metrics.status
            }
        }

        assertEquals(RunStatus.PAUSED_AUTO, statusAtSecond90)
        assertTrue("never reported recording again", RunStatus.RECORDING in seen)
    }

    @Test
    fun `it says so when it stops the clock and when it starts it again`() = runTest {
        settings.setAutoPauseEnabled(true)
        settings.setVoiceEnabled(true)
        val spoken = mutableListOf<RunNotice>()
        recorder.onNotice = { spoken += it }
        recorder.start()

        Traces.runWithStop(
            beforeSeconds = 60,
            stillSeconds = 60,
            afterSeconds = 60,
            speedMps = 3.0,
        ).forEach { fix ->
            clock.nowMs = fix.epochMs
            recorder.onFix(fix)
        }
        recorder.finish()

        assertEquals(listOf(RunNotice.AutoPaused, RunNotice.AutoResumed), spoken)
    }

    @Test
    fun `a silent voice stays silent about auto-pause too`() = runTest {
        // The notice is part of the voice feature, not a separate one. Someone who
        // turned the voice off wants the app quiet, including about its own decisions.
        settings.setAutoPauseEnabled(true)
        settings.setVoiceEnabled(false)
        val spoken = mutableListOf<RunNotice>()
        recorder.onNotice = { spoken += it }
        recorder.start()

        Traces.runWithStop(
            beforeSeconds = 60,
            stillSeconds = 60,
            afterSeconds = 60,
            speedMps = 3.0,
        ).forEach { fix ->
            clock.nowMs = fix.epochMs
            recorder.onFix(fix)
        }
        recorder.finish()

        assertEquals(emptyList<RunNotice>(), spoken)
    }

    @Test
    fun `without the setting the clock keeps running through a stop`() = runTest {
        recorder.start()

        Traces.runWithStop(
            beforeSeconds = 300,
            stillSeconds = 60,
            afterSeconds = 300,
            speedMps = 3.0,
        ).forEach { fix ->
            clock.nowMs = fix.epochMs
            recorder.onFix(fix)
        }
        val id = recorder.finish()!!

        // The whole eleven minutes, because nothing was asked to pause.
        assertEquals(660_000.0, repository.observeRun(id).first()!!.movingTimeMs.toDouble(), 2_000.0)
    }

    @Test
    fun `auto-pause never undoes a pause the runner asked for`() = runTest {
        settings.setAutoPauseEnabled(true)
        recorder.start()

        Traces.steadyRun(seconds = 60, speedMps = 3.0).forEach { fix ->
            clock.nowMs = fix.epochMs
            recorder.onFix(fix)
        }
        recorder.pause()

        // Ten minutes of running arrive while paused by hand. None of it counts.
        (1..600).forEach { i ->
            val fix = Traces.fix(180.0 + i * 3.0, Traces.START_MS + 60_000 + i * 1000L, speedMps = 3f)
            clock.nowMs = fix.epochMs
            recorder.onFix(fix)
        }
        val state = recorder.state.value as RecordingState.Active

        assertEquals(RunStatus.PAUSED_MANUAL, state.metrics.status)
        assertEquals(180.0, state.metrics.distanceMeters, 10.0)
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

package io.snailrun.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.snailrun.data.db.SnailDatabase
import io.snailrun.data.repo.RunRepository
import io.snailrun.data.repo.SOURCE_MANUAL
import io.snailrun.data.repo.STATUS_COMPLETE
import io.snailrun.domain.fixtures.Traces
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A run typed in by hand: in the history like any other, and nowhere a track is needed. */
@RunWith(RobolectricTestRunner::class)
class ManualRunTest {

    private lateinit var db: SnailDatabase
    private lateinit var repository: RunRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SnailDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RunRepository(db.runDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a manual run is stored complete with its figures`() = runTest {
        val id = repository.addManualRun(
            startedAtEpochMs = Traces.START_MS,
            durationMs = 30 * 60_000L,
            distanceMeters = 6_000.0,
            zone = ZoneId.of("Europe/Paris"),
        )

        val run = repository.observeHistory().first().single()
        assertEquals(id, run.id)
        assertEquals(SOURCE_MANUAL, run.source)
        assertEquals(STATUS_COMPLETE, run.status)
        assertEquals(6_000.0, run.distanceMeters, 0.0)
        assertEquals(30 * 60_000L, run.movingTimeMs)
        assertEquals(30 * 60_000L, run.elapsedTimeMs)
        assertEquals(Traces.START_MS + 30 * 60_000L, run.endedAtEpochMs)
        assertEquals(300.0, run.avgPaceSecPerKm, 0.001)
        assertEquals(0, run.pointCount)
        assertNull("a manual run is not an unfinished one", repository.unfinishedRun())
    }

    @Test
    fun `a manual run holds no records and survives reprocessing`() = runTest {
        val id = repository.addManualRun(
            startedAtEpochMs = Traces.START_MS,
            durationMs = 20 * 60_000L,
            distanceMeters = 5_000.0,
        )

        assertNull(repository.observePersonalRecord(1_000).first())
        assertTrue(repository.observeSplits(id).first().isEmpty())

        repository.reprocessOutdatedRuns()
        val run = repository.observeRun(id).first()!!
        assertEquals(5_000.0, run.distanceMeters, 0.0)
        assertEquals(20 * 60_000L, run.movingTimeMs)
    }
}

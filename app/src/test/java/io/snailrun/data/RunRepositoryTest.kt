package io.snailrun.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.snailrun.data.db.SnailDatabase
import io.snailrun.data.repo.RunRepository
import io.snailrun.domain.fixtures.Traces
import io.snailrun.domain.metrics.RunMetrics
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

@RunWith(RobolectricTestRunner::class)
class RunRepositoryTest {

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

    // 1010 rather than a round 1000 seconds: at exactly 3000 m the third kilometre
    // boundary sits on the last point, and the position filter's final centimetre
    // decides whether the split exists. A run is never that tidy.
    private suspend fun recordRun(seconds: Int = 1010, speedMps: Double = 3.0): Long {
        val id = repository.startRun(Traces.START_MS)
        val points = Traces.straightTrack(seconds = seconds, speedMps = speedMps)
        repository.appendPoints(id, points)
        repository.completeRun(
            runId = id,
            metrics = RunMetrics(
                distanceMeters = points.last().cumulativeDistanceM,
                activeDurationMs = seconds * 1000L,
                elevationGainM = 12.0,
                pointCount = points.size,
            ),
            endedAtEpochMs = Traces.START_MS + seconds * 1000L,
        )
        return id
    }

    @Test
    fun `completing a run derives its splits and records`() = runTest {
        val id = recordRun()

        val splits = repository.observeSplits(id).first()
        assertEquals(3, splits.count { !it.isPartial })

        val record = repository.observePersonalRecord(1_000).first()
        assertNotNull("a 3 km run must yield a best kilometre", record)
        assertEquals(id, record!!.runId)
    }

    @Test
    fun `history only lists completed runs`() = runTest {
        val live = repository.startRun(Traces.START_MS)
        assertTrue(repository.observeHistory().first().isEmpty())
        assertEquals(live, repository.unfinishedRun()?.id)

        recordRun()
        assertEquals(1, repository.observeHistory().first().size)
    }

    @Test
    fun `an interrupted run is recoverable and its points survive`() = runTest {
        val id = repository.startRun(Traces.START_MS)
        repository.appendPoints(id, Traces.straightTrack(seconds = 300))

        val unfinished = repository.unfinishedRun()
        assertEquals(id, unfinished?.id)
        assertEquals(301, repository.pointsFor(id).size)
    }

    @Test
    fun `deleting a run takes its points, splits and records with it`() = runTest {
        val id = recordRun()
        assertTrue(repository.pointsFor(id).isNotEmpty())

        repository.deleteRun(id)

        assertTrue(repository.pointsFor(id).isEmpty())
        assertTrue(repository.observeSplits(id).first().isEmpty())
        assertNull(repository.observePersonalRecord(1_000).first())
    }

    @Test
    fun `the fastest run holds the record`() = runTest {
        recordRun(seconds = 1000, speedMps = 3.0)   // 5:33/km
        val fast = recordRun(seconds = 800, speedMps = 3.75) // 4:26/km

        val record = repository.observePersonalRecord(1_000).first()
        assertEquals(fast, record!!.runId)
    }

    @Test
    fun `daily totals group runs by their local date`() = runTest {
        recordRun()
        val date = RunRepository.localDate(Traces.START_MS, java.time.ZoneId.systemDefault())
        val totals = repository.observeDailyTotals(date, date).first()
        assertEquals(1, totals.single().runCount)
        assertEquals(3030.0, totals.single().meters, 2.0)
    }

    @Test
    fun `summary bounds cover the whole track`() = runTest {
        val id = recordRun()
        val run = repository.observeRun(id).first()!!
        val points = repository.pointsFor(id)
        assertEquals(points.minOf { it.lat }, run.minLat, 1e-9)
        assertEquals(points.maxOf { it.lat }, run.maxLat, 1e-9)
    }
}

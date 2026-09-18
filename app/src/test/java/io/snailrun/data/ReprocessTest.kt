package io.snailrun.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.snailrun.data.db.SnailDatabase
import io.snailrun.data.repo.RunRepository
import io.snailrun.domain.fixtures.Traces
import io.snailrun.domain.geo.GeoDistance
import io.snailrun.domain.geo.TrackSmoother
import io.snailrun.data.repo.toEntity
import io.snailrun.domain.analysis.BestEffortFinder
import io.snailrun.domain.analysis.SplitCalculator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The promise the storage layer makes: raw positions are kept forever, every figure is
 * derived, and improving the filter brings old runs along without touching what the chip
 * said.
 */
@RunWith(RobolectricTestRunner::class)
class ReprocessTest {

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

    /**
     * A finished run written the way a build without the position filter wrote one:
     * distance summed straight from the raw fixes, splits derived from that, and no
     * filter version stamped on it.
     */
    private suspend fun legacyRun(): Long {
        val dao = db.runDao()
        val id = repository.startRun(Traces.START_MS)

        var distance = 0.0
        val points = Traces.noisyTrack(seconds = 900, speedMps = 3.0, noiseM = 4.0)
            .mapIndexed { i, point ->
                if (i > 0) {
                    val previous = Traces.noisyTrack(seconds = 900, speedMps = 3.0, noiseM = 4.0)[i - 1]
                    distance += GeoDistance.between(previous.lat, previous.lon, point.lat, point.lon)
                }
                point.copy(cumulativeDistanceM = distance)
            }
        repository.appendPoints(id, points)

        dao.completeRun(
            run = dao.runById(id)!!.copy(
                endedAtEpochMs = Traces.START_MS + 900_000L,
                distanceMeters = distance,
                movingTimeMs = 900_000L,
                avgPaceSecPerKm = 900_000L / 1000.0 / (distance / 1000.0),
                pointCount = points.size,
                status = "COMPLETE",
                smootherVersion = 0,
            ),
            splits = SplitCalculator.compute(points).map { it.toEntity(id) },
            efforts = BestEffortFinder.findAll(points).map { it.toEntity(id) },
        )
        return id
    }

    @Test
    fun `a run recorded before the filter is brought up to date on demand`() = runTest {
        val id = legacyRun()
        val before = repository.observeRun(id).first()!!
        // Raw noise integration reads a 2700 m run far longer than it was.
        assertTrue("stored ${before.distanceMeters} m", before.distanceMeters > 2_700 * 1.2)

        assertEquals(1, repository.reprocessOutdatedRuns())

        val after = repository.observeRun(id).first()!!
        assertEquals(2_700.0, after.distanceMeters, 2_700 * 0.04)
        assertEquals(TrackSmoother.VERSION, after.smootherVersion)
    }

    @Test
    fun `reprocessing leaves the raw positions exactly as the chip reported them`() = runTest {
        val id = legacyRun()
        val before = repository.pointsFor(id)

        repository.reprocessOutdatedRuns()

        val after = repository.pointsFor(id)
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (a, b) ->
            assertEquals(a.lat, b.lat, 0.0)
            assertEquals(a.lon, b.lon, 0.0)
            assertEquals(a.timestampMs, b.timestampMs)
        }
        // Only the derived column moved.
        assertTrue(before.last().cumulativeDistanceM > after.last().cumulativeDistanceM)
    }

    @Test
    fun `an up-to-date run is left alone`() = runTest {
        legacyRun()
        repository.reprocessOutdatedRuns()

        assertEquals(0, repository.reprocessOutdatedRuns())
    }

    @Test
    fun `reprocessing re-derives the splits and the records too`() = runTest {
        val id = legacyRun()
        val staleSplits = repository.observeSplits(id).first().size

        repository.reprocessOutdatedRuns()

        val splits = repository.observeSplits(id).first()
        // 2.7 km: two full kilometres and a tail, where the inflated track claimed more.
        assertEquals(2, splits.count { !it.isPartial })
        assertTrue(staleSplits > splits.size)
        assertEquals(id, repository.observePersonalRecord(1_000).first()!!.runId)
    }

    @Test
    fun `the smoothed view never changes what pointsFor returns`() = runTest {
        val id = legacyRun()

        val raw = repository.pointsFor(id)
        val smoothed = repository.smoothedPointsFor(id)

        assertEquals(raw.size, smoothed.size)
        assertTrue(
            "the filter moved nothing",
            raw.zip(smoothed).any { (a, b) -> a.lat != b.lat || a.lon != b.lon },
        )
    }
}

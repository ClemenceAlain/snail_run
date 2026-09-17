package io.snailrun.domain.geo

import io.snailrun.domain.model.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimplifyTest {

    @Test
    fun `a straight line collapses to its endpoints`() {
        val line = (0..20).map { LatLon(48.8560 + it * 0.0001, 2.3500) }
        assertEquals(2, Simplify.douglasPeucker(line, toleranceMeters = 1.0).size)
    }

    @Test
    fun `a corner survives simplification`() {
        val corner = listOf(
            LatLon(48.8560, 2.3500),
            LatLon(48.8565, 2.3500),
            LatLon(48.8570, 2.3500),
            LatLon(48.8570, 2.3510),  // the turn
            LatLon(48.8570, 2.3520),
        )
        val simplified = Simplify.douglasPeucker(corner, toleranceMeters = 5.0)
        assertTrue(simplified.contains(LatLon(48.8570, 2.3510)) || simplified.size >= 3)
    }

    @Test
    fun `a larger tolerance never yields more points`() {
        val wander = (0..100).map {
            LatLon(48.8560 + it * 0.0001, 2.3500 + (it % 3) * 0.00002)
        }
        val tight = Simplify.douglasPeucker(wander, 1.0).size
        val loose = Simplify.douglasPeucker(wander, 20.0).size
        assertTrue("tolerance 20 gave $loose points, tolerance 1 gave $tight", loose <= tight)
    }

    @Test
    fun `toAtMost respects its budget`() {
        val wander = (0..2000).map {
            LatLon(48.8560 + it * 0.00002, 2.3500 + kotlin.math.sin(it / 30.0) * 0.0005)
        }
        val thumbnail = Simplify.toAtMost(wander, maxPoints = 60)
        assertTrue("got ${thumbnail.size} points", thumbnail.size <= 60)
        assertEquals(wander.first(), thumbnail.first())
        assertEquals(wander.last(), thumbnail.last())
    }
}

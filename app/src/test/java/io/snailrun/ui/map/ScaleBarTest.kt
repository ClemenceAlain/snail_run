package io.snailrun.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scale bar's one piece of arithmetic. A bar labelled "1 km" that is not a kilometre
 * long is worse than no bar, and this is the only place that can go wrong.
 */
class ScaleBarTest {

    @Test
    fun `it rounds down to one, two or five`() {
        assertEquals(1.0, niceDistance(1.9), 1e-9)
        assertEquals(2.0, niceDistance(4.9), 1e-9)
        assertEquals(5.0, niceDistance(9.9), 1e-9)
        assertEquals(10.0, niceDistance(19.0), 1e-9)
        assertEquals(200.0, niceDistance(430.0), 1e-9)
        assertEquals(500.0, niceDistance(999.0), 1e-9)
        assertEquals(1_000.0, niceDistance(1_400.0), 1e-9)
    }

    @Test
    fun `an exact round number is itself`() {
        assertEquals(100.0, niceDistance(100.0), 1e-9)
        assertEquals(2_000.0, niceDistance(2_000.0), 1e-9)
    }

    @Test
    fun `it never claims more ground than the bar covers`() {
        // The bar is drawn at `metres / metresPerPixel` pixels wide, so a result longer
        // than the limit would draw a bar wider than the space it was given.
        generateSequence(1.05) { it * 1.17 }.takeWhile { it < 100_000 }.forEach { limit ->
            assertTrue("$limit", niceDistance(limit) <= limit)
        }
    }
}

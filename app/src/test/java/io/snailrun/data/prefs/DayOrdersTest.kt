package io.snailrun.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one piece of the coach that is written to disk, so the one piece that has to
 * survive meeting a file an older or newer version wrote.
 */
class DayOrdersTest {

    private val moved = listOf(0, 2, 3, 1, 4, 5, 6)

    @Test
    fun `a week survives the round trip`() {
        val orders = mapOf(20_353L to moved, 20_360L to listOf(6, 0, 1, 2, 3, 4, 5))
        assertEquals(orders, DayOrders.decode(DayOrders.encode(orders)))
    }

    @Test
    fun `nothing stored is nothing read`() {
        assertTrue(DayOrders.decode(null).isEmpty())
        assertTrue(DayOrders.decode("").isEmpty())
        assertTrue(DayOrders.encode(emptyMap()).isEmpty())
    }

    @Test
    fun `weeks are written in order so the same set writes the same string`() {
        val a = DayOrders.encode(mapOf(20_360L to moved, 20_353L to moved))
        val b = DayOrders.encode(mapOf(20_353L to moved, 20_360L to moved))
        assertEquals(a, b)
    }

    /**
     * Anything that is not a permutation of seven days is dropped rather than repaired.
     * A half-understood order would silently move somebody's sessions to days they never
     * chose, which is worse than the plan simply reverting to what the rules said.
     */
    @Test
    fun `an order that is not seven distinct days is dropped`() {
        assertTrue(DayOrders.decode("20353:0,1,2").isEmpty())
        assertTrue(DayOrders.decode("20353:0,0,1,2,3,4,5").isEmpty())
        assertTrue(DayOrders.decode("20353:0,1,2,3,4,5,6,7").isEmpty())
        assertTrue(DayOrders.decode("20353:").isEmpty())
    }

    @Test
    fun `a broken entry does not take the good ones with it`() {
        val decoded = DayOrders.decode("nonsense|20353:0,2,3,1,4,5,6|20360:oops")
        assertEquals(mapOf(20_353L to moved), decoded)
    }
}

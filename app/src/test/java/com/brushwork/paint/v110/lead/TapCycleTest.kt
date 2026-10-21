package com.brushwork.paint.v110.lead

import com.brushwork.paint.vector.select.TapCycle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.10 F0 (R1, J19; lead, frozen): the tap cycle counts same-spot taps spatially. Same spot =
 * within [TapCycle.SAME_SPOT_DP] (16 dp) of the previous tap with the same selection key and no
 * [TapCycle.reset] in between; any other tap starts again at 0.
 */
class TapCycleTest {

    @Test
    fun sameSpotTapsDeepenAndAnythingElseStartsAgain() {
        assertEquals(16f, TapCycle.SAME_SPOT_DP)
        val t = TapCycle()
        // The depth sequence: 0 (side), 1 (all sides), then v1.9's deeper list.
        assertEquals(listOf(0, 1, 2, 3), List(4) { t.next(100f, 100f, "k") })
        // Within 16 dp of the previous tap (the edge included) is the same spot; the previous tap is the anchor.
        assertEquals(4, t.next(116f, 100f, "k"))
        assertEquals(5, t.next(116f, 116f, "k"))
        assertEquals(6, t.next(124f, 122f, "k"))        // 10 dp away diagonally
        // A move of more than 16 dp restarts the cycle.
        assertEquals(0, t.next(124f, 138.5f, "k"))
        assertEquals(1, t.next(124f, 138.5f, "k"))

        // A new key (the selection changed) restarts it, even at the same spot.
        assertEquals(0, t.next(124f, 138.5f, "other"))
        assertEquals(1, t.next(124f, 138.5f, "other"))
        assertEquals(0, t.next(124f, 138.5f, null))
        assertEquals(1, t.next(124f, 138.5f, null))

        // reset() (a drag, pinch or handle gesture in between, a tool switch) restarts it.
        t.reset()
        assertEquals(0, t.next(124f, 138.5f, null))
        assertEquals(1, t.next(124f, 138.5f, null))
        t.reset()
        assertEquals(0, t.next(10f, 10f, "k"))

        // A fresh cycle starts at 0, whatever the first tap is.
        assertEquals(0, TapCycle().next(0f, 0f, null))
    }
}

package com.brushwork.paint.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7 item 10 (design §3.10; area I): [HistoryTapHub], the two- and three-finger taps over the
 * UI, on plain numbers (slop 36 px, 300 ms): which gestures it claims, when it takes and drops the
 * mark, when it asks the canvas to yield, and the one undo / redo at the end.
 */
class HistoryTapHubTest {

    private class Host(var undoOn: Boolean = true, var redoOn: Boolean = true) : HistoryTapHub.Host {
        var opened = 0
        var released = 0
        var yields = 0
        val taps = ArrayList<String>()
        override fun allowed(redo: Boolean) = if (redo) redoOn else undoOn
        override fun openMark() { opened++ }
        override fun releaseMark() { released++ }
        override fun yieldCanvas() { yields++ }
        override fun historyTap(redo: Boolean) { taps += if (redo) "redo" else "undo" }
    }

    private val slop = 36f
    private val host = Host()
    private val hub = HistoryTapHub(host, slop)

    /** One down event: Initial pass (returns whether it is consumed), the canvas' mark, Final pass. */
    private fun down(id: Long, x: Float, t: Long, canvas: Boolean = false, slot: Int = 0, finger: Boolean = true): Boolean {
        val consume = hub.down(slot, id, x, 100f, t, finger)
        if (canvas) hub.canvasDown(slot, id)
        hub.downsDone(t)
        return consume
    }

    @Test
    fun twoFingersOnTheUiUndoOnce() {
        assertFalse("one finger is not claimed", down(0, 100f, 1000))
        assertEquals("the mark is taken at the first down", 1, host.opened)
        assertTrue("a second finger claims the gesture", down(1, 300f, 1050))
        assertTrue(hub.claimed)
        hub.move(0, 0, 105f, 100f, 1100)
        assertTrue("claimed events are consumed", hub.up(0, 0, 1150))
        assertTrue(hub.up(0, 1, 1200))
        assertEquals(listOf("undo"), host.taps)
        assertEquals("one mark, released once", 1, host.released)
        assertEquals("no canvas finger: nothing to yield", 0, host.yields)
        assertFalse(hub.claimed)
        assertEquals(0, hub.pointersDown)
    }

    @Test
    fun threeFingersRedo() {
        down(0, 100f, 0)
        down(1, 200f, 20)
        down(2, 300f, 40)
        hub.up(0, 2, 100)
        hub.up(0, 1, 110)
        hub.up(0, 0, 120)
        assertEquals(listOf("redo"), host.taps)
        assertEquals(host.opened, host.released)
    }

    @Test
    fun aMoveOfTheSlopOrALateFingerIsNoTap() {
        down(0, 100f, 0)
        down(1, 300f, 40)
        hub.move(0, 1, 300f + slop, 100f, 60)
        assertEquals("the mark goes as soon as no tap can come of it", 1, host.released)
        hub.up(0, 0, 100)
        hub.up(0, 1, 110)
        assertTrue(host.taps.isEmpty())

        // A second finger after 300 ms: not claimed, the first finger's control keeps its touch.
        assertFalse(down(0, 100f, 1000))
        assertFalse(down(1, 300f, 1301))
        assertFalse(hub.claimed)
        assertFalse(hub.up(0, 0, 1310))
        hub.up(0, 1, 1320)
        assertTrue(host.taps.isEmpty())
        assertEquals(host.opened, host.released)

        // Lifted after 300 ms: claimed (consumed) but no tap.
        down(0, 100f, 2000)
        down(1, 300f, 2010)
        hub.up(0, 0, 2200)
        hub.up(0, 1, 2301)
        assertTrue(host.taps.isEmpty())
        assertEquals(host.opened, host.released)
    }

    @Test
    fun settingsOffNothing() {
        host.undoOn = false
        host.redoOn = false
        down(0, 100f, 0)
        assertFalse(down(1, 300f, 10))
        hub.up(0, 0, 50)
        hub.up(0, 1, 60)
        assertEquals("no mark at all", 0, host.opened)
        assertTrue(host.taps.isEmpty())

        // Redo only: two fingers are claimed (they may become three) but do not undo.
        host.redoOn = true
        down(0, 100f, 1000)
        assertTrue(down(1, 300f, 1010))
        hub.up(0, 0, 1050)
        hub.up(0, 1, 1060)
        assertTrue(host.taps.isEmpty())
        down(0, 100f, 2000)
        down(1, 200f, 2010)
        down(2, 300f, 2020)
        hub.up(0, 0, 2050); hub.up(0, 1, 2060); hub.up(0, 2, 2070)
        assertEquals(listOf("redo"), host.taps)
        assertEquals(host.opened, host.released)
    }

    @Test
    fun theCanvasAloneKeepsItsOwnTaps() {
        assertFalse(down(0, 100f, 0, canvas = true))
        assertEquals("taken before it is known where the finger is", 1, host.opened)
        assertEquals("and dropped once it is the canvas'", 1, host.released)
        assertFalse(down(1, 300f, 20, canvas = true))
        assertFalse(hub.claimed)
        assertFalse(hub.up(0, 0, 60))
        assertFalse(hub.up(0, 1, 70))
        assertTrue("the canvas undoes, not the hub", host.taps.isEmpty())
        assertEquals(0, host.yields)
        assertEquals(host.opened, host.released)
    }

    @Test
    fun aCanvasFingerAndAUiFingerUndoOnce() {
        // Canvas first: the UI finger claims at its Final pass, and the canvas yields once.
        down(0, 100f, 0, canvas = true)
        assertFalse("not known yet at the Initial pass", down(1, 300f, 20))
        assertTrue(hub.claimed)
        assertEquals(1, host.yields)
        hub.up(0, 0, 60)
        hub.up(0, 1, 70)
        assertEquals(listOf("undo"), host.taps)

        // UI first: the canvas finger is consumed before it reaches the canvas; it yields all the same.
        down(0, 100f, 1000)
        assertTrue(down(1, 300f, 1020, canvas = true))
        assertEquals(2, host.yields)
        hub.up(0, 1, 1060)
        hub.up(0, 0, 1070)
        assertEquals(listOf("undo", "undo"), host.taps)
        assertEquals(host.opened, host.released)
    }

    @Test
    fun windowsStylusFourFingersAndCancel() {
        // Two windows (the editor and a sheet's), the same pointer id: two fingers.
        down(0, 100f, 0, slot = 0)
        assertTrue(down(0, 100f, 10, slot = 1))
        hub.up(1, 0, 40)
        hub.up(0, 0, 50)
        assertEquals(listOf("undo"), host.taps)

        // A stylus is never a history tap.
        down(0, 100f, 1000)
        down(1, 300f, 1010, finger = false)
        hub.up(0, 0, 1040); hub.up(0, 1, 1050)
        assertEquals(1, host.taps.size)

        // Four fingers: no tap.
        for (i in 0L..3L) down(i, 100f * i, 2000 + i)
        for (i in 0L..3L) hub.up(0, i, 2050 + i)
        assertEquals(1, host.taps.size)

        // Cancelled: no tap, the mark released; a cancel of another window changes nothing.
        down(0, 100f, 3000)
        down(1, 300f, 3010)
        hub.cancel(5)
        assertEquals(2, hub.pointersDown)
        hub.cancel(0)
        assertEquals(0, hub.pointersDown)
        assertFalse(hub.claimed)
        assertEquals(1, host.taps.size)
        assertEquals(host.opened, host.released)
    }

    @Test
    fun aPointerEventCostsMicroseconds() {
        // Design §3.10: ≤ 0.02 ms per pointer event (here a move of two claimed fingers).
        val quiet = Host()
        val h = HistoryTapHub(quiet, slop)
        fun round(n: Int): Long {
            val t0 = System.nanoTime()
            for (r in 0 until n) {
                val t = r * 1000L
                h.down(0, 0, 100f, 100f, t, true); h.downsDone(t)
                h.down(0, 1, 300f, 100f, t + 1, true); h.downsDone(t + 1)
                for (k in 0 until 6) { h.move(0, 0, 101f, 100f, t + 2); h.move(0, 1, 301f, 100f, t + 2) }
                h.up(0, 0, t + 50); h.up(0, 1, t + 60)
            }
            return System.nanoTime() - t0
        }
        round(20_000)
        val n = 20_000
        val perEvent = round(n).toDouble() / (n * 18) / 1e6
        assertTrue("$perEvent ms per event", perEvent <= 0.02)
        assertEquals("every round is one two-finger tap", 20_000 + n, quiet.taps.size)
    }
}

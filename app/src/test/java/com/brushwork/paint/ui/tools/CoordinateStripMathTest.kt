package com.brushwork.paint.ui.tools

import com.brushwork.paint.core.LengthUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5 §4.6 / v1.6 §3.7.8 (G, JVM): the X / Y math. The screen reader's range is the canvas and a
 * quarter on each side (the value's own place included); a cell drag (the number is the slider)
 * moves the value by the finger's travel in document px (dx / zoom), a tenth of it in fine mode
 * (re-anchored, so the value never jumps), pulled onto detents at 0, the centre and the edge, else
 * onto multiples of the Length step while increments are on (detents win); and how values read.
 */
class CoordinateStripMathTest {

    @Test
    fun theRangeIsTheCanvasAndAQuarterOnEachSideWithTheValueIncluded() {
        assertEquals(-100f..500f, axisRange(120f, 400f))
        assertEquals(-900f..500f, axisRange(-900f, 400f))
        assertEquals(-100f..2000f, axisRange(2000f, 400f))
        assertEquals(-400f..800f, dragRange(120f, 400f))
        assertEquals(-900f..800f, dragRange(-900f, 400f))
    }

    @Test
    fun theValueFollowsTheFingersTravelInDocumentPixelsAndFineModeIsATenth() {
        // Zoom 2: one screen px is half a document px.
        val d = PillDrag(start = 200f, docPerPx = 0.5f, fineDistancePx = 96f, detents = emptyList(), detentPx = 16f, step = null, range = -400f..800f)
        d.down(100f)
        assertEquals("no movement, no change", 200f, d.move(100f, 0f), 1e-4f)
        assertEquals(210f, d.move(120f, 10f), 1e-4f)
        assertFalse(d.fine)
        // The finger goes far above the cell: fine from here on (no jump when it switches).
        assertEquals(210f, d.move(120f, -120f), 1e-4f)
        assertTrue(d.fine)
        assertEquals(211f, d.move(140f, -130f), 1e-4f)
        // Back near the cell: full speed again, from where it is.
        assertEquals(211f, d.move(140f, 20f), 1e-4f)
        assertFalse(d.fine)
        assertEquals(216f, d.move(150f, 0f), 1e-4f)
        // Clamped to the range.
        assertEquals(800f, d.move(5000f, 0f), 1e-4f)
        assertEquals(-400f, d.move(-5000f, 0f), 1e-4f)
        // Zoom 1/4: one screen px is 4 document px.
        val far = PillDrag(start = 0f, docPerPx = 4f, fineDistancePx = 96f, detents = emptyList(), detentPx = 16f, step = null, range = -400f..800f)
        far.down(0f)
        assertEquals(120f, far.move(30f, 0f), 1e-4f)
    }

    @Test
    fun detentsPullTheValueOntoZeroTheCentreAndTheEdge() {
        val detents = listOf(0f, 200f, 400f)
        // Zoom 1: 8 px of finger = 8 document px around each detent.
        val d = PillDrag(start = 150f, docPerPx = 1f, fineDistancePx = 96f, detents = detents, detentPx = 8f, step = null, range = -400f..800f)
        d.down(0f)
        assertEquals(150f, d.move(0f, 0f), 1e-4f)
        assertNull(d.detent)
        assertEquals(200f, d.move(44f, 0f), 1e-4f)
        assertEquals(200f, d.detent)
        assertEquals(210f, d.move(60f, 0f), 1e-4f)
        assertNull(d.detent)
        assertEquals(400f, d.move(255f, 0f), 1e-4f)
        assertEquals(400f, d.detent)
        // In fine mode the pull is a tenth as wide (raw 405 + 0.1 · 30 = 408 is too far).
        assertEquals(405f, d.move(255f, 200f), 1e-4f)
        assertEquals(408f, d.move(285f, 200f), 1e-4f)
        assertNull(d.detent)
        assertEquals(400f, d.move(205f, 200f), 1e-4f)
        assertEquals(400f, d.detent)
    }

    @Test
    fun withALengthStepTheAbsoluteValueLandsOnItsMultiplesAndDetentsWin() {
        val d = PillDrag(start = 37f, docPerPx = 1f, fineDistancePx = 96f, detents = listOf(0f, 205f, 410f), detentPx = 8f, step = 10f, range = -410f..820f)
        d.down(0f)
        assertEquals(40f, d.move(1f, 0f), 1e-4f)
        assertEquals(50f, d.move(9f, 0f), 1e-4f)
        assertEquals(60f, d.move(19f, 0f), 1e-4f)
        assertEquals(-30f, d.move(-64f, 0f), 1e-4f)
        // The centre of a 410 px canvas is no multiple of 10: the detent wins there.
        assertEquals(205f, d.move(166f, 0f), 1e-4f)
        assertEquals(205f, d.detent)
        assertEquals(220f, d.move(180f, 0f), 1e-4f)
        // Fine mode with a step: slower, still on multiples.
        assertEquals(220f, d.move(180f, 200f), 1e-4f)
        assertEquals(230f, d.move(280f, 200f), 1e-4f)
    }

    @Test
    fun valuesReadWithThousandsSeparatorsInPixelsAndTheUnitsDecimalsOtherwise() {
        assertEquals("1,234", formatCoordinate(1234f, LengthUnit.PX, 300.0))
        assertEquals("1,234.5", formatCoordinate(1234.46f, LengthUnit.PX, 300.0))
        assertEquals("−56", formatCoordinate(-56f, LengthUnit.PX, 300.0))
        assertEquals("25.4", formatCoordinate(300f, LengthUnit.MM, 300.0))
        assertEquals("1", formatCoordinate(300f, LengthUnit.IN, 300.0))
    }
}

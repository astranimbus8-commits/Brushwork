package com.brushwork.paint.ui.tools

import com.brushwork.paint.core.LengthUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5 §4.6 (A4, JVM): the X / Y strip's slider math — absolute over the canvas and a quarter on
 * each side (the value's own place included), jumping to the finger, a tenth of the movement in
 * fine mode (re-anchored, so the value never jumps), detents at 0, the centre and the edge — and
 * how values read.
 */
class CoordinateStripMathTest {

    @Test
    fun theRangeIsTheCanvasAndAQuarterOnEachSideWithTheValueIncluded() {
        assertEquals(-100f..500f, axisRange(120f, 400f))
        assertEquals(-900f..500f, axisRange(-900f, 400f))
        assertEquals(-100f..2000f, axisRange(2000f, 400f))
    }

    @Test
    fun aDragJumpsToTheFingerThenFollowsItAndFineModeIsATenth() {
        // A 300 px track over -100..500 (2 document px per track px).
        val d = AxisDrag(-100f..500f, widthPx = 300f, fineDistancePx = 96f, detents = emptyList(), detentPx = 16f)
        assertEquals(200f, d.down(150f), 1e-3f)
        assertEquals(240f, d.move(170f, 10f), 1e-3f)
        assertFalse(d.fine)
        // The finger goes far from the track: fine from here on (no jump when it switches).
        assertEquals(240f, d.move(170f, 120f), 1e-3f)
        assertTrue(d.fine)
        assertEquals(244f, d.move(190f, 130f), 1e-3f)
        // Back near the track: full speed again, from where it is.
        assertEquals(244f, d.move(190f, 20f), 1e-3f)
        assertFalse(d.fine)
        assertEquals(264f, d.move(200f, 0f), 1e-3f)
        // Clamped to the range.
        assertEquals(500f, d.move(2000f, 0f), 1e-3f)
        assertEquals(-100f, d.move(-2000f, 0f), 1e-3f)
        assertEquals(-100f, d.down(-50f), 1e-3f)
    }

    @Test
    fun detentsPullTheValueOntoZeroTheCentreAndTheEdge() {
        val detents = listOf(0f, 200f, 400f)
        val d = AxisDrag(-100f..500f, widthPx = 300f, fineDistancePx = 96f, detents = detents, detentPx = 8f)
        // 8 px of finger = 16 document px around each detent.
        assertEquals(200f, d.down(157f), 1e-3f)
        assertEquals(200f, d.detent)
        assertEquals(220f, d.move(160f, 0f), 1e-3f)
        assertNull(d.detent)
        assertEquals(400f, d.move(255f, 0f), 1e-3f)
        assertEquals(400f, d.detent)
        assertEquals(0f, d.move(52f, 0f), 1e-3f)
        // In fine mode the pull is a tenth as wide (the raw value here is 4).
        assertEquals(4f, d.move(52f, 200f), 1e-3f)
        assertNull(d.detent)
        assertEquals(2.6f, d.move(45f, 200f), 1e-3f)
        assertNull(d.detent)
        assertEquals(0f, d.move(39f, 200f), 1e-3f)
        assertEquals(0f, d.detent)
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

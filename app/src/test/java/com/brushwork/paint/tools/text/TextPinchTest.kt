package com.brushwork.paint.tools.text

import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Test

/** [TextItem.pinched] (pure JVM). */
class TextPinchTest {
    private val item = TextItem("Hi", TextSpec(sizePx = 40f, strokeWidthPx = 4f), cx = 100f, cy = 50f, rotationDeg = 10f)

    @Test
    fun scalesFontAndOutlineAroundTheFocus() {
        val out = item.pinched(Vec2(80f, 50f), Vec2.ZERO, 2f, 0f, maxSizePx = 1000f)
        assertEquals(80f, out.spec.sizePx, 1e-4f)
        assertEquals(8f, out.spec.strokeWidthPx, 1e-4f)
        // The center moves away from the focus by the same factor.
        assertEquals(120f, out.cx, 1e-3f)
        assertEquals(50f, out.cy, 1e-3f)
        assertEquals(10f, out.rotationDeg, 0f)
        assertEquals("Hi", out.text)
    }

    @Test
    fun turnsAboutTheFocusAndMoves() {
        val out = item.pinched(Vec2(100f, 0f), Vec2(5f, 0f), 1f, 90f, maxSizePx = 1000f)
        assertEquals(100f, out.rotationDeg, 1e-4f)
        // (100, 50) is 50 below the focus; a clockwise quarter turn (y down) puts it 50 left.
        assertEquals(55f, out.cx, 1e-3f)
        assertEquals(0f, out.cy, 1e-3f)
    }

    @Test
    fun sizeIsClampedAndTheCenterFollowsTheClampedScale() {
        val out = item.pinched(Vec2(80f, 50f), Vec2.ZERO, 10f, 0f, maxSizePx = 100f)
        assertEquals(100f, out.spec.sizePx, 1e-4f)
        assertEquals(10f, out.spec.strokeWidthPx, 1e-4f)
        assertEquals(130f, out.cx, 1e-3f) // factor 2.5, not 10
        val small = item.pinched(Vec2(80f, 50f), Vec2.ZERO, 0f, 0f, maxSizePx = 100f)
        assertEquals("a zero scale is ignored", 40f, small.spec.sizePx, 1e-4f)
        val tiny = item.pinched(Vec2(80f, 50f), Vec2.ZERO, 0.001f, 0f, maxSizePx = 100f)
        assertEquals(TextSpec.MIN_SIZE_PX, tiny.spec.sizePx, 1e-4f)
    }

    @Test
    fun rotationIsNormalized() {
        val out = item.copy(rotationDeg = 170f).pinched(Vec2(100f, 50f), Vec2.ZERO, 1f, 30f, 1000f)
        assertEquals(-160f, out.rotationDeg, 1e-3f)
    }
}

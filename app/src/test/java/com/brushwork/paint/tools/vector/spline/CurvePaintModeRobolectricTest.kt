package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurvePaint
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.onLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tap
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorCodec
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 (item 7, design §3.7): Stroke, Fill or Both for the curve tools. Fill alone keeps the
 * stroke kind it had (Fill then Both brings the brush back); "No stroke" is never a stroke kind
 * of its own; Fill needs 3 points; a fill-only path (as v1.6 writes it: no stroke, a fill)
 * reopens with Fill, a path with both with Both and its kind.
 */
@RunWith(RobolectricTestRunner::class)
class CurvePaintModeRobolectricTest {

    @Test
    fun fillKeepsTheStrokeKindAndBothBringsItBack() {
        val c = controller()
        val t = c.tool(ToolId.CURVE)
        assertEquals("v1.6 default: a brush line", CurvePaint.STROKE, t.paintMode)
        assertEquals(CurveStroke.BRUSH, t.settings.stroke)
        t.setPaintMode(CurvePaint.FILL)
        assertEquals(CurvePaint.FILL, t.paintMode)
        assertEquals(CurveStroke.NONE, t.settings.stroke)
        assertTrue(t.settings.fill)
        assertEquals(CurveStroke.BRUSH, t.settings.lastStroke)
        t.setPaintMode(CurvePaint.BOTH)
        assertEquals("the brush is back", CurveStroke.BRUSH, t.settings.stroke)
        assertTrue(t.settings.fill)

        t.setStrokeKind(CurveStroke.PLAIN)
        assertEquals(CurveStroke.PLAIN, t.settings.stroke)
        t.setStrokeKind(CurveStroke.NONE)
        assertEquals("No stroke is not a kind", CurveStroke.PLAIN, t.settings.stroke)
        t.setPaintMode(CurvePaint.FILL)
        t.setPaintMode(CurvePaint.STROKE)
        assertEquals("the plain line is back", CurveStroke.PLAIN, t.settings.stroke)
        assertFalse(t.settings.fill)
        assertEquals(CurvePaint.STROKE, t.paintMode)
    }

    @Test
    fun fillNeedsThreePoints() {
        val c = controller()
        val t = c.tool(ToolId.PATH)
        assertTrue("for the next path", t.fillPossible)
        c.tap(60f, 60f)
        assertFalse(t.fillPossible)
        c.tap(160f, 60f)
        assertFalse(t.fillPossible)
        c.tap(110f, 160f)
        assertTrue(t.fillPossible)
        t.discard()
    }

    @Test
    fun aFillOnlyPathReopensWithFillAndOneWithBothWithBoth() {
        val c = controller()
        val layer = c.activeLayer
        val t = c.tool(ToolId.PATH)
        t.setPaintMode(CurvePaint.FILL)
        for ((x, y) in listOf(60f to 60f, 220f to 50f, 260f to 200f, 90f to 230f)) c.tap(x, y)
        t.commit()
        val p = layer.vector!!.objects.single() as VPath
        assertNull("no stroke", p.stroke)
        assertNotNull(p.fill)
        // Nothing new is written: it is what v1.6 writes for a fill alone.
        val json = Json.encodeToString(VObject.serializer(), p)
        assertFalse(json.contains("lastStroke"))
        layer.vector = VectorCodec.decode(VectorCodec.encode(layer.vector!!))

        // Another look in the tool; the path brings its own when it reopens.
        t.setPaintMode(CurvePaint.STROKE)
        c.tap(onLine(p))
        assertTrue(t.isReopened)
        assertEquals(CurvePaint.FILL, t.paintMode)
        // Both, with the stroke kind the tool had before: one in-tool step, on Apply the path has both.
        t.setPaintMode(CurvePaint.BOTH)
        assertEquals(CurvePaint.BOTH, t.paintMode)
        assertTrue(t.settings.stroke != CurveStroke.NONE)
        t.commit()
        val q = layer.vector!!.objects.single() as VPath
        assertNotNull(q.stroke)
        assertNotNull(q.fill)
        t.setPaintMode(CurvePaint.FILL)
        c.tap(onLine(q))
        assertTrue(t.isReopened)
        assertEquals(CurvePaint.BOTH, t.paintMode)
        t.discard()
    }
}

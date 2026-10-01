package com.brushwork.paint.tools

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.ShapeBox
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.5 foundation (§4.7, §5.10 item 9): a two-finger gesture scales the object only when finger A
 * or finger B is on its box as drawn (4 dp grace, boxes under 44 dp enlarged); the midpoint never
 * counts. The five cases for the rule itself and for Transform (lifted and not), Text, Shape and
 * Shape lines. The view is unzoomed with density 1, so dp = document px.
 */
@RunWith(RobolectricTestRunner::class)
class PinchTargetingRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()

    private fun setup(w: Int = 800, h: Int = 600): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(w, h)) }
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()); it.viewTransform.density = 1f }
    }

    private fun identity() = ViewTransform().apply { set(Matrix()); density = 1f }

    private fun rect(l: Float, t: Float, r: Float, b: Float) = listOf(Vec2(l, t), Vec2(r, t), Vec2(r, b), Vec2(l, b))

    private val far = Vec2(5000f, 5000f)

    // ------------------------------------------------------------------ the rule

    @Test
    fun quadRuleFiveCases() {
        val t = identity()
        val box = rect(100f, 100f, 200f, 150f)
        // 1. both fingers 20 dp outside on opposite sides, midpoint inside -> view
        assertFalse(PinchTargeting.acceptsQuad(Vec2(80f, 125f), Vec2(220f, 125f), box, t))
        // 2. one finger inside, one far away -> object
        assertTrue(PinchTargeting.acceptsQuad(Vec2(150f, 125f), far, box, t))
        assertTrue(PinchTargeting.acceptsQuad(far, Vec2(150f, 125f), box, t))
        // 3. both inside -> object
        assertTrue(PinchTargeting.acceptsQuad(Vec2(120f, 110f), Vec2(180f, 140f), box, t))
        // Grace: 4 dp outside the edge still counts, 5 dp doesn't.
        assertTrue(PinchTargeting.acceptsQuad(Vec2(96f, 125f), far, box, t))
        assertFalse(PinchTargeting.acceptsQuad(Vec2(95f, 125f), far, box, t))
        // 4. a 10 dp object: enlarged to 44 dp around its centre (+ grace)
        val tiny = rect(300f, 300f, 310f, 310f)
        assertTrue("finger beside the tiny box, within 22 dp", PinchTargeting.acceptsQuad(Vec2(320f, 305f), far, tiny, t))
        assertTrue(PinchTargeting.acceptsQuad(Vec2(305f, 284f), far, tiny, t))
        assertFalse(PinchTargeting.acceptsQuad(Vec2(335f, 305f), far, tiny, t))
        // 5. a rotated quad: inside its bounding box but outside the quad -> view; inside the quad -> object
        val r = 50f * Math.sqrt(2.0).toFloat()
        val diamond = listOf(Vec2(500f, 500f - r), Vec2(500f + r, 500f), Vec2(500f, 500f + r), Vec2(500f - r, 500f))
        assertFalse(PinchTargeting.acceptsQuad(Vec2(440f, 440f), far, diamond, t))
        assertTrue(PinchTargeting.acceptsQuad(Vec2(500f, 440f), far, diamond, t))
        // Non-finite fingers never count.
        assertFalse(PinchTargeting.acceptsQuad(Vec2(Float.NaN, 0f), far, box, t))
    }

    @Test
    fun rectAndSegmentRules() {
        val t = identity()
        assertTrue(PinchTargeting.acceptsRect(Vec2(150f, 125f), far, RectF(100f, 100f, 200f, 150f), t))
        assertFalse(PinchTargeting.acceptsRect(Vec2(80f, 125f), Vec2(220f, 125f), RectF(100f, 100f, 200f, 150f), t))
        val p0 = Vec2(100f, 100f); val p1 = Vec2(300f, 100f)
        // Band = max(half width, 12 dp) + 4 dp grace.
        assertTrue(PinchTargeting.acceptsSegment(Vec2(200f, 116f), far, p0, p1, 2f, t))
        assertFalse(PinchTargeting.acceptsSegment(Vec2(200f, 117f), far, p0, p1, 2f, t))
        assertTrue("a thick line is grabbed on its whole width", PinchTargeting.acceptsSegment(Vec2(200f, 130f), far, p0, p1, 30f, t))
        assertFalse(PinchTargeting.acceptsSegment(Vec2(200f, 60f), Vec2(200f, 140f), p0, p1, 2f, t))
        // A 10 dp line is lengthened to 44 dp.
        assertTrue(PinchTargeting.acceptsSegment(Vec2(417f, 300f), far, Vec2(395f, 300f), Vec2(405f, 300f), 2f, t))
        assertFalse(PinchTargeting.acceptsSegment(Vec2(445f, 300f), far, Vec2(395f, 300f), Vec2(405f, 300f), 2f, t))
    }

    @Test
    fun theRuleIsMeasuredOnScreen() {
        // Zoomed in 2x with density 2: 1 dp = 2 screen px = 1 document px; zoomed out it shrinks.
        val t = ViewTransform().apply { set(Matrix().apply { setScale(2f, 2f) }); density = 2f }
        val tiny = rect(100f, 100f, 105f, 105f) // 10 screen px
        // Enlarged to 44 dp = 88 screen px = 44 doc px around the centre (102.5).
        assertTrue(PinchTargeting.acceptsQuad(Vec2(122f, 102.5f), far, tiny, t))
        assertFalse(PinchTargeting.acceptsQuad(Vec2(130f, 102.5f), far, tiny, t))
        val out = ViewTransform().apply { set(Matrix().apply { setScale(0.25f, 0.25f) }); density = 1f }
        // 100 doc px = 25 screen px: enlarged to 44 screen px = 176 doc px.
        val box = rect(0f, 0f, 100f, 100f)
        assertTrue(PinchTargeting.acceptsQuad(Vec2(130f, 50f), far, box, out))
        assertFalse(PinchTargeting.acceptsQuad(Vec2(160f, 50f), far, box, out))
    }

    // ------------------------------------------------------------------ tools

    private fun paint(c: EditorController, l: Float, t: Float, r: Float, b: Float) {
        val layer = c.activeLayer
        Canvas(layer.bitmap).drawRect(l, t, r, b, Paint().apply { color = 0xFFCC2200.toInt() })
        layer.markChanged()
    }

    /** Runs the five-case checks of an axis-aligned box [l, t, r, b] (at least 44 dp) against [start]. */
    private fun boxCases(name: String, l: Float, t: Float, r: Float, b: Float, start: (Vec2, Vec2, Vec2) -> Boolean, end: () -> Unit) {
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        fun check(case: String, expected: Boolean, a: Vec2, bb: Vec2) {
            val got = start((a + bb) / 2f, a, bb)
            if (got) end()
            assertEquals("$name: $case", expected, got)
        }
        check("1 fingers 20 dp outside on both sides", false, Vec2(l - 20f, cy), Vec2(r + 20f, cy))
        check("2 one finger inside", true, Vec2(cx, cy), far)
        check("2 the other finger inside", true, far, Vec2(cx + 3f, cy))
        check("3 both inside", true, Vec2(cx - 5f, cy), Vec2(cx + 5f, cy))
    }

    @Test
    fun transformLiftedFollowsTheRule() {
        val c = setup()
        paint(c, 300f, 200f, 500f, 300f)
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.start()
        assertNotNull(tool.transformState)
        boxCases("transform", 300f, 200f, 500f, 300f, tool::onTwoFingerStart) { tool.onTwoFingerEnd(cancelled = true) }
        // 5. turned 45°: a finger in the corner of its bounding box is beside it.
        tool.setRotation(45.0)
        val q = tool.transformState!!.corners()
        val (inBox, outside) = aabbCornerPoint(q)
        assertTrue(Geometry.pointInPolygon(inBox, q))
        assertFalse(tool.onTwoFingerStart(outside, outside, far))
        assertTrue(tool.onTwoFingerStart(inBox, inBox, far))
        tool.onTwoFingerEnd(cancelled = true)
        tool.discard()
    }

    @Test
    fun transformLiftedTinyBoxIsEnlarged() {
        val c = setup()
        paint(c, 100f, 100f, 110f, 110f)
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.start()
        assertTrue("4 finger beside a 10 dp box", tool.onTwoFingerStart(Vec2(120f, 105f), Vec2(120f, 105f), far))
        tool.onTwoFingerEnd(cancelled = true)
        assertFalse(tool.onTwoFingerStart(Vec2(140f, 105f), Vec2(140f, 105f), far))
        tool.discard()
    }

    @Test
    fun transformNotLiftedYetFollowsTheRule() {
        val c = setup()
        paint(c, 300f, 200f, 500f, 300f)
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        // (The automatic lift runs on the next main-loop turn; the pinch comes first here.)
        boxCases("transform (not lifted)", 300f, 200f, 500f, 300f, tool::onTwoFingerStart) {
            tool.onTwoFingerEnd(cancelled = true)
            assertNotNull("the content was lifted", tool.transformState)
            tool.discard()
        }
        assertNull(tool.transformState)
        // 4. small content bounds are enlarged too.
        c.activeLayer.bitmap.eraseColor(0)
        paint(c, 100f, 100f, 110f, 110f)
        assertTrue(tool.onTwoFingerStart(Vec2(120f, 105f), Vec2(120f, 105f), far))
        tool.onTwoFingerEnd(cancelled = true)
        tool.discard()
        assertFalse(tool.onTwoFingerStart(Vec2(140f, 105f), Vec2(140f, 105f), far))
        // With a selection its bounds decide.
        c.setSelection(Selection.fromPath(android.graphics.Path().apply { addRect(500f, 400f, 600f, 500f, android.graphics.Path.Direction.CW) }, 800, 600))
        tool.discard()
        assertFalse(tool.onTwoFingerStart(Vec2(550f, 450f), Vec2(480f, 450f), Vec2(620f, 450f)))
        assertTrue(tool.onTwoFingerStart(Vec2(550f, 450f), Vec2(550f, 450f), far))
        tool.onTwoFingerEnd(cancelled = true)
        tool.discard()
    }

    @Test
    fun textFollowsTheRule() {
        val c = setup()
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        tool.startTextAt(400f, 300f)
        tool.setText("Hello")
        tool.setSizePx(60f)
        val item = tool.item!!
        val block = tool.blockFor(item)
        assertTrue("a box larger than the minimum", block.width > 60f && block.height > 40f)
        val pad = 6f // BOX_PAD_DP at density 1
        val l = item.cx - block.width / 2f - pad; val r = item.cx + block.width / 2f + pad
        val t = item.cy - block.height / 2f - pad; val b = item.cy + block.height / 2f + pad
        boxCases("text", l, t, r, b, tool::onTwoFingerStart) { tool.onTwoFingerEnd(cancelled = true) }
        // 5. turned 45°.
        tool.setRotation(45f)
        val turned = tool.item!!
        val q = turned.corners(block.width, block.height, pad)
        val (inBox, outside) = aabbCornerPoint(q)
        assertFalse(tool.onTwoFingerStart(outside, outside, far))
        assertTrue(tool.onTwoFingerStart(inBox, inBox, far))
        tool.onTwoFingerEnd(cancelled = true)
        // 4. a tiny text: enlarged to 44 dp around its centre.
        tool.setRotation(0f)
        tool.setText(".")
        tool.setSizePx(8f)
        val tiny = tool.item!!
        assertTrue(tool.onTwoFingerStart(Vec2(tiny.cx + 20f, tiny.cy), Vec2(tiny.cx + 20f, tiny.cy), far))
        tool.onTwoFingerEnd(cancelled = true)
        assertFalse(tool.onTwoFingerStart(Vec2(tiny.cx + 40f, tiny.cy), Vec2(tiny.cx + 40f, tiny.cy), far))
        tool.discard()
    }

    @Test
    fun shapeFollowsTheRule() {
        val c = setup()
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update { it.copy(type = ShapeType.RECTANGLE) }
        assertTrue(tool.ensurePending())
        tool.place(ShapeBox(400f, 300f, 200f, 100f, 0f))
        boxCases("shape", 300f, 250f, 500f, 350f, tool::onTwoFingerStart) { tool.onTwoFingerEnd(cancelled = true) }
        // 5. turned 45°.
        tool.place(ShapeBox(400f, 300f, 100f, 100f, 45f))
        val q = tool.box!!.corners()
        val (inBox, outside) = aabbCornerPoint(q)
        assertFalse(tool.onTwoFingerStart(outside, outside, far))
        assertTrue(tool.onTwoFingerStart(inBox, inBox, far))
        tool.onTwoFingerEnd(cancelled = true)
        // 4. a 10 dp shape.
        tool.place(ShapeBox(400f, 300f, 10f, 10f, 0f))
        assertTrue(tool.onTwoFingerStart(Vec2(415f, 300f), Vec2(415f, 300f), far))
        tool.onTwoFingerEnd(cancelled = true)
        assertFalse(tool.onTwoFingerStart(Vec2(430f, 300f), Vec2(430f, 300f), far))
        tool.discard()
    }

    @Test
    fun shapeLineFollowsTheRule() {
        val c = setup()
        c.brush = c.brush.copy(size = 4f)
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update { it.copy(type = ShapeType.LINE) }
        assertTrue(tool.ensurePending())
        tool.place(ShapeBox.line(Vec2(300f, 300f), Vec2(500f, 300f)))
        fun check(case: String, expected: Boolean, a: Vec2, b: Vec2) {
            val got = tool.onTwoFingerStart((a + b) / 2f, a, b)
            if (got) tool.onTwoFingerEnd(cancelled = true)
            assertEquals("line: $case", expected, got)
        }
        // The band is 12 dp (+ 4 dp grace) on each side of a thin line.
        check("1 fingers 20 dp beyond the band on both sides", false, Vec2(400f, 264f), Vec2(400f, 336f))
        check("2 one finger on the line", true, Vec2(400f, 305f), far)
        check("3 both on the line", true, Vec2(350f, 300f), Vec2(450f, 302f))
        // 5. a diagonal line: inside its bounding box but away from the line -> view.
        tool.place(ShapeBox.line(Vec2(300f, 300f), Vec2(500f, 500f)))
        check("5 beside a diagonal line", false, Vec2(320f, 480f), far)
        check("5 on a diagonal line", true, Vec2(402f, 398f), far)
        // 4. a 10 dp line is lengthened to 44 dp.
        tool.place(ShapeBox.line(Vec2(395f, 300f), Vec2(405f, 300f)))
        check("4 beyond the end of a tiny line", true, Vec2(417f, 300f), far)
        check("4 too far beyond it", false, Vec2(445f, 300f), far)
        tool.discard()
    }

    /** A point inside the quad [q] and one inside its bounding box but clearly outside it. */
    private fun aabbCornerPoint(q: List<Vec2>): Pair<Vec2, Vec2> {
        val minX = q.minOf { it.x }; val minY = q.minOf { it.y }
        val maxX = q.maxOf { it.x }; val maxY = q.maxOf { it.y }
        val candidates = listOf(Vec2(minX + 3f, minY + 3f), Vec2(maxX - 3f, minY + 3f), Vec2(maxX - 3f, maxY - 3f), Vec2(minX + 3f, maxY - 3f))
        val outside = candidates.first { p ->
            !Geometry.pointInPolygon(p, q) && q.indices.all { i -> Geometry.distanceToSegment(p, q[i], q[(i + 1) % q.size]) > 8f }
        }
        val center = Vec2(q.sumOf { it.x.toDouble() }.toFloat() / q.size, q.sumOf { it.y.toDouble() }.toFloat() / q.size)
        return center to outside
    }
}

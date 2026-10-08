package com.brushwork.paint.tools.array

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 (item 3, §3.3 a; area E): the Array tool's canvas work. A handle drag previews through
 * the render override and commits ONE "Edit array" step on release; a cancelled drag or a rolled
 * back preview records nothing and leaves no override; "Draw guide" and "Use a path" give the
 * Curve mode its guide in one step each.
 */
@RunWith(RobolectricTestRunner::class)
class ArrayToolRobolectricTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private val red = 0xFFDD2211.toInt()

    /** A raster array of a 40 × 40 red square at (20, 30), count 3 side by side; the Array tool is current. */
    private fun rasterArray(): Pair<EditorController, Layer> {
        val c = Smoke.controller(app)
        val src = c.doc.layers[1]
        Canvas(src.bitmap).drawRect(20f, 30f, 60f, 70f, Paint().apply { color = red })
        c.selectLayer(src)
        c.setSelection(Selection.fromPath(Path().apply { addRect(10f, 20f, 70f, 80f, Path.Direction.CW) }, c.doc.width, c.doc.height, antiAlias = false), recordUndo = false)
        assertTrue(c.arrayFromSelection())
        Smoke.pump(20)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        return c to c.activeLayer
    }

    private fun tool(c: EditorController) = c.tools.getValue(ToolId.ARRAY) as ArrayTool

    private fun drag(t: ArrayTool, from: Vec2, to: Vec2, finish: Boolean = true) {
        t.onDown(ToolPoint(from.x, from.y))
        for (i in 1..4) {
            val p = from.lerp(to, i / 4f)
            t.onMove(ToolPoint(p.x, p.y))
        }
        if (finish) t.onUp(ToolPoint(to.x, to.y))
    }

    @Test
    fun aLineArrowDragIsOneEditStepAndMovesTheCopies() {
        val (c, layer) = rasterArray()
        val t = tool(c)
        assertTrue("the sheet opens with the tool", t.sheetOpen)
        val steps = c.undoManager.undoCount
        // Copy 1's centre is (80, 50); drag it to (90, 60).
        drag(t, Vec2(80f, 50f), Vec2(90f, 60f), finish = false)
        assertNotNull("the preview draws the layer", c.renderOverride)
        assertSame(layer, c.renderOverride!!.layer)
        assertEquals(10f, t.previewSpec!!.constantX, 1e-3f)
        assertEquals(steps, c.undoManager.undoCount)
        t.onUp(ToolPoint(90f, 60f))
        Smoke.pump(20)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(ArrayLabels.EDIT, c.undoManager.undoLabel)
        assertNull(c.renderOverride)
        assertNull(t.previewSpec)
        val spec = layer.array!!.spec
        assertEquals(10f, spec.constantX, 1e-3f)
        assertEquals(10f, spec.constantY, 1e-3f)
        // Copy 1 now covers (70..110, 40..80), copy 2 (120..160, 50..90).
        assertEquals(red, layer.bitmap.getPixel(105, 75))
        assertEquals(red, layer.bitmap.getPixel(155, 85))
        assertEquals(0, layer.bitmap.getPixel(65, 35))
        // A touch away from the handles does nothing.
        drag(t, Vec2(300f, 250f), Vec2(320f, 260f))
        assertEquals(steps + 1, c.undoManager.undoCount)
        c.undo()
        assertEquals(0f, layer.array!!.spec.constantX, 1e-3f)
        Smoke.assertQuiet(c, "line arrow")
    }

    @Test
    fun aCancelledDragOrARolledBackPreviewRecordsNothing() {
        val (c, layer) = rasterArray()
        val t = tool(c)
        val steps = c.undoManager.undoCount
        drag(t, Vec2(80f, 50f), Vec2(130f, 90f), finish = false)
        assertNotNull(c.renderOverride)
        t.onCancel()
        assertNull(c.renderOverride)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(ArraySpec().constantX, layer.array!!.spec.constantX)
        // A slider moving when a second finger lands: the history tap rolls the preview back.
        val mark = t.historyMark()
        t.preview(layer.array!!.spec.copy(count = 7))
        assertNotNull(c.renderOverride)
        t.rollbackHistory(mark)
        assertNull(c.renderOverride)
        t.commitPreview()
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(3, layer.array!!.spec.count)
        // A slider released: one step.
        t.preview(layer.array!!.spec.copy(count = 6))
        t.preview(layer.array!!.spec.copy(count = 7))
        t.commitPreview()
        Smoke.pump(20)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(7, layer.array!!.spec.count)
        assertNull(c.renderOverride)
        Smoke.assertQuiet(c, "rolled back")
    }

    @Test
    fun drawGuideAndUseAPathGiveTheCurveModeItsGuide() {
        val (c, layer) = rasterArray()
        val t = tool(c)
        val steps = c.undoManager.undoCount
        t.startGuideInput(ArrayTool.GuideInput.DRAW)
        t.onDown(ToolPoint(30f, 200f))
        for (i in 1..200) t.onMove(ToolPoint(30f + i * 1.3f, 200f + 40f * kotlin.math.sin(i / 20.0).toFloat()))
        t.onUp(ToolPoint(290f, 200f))
        Smoke.pump(20)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(ArrayLabels.EDIT, c.undoManager.undoLabel)
        val drawn = layer.array!!.spec
        assertEquals(ArrayMode.CURVE, drawn.mode)
        val anchors = drawn.guide!!.anchors
        assertTrue("${anchors.size} anchors", anchors.size in 2..ArraySpec.MAX_GUIDE_ANCHORS)
        assertEquals(30f, anchors.first().x, 1e-3f)
        assertEquals(ArrayTool.GuideInput.NONE, t.guideInput)

        // A path on another layer, tapped on its edge: its first subpath is copied.
        val vl = Layer(c.doc.newLayerId(), "Paths", com.brushwork.paint.engine.BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height))
        val path = VPath(
            0, subpaths = listOf(VSubpath(listOf(VAnchor(50f, 250f), VAnchor(200f, 120f), VAnchor(350f, 250f)))),
            stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 4f),
        )
        vl.vector = VectorContent.EMPTY.plus(listOf(path)).first
        assertTrue(c.structure.insert(vl, label = "Test"))
        c.selectLayer(layer)
        val before = c.undoManager.undoCount
        t.startGuideInput(ArrayTool.GuideInput.PICK)
        t.onDown(ToolPoint(350f, 250f))
        t.onUp(ToolPoint(350f, 250f))
        Smoke.pump(20)
        assertEquals(before + 1, c.undoManager.undoCount)
        val picked = layer.array!!.spec.guide!!
        assertEquals(listOf(50f, 200f, 350f), picked.anchors.map { it.x })
        assertTrue(picked.anchors.all { it.sharp && it.outX != null && it.inX != null })
        // Copied, not linked.
        vl.vector = VectorContent.EMPTY
        assertEquals(picked, layer.array!!.spec.guide)
        Smoke.assertQuiet(c, "guides")
    }

    @Test
    fun theGuideFitKeepsAtMostSixtyFourAnchors() {
        val pts = List(2000) { i -> Vec2(i * 0.5f, 100f * kotlin.math.sin(i / 37.0).toFloat()) }
        val g = GuideEditor.fitStroke(pts)!!
        assertTrue(g.anchors.size in 2..ArraySpec.MAX_GUIDE_ANCHORS)
        assertNull(GuideEditor.fitStroke(listOf(Vec2(5f, 5f), Vec2(5f, 5f))))
        // A long path is refitted, a short one copied with its shape.
        val long = VPath(0, subpaths = listOf(VSubpath(List(300) { i -> VAnchor(i * 2f, if (i % 2 == 0) 0f else 30f) })), polyline = true)
        assertTrue(GuideEditor.fromPath(long)!!.anchors.size <= ArraySpec.MAX_GUIDE_ANCHORS)
        val fill = VPath(0, subpaths = listOf(VSubpath(listOf(VAnchor(0f, 0f), VAnchor(40f, 0f), VAnchor(40f, 40f)), closed = true)), fill = VPaint.Solid(-1))
        assertTrue(GuideEditor.fromPath(fill)!!.closed)
    }
}

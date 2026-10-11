package com.brushwork.paint.tools.vector

import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.render.VectorLayerRenderer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 F (design §3.8): a cog with a hole stroked with a brush paints BOTH outlines (the teeth
 * and the hole, a stroke each) in ONE undo step: into the active layer, into a shape layer of its
 * own, and again when that shape is edited. On a vector layer the object is rendered from its
 * data (the renderer's replay; the live stroke is not kept, since it would be the teeth alone);
 * that replay paints every contour (the teeth, then the hole, seed + index), so both show there too.
 */
@RunWith(RobolectricTestRunner::class)
class CogBrushOutlineTest {
    private val rig = CogRig()

    @After
    fun close() = rig.close()

    // The cog drawn by CogRig.drawCog: centre (160, 140), 200 px. Depth 0.2: the hole's radius
    // is hole × 80 px; at hole 0.5 a hole vertex lies at (200, 140).
    private val cx = 160
    private val cy = 140
    private val cog = CogParams(teeth = 9, hole = 0.5f)

    private fun brushCog(s: ShapeSettings) = s.copy(style = ShapeStyle.STROKE, strokeWith = ShapeStroke.BRUSH)

    @Test
    fun bothOutlinesArePaintedInOneStep() {
        // 1. Into the active layer (Editable off).
        run {
            val c = rig.controller()
            val tool = rig.cogTool(c, cog, editable = false, more = ::brushCog)
            rig.drawCog(c)
            val layer = c.doc.activeLayer
            val steps = c.undoManager.undoCount
            tool.commit()
            assertEquals("ONE step", steps + 1, c.undoManager.undoCount)
            assertNotEquals("the hole's line", 0, rig.alphaAt(layer.bitmap, cx + 40, cy))
            assertNotEquals("the first tooth's tip", 0, rig.alphaAt(layer.bitmap, cx, cy - 100 + 1))
            assertEquals("inside the hole", 0, rig.alphaAt(layer.bitmap, cx, cy))
            c.undo()
            assertEquals(0, rig.alphaAt(layer.bitmap, cx + 40, cy))
        }
        // 2. Into a shape layer of its own, then edited.
        run {
            val c = rig.controller()
            val tool = rig.cogTool(c, cog, more = ::brushCog)
            rig.drawCog(c)
            val steps = c.undoManager.undoCount
            tool.commit()
            assertEquals("ONE step", steps + 1, c.undoManager.undoCount)
            val layer = c.doc.activeLayer
            assertTrue(layer.isShapeLayer)
            assertNotEquals("the hole's line", 0, rig.alphaAt(layer.bitmap, cx + 40, cy))
            assertNotEquals("the teeth", 0, rig.alphaAt(layer.bitmap, cx, cy - 100 + 1))
            // Edited: a smaller hole (radius 0.25 × 80 = 20 px); both outlines repainted.
            assertTrue(tool.editLayer(layer))
            tool.update { it.copy(cogHole = 0.25f) }
            tool.commit()
            assertEquals(steps + 2, c.undoManager.undoCount)
            assertEquals(0, rig.alphaAt(layer.bitmap, cx + 40, cy))
            assertNotEquals("the new hole's line", 0, rig.alphaAt(layer.bitmap, cx + 20, cy))
            assertNotEquals("the teeth", 0, rig.alphaAt(layer.bitmap, cx, cy - 100 + 1))
        }
    }

    @Test
    fun onAVectorLayerTheObjectIsRenderedFromItsData() {
        val c = rig.controller(vector = true)
        val tool = rig.cogTool(c, cog, more = ::brushCog)
        rig.drawCog(c)
        tool.commit()
        val layer = c.doc.activeLayer
        val o = layer.vector!!.objects.single() as VShape
        assertEquals(cog, o.shape.cogParams)
        // Data and pixels together (I1): the layer shows what the renderer draws for the object.
        val b = BitmapUtils.createLayerBitmap(rig.w, rig.h)
        VectorLayerRenderer.render(Canvas(b), layer.vector!!, Rect(0, 0, rig.w, rig.h), tips = TipCache(), document = Rect(0, 0, rig.w, rig.h))
        assertArrayEquals(rig.pixels(b), rig.pixels(layer.bitmap))
        assertNotEquals("the teeth", 0, rig.alphaAt(layer.bitmap, cx, cy - 100 + 1))
        // VectorLayerRenderer.shapeBrushReplays replays every contour (seed + index): the hole's line too.
        assertNotEquals("the hole's line", 0, rig.alphaAt(layer.bitmap, cx + 40, cy))
        assertEquals("inside the hole", 0, rig.alphaAt(layer.bitmap, cx, cy))
    }
}

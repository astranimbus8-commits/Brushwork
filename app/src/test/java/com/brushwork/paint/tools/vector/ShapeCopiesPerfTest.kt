package com.brushwork.paint.tools.vector

import android.graphics.Canvas
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.vector.VShape
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 F review (design §3.1 budgets, "Vector layer redraw, 32 shape copies, box drag"): a shape
 * open on a vector layer with a 32-way rotation ruler's copies is moved by a finger; each frame
 * (the move, then the document and its overlays drawn as the canvas draws them, 512 × 512) takes
 * at most 33 ms on a desktop (PerfBudget scales the budget on CI), the average of a batch after a
 * warm-up. The drag ends in ONE step with the 32 copies kept.
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class ShapeCopiesPerfTest {
    private val rig = CogRig(512, 512)

    @After
    fun close() = rig.close()

    @Test
    fun aBoxDragWith32CopiesRedrawsWithin33MsAFrame() {
        val c = rig.controller(vector = true)
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.ROTATION, divisions = SymmetrySettings.MAX_DIVISIONS))
        val tool = rig.cogTool(c) { it.copy(type = ShapeType.RECTANGLE) }
        // The box x 280..380, y 80..180 (centre (330, 130)); the copies turn about (256, 256).
        rig.drag(c, 280f to 80f, 330f to 130f, 380f to 180f)
        assertEquals("32 instances", 32, tool.pendingObject()!!.copies.count)
        val layer = c.doc.activeLayer
        val out = BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height)
        val canvas = Canvas(out)
        var x = 330f
        fun frame(dx: Float) {
            x += dx
            c.pointerMove(ToolPoint(x, 130f))
            out.eraseColor(0)
            c.compositor.drawDocument(canvas, null, target = null)
            c.drawOverlays(canvas, 0f)
        }
        // A finger on the shape's middle moves it (back and forth by 1 px a frame).
        c.pointerDown(ToolPoint(x, 130f))
        repeat(20) { frame(if (it % 2 == 0) 1f else -1f) }
        PerfBudget.measuredWithin(PerfBudget.ms(33.0), what = "32 copies box drag frame") {
            val n = 30
            val t0 = System.nanoTime()
            repeat(n) { frame(if (it % 2 == 0) 1f else -1f) }
            (System.nanoTime() - t0) / 1e6 / n
        }
        frame(10f)
        c.pointerUp(ToolPoint(x, 130f))
        assertEquals("the shape moved with the finger", 340f, tool.pendingObject()!!.box.cx, 0.5f)
        val steps = c.undoManager.undoCount
        tool.commit()
        c.vectors.flushPending()
        assertEquals("ONE step", steps + 1, c.undoManager.undoCount)
        val o = layer.vector!!.objects.single() as VShape
        assertEquals(32, o.shape.copies.count)
        assertTrue("drawn", rig.alphaAt(layer.bitmap, 340, 130) == 255)
    }
}

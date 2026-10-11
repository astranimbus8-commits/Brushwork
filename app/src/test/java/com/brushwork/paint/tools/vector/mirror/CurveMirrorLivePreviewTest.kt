package com.brushwork.paint.tools.vector.mirror

import android.graphics.Canvas
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.mirror.CurveMirrorSupport.alphaNear
import com.brushwork.paint.tools.vector.mirror.CurveMirrorSupport.drawPath
import com.brushwork.paint.tools.vector.mirror.CurveMirrorSupport.inside
import com.brushwork.paint.tools.vector.mirror.CurveMirrorSupport.map
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.H
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.W
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.pixels
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.plainLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 (item 1a, design §3.1): while a path with copies is open every copy is drawn live: a point
 * drag redraws each copy on the same frame (no wait, no idle); MirrorLabels18.REMOVE is ONE
 * in-tool step.
 */
@RunWith(RobolectricTestRunner::class)
class CurveMirrorLivePreviewTest {
    private val rotation = SymmetrySettings(type = SymmetryType.ROTATION, divisions = 4)

    @Test
    fun aPointDragRedrawsEveryCopyOnTheSameFrame() {
        for (vector in listOf(false, true)) for (id in listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)) {
            val c = controller(vector = vector)
            c.updateSymmetry(rotation)
            val t = c.tool(id)
            t.plainLine()
            c.drawPath()
            val maps = t.copyMapsNow
            assertEquals(4, maps.size)
            val p2 = t.pointAt(2)
            val to = Vec2(p2.x + 20f, p2.y + 30f)
            c.pointerDown(ToolPoint(p2.x, p2.y))
            c.pointerMove(ToolPoint(p2.x + 10f, p2.y + 15f))
            c.pointerMove(ToolPoint(to.x, to.y))
            // The same frame: the preview drawn now has the point and each copy where it went.
            assertTrue("$id / vector=$vector: the copies are previewed", t.copiesShown)
            val shown = BitmapUtils.createLayerBitmap(W, H)
            assertTrue(c.renderOverride!!.drawContent(Canvas(shown)))
            for (m in maps) {
                val q = map(m, to)!!
                if (!inside(q)) continue
                assertTrue("$id / vector=$vector: copy drawn at $q", alphaNear(shown, q) > 0)
                val old = map(m, p2)!!
                assertEquals("$id / vector=$vector: nothing left at the old place $old", 0, alphaNear(shown, old, 1))
            }
            c.pointerUp(ToolPoint(to.x, to.y))
            t.discard()
            assertFalse(t.copiesShown)
            assertTrue("nothing painted", pixels(c.activeLayer.bitmap).all { it == 0 })
        }
    }

    @Test
    fun removeMirrorCopiesIsOneInToolStep() {
        val c = controller(vector = false)
        c.updateSymmetry(rotation)
        val t = c.tool(ToolId.CURVE)
        t.plainLine()
        c.drawPath()
        val maps = t.copyMapsNow
        val probe = map(maps[2], t.pointAt(1))!!
        assertTrue(t.hasCopies)
        assertTrue(t.removeCopies())
        assertFalse(t.hasCopies)
        assertFalse(t.copiesShown)
        assertTrue("the path itself is still open", t.pointCount == 3)
        assertTrue(t.undoStep())
        assertTrue("one step brings them back", t.hasCopies)
        assertEquals(4, t.copyMapsNow.size)
        assertTrue(t.copiesShown)
        assertTrue(t.redoStep())
        assertFalse(t.hasCopies)
        // ✓ paints the path alone; no step of the controller's until then.
        assertEquals(0, c.undoManager.undoCount)
        t.commit()
        assertEquals(0, alphaNear(c.activeLayer.bitmap, probe))
        assertTrue(alphaNear(c.activeLayer.bitmap, CurveMirrorSupport.points[1]) > 0)
    }

    @Test
    fun undoingTheFirstPointForgetsTheMaps() {
        val c = controller(vector = true)
        c.updateSymmetry(rotation)
        val t = c.tool(ToolId.CURVE)
        t.plainLine()
        c.drawPath(CurveMirrorSupport.points.take(1))
        assertTrue(t.hasCopies)
        assertTrue(t.undoStep())
        assertEquals(0, t.pointCount)
        assertFalse(t.hasCopies)
        // The next first point takes the ruler as it is then.
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.MIRROR))
        c.drawPath(CurveMirrorSupport.points.take(1))
        assertEquals(2, t.copyMapsNow.size)
        t.discard()
    }
}

package com.brushwork.paint.tools.vector.mirror

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.mirror.CurveMirrorSupport.drawPath
import com.brushwork.paint.tools.vector.mirror.CurveMirrorSupport.first
import com.brushwork.paint.tools.vector.mirror.CurveMirrorSupport.perspective
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.plainLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VectorOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 (item 1a, design §3.1, D2): a vector path drawn with the Perspective array ruler is stored
 * as N plain paths (the path and one per copy, each mapped with its homography), added in ONE
 * step; the hint that says so shows once per session.
 */
@RunWith(RobolectricTestRunner::class)
class CurvePerspectiveBakeTest {
    @Test
    fun aPerspectivePathBecomesPlainPathsInOneStep() {
        for (id in listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)) {
            val c = controller(vector = true)
            c.updateSymmetry(perspective)
            val layer = c.activeLayer
            val t = c.tool(id)
            t.plainLine()
            c.drawPath()
            val f = t.first()
            val maps = ObjectCopies.mapsAt(c, f.x, f.y)
            assertTrue(ObjectCopies.isPerspective(maps))
            val source = t.buildVPath()!!
            assertTrue("$id: nothing to store as live copies", source.copies.isEmpty)
            t.commit()
            val objs = layer.vector!!.objects
            assertEquals("$id: the path and one per copy", maps.size, objs.size)
            assertTrue(objs.all { it is VPath && it.copies.isEmpty })
            assertEquals("$id: the first is the path itself", VectorOps.bounds(source), VectorOps.bounds(objs[0]))
            for (k in 1 until objs.size) {
                val b = VectorOps.bounds(objs[k])
                assertNotEquals("$id: copy $k lies elsewhere", VectorOps.bounds(objs[0]), b)
            }
            assertEquals("$id: one step", 1, c.undoManager.undoCount)
            c.undo()
            assertTrue(layer.vector!!.objects.isEmpty())
        }
    }

    @Test
    fun theHintShowsOncePerSession() {
        val c = controller(vector = true)
        c.updateSymmetry(perspective)
        val t = c.tool(ToolId.CURVE)
        t.plainLine()
        c.drawPath()
        c.message = null
        t.commit()
        assertEquals(MirrorLabels18.PERSPECTIVE_BAKED, c.message)
        c.message = null
        c.drawPath(CurveMirrorSupport.points.map { Vec2(it.x + 10f, it.y + 30f) })
        t.commit()
        assertNull("once", c.message)
        assertTrue(c.activeLayer.vector!!.objects.size > 2)
    }

    /** Once per editor session, not once per tool: after the Curve tool showed it, Polyline and Path show nothing. */
    @Test
    fun theHintShowsOncePerEditorAcrossTheCurveTools() {
        val c = controller(vector = true)
        c.updateSymmetry(perspective)
        for ((k, id) in listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH).withIndex()) {
            val t = c.tool(id)
            t.plainLine()
            c.drawPath()
            c.message = null
            t.commit()
            assertTrue("$id: baked", c.activeLayer.vector!!.objects.size > 1)
            if (k == 0) assertEquals(MirrorLabels18.PERSPECTIVE_BAKED, c.message) else assertNull("$id: shown already", c.message)
            // Undone, so the next tool's taps start a new path (the hint is no document state).
            c.undo()
            assertTrue(c.activeLayer.vector!!.objects.isEmpty())
        }
    }

    @Test
    fun aBrushLineIsBakedAndDrawnByTheLayerToo() {
        val c = controller(vector = true)
        c.updateSymmetry(perspective)
        val t = c.tool(ToolId.CURVE)
        t.update { it.copy(stroke = CurveStroke.BRUSH) }
        c.drawPath()
        val f = t.first()
        val n = ObjectCopies.mapsAt(c, f.x, f.y).size
        t.flushPreview()
        t.commit()
        val objs = c.activeLayer.vector!!.objects
        assertEquals(n, objs.size)
        assertTrue(objs.all { (it as VPath).stroke?.kind == VStrokeKind.BRUSH && it.copies.isEmpty })
        assertEquals(1, c.undoManager.undoCount)
        assertTrue("no live stroke is left", !t.brushLive)
    }
}

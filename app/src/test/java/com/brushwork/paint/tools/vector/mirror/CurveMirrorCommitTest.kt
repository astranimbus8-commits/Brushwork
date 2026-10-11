package com.brushwork.paint.tools.vector.mirror

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.mirror.CurveMirrorSupport.affineRulers
import com.brushwork.paint.tools.vector.mirror.CurveMirrorSupport.alphaNear
import com.brushwork.paint.tools.vector.mirror.CurveMirrorSupport.drawPath
import com.brushwork.paint.tools.vector.mirror.CurveMirrorSupport.first
import com.brushwork.paint.tools.vector.mirror.CurveMirrorSupport.inside
import com.brushwork.paint.tools.vector.mirror.CurveMirrorSupport.map
import com.brushwork.paint.tools.vector.mirror.CurveMirrorSupport.rulers
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.onLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.pixels
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.plainLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.render
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 (item 1a, design §3.1): ✓ of a Curve, Polyline or Path drawn with a symmetry ruler paints
 * (raster layer) or stores (vector layer) the path and every copy as ONE undo step, for each
 * ruler kind; the maps are the ones at the path's first point; a translucent raster path's
 * copies are one group, so where they overlap they are not darker.
 */
@RunWith(RobolectricTestRunner::class)
class CurveMirrorCommitTest {
    private val tools = listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)

    @Test
    fun aRasterPathPaintsEveryCopyInOneStep() {
        for ((name, ruler) in rulers) for (id in tools) {
            val c = controller(vector = false)
            c.updateSymmetry(ruler)
            val layer = c.activeLayer
            val t = c.tool(id)
            t.plainLine()
            c.drawPath()
            val f = t.first()
            val maps = ObjectCopies.mapsAt(c, f.x, f.y)
            assertTrue("$name: the ruler gives copies", maps.size >= 2)
            assertTrue("$name / $id: the open path has copies", t.hasCopies)
            val probe = onLine(t.buildVPath()!!)
            t.commit()
            assertEquals("$name / $id: one step", 1, c.undoManager.undoCount)
            var seen = 0
            for (m in maps) {
                val q = map(m, probe) ?: continue
                if (!inside(q)) continue
                assertTrue("$name / $id: painted at $q", alphaNear(layer.bitmap, q) > 0)
                seen++
            }
            assertTrue("$name / $id: copies were checked ($seen)", seen >= 2)
            c.undo()
            assertTrue("$name / $id: one undo takes every copy", pixels(layer.bitmap).all { it == 0 })
        }
    }

    @Test
    fun aVectorPathStoresItsCopiesAsOneObjectInOneStep() {
        for ((name, ruler) in affineRulers) for (id in tools) {
            val c = controller(vector = true)
            c.updateSymmetry(ruler)
            val layer = c.activeLayer
            val t = c.tool(id)
            t.plainLine()
            c.drawPath()
            val f = t.first()
            val maps = ObjectCopies.mapsAt(c, f.x, f.y)
            t.commit()
            val p = layer.vector!!.objects.single() as VPath
            assertEquals("$name / $id: the maps at the first point", CopyMaps.of(maps), p.copies)
            assertEquals("$name / $id: one step", 1, c.undoManager.undoCount)
            val shown = render(layer.vector!!)
            val probe = onLine(p)
            var seen = 0
            for (m in maps) {
                val q = map(m, probe) ?: continue
                if (!inside(q)) continue
                assertTrue("$name / $id: the layer draws the copy at $q", shown[q.y.toInt() * layer.bitmap.width + q.x.toInt()] ushr 24 > 0 || near(shown, layer.bitmap.width, q))
                seen++
            }
            assertTrue(seen >= 2)
            c.undo()
            assertTrue("$name / $id: one undo", layer.vector!!.objects.isEmpty())
        }
    }

    private fun near(px: IntArray, w: Int, q: Vec2): Boolean {
        for (dy in -2..2) for (dx in -2..2) {
            val x = q.x.toInt() + dx
            val y = q.y.toInt() + dy
            if (x < 0 || y < 0 || x >= w || y * w + x >= px.size) continue
            if (px[y * w + x] ushr 24 > 0) return true
        }
        return false
    }

    @Test
    fun theMapsAreTheOnesAtTheFirstPoint() {
        val c = controller(vector = true)
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.ROTATION, divisions = 4))
        val t = c.tool(ToolId.CURVE)
        t.plainLine()
        c.drawPath(CurveMirrorSupport.points.take(1))
        val f = t.first()
        val maps = ObjectCopies.mapsAt(c, f.x, f.y)
        // A ruler changed while the path is open does not change its copies.
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.MIRROR))
        c.drawPath(CurveMirrorSupport.points.drop(1))
        assertEquals(4, t.copyMapsNow.size)
        t.commit()
        assertEquals(CopyMaps.of(maps), (c.activeLayer.vector!!.objects.single() as VPath).copies)
        // Without a ruler a path has none.
        c.updateSymmetry(SymmetrySettings())
        c.drawPath(CurveMirrorSupport.points.map { Vec2(it.x, it.y + 120f) })
        assertTrue(!t.hasCopies)
        t.commit()
        assertTrue((c.activeLayer.vector!!.objects.last() as VPath).copies.isEmpty)
    }

    @Test
    fun aTranslucentRasterPathIsNotDarkerWhereItsCopiesOverlap() {
        fun paint(mirror: Boolean): Int {
            val c = controller(vector = false)
            c.color = 0x80203080.toInt()
            if (mirror) c.updateSymmetry(SymmetrySettings(type = SymmetryType.MIRROR))
            val t = c.tool(ToolId.POLYLINE)
            t.plainLine()
            // A line across the vertical axis (x = 200): its mirror copy lies on it.
            c.drawPath(listOf(Vec2(150f, 150f), Vec2(250f, 150f)))
            assertEquals(mirror, t.hasCopies)
            t.commit()
            assertEquals(1, c.undoManager.undoCount)
            return c.activeLayer.bitmap.getPixel(200, 150) ushr 24
        }
        val single = paint(false)
        val both = paint(true)
        assertTrue("painted ($single)", single > 0x60)
        assertEquals("as one translucent group", single.toFloat(), both.toFloat(), 3f)
    }

    @Test
    fun aBrushLineOnARasterLayerPaintsItsCopiesInTheSameStep() {
        val c = controller(vector = false)
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.MIRROR))
        val layer = c.activeLayer
        val t = c.tool(ToolId.CURVE)
        t.update { it.copy(stroke = CurveStroke.BRUSH) }
        c.drawPath()
        val probe = onLine(t.buildVPath()!!)
        t.flushPreview()
        t.commit()
        assertEquals(1, c.undoManager.undoCount)
        assertTrue("the line", alphaNear(layer.bitmap, probe) > 0)
        assertTrue("its mirror copy", alphaNear(layer.bitmap, Vec2(400f - probe.x, probe.y)) > 0)
        c.undo()
        assertTrue(pixels(layer.bitmap).all { it == 0 })
    }
}

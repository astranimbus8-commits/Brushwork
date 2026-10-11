package com.brushwork.paint.tools.vector

import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.CogLabels18
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.render.VectorLayerRenderer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 F (design §3.8): "Turn into path" of a cog with a hole gives ONE compound plain path (its
 * teeth and its hole, no spline: the Path tool can't edit it as points) as one undo step, with
 * the hint "The hole makes a compound path" ([CogLabels18.COMPOUND_HINT]); the hole stays
 * empty, a brush line paints both contours. A cog without a hole becomes a Path-tool path as
 * every other shape does, without the hint.
 */
@RunWith(RobolectricTestRunner::class)
class CogTurnIntoPathTest {
    private val rig = CogRig()

    @After
    fun close() = rig.close()

    private fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(rig.w, rig.h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, rig.w, rig.h), tips = TipCache(), document = Rect(0, 0, rig.w, rig.h))
        return rig.pixels(b)
    }

    // The cog drawn by CogRig.drawCog: centre (160, 140), 200 px; root radius 80 px at depth 0.2.
    private val cx = 160
    private val cy = 140

    @Test
    fun aShapeLayerCogWithAHoleBecomesOneCompoundPath() {
        val c = rig.controller()
        val tool = rig.cogTool(c, CogParams(teeth = 10, hole = 0.4f))
        rig.drawCog(c)
        tool.commit()
        val layer = c.doc.activeLayer
        assertTrue(layer.isShapeLayer)
        val data = layer.shapeData
        val steps = c.undoManager.undoCount
        assertTrue(tool.editLayer(layer))
        c.message = null
        assertTrue(tool.turnIntoPath())
        assertEquals("ONE step", steps + 1, c.undoManager.undoCount)
        assertEquals(HistoryLabels.TURN_INTO_PATH, c.undoManager.undoLabel)
        assertEquals(CogLabels18.COMPOUND_HINT, c.message)
        assertTrue(layer.isVectorLayer)
        val path = layer.vector!!.objects.single() as VPath
        assertNull("no spline: not editable as points", path.spline)
        assertEquals("the teeth and the hole", 2, path.subpaths.size)
        assertTrue(path.subpaths.all { it.closed })
        assertNotNull(path.fill)
        // The hole is empty, the ring is filled; data and pixels together.
        assertEquals(0, rig.alphaAt(layer.bitmap, cx, cy))
        assertEquals(255, rig.alphaAt(layer.bitmap, cx + 55, cy))
        assertArrayEquals(render(layer.vector!!), rig.pixels(layer.bitmap))
        assertEquals(ToolId.PATH, c.activeToolId)
        // ONE undo: the cog's shape layer back, byte for byte.
        c.undo()
        assertEquals(data, layer.shapeData)
    }

    @Test
    fun aCogObjectWithAHoleIsReplacedInPlaceAndItsBrushPaintsBothContours() {
        val c = rig.controller(vector = true)
        val tool = rig.cogTool(c, CogParams(teeth = 8, depth = 0.25f, hole = 0.5f)) { it.copy(style = ShapeStyle.STROKE, strokeWith = ShapeStroke.BRUSH) }
        rig.drawCog(c)
        tool.commit()
        val layer = c.doc.activeLayer
        val shape = layer.vector!!.objects.single() as VShape
        c.message = null
        assertTrue(tool.editShapeObject(layer, shape.id))
        assertTrue(tool.turnIntoPath())
        c.vectors.flushPending()
        assertEquals(CogLabels18.COMPOUND_HINT, c.message)
        val path = layer.vector!!.objects.single() as VPath
        assertEquals("same id", shape.id, path.id)
        assertNull(path.spline)
        assertEquals(2, path.subpaths.size)
        assertEquals(VStrokeKind.BRUSH, path.stroke!!.kind)
        assertEquals("the brush keeps the object's grain", shape.seed, path.stroke!!.seed)
        // The path's brush runs along both contours: on the hole's edge (radius 0.5 × 75 px) too.
        val holeR = (0.5f * 0.75f * 100f).toInt()
        val pixels = render(layer.vector!!)
        assertNotEquals("the hole's line", 0, pixels[cy * rig.w + cx + holeR] ushr 24)
        assertEquals("inside the hole", 0, pixels[cy * rig.w + cx] ushr 24)
        assertArrayEquals(pixels, rig.pixels(layer.bitmap))
    }

    @Test
    fun aCogWithoutAHoleBecomesAPathToolPath() {
        val c = rig.controller()
        val tool = rig.cogTool(c, CogParams(teeth = 6))
        rig.drawCog(c)
        tool.commit()
        val layer = c.doc.activeLayer
        assertTrue(tool.editLayer(layer))
        c.message = null
        assertTrue(tool.turnIntoPath())
        assertTrue(c.message != CogLabels18.COMPOUND_HINT)
        val path = layer.vector!!.objects.single() as VPath
        assertNotNull("a Path tool path", path.spline)
        assertEquals(1, path.subpaths.size)
        // Every vertex of the teeth is a sharp control point.
        assertEquals(CogGeometry.vertexCount(CogParams(teeth = 6)), path.spline!!.points.size)
        assertTrue(path.spline.points.all { it.sharp })
    }
}
